import Foundation
import Testing
@testable import VocabCore

@Suite(.serialized)
@MainActor
final class IncrementalSyncTests {
    @Test func testNegotiatedChangesPersistCursorAndNextPullUsesIt() async throws {
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
        #expect(pulls.value == 2)
    }

    nonisolated private static func installHandler(cursor: String) -> LockedRequestCounter {
        let pulls = LockedRequestCounter()
        DictionaryURLProtocolStub.handler = { request in
            if request.url?.path == "/sync/batch" {
                return (200, [:], try DictionaryURLProtocolStub.jsonData(["applied": [:], "skipped": [:], "sync_protocol": 3]))
            }
            #expect(request.url?.path == "/sync/changes")
            let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems
            #expect(query?.first { $0.name == "cursor" }?.value == (pulls.value == 0 ? "" : cursor))
            pulls.increment()
            return (200, [:], try DictionaryURLProtocolStub.jsonData([
                "cards": [], "contexts": [], "definition_candidates": [], "review_events": [], "recommendation_feedback": [],
                "settings": NSNull(), "sync_cursor": cursor, "has_more": false
            ]))
        }
        return pulls
    }

    @Test func testMalformedPageAndNonAdvancingCursorAreRejected() {
        var accumulator = SyncChangeAccumulator()
        #expect(throws: (any Error).self) { try accumulator.append(["sync_cursor": "same", "has_more": true], previousCursor: "same") }
        #expect(throws: (any Error).self) { try accumulator.append(["sync_cursor": "next", "has_more": false], previousCursor: "") }
    }

    @Test func testRecommendationIdentityNormalizesAcrossDevices() {
        #expect(RecommendationEngine.normalizedLemma("  ＳＵＢＴＬＥ  ") == "subtle")
    }

}

extension IncrementalSyncTests {
    @Test func testOnlyUnacknowledgedLocalChangesAreUploaded() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = LocalVocabularyStore(appSupportURL: directory, scheduleNotifications: false, previewDictionaryLookup: { _ in nil })
        store.capture(text: "outbox", method: .manual, sourceApp: "test")
        store.savePreview(meaningOverride: "보낼 편지함", exampleOverride: "")
        let card = try #require(store.activeCards.first)

        let first = try store.pendingUpload()
        let firstCards = try #require(try first.payload()["cards"] as? [[String: Any]])
        #expect(firstCards.map { $0["id"] as? String } == [card.id])
        try store.acknowledgeUpload(first.digests)
        #expect(try store.pendingUpload().digests.isEmpty)

        store.softDeleteCard(card)
        let second = try #require(try store.pendingUpload().payload()["cards"] as? [[String: Any]])
        #expect(second.count == 1)
        #expect(second[0]["deleted_at"] is String)
    }

    @Test func testReloadReadsBackEveryRowFromSQLite() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = LocalVocabularyStore(appSupportURL: directory, scheduleNotifications: false, previewDictionaryLookup: { _ in nil })
        store.capture(text: "durable", method: .manual, sourceApp: "test")
        store.savePreview(meaningOverride: "오래가는", exampleOverride: "A durable record.")
        let card = try #require(store.activeCards.first)
        store.review(card: card, response: .remembered)

        let reloaded = LocalVocabularyStore(appSupportURL: directory, scheduleNotifications: false)
        #expect(reloaded.cards.map(\.id) == store.cards.map(\.id))
        #expect(reloaded.cards.map(\.meaningKo) == store.cards.map(\.meaningKo))
        #expect(reloaded.cards.map(\.status) == store.cards.map(\.status))
        #expect(reloaded.reviewEvents.count == store.reviewEvents.count)
        #expect(reloaded.contexts.count == store.contexts.count)
        let reloadedCard = try #require(reloaded.activeCards.first)
        #expect(reloadedCard.scheduleVersion == card.scheduleVersion + 1)
        #expect(reloadedCard.contentVersion == card.contentVersion)
    }
}
