import Foundation
@preconcurrency import UserNotifications

@MainActor
final class MacNotificationScheduler {
    static let shared = MacNotificationScheduler()

    private let center = UNUserNotificationCenter.current()
    private let identifierPrefix = "vocab-reminder.card."

    private init() {}

    func requestAuthorization() {
        center.requestAuthorization(options: [.alert, .sound]) { granted, error in
            if let error {
                print("Notification authorization failed: \(error.localizedDescription)")
            } else if !granted {
                print("Notification authorization was not granted.")
            }
        }
    }

    func reschedule(cards: [VocabularyCard], settings: UserSettingsData) {
        let scheduledCards = MacNotificationPlan.scheduledCards(cards: cards, settings: settings)

        center.getPendingNotificationRequests { [center, identifierPrefix] requests in
            let oldIdentifiers = requests
                .map(\.identifier)
                .filter { $0.hasPrefix(identifierPrefix) }
            if !oldIdentifiers.isEmpty {
                center.removePendingNotificationRequests(withIdentifiers: oldIdentifiers)
            }

            for card in scheduledCards {
                let content = UNMutableNotificationContent()
                content.title = card.displayWord
                content.body = "복습 시간입니다. 메뉴바에서 뜻을 확인하세요."
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

enum MacNotificationPlan {
    static func scheduledCards(cards: [VocabularyCard], settings: UserSettingsData) -> [VocabularyCard] {
        let limit = max(0, min(settings.maxQueueSize, settings.dailyWordGoal))
        guard limit > 0 else { return [] }
        return Array(cards
            .filter { card in
                card.deletedAt == nil &&
                    !card.meaningKo.isEmpty &&
                    card.status != .mastered &&
                    card.status != .suspended &&
                    isNotificationAllowed(card: card, settings: settings)
            }
            .sorted { $0.dueAt < $1.dueAt }
            .prefix(limit))
    }

    private static func isNotificationAllowed(card: VocabularyCard, settings: UserSettingsData) -> Bool {
        guard card.wordbook == .recommended else { return true }
        return settings.enableWordRecommendations && card.recommendationStartedAt != nil
    }
}
