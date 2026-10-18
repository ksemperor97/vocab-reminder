import Foundation
@preconcurrency import UserNotifications

@MainActor
package final class MacNotificationScheduler: NSObject, UNUserNotificationCenterDelegate {
    package static let shared = MacNotificationScheduler()

    // UNUserNotificationCenter는 앱 번들이 없는 프로세스(`swift run` 직접 실행)에서
    // 접근하는 순간 크래시한다 (bundleProxyForCurrentProcess is nil).
    // 번들이 없으면 알림 기능만 끄고 앱은 계속 동작하게 한다. (QC-H1 수정)
    private static var hasAppBundle: Bool { Bundle.main.bundleIdentifier != nil }
    private static let cardIdentifierPrefix = "vocab-reminder.card."
    private static let feedbackHoldDefaultsKey = "notification.feedbackHold.v1"
    private static let promptReminderDefaultsKey = "notification.promptReminder.v1"
    private static let suppressedRecommendationsDefaultsKey = "notification.suppressedRecommendations.v1"
    private static let unansweredReminderInterval: TimeInterval = 4 * 60 * 60
    private static let maxUnansweredRemindersPerDay = 2

    private let center: UNUserNotificationCenter?
    private let defaults: UserDefaults
    private let identifierPrefix = MacNotificationScheduler.cardIdentifierPrefix
    private var feedbackHold: FeedbackHoldState?
    private var rescheduleGeneration = 0
    private var quietBoundaryTask: Task<Void, Never>?
    private var unansweredReminderTask: Task<Void, Never>?

    private override init() {
        defaults = .standard
        center = Self.hasAppBundle ? UNUserNotificationCenter.current() : nil
        feedbackHold = Self.loadFeedbackHold(defaults: .standard)
        super.init()
        center?.delegate = self
        registerCategories()
    }

    package func requestAuthorization() {
        registerCategories()
        guard let center else { return }
        center.requestAuthorization(options: [.alert, .sound]) { granted, error in
            if let error {
                Task { @MainActor in
                    LocalVocabularyStore.shared.notificationWarning = "알림이 꺼져 있습니다"
                    LocalVocabularyStore.shared.message = "알림 권한 확인 실패: \(error.localizedDescription)"
                }
            } else if !granted {
                Task { @MainActor in
                    LocalVocabularyStore.shared.notificationWarning = "알림이 꺼져 있습니다"
                    LocalVocabularyStore.shared.message = "알림이 꺼져 있습니다"
                }
            } else {
                Task { @MainActor in
                    LocalVocabularyStore.shared.notificationWarning = ""
                }
            }
        }
    }

    package func authorizationDiagnosticLabel() async -> String {
        guard let center else { return "번들 앱 실행에서 확인 가능" }
        let status = await center.notificationSettings().authorizationStatus
        switch status {
        case .authorized:
            return "허용됨"
        case .provisional:
            return "임시 허용"
        case .ephemeral:
            return "일시 허용"
        case .denied:
            return "차단됨"
        case .notDetermined:
            return "아직 선택하지 않음"
        @unknown default:
            return "알 수 없음"
        }
    }

    package func reschedule(
        cards: [VocabularyCard],
        settings: UserSettingsData,
        processedCardIdsToday: Set<String> = []
    ) {
        guard center != nil else { return }
        rescheduleGeneration &+= 1
        let generation = rescheduleGeneration
        let now = Date()
        scheduleQuietBoundaryRefresh(settings: settings, now: now)

        let suppressed = activeRecommendationSuppressions(now: now)
        let eligibleCards = cards.filter { suppressed[$0.id] == nil }
        let targetCard = MacNotificationPlan.scheduledCards(
            cards: eligibleCards,
            settings: settings,
            processedCardIdsToday: processedCardIdsToday
        ).first
        let hold = feedbackHold
        let isQuiet = Self.isQuietHours(now, settings: settings)
        let promptState = hold == nil
            ? updatePromptReminderState(targetCard: targetCard, settings: settings, now: now)
            : nil
        unansweredReminderTask?.cancel()

        Task { @MainActor [weak self] in
            guard let self, let center = self.center else { return }
            let requests = await center.pendingNotificationRequests()
            guard self.rescheduleGeneration == generation else { return }
            let ownedPending = requests.map(\.identifier).filter { $0.hasPrefix(self.identifierPrefix) }
            if !ownedPending.isEmpty {
                center.removePendingNotificationRequests(withIdentifiers: ownedPending)
            }

            let notifications = await center.deliveredNotifications()
            guard self.rescheduleGeneration == generation else { return }
            let deliveredIdentifiers = notifications
                .map(\.request.identifier)
                .filter { $0.hasPrefix(self.identifierPrefix) }

            if hold == nil,
               let targetCard,
               let promptState,
               let deliveredFailsafe = notifications.first(where: {
                   $0.request.identifier == Self.failsafeIdentifier(cardId: targetCard.id)
               }) {
                let recoveredAt = max(
                    deliveredFailsafe.date,
                    Self.allowedDeliveryDate(now, settings: settings)
                )
                let recovered = Self.recordingUnansweredReminder(
                    in: promptState,
                    at: recoveredAt,
                    settings: settings
                )
                Self.savePromptReminderState(recovered, defaults: self.defaults)
                self.removeNotificationFamily(cardId: targetCard.id, center: center)
                let deliveryDate = Self.allowedDeliveryDate(now, settings: settings)
                let recoveredContent = self.promptContent(card: targetCard, isReminder: true)
                // The fail-safe already alerted while the app was not running.
                // Restore the single base slot without producing a second sound.
                recoveredContent.sound = nil
                center.add(UNNotificationRequest(
                    identifier: Self.notificationIdentifier(cardId: targetCard.id),
                    content: recoveredContent,
                    trigger: Self.trigger(deliveryDate: deliveryDate, now: now)
                )) { error in
                    if let error {
                        Task { @MainActor in
                            LocalVocabularyStore.shared.message = "재알림 복구 실패: \(error.localizedDescription)"
                        }
                    }
                }
                self.scheduleUnansweredReminder(
                    card: targetCard,
                    state: recovered,
                    settings: settings,
                    now: now
                )
                return
            }

            let keptDelivered = isQuiet && hold == nil
                ? Set<String>()
                : self.allowedDeliveredIdentifiers(
                    deliveredIdentifiers,
                    hold: hold,
                    targetCard: targetCard,
                    promptState: promptState,
                    now: now
                )
            let staleDelivered = deliveredIdentifiers.filter { !keptDelivered.contains($0) }
            if !staleDelivered.isEmpty {
                center.removeDeliveredNotifications(withIdentifiers: staleDelivered)
            }

            if let hold {
                self.unansweredReminderTask?.cancel()
                guard let card = cards.first(where: { $0.id == hold.cardId }) else {
                    self.clearQueueHold()
                    LocalVocabularyStore.shared.refreshNotifications()
                    return
                }
                if !deliveredIdentifiers.contains(hold.identifier) {
                    self.addFeedbackRequest(hold: hold, card: card, now: now)
                }
                return
            }

            guard let targetCard, let promptState else {
                self.clearPromptReminderState()
                return
            }
            let nextReminderAt = Self.nextUnansweredReminderDate(
                state: promptState,
                settings: settings,
                now: now
            )
            if nextReminderAt <= now.addingTimeInterval(1), promptState.firstPromptAt <= now {
                // Avoid an ordinary prompt followed immediately by a second
                // reminder alert when an app restart discovers an overdue slot.
                self.scheduleUnansweredReminder(
                    card: targetCard,
                    state: promptState,
                    settings: settings,
                    now: now
                )
                return
            }
            self.addPromptRequest(
                card: targetCard,
                state: promptState,
                now: now,
                deliveredIdentifiers: Set(deliveredIdentifiers),
                suppressImmediateDelivery: isQuiet
            )
            self.scheduleUnansweredReminder(
                card: targetCard,
                state: promptState,
                settings: settings,
                now: now
            )
        }
    }

    package func cancel(card: VocabularyCard) {
        guard let center else { return }
        removeNotificationFamily(cardId: card.id, center: center)
        if Self.loadPromptReminderState(defaults: defaults)?.cardId == card.id {
            clearPromptReminderState()
        }
    }

    private func registerCategories() {
        let start = UNNotificationAction(
            identifier: MacNotificationAction.start,
            title: "시작하기",
            options: []
        )
        let known = UNNotificationAction(
            identifier: MacNotificationAction.known,
            title: "알아요",
            options: []
        )
        let dismiss = UNNotificationAction(
            identifier: MacNotificationAction.dismiss,
            title: "관심 없음",
            options: [.destructive]
        )
        let remembered = UNNotificationAction(
            identifier: MacNotificationAction.remembered,
            title: "외웠다",
            options: []
        )
        let forgotten = UNNotificationAction(
            identifier: MacNotificationAction.forgotten,
            title: "모르겠음",
            options: []
        )
        let unavailable = UNNotificationAction(
            identifier: MacNotificationAction.unavailable,
            title: "나중에",
            options: []
        )
        let acknowledge = UNNotificationAction(
            identifier: MacNotificationAction.feedbackAcknowledge,
            title: "확인",
            options: []
        )
        let recommendation = UNNotificationCategory(
            identifier: MacNotificationAction.recommendationCategory,
            actions: [start, known, dismiss],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )
        let review = UNNotificationCategory(
            identifier: MacNotificationAction.reviewCategory,
            actions: [remembered, forgotten, unavailable],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )
        let feedback = UNNotificationCategory(
            identifier: MacNotificationAction.feedbackCategory,
            actions: [acknowledge],
            intentIdentifiers: [],
            options: [.customDismissAction]
        )
        center?.setNotificationCategories([recommendation, review, feedback])
    }

    @MainActor
    @discardableResult
    package static func handleAction(_ action: String, cardId: String, store: LocalVocabularyStore = .shared) -> VocabularyCard? {
        switch action {
        case MacNotificationAction.start:
            store.markRecommendationStarted(cardId: cardId)
            return store.cards.first { $0.id == cardId }
        case MacNotificationAction.known:
            store.softDeleteRecommendation(cardId: cardId, action: "known")
            return store.cards.first { $0.id == cardId }
        case MacNotificationAction.dismiss:
            store.softDeleteRecommendation(cardId: cardId, action: "dismissed")
            return store.cards.first { $0.id == cardId }
        case MacNotificationAction.remembered:
            return store.review(cardId: cardId, response: .remembered)
        case MacNotificationAction.forgotten:
            return store.review(cardId: cardId, response: .forgotten)
        case MacNotificationAction.unavailable:
            return store.review(cardId: cardId, response: .unavailable)
        default:
            return nil
        }
    }

    package static func feedbackContent(for action: String, card: VocabularyCard) -> (title: String, body: String)? {
        switch action {
        case MacNotificationAction.start:
            return (
                title: "\(card.displayWord) · 복습 시작",
                body: meaningExampleBody(card)
            )
        case MacNotificationAction.remembered:
            return (
                title: "\(card.displayWord) ✓",
                body: "\(card.meaningKo) · 다음 복습: \(nextReviewLabel(card.dueAt))"
            )
        case MacNotificationAction.forgotten:
            return (
                title: card.displayWord,
                body: meaningExampleBody(card)
            )
        default:
            return nil
        }
    }

    private static func meaningExampleBody(_ card: VocabularyCard) -> String {
        var lines = ["뜻: \(card.meaningKo)"]
        if !card.exampleSentence.isEmpty {
            lines.append("예문: \(card.exampleSentence)")
        }
        return lines.joined(separator: "\n")
    }

    private static func nextReviewLabel(_ dueAt: Date) -> String {
        let calendar = Calendar.current
        let days = calendar.dateComponents(
            [.day],
            from: calendar.startOfDay(for: Date()),
            to: calendar.startOfDay(for: dueAt)
        ).day ?? 0
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"

        if days < 0 { return "지금" }
        if days == 0 { return "오늘 \(formatter.string(from: dueAt))" }
        if days == 1 { return "내일 \(formatter.string(from: dueAt))" }
        return "\(days)일 뒤"
    }

    package static func shouldAcknowledgeFeedback(action: String, category: String) -> Bool {
        category == MacNotificationAction.feedbackCategory &&
            (
                action == MacNotificationAction.feedbackAcknowledge ||
                    action == UNNotificationDismissActionIdentifier ||
                    action == UNNotificationDefaultActionIdentifier
            )
    }

    package static func producesFeedback(action: String) -> Bool {
        action == MacNotificationAction.start ||
            action == MacNotificationAction.remembered ||
            action == MacNotificationAction.forgotten
    }

    package static func resolvedPromptAction(action: String, category: String) -> String {
        guard action == UNNotificationDismissActionIdentifier else { return action }
        if category == MacNotificationAction.reviewCategory {
            return MacNotificationAction.unavailable
        }
        if category == MacNotificationAction.recommendationCategory {
            return MacNotificationAction.advanceRecommendation
        }
        return action
    }

    package static func shouldHandlePromptAction(
        action: String,
        card: VocabularyCard?,
        promptState: PromptReminderState?,
        feedbackHold: FeedbackHoldState?,
        now: Date
    ) -> Bool {
        guard feedbackHold == nil,
              let card,
              promptState?.cardId == card.id,
              card.deletedAt == nil,
              card.status != .mastered,
              card.status != .suspended,
              !card.meaningKo.isEmpty else { return false }

        let recommendationActions: Set<String> = [
            MacNotificationAction.start,
            MacNotificationAction.known,
            MacNotificationAction.dismiss,
            MacNotificationAction.advanceRecommendation
        ]
        if recommendationActions.contains(action) {
            return card.wordbook == .recommended && card.recommendationStartedAt == nil
        }

        let reviewActions: Set<String> = [
            MacNotificationAction.remembered,
            MacNotificationAction.forgotten,
            MacNotificationAction.unavailable
        ]
        return reviewActions.contains(action) &&
            !card.isUnstartedRecommendation &&
            card.dueAt <= now.addingTimeInterval(5)
    }

    private func beginQueueHold(for action: String, cardId: String) {
        guard Self.producesFeedback(action: action) else { return }
        // Persist the phase and identifier before the store mutation. Every
        // mutation saves and reschedules, so doing this afterwards allows an
        // older callback to briefly publish the next prompt over the meaning.
        rescheduleGeneration &+= 1
        let hold = FeedbackHoldState(
            phase: "feedback",
            action: action,
            cardId: cardId,
            identifier: Self.notificationIdentifier(cardId: cardId)
        )
        feedbackHold = hold
        Self.saveFeedbackHold(hold, defaults: defaults)
        clearPromptReminderState()
    }

    private func clearQueueHold() {
        rescheduleGeneration &+= 1
        feedbackHold = nil
        Self.saveFeedbackHold(nil, defaults: defaults)
    }

    private func acknowledgeFeedback(cardId: String) {
        if let center {
            removeNotificationFamily(cardId: feedbackHold?.cardId ?? cardId, center: center)
        }
        clearQueueHold()
        LocalVocabularyStore.shared.refreshNotifications()
    }

    private func showFeedbackNotification(for action: String, card: VocabularyCard) {
        guard let center,
              let hold = feedbackHold,
              hold.action == action,
              hold.cardId == card.id else { return }
        removeNotificationFamily(cardId: card.id, center: center)
        addFeedbackRequest(
            hold: hold,
            card: card,
            now: Date()
        )
    }

    private func addFeedbackRequest(
        hold: FeedbackHoldState,
        card: VocabularyCard,
        now: Date
    ) {
        guard let center, let feedback = Self.feedbackContent(for: hold.action, card: card) else { return }
        let content = UNMutableNotificationContent()
        content.title = feedback.title
        content.body = feedback.body
        content.categoryIdentifier = MacNotificationAction.feedbackCategory
        content.sound = .default
        content.userInfo = ["card_id": card.id, "notification_phase": hold.phase]

        let request = UNNotificationRequest(
            identifier: hold.identifier,
            content: content,
            // This is solicited feedback to an action the user just took, so it
            // remains immediate even during quiet hours. Only unsolicited
            // prompts/reminders are deferred to the quiet-hours release.
            trigger: nil
        )
        center.add(request) { error in
            if let error {
                Task { @MainActor in
                    LocalVocabularyStore.shared.message = "알림 표시 실패: \(error.localizedDescription)"
                }
            }
        }
    }

    private func addPromptRequest(
        card: VocabularyCard,
        state: PromptReminderState,
        now: Date,
        deliveredIdentifiers: Set<String>,
        suppressImmediateDelivery: Bool
    ) {
        guard let center else { return }
        let baseIdentifier = Self.notificationIdentifier(cardId: card.id)
        if !deliveredIdentifiers.contains(baseIdentifier) || suppressImmediateDelivery {
            let content = promptContent(card: card, isReminder: false)
            center.add(UNNotificationRequest(
                identifier: baseIdentifier,
                content: content,
                trigger: Self.trigger(deliveryDate: state.firstPromptAt, now: now)
            )) { error in
                if let error {
                    Task { @MainActor in
                        LocalVocabularyStore.shared.message = "알림 예약 실패: \(error.localizedDescription)"
                    }
                }
            }
        }

    }

    private func promptContent(card: VocabularyCard, isReminder: Bool) -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        if card.isUnstartedRecommendation {
            content.title = isReminder ? "다시 알려드려요: \(card.displayWord)" : "오늘의 새 단어: \(card.displayWord)"
            content.body = [card.meaningKo, card.exampleSentence]
                .filter { !$0.isEmpty }
                .joined(separator: "\n")
            content.categoryIdentifier = MacNotificationAction.recommendationCategory
        } else {
            content.title = isReminder ? "복습 응답을 기다리고 있어요: \(card.displayWord)" : card.displayWord
            content.body = "복습 시간입니다. 메뉴바에서 뜻을 확인하세요."
            content.categoryIdentifier = MacNotificationAction.reviewCategory
        }
        content.sound = .default
        content.threadIdentifier = "vocab-reminder.card.\(card.id)"
        content.userInfo = [
            "card_id": card.id,
            "notification_phase": isReminder ? "unanswered_reminder" : "prompt"
        ]
        return content
    }

    private func allowedDeliveredIdentifiers(
        _ delivered: [String],
        hold: FeedbackHoldState?,
        targetCard: VocabularyCard?,
        promptState: PromptReminderState?,
        now: Date
    ) -> Set<String> {
        if let hold { return [hold.identifier] }
        guard let targetCard, let promptState, promptState.firstPromptAt <= now.addingTimeInterval(1) else {
            return []
        }
        let base = Self.notificationIdentifier(cardId: targetCard.id)
        return Set(delivered.filter { $0 == base })
    }

    private func updatePromptReminderState(
        targetCard: VocabularyCard?,
        settings: UserSettingsData,
        now: Date
    ) -> PromptReminderState? {
        guard let targetCard else {
            clearPromptReminderState()
            return nil
        }
        let plannedDate = Self.allowedDeliveryDate(max(targetCard.dueAt, now), settings: settings)
        if let existing = Self.loadPromptReminderState(defaults: defaults),
           existing.cardId == targetCard.id {
            let drift = abs(plannedDate.timeIntervalSince(existing.firstPromptAt))
            let promptHasNotFired = existing.firstPromptAt > now.addingTimeInterval(1)
            let movedBackToFuture = plannedDate > now.addingTimeInterval(60) && existing.firstPromptAt <= now
            if drift <= 60 || (!promptHasNotFired && !movedBackToFuture) {
                return existing
            }
        }
        let state = PromptReminderState(
            cardId: targetCard.id,
            firstPromptAt: plannedDate,
            lastReminderAt: nil,
            reminderDay: nil,
            remindersSentOnDay: 0
        )
        Self.savePromptReminderState(state, defaults: defaults)
        return state
    }

    private func clearPromptReminderState() {
        Self.savePromptReminderState(nil, defaults: defaults)
    }

    private func removeNotificationFamily(cardId: String, center: UNUserNotificationCenter) {
        let identifiers = [
            Self.notificationIdentifier(cardId: cardId),
            Self.failsafeIdentifier(cardId: cardId)
        ]
        center.removePendingNotificationRequests(withIdentifiers: identifiers)
        center.removeDeliveredNotifications(withIdentifiers: identifiers)
    }

    private func activeRecommendationSuppressions(now: Date) -> [String: Date] {
        let stored = defaults.dictionary(forKey: Self.suppressedRecommendationsDefaultsKey) ?? [:]
        let active = stored.compactMapValues { value -> Date? in
            let timestamp = (value as? NSNumber)?.doubleValue ?? (value as? Double)
            guard let timestamp else { return nil }
            let date = Date(timeIntervalSince1970: timestamp)
            return date > now ? date : nil
        }
        defaults.set(active.mapValues(\.timeIntervalSince1970), forKey: Self.suppressedRecommendationsDefaultsKey)
        return active
    }

    private func advanceRecommendation(cardId: String) {
        var active = activeRecommendationSuppressions(now: Date())
        active[cardId] = Date().addingTimeInterval(24 * 60 * 60)
        defaults.set(active.mapValues(\.timeIntervalSince1970), forKey: Self.suppressedRecommendationsDefaultsKey)
        clearPromptReminderState()
        rescheduleGeneration &+= 1
        if let center { removeNotificationFamily(cardId: cardId, center: center) }
        LocalVocabularyStore.shared.refreshNotifications()
    }

    private func scheduleUnansweredReminder(
        card: VocabularyCard?,
        state: PromptReminderState?,
        settings: UserSettingsData,
        now: Date
    ) {
        unansweredReminderTask?.cancel()
        guard feedbackHold == nil, let card, let state else { return }
        let reminderAt = Self.nextUnansweredReminderDate(
            state: state,
            settings: settings,
            now: now
        )
        if let center, reminderAt > now.addingTimeInterval(1) {
            let identifier = Self.failsafeIdentifier(cardId: card.id)
            let failsafeAt = reminderAt.addingTimeInterval(60)
            center.removePendingNotificationRequests(withIdentifiers: [identifier])
            center.add(UNNotificationRequest(
                identifier: identifier,
                content: promptContent(card: card, isReminder: true),
                trigger: Self.trigger(deliveryDate: failsafeAt, now: now)
            )) { error in
                if let error {
                    Task { @MainActor in
                        LocalVocabularyStore.shared.message = "재알림 안전 예약 실패: \(error.localizedDescription)"
                    }
                }
            }
        }
        let nanoseconds = UInt64(max(0.1, reminderAt.timeIntervalSince(now)) * 1_000_000_000)
        unansweredReminderTask = Task { @MainActor [weak self] in
            do {
                try await Task.sleep(nanoseconds: nanoseconds)
            } catch {
                return
            }
            self?.publishUnansweredReminder(
                card: card,
                expectedState: state,
                settings: settings
            )
        }
    }

    private func publishUnansweredReminder(
        card: VocabularyCard,
        expectedState: PromptReminderState,
        settings: UserSettingsData
    ) {
        guard feedbackHold == nil,
              let current = Self.loadPromptReminderState(defaults: defaults),
              current.cardId == expectedState.cardId,
              current == expectedState,
              let center else { return }
        Task { @MainActor [weak self] in
            guard let self else { return }
            let delivered = await center.deliveredNotifications()
            guard self.feedbackHold == nil,
                  let latestState = Self.loadPromptReminderState(defaults: self.defaults),
                  latestState == expectedState else { return }

            let store = LocalVocabularyStore.shared
            let suppressed = self.activeRecommendationSuppressions(now: Date())
            let target = MacNotificationPlan.scheduledCards(
                cards: store.activeCards.filter { suppressed[$0.id] == nil },
                settings: store.settings,
                processedCardIdsToday: store.processedNotificationCardIdsToday()
            ).first
            guard target?.id == card.id else {
                store.refreshNotifications()
                return
            }

            let deliveredFailsafe = delivered.first {
                $0.request.identifier == Self.failsafeIdentifier(cardId: card.id)
            }
            let firedAt = deliveredFailsafe?.date ?? Date()
            let updatedState = Self.recordingUnansweredReminder(in: latestState, at: firedAt, settings: settings)
            Self.savePromptReminderState(updatedState, defaults: self.defaults)

            // Reuse the base identifier and remove the fail-safe first. If the
            // OS already delivered that fail-safe while the main actor was
            // delayed, replace it silently so one reminder produces one sound.
            self.removeNotificationFamily(cardId: card.id, center: center)
            let content = self.promptContent(card: target ?? card, isReminder: true)
            if deliveredFailsafe != nil { content.sound = nil }
            center.add(UNNotificationRequest(
                identifier: Self.notificationIdentifier(cardId: card.id),
                content: content,
                trigger: nil
            )) { error in
                if let error {
                    Task { @MainActor in
                        LocalVocabularyStore.shared.message = "재알림 표시 실패: \(error.localizedDescription)"
                    }
                }
            }
            self.scheduleUnansweredReminder(
                card: target ?? card,
                state: updatedState,
                settings: settings,
                now: firedAt
            )
        }
    }

    private func scheduleQuietBoundaryRefresh(settings: UserSettingsData, now: Date) {
        quietBoundaryTask?.cancel()
        guard let boundary = Self.nextQuietBoundary(after: now, settings: settings) else { return }
        let nanoseconds = UInt64(max(1, boundary.timeIntervalSince(now)) * 1_000_000_000)
        quietBoundaryTask = Task { @MainActor [weak self] in
            do {
                try await Task.sleep(nanoseconds: nanoseconds)
            } catch {
                return
            }
            guard self != nil else { return }
            LocalVocabularyStore.shared.refreshNotifications()
        }
    }

    package static func allowedDeliveryDate(_ proposed: Date, settings: UserSettingsData) -> Date {
        Scheduler.avoidQuietHours(proposed, settings: settings.schedulerSettings)
    }

    package static func notificationIdentifier(cardId: String) -> String {
        cardIdentifierPrefix + cardId
    }

    package static func failsafeIdentifier(cardId: String) -> String {
        notificationIdentifier(cardId: cardId) + ".failsafe"
    }

    package static func isQuietHours(_ date: Date, settings: UserSettingsData) -> Bool {
        allowedDeliveryDate(date, settings: settings) > date.addingTimeInterval(1)
    }

    package static func nextUnansweredReminderDate(
        state: PromptReminderState,
        settings: UserSettingsData,
        now: Date
    ) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: settings.timezone) ?? .current
        let base = state.lastReminderAt ?? state.firstPromptAt
        var candidate = max(base.addingTimeInterval(unansweredReminderInterval), now)
        candidate = allowedDeliveryDate(candidate, settings: settings)

        let candidateDay = calendar.startOfDay(for: candidate)
        if let reminderDay = state.reminderDay,
           calendar.isDate(reminderDay, inSameDayAs: candidateDay),
           state.remindersSentOnDay >= maxUnansweredRemindersPerDay {
            let nextDay = calendar.date(byAdding: .day, value: 1, to: candidateDay) ?? candidate.addingTimeInterval(24 * 60 * 60)
            candidate = allowedDeliveryDate(nextDay, settings: settings)
        }
        return candidate
    }

    package static func recordingUnansweredReminder(
        in state: PromptReminderState,
        at date: Date,
        settings: UserSettingsData
    ) -> PromptReminderState {
        var updated = state
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: settings.timezone) ?? .current
        let day = calendar.startOfDay(for: date)
        if let previousDay = state.reminderDay, calendar.isDate(previousDay, inSameDayAs: day) {
            updated.remindersSentOnDay += 1
        } else {
            updated.reminderDay = day
            updated.remindersSentOnDay = 1
        }
        updated.lastReminderAt = date
        return updated
    }

    package static func nextQuietBoundary(after now: Date, settings: UserSettingsData) -> Date? {
        guard let timeZone = TimeZone(identifier: settings.timezone),
              let start = minuteOfDay(settings.quietHoursStart),
              let end = minuteOfDay(settings.quietHoursEnd) else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let startOfDay = calendar.startOfDay(for: now)
        let boundaryMinutes = Set([start, end])
        return boundaryMinutes.compactMap { minute -> Date? in
            guard let today = calendar.date(byAdding: .minute, value: minute, to: startOfDay) else { return nil }
            return today > now ? today : calendar.date(byAdding: .day, value: 1, to: today)
        }.min()
    }

    private static func minuteOfDay(_ value: String) -> Int? {
        let parts = value.split(separator: ":")
        guard parts.count == 2, let hour = Int(parts[0]), let minute = Int(parts[1]),
              (0...23).contains(hour), (0...59).contains(minute) else { return nil }
        return hour * 60 + minute
    }

    private static func trigger(deliveryDate: Date, now: Date) -> UNNotificationTrigger? {
        let interval = deliveryDate.timeIntervalSince(now)
        return interval <= 1 ? nil : UNTimeIntervalNotificationTrigger(timeInterval: interval, repeats: false)
    }

    package static func loadFeedbackHold(defaults: UserDefaults) -> FeedbackHoldState? {
        guard let data = defaults.data(forKey: feedbackHoldDefaultsKey) else { return nil }
        return try? JSONDecoder().decode(FeedbackHoldState.self, from: data)
    }

    package static func saveFeedbackHold(_ hold: FeedbackHoldState?, defaults: UserDefaults) {
        guard let hold, let data = try? JSONEncoder().encode(hold) else {
            defaults.removeObject(forKey: feedbackHoldDefaultsKey)
            return
        }
        defaults.set(data, forKey: feedbackHoldDefaultsKey)
    }

    package static func loadPromptReminderState(defaults: UserDefaults) -> PromptReminderState? {
        guard let data = defaults.data(forKey: promptReminderDefaultsKey) else { return nil }
        return try? JSONDecoder().decode(PromptReminderState.self, from: data)
    }

    package static func savePromptReminderState(_ state: PromptReminderState?, defaults: UserDefaults) {
        guard let state, let data = try? JSONEncoder().encode(state) else {
            defaults.removeObject(forKey: promptReminderDefaultsKey)
            return
        }
        defaults.set(data, forKey: promptReminderDefaultsKey)
    }

    package nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping @Sendable (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }

    package nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping @Sendable () -> Void
    ) {
        guard let cardId = response.notification.request.content.userInfo["card_id"] as? String else {
            completionHandler()
            return
        }
        let rawAction = response.actionIdentifier
        let category = response.notification.request.content.categoryIdentifier
        Task { @MainActor in
            if Self.shouldAcknowledgeFeedback(action: rawAction, category: category) {
                self.acknowledgeFeedback(cardId: cardId)
                completionHandler()
                return
            }
            let action = Self.resolvedPromptAction(action: rawAction, category: category)
            if action == MacNotificationAction.advanceRecommendation {
                let store = LocalVocabularyStore.shared
                let card = store.cards.first(where: { $0.id == cardId })
                guard Self.shouldHandlePromptAction(
                    action: action,
                    card: card,
                    promptState: Self.loadPromptReminderState(defaults: self.defaults),
                    feedbackHold: self.feedbackHold,
                    now: Date()
                ) else {
                    completionHandler()
                    return
                }
                self.advanceRecommendation(cardId: cardId)
                completionHandler()
                return
            }

            let recognizedPromptActions: Set<String> = [
                MacNotificationAction.start,
                MacNotificationAction.known,
                MacNotificationAction.dismiss,
                MacNotificationAction.remembered,
                MacNotificationAction.forgotten,
                MacNotificationAction.unavailable
            ]
            guard recognizedPromptActions.contains(action) else {
                completionHandler()
                return
            }

            let store = LocalVocabularyStore.shared
            let card = store.cards.first(where: { $0.id == cardId })
            let promptState = Self.loadPromptReminderState(defaults: self.defaults)
            guard Self.shouldHandlePromptAction(
                action: action,
                card: card,
                promptState: promptState,
                feedbackHold: self.feedbackHold,
                now: Date()
            ) else {
                completionHandler()
                return
            }

            if Self.producesFeedback(action: action) {
                self.beginQueueHold(for: action, cardId: cardId)
            } else {
                self.rescheduleGeneration &+= 1
                self.clearPromptReminderState()
            }
            if let updatedCard = Self.handleAction(action, cardId: cardId) {
                if Self.producesFeedback(action: action) {
                    self.showFeedbackNotification(for: action, card: updatedCard)
                }
            } else if Self.producesFeedback(action: action) {
                self.clearQueueHold()
                LocalVocabularyStore.shared.refreshNotifications()
            }
            completionHandler()
        }
    }
}

