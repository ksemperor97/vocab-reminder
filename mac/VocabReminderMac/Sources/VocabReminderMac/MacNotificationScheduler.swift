import Foundation
@preconcurrency import UserNotifications

@MainActor
final class MacNotificationScheduler: NSObject, UNUserNotificationCenterDelegate {
    static let shared = MacNotificationScheduler()

    // UNUserNotificationCenter는 앱 번들이 없는 프로세스(`swift run` 직접 실행)에서
    // 접근하는 순간 크래시한다 (bundleProxyForCurrentProcess is nil).
    // 번들이 없으면 알림 기능만 끄고 앱은 계속 동작하게 한다. (QC-H1 수정)
    private static var hasAppBundle: Bool { Bundle.main.bundleIdentifier != nil }
    private static let cardIdentifierPrefix = "vocab-reminder.card."

    private let center: UNUserNotificationCenter?
    private let identifierPrefix = MacNotificationScheduler.cardIdentifierPrefix
    private var queueHeldForFeedback = false
    private var heldFeedbackIdentifier: String?

    private override init() {
        center = Self.hasAppBundle ? UNUserNotificationCenter.current() : nil
        super.init()
        center?.delegate = self
        if center == nil {
            print("Local notifications disabled: no app bundle (running via `swift run`). Package as .app to enable notifications.")
        }
        registerCategories()
    }

    func requestAuthorization() {
        registerCategories()
        guard let center else { return }
        center.requestAuthorization(options: [.alert, .sound]) { granted, error in
            if let error {
                print("Notification authorization failed: \(error.localizedDescription)")
            } else if !granted {
                print("Notification authorization was not granted.")
            }
        }
    }

    func reschedule(cards: [VocabularyCard], settings: UserSettingsData) {
        guard let center else { return }
        let scheduledCards = MacNotificationPlan.scheduledCards(cards: cards, settings: settings)
        let shouldHold = queueHeldForFeedback
        let heldFeedbackIdentifier = heldFeedbackIdentifier
        let targetIdentifier = scheduledCards.first.map { identifierPrefix + $0.id }

        center.getPendingNotificationRequests { [identifierPrefix] requests in
            let oldIdentifiers = requests
                .map(\.identifier)
                .filter { $0.hasPrefix(identifierPrefix) }
            if !oldIdentifiers.isEmpty {
                center.removePendingNotificationRequests(withIdentifiers: oldIdentifiers)
            }

            center.getDeliveredNotifications { notifications in
                let deliveredIdentifiers = notifications
                    .map(\.request.identifier)
                    .filter { $0.hasPrefix(identifierPrefix) }
                let keepIdentifier = shouldHold ? heldFeedbackIdentifier : targetIdentifier
                let staleDelivered = deliveredIdentifiers.filter { $0 != keepIdentifier }
                if !staleDelivered.isEmpty {
                    center.removeDeliveredNotifications(withIdentifiers: staleDelivered)
                }
                guard !shouldHold else { return }
                if let targetIdentifier, deliveredIdentifiers.contains(targetIdentifier) {
                    return
                }

                for card in scheduledCards {
                    let content = UNMutableNotificationContent()
                    if card.isUnstartedRecommendation {
                        content.title = "오늘의 새 단어: \(card.displayWord)"
                        content.body = [card.meaningKo, card.exampleSentence]
                            .filter { !$0.isEmpty }
                            .joined(separator: "\n")
                        content.categoryIdentifier = MacNotificationAction.recommendationCategory
                    } else {
                        content.title = card.displayWord
                        content.body = "복습 시간입니다. 메뉴바에서 뜻을 확인하세요."
                        content.categoryIdentifier = MacNotificationAction.reviewCategory
                    }
                    content.sound = .default
                    content.userInfo = ["card_id": card.id]

                    let interval = max(1, card.dueAt.timeIntervalSinceNow)
                    let trigger = UNTimeIntervalNotificationTrigger(timeInterval: interval, repeats: false)
                    let request = UNNotificationRequest(
                        identifier: identifierPrefix + card.id,
                        content: content,
                        trigger: trigger
                    )
                    center.add(request) { error in
                        if let error {
                            print("Failed to schedule notification for \(card.id): \(error.localizedDescription)")
                        }
                    }
                }
            }
        }
    }

