import Foundation
@preconcurrency import UserNotifications

@MainActor
final class MacNotificationScheduler: NSObject, UNUserNotificationCenterDelegate {
    static let shared = MacNotificationScheduler()

    // UNUserNotificationCenter는 앱 번들이 없는 프로세스(`swift run` 직접 실행)에서
    // 접근하는 순간 크래시한다 (bundleProxyForCurrentProcess is nil).
    // 번들이 없으면 알림 기능만 끄고 앱은 계속 동작하게 한다. (QC-H1 수정)
    private static var hasAppBundle: Bool { Bundle.main.bundleIdentifier != nil }

    private let center: UNUserNotificationCenter?
    private let identifierPrefix = "vocab-reminder.card."

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

        center.getPendingNotificationRequests { [identifierPrefix] requests in
            let oldIdentifiers = requests
                .map(\.identifier)
                .filter { $0.hasPrefix(identifierPrefix) }
            if !oldIdentifiers.isEmpty {
                center.removePendingNotificationRequests(withIdentifiers: oldIdentifiers)
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
        let recommendation = UNNotificationCategory(
            identifier: MacNotificationAction.recommendationCategory,
            actions: [start, known, dismiss],
            intentIdentifiers: []
        )
        let review = UNNotificationCategory(
            identifier: MacNotificationAction.reviewCategory,
            actions: [],
            intentIdentifiers: []
        )
        center?.setNotificationCategories([recommendation, review])
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        guard let cardId = response.notification.request.content.userInfo["card_id"] as? String else {
            completionHandler()
            return
        }
        let action = response.actionIdentifier
        Task { @MainActor in
            switch action {
            case MacNotificationAction.start:
                LocalVocabularyStore.shared.markRecommendationStarted(cardId: cardId)
            case MacNotificationAction.known:
                LocalVocabularyStore.shared.softDeleteRecommendation(cardId: cardId, action: "known")
            case MacNotificationAction.dismiss:
                LocalVocabularyStore.shared.softDeleteRecommendation(cardId: cardId, action: "dismissed")
            default:
                break
            }
        }
        completionHandler()
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
        var selected = Array(userCards.filter { $0.dueAt <= now }.prefix(limit))
        var remaining = limit - selected.count
        guard remaining > 0 else { return selected }

        if settings.enableWordRecommendations {
            let recommendations = cards
                .filter { $0.wordbook == .recommended && isNotificationEligible($0) }
                .sorted { $0.dueAt < $1.dueAt }
                .prefix(remaining)
            selected += recommendations
            remaining = limit - selected.count
        }

        if remaining > 0 {
            selected += userCards.filter { $0.dueAt > now }.prefix(remaining)
        }
        return selected
    }

    private static func isNotificationEligible(_ card: VocabularyCard) -> Bool {
        card.deletedAt == nil &&
            !card.meaningKo.isEmpty &&
            card.status != .mastered &&
            card.status != .suspended
    }
}

private enum MacNotificationAction {
    static let reviewCategory = "vocab-reminder.review"
    static let recommendationCategory = "vocab-reminder.recommendation"
    static let start = "vocab-reminder.recommendation.start"
    static let known = "vocab-reminder.recommendation.known"
    static let dismiss = "vocab-reminder.recommendation.dismiss"
}

private extension VocabularyCard {
    var isUnstartedRecommendation: Bool {
        wordbook == .recommended && recommendationStartedAt == nil
    }
}
