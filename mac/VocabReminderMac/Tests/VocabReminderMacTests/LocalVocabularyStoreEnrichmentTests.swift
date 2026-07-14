import Foundation
import XCTest
@testable import VocabReminderMac

@MainActor
final class LocalVocabularyStoreEnrichmentTests: XCTestCase {
    func testResolvedMeaningIsAppliedOnceWithSelectedCandidateAndFreshFirstDueDate() throws {
        let fixture = try makeBlankMeaningStore(words: ["codexblanklemma"])
        defer { try? FileManager.default.removeItem(at: fixture.directory) }
        let store = fixture.store
        let target = try XCTUnwrap(store.pendingDictionaryEnrichmentTargets().first)
        let before = try XCTUnwrap(store.cards.first(where: { $0.id == target.cardId }))
        let candidateCount = store.definitionCandidates.count
        let now = try XCTUnwrap(ISO8601DateFormatter().date(from: "2026-07-13T04:00:00Z"))

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

        XCTAssertEqual(summary.appliedCardIds, [target.cardId])
        let updated = try XCTUnwrap(store.cards.first(where: { $0.id == target.cardId }))
        XCTAssertEqual(updated.meaningKo, "자동으로 채운 뜻")
        XCTAssertEqual(updated.definitionEn, "a definition")
        XCTAssertEqual(updated.definitionSourcePrimary, "offline_catalog")
        XCTAssertEqual(updated.version, before.version + 1)
        XCTAssertEqual(updated.updatedAt, now)
        XCTAssertGreaterThanOrEqual(updated.dueAt.timeIntervalSince(now), 60)
        XCTAssertLessThanOrEqual(updated.dueAt.timeIntervalSince(now), 20 * 60)
        XCTAssertEqual(store.definitionCandidates.count, candidateCount + 1)
        let candidate = try XCTUnwrap(store.definitionCandidates.last)
        XCTAssertEqual(candidate.cardId, target.cardId)
        XCTAssertEqual(candidate.parsedMeaningKo, "자동으로 채운 뜻")
        XCTAssertTrue(candidate.selectedAsPrimary)
        XCTAssertTrue(store.pendingDictionaryEnrichmentTargets().isEmpty)

        let reloaded = LocalVocabularyStore(
            appSupportURL: fixture.directory,
            scheduleNotifications: false
        )
        XCTAssertEqual(reloaded.cards.first(where: { $0.id == target.cardId })?.meaningKo, "자동으로 채운 뜻")
        XCTAssertEqual(
            reloaded.definitionCandidates.filter { $0.cardId == target.cardId }.last?.parsedMeaningKo,
            "자동으로 채운 뜻"
        )
    }

    func testLateServerResultNeverOverwritesManualMeaning() throws {
        let fixture = try makeBlankMeaningStore(words: ["codexmanualguard"])
        defer { try? FileManager.default.removeItem(at: fixture.directory) }
        let store = fixture.store
        let target = try XCTUnwrap(store.pendingDictionaryEnrichmentTargets().first)
        let card = try XCTUnwrap(store.cards.first(where: { $0.id == target.cardId }))
        store.updateCardDetails(
            cardId: card.id,
            displayWord: card.displayWord,
            lemma: card.lemma,
            meaningKo: "사용자가 직접 입력한 뜻",
            definitionEn: card.definitionEn,
            partOfSpeech: card.partOfSpeech,
            exampleSentence: card.exampleSentence
        )
        let manuallyEdited = try XCTUnwrap(store.cards.first(where: { $0.id == target.cardId }))
        let candidateCount = store.definitionCandidates.count

        let summary = store.applyDictionaryEnrichment(
            [CloudDictionaryLookupResult(lemma: target.lemma, meaningKo: "늦게 도착한 서버 뜻")],
            requestedTargets: [target],
            now: manuallyEdited.updatedAt.addingTimeInterval(30)
        )

        XCTAssertEqual(summary.appliedCount, 0)
        let unchanged = try XCTUnwrap(store.cards.first(where: { $0.id == target.cardId }))
        XCTAssertEqual(unchanged.meaningKo, "사용자가 직접 입력한 뜻")
        XCTAssertEqual(unchanged.version, manuallyEdited.version)
        XCTAssertEqual(store.definitionCandidates.count, candidateCount)
    }

