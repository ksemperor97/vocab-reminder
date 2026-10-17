import Foundation
import XCTest
@testable import VocabReminderMac

@MainActor
final class IncrementalSyncTests: XCTestCase {
    func testNegotiatedChangesPersistCursorAndNextPullUsesIt() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory); DictionaryURLProtocolStub.handler = nil }
        let store = LocalVocabularyStore(appSupportURL: directory, scheduleNotifications: false)
        store.settings.enableWordRecommendations = false
        store.persistSettings()
        let cursor = "00000000-0000-0000-0000-000000000001:42"
        let pulls = Self.installHandler(cursor: cursor)
        let session = DictionaryURLProtocolStub.session()
        _ = try await store.syncWithServer(serverBaseURL: "https://example.invalid", authToken: "test", session: session)
        let reloaded = LocalVocabularyStore(appSupportURL: directory, scheduleNotifications: false)
        _ = try await reloaded.syncWithServer(serverBaseURL: "https://example.invalid", authToken: "test", session: session)
        XCTAssertEqual(pulls.value, 2)
    }

    nonisolated private static func installHandler(cursor: String) -> LockedRequestCounter {
        let pulls = LockedRequestCounter()
        DictionaryURLProtocolStub.handler = { request in
            if request.url?.path == "/sync/batch" {
                return (200, [:], try DictionaryURLProtocolStub.jsonData(["applied": [:], "skipped": [:], "sync_protocol": 2]))
            }
            XCTAssertEqual(request.url?.path, "/sync/changes")
            let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems
            XCTAssertEqual(query?.first { $0.name == "cursor" }?.value, pulls.value == 0 ? "" : cursor)
            pulls.increment()
            return (200, [:], try DictionaryURLProtocolStub.jsonData([
                "cards": [], "contexts": [], "definition_candidates": [], "review_events": [], "recommendation_feedback": [],
                "settings": NSNull(), "sync_cursor": cursor, "has_more": false
            ]))
        }
        return pulls
    }

    func testMalformedPageAndNonAdvancingCursorAreRejected() {
        var accumulator = SyncChangeAccumulator()
        XCTAssertThrowsError(try accumulator.append(["sync_cursor": "same", "has_more": true], previousCursor: "same"))
        XCTAssertThrowsError(try accumulator.append(["sync_cursor": "next", "has_more": false], previousCursor: ""))
    }

    func testRecommendationIdentityNormalizesAcrossDevices() {
        XCTAssertEqual(RecommendationEngine.normalizedLemma("  ＳＵＢＴＬＥ  "), "subtle")
    }

}

extension IncrementalSyncTests {
    func testUploadAcknowledgementsSendOnlyChangedRecordsIncludingDeletion() throws {
        let initial: [String: Any] = ["cards": [["id": "a", "version": 1], ["id": "b", "version": 1]], "settings": ["daily_word_goal": 10]]
        let acknowledged = try SyncUploadState.fingerprints(initial)
        let unchanged = try SyncUploadState.pending(initial, acknowledged: acknowledged)
        XCTAssertEqual((unchanged["cards"] as? [[String: Any]])?.count, 0)
        XCTAssertTrue(unchanged["settings"] is NSNull)
        let changed: [String: Any] = ["cards": [["id": "a", "version": 2, "deleted_at": "2026-09-08T00:00:00Z"], ["id": "b", "version": 1]], "settings": ["daily_word_goal": 10]]
        let pending = try SyncUploadState.pending(changed, acknowledged: acknowledged)
        let cards = try XCTUnwrap(pending["cards"] as? [[String: Any]])
        XCTAssertEqual(cards.count, 1)
        XCTAssertEqual(cards[0]["id"] as? String, "a")
        XCTAssertNotNil(cards[0]["deleted_at"])
        XCTAssertEqual((try SyncUploadState.pending(changed, acknowledged: [:])["cards"] as? [[String: Any]])?.count, 2)
    }
}
