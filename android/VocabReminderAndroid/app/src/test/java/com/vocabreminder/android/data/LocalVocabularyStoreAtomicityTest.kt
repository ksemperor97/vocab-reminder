package com.vocabreminder.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vocabreminder.android.dictionary.DictionaryLookupResult
import com.vocabreminder.android.dictionary.DictionaryLookupStatus
import com.vocabreminder.android.scheduler.CardStatus
import com.vocabreminder.android.scheduler.ReviewEventDraft
import com.vocabreminder.android.scheduler.ReviewResponse
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LocalVocabularyStoreAtomicityTest {
    private lateinit var context: Context
    private lateinit var store: LocalVocabularyStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = LocalVocabularyStore(context)
        store.clearAll()
    }

    @Test
    fun manualEditKilledBeforeCommitLeavesEveryRelatedCollectionUntouched() {
        val now = Instant.parse("2026-07-14T00:00:00Z")
        val card = pendingCard("atomic-edit", now)
        val originalCandidateIds = store.definitionCandidates().map { it.id }
        val failingStore = LocalVocabularyStore(context) { false }

        assertThrows(IllegalStateException::class.java) {
            failingStore.updateCardDetails(
                cardId = card.id,
                meaningKo = "원자적인 뜻",
                exampleSentence = "An atomic edit.",
                now = now.plusSeconds(10)
            )
        }

        val reloaded = LocalVocabularyStore(context)
        assertEquals(card, reloaded.findCard(card.id))
        assertEquals(originalCandidateIds, reloaded.definitionCandidates().map { it.id })
        assertTrue(reloaded.events().isEmpty())
        assertEquals(
            DictionaryEnrichmentStatus.PENDING,
            reloaded.dictionaryEnrichmentState(card.id)?.status
        )
    }

    @Test
    fun manualEditKilledAfterCommitReloadsAsOneCompleteTransaction() {
        val now = Instant.parse("2026-07-14T00:00:00Z")
        val card = pendingCard("durable-edit", now)
        val crashAfterCommit = LocalVocabularyStore(context) { editor ->
            check(editor.commit())
            throw SimulatedProcessDeath()
        }

        assertThrows(SimulatedProcessDeath::class.java) {
            crashAfterCommit.updateCardDetails(
                cardId = card.id,
                meaningKo = "내구성 있는 뜻",
                exampleSentence = "A durable edit.",
                now = now.plusSeconds(10)
            )
        }

        val reloaded = LocalVocabularyStore(context)
        val updated = reloaded.findCard(card.id)!!
        assertEquals("내구성 있는 뜻", updated.meaningKo)
        assertEquals(card.version + 1, updated.version)
        assertEquals(1, reloaded.events().size)
        assertEquals("manual_edit", reloaded.events().single().metadata["action"])
        assertEquals(2, reloaded.definitionCandidates().size)
        assertEquals("내구성 있는 뜻", reloaded.definitionCandidates().last().parsedMeaningKo)
        assertEquals(
            DictionaryEnrichmentStatus.USER_LOCKED,
            reloaded.dictionaryEnrichmentState(card.id)?.status
        )
    }

    @Test
    fun dictionaryResolutionKilledBeforeCommitCannotLoseCandidateOrResolvedState() {
        val now = Instant.parse("2026-07-14T00:00:00Z")
        val card = pendingCard("atomic-lookup", now)
        val originalCandidateIds = store.definitionCandidates().map { it.id }
        val failingStore = LocalVocabularyStore(context) { false }

        assertThrows(IllegalStateException::class.java) {
            failingStore.applyDictionaryEnrichment(
                card.id,
                resolvedResult("atomic-lookup", now.plusSeconds(10)),
                now.plusSeconds(10)
            )
        }

        val reloaded = LocalVocabularyStore(context)
        assertEquals("", reloaded.findCard(card.id)?.meaningKo)
        assertEquals(card.version, reloaded.findCard(card.id)?.version)
        assertEquals(originalCandidateIds, reloaded.definitionCandidates().map { it.id })
        assertEquals(
            DictionaryEnrichmentStatus.PENDING,
            reloaded.dictionaryEnrichmentState(card.id)?.status
        )
    }

    @Test
    fun reviewKilledBeforeCommitCannotAdvanceCardWithoutItsAuditEvent() {
        val now = Instant.parse("2026-07-14T00:00:00Z")
        val card = store.addCard(
            displayWord = "review-atomicity",
            meaningKo = "복습 원자성",
            exampleSentence = "",
            dueAt = now,
            now = now
        )
        val updated = card.copy(
            status = CardStatus.LEARNING,
            dueAt = now.plusSeconds(600),
            intervalDays = 1,
            updatedAt = now.plusSeconds(1),
            version = card.version + 1
        )
        val event = ReviewEventDraft(
            cardId = card.id,
            response = ReviewResponse.REMEMBERED,
            previousStatus = card.status,
            nextStatus = updated.status,
            previousDueAt = card.dueAt,
            nextDueAt = updated.dueAt,
            previousIntervalDays = card.intervalDays,
            nextIntervalDays = updated.intervalDays
        )
        val failingStore = LocalVocabularyStore(context) { false }

        assertThrows(IllegalStateException::class.java) {
            failingStore.updateCardAndAppendEvent(updated, event, now.plusSeconds(1))
        }

        val reloaded = LocalVocabularyStore(context)
        assertEquals(card, reloaded.findCard(card.id))
        assertTrue(reloaded.events().isEmpty())
    }

    @Test
    fun englishFallbackKilledAfterCommitReloadsWithCandidateAndFailureState() {
        val now = Instant.parse("2026-07-14T00:00:00Z")
        val card = pendingCard("atomic-fallback", now)
        val crashAfterCommit = LocalVocabularyStore(context) { editor ->
            check(editor.commit())
            throw SimulatedProcessDeath()
        }
        val result = DictionaryLookupResult(
            status = DictionaryLookupStatus.RESOLVED,
            lemma = card.lemma,
            meaningKo = "",
            definitionEn = "an English-only fallback",
            sourceType = "mac_dictionary",
            confidence = 0.5,
            attemptedAt = now.plusSeconds(10),
            requestId = "request-atomic-fallback"
        )

        assertThrows(SimulatedProcessDeath::class.java) {
            crashAfterCommit.applyDictionaryDefinitionFallback(card.id, result, now.plusSeconds(10))
        }

        val reloaded = LocalVocabularyStore(context)
        assertEquals("an English-only fallback", reloaded.findCard(card.id)?.definitionEn)
        assertEquals(2, reloaded.definitionCandidates().size)
        assertFalse(reloaded.definitionCandidates().last().selectedAsPrimary)
        val state = reloaded.dictionaryEnrichmentState(card.id)!!
        assertEquals(DictionaryEnrichmentStatus.FAILED, state.status)
        assertEquals(DictionaryEnrichmentFailure.NOT_FOUND, state.failure)
        assertEquals("request-atomic-fallback", state.requestId)
    }

    @Test
    fun syncImportKilledBeforeCommitLeavesAllLocalCollectionsAndSettingsTogether() {
        val now = Instant.parse("2026-07-14T00:00:00Z")
        val localCard = store.addCard(
            displayWord = "local",
            meaningKo = "로컬",
            exampleSentence = "A local context.",
            dueAt = now.plusSeconds(900),
            now = now,
            sourceApp = "test"
        )
        val payload = JSONObject(store.exportSyncBatchJson())
        val remoteCardId = "remote-atomic-import"
        val remoteCard = JSONObject(payload.getJSONArray("cards").getJSONObject(0).toString())
            .put("id", remoteCardId)
            .put("lemma", "remote")
            .put("display_word", "remote")
            .put("meaning_ko", "원격")
            .put("notification_id", 9911)
        payload.getJSONArray("cards").put(remoteCard)

        val remoteContext = JSONObject(payload.getJSONArray("contexts").getJSONObject(0).toString())
            .put("id", "remote-context")
            .put("card_id", remoteCardId)
        payload.getJSONArray("contexts").put(remoteContext)

        val remoteCandidate = JSONObject(
            payload.getJSONArray("definition_candidates").getJSONObject(0).toString()
        )
            .put("id", "remote-candidate")
            .put("card_id", remoteCardId)
            .put("parsed_meaning_ko", "원격")
        payload.getJSONArray("definition_candidates").put(remoteCandidate)

        payload.getJSONArray("review_events").put(
            JSONObject()
                .put("id", "remote-event")
                .put("card_id", remoteCardId)
                .put("response", "remembered")
                .put("previous_status", "new")
                .put("next_status", "learning")
                .put("previous_due_at", now.toString())
                .put("next_due_at", now.plusSeconds(600).toString())
                .put("previous_interval_days", 0)
                .put("next_interval_days", 1)
                .put("client_created_at", now.plusSeconds(10).toString())
                .put("metadata", JSONObject())
        )
        payload.getJSONObject("settings")
            .put("daily_word_goal", 77)
            .put("settings_updated_at", "2030-01-01T00:00:00Z")

        val failingStore = LocalVocabularyStore(context) { false }
        assertThrows(IllegalStateException::class.java) {
            failingStore.importSyncBatchJson(payload.toString())
        }

        val reloaded = LocalVocabularyStore(context)
        assertEquals(listOf(localCard.id), reloaded.cards().map { it.id })
        assertEquals(1, reloaded.contexts().size)
        assertEquals(1, reloaded.definitionCandidates().size)
        assertTrue(reloaded.events().isEmpty())
        assertEquals(10, reloaded.settings().dailyWordGoal)
    }

    @Test
    fun duplicateCardImportRemapsDeviceLocalEnrichmentStateWithItsCanonicalCard() {
        val now = Instant.parse("2026-07-14T00:00:00Z")
        val local = pendingCard("duplicate-state", now)
        store.recordDictionaryEnrichmentFailure(
            listOf(local.id),
            DictionaryEnrichmentFailure.NETWORK,
            now.plusSeconds(5),
            "request-before-dedup"
        )
        val payload = JSONObject(store.exportSyncBatchJson())
        val canonicalId = "canonical-duplicate-state"
        val canonical = JSONObject(payload.getJSONArray("cards").getJSONObject(0).toString())
            .put("id", canonicalId)
            .put("version", local.version + 1)
            .put("updated_at", now.plusSeconds(10).toString())
            .put("notification_id", 9912)
        payload.getJSONArray("cards").put(canonical)

        store.importSyncBatchJson(payload.toString())

        assertEquals(null, store.findCard(local.id))
        assertEquals(canonicalId, store.findCard(canonicalId)?.id)
        val state = store.dictionaryEnrichmentState(canonicalId)!!
        assertEquals(DictionaryEnrichmentStatus.FAILED, state.status)
        assertEquals(DictionaryEnrichmentFailure.NETWORK, state.failure)
        assertEquals("request-before-dedup", state.requestId)
    }

    private fun pendingCard(word: String, now: Instant): VocabularyCard {
        return store.addCard(
            displayWord = word,
            meaningKo = "",
            exampleSentence = "",
            dueAt = now.plusSeconds(900),
            now = now,
            definitionSourcePrimary = "pending_capture"
        )
    }

    private fun resolvedResult(lemma: String, attemptedAt: Instant): DictionaryLookupResult {
        return DictionaryLookupResult(
            status = DictionaryLookupStatus.RESOLVED,
            lemma = lemma,
            meaningKo = "사전 뜻",
            definitionEn = "a dictionary definition",
            sourceType = "mac_dictionary",
            confidence = 0.8,
            attemptedAt = attemptedAt,
            requestId = "request-atomic-resolution"
        )
    }

    private class SimulatedProcessDeath : RuntimeException()
}
