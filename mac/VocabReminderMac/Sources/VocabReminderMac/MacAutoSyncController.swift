import Foundation

@MainActor
final class MacAutoSyncController {
    static let shared = MacAutoSyncController()

    private weak var store: LocalVocabularyStore?
    private var syncTask: Task<Void, Never>?
    private var debounceTask: Task<Void, Never>?
    private var timerTask: Task<Void, Never>?

    private init() {}

    func start(store: LocalVocabularyStore) {
        self.store = store
        syncNow(reason: "startup")
        guard timerTask == nil else { return }
        timerTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 3_600_000_000_000)
                self?.syncNow(reason: "timer")
            }
        }
    }

    func enqueueDebounced() {
        debounceTask?.cancel()
        debounceTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 300_000_000_000)
            self?.syncNow(reason: "debounced")
        }
    }

    func syncNow(reason: String) {
        guard syncTask == nil else { return }
        guard let store else { return }
        guard let credentials = SyncDefaults.effectiveCredentials() else {
            return
        }
        syncTask = Task { @MainActor [weak self, weak store] in
            defer { self?.syncTask = nil }
            guard let store else { return }
            do {
                let summary = try await store.syncWithServer(
                    serverBaseURL: credentials.serverURL,
                    authToken: credentials.bearerToken
                )
                MacSyncStatusStore.recordSuccess(skippedSummary: summary.skippedSummary)
                if reason != "timer" {
                    store.message = summary.skippedSummary.isEmpty
                        ? "자동 동기화 완료 · 카드 \(summary.cards), 이벤트 \(summary.reviewEvents)"
                        : "자동 동기화 완료 · 스킵 \(summary.skippedSummary)"
                }
            } catch {
                MacSyncStatusStore.recordFailure(error.localizedDescription)
                if reason != "timer" {
                    store.message = "자동 동기화 대기: \(error.localizedDescription)"
                }
            }
        }
    }
}

enum MacSyncStatusStore {
    static func recordSuccess(skippedSummary: String = "") {
        let defaults = UserDefaults.standard
        defaults.set(Date().formatted(date: .abbreviated, time: .shortened), forKey: "lastLocalSyncSummary")
        defaults.set("", forKey: "lastLocalSyncError")
        defaults.set(skippedSummary, forKey: "lastLocalSyncSkipped")
    }

    static func recordFailure(_ error: String) {
        UserDefaults.standard.set(error, forKey: "lastLocalSyncError")
    }
}
