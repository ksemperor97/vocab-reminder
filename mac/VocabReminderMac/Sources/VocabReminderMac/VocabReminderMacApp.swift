import Carbon.HIToolbox
import ServiceManagement
import SwiftUI

@main
struct VocabReminderMacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.openWindow) private var openWindow
    @StateObject private var store = LocalVocabularyStore.shared

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
        }
    }
}

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var mainWindow: NSWindow?

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        NSApp.servicesProvider = self
        NSAppleEventManager.shared().setEventHandler(
            self,
            andSelector: #selector(handleGetURLEvent(_:withReplyEvent:)),
            forEventClass: AEEventClass(kInternetEventClass),
            andEventID: AEEventID(kAEGetURL)
        )
        NSUpdateDynamicServices()
        GlobalHotKeyController.shared.onPressed = { [weak self] in
            LocalVocabularyStore.shared.captureClipboard()
            self?.showMainWindow()
        }
        MacNotificationScheduler.shared.requestAuthorization()
        LocalVocabularyStore.shared.refreshNotifications()
        LoginItemController.registerAtLaunchIfNeeded(store: LocalVocabularyStore.shared)
        MacAutoSyncController.shared.start(store: LocalVocabularyStore.shared)
    }

    @objc func addToVocabularyReminder(_ pasteboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        let text = pasteboard.string(forType: .string)
            ?? pasteboard.string(forType: NSPasteboard.PasteboardType("NSStringPboardType"))
            ?? ""
        guard !text.isEmpty else {
            error.pointee = "No text found on pasteboard" as NSString
            return
        }
        LocalVocabularyStore.shared.capture(text: text, method: .macService, sourceApp: "macOS Services")
        showMainWindow()
    }

    func application(_ application: NSApplication, open urls: [URL]) {
        guard let text = urls.compactMap(captureText(from:)).first else { return }
        LocalVocabularyStore.shared.capture(text: text, method: .macService, sourceApp: "macOS Quick Action")
        showMainWindow()
    }

    @objc private func handleGetURLEvent(_ event: NSAppleEventDescriptor, withReplyEvent replyEvent: NSAppleEventDescriptor) {
        guard let urlString = event.paramDescriptor(forKeyword: keyDirectObject)?.stringValue,
              let url = URL(string: urlString),
              let text = captureText(from: url)
        else {
            return
        }
        LocalVocabularyStore.shared.capture(text: text, method: .macService, sourceApp: "macOS Quick Action")
        showMainWindow()
    }

    private func captureText(from url: URL) -> String? {
        guard url.scheme == "vocabreminder",
              url.host == "capture",
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let text = components.queryItems?.first(where: { $0.name == "text" })?.value?
                .trimmingCharacters(in: .whitespacesAndNewlines),
              !text.isEmpty
        else {
            return nil
        }
        return text
    }

    private func showMainWindow() {
        if let existing = NSApp.windows.first(where: { $0.title == "Vocabulary Reminder" }) {
            existing.makeKeyAndOrderFront(nil)
            NSApp.activate(ignoringOtherApps: true)
            return
        }
        if mainWindow == nil {
            let window = NSWindow(
                contentRect: NSRect(x: 0, y: 0, width: 860, height: 640),
                styleMask: [.titled, .closable, .miniaturizable, .resizable],
                backing: .buffered,
                defer: false
            )
            window.title = "Vocabulary Reminder"
            window.contentView = NSHostingView(
                rootView: ContentView(store: LocalVocabularyStore.shared)
                    .frame(minWidth: 720, minHeight: 560)
            )
            window.center()
            mainWindow = window
        }
        mainWindow?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }
}

@MainActor
enum LoginItemController {
    private static let attemptedKey = "loginItemRegistrationAttempted"
    private static let lastResultKey = "loginItemRegistrationLastResult"

    static var canManageLoginItem: Bool {
        Bundle.main.bundleIdentifier != nil
    }

