import Foundation
import Testing
@testable import VocabCore

@Suite(.serialized)
@MainActor
final class SchedulerBoundaryTests {
    private let schedulerSettings = SchedulerSettings(
        snoozeDurationMinutes: 60,
        quietHoursStart: "23:00",
        quietHoursEnd: "09:00",
        timezone: "Asia/Seoul",
        maxUnavailablePerCardPerDay: 2
    )

    private let notificationSettings = UserSettingsData(
        snoozeDurationMinutes: 60,
        quietHoursStart: "23:00",
        quietHoursEnd: "09:00",
        timezone: "Asia/Seoul"
    )

    @Test func testQuietHoursStartBoundaryAt2259And2300InSeoul() {
        let beforeQuietHours = seoulDate(day: 13, hour: 22, minute: 59)
        let quietHoursStart = seoulDate(day: 13, hour: 23, minute: 0)
        let nextMorningRelease = seoulDate(day: 14, hour: 9, minute: 30)

        #expect(Scheduler.avoidQuietHours(beforeQuietHours, settings: schedulerSettings) == beforeQuietHours)
        #expect(Scheduler.avoidQuietHours(quietHoursStart, settings: schedulerSettings) == nextMorningRelease)
        #expect(!(MacNotificationScheduler.isQuietHours(beforeQuietHours, settings: notificationSettings)))
        #expect(MacNotificationScheduler.isQuietHours(quietHoursStart, settings: notificationSettings))
        #expect(MacNotificationScheduler.nextQuietBoundary(after: beforeQuietHours, settings: notificationSettings) == quietHoursStart)
        #expect(MacNotificationScheduler.nextQuietBoundary(after: quietHoursStart, settings: notificationSettings) == seoulDate(day: 14, hour: 9, minute: 0))
    }

    @Test func testMidnightIsQuietAndWaitsUntilMorningRelease() {
        let midnight = seoulDate(day: 14, hour: 0, minute: 0)

        #expect(Scheduler.avoidQuietHours(midnight, settings: schedulerSettings) == seoulDate(day: 14, hour: 9, minute: 30))
        #expect(MacNotificationScheduler.isQuietHours(midnight, settings: notificationSettings))
        #expect(MacNotificationScheduler.nextQuietBoundary(after: midnight, settings: notificationSettings) == seoulDate(day: 14, hour: 9, minute: 0))
    }

    @Test func testQuietHoursEndAt0900IsExclusive() {
        let lastQuietMinute = seoulDate(day: 14, hour: 8, minute: 59)
        let quietHoursEnd = seoulDate(day: 14, hour: 9, minute: 0)

        #expect(Scheduler.avoidQuietHours(lastQuietMinute, settings: schedulerSettings) == seoulDate(day: 14, hour: 9, minute: 30))
        #expect(Scheduler.avoidQuietHours(quietHoursEnd, settings: schedulerSettings) == quietHoursEnd)
        #expect(MacNotificationScheduler.isQuietHours(lastQuietMinute, settings: notificationSettings))
        #expect(!(MacNotificationScheduler.isQuietHours(quietHoursEnd, settings: notificationSettings)))
        #expect(MacNotificationScheduler.nextQuietBoundary(after: quietHoursEnd, settings: notificationSettings) == seoulDate(day: 14, hour: 23, minute: 0))
    }

    @Test func testFirstAndSecondUnavailableUseFixedNow() {
        let now = seoulDate(day: 13, hour: 10, minute: 0)
        let card = reviewCard(dueAt: now)

        let first = Scheduler.applyReview(
            card: card,
            response: .unavailable,
            settings: schedulerSettings,
            now: now,
            unavailableCountToday: 0
        )
        let second = Scheduler.applyReview(
            card: first.card,
            response: .unavailable,
            settings: schedulerSettings,
            now: now,
            unavailableCountToday: 1
        )

        #expect(first.card.dueAt == seoulDate(day: 13, hour: 11, minute: 0))
        #expect(second.card.dueAt == seoulDate(day: 14, hour: 10, minute: 0))
        #expect(first.card.status == card.status)
        #expect(second.card.intervalDays == card.intervalDays)
    }

    @Test func testFirstUnavailableAt2259DefersAcrossQuietHours() {
        let now = seoulDate(day: 13, hour: 22, minute: 59)

        let result = Scheduler.applyReview(
            card: reviewCard(dueAt: now),
            response: .unavailable,
            settings: schedulerSettings,
            now: now,
            unavailableCountToday: 0
        )

        #expect(result.card.dueAt == seoulDate(day: 14, hour: 9, minute: 30))
    }

    private func reviewCard(dueAt: Date) -> SchedulerCard {
        SchedulerCard(
            id: "review-card",
            status: .review,
            intervalDays: 7,
            dueAt: dueAt
        )
    }

    private func seoulDate(day: Int, hour: Int, minute: Int) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Seoul")!
        return calendar.date(from: DateComponents(
            year: 2026,
            month: 7,
            day: day,
            hour: hour,
            minute: minute
        ))!
    }
}

extension SchedulerBoundaryTests {
    @Test func testQuietReleaseUsesLocalMorningAcrossBothDSTTransitions() throws {
        let formatter = ISO8601DateFormatter()
        let settings = SchedulerSettings(quietHoursStart: "23:00", quietHoursEnd: "09:00", timezone: "America/New_York")
        for (input, expected) in [
            ("2026-03-08T06:55:00Z", "2026-03-08T13:30:00Z"),
            ("2026-11-01T05:55:00Z", "2026-11-01T14:30:00Z"),
            ("2026-11-01T06:55:00Z", "2026-11-01T14:30:00Z")
        ] {
            let now = try #require(formatter.date(from: input))
            #expect(Scheduler.avoidQuietHours(now, settings: settings) == formatter.date(from: expected))
        }
    }
}