    func testExplicitlyClearedUserMeaningStaysLockedUntilManualRetry() throws {
        let fixture = try makeBlankMeaningStore(words: ["codexmanualblank"])
        defer { try? FileManager.default.removeItem(at: fixture.directory) }
        let store = fixture.store
        let original = try XCTUnwrap(store.cards.first)
        store.updateCardDetails(
            cardId: original.id,
            displayWord: original.displayWord,
            lemma: original.lemma,
            meaningKo: "사용자 뜻",
            definitionEn: original.definitionEn,
            partOfSpeech: original.partOfSpeech,
            exampleSentence: original.exampleSentence
        )
        let withMeaning = try XCTUnwrap(store.cards.first)
        store.updateCardDetails(
            cardId: withMeaning.id,
            displayWord: withMeaning.displayWord,
            lemma: withMeaning.lemma,
            meaningKo: "",
            definitionEn: withMeaning.definitionEn,
            partOfSpeech: withMeaning.partOfSpeech,
            exampleSentence: withMeaning.exampleSentence
        )
        let cleared = try XCTUnwrap(store.cards.first)
        XCTAssertEqual(cleared.definitionSourcePrimary, "user")
        XCTAssertTrue(store.pendingDictionaryEnrichmentTargets().isEmpty)

        let staleTarget = DictionaryEnrichmentTarget(cardId: cleared.id, lemma: cleared.lemma)
        let lockedSummary = store.applyDictionaryEnrichment(
            [CloudDictionaryLookupResult(lemma: cleared.lemma, meaningKo: "자동 뜻")],
            requestedTargets: [staleTarget]
        )
        XCTAssertEqual(lockedSummary.appliedCount, 0)
        XCTAssertEqual(store.cards.first?.meaningKo, "")

        store.retryDictionaryEnrichment(cardId: cleared.id)
        XCTAssertEqual(store.pendingDictionaryEnrichmentTargets().map(\.cardId), [cleared.id])
        let retrySummary = store.applyDictionaryEnrichment(
            [CloudDictionaryLookupResult(lemma: cleared.lemma, meaningKo: "자동 뜻")],
            requestedTargets: store.pendingDictionaryEnrichmentTargets()
        )
        XCTAssertEqual(retrySummary.appliedCount, 1)
        XCTAssertEqual(store.cards.first?.meaningKo, "자동 뜻")
    }

    func testDeletedAndAlreadyResolvedCardsAreNotLookupTargets() throws {
        let fixture = try makeBlankMeaningStore(words: ["codextargetone", "codextargettwo"])
        defer { try? FileManager.default.removeItem(at: fixture.directory) }
        let store = fixture.store
        let cards = store.activeCards
        XCTAssertEqual(cards.count, 2)

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

        XCTAssertTrue(store.pendingDictionaryEnrichmentTargets().isEmpty)
    }

    func testSchedulerRecentResponsesIgnoreManualEditSuspendAndRestoreEvents() throws {
        let fixture = try makeBlankMeaningStore(words: ["codexresponsefilter"])
        defer { try? FileManager.default.removeItem(at: fixture.directory) }
        let store = fixture.store
        let card = try XCTUnwrap(store.cards.first)
        store.review(cardId: card.id, response: .remembered)
        let afterFirstReview = try XCTUnwrap(store.cards.first)
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

        XCTAssertEqual(
            store.schedulerRecentResponses(cardId: card.id, limit: 2),
            [.remembered, .remembered]
        )
    }
}
