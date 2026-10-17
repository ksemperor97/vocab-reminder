import Foundation
import XCTest
@testable import VocabReminderMac

@MainActor
final class RecommendationAvailabilityTests: XCTestCase {
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

    func testKnownFeedbackSurvivesReloadAndResurrectedServerCard() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try serverStore(at: directory)
        let card = try XCTUnwrap(store.activeCards.first)
        store.softDeleteRecommendation(card, action: "known")
        let url = directory.appendingPathComponent("vocab-data.json")
        var snapshot = try SyncBatchFactory.localSnapshot(from: Data(contentsOf: url))
        snapshot.cards[0].deletedAt = nil
        snapshot.cards[0].lemma = "  " + card.lemma.uppercased() + "  "
        try JSONEncoder.vocab.encode(snapshot).write(to: url, options: .atomic)
        let reloaded = LocalVocabularyStore(appSupportURL: directory, scheduleNotifications: false)
        XCTAssertTrue(reloaded.activeCards.isEmpty)
        XCTAssertEqual(reloaded.ensureRecommendations(now: Date().addingTimeInterval(86400)), 0)
    }

    func testColdStartAndNewDayNeverGenerateLocalWords() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = LocalVocabularyStore(appSupportURL: directory, scheduleNotifications: false)
        XCTAssertEqual(store.ensureRecommendations(), 0)
        XCTAssertEqual(store.ensureRecommendations(now: Date().addingTimeInterval(86400)), 0)
        XCTAssertTrue(store.activeCards.isEmpty)
        XCTAssertTrue(store.recommendationStatusMessage.contains("동기화"))
        store.settings.enableWordRecommendations = false
        store.persistSettings()
        XCTAssertTrue(store.recommendationStatusMessage.contains("꺼져"))
    }

    func testServerOnlyWordIsAvailableOfflineWithoutBundledCatalogue() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try serverStore(at: directory)
        XCTAssertEqual(store.activeCards.first?.meaningKo, "서버에서 내려온 뜻")
        XCTAssertEqual(store.ensureRecommendations(), 0)
        XCTAssertEqual(store.activeCards.count, 1)
        store.markRecommendationStarted(try XCTUnwrap(store.activeCards.first))
        XCTAssertNotNil(store.activeCards.first?.recommendationStartedAt)
    }
}
