package com.vocabreminder.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vocabreminder.android.dictionary.DictionaryLookupResult
import com.vocabreminder.android.dictionary.DictionaryLookupStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DictionaryEnrichmentStoreTest {
    private lateinit var store: LocalVocabularyStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = LocalVocabularyStore(context)
        store.clearAll()
    }

    @Test
    fun blankCardStartsPendingAndResolvedLookupAppendsCandidateAndRestartsFirstDue() {
        val capturedAt = Instant.parse("2026-07-13T00:00:00Z")
        val resolvedAt = Instant.parse("2026-07-13T03:00:00Z")
        val card = store.addCard(
            displayWord = "unmapped",
            meaningKo = "",
            exampleSentence = "An unmapped region remained.",
            dueAt = capturedAt.plusSeconds(900),
            now = capturedAt,
            definitionSourcePrimary = "pending_capture"
        )
        val originalCandidateCount = store.definitionCandidates().size

        assertEquals(DictionaryEnrichmentStatus.PENDING, store.dictionaryEnrichmentState(card.id)?.status)

        val updated = store.applyDictionaryEnrichment(
            card.id,
            resolved("unmapped", "지도에 표시되지 않은", attemptedAt = resolvedAt.minusSeconds(2)),
            resolvedAt
        )!!

        assertEquals("지도에 표시되지 않은", updated.meaningKo)
        assertEquals(card.version + 1, updated.version)
        assertEquals(resolvedAt, updated.updatedAt)
        assertTrue(updated.dueAt.isAfter(resolvedAt))
        assertEquals(DictionaryEnrichmentStatus.RESOLVED, store.dictionaryEnrichmentState(card.id)?.status)
        assertEquals(originalCandidateCount + 1, store.definitionCandidates().size)
        val candidate = store.definitionCandidates().last()
        assertEquals(card.id, candidate.cardId)
        assertEquals("지도에 표시되지 않은", candidate.parsedMeaningKo)
        assertEquals("mac_dictionary", candidate.source)
        assertEquals(true, candidate.selectedAsPrimary)

        val exported = JSONObject(store.exportSyncBatchJson())
        assertEquals(false, exported.has("dictionary_enrichment_states"))
    }

    @Test
    fun lookupNeverOverwritesMeaningEnteredWhileRequestWasInFlight() {
        val capturedAt = Instant.parse("2026-07-13T00:00:00Z")
        val card = store.addCard(
            displayWord = "concurrent",
            meaningKo = "",
            exampleSentence = "",
            dueAt = capturedAt.plusSeconds(900),
            now = capturedAt,
            definitionSourcePrimary = "pending_capture"
        )
        store.updateCardDetails(
            cardId = card.id,
            meaningKo = "사용자가 입력한 뜻",
            exampleSentence = "Manual context.",
            now = capturedAt.plusSeconds(30)
        )
        val beforeCandidateCount = store.definitionCandidates().size

        val applied = store.applyDictionaryEnrichment(
            card.id,
            resolved("concurrent", "자동 뜻", capturedAt.plusSeconds(40)),
            capturedAt.plusSeconds(40)
        )

        assertNull(applied)
        assertEquals("사용자가 입력한 뜻", store.findCard(card.id)?.meaningKo)
        assertEquals(DictionaryEnrichmentStatus.USER_LOCKED, store.dictionaryEnrichmentState(card.id)?.status)
        assertEquals(beforeCandidateCount, store.definitionCandidates().size)
    }

    @Test
    fun explicitlyClearedUserMeaningStaysLockedUntilManualRetry() {
        val capturedAt = Instant.parse("2026-07-13T00:00:00Z")
        val card = store.addCard(
            displayWord = "clearable",
            meaningKo = "기존 뜻",
            exampleSentence = "",
            dueAt = capturedAt.plusSeconds(900),
            now = capturedAt,
            definitionSourcePrimary = "user"
        )
        store.updateCardDetails(
            cardId = card.id,
            meaningKo = "",
            exampleSentence = "",
            now = capturedAt.plusSeconds(10)
        )

        assertEquals(DictionaryEnrichmentStatus.USER_LOCKED, store.dictionaryEnrichmentState(card.id)?.status)
        assertTrue(store.cardsNeedingDictionaryEnrichment(10).none { it.id == card.id })
        assertNull(
            store.applyDictionaryEnrichment(
                card.id,
                resolved("clearable", "자동으로 되살아난 뜻", capturedAt.plusSeconds(20)),
                capturedAt.plusSeconds(20)
            )
        )
        assertEquals("", store.findCard(card.id)?.meaningKo)

        store.retryDictionaryEnrichment(card.id)
        assertEquals(DictionaryEnrichmentStatus.PENDING, store.dictionaryEnrichmentState(card.id)?.status)
        assertTrue(store.cardsNeedingDictionaryEnrichment(10).any { it.id == card.id })
        assertEquals(
            "자동으로 되살아난 뜻",
            store.applyDictionaryEnrichment(
                card.id,
                resolved("clearable", "자동으로 되살아난 뜻", capturedAt.plusSeconds(30)),
                capturedAt.plusSeconds(30)
            )?.meaningKo
        )
    }

    @Test
    fun failureReasonsRemainDistinctAndOnlyRetryableFailuresReturnToQueue() {
        val now = Instant.parse("2026-07-13T00:00:00Z")
        val network = blankCard("network", now)
        val missing = blankCard("missing", now.plusSeconds(1))
        val provider = blankCard("provider", now.plusSeconds(2))

        store.recordDictionaryEnrichmentFailure(listOf(network.id), DictionaryEnrichmentFailure.NETWORK, now)
        store.recordDictionaryEnrichmentFailure(listOf(missing.id), DictionaryEnrichmentFailure.NOT_FOUND, now)
        store.recordDictionaryEnrichmentFailure(listOf(provider.id), DictionaryEnrichmentFailure.PROVIDER_FAILURE, now)

        assertEquals(DictionaryEnrichmentFailure.NETWORK, store.dictionaryEnrichmentState(network.id)?.failure)
        assertEquals(DictionaryEnrichmentFailure.NOT_FOUND, store.dictionaryEnrichmentState(missing.id)?.failure)
        assertEquals(DictionaryEnrichmentFailure.PROVIDER_FAILURE, store.dictionaryEnrichmentState(provider.id)?.failure)
        assertEquals(setOf(network.id, provider.id), store.cardsNeedingDictionaryEnrichment(10).map { it.id }.toSet())

        store.retryDictionaryEnrichment(missing.id)
        assertEquals(DictionaryEnrichmentStatus.PENDING, store.dictionaryEnrichmentState(missing.id)?.status)
        assertEquals(setOf(network.id, missing.id, provider.id), store.cardsNeedingDictionaryEnrichment(10).map { it.id }.toSet())
    }

    @Test
    fun englishOnlyLookupIsPreservedAsUnselectedCandidateWithoutPretendingMeaningResolved() {
        val now = Instant.parse("2026-07-13T00:00:00Z")
        val card = blankCard("englishonly", now)
        val result = DictionaryLookupResult(
            status = DictionaryLookupStatus.RESOLVED,
            lemma = card.lemma,
            meaningKo = "",
            definitionEn = "available only as an English definition",
            sourceType = "mac_dictionary",
            confidence = 0.68,
            attemptedAt = now.plusSeconds(10)
        )

        assertEquals(true, store.applyDictionaryDefinitionFallback(card.id, result, now.plusSeconds(12)))

        val updated = store.findCard(card.id)!!
        assertEquals("", updated.meaningKo)
        assertEquals("available only as an English definition", updated.definitionEn)
        assertEquals(card.version + 1, updated.version)
        assertEquals(DictionaryEnrichmentFailure.NOT_FOUND, store.dictionaryEnrichmentState(card.id)?.failure)
        assertEquals(false, store.definitionCandidates().last().selectedAsPrimary)
    }

    @Test
    fun englishFallbackNeverOverwritesDefinitionAfterUserClearsMeaningInFlight() {
        val now = Instant.parse("2026-07-14T00:00:00Z")
        val card = store.addCard(
            displayWord = "fallback-lock",
            meaningKo = "사용자 뜻",
            definitionEn = "user definition",
            exampleSentence = "",
            dueAt = now.plusSeconds(900),
            now = now,
            definitionSourcePrimary = "user"
        )
        store.updateCardDetails(
            cardId = card.id,
            meaningKo = "",
            exampleSentence = "",
            now = now.plusSeconds(5)
        )
        val candidatesBefore = store.definitionCandidates().map { it.id }
        val inFlightResult = DictionaryLookupResult(
            status = DictionaryLookupStatus.RESOLVED,
            lemma = card.lemma,
            meaningKo = "",
            definitionEn = "an automatic fallback must not win",
            sourceType = "mac_dictionary",
            confidence = 0.7,
            attemptedAt = now.plusSeconds(10)
        )

        assertEquals(
            false,
            store.applyDictionaryDefinitionFallback(card.id, inFlightResult, now.plusSeconds(10))
        )

        val reloaded = store.findCard(card.id)!!
        assertEquals("", reloaded.meaningKo)
        assertEquals("user definition", reloaded.definitionEn)
        assertEquals("user", reloaded.definitionSourcePrimary)
        assertEquals(candidatesBefore, store.definitionCandidates().map { it.id })
        assertEquals(
            DictionaryEnrichmentStatus.USER_LOCKED,
            store.dictionaryEnrichmentState(card.id)?.status
        )
    }

    @Test
    fun selectingHistoricalCandidatePreservesHistoryAndAppendsRestoreLineage() {
        val now = Instant.parse("2026-07-13T00:00:00Z")
        val card = store.addCard(
            displayWord = "restore",
            meaningKo = "첫 뜻",
            exampleSentence = "First example.",
            dueAt = now.plusSeconds(900),
            now = now,
            definitionSourcePrimary = "user"
        )
        val firstCandidate = store.definitionCandidates().single()
        store.updateCardDetails(
            cardId = card.id,
            meaningKo = "두 번째 뜻",
            exampleSentence = "Second example.",
            now = now.plusSeconds(10)
        )
        val candidateIdsBefore = store.definitionCandidates().map { it.id }

        val result = store.selectDefinitionCandidate(
            cardId = card.id,
            candidateId = firstCandidate.id,
            now = now.plusSeconds(20)
        )!!

        assertEquals("첫 뜻", result.after.meaningKo)
        assertEquals("First example.", result.after.exampleSentence)
        val candidatesAfter = store.definitionCandidates()
        assertTrue(candidatesAfter.map { it.id }.containsAll(candidateIdsBefore))
        assertEquals(candidateIdsBefore.size + 1, candidatesAfter.size)
        val restoredCandidate = candidatesAfter.single { it.id !in candidateIdsBefore }
        assertEquals("첫 뜻", restoredCandidate.parsedMeaningKo)
        assertEquals(result.before.version, restoredCandidate.baseCardVersion)
        assertTrue(!restoredCandidate.originDeviceId.isNullOrBlank())
        assertTrue(restoredCandidate.selectedAsPrimary)
        assertEquals("select_definition_candidate", store.events().last().metadata["action"])
        assertEquals(firstCandidate.id, store.events().last().metadata["candidate_id"])
        assertEquals(DictionaryEnrichmentStatus.USER_LOCKED, store.dictionaryEnrichmentState(card.id)?.status)
    }

    private fun blankCard(word: String, now: Instant): VocabularyCard {
        return store.addCard(
            displayWord = word,
            meaningKo = "",
            exampleSentence = "",
            dueAt = now.plusSeconds(900),
            now = now,
            definitionSourcePrimary = "pending_capture"
        )
    }

    private fun resolved(lemma: String, meaning: String, attemptedAt: Instant): DictionaryLookupResult {
        return DictionaryLookupResult(
            status = DictionaryLookupStatus.RESOLVED,
            lemma = lemma,
            meaningKo = meaning,
            definitionEn = "an English definition",
            partOfSpeech = "adjective",
            exampleSentence = "A dictionary example.",
            sourceType = "mac_dictionary",
            confidence = 0.78,
            attemptedAt = attemptedAt
        )
    }
}
