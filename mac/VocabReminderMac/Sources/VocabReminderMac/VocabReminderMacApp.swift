import SwiftUI

@main
struct VocabReminderMacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var store = LocalVocabularyStore.shared
    @StateObject private var hotKey = GlobalHotKeyController.shared

    init() {
        SchedulerVectorCommand.runAndExitIfRequested()
    }

    var body: some Scene {
        MenuBarExtra("Vocabulary Reminder", systemImage: "text.book.closed") {
            MenuBarView(store: store)
        }
        WindowGroup("Vocabulary Reminder") {
            ContentView(store: store)
                .frame(minWidth: 720, minHeight: 560)
                .onAppear {
                    hotKey.onPressed = { LocalVocabularyStore.shared.captureClipboard() }
                }
        }
    }
}

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        NSApp.servicesProvider = self
    }

    @objc func addToVocabularyReminder(_ pasteboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        guard let text = pasteboard.string(forType: .string), !text.isEmpty else {
            error.pointee = "No text found on pasteboard" as NSString
            return
        }
        LocalVocabularyStore.shared.capture(text: text, method: .macService, sourceApp: "macOS Services")
    }
}

struct MenuBarView: View {
    @ObservedObject var store: LocalVocabularyStore

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Vocabulary Reminder")
                .font(.headline)
            Text("오늘 due: \(store.dueCount)개")
            Text("새 단어: \(store.activeCards.filter { $0.status == .new }.count)개")
            Text("취약 단어: \(store.weakCount)개")
            Divider()
            Button("클립보드에서 추가") { store.captureClipboard() }
                .keyboardShortcut("v", modifiers: [.command, .option])
            Button("오늘 복습하기") {
                if let card = store.activeCards.first(where: { !$0.meaningKo.isEmpty && $0.dueAt <= Date() }) {
                    store.review(card: card, response: .remembered)
                }
            }
            Button("복습 재조정") { store.rebalanceDueCards() }
            Divider()
            Button("창 열기") { NSApp.activate(ignoringOtherApps: true) }
            Button("종료") { NSApp.terminate(nil) }
        }
        .padding(10)
    }
}

struct ContentView: View {
    @ObservedObject var store: LocalVocabularyStore
    @State private var manualText = ""
    @State private var previewMeaning = ""
    @State private var previewExample = ""

    var body: some View {
        NavigationSplitView {
            List(store.activeCards) { card in
                VStack(alignment: .leading) {
                    Text(card.displayWord)
                        .font(.headline)
                    Text(card.meaningKo.isEmpty ? "뜻 없음" : card.meaningKo)
                        .foregroundStyle(.secondary)
                    Text("\(card.status.rawValue) · \(card.wordbook.rawValue)")
                        .font(.caption)
                }
            }
            .navigationTitle("단어장")
        } detail: {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Vocabulary Reminder")
                        .font(.title2)
                        .fontWeight(.semibold)

                    HStack {
                        TextField("단어 또는 문장", text: $manualText)
                            .textFieldStyle(.roundedBorder)
                        Button("미리보기") {
                            store.capture(text: manualText, method: .manual, sourceApp: "Mac manual")
                        }
                        Button("클립보드") {
                            store.captureClipboard()
                        }
                        .keyboardShortcut("v", modifiers: [.command, .option])
                    }

                    if let pending = store.pendingAnalysis {
                        CandidateSelectionView(analysis: pending, store: store)
                    }

                    if let preview = store.preview {
                        PreviewView(
                            result: preview,
                            meaning: $previewMeaning,
                            example: $previewExample,
                            onAppear: {
                                previewMeaning = preview.meaningKo
                                previewExample = preview.sourceSentence.isEmpty ? preview.exampleSentence : preview.sourceSentence
                            },
                            onSave: {
                                store.savePreview(meaningOverride: previewMeaning, exampleOverride: previewExample)
                                manualText = ""
                            },
                            onCancel: {
                                store.preview = nil
                                store.pendingAnalysis = nil
                            }
                        )
                    }

                    SettingsView(store: store)

                    Text(store.message)
                        .font(.caption)
                        .foregroundStyle(.secondary)

                    Divider()

                    Text("최근 저장 단어")
                        .font(.headline)
                    ForEach(store.activeCards.prefix(12)) { card in
                        CardRow(card: card, store: store)
                    }
                }
                .padding(24)
            }
        }
    }
}