    static var isEnabled: Bool {
        canManageLoginItem && SMAppService.mainApp.status == .enabled
    }

    static func registerAtLaunchIfNeeded(store: LocalVocabularyStore) {
        guard canManageLoginItem else { return }
        let defaults = UserDefaults.standard
        guard !defaults.bool(forKey: attemptedKey) else { return }
        setEnabled(true, store: store, reportSuccess: false)
    }

    static func setEnabled(_ enabled: Bool, store: LocalVocabularyStore, reportSuccess: Bool = true) {
        guard canManageLoginItem else {
            store.message = "번들 앱에서만 로그인 자동 시작을 설정할 수 있습니다."
            return
        }
        do {
            if enabled {
                if SMAppService.mainApp.status != .enabled {
                    try SMAppService.mainApp.register()
                }
            } else if SMAppService.mainApp.status == .enabled {
                try SMAppService.mainApp.unregister()
            }
            UserDefaults.standard.set(true, forKey: attemptedKey)
            UserDefaults.standard.set(enabled ? "enabled" : "disabled", forKey: lastResultKey)
            if reportSuccess {
                store.message = enabled ? "로그인 시 자동 시작 켜짐" : "로그인 시 자동 시작 꺼짐"
            }
        } catch {
            UserDefaults.standard.set(true, forKey: attemptedKey)
            UserDefaults.standard.set("failed: \(error.localizedDescription)", forKey: lastResultKey)
            store.message = "로그인 자동 시작 설정 실패: \(error.localizedDescription)"
        }
    }
}

struct MenuBarView: View {
    @ObservedObject var store: LocalVocabularyStore
    var openMainWindow: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Vocabulary Reminder")
                .font(.headline)
            VStack(alignment: .leading, spacing: 4) {
                Text("오늘 due: \(store.dueCount)개")
                Text("새 단어: \(store.activeCards.filter { $0.status == .new }.count)개")
                Text("취약 단어: \(store.weakCount)개")
            }
            Divider()
            Button("오늘 복습하기") { openMainWindow() }
                .disabled(store.dueCount == 0)
            Button("최근 저장 단어") { openMainWindow() }
            Button("설정") { openMainWindow() }
        }
        .padding(12)
        .frame(width: 220, alignment: .leading)
    }
}

struct ContentView: View {
    @ObservedObject var store: LocalVocabularyStore
    @State private var manualText = ""
    @State private var previewMeaning = ""
    @State private var previewExample = ""
    @State private var selectedCardId: String?