    func cancel(card: VocabularyCard) {
        guard let center else { return }
        let identifier = identifierPrefix + card.id
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
        center.removeDeliveredNotifications(withIdentifiers: [identifier])
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
            intentIdentifiers: []
        )
        let review = UNNotificationCategory(
            identifier: MacNotificationAction.reviewCategory,
            actions: [remembered, forgotten, unavailable],
            intentIdentifiers: []
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
    static func handleAction(_ action: String, cardId: String, store: LocalVocabularyStore = .shared) -> VocabularyCard? {
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

    static func feedbackContent(for action: String, card: VocabularyCard) -> (title: String, body: String)? {
        switch action {
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

    static func shouldAcknowledgeFeedback(action: String, category: String) -> Bool {
        category == MacNotificationAction.feedbackCategory &&
            (
                action == MacNotificationAction.feedbackAcknowledge ||
                    action == UNNotificationDismissActionIdentifier ||
                    action == UNNotificationDefaultActionIdentifier
            )
    }

    static func producesFeedback(action: String) -> Bool {
        action == MacNotificationAction.remembered || action == MacNotificationAction.forgotten
    }

    private func beginQueueHold(for action: String) {
        guard Self.producesFeedback(action: action) else { return }
        queueHeldForFeedback = true
    }

    private func clearQueueHold() {
        queueHeldForFeedback = false
        heldFeedbackIdentifier = nil
    }

    private func acknowledgeFeedback(cardId: String) {
        let notificationIdentifier = heldFeedbackIdentifier ?? identifierPrefix + cardId
        center?.removeDeliveredNotifications(withIdentifiers: [notificationIdentifier])
        clearQueueHold()
        LocalVocabularyStore.shared.refreshNotifications()
    }

    private func showFeedbackNotification(for action: String, card: VocabularyCard) {
        guard let center, let feedback = Self.feedbackContent(for: action, card: card) else { return }

        let content = UNMutableNotificationContent()
        content.title = feedback.title
        content.body = feedback.body
        content.categoryIdentifier = MacNotificationAction.feedbackCategory
        content.sound = .default
        content.userInfo = ["card_id": card.id]

        let notificationIdentifier = identifierPrefix + card.id
        heldFeedbackIdentifier = notificationIdentifier
        center.removeDeliveredNotifications(withIdentifiers: [notificationIdentifier])
        center.removePendingNotificationRequests(withIdentifiers: [notificationIdentifier])
        let request = UNNotificationRequest(
            identifier: notificationIdentifier,
            content: content,
            trigger: nil
        )
        center.add(request) { error in
            if let error {
                print("Failed to show notification feedback for \(card.id): \(error.localizedDescription)")
            }
        }
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping @Sendable (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping @Sendable () -> Void
    ) {
        guard let cardId = response.notification.request.content.userInfo["card_id"] as? String else {
            completionHandler()
            return
        }
        let action = response.actionIdentifier
        let category = response.notification.request.content.categoryIdentifier
        Task { @MainActor in
            if Self.shouldAcknowledgeFeedback(action: action, category: category) {
                self.acknowledgeFeedback(cardId: cardId)
                completionHandler()
                return
            }
            self.beginQueueHold(for: action)
            if let updatedCard = Self.handleAction(action, cardId: cardId) {
                self.showFeedbackNotification(for: action, card: updatedCard)
            } else {
                self.clearQueueHold()
            }
            completionHandler()
        }
    }
}

enum MacNotificationPlan {
    static func scheduledCards(cards: [VocabularyCard], settings: UserSettingsData) -> [VocabularyCard] {
        let limit = max(0, min(settings.maxQueueSize, settings.dailyWordGoal))
        guard limit > 0 else { return [] }
        let now = Date()
        let userCards = cards
            .filter { $0.wordbook == .user && isNotificationEligible($0) }
            .sorted { $0.dueAt < $1.dueAt }
        if let dueUser = userCards.first(where: { $0.dueAt <= now }) {
            return [dueUser]
        }

        if settings.enableWordRecommendations {
            let recommendations = cards
                .filter { $0.wordbook == .recommended && isNotificationEligible($0) }
                .sorted { $0.dueAt < $1.dueAt }
            if let recommendation = recommendations.first {
                return [recommendation]
            }
        }

        return Array(userCards.filter { $0.dueAt > now }.prefix(1))
    }

    private static func isNotificationEligible(_ card: VocabularyCard) -> Bool {
        card.deletedAt == nil &&
            !card.meaningKo.isEmpty &&
            card.status != .mastered &&
            card.status != .suspended
    }
}

enum MacNotificationAction {
    static let reviewCategory = "vocab-reminder.review"
    static let recommendationCategory = "vocab-reminder.recommendation"
    static let feedbackCategory = "vocab-reminder.feedback"
    static let remembered = "vocab-reminder.review.remembered"
    static let forgotten = "vocab-reminder.review.forgotten"
    static let unavailable = "vocab-reminder.review.unavailable"
    static let start = "vocab-reminder.recommendation.start"
    static let known = "vocab-reminder.recommendation.known"
    static let dismiss = "vocab-reminder.recommendation.dismiss"
    static let feedbackAcknowledge = "vocab-reminder.feedback.acknowledge"
}

private extension VocabularyCard {
    var isUnstartedRecommendation: Bool {
        wordbook == .recommended && recommendationStartedAt == nil
    }
}
