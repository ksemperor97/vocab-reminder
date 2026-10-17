import Foundation
import XCTest
@testable import VocabReminderMac

@MainActor
final class SchedulerBoundaryTests: XCTestCase {
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

    func testQuietHoursStartBoundaryAt2259And2300InSeoul() {
        let beforeQuietHours = seoulDate(day: 13, hour: 22, minute: 59)
        let quietHoursStart = seoulDate(day: 13, hour: 23, minute: 0)
        let nextMorningRelease = seoulDate(day: 14, hour: 9, minute: 30)

        XCTAssertEqual(
            Scheduler.avoidQuietHours(beforeQuietHours, settings: schedulerSettings),
            beforeQuietHours
        )
        XCTAssertEqual(
            Scheduler.avoidQuietHours(quietHoursStart, settings: schedulerSettings),
            nextMorningRelease
        )
        XCTAssertFalse(MacNotificationScheduler.isQuietHours(beforeQuietHours, settings: notificationSettings))
        XCTAssertTrue(MacNotificationScheduler.isQuietHours(quietHoursStart, settings: notificationSettings))
        XCTAssertEqual(
            MacNotificationScheduler.nextQuietBoundary(after: beforeQuietHours, settings: notificationSettings),
            quietHoursStart
        )
        XCTAssertEqual(
            MacNotificationScheduler.nextQuietBoundary(after: quietHoursStart, settings: notificationSettings),
            seoulDate(day: 14, hour: 9, minute: 0)
        )
    }

    func testMidnightIsQuietAndWaitsUntilMorningRelease() {
        let midnight = seoulDate(day: 14, hour: 0, minute: 0)

        XCTAssertEqual(
            Scheduler.avoidQuietHours(midnight, settings: schedulerSettings),
            seoulDate(day: 14, hour: 9, minute: 30)
        )
        XCTAssertTrue(MacNotificationScheduler.isQuietHours(midnight, settings: notificationSettings))
        XCTAssertEqual(
            MacNotificationScheduler.nextQuietBoundary(after: midnight, settings: notificationSettings),
            seoulDate(day: 14, hour: 9, minute: 0)
        )
    }

    func testQuietHoursEndAt0900IsExclusive() {
        let lastQuietMinute = seoulDate(day: 14, hour: 8, minute: 59)
        let quietHoursEnd = seoulDate(day: 14, hour: 9, minute: 0)

        XCTAssertEqual(
            Scheduler.avoidQuietHours(lastQuietMinute, settings: schedulerSettings),
            seoulDate(day: 14, hour: 9, minute: 30)
        )
        XCTAssertEqual(
            Scheduler.avoidQuietHours(quietHoursEnd, settings: schedulerSettings),
            quietHoursEnd
        )
        XCTAssertTrue(MacNotificationScheduler.isQuietHours(lastQuietMinute, settings: notificationSettings))
        XCTAssertFalse(MacNotificationScheduler.isQuietHours(quietHoursEnd, settings: notificationSettings))
        XCTAssertEqual(
            MacNotificationScheduler.nextQuietBoundary(after: quietHoursEnd, settings: notificationSettings),
            seoulDate(day: 14, hour: 23, minute: 0)
        )
    }

    func testFirstAndSecondUnavailableUseFixedNow() {
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

        XCTAssertEqual(first.card.dueAt, seoulDate(day: 13, hour: 11, minute: 0))
        XCTAssertEqual(second.card.dueAt, seoulDate(day: 14, hour: 10, minute: 0))
        XCTAssertEqual(first.card.status, card.status)
        XCTAssertEqual(second.card.intervalDays, card.intervalDays)
    }

    func testFirstUnavailableAt2259DefersAcrossQuietHours() {
        let now = seoulDate(day: 13, hour: 22, minute: 59)

        let result = Scheduler.applyReview(
            card: reviewCard(dueAt: now),
            response: .unavailable,
            settings: schedulerSettings,
            now: now,
            unavailableCountToday: 0
        )

        XCTAssertEqual(result.card.dueAt, seoulDate(day: 14, hour: 9, minute: 30))
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

final class SchedulerSharedVectorTests: XCTestCase {
    func testSharedCrossPlatformSchedulerVectors() throws {
        try SchedulerVectorCommand.verifySharedSchedulerVectors()
    }
}

extension SchedulerBoundaryTests {
    func testQuietReleaseUsesLocalMorningAcrossBothDSTTransitions() throws {
        let formatter = ISO8601DateFormatter()
        let settings = SchedulerSettings(quietHoursStart: "23:00", quietHoursEnd: "09:00", timezone: "America/New_York")
        for (input, expected) in [
            ("2026-03-08T06:55:00Z", "2026-03-08T13:30:00Z"),
            ("2026-11-01T05:55:00Z", "2026-11-01T14:30:00Z"),
            ("2026-11-01T06:55:00Z", "2026-11-01T14:30:00Z")
        ] {
            let now = try XCTUnwrap(formatter.date(from: input))
            XCTAssertEqual(Scheduler.avoidQuietHours(now, settings: settings), formatter.date(from: expected))
        }
    }
}
