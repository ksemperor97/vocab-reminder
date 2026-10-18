import Darwin
import Foundation
@preconcurrency import UserNotifications

/// `VocabReminder.app/Contents/MacOS/VocabReminderMac --check-notification-status`
/// prints this bundle's notification authorization and pending/delivered
/// requests. It must run inside the app bundle, so it stays in the app; all
/// other checks live in the vocab-check CLI.
enum AppDiagnosticsCommand {
    static func runAndExitIfRequested() {
        guard CommandLine.arguments.contains("--check-notification-status") else { return }
        guard let bundleIdentifier = Bundle.main.bundleIdentifier else {
            print("bundleIdentifier=nil")
            exit(2)
        }

        let center = UNUserNotificationCenter.current()
        let semaphore = DispatchSemaphore(value: 0)
        let status = NotificationStatusBox()

        center.getNotificationSettings { settings in
            status.settingsStatus = String(describing: settings.authorizationStatus)
            center.getPendingNotificationRequests { requests in
                status.pending = requests.map(\.identifier).sorted()
                status.pendingDetails = requests.map(Self.describe).sorted()
                center.getDeliveredNotifications { notifications in
                    status.delivered = notifications.map(\.request.identifier).sorted()
                    status.deliveredDetails = notifications.map { Self.describe($0.request) }.sorted()
                    semaphore.signal()
                }
            }
        }

        _ = semaphore.wait(timeout: .now() + 5)
        print("bundleIdentifier=\(bundleIdentifier)")
        print("authorizationStatus=\(status.settingsStatus)")
        print("pending=\(status.pending.joined(separator: ","))")
        print("delivered=\(status.delivered.joined(separator: ","))")
        status.pendingDetails.forEach { print("pendingDetail=\($0)") }
        status.deliveredDetails.forEach { print("deliveredDetail=\($0)") }
        exit(0)
    }

    private static func describe(_ request: UNNotificationRequest) -> String {
        [
            request.identifier,
            request.content.title,
            request.content.body.replacingOccurrences(of: "\n", with: " / "),
            request.content.categoryIdentifier
        ].joined(separator: "|")
    }

    private final class NotificationStatusBox: @unchecked Sendable {
        var settingsStatus = "unknown"
        var pending: [String] = []
        var delivered: [String] = []
        var pendingDetails: [String] = []
        var deliveredDetails: [String] = []
    }
}