package struct FeedbackHoldState: Codable, Equatable {
    package init(
        phase: String,
        action: String,
        cardId: String,
        identifier: String
    ) {
        self.phase = phase
        self.action = action
        self.cardId = cardId
        self.identifier = identifier
    }

    package var phase: String
    package var action: String
    package var cardId: String
    package var identifier: String
}

package struct PromptReminderState: Codable, Equatable {
    package var cardId: String
    package var firstPromptAt: Date
    package var lastReminderAt: Date?
    package var reminderDay: Date?
    package var remindersSentOnDay: Int

    package init(
        cardId: String,
        firstPromptAt: Date,
        lastReminderAt: Date? = nil,
        reminderDay: Date? = nil,
        remindersSentOnDay: Int = 0
    ) {
        self.cardId = cardId
        self.firstPromptAt = firstPromptAt
        self.lastReminderAt = lastReminderAt
        self.reminderDay = reminderDay
        self.remindersSentOnDay = remindersSentOnDay
    }

    private enum CodingKeys: String, CodingKey {
        case cardId
        case firstPromptAt
        case lastReminderAt
        case reminderDay
        case remindersSentOnDay
    }

    package init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        cardId = try container.decode(String.self, forKey: .cardId)
        firstPromptAt = try container.decode(Date.self, forKey: .firstPromptAt)
        lastReminderAt = try container.decodeIfPresent(Date.self, forKey: .lastReminderAt)
        reminderDay = try container.decodeIfPresent(Date.self, forKey: .reminderDay)
        remindersSentOnDay = try container.decodeIfPresent(Int.self, forKey: .remindersSentOnDay) ?? 0
    }
}

