import Foundation
import Testing
@testable import VocabCore

@Suite(.serialized)
@MainActor
final class RecommendationAvailabilityTests {
    private func serverStore(at directory: URL) throws -> LocalVocabularyStore {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let payload: [String: Any] = ["cards": [[
            "id": "server-recommendation-example", "lemma": "serveronlyword", "display_word": "serveronlyword",
            "meaning_ko": "서버에서 내려온 뜻", "wordbook": "recommended", "status": "new", "version": 1,
            "due_at": "2026-09-23T01:00:00Z", "created_at": "2026-09-23T01:00:00Z", "updated_at": "2026-09-23T01:00:00Z"
        ]]]
        let snapshot = try SyncBatchFactory.localSnapshot(from: JSONSerialization.data(withJSONObject: payload))
        try JSONEncoder.vocab.encode(snapshot).write(to: directory.appendingPathComponent("vocab-data.json"), options: .atomic)
        return LocalVocabularyStore(appSupportURL: directory, scheduleNotifications: false)
    }

    @Test func testKnownFeedbackSurvivesReloadAndResurrectedServerCard() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try serverStore(at: directory)
        let card = try #require(store.activeCards.first)
        store.softDeleteRecommendation(card, action: "known")
        // The legacy vocab-data.json was migrated into SQLite on first open.
        #expect(FileManager.default.fileExists(atPath: directory.appendingPathComponent("vocab-data.json.migrated").path))
        // A stale active copy arriving from elsewhere must not bring the word back.
        var stale = SyncBatchFactory.card(card, deviceId: "mac-test")
        stale["deleted_at"] = NSNull()
        stale["lemma"] = "  " + card.lemma.uppercased() + "  "
        try JSONSerialization.data(withJSONObject: ["cards": [stale]])
            .write(to: directory.appendingPathComponent("vocab-reminder-sync.json"), options: .atomic)
        store.importSyncSnapshot()
        let reloaded = LocalVocabularyStore(appSupportURL: directory, scheduleNotifications: false)
        #expect(reloaded.activeCards.isEmpty)
        #expect(reloaded.ensureRecommendations(now: Date().addingTimeInterval(86400)) == 0)
    }

    @Test func testColdStartAndNewDayNeverGenerateLocalWords() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = LocalVocabularyStore(appSupportURL: directory, scheduleNotifications: false)
        #expect(store.ensureRecommendations() == 0)
        #expect(store.ensureRecommendations(now: Date().addingTimeInterval(86400)) == 0)
        #expect(store.activeCards.isEmpty)
        #expect(store.recommendationStatusMessage.contains("동기화"))
        store.settings.enableWordRecommendations = false
        store.persistSettings()
        #expect(store.recommendationStatusMessage.contains("꺼져"))
    }

    @Test func testServerOnlyWordIsAvailableOfflineWithoutBundledCatalogue() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try serverStore(at: directory)
        #expect(store.activeCards.first?.meaningKo == "서버에서 내려온 뜻")
        #expect(store.ensureRecommendations() == 0)
        #expect(store.activeCards.count == 1)
        store.markRecommendationStarted(try #require(store.activeCards.first))
        #expect(store.activeCards.first?.recommendationStartedAt != nil)
    }
}
