import SwiftUI

@main
struct VocabReminderMacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.openWindow) private var openWindow
    @StateObject private var store = LocalVocabularyStore.shared
    @StateObject private var hotKey = GlobalHotKeyController.shared

    init() {
        SchedulerVectorCommand.runAndExitIfRequested()
    }

    var body: some Scene {
        MenuBarExtra("Vocabulary Reminder", systemImage: "text.book.closed") {
            MenuBarView(
                store: store,
                openMainWindow: {
                    openWindow(id: "main")
                    NSApp.activate(ignoringOtherApps: true)
                }
            )
        }
        WindowGroup("Vocabulary Reminder", id: "main") {
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
        MacNotificationScheduler.shared.requestAuthorization()
        LocalVocabularyStore.shared.refreshNotifications()
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
    var openMainWindow: () -> Void

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
            Button("추천 채우기") { store.fillRecommendations() }
            Divider()
            Button("창 열기") { openMainWindow() }
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
    @AppStorage("firebaseWebApiKey") private var firebaseWebApiKey = ""
    @AppStorage("firebaseEmail") private var firebaseEmail = ""
    @AppStorage("firebaseRefreshToken") private var firebaseRefreshToken = ""
    @AppStorage("cloudFunctionsURL") private var cloudFunctionsURL = ""
    @State private var firebasePassword = ""
    @State private var cloudAuthToken = ""
    @State private var firebaseAuthRunning = false
    @State private var cloudSyncRunning = false

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
                Button("추천 채우기") { store.fillRecommendations() }
                Button("Sync 내보내기") { store.exportSyncSnapshot() }
                Button("Sync 가져오기") { store.importSyncSnapshot() }
            }
            Divider()
            TextField("Firebase Web API key", text: $firebaseWebApiKey)
                .textFieldStyle(.roundedBorder)
            TextField("Firebase email", text: $firebaseEmail)
                .textFieldStyle(.roundedBorder)
            SecureField("Firebase password", text: $firebasePassword)
                .textFieldStyle(.roundedBorder)
            HStack {
                Button(firebaseAuthRunning ? "로그인 중" : "Firebase 로그인") {
                    signInFirebase()
                }
                .disabled(firebaseAuthRunning || firebaseWebApiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || firebaseEmail.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || firebasePassword.isEmpty)
                Button(firebaseAuthRunning ? "갱신 중" : "ID token 갱신") {
                    refreshFirebaseToken()
                }
                .disabled(firebaseAuthRunning || firebaseWebApiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || firebaseRefreshToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            TextField("Functions URL", text: $cloudFunctionsURL)
                .textFieldStyle(.roundedBorder)
            SecureField("Firebase Auth ID token", text: $cloudAuthToken)
                .textFieldStyle(.roundedBorder)
            Button(cloudSyncRunning ? "Cloud Sync 중" : "Cloud Sync") {
                runCloudSync()
            }
            .disabled(cloudSyncRunning || cloudFunctionsURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || cloudAuthToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .padding(12)
        .background(.quaternary, in: RoundedRectangle(cornerRadius: 8))
        .onChange(of: store.settings) { _, _ in
            store.persistSettings()
        }
    }

    private func signInFirebase() {
        let apiKey = firebaseWebApiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let email = firebaseEmail.trimmingCharacters(in: .whitespacesAndNewlines)
        guard apiKey.isEmpty == false, email.isEmpty == false, firebasePassword.isEmpty == false else {
            store.message = "Firebase Web API key, email, password를 입력하세요."
            return
        }
        firebaseAuthRunning = true
        store.message = "Firebase 로그인 중..."
        Task { @MainActor in
            defer { firebaseAuthRunning = false }
            do {
                let session = try await FirebaseAuthRestClient().signInWithPassword(
                    FirebasePasswordAuthConfig(webApiKey: apiKey, email: email, password: firebasePassword)
                )
                cloudAuthToken = session.idToken
                firebaseRefreshToken = session.refreshToken
                if session.email.isEmpty == false { firebaseEmail = session.email }
                firebasePassword = ""
                store.message = "Firebase 로그인 완료 · ID token \(session.expiresInSeconds)초 유효"
            } catch {
                store.message = "Firebase 로그인 실패: \(error.localizedDescription)"
            }
        }
    }

    private func refreshFirebaseToken() {
        let apiKey = firebaseWebApiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let refreshToken = firebaseRefreshToken.trimmingCharacters(in: .whitespacesAndNewlines)
        guard apiKey.isEmpty == false, refreshToken.isEmpty == false else {
            store.message = "저장된 Firebase Web API key와 refresh token이 필요합니다."
            return
        }
        firebaseAuthRunning = true
        store.message = "ID token 갱신 중..."
        Task { @MainActor in
            defer { firebaseAuthRunning = false }
            do {
                let session = try await FirebaseAuthRestClient().refreshIdToken(
                    webApiKey: apiKey,
                    refreshToken: refreshToken
                )
                cloudAuthToken = session.idToken
                firebaseRefreshToken = session.refreshToken
                store.message = "ID token 갱신 완료 · \(session.expiresInSeconds)초 유효"
            } catch {
                store.message = "ID token 갱신 실패: \(error.localizedDescription)"
            }
        }
    }

    private func runCloudSync() {
        let functionsURL = cloudFunctionsURL.trimmingCharacters(in: .whitespacesAndNewlines)
        let token = cloudAuthToken.trimmingCharacters(in: .whitespacesAndNewlines)
        guard functionsURL.isEmpty == false, token.isEmpty == false else {
            store.message = "Functions URL과 Firebase Auth ID token을 입력하세요."
            return
        }
        cloudSyncRunning = true
        store.message = "Cloud sync 중..."
        Task { @MainActor in
            defer { cloudSyncRunning = false }
            do {
                let summary = try await store.syncWithCloud(functionsBaseURL: functionsURL, authToken: token)
                store.message = "Cloud sync 완료 · 카드 \(summary.cards), 이벤트 \(summary.reviewEvents)"
            } catch {
                store.message = "Cloud sync 실패: \(error.localizedDescription)"
            }
        }
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
            if card.wordbook == .recommended && card.recommendationStartedAt == nil {
                HStack {
                    Button("시작하기") { store.markRecommendationStarted(card) }
                    Button("알아요") { store.softDeleteRecommendation(card, action: "known") }
                    Button("관심 없음") { store.softDeleteRecommendation(card, action: "dismissed") }
                }
            } else {
                HStack {
                    Button("외웠다") { store.review(card: card, response: .remembered) }
                    Button("못외웠다") { store.review(card: card, response: .forgotten) }
                    Button("나중에") { store.review(card: card, response: .unavailable) }
                    Button("삭제") { store.softDeleteCard(card) }
                }
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
