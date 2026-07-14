import Foundation
import XCTest
@testable import VocabReminderMac

@MainActor
final class MeaningConflictFixtureTests: XCTestCase {
    func testSharedMeaningConflictFixtureMatchesMergeExportAndRestoreContract() throws {
        let fixture = try loadFixture()
        let cases = try XCTUnwrap(fixture["cases"] as? [[String: Any]])

        for fixtureCase in cases {
            let name = try XCTUnwrap(fixtureCase["name"] as? String)
            let androidPayload = try XCTUnwrap(fixtureCase["android_offline"] as? [String: Any])
            let macPayload = try XCTUnwrap(fixtureCase["mac_offline"] as? [String: Any])
            let expected = try XCTUnwrap(fixtureCase["expected"] as? [String: Any])
            let androidSnapshot = try snapshot(from: androidPayload)
            let macSnapshot = try snapshot(from: macPayload)

            let forward = SyncBatchFactory.merge(local: androidSnapshot, incoming: macSnapshot)
            let reverse = SyncBatchFactory.merge(local: macSnapshot, incoming: androidSnapshot)
            try assertMeaningConflictState(forward, expected: expected, name: name)
            try assertMeaningConflictState(reverse, expected: expected, name: name)

            let exported = try SyncBatchFactory.jsonData(
                cards: forward.cards,
                contexts: forward.contexts,
                definitionCandidates: forward.definitionCandidates,
                reviewEvents: forward.reviewEvents,
                recommendationFeedback: forward.recommendationFeedback,
                settings: forward.settings,
                deviceId: "mac-fixture-export"
            )
            let reimported = try SyncBatchFactory.localSnapshot(from: exported)
            try assertMeaningConflictState(reimported, expected: expected, name: name)

            try assertStoreRestoreRoundTrip(exported: exported, expected: expected, name: name)
        }
    }

    private func assertStoreRestoreRoundTrip(
        exported: Data,
        expected: [String: Any],
        name: String
    ) throws {
        let sourceDirectory = try makeDirectory(prefix: "MeaningConflictSource")
        let importedDirectory = try makeDirectory(prefix: "MeaningConflictImported")
        defer {
            try? FileManager.default.removeItem(at: sourceDirectory)
            try? FileManager.default.removeItem(at: importedDirectory)
        }

        try exported.write(
            to: sourceDirectory.appendingPathComponent("vocab-reminder-sync.json"),
            options: .atomic
        )
        let sourceStore = LocalVocabularyStore(
            appSupportURL: sourceDirectory,
            scheduleNotifications: false
        )
        sourceStore.importSyncSnapshot()
        try assertMeaningConflictState(
            cards: sourceStore.cards,
            candidates: sourceStore.definitionCandidates,
            expected: expected,
            name: name
        )

        let restore = try XCTUnwrap(expected["restore"] as? [String: Any])
        let cardID = try XCTUnwrap(expected["card_id"] as? String)
        let restoreCandidateID = try XCTUnwrap(restore["candidate_id"] as? String)
        let restoredMeaning = try XCTUnwrap(restore["meaning_ko"] as? String)
        sourceStore.restoreDefinitionCandidate(cardId: cardID, candidateId: restoreCandidateID)
        XCTAssertEqual(sourceStore.cards.first(where: { $0.id == cardID })?.meaningKo, restoredMeaning, name)

        sourceStore.exportSyncSnapshot()
        try FileManager.default.copyItem(
            at: sourceDirectory.appendingPathComponent("vocab-reminder-sync.json"),
            to: importedDirectory.appendingPathComponent("vocab-reminder-sync.json")
        )
        let importedStore = LocalVocabularyStore(
            appSupportURL: importedDirectory,
            scheduleNotifications: false
        )
        importedStore.importSyncSnapshot()

        let preservedIDs = Set(try XCTUnwrap(expected["preserved_candidate_ids"] as? [String]))
        let importedIDs = Set(
            importedStore.definitionCandidates
                .filter { $0.cardId == cardID }
                .map(\.id)
        )
        XCTAssertEqual(importedStore.cards.first(where: { $0.id == cardID })?.meaningKo, restoredMeaning, name)
        XCTAssertTrue(preservedIDs.isSubset(of: importedIDs), name)
    }

    private func assertMeaningConflictState(
        _ snapshot: StoreSnapshot,
        expected: [String: Any],
        name: String
    ) throws {
        try assertMeaningConflictState(
            cards: snapshot.cards,
            candidates: snapshot.definitionCandidates,
            expected: expected,
            name: name
        )
    }

    private func assertMeaningConflictState(
        cards: [VocabularyCard],
        candidates: [StoredDefinitionCandidate],
        expected: [String: Any],
        name: String
    ) throws {
        let cardID = try XCTUnwrap(expected["card_id"] as? String)
        let primary = try XCTUnwrap(expected["primary"] as? [String: Any])
        let primaryCandidateID = try XCTUnwrap(primary["candidate_id"] as? String)
        let primaryMeaning = try XCTUnwrap(primary["meaning_ko"] as? String)
        let preservedIDs = Set(try XCTUnwrap(expected["preserved_candidate_ids"] as? [String]))
        let cardCandidates = candidates.filter { $0.cardId == cardID }

        XCTAssertEqual(cards.first(where: { $0.id == cardID })?.meaningKo, primaryMeaning, name)
        XCTAssertEqual(Set(cardCandidates.map(\.id)), preservedIDs, name)
        XCTAssertEqual(
            cardCandidates.first(where: { $0.id == primaryCandidateID })?.parsedMeaningKo,
            primaryMeaning,
            name
        )
        let expectedConflictIDs = Set(try XCTUnwrap(expected["conflict_candidate_ids"] as? [String]))
        XCTAssertEqual(
            CardBiographyBuilder.preservedAlternativeCandidateIDs(
                card: try XCTUnwrap(cards.first(where: { $0.id == cardID })),
                candidates: cardCandidates
            ),
            expectedConflictIDs,
            name
        )
    }

    private func snapshot(from payload: [String: Any]) throws -> StoreSnapshot {
        let data = try JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys])
        return try SyncBatchFactory.localSnapshot(from: data)
    }

    private func loadFixture() throws -> [String: Any] {
        var directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while directory.path != "/" {
            let candidate = directory
                .appendingPathComponent("shared")
                .appendingPathComponent("test-vectors")
                .appendingPathComponent("meaning_conflicts.json")
            if FileManager.default.fileExists(atPath: candidate.path) {
                let data = try Data(contentsOf: candidate)
                return try XCTUnwrap(
                    JSONSerialization.jsonObject(with: data) as? [String: Any]
                )
            }
            directory.deleteLastPathComponent()
        }
        throw FixtureError.notFound
    }

    private func makeDirectory(prefix: String) throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(prefix)-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }
}

private enum FixtureError: Error {
    case notFound
}
