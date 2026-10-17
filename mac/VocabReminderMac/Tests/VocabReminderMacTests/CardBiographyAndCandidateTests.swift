import Foundation
import Testing
@testable import VocabCore

@Suite(.serialized)
@MainActor
final class CardBiographyAndCandidateTests {
    @Test func testBiographyIsChronologicalAndMarksOnlyNewestMatchingCandidateCurrent() {
        let base = Date(timeIntervalSince1970: 1_784_000_000)
        let card = makeCard(meaning: "두 번째 뜻", updatedAt: base.addingTimeInterval(40))
        let first = makeCandidate(
            id: "candidate-a",
            meaning: "두 번째 뜻",
            definition: "stale definition",
            at: base.addingTimeInterval(10),
            originDeviceId: "android-a",
            baseCardVersion: 1
        )
        let matchingOlder = makeCandidate(
            id: "candidate-b",
            meaning: "두 번째 뜻",
            at: base.addingTimeInterval(20),
            originDeviceId: "mac-b",
            baseCardVersion: 1
        )
        let matchingNewer = makeCandidate(
            id: "candidate-c",
            meaning: "  두 번째 뜻 ",
            definition: " ABLE  to recover ",
            partOfSpeech: " Adjective ",
            exampleSentence: "A resilient   system recovers.",
            at: base.addingTimeInterval(30),
            originDeviceId: "mac-b",
            baseCardVersion: 1
        )
        let context = StoredContext(
            id: "context",
            cardId: card.id,
            sourceSentence: "A context.",
            sourceURL: "https://example.com/article",
            pageTitle: "Research Article",
            sourceApp: MacCaptureSource.service,
            captureMethod: .macService,
            createdAt: base.addingTimeInterval(40)
        )

        let items = CardBiographyBuilder.items(
            card: card,
            contexts: [context],
            candidates: [first, matchingOlder, matchingNewer],
            reviewEvents: [],
            syncEvents: []
        )

        #expect(items.map(\.occurredAt) == items.map(\.occurredAt).sorted(by: >))
        #expect(items.compactMap { item -> StoredContext? in
                guard case .context(let context) = item.kind else { return nil }
                return context
            }.first?.pageTitle == "Research Article")
        #expect(CardBiographyBuilder.currentPrimaryCandidateID(
                card: card,
                candidates: [first, matchingOlder, matchingNewer]
            ) == matchingNewer.id)
        let candidates = [first, matchingOlder, matchingNewer]
        #expect(CardBiographyBuilder.preservedAlternativeCandidateIDs(card: card, candidates: candidates) == Set(candidates.map(\.id)))
        #expect(CardBiographyBuilder.candidateConflicts(first, card: card, candidates: candidates))
        #expect(CardBiographyBuilder.candidateConflicts(matchingNewer, card: card, candidates: candidates))
    }

    @Test func testRepeatedSelectedCandidatesWithSameNormalizedProjectionAreNotAlternatives() {
        let base = Date(timeIntervalSince1970: 1_784_000_000)
        let card = makeCard(meaning: "뜻", updatedAt: base)
        let first = makeCandidate(
            id: "candidate-a",
            meaning: "뜻",
            at: base.addingTimeInterval(1),
            originDeviceId: "android-a",
            baseCardVersion: 1
        )
        let duplicate = makeCandidate(
            id: "candidate-b",
            meaning: " 뜻 ",
            definition: " ABLE  TO recover ",
            partOfSpeech: "ADJECTIVE",
            exampleSentence: "A resilient  system recovers.",
            at: base.addingTimeInterval(2),
            originDeviceId: "mac-b",
            baseCardVersion: 1
        )

        #expect(CardBiographyBuilder.currentPrimaryCandidateID(card: card, candidates: [first, duplicate]) == duplicate.id)
        #expect(CardBiographyBuilder.preservedAlternativeCandidateIDs(
                card: card,
                candidates: [first, duplicate]
            ).isEmpty)
    }

    @Test func testSequentialEditsAreNotMarkedAsConflicts() {
        let base = Date(timeIntervalSince1970: 1_784_000_000)
        var card = makeCard(meaning: "세 번째 뜻", updatedAt: base.addingTimeInterval(2))
        card.version = 3
        let firstEdit = makeCandidate(
            id: "first-edit",
            meaning: "두 번째 뜻",
            at: base.addingTimeInterval(1),
            originDeviceId: "mac-a",
            baseCardVersion: 1
        )
        let secondEdit = makeCandidate(
            id: "second-edit",
            meaning: "세 번째 뜻",
            at: base.addingTimeInterval(2),
            originDeviceId: "mac-a",
            baseCardVersion: 2
        )

        #expect(CardBiographyBuilder.preservedAlternativeCandidateIDs(
                card: card,
                candidates: [firstEdit, secondEdit]
            ).isEmpty)
        #expect(!(CardBiographyBuilder.candidateConflicts(firstEdit, card: card, candidates: [firstEdit, secondEdit])))
        #expect(!(CardBiographyBuilder.candidateConflicts(secondEdit, card: card, candidates: [firstEdit, secondEdit])))
    }

    @Test func testLegacyCandidatesWithoutLineageAreNotMarkedAsConflicts() {
        let base = Date(timeIntervalSince1970: 1_784_000_000)
        let card = makeCard(meaning: "새 뜻", updatedAt: base)
        let old = makeCandidate(id: "old", meaning: "옛 뜻", at: base)
        let current = makeCandidate(id: "current", meaning: "새 뜻", at: base.addingTimeInterval(1))

        #expect(CardBiographyBuilder.preservedAlternativeCandidateIDs(
                card: card,
                candidates: [old, current]
            ).isEmpty)
    }

    @Test func testAndroidPageTitleSurvivesSyncImportAndMacExport() throws {
        let payload: [String: Any] = [
            "cards": [],
            "contexts": [[
                "id": "android-context",
                "card_id": "android-card",
                "source_sentence": "A captured sentence.",
                "source_url": "https://example.com/article?private=value",
                "page_title": "Android Research Article",
                "source_app": "com.example.reader",
                "capture_method": "android_share",
                "created_at": "2026-07-13T00:00:00.000Z"
            ]],
            "definition_candidates": [],
            "review_events": [],
            "recommendation_feedback": [],
            "settings": [:]
        ]
        let imported = try SyncBatchFactory.localSnapshot(
            from: JSONSerialization.data(withJSONObject: payload)
        )

        let context = try #require(imported.contexts.first)
        #expect(context.pageTitle == "Android Research Article")
        #expect(context.sourceURL == "https://example.com/article")

        let exportedData = try SyncBatchFactory.jsonData(
            cards: [],
            contexts: imported.contexts,
            definitionCandidates: [],
            reviewEvents: [],
            settings: imported.settings,
            deviceId: "mac-test0000"
        )
        let exported = try #require(JSONSerialization.jsonObject(with: exportedData) as? [String: Any])
        let exportedContexts = try #require(exported["contexts"] as? [[String: Any]])
        #expect(exportedContexts.first?["page_title"] as? String == "Android Research Article")

        let reimported = try SyncBatchFactory.localSnapshot(from: exportedData)
        #expect(reimported.contexts.first?.pageTitle == "Android Research Article")
    }

    @Test func testTwoOfflineMeaningsSurviveRestoreThenExportAndReimport() throws {
        let sourceDirectory = try makeDirectory(prefix: "CandidateSource")
        let importedDirectory = try makeDirectory(prefix: "CandidateImported")
        defer {
            try? FileManager.default.removeItem(at: sourceDirectory)
            try? FileManager.default.removeItem(at: importedDirectory)
        }

        let base = Date(timeIntervalSince1970: 1_784_000_000)
        let originalCard = makeCard(meaning: "첫 번째 오프라인 뜻", updatedAt: base)
        let first = makeCandidate(
            id: "offline-a",
            meaning: "첫 번째 오프라인 뜻",
            at: base,
            selected: true
        )
        let second = makeCandidate(
            id: "offline-b",
            meaning: "두 번째 오프라인 뜻",
            at: base.addingTimeInterval(1),
            selected: false
        )
        let snapshot = StoreSnapshot(
            cards: [originalCard],
            contexts: [],
            definitionCandidates: [first, second],
            reviewEvents: [],
            settings: UserSettingsData()
        )
        try JSONEncoder.vocab.encode(snapshot)
            .write(to: sourceDirectory.appendingPathComponent("vocab-data.json"), options: .atomic)

        let sourceStore = LocalVocabularyStore(appSupportURL: sourceDirectory, scheduleNotifications: false)
        sourceStore.restoreDefinitionCandidate(cardId: originalCard.id, candidateId: second.id)

        #expect(sourceStore.cards.first?.meaningKo == "두 번째 오프라인 뜻")
        #expect(Set(sourceStore.definitionCandidates.map(\.parsedMeaningKo)) == Set(["첫 번째 오프라인 뜻", "두 번째 오프라인 뜻"]))
        #expect(sourceStore.definitionCandidates.count == 3)
        #expect(sourceStore.definitionCandidates.contains { $0.id == first.id })
        #expect(sourceStore.definitionCandidates.contains { $0.id == second.id && $0.selectedAsPrimary == false })
        #expect(sourceStore.definitionCandidates.last?.selectedAsPrimary == true)
        #expect(sourceStore.definitionCandidates.last?.baseCardVersion == originalCard.version)
        #expect(sourceStore.definitionCandidates.last?.originDeviceId != nil)
        #expect(sourceStore.reviewEvents.last?.metadata["action"] == "definition_candidate_restored")

        sourceStore.exportSyncSnapshot()
        try FileManager.default.copyItem(
            at: sourceDirectory.appendingPathComponent("vocab-reminder-sync.json"),
            to: importedDirectory.appendingPathComponent("vocab-reminder-sync.json")
        )
        let importedStore = LocalVocabularyStore(appSupportURL: importedDirectory, scheduleNotifications: false)
        importedStore.importSyncSnapshot()

        #expect(importedStore.cards.first?.meaningKo == "두 번째 오프라인 뜻")
        #expect(Set(importedStore.definitionCandidates.map(\.parsedMeaningKo)) == Set(["첫 번째 오프라인 뜻", "두 번째 오프라인 뜻"]))
        #expect(importedStore.definitionCandidates.count == 3)
        #expect(importedStore.definitionCandidates.contains { candidate in
            candidate.id == first.id && candidate.source == "offline_catalog"
        })
        #expect(importedStore.definitionCandidates.contains { candidate in
            candidate.id == second.id && candidate.source == "offline_catalog"
        })
    }

    private func makeCard(meaning: String, updatedAt: Date) -> VocabularyCard {
        VocabularyCard(
            id: "card-offline",
            lemma: "resilient",
            displayWord: "resilient",
            meaningKo: meaning,
            definitionEn: "able to recover",
            partOfSpeech: "adjective",
            exampleSentence: "A resilient system recovers.",
            definitionSourcePrimary: "offline_catalog",
            status: .new,
            wordbook: .user,
            dueAt: updatedAt.addingTimeInterval(600),
            intervalDays: 0,
            lapseCount: 0,
            intervalBeforeLapse: nil,
            recommendationStartedAt: nil,
            deletedAt: nil,
            createdAt: updatedAt,
            updatedAt: updatedAt,
            version: 1
        )
    }

    private func makeCandidate(
        id: String,
        meaning: String,
        definition: String = "able to recover",
        partOfSpeech: String = "adjective",
        exampleSentence: String = "A resilient system recovers.",
        at: Date,
        selected: Bool = true,
        originDeviceId: String? = nil,
        baseCardVersion: Int? = nil
    ) -> StoredDefinitionCandidate {
        StoredDefinitionCandidate(
            id: id,
            cardId: "card-offline",
            source: "offline_catalog",
            rawText: meaning,
            parsedMeaningKo: meaning,
            parsedDefinitionEn: definition,
            partOfSpeech: partOfSpeech,
            exampleSentence: exampleSentence,
            confidence: 0.9,
            selectedAsPrimary: selected,
            createdAt: at,
            originDeviceId: originDeviceId,
            baseCardVersion: baseCardVersion
        )
    }

    private func makeDirectory(prefix: String) throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(prefix)-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }
}
