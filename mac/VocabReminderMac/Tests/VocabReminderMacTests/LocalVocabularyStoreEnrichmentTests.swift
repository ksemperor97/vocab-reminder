import Foundation
import Testing
@testable import VocabCore

@Suite(.serialized)
@MainActor
final class LocalVocabularyStoreEnrichmentTests {
    @Test func testPreviewLookupFillsKoreanMeaningBeforeSave() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("VocabReminderPreviewLookupTests-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = LocalVocabularyStore(
            appSupportURL: directory,
            scheduleNotifications: false,
            previewDictionaryLookup: { lemma in
                #expect(lemma == "uncomfy")
                return CloudDictionaryLookupResult(
                    lemma: lemma,
                    meaningKo: "불편한, 편하지 않은",
                    definitionEn: "informal: not comfortable",
                    partOfSpeech: "adjective",
                    sourceType: "definition_variant",
                    confidence: 0.72
                )
            }
        )

        store.capture(text: "uncomfy")
        for _ in 0..<20 where store.preview?.meaningKo.isEmpty != false {
            await Task.yield()
        }

        #expect(store.preview?.displayWord == "uncomfy")
        #expect(store.preview?.meaningKo == "불편한, 편하지 않은")
        #expect(store.preview?.sourceType == "definition_variant")
        #expect(store.message == "한국어 뜻을 찾았습니다. 저장 전 preview를 확인하세요.")
        #expect(store.cards.isEmpty)
    }

    @Test func testResolvedMeaningIsAppliedOnceWithSelectedCandidateAndFreshFirstDueDate() throws {
        let fixture = try makeBlankMeaningStore(words: ["codexblanklemma"])
        defer { try? FileManager.default.removeItem(at: fixture.directory) }
        let store = fixture.store
        let target = try #require(store.pendingDictionaryEnrichmentTargets().first)
        let before = try #require(store.cards.first(where: { $0.id == target.cardId }))
        let candidateCount = store.definitionCandidates.count
        let now = try #require(ISO8601DateFormatter().date(from: "2026-07-13T04:00:00Z"))

        let summary = store.applyDictionaryEnrichment(
            [
                CloudDictionaryLookupResult(
                    lemma: target.lemma,
                    meaningKo: "자동으로 채운 뜻",
                    definitionEn: "a definition",
                    partOfSpeech: "noun",
                    exampleSentence: "A useful example.",
                    sourceType: "offline_catalog",
                    confidence: 0.93
                )
            ],
            requestedTargets: [target],
            now: now
        )

        #expect(summary.appliedCardIds == [target.cardId])
        let updated = try #require(store.cards.first(where: { $0.id == target.cardId }))
        #expect(updated.meaningKo == "자동으로 채운 뜻")
        #expect(updated.definitionEn == "a definition")
        #expect(updated.definitionSourcePrimary == "offline_catalog")
        #expect(updated.version == before.version + 1)
        #expect(updated.updatedAt == now)
        #expect(updated.dueAt.timeIntervalSince(now) >= 60)
        #expect(updated.dueAt.timeIntervalSince(now) <= 20 * 60)
        #expect(store.definitionCandidates.count == candidateCount + 1)
        let candidate = try #require(store.definitionCandidates.last)
        #expect(candidate.cardId == target.cardId)
        #expect(candidate.parsedMeaningKo == "자동으로 채운 뜻")
        #expect(candidate.selectedAsPrimary)
        #expect(store.pendingDictionaryEnrichmentTargets().isEmpty)

        let reloaded = LocalVocabularyStore(
            appSupportURL: fixture.directory,
            scheduleNotifications: false
        )
        #expect(reloaded.cards.first(where: { $0.id == target.cardId })?.meaningKo == "자동으로 채운 뜻")
        #expect(reloaded.definitionCandidates.filter { $0.cardId == target.cardId }.last?.parsedMeaningKo == "자동으로 채운 뜻")
    }

    @Test func testLateServerResultNeverOverwritesManualMeaning() throws {
        let fixture = try makeBlankMeaningStore(words: ["codexmanualguard"])
        defer { try? FileManager.default.removeItem(at: fixture.directory) }
        let store = fixture.store
        let target = try #require(store.pendingDictionaryEnrichmentTargets().first)
        let card = try #require(store.cards.first(where: { $0.id == target.cardId }))
        store.updateCardDetails(
            cardId: card.id,
            displayWord: card.displayWord,
            lemma: card.lemma,
            meaningKo: "사용자가 직접 입력한 뜻",
            definitionEn: card.definitionEn,
            partOfSpeech: card.partOfSpeech,
            exampleSentence: card.exampleSentence
        )
        let manuallyEdited = try #require(store.cards.first(where: { $0.id == target.cardId }))
        let candidateCount = store.definitionCandidates.count

        let summary = store.applyDictionaryEnrichment(
            [CloudDictionaryLookupResult(lemma: target.lemma, meaningKo: "늦게 도착한 서버 뜻")],
            requestedTargets: [target],
            now: manuallyEdited.updatedAt.addingTimeInterval(30)
        )

        #expect(summary.appliedCount == 0)
        let unchanged = try #require(store.cards.first(where: { $0.id == target.cardId }))
        #expect(unchanged.meaningKo == "사용자가 직접 입력한 뜻")
        #expect(unchanged.version == manuallyEdited.version)
        #expect(store.definitionCandidates.count == candidateCount)
    }

    @Test func testExplicitlyClearedUserMeaningStaysLockedUntilManualRetry() throws {
        let fixture = try makeBlankMeaningStore(words: ["codexmanualblank"])
        defer { try? FileManager.default.removeItem(at: fixture.directory) }
        let store = fixture.store
        let original = try #require(store.cards.first)
        store.updateCardDetails(
            cardId: original.id,
            displayWord: original.displayWord,
            lemma: original.lemma,
            meaningKo: "사용자 뜻",
            definitionEn: original.definitionEn,
            partOfSpeech: original.partOfSpeech,
            exampleSentence: original.exampleSentence
        )
        let withMeaning = try #require(store.cards.first)
        store.updateCardDetails(
            cardId: withMeaning.id,
            displayWord: withMeaning.displayWord,
            lemma: withMeaning.lemma,
            meaningKo: "",
            definitionEn: withMeaning.definitionEn,
            partOfSpeech: withMeaning.partOfSpeech,
            exampleSentence: withMeaning.exampleSentence
        )
        let cleared = try #require(store.cards.first)
        #expect(cleared.definitionSourcePrimary == "user")
        #expect(store.pendingDictionaryEnrichmentTargets().isEmpty)

        let staleTarget = DictionaryEnrichmentTarget(cardId: cleared.id, lemma: cleared.lemma)
        let lockedSummary = store.applyDictionaryEnrichment(
            [CloudDictionaryLookupResult(lemma: cleared.lemma, meaningKo: "자동 뜻")],
            requestedTargets: [staleTarget]
        )
        #expect(lockedSummary.appliedCount == 0)
        #expect(store.cards.first?.meaningKo == "")

        store.retryDictionaryEnrichment(cardId: cleared.id)
        #expect(store.pendingDictionaryEnrichmentTargets().map(\.cardId) == [cleared.id])
        let retrySummary = store.applyDictionaryEnrichment(
            [CloudDictionaryLookupResult(lemma: cleared.lemma, meaningKo: "자동 뜻")],
            requestedTargets: store.pendingDictionaryEnrichmentTargets()
        )
        #expect(retrySummary.appliedCount == 1)
        #expect(store.cards.first?.meaningKo == "자동 뜻")
    }

    @Test func testDeletedAndAlreadyResolvedCardsAreNotLookupTargets() throws {
        let fixture = try makeBlankMeaningStore(words: ["codextargetone", "codextargettwo"])
        defer { try? FileManager.default.removeItem(at: fixture.directory) }
        let store = fixture.store
        let cards = store.activeCards
        #expect(cards.count == 2)

        let resolved = cards[0]
        store.updateCardDetails(
            cardId: resolved.id,
            displayWord: resolved.displayWord,
            lemma: resolved.lemma,
            meaningKo: "수동 뜻",
            definitionEn: resolved.definitionEn,
            partOfSpeech: resolved.partOfSpeech,
            exampleSentence: resolved.exampleSentence
        )
        store.softDeleteCard(cards[1])

        #expect(store.pendingDictionaryEnrichmentTargets().isEmpty)
    }

    @Test func testSchedulerRecentResponsesIgnoreManualEditSuspendAndRestoreEvents() throws {
        let fixture = try makeBlankMeaningStore(words: ["codexresponsefilter"])
        defer { try? FileManager.default.removeItem(at: fixture.directory) }
        let store = fixture.store
        let card = try #require(store.cards.first)
        store.review(cardId: card.id, response: .remembered)
        let afterFirstReview = try #require(store.cards.first)
        store.updateCardDetails(
            cardId: afterFirstReview.id,
            displayWord: afterFirstReview.displayWord,
            lemma: afterFirstReview.lemma,
            meaningKo: "직접 뜻",
            definitionEn: afterFirstReview.definitionEn,
            partOfSpeech: afterFirstReview.partOfSpeech,
            exampleSentence: afterFirstReview.exampleSentence
        )
        store.review(cardId: card.id, response: .suspend)
        store.review(cardId: card.id, response: .restore)
        store.review(cardId: card.id, response: .remembered)

        #expect(store.schedulerRecentResponses(cardId: card.id, limit: 2) == [.remembered, .remembered])
    }
}

extension LocalVocabularyStoreEnrichmentTests {
    @Test func testAlternativeSensesAndPrimaryPointerSurviveReload() throws {
        let fixture = try makeBlankMeaningStore(words: ["codexsenselemma"])
        defer { try? FileManager.default.removeItem(at: fixture.directory) }
        let store = fixture.store
        let target = try #require(store.pendingDictionaryEnrichmentTargets().first)
        let result = CloudDictionaryLookupResult(lemma: target.lemma, meaningKo: "은행", definitionEn: "a financial institution",
            senses: [CloudDictionarySense(meaningKo: "강둑", definitionEn: "land beside a river", partOfSpeech: "noun")])
        _ = store.applyDictionaryEnrichment([result], requestedTargets: [target])
        let reloaded = LocalVocabularyStore(appSupportURL: fixture.directory, scheduleNotifications: false)
        let card = try #require(reloaded.cards.first)
        #expect(card.meaningKo == "은행")
        let primary = try #require(reloaded.definitionCandidates.first { $0.id == card.primaryDefinitionCandidateId })
        #expect(primary.parsedMeaningKo == "은행")
        #expect(reloaded.definitionCandidates.contains { $0.parsedMeaningKo == "강둑" && !$0.selectedAsPrimary })
    }
}
