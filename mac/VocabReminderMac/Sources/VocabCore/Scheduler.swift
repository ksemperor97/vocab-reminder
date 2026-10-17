import Foundation

package struct SchedulerCard: Equatable {
    package init(
        id: String,
        status: CardStatus,
        intervalDays: Int,
        dueAt: Date,
        lapseCount: Int = 0,
        intervalBeforeLapse: Int? = nil,
        meaningKo: String = "",
        wordbook: Wordbook = .user,
        deletedAt: Date? = nil,
        recommendationStartedAt: Date? = nil
    ) {
        self.id = id
        self.status = status
        self.intervalDays = intervalDays
        self.dueAt = dueAt
        self.lapseCount = lapseCount
        self.intervalBeforeLapse = intervalBeforeLapse
        self.meaningKo = meaningKo
        self.wordbook = wordbook
        self.deletedAt = deletedAt
        self.recommendationStartedAt = recommendationStartedAt
    }

    package var id: String
    package var status: CardStatus
    package var intervalDays: Int
    package var dueAt: Date
    package var lapseCount: Int = 0
    package var intervalBeforeLapse: Int?
    package var meaningKo: String = "meaning"
    package var wordbook: Wordbook = .user
    package var deletedAt: Date?
    package var recommendationStartedAt: Date? = nil
}

package struct SchedulerSettings: Equatable {
    package init(
        dailyWordGoal: Int = 10,
        maxQueueSize: Int = 20,
        snoozeDurationMinutes: Int = 60,
        quietHoursStart: String = "",
        quietHoursEnd: String = "",
        timezone: String = "",
        maxUnavailablePerCardPerDay: Int = 2,
        enableWordRecommendations: Bool = true
    ) {
        self.dailyWordGoal = dailyWordGoal
        self.maxQueueSize = maxQueueSize
        self.snoozeDurationMinutes = snoozeDurationMinutes
        self.quietHoursStart = quietHoursStart
        self.quietHoursEnd = quietHoursEnd
        self.timezone = timezone
        self.maxUnavailablePerCardPerDay = maxUnavailablePerCardPerDay
        self.enableWordRecommendations = enableWordRecommendations
    }

    package var dailyWordGoal: Int = 10
    package var maxQueueSize: Int = 20
    package var snoozeDurationMinutes: Int = 60
    package var quietHoursStart: String = "23:00"
    package var quietHoursEnd: String = "09:00"
    package var timezone: String = "Asia/Seoul"
    package var maxUnavailablePerCardPerDay: Int = 2
    package var enableWordRecommendations: Bool = true
}

package struct ReviewEventDraft: Equatable {
    package init(
        cardId: String,
        response: ReviewResponse,
        previousStatus: CardStatus,
        nextStatus: CardStatus,
        previousDueAt: Date,
        nextDueAt: Date,
        previousIntervalDays: Int,
        nextIntervalDays: Int,
        metadata: [String: String] = [:]
    ) {
        self.cardId = cardId
        self.response = response
        self.previousStatus = previousStatus
        self.nextStatus = nextStatus
        self.previousDueAt = previousDueAt
        self.nextDueAt = nextDueAt
        self.previousIntervalDays = previousIntervalDays
        self.nextIntervalDays = nextIntervalDays
        self.metadata = metadata
    }

    package var cardId: String
    package var response: ReviewResponse
    package var previousStatus: CardStatus
    package var nextStatus: CardStatus
    package var previousDueAt: Date
    package var nextDueAt: Date
    package var previousIntervalDays: Int
    package var nextIntervalDays: Int
    package var metadata: [String: String] = [:]
}

package struct ReviewResult: Equatable {
    package init(
        card: SchedulerCard,
        event: ReviewEventDraft
    ) {
        self.card = card
        self.event = event
    }

    package var card: SchedulerCard
    package var event: ReviewEventDraft
}

package struct PlanDayResult: Equatable {
    package init(
        notifications: [SchedulerCard],
        rebalancedCards: [SchedulerCard],
        rebalanceEvents: [ReviewEventDraft]
    ) {
        self.notifications = notifications
        self.rebalancedCards = rebalancedCards
        self.rebalanceEvents = rebalanceEvents
    }

    package var notifications: [SchedulerCard]
    package var rebalancedCards: [SchedulerCard]
    package var rebalanceEvents: [ReviewEventDraft]
}