package enum MacNotificationPlan {
    package static func scheduledCards(
        cards: [VocabularyCard],
        settings: UserSettingsData,
        processedCardIdsToday: Set<String> = [],
        now: Date = Date()
    ) -> [VocabularyCard] {
        let budget = max(0, min(settings.maxQueueSize, settings.dailyWordGoal))
        let remaining = max(0, budget - processedCardIdsToday.count)
        guard remaining > 0 else { return [] }
        let userCards = cards
            .filter { ($0.wordbook == .user || $0.recommendationStartedAt != nil) && isNotificationEligible($0) }
        let dueUserCards = userCards
            .filter { $0.dueAt <= now }
            .sorted { Scheduler.isHigherPriority($0.notificationSchedulerCard, than: $1.notificationSchedulerCard, now: now) }
        if let dueUser = dueUserCards.first {
            return [dueUser]
        }

        if settings.enableWordRecommendations {
            let recommendations = cards
                .filter { $0.wordbook == .recommended && $0.recommendationStartedAt == nil && $0.dueAt <= now && isNotificationEligible($0) }
                .sorted { $0.dueAt == $1.dueAt ? $0.id < $1.id : $0.dueAt < $1.dueAt }
            if let recommendation = recommendations.first {
                return [recommendation]
            }
        }

        return Array(userCards
            .filter { $0.dueAt > now }
            .sorted { $0.dueAt == $1.dueAt ? $0.id < $1.id : $0.dueAt < $1.dueAt }
            .prefix(1))
    }

    private static func isNotificationEligible(_ card: VocabularyCard) -> Bool {
        card.deletedAt == nil &&
            !card.meaningKo.isEmpty &&
            card.dueAt > Date(timeIntervalSince1970: 1) &&
            card.updatedAt > Date(timeIntervalSince1970: 1) &&
            card.status != .mastered &&
            card.status != .suspended
    }
}

