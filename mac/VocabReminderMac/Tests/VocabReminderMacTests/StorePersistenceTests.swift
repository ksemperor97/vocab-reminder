import Foundation
import XCTest
@testable import VocabReminderMac

@MainActor
final class StorePersistenceTests: XCTestCase {
    func testFailedCaptureKeepsPreviewAndCommittedFileThenRetrySucceeds() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        var fail = false
        let store = LocalVocabularyStore(appSupportURL: directory, scheduleNotifications: false, writeStoreData: { data, url in
            if fail { throw CocoaError(.fileWriteOutOfSpace) }
            try data.write(to: url, options: .atomic)
        })
        store.settings.enableWordRecommendations = false
        store.persistSettings()
        let file = directory.appendingPathComponent("vocab-data.json")
        let before = try Data(contentsOf: file)
        store.capture(text: "persistword", method: .manual)
        fail = true
        store.savePreview(meaningOverride: "지속하다", exampleOverride: "Keep the input.")
        XCTAssertEqual(try Data(contentsOf: file), before)
        XCTAssertTrue(store.cards.isEmpty)
        XCTAssertTrue(store.contexts.isEmpty)
        XCTAssertNotNil(store.preview)
        XCTAssertNotNil(store.storageError)
        XCTAssertTrue(store.message.contains("저장 실패"))
        fail = false
        store.savePreview(meaningOverride: "지속하다", exampleOverride: "Keep the input.")
        XCTAssertNil(store.storageError)
        XCTAssertNil(store.preview)
        XCTAssertEqual(store.cards.count, 1)
        let reloaded = LocalVocabularyStore(appSupportURL: directory, scheduleNotifications: false)
        XCTAssertEqual(reloaded.cards.first?.meaningKo, "지속하다")
    }

    func testFailedReviewRollsBackBothProgressAndEvent() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        var fail = false
        var writes = 0
        let store = LocalVocabularyStore(appSupportURL: directory, scheduleNotifications: false, writeStoreData: { data, url in
            if fail { throw CocoaError(.fileWriteOutOfSpace) }
            writes += 1
            try data.write(to: url, options: .atomic)
        })
        store.settings.enableWordRecommendations = false
        store.persistSettings()
        store.capture(text: "persistword")
        store.savePreview(meaningOverride: "뜻", exampleOverride: "")
        let card = try XCTUnwrap(store.cards.first)
        let eventCount = store.reviewEvents.count
        fail = true
        store.review(card: card, response: .remembered)
        XCTAssertEqual(store.cards.first?.version, card.version)
        XCTAssertEqual(store.cards.first?.dueAt, card.dueAt)
        XCTAssertEqual(store.reviewEvents.count, eventCount)
        XCTAssertNotNil(store.storageError)
        fail = false
        let before = writes
        store.review(card: card, response: .remembered)
        XCTAssertEqual(writes - before, 1, "A review and its history must commit together")
        XCTAssertEqual(store.reviewEvents.count, eventCount + 1)
    }
}