package enum Scheduler {
    private static let maxReviewIntervalDays = 90
    private static let firstNotificationDelayMinutes: TimeInterval = 10 * 60
    private static let forgottenRecheckMinutes: TimeInterval = 20 * 60
    private static let maxForgottenPerCardPerDay = 3
    private static let quietReleaseOffsetMinutes = 30

    package static func initialDueAt(now: Date, settings: SchedulerSettings = SchedulerSettings()) -> Date {
        // guide 6.3: new card first notification is save time + 10 minutes.
        avoidQuietHours(now.addingTimeInterval(firstNotificationDelayMinutes), settings: settings)
    }

    package static func applyReview(
        card: SchedulerCard,
        response: ReviewResponse,
        settings: SchedulerSettings = SchedulerSettings(),
        now: Date,
        recentResponses: [ReviewResponse] = [],
        unavailableCountToday: Int = 0,
        forgottenCountToday: Int = 0
    ) -> ReviewResult {
        let updated: SchedulerCard
        switch response {
        case .remembered:
            updated = remembered(card: card, settings: settings, now: now, recentResponses: recentResponses)
        case .forgotten:
            updated = forgotten(card: card, settings: settings, now: now, forgottenCountToday: forgottenCountToday)
        case .unavailable:
            updated = unavailable(card: card, settings: settings, now: now, unavailableCountToday: unavailableCountToday)
        case .reveal:
            updated = reveal(card: card, settings: settings, now: now)
        case .suspend:
            updated = copy(card, status: .suspended, dueAt: now)
        case .restore:
            updated = copy(card, status: .learning, dueAt: avoidQuietHours(now.addingTimeInterval(24 * 60 * 60), settings: settings))
        case .manualEdit:
            updated = copy(card, dueAt: avoidQuietHours(card.dueAt, settings: settings))
        }

        return ReviewResult(
            card: updated,
            event: ReviewEventDraft(
                cardId: card.id,
                response: response,
                previousStatus: card.status,
                nextStatus: updated.status,
                previousDueAt: card.dueAt,
                nextDueAt: updated.dueAt,
                previousIntervalDays: card.intervalDays,
                nextIntervalDays: updated.intervalDays
            )
        )
    }

    package static func planDay(cards: [SchedulerCard], settings: SchedulerSettings = SchedulerSettings(), now: Date, userWordCount: Int = 0) -> PlanDayResult {
        // guide 6.4, 6.7, 6.9: user due cards fill the goal first; overflow is rebalanced.
        let dueUserCards = cards
            .filter { ($0.wordbook == .user || $0.recommendationStartedAt != nil) && isNotificationEligible($0) && $0.dueAt <= now }
            .sorted { isHigherPriority($0, than: $1, now: now) }

        let activeLimit = min(settings.dailyWordGoal, settings.maxQueueSize)
        let selectedDue = Array(dueUserCards.prefix(activeLimit))
        let overflow = Array(dueUserCards.dropFirst(activeLimit))
        let remainingSlots = activeLimit - selectedDue.count

        let recommendations: [SchedulerCard]
        if remainingSlots > 0 && settings.enableWordRecommendations {
            recommendations = Array(cards
                .filter { $0.wordbook == .recommended && $0.recommendationStartedAt == nil && isNotificationEligible($0) }
                .sorted { $0.id < $1.id }
                .prefix(remainingSlots))
        } else {
            recommendations = []
        }

        let rebalance = autoRebalance(cards: overflow, settings: settings, now: now, trigger: "auto")
        return PlanDayResult(
            notifications: selectedDue + recommendations,
            rebalancedCards: rebalance.map(\.card),
            rebalanceEvents: rebalance.map(\.event)
        )
    }

    package static func autoRebalance(cards: [SchedulerCard], settings: SchedulerSettings = SchedulerSettings(), now: Date, trigger: String = "auto") -> [ReviewResult] {
        if cards.isEmpty { return [] }
        let lowPriorityFirst = cards.sorted { isHigherPriority($1, than: $0, now: now) }
        return lowPriorityFirst.enumerated().map { index, card in
            let dueAt = avoidQuietHours(now.addingTimeInterval(TimeInterval((index % 7) + 1) * 24 * 60 * 60), settings: settings)
            let updated = copy(card, dueAt: dueAt)
            return ReviewResult(
                card: updated,
                event: ReviewEventDraft(
                    cardId: card.id,
                    response: .manualEdit,
                    previousStatus: card.status,
                    nextStatus: updated.status,
                    previousDueAt: card.dueAt,
                    nextDueAt: updated.dueAt,
                    previousIntervalDays: card.intervalDays,
                    nextIntervalDays: updated.intervalDays,
                    metadata: ["action": "rebalance", "trigger": trigger]
                )
            )
        }
    }

    package static func avoidQuietHours(_ candidate: Date, settings: SchedulerSettings) -> Date {
        guard let timeZone = TimeZone(identifier: settings.timezone),
              let start = minutes(settings.quietHoursStart),
              let end = minutes(settings.quietHoursEnd) else {
            return candidate
        }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let components = calendar.dateComponents([.hour, .minute], from: candidate)
        let current = (components.hour ?? 0) * 60 + (components.minute ?? 0)
        guard isWithinQuietHours(current, start: start, end: end) else { return candidate }

        var endComponents = calendar.dateComponents([.year, .month, .day], from: candidate)
        endComponents.hour = end / 60
        endComponents.minute = end % 60
        var release = calendar.date(from: endComponents) ?? candidate
        if !(start < end || current < end) {
            release = calendar.date(byAdding: .day, value: 1, to: release) ?? release
        }
        return calendar.date(byAdding: .minute, value: quietReleaseOffsetMinutes, to: release) ?? release
    }

    private static func remembered(card: SchedulerCard, settings: SchedulerSettings, now: Date, recentResponses: [ReviewResponse]) -> SchedulerCard {
        let nextInterval: Int
        switch card.status {
        case .new:
            nextInterval = 1
        case .learning:
            nextInterval = nextLearningInterval(card.intervalDays)
        case .review:
            nextInterval = min(max(card.intervalDays, 1) * 2, maxReviewIntervalDays)
        case .relearning:
            nextInterval = max(1, min(Int(floor(Double(card.intervalBeforeLapse ?? 1) * 0.5)), 7))
        case .mastered:
            nextInterval = maxReviewIntervalDays
        case .suspended:
            nextInterval = card.intervalDays
        }

        let nextStatus: CardStatus
        switch card.status {
        case .new:
            nextStatus = .learning
        case .learning:
            nextStatus = nextInterval >= 7 ? .review : .learning
        case .review:
            let lastTwoRemembered = recentResponses.suffix(2).allSatisfy { $0 == .remembered } && recentResponses.count >= 2
            nextStatus = nextInterval >= 90 && lastTwoRemembered ? .mastered : .review
        case .relearning:
            nextStatus = nextInterval >= 7 ? .review : .learning
        case .mastered:
            nextStatus = .mastered
        case .suspended:
            nextStatus = .suspended
        }

        return copy(
            card,
            status: nextStatus,
            intervalDays: nextInterval,
            dueAt: avoidQuietHours(now.addingTimeInterval(TimeInterval(nextInterval) * 24 * 60 * 60), settings: settings)
        )
    }

    private static func forgotten(card: SchedulerCard, settings: SchedulerSettings, now: Date, forgottenCountToday: Int) -> SchedulerCard {
        // guide 6.3, 13.2: forgotten preserves the previous interval and rechecks after 10-30 minutes.
        let previousInterval: Int
        switch card.status {
        case .mastered:
            previousInterval = maxReviewIntervalDays
        case .relearning:
            previousInterval = card.intervalBeforeLapse ?? card.intervalDays
        default:
            previousInterval = card.intervalDays
        }
        let delay = forgottenCountToday + 1 >= maxForgottenPerCardPerDay
            ? TimeInterval(24 * 60 * 60)
            : forgottenRecheckMinutes
        return copy(
            card,
            status: .relearning,
            intervalDays: 0,
            dueAt: avoidQuietHours(now.addingTimeInterval(delay), settings: settings),
            lapseCount: card.lapseCount + 1,
            intervalBeforeLapse: previousInterval
        )
    }

    private static func unavailable(card: SchedulerCard, settings: SchedulerSettings, now: Date, unavailableCountToday: Int) -> SchedulerCard {
        // guide 6.3: the second unavailable in a day moves the card to tomorrow.
        let delay = unavailableCountToday + 1 >= settings.maxUnavailablePerCardPerDay
            ? TimeInterval(24 * 60 * 60)
            : TimeInterval(settings.snoozeDurationMinutes * 60)
        return copy(card, dueAt: avoidQuietHours(now.addingTimeInterval(delay), settings: settings))
    }

    private static func reveal(card: SchedulerCard, settings: SchedulerSettings, now: Date) -> SchedulerCard {
        // guide 6.2: reveal is a weak failure, without increasing lapse_count.
        let nextInterval = max(1, Int(floor(Double(card.intervalDays) * 0.5)))
        return copy(
            card,
            intervalDays: nextInterval,
            dueAt: avoidQuietHours(now.addingTimeInterval(TimeInterval(nextInterval) * 24 * 60 * 60), settings: settings)
        )
    }

    private static func nextLearningInterval(_ current: Int) -> Int {
        if current < 1 { return 1 }
        if current < 3 { return 3 }
        return 7
    }

    private static func isNotificationEligible(_ card: SchedulerCard) -> Bool {
        card.status != .mastered && card.status != .suspended && card.deletedAt == nil && !card.meaningKo.isEmpty
    }

    package static func isHigherPriority(_ left: SchedulerCard, than right: SchedulerCard, now: Date) -> Bool {
        let leftRank = priorityRank(left)
        let rightRank = priorityRank(right)
        if leftRank != rightRank { return leftRank > rightRank }
        if left.status == .learning && right.status == .learning && left.dueAt != right.dueAt {
            return left.dueAt < right.dueAt
        }
        if left.status == .review && right.status == .review {
            let leftRatio = reviewOverdueRatio(left, now: now)
            let rightRatio = reviewOverdueRatio(right, now: now)
            if leftRatio != rightRatio { return leftRatio > rightRatio }
        }
        if left.lapseCount != right.lapseCount { return left.lapseCount > right.lapseCount }
        return left.id < right.id
    }

    private static func priorityRank(_ card: SchedulerCard) -> Int {
        switch card.status {
        case .relearning: return 4
        case .learning: return 3
        case .review: return 2
        case .new: return 1
        case .mastered, .suspended: return 0
        }
    }

    private static func reviewOverdueRatio(_ card: SchedulerCard, now: Date) -> Double {
        let overdue = max(0, now.timeIntervalSince(card.dueAt))
        let interval = max(1, Double(card.intervalDays) * 24 * 60 * 60)
        return overdue / interval
    }

    private static func isWithinQuietHours(_ current: Int, start: Int, end: Int) -> Bool {
        if start < end { return current >= start && current < end }
        return current >= start || current < end
    }

    private static func minutes(_ time: String) -> Int? {
        let parts = time.split(separator: ":").compactMap { Int($0) }
        guard parts.count == 2 else { return nil }
        return parts[0] * 60 + parts[1]
    }

    private static func copy(
        _ card: SchedulerCard,
        status: CardStatus? = nil,
        intervalDays: Int? = nil,
        dueAt: Date? = nil,
        lapseCount: Int? = nil,
        intervalBeforeLapse: Int? = nil
    ) -> SchedulerCard {
        SchedulerCard(
            id: card.id,
            status: status ?? card.status,
            intervalDays: intervalDays ?? card.intervalDays,
            dueAt: dueAt ?? card.dueAt,
            lapseCount: lapseCount ?? card.lapseCount,
            intervalBeforeLapse: intervalBeforeLapse ?? card.intervalBeforeLapse,
            meaningKo: card.meaningKo,
            wordbook: card.wordbook,
            deletedAt: card.deletedAt,
            recommendationStartedAt: card.recommendationStartedAt
        )
    }
}