package enum MacNotificationAction {
    package static let reviewCategory = "vocab-reminder.review"
    package static let recommendationCategory = "vocab-reminder.recommendation"
    package static let feedbackCategory = "vocab-reminder.feedback"
    package static let remembered = "vocab-reminder.review.remembered"
    package static let forgotten = "vocab-reminder.review.forgotten"
    package static let unavailable = "vocab-reminder.review.unavailable"
    package static let start = "vocab-reminder.recommendation.start"
    package static let known = "vocab-reminder.recommendation.known"
    package static let dismiss = "vocab-reminder.recommendation.dismiss"
    package static let advanceRecommendation = "vocab-reminder.recommendation.advance"
    package static let feedbackAcknowledge = "vocab-reminder.feedback.acknowledge"
}

private extension VocabularyCard {
    var isUnstartedRecommendation: Bool {
        wordbook == .recommended && recommendationStartedAt == nil
    }

    var notificationSchedulerCard: SchedulerCard {
        SchedulerCard(
            id: id,
            status: status,
            intervalDays: intervalDays,
            dueAt: dueAt,
            lapseCount: lapseCount,
            intervalBeforeLapse: intervalBeforeLapse,
            meaningKo: meaningKo,
            wordbook: wordbook,
            deletedAt: deletedAt
        )
    }
}

private extension UserSettingsData {
    var schedulerSettings: SchedulerSettings {
        SchedulerSettings(
            dailyWordGoal: dailyWordGoal,
            maxQueueSize: maxQueueSize,
            snoozeDurationMinutes: snoozeDurationMinutes,
            quietHoursStart: quietHoursStart,
            quietHoursEnd: quietHoursEnd,
            timezone: timezone,
            enableWordRecommendations: enableWordRecommendations
        )
    }
}