struct CandidateSelectionView: View {
    var analysis: CaptureAnalysis
    @ObservedObject var store: LocalVocabularyStore

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("어떤 단어를 저장할까요?")
                .font(.headline)
            Text(analysis.normalizedText)
                .font(.caption)
                .foregroundStyle(.secondary)
            FlowLayout(items: analysis.candidates) { candidate in
                Button(candidate) { store.selectCandidate(candidate) }
            }
        }
        .padding(12)
        .background(.quaternary, in: RoundedRectangle(cornerRadius: 8))
    }
}

struct PreviewView: View {
    var result: DictionaryResult
    @Binding var meaning: String
    @Binding var example: String
    var onAppear: () -> Void
    var onSave: () -> Void
    var onCancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("\(result.displayWord) → \(result.lemma)")
                .font(.headline)
            if !result.definitionEn.isEmpty {
                Text("\(result.partOfSpeech) · \(result.definitionEn)")
                    .foregroundStyle(.secondary)
            }
            TextField("뜻", text: $meaning)
                .textFieldStyle(.roundedBorder)
            TextField("예문", text: $example)
                .textFieldStyle(.roundedBorder)
            Text("출처: \(result.sourceApp) · \(result.sourceType)")
                .font(.caption)
                .foregroundStyle(.secondary)
            HStack {
                Button("저장", action: onSave)
                    .keyboardShortcut(.return)
                Button("취소", action: onCancel)
            }
        }
        .padding(12)
        .background(.quaternary, in: RoundedRectangle(cornerRadius: 8))
        .onAppear(perform: onAppear)
        .onChange(of: result.lemma) { _, _ in onAppear() }
    }
}

struct SettingsView: View {
    @ObservedObject var store: LocalVocabularyStore

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("설정")
                .font(.headline)
            HStack {
                Stepper("하루 목표 \(store.settings.dailyWordGoal)", value: $store.settings.dailyWordGoal, in: 1...50)
                Stepper("큐 상한 \(store.settings.maxQueueSize)", value: $store.settings.maxQueueSize, in: 1...50)
                Stepper("미루기 \(store.settings.snoozeDurationMinutes)분", value: $store.settings.snoozeDurationMinutes, in: 5...240, step: 5)
            }
            Toggle("추천 단어", isOn: $store.settings.enableWordRecommendations)
            HStack {
                Button("복습 재조정") { store.rebalanceDueCards() }
                Button("Sync 내보내기") { store.exportSyncSnapshot() }
                Button("Sync 가져오기") { store.importSyncSnapshot() }
            }
        }
        .padding(12)
        .background(.quaternary, in: RoundedRectangle(cornerRadius: 8))
    }
}

struct CardRow: View {
    var card: VocabularyCard
    @ObservedObject var store: LocalVocabularyStore

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(card.displayWord)
                .font(.headline)
            Text(card.meaningKo.isEmpty ? "뜻 없음 · 알림 보류" : card.meaningKo)
            if !card.exampleSentence.isEmpty {
                Text(card.exampleSentence)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text("\(card.status.rawValue) · interval=\(card.intervalDays)d · due=\(card.dueAt.formatted())")
                .font(.caption)
                .foregroundStyle(.secondary)
            HStack {
                Button("외웠다") { store.review(card: card, response: .remembered) }
                Button("못외웠다") { store.review(card: card, response: .forgotten) }
                Button("나중에") { store.review(card: card, response: .unavailable) }
            }
        }
        .padding(.vertical, 8)
    }
}

struct FlowLayout<Content: View>: View {
    var items: [String]
    @ViewBuilder var content: (String) -> Content

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 90), spacing: 8)], alignment: .leading, spacing: 8) {
            ForEach(items, id: \.self) { item in
                content(item)
            }
        }
    }
}
