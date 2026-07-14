package com.vocabreminder.android.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.vocabreminder.android.data.CloudSyncCredentials
import com.vocabreminder.android.data.DictionaryEnrichmentFailure
import com.vocabreminder.android.data.DictionaryEnrichmentStatus
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.dictionary.DictionaryLookupErrorCode
import com.vocabreminder.android.dictionary.DictionaryLookupResult
import com.vocabreminder.android.dictionary.DictionaryLookupStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DictionaryEnrichmentWorkerTest {
    private lateinit var context: Context
    private lateinit var store: LocalVocabularyStore
    private val now = Instant.parse("2026-07-13T04:00:00Z")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        store = LocalVocabularyStore(context)
        store.clearAll()
        store.saveCloudSyncCredentials(
            CloudSyncCredentials(
                serverBaseUrl = "https://sync.example.invalid",
                bearerToken = "test-token"
            )
        )
        DictionaryEnrichmentRuntime.now = { now }
    }

    @After
    fun tearDown() {
        DictionaryEnrichmentRuntime.resetForTests()
    }

    @Test
    fun successfulWorkerAppliesMeaningSchedulesReminderAndQueuesSync() = runBlocking {
        val card = blankCard("resolvable")
        var requestedLemmas = emptyList<String>()
        DictionaryEnrichmentRuntime.clientFactory = { config ->
            assertEquals("https://sync.example.invalid", config.serverBaseUrl)
            assertEquals("test-token", config.bearerToken)
            DictionaryLookupClient { lemmas ->
                requestedLemmas = lemmas
                listOf(resolved("resolvable", "해결할 수 있는"))
            }
        }

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(listOf("resolvable"), requestedLemmas)
        assertEquals("해결할 수 있는", store.findCard(card.id)?.meaningKo)
        assertEquals(DictionaryEnrichmentStatus.RESOLVED, store.dictionaryEnrichmentState(card.id)?.status)
        assertEquals(REQUEST_ID, store.dictionaryEnrichmentState(card.id)?.requestId)
        assertEquals(
            WorkInfo.State.ENQUEUED,
            WorkManager.getInstance(context).getWorkInfosForUniqueWork("review:${card.id}").get().single().state
        )
        assertEquals(
            WorkInfo.State.ENQUEUED,
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncWorker.IMMEDIATE_WORK).get().single().state
        )
    }

    @Test
    fun workerDrainsTwoHundredCardBacklogWithoutConsumingRetryAttempts() = runBlocking {
        val cards = (0 until 200).map { index -> blankCard(indexedWord(index)) }
        val requestedPages = mutableListOf<List<String>>()
        DictionaryEnrichmentRuntime.clientFactory = {
            DictionaryLookupClient { lemmas ->
                requestedPages += lemmas
                lemmas.map { lemma -> resolved(lemma, "meaning for $lemma") }
            }
        }

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(
            List(200) { it }.chunked(DictionaryEnrichmentWorker.MAX_CARDS_PER_RUN).map { it.size },
            requestedPages.map { it.size }
        )
        assertEquals(200, requestedPages.flatten().distinct().size)
        assertEquals(200, cards.count { store.findCard(it.id)?.meaningKo?.isNotBlank() == true })
        assertFalse(store.hasCardsNeedingDictionaryEnrichment())
    }

    @Test
    fun transportFailureAfterNormalPageUsesBackoffRetryAndThenResumesBacklog() = runBlocking {
        val cards = (0 until 75).map { index -> blankCard(indexedWord(index)) }
        var requestCount = 0
        DictionaryEnrichmentRuntime.clientFactory = {
            DictionaryLookupClient { lemmas ->
                requestCount += 1
                if (requestCount == 2) throw CloudSyncException("offline", REQUEST_ID)
                lemmas.map { lemma -> resolved(lemma, "meaning for $lemma") }
            }
        }

        val failedAttempt = worker(runAttemptCount = 0).doWork()

        assertTrue(failedAttempt is ListenableWorker.Result.Retry)
        assertEquals(2, requestCount)
        val firstPageSize = minOf(DictionaryEnrichmentWorker.MAX_CARDS_PER_RUN, cards.size)
        val failedPageSize = minOf(DictionaryEnrichmentWorker.MAX_CARDS_PER_RUN, cards.size - firstPageSize)
        assertEquals(firstPageSize, cards.count { store.findCard(it.id)?.meaningKo?.isNotBlank() == true })
        assertEquals(
            failedPageSize,
            cards.count {
                store.dictionaryEnrichmentState(it.id)?.failure == DictionaryEnrichmentFailure.NETWORK
            }
        )

        val resumedAttempt = worker(runAttemptCount = 1).doWork()

        assertTrue(resumedAttempt is ListenableWorker.Result.Success)
        val remainingPages = (cards.size - firstPageSize + DictionaryEnrichmentWorker.MAX_CARDS_PER_RUN - 1) /
            DictionaryEnrichmentWorker.MAX_CARDS_PER_RUN
        assertEquals(2 + remainingPages, requestCount)
        assertEquals(75, cards.count { store.findCard(it.id)?.meaningKo?.isNotBlank() == true })
        assertFalse(store.hasCardsNeedingDictionaryEnrichment())
    }

    @Test
    fun workerRechecksStoreAndDoesNotOverwriteManualEditMadeDuringLookup() = runBlocking {
        val card = blankCard("racing")
        DictionaryEnrichmentRuntime.clientFactory = {
            DictionaryLookupClient {
                store.updateCardDetails(
                    cardId = card.id,
                    meaningKo = "수동 뜻",
                    exampleSentence = "",
                    now = now.minusSeconds(1)
                )
                listOf(resolved("racing", "자동 뜻"))
            }
        }

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("수동 뜻", store.findCard(card.id)?.meaningKo)
        assertEquals(DictionaryEnrichmentStatus.USER_LOCKED, store.dictionaryEnrichmentState(card.id)?.status)
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncWorker.IMMEDIATE_WORK).get().isEmpty())
    }

    @Test
    fun workerDistinguishesNotFoundAndProviderFailureAndRetriesOnlyProviderFailure() = runBlocking {
        val missing = blankCard("unknownword")
        val provider = blankCard("provider")
        DictionaryEnrichmentRuntime.clientFactory = {
            DictionaryLookupClient { lemmas ->
                lemmas.map { lemma ->
                    when (lemma) {
                        "unknownword" -> pending(lemma, DictionaryLookupErrorCode.NOT_FOUND)
                        else -> pending(lemma, DictionaryLookupErrorCode.PROVIDER_FAILURE)
                    }
                }
            }
        }

        val result = worker().doWork()

        assertTrue(result is ListenableWorker.Result.Retry)
        assertEquals(DictionaryEnrichmentFailure.NOT_FOUND, store.dictionaryEnrichmentState(missing.id)?.failure)
        assertEquals(DictionaryEnrichmentFailure.PROVIDER_FAILURE, store.dictionaryEnrichmentState(provider.id)?.failure)
        assertEquals(listOf(provider.id), store.cardsNeedingDictionaryEnrichment(10).map { it.id })
    }

    @Test
    fun transportFailureIsNetworkFailureAndStopsAfterBoundedAttempts() = runBlocking {
        val card = blankCard("offline")
        DictionaryEnrichmentRuntime.clientFactory = {
            DictionaryLookupClient { throw CloudSyncException("offline", REQUEST_ID) }
        }

        val retry = worker(runAttemptCount = 0).doWork()
        val stopped = worker(runAttemptCount = DictionaryEnrichmentWorker.MAX_ATTEMPTS - 1).doWork()

        assertTrue(retry is ListenableWorker.Result.Retry)
        assertTrue(stopped is ListenableWorker.Result.Success)
        assertEquals(DictionaryEnrichmentFailure.NETWORK, store.dictionaryEnrichmentState(card.id)?.failure)
        assertEquals(REQUEST_ID, store.dictionaryEnrichmentState(card.id)?.requestId)
    }

    @Test
    fun enqueueUsesConnectedUniqueAppendPolicy() {
        DictionaryEnrichmentWorker.enqueue(context)

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(DictionaryEnrichmentWorker.UNIQUE_WORK)
            .get()

        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, DictionaryEnrichmentWorker.WORK_POLICY)
        assertTrue(infos.isNotEmpty())
        assertTrue(infos.all { it.state == WorkInfo.State.ENQUEUED })
    }

    private fun blankCard(word: String) = store.addCard(
        displayWord = word,
        meaningKo = "",
        exampleSentence = "",
        dueAt = now.minusSeconds(3_600),
        now = now.minusSeconds(7_200),
        definitionSourcePrimary = "pending_capture"
    )

    private fun indexedWord(index: Int): String {
        val suffix = index.toString().map { digit -> ('a'.code + (digit - '0')).toChar() }.joinToString("")
        // End in a neutral letter so the capture normalizer does not collapse test
        // fixtures through its common -s/-ed/-ing inflection rules.
        return "vocab${suffix}a"
    }

    private fun worker(runAttemptCount: Int = 0): DictionaryEnrichmentWorker {
        return TestListenableWorkerBuilder<DictionaryEnrichmentWorker>(context)
            .setRunAttemptCount(runAttemptCount)
            .build()
    }

    private fun resolved(lemma: String, meaning: String): DictionaryLookupResult {
        return DictionaryLookupResult(
            status = DictionaryLookupStatus.RESOLVED,
            lemma = lemma,
            meaningKo = meaning,
            definitionEn = "definition for $lemma",
            sourceType = "offline_catalog",
            confidence = 0.9,
            attemptedAt = now.minusSeconds(1),
            requestId = REQUEST_ID
        )
    }

    private fun pending(
        lemma: String,
        errorCode: DictionaryLookupErrorCode
    ): DictionaryLookupResult {
        return DictionaryLookupResult(
            status = DictionaryLookupStatus.PENDING,
            lemma = lemma,
            meaningKo = "",
            definitionEn = "",
            sourceType = "none",
            attemptedAt = now.minusSeconds(1),
            errorCode = errorCode,
            requestId = REQUEST_ID
        )
    }

    private companion object {
        const val REQUEST_ID = "151708e5-64d1-447f-99bd-7b0e9bc9683e"
    }
}
