import SwiftUI

@main
struct VocabReminderMacApp: App {
    var body: some Scene {
        WindowGroup("Vocabulary Reminder") {
            ContentView()
        }
    }
}

struct ContentView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Vocabulary Reminder")
                .font(.title2)
                .fontWeight(.semibold)
            Text("Mac capture and management shell for M0.")
                .foregroundStyle(.secondary)
        }
        .padding(24)
        .frame(minWidth: 420, minHeight: 240)
    }
}
