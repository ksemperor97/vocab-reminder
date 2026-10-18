import Foundation
import Testing
@testable import VocabCore

@Suite(.serialized)
@MainActor
final class StorePersistenceTests {
    @Test func testFailedCaptureKeepsPreviewAndCommittedFileThenRetrySucceeds() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        nonisolated(unsafe) var fail = false
        let store = LocalVocabularyStore(appSupportURL: directory, scheduleNotifications: false, beforeCommit: {
            if fail { throw CocoaError(.fileWriteOutOfSpace) }
        })
        store.settings.enableWordRecommendations = false
        store.persistSettings()
        store.capture(text: "persistword", method: .manual)
        fail = true
        store.savePreview(meaningOverride: "지속하다", exampleOverride: "Keep the input.")
        // The failed transaction rolled back: nothing reached SQLite.
        #expect(LocalVocabularyStore(appSupportURL: directory, scheduleNotifications: false).cards.isEmpty)
        #expect(store.cards.isEmpty)
        #expect(store.contexts.isEmpty)
        #expect(store.preview != nil)
        #expect(store.storageError != nil)
        #expect(store.message.contains("저장 실패"))
        fail = false
        store.savePreview(meaningOverride: "지속하다", exampleOverride: "Keep the input.")
        #expect(store.storageError == nil)
        #expect(store.preview == nil)
        #expect(store.cards.count == 1)
        let reloaded = LocalVocabularyStore(appSupportURL: directory, scheduleNotifications: false)
        #expect(reloaded.cards.first?.meaningKo == "지속하다")
    }

    @Test func testFailedReviewRollsBackBothProgressAndEvent() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        nonisolated(unsafe) var fail = false
        nonisolated(unsafe) var writes = 0
        let store = LocalVocabularyStore(appSupportURL: directory, scheduleNotifications: false, beforeCommit: {
            if fail { throw CocoaError(.fileWriteOutOfSpace) }
            writes += 1
        })
        store.settings.enableWordRecommendations = false
        store.persistSettings()
        store.capture(text: "persistword")
        store.savePreview(meaningOverride: "뜻", exampleOverride: "")
        let card = try #require(store.cards.first)
        let eventCount = store.reviewEvents.count
        fail = true
        store.review(card: card, response: .remembered)
        #expect(store.cards.first?.version == card.version)
        #expect(store.cards.first?.dueAt == card.dueAt)
        #expect(store.reviewEvents.count == eventCount)
        #expect(store.storageError != nil)
        fail = false
        let before = writes
        store.review(card: card, response: .remembered)
        #expect(writes - before == 1, "A review and its history must commit together")
        #expect(store.reviewEvents.count == eventCount + 1)
        let reloaded = LocalVocabularyStore(appSupportURL: directory, scheduleNotifications: false)
        #expect(reloaded.reviewEvents.count == eventCount + 1)
    }
}
