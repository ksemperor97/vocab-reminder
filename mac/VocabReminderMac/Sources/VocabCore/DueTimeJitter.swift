import Foundation

package enum DueTimeJitter {
    package static func apply(
        to dueAt: Date,
        now: Date,
        settings: SchedulerSettings,
        offsetProvider: ((ClosedRange<TimeInterval>) -> TimeInterval)? = nil
    ) -> Date {
        let horizon = dueAt.timeIntervalSince(now)
        guard horizon > 60 else {
            return Scheduler.avoidQuietHours(dueAt, settings: settings)
        }

        let window = jitterWindow(for: horizon)
        guard window >= 1 else {
            return Scheduler.avoidQuietHours(dueAt, settings: settings)
        }

        let range = (-window)...window
        let offset = offsetProvider?(range) ?? TimeInterval.random(in: range)
        let candidate = max(dueAt.addingTimeInterval(offset), now.addingTimeInterval(60))
        return Scheduler.avoidQuietHours(candidate, settings: settings)
    }

    package static func apply(
        to result: ReviewResult,
        now: Date,
        settings: SchedulerSettings,
        offsetProvider: ((ClosedRange<TimeInterval>) -> TimeInterval)? = nil
    ) -> ReviewResult {
        let dueAt = apply(
            to: result.card.dueAt,
            now: now,
            settings: settings,
            offsetProvider: offsetProvider
        )
        guard dueAt != result.card.dueAt else { return result }

        var card = result.card
        var event = result.event
        card.dueAt = dueAt
        event.nextDueAt = dueAt
        event.metadata["due_jitter_seconds"] = "\(Int(dueAt.timeIntervalSince(result.card.dueAt)))"
        return ReviewResult(card: card, event: event)
    }

    package static func jitterWindow(for horizon: TimeInterval) -> TimeInterval {
        if horizon <= 30 * 60 {
            return min(max(horizon * 0.5, 2 * 60), 10 * 60)
        }
        if horizon <= 6 * 60 * 60 {
            return min(max(horizon * 0.2, 10 * 60), 60 * 60)
        }
        return min(max(horizon * 0.1, 60 * 60), 12 * 60 * 60)
    }
}