    var body: some View {
        NavigationSplitView {
            List(selection: $selectedCardId) {
                Section("내 단어장 \(userCards.count)") {
                    ForEach(userCards) { card in
                        SidebarCardRow(card: card)
                            .tag(card.id)
                    }
                }
                Section("추천 단어장 \(recommendedCards.count)") {
                    ForEach(recommendedCards) { card in
                        SidebarCardRow(card: card)
                            .tag(card.id)
                    }
                }
            }
            .navigationTitle("단어장")
        } detail: {
            if let selectedCard {
                CardEditorView(
                    card: selectedCard,
                    store: store,
                    onClose: { selectedCardId = nil }
                )
            } else {
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

    private var selectedCard: VocabularyCard? {
        guard let selectedCardId else { return nil }
        return store.activeCards.first { $0.id == selectedCardId }
    }

    private var userCards: [VocabularyCard] {
        store.activeCards.filter { $0.wordbook == .user }
    }

    private var recommendedCards: [VocabularyCard] {
        store.activeCards.filter { $0.wordbook == .recommended }
    }
}

struct SidebarCardRow: View {
    var card: VocabularyCard

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(card.displayWord)
                .font(.headline)
            Text(card.meaningKo.isEmpty ? "뜻 없음" : card.meaningKo)
                .foregroundStyle(.secondary)
                .lineLimit(2)
            Text("\(card.status.rawValue) · 다음 학습: \(relativeDueLabel(card.dueAt))")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 2)
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
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(result.displayWord)
                    .font(.system(size: 28, weight: .semibold))
                if result.lemma != result.displayWord.lowercased() {
                    Text("원형: \(result.lemma)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            VStack(alignment: .leading, spacing: 4) {
                Text("뜻")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                TextField("뜻", text: $meaning)
                    .textFieldStyle(.roundedBorder)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text("예문")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                TextField("예문", text: $example)
                    .textFieldStyle(.roundedBorder)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text("출처: \(result.sourceApp) · \(result.sourceType)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if !result.definitionEn.isEmpty {
                    Text("\(result.partOfSpeech) · \(result.definitionEn)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
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
    @AppStorage("syncServerURL") private var syncServerURL = SyncDefaults.serverURL
    @AppStorage("syncBearerToken") private var syncBearerToken = SyncDefaults.bearerToken
    @AppStorage("lastLocalSyncSummary") private var lastLocalSyncSummary = ""
    @AppStorage("lastLocalSyncError") private var lastLocalSyncError = ""
    @AppStorage("lastLocalSyncSkipped") private var lastLocalSyncSkipped = ""
    @State private var connectionTestRunning = false
    @State private var cloudSyncRunning = false
    @State private var launchAtLoginEnabled = LoginItemController.isEnabled
    @State private var syncingLaunchAtLoginState = false

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
            Toggle("로그인 시 자동 시작", isOn: $launchAtLoginEnabled)
                .disabled(!LoginItemController.canManageLoginItem)
            HStack {
                Button("복습 재조정") { store.rebalanceDueCards() }
                Button("추천 채우기") { store.fillRecommendations() }
            }
            Divider()
            VStack(alignment: .leading, spacing: 6) {
                Text("동기화")
                    .font(.headline)
                Text(syncStatusText)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text("마지막 동기화: \(lastLocalSyncSummary.isEmpty ? "없음" : lastLocalSyncSummary)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if !lastLocalSyncSkipped.isEmpty {
                    Text("스킵: \(lastLocalSyncSkipped)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if !lastLocalSyncError.isEmpty {
                    Text("마지막 오류: \(lastLocalSyncError)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
#if DEBUG
            TextField("서버 주소", text: $syncServerURL)
                .textFieldStyle(.roundedBorder)
            SecureField("토큰", text: $syncBearerToken)
                .textFieldStyle(.roundedBorder)
#endif
            HStack {
                Button(connectionTestRunning ? "확인 중" : "연결 테스트") {
                    testConnection()
                }
                .disabled(connectionTestRunning || !canCloudSync)
                Button(cloudSyncRunning ? "동기화 중" : "지금 동기화") {
                    runCloudSync()
                }
                .disabled(cloudSyncRunning || !canCloudSync)
            }
#if DEBUG
            DisclosureGroup("Debug sync fields") {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Button("Sync 내보내기") { store.exportSyncSnapshot() }
                        Button("Sync 가져오기") { store.importSyncSnapshot() }
                    }
                }
            }
#endif
        }
        .padding(12)
        .background(.quaternary, in: RoundedRectangle(cornerRadius: 8))
        .onAppear {
            launchAtLoginEnabled = LoginItemController.isEnabled
        }
        .onChange(of: launchAtLoginEnabled) { _, enabled in
            guard !syncingLaunchAtLoginState else { return }
            LoginItemController.setEnabled(enabled, store: store)
            let actual = LoginItemController.isEnabled
            if actual != enabled {
                syncingLaunchAtLoginState = true
                launchAtLoginEnabled = actual
                DispatchQueue.main.async {
                    syncingLaunchAtLoginState = false
                }
            }
        }
        .onChange(of: store.settings) { oldValue, newValue in
            guard settingsUserFieldsChanged(oldValue, newValue) else { return }
            store.persistSettings()
        }
    }

    private var canCloudSync: Bool {
        resolvedServerURL.isEmpty == false && resolvedBearerToken.isEmpty == false
    }

    private var resolvedServerURL: String {
        SyncDefaults.effectiveServerURL(stored: syncServerURL)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var resolvedBearerToken: String {
        SyncDefaults.effectiveBearerToken(stored: syncBearerToken)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var syncStatusText: String {
#if DEBUG
        return "서버: \(resolvedServerURL.isEmpty ? "미설정" : resolvedServerURL)"
#else
        return canCloudSync ? "자동 동기화: 준비됨" : "자동 동기화: 설정 필요"
#endif
    }

    private func testConnection() {
        let serverURL = resolvedServerURL
        let token = resolvedBearerToken
        guard serverURL.isEmpty == false, token.isEmpty == false else {
            store.message = syncMissingConfigMessage
            return
        }
        connectionTestRunning = true
        store.message = "서버 연결 테스트 중..."
        Task { @MainActor in
            defer { connectionTestRunning = false }
            do {
                let health = try await CloudSyncClient(
                    config: CloudSyncConfig(serverBaseURL: serverURL, bearerToken: token)
                ).health()
                let cards = health["cards"] as? Int ?? 0
                store.message = "서버 연결 성공 · 카드 \(cards)개"
            } catch {
                store.message = "서버 연결 실패: \(error.localizedDescription)"
            }
        }
    }

    private func runCloudSync() {
        let serverURL = resolvedServerURL
        let token = resolvedBearerToken
        guard serverURL.isEmpty == false, token.isEmpty == false else {
            store.message = syncMissingConfigMessage
            return
        }
        cloudSyncRunning = true
        store.message = "동기화 중..."
        Task { @MainActor in
            defer { cloudSyncRunning = false }
            do {
                let summary = try await store.syncWithServer(serverBaseURL: serverURL, authToken: token)
                MacSyncStatusStore.recordSuccess(skippedSummary: summary.skippedSummary)
                store.message = summary.skippedSummary.isEmpty
                    ? "동기화 완료 · 카드 \(summary.cards), 이벤트 \(summary.reviewEvents)"
                    : "동기화 완료 · 스킵 \(summary.skippedSummary)"
            } catch {
                MacSyncStatusStore.recordFailure(error.localizedDescription)
                store.message = "동기화 실패: \(error.localizedDescription)"
            }
        }
    }

    private var syncMissingConfigMessage: String {
#if DEBUG
        "서버 주소와 토큰을 입력하세요."
#else
        "동기화 설정이 포함되지 않은 빌드입니다."
#endif
    }

    private func settingsUserFieldsChanged(_ oldValue: UserSettingsData, _ newValue: UserSettingsData) -> Bool {
        oldValue.dailyWordGoal != newValue.dailyWordGoal ||
            oldValue.maxQueueSize != newValue.maxQueueSize ||
            oldValue.snoozeDurationMinutes != newValue.snoozeDurationMinutes ||
            oldValue.quietHoursStart != newValue.quietHoursStart ||
            oldValue.quietHoursEnd != newValue.quietHoursEnd ||
            oldValue.timezone != newValue.timezone ||
            oldValue.enableWordRecommendations != newValue.enableWordRecommendations ||
            oldValue.recommendationsIntroducedAt != newValue.recommendationsIntroducedAt
    }
}

struct CardEditorView: View {
    var card: VocabularyCard
    @ObservedObject var store: LocalVocabularyStore
    var onClose: () -> Void

    @State private var displayWord = ""
    @State private var lemma = ""
    @State private var meaningKo = ""
    @State private var partOfSpeech = ""
    @State private var definitionEn = ""
    @State private var exampleSentence = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack(alignment: .firstTextBaseline) {
                    Text(displayWord.isEmpty ? card.displayWord : displayWord)
                        .font(.title2)
                        .fontWeight(.semibold)
                    Spacer()
                    Button("닫기", action: onClose)
                }

                VStack(alignment: .leading, spacing: 10) {
                    LabeledContent("단어") {
                        TextField("단어", text: $displayWord)
                            .textFieldStyle(.roundedBorder)
                    }
                    LabeledContent("원형") {
                        TextField("원형", text: $lemma)
                            .textFieldStyle(.roundedBorder)
                    }
                    LabeledContent("뜻") {
                        TextField("뜻", text: $meaningKo)
                            .textFieldStyle(.roundedBorder)
                    }
                    LabeledContent("품사") {
                        TextField("품사", text: $partOfSpeech)
                            .textFieldStyle(.roundedBorder)
                    }
                    LabeledContent("영영 정의") {
                        TextField("영영 정의", text: $definitionEn)
                            .textFieldStyle(.roundedBorder)
                    }
                    LabeledContent("예문") {
                        TextField("예문", text: $exampleSentence)
                            .textFieldStyle(.roundedBorder)
                    }
                }

                HStack {
                    Button("저장") {
                        store.updateCardDetails(
                            cardId: card.id,
                            displayWord: displayWord,
                            lemma: lemma,
                            meaningKo: meaningKo,
                            definitionEn: definitionEn,
                            partOfSpeech: partOfSpeech,
                            exampleSentence: exampleSentence
                        )
                    }
                    .keyboardShortcut(.return)
                    Button("되돌리기") { load(card) }
                    Button("삭제") {
                        store.softDeleteCard(card)
                        onClose()
                    }
                }

                Divider()

                VStack(alignment: .leading, spacing: 6) {
                    Text("\(card.status.rawValue) · \(wordbookLabel(card.wordbook)) · 다음 학습: \(relativeDueLabel(card.dueAt))")
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
                            Button("모르겠음") { store.review(card: card, response: .forgotten) }
                            Button("나중에") { store.review(card: card, response: .unavailable) }
                        }
                    }
                }

                Text(store.message)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(24)
        }
        .onAppear { load(card) }
        .onChange(of: card.id) { _, _ in load(card) }
    }

    private func load(_ card: VocabularyCard) {
        displayWord = card.displayWord
        lemma = card.lemma
        meaningKo = card.meaningKo
        partOfSpeech = card.partOfSpeech
        definitionEn = card.definitionEn
        exampleSentence = card.exampleSentence
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
            Text("\(wordbookLabel(card.wordbook)) · \(card.status.rawValue) · 다음 학습: \(relativeDueLabel(card.dueAt))")
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
                    Button("모르겠음") { store.review(card: card, response: .forgotten) }
                    Button("나중에") { store.review(card: card, response: .unavailable) }
                    Button("삭제") { store.softDeleteCard(card) }
                }
            }
        }
        .padding(.vertical, 8)
    }
}

private func wordbookLabel(_ wordbook: Wordbook) -> String {
    switch wordbook {
    case .user:
        return "내 단어장"
    case .recommended:
        return "추천 단어장"
    }
}

private func relativeDueLabel(_ dueAt: Date, now: Date = Date()) -> String {
    guard dueAt > now else { return "지금" }
    let seconds = dueAt.timeIntervalSince(now)
    let minutes = Int(ceil(seconds / 60))
    if minutes < 60 {
        return "\(minutes)분 뒤"
    }
    let hours = Int(ceil(seconds / 3_600))
    if hours < 24 {
        return "\(hours)시간 뒤"
    }

    let calendar = Calendar.current
    let days = calendar.dateComponents(
        [.day],
        from: calendar.startOfDay(for: now),
        to: calendar.startOfDay(for: dueAt)
    ).day ?? 0
    let formatter = DateFormatter()
    formatter.dateFormat = "HH:mm"
    if days == 1 {
        return "내일 \(formatter.string(from: dueAt))"
    }
    return "\(days)일 뒤"
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
