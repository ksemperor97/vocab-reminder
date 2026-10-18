package com.vocabreminder.android.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vocabreminder.android.data.ContextMeaning
import com.vocabreminder.android.data.DictionaryEnrichmentFailure
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.data.applyContextMeaning
import com.vocabreminder.android.data.applyDictionaryDefinitionFallback
import com.vocabreminder.android.data.applyDictionaryEnrichment
import com.vocabreminder.android.data.cardsNeedingContextMeaning
import com.vocabreminder.android.data.cardsNeedingDictionaryEnrichment
import com.vocabreminder.android.data.hasCardsNeedingContextMeaning
import com.vocabreminder.android.data.hasCardsNeedingDictionaryEnrichment
import com.vocabreminder.android.data.markDictionaryEnrichmentPending
import com.vocabreminder.android.data.recordContextAttempt
import com.vocabreminder.android.data.recordDictionaryEnrichmentFailure
import com.vocabreminder.android.dictionary.DictionaryLookupErrorCode
import com.vocabreminder.android.dictionary.DictionaryLookupRequest
import com.vocabreminder.android.dictionary.DictionaryLookupStatus
import com.vocabreminder.android.notifications.ReminderScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.TimeUnit

class DictionaryEnrichmentWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = LocalVocabularyStore(applicationContext)
        val credentials = store.cloudSyncCredentials()
        val serverUrl = credentials.serverBaseUrl.trim()
        val token = credentials.bearerToken.trim()
        if (serverUrl.isBlank() || token.isBlank()) return@withContext Result.success()

        val config = CloudSyncConfig(serverUrl, token)
        var client: DictionaryLookupClient? = null
        var processedCards = 0
        var appliedAnyDictionaryData = false

        while (processedCards < MAX_CARDS_PER_WORK_INVOCATION) {
            val pageSize = minOf(
                MAX_CARDS_PER_RUN,
                MAX_CARDS_PER_WORK_INVOCATION - processedCards
            )
            // Blank cards first; then cards whose dictionary meaning may be
            // replaced by the meaning their captured sentence uses.
            val blankCards = store.cardsNeedingDictionaryEnrichment(pageSize)
            val contextCards = store.cardsNeedingContextMeaning(pageSize - blankCards.size, DictionaryEnrichmentRuntime.now())
            val queuedCards = blankCards + contextCards
            if (queuedCards.isEmpty()) break
            processedCards += queuedCards.size

            val validCards = queuedCards.filter { card ->
                runCatching { normalizeDictionaryLemma(card.lemma) }.isSuccess
            }
            val invalidIds = queuedCards.map { it.id }.toSet() - validCards.map { it.id }.toSet()
            val now = DictionaryEnrichmentRuntime.now()
            store.recordDictionaryEnrichmentFailure(
                invalidIds,
                DictionaryEnrichmentFailure.NOT_FOUND,
                now
            )
            contextCards.filter { it.id in invalidIds }.forEach { store.recordContextAttempt(it, null, now) }
            if (validCards.isEmpty()) continue

            store.markDictionaryEnrichmentPending(validCards.map { it.id })
            val results = try {
                val lookupClient = client
                    ?: DictionaryEnrichmentRuntime.clientFactory(config).also { client = it }
                lookupClient.lookupDictionary(validCards.map { DictionaryLookupRequest(it.lemma, ContextMeaning.sentence(it)) })
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                store.recordDictionaryEnrichmentFailure(
                    validCards.map { it.id },
                    DictionaryEnrichmentFailure.NETWORK,
                    now,
                    (error as? CloudSyncException)?.requestId
                )
                if (appliedAnyDictionaryData) SyncWorker.enqueueImmediate(applicationContext)
                return@withContext retryOrStop()
            }

            var retryableFailure = false
            val contextIds = contextCards.map { it.id }.toSet()
            validCards.zip(results).forEach { (card, result) ->
                if (card.id in contextIds) {
                    val updated = store.applyContextMeaning(card, result, now)
                    if (updated != null) {
                        ReminderScheduler.schedule(applicationContext, updated, now)
                        appliedAnyDictionaryData = true
                    }
                    return@forEach
                }
                if (ContextMeaning.sentence(card).isNotBlank()) store.recordContextAttempt(card, result, now)
                when {
                    result.status == DictionaryLookupStatus.RESOLVED && result.meaningKo.isNotBlank() -> {
                        val updated = store.applyDictionaryEnrichment(card.id, result, now)
                        if (updated != null) {
                            ReminderScheduler.schedule(applicationContext, updated, now)
                            appliedAnyDictionaryData = true
                        }
                    }
                    result.status == DictionaryLookupStatus.RESOLVED && result.definitionEn.isNotBlank() -> {
                        appliedAnyDictionaryData = store.applyDictionaryDefinitionFallback(card.id, result, now) ||
                            appliedAnyDictionaryData
                    }
                    result.errorCode == DictionaryLookupErrorCode.PROVIDER_FAILURE -> {
                        store.recordDictionaryEnrichmentFailure(
                            listOf(card.id),
                            DictionaryEnrichmentFailure.PROVIDER_FAILURE,
                            result.attemptedAt ?: now,
                            result.requestId
                        )
                        retryableFailure = true
                    }
                    else -> store.recordDictionaryEnrichmentFailure(
                        listOf(card.id),
                        DictionaryEnrichmentFailure.NOT_FOUND,
                        result.attemptedAt ?: now,
                        result.requestId
                    )
                }
            }

            if (retryableFailure) {
                if (appliedAnyDictionaryData) SyncWorker.enqueueImmediate(applicationContext)
                return@withContext retryOrStop()
            }
        }

        if (appliedAnyDictionaryData) SyncWorker.enqueueImmediate(applicationContext)
        if (store.hasCardsNeedingDictionaryEnrichment() || store.hasCardsNeedingContextMeaning(DictionaryEnrichmentRuntime.now())) {
            // Normal pagination must not consume WorkManager's failure-attempt budget.
            // A fresh request continues unusually large queues with a new attempt count.
            enqueue(applicationContext)
        }
        Result.success()
    }

    private fun retryOrStop(): Result {
        return if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    companion object {
        internal const val UNIQUE_WORK = "dictionary-enrichment"
        internal const val MAX_CARDS_PER_RUN = CloudSyncClient.MAX_DICTIONARY_ITEMS_PER_BATCH
        internal const val MAX_CARDS_PER_WORK_INVOCATION = 250
        internal const val MAX_ATTEMPTS = 5
        internal val WORK_POLICY = ExistingWorkPolicy.APPEND_OR_REPLACE

        private val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<DictionaryEnrichmentWorker>()
                .setConstraints(networkConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                WORK_POLICY,
                request
            )
        }
    }
}

internal object DictionaryEnrichmentRuntime {
    private val defaultClientFactory: (CloudSyncConfig) -> DictionaryLookupClient = { CloudSyncClient(it) }
    private val defaultNow: () -> Instant = { Instant.now() }

    @Volatile
    var clientFactory: (CloudSyncConfig) -> DictionaryLookupClient = defaultClientFactory

    @Volatile
    var now: () -> Instant = defaultNow

    fun resetForTests() {
        clientFactory = defaultClientFactory
        now = defaultNow
    }
}
