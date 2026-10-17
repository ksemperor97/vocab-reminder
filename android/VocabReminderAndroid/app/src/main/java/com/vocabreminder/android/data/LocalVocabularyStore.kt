package com.vocabreminder.android.data

import com.vocabreminder.android.recommendation.RecommendationEngine
import android.content.Context
import android.content.SharedPreferences
import com.vocabreminder.android.BuildConfig
import com.vocabreminder.android.capture.CaptureMethod
import com.vocabreminder.android.capture.CaptureNormalizer
import com.vocabreminder.android.capture.CaptureProvenanceExtractor
import com.vocabreminder.android.dictionary.DictionaryLookupResult
import com.vocabreminder.android.dictionary.DictionaryLookupStatus
import com.vocabreminder.android.dictionary.DictionaryResult
import com.vocabreminder.android.scheduler.CardStatus
import com.vocabreminder.android.scheduler.DueTimeJitter
import com.vocabreminder.android.scheduler.ReviewEventDraft
import com.vocabreminder.android.scheduler.ReviewResponse
import com.vocabreminder.android.scheduler.Scheduler
import com.vocabreminder.android.scheduler.SchedulerCard
import com.vocabreminder.android.scheduler.SchedulerSettings
import com.vocabreminder.android.scheduler.Wordbook
import com.vocabreminder.android.sync.CloudSyncClient
import com.vocabreminder.android.sync.DictionaryEnrichmentWorker
import com.vocabreminder.android.sync.SyncPayloadFactory
import com.vocabreminder.android.sync.normalizeRequestId
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

data class VocabularyCard(
    val id: String,
    val lemma: String,
    val displayWord: String,
    val meaningKo: String,
    val definitionEn: String = "",
    val partOfSpeech: String = "",
    val phonetic: String = "",
    val exampleSentence: String = "",
    val sourceAppPrimary: String = "",
    val sourceUrlPrimary: String = "",
    val definitionSourcePrimary: String = "user",
    val primaryDefinitionCandidateId: String? = null,
    val status: CardStatus,
    val wordbook: Wordbook = Wordbook.USER,
    val dueAt: Instant,
    val intervalDays: Int,
    val lapseCount: Int,
    val intervalBeforeLapse: Int? = null,
    val recommendationStartedAt: Instant? = null,
    val deletedAt: Instant? = null,
    val notificationId: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Int = 1
) {
    fun toSchedulerCard(): SchedulerCard {
        return SchedulerCard(
            id = id,
            status = status,
            intervalDays = intervalDays,
            dueAt = dueAt,
            lapseCount = lapseCount,
            intervalBeforeLapse = intervalBeforeLapse,
            meaningKo = meaningKo,
            wordbook = wordbook,
            recommendationStartedAt = recommendationStartedAt,
            deletedAt = deletedAt
        )
    }

    fun withSchedulerCard(card: SchedulerCard, now: Instant): VocabularyCard {
        return copy(
            status = card.status,
            dueAt = card.dueAt,
            intervalDays = card.intervalDays,
            lapseCount = card.lapseCount,
            intervalBeforeLapse = card.intervalBeforeLapse,
            updatedAt = now,
            version = version + 1
        )
    }
}

data class StoredContext(
    val id: String,
    val cardId: String,
    val sourceSentence: String,
    val sourceUrl: String,
    val sourceApp: String,
    val pageTitle: String = "",
    val captureMethod: CaptureMethod,
    val createdAt: Instant
)

data class StoredDefinitionCandidate(
    val id: String,
    val cardId: String,
    val source: String,
    val rawText: String,
    val parsedMeaningKo: String,
    val parsedDefinitionEn: String,
    val partOfSpeech: String,
    val phonetic: String,
    val exampleSentence: String,
    val confidence: Double,
    val selectedAsPrimary: Boolean,
    val createdAt: Instant,
    val originDeviceId: String? = null,
    val baseCardVersion: Int? = null
)

data class StoredReviewEvent(
    val id: String,
    val cardId: String,
    val response: ReviewResponse,
    val previousStatus: CardStatus,
    val nextStatus: CardStatus,
    val previousDueAt: Instant,
    val nextDueAt: Instant,
    val previousIntervalDays: Int,
    val nextIntervalDays: Int,
    val clientCreatedAt: Instant,
    val metadata: Map<String, String> = emptyMap()
)

data class RecommendationFeedback(
    val id: String,
    val cardId: String,
    val lemma: String,
    val action: String,
    val createdAt: Instant
)

data class UserSettingsData(
    val dailyWordGoal: Int = 10,
    val maxQueueSize: Int = 20,
    val snoozeDurationMinutes: Long = 60,
    val quietHoursStart: LocalTime = LocalTime.of(23, 0),
    val quietHoursEnd: LocalTime = LocalTime.of(9, 0),
    val timezone: ZoneId = ZoneId.of("Asia/Seoul"),
    val enableWordRecommendations: Boolean = true,
    val enableWatchReplyReview: Boolean = true,
    val recommendationsIntroducedAt: Instant? = null,
    val updatedAt: Instant = Instant.EPOCH
) {
    fun toSchedulerSettings(): SchedulerSettings {
        return SchedulerSettings(
            dailyWordGoal = dailyWordGoal,
            maxQueueSize = maxQueueSize,
            snoozeDurationMinutes = snoozeDurationMinutes,
            quietHoursStart = quietHoursStart,
            quietHoursEnd = quietHoursEnd,
            timezone = timezone,
            enableWordRecommendations = enableWordRecommendations
        )
    }
}

data class CloudSyncCredentials(
    val serverBaseUrl: String = BuildConfig.DEFAULT_SYNC_SERVER_URL,
    val bearerToken: String = BuildConfig.DEFAULT_SYNC_BEARER_TOKEN
)

data class CloudSyncStatus(
    val lastSuccessAt: Instant? = null,
    val lastFailureAt: Instant? = null,
    val lastError: String = "",
    val lastSkippedSummary: String = "",
    val lastRequestId: String = "",
    val lastWorkerRunAt: Instant? = null
)

data class CloudSyncResult(
    val snapshotJson: String,
    val skippedSummary: String,
    val requestId: String? = null
)

data class CardEditResult(
    val before: VocabularyCard,
    val after: VocabularyCard
) {
    val filledMissingMeaning: Boolean = before.meaningKo.isBlank() && after.meaningKo.isNotBlank()
}

class LocalVocabularyStore private constructor(
    context: Context,
    private val transactionCommitter: (SharedPreferences.Editor) -> Boolean,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit
) {
    constructor(context: Context) : this(context, { editor -> editor.commit() }, Unit)

    internal constructor(
        context: Context,
        transactionCommitter: (SharedPreferences.Editor) -> Boolean
    ) : this(context, transactionCommitter, Unit)

    private val applicationContext = context.applicationContext
    private val prefs = applicationContext.getSharedPreferences("vocab_reminder_store", Context.MODE_PRIVATE)

    fun cards(): List<VocabularyCard> {
        val excluded = recommendationFeedback().filter { it.action == "known" || it.action == "dismissed" }
            .map { RecommendationEngine.normalizedLemma(it.lemma) }.toSet()
        return allCards(includeDeleted = false).filter {
            it.wordbook != Wordbook.RECOMMENDED || RecommendationEngine.normalizedLemma(it.lemma) !in excluded
        }.sortedBy { it.createdAt }
    }

    fun allCards(includeDeleted: Boolean = false): List<VocabularyCard> {
        val array = JSONArray(prefs.getString(KEY_CARDS, "[]"))
        return (0 until array.length())
            .map { parseCard(array.getJSONObject(it)) }
            .filter { includeDeleted || it.deletedAt == null }
            .sortedBy { it.createdAt }
    }

    fun contexts(): List<StoredContext> {
        val array = JSONArray(prefs.getString(KEY_CONTEXTS, "[]"))
        return (0 until array.length()).map { parseContext(array.getJSONObject(it)) }
            .sortedBy { it.createdAt }
    }

    fun definitionCandidates(): List<StoredDefinitionCandidate> {
        val array = JSONArray(prefs.getString(KEY_DEFINITION_CANDIDATES, "[]"))
        return (0 until array.length()).map { parseDefinitionCandidate(array.getJSONObject(it)) }
            .sortedBy { it.createdAt }
    }

    fun events(): List<StoredReviewEvent> {
        val array = JSONArray(prefs.getString(KEY_EVENTS, "[]"))
        return (0 until array.length()).map { parseEvent(array.getJSONObject(it)) }
            .sortedBy { it.clientCreatedAt }
    }

    fun recommendationFeedback(): List<RecommendationFeedback> {
        val array = JSONArray(prefs.getString(KEY_RECOMMENDATION_FEEDBACK, "[]"))
        return (0 until array.length()).map { parseRecommendationFeedback(array.getJSONObject(it)) }
            .sortedBy { it.createdAt }
    }

    fun settings(): UserSettingsData {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return UserSettingsData()
        return parseSettings(JSONObject(raw))
    }

    fun cloudSyncCredentials(): CloudSyncCredentials {
        val defaults = CloudSyncCredentials()
        if (!BuildConfig.DEBUG) return defaults
        val raw = prefs.getString(KEY_CLOUD_SYNC, null) ?: return defaults
        val json = JSONObject(raw)
        return CloudSyncCredentials(
            serverBaseUrl = json.optString(
                "server_base_url",
                json.optString("functions_base_url", defaults.serverBaseUrl)
            ).ifBlank { defaults.serverBaseUrl },
            bearerToken = json.optString("bearer_token").ifBlank { defaults.bearerToken }
        )
    }

    fun saveCloudSyncCredentials(credentials: CloudSyncCredentials) {
        if (!BuildConfig.DEBUG) return
        prefs.edit().putString(
            KEY_CLOUD_SYNC,
            JSONObject()
                .put("server_base_url", credentials.serverBaseUrl)
                .put("bearer_token", credentials.bearerToken)
                .toString()
        ).apply()
    }

    fun cloudSyncStatus(): CloudSyncStatus {
        val raw = prefs.getString(KEY_CLOUD_SYNC_STATUS, null) ?: return CloudSyncStatus()
        val json = JSONObject(raw)
        return CloudSyncStatus(
            lastSuccessAt = json.optNullableInstant("last_success_at"),
            lastFailureAt = json.optNullableInstant("last_failure_at"),
            lastError = json.optString("last_error"),
            lastSkippedSummary = json.optString("last_skipped_summary"),
            lastRequestId = normalizeRequestId(json.optString("last_request_id")).orEmpty(),
            lastWorkerRunAt = json.optNullableInstant("last_worker_run_at")
        )
    }

    fun onboardingState(now: Instant = Instant.now()): AndroidOnboardingState {
        val raw = prefs.getString(KEY_ONBOARDING, null)
        val parsed = raw?.let { value ->
            runCatching {
                val json = JSONObject(value)
                AndroidOnboardingState(
                    firstLaunchAt = json.optNullableInstant("first_launch_at") ?: now,
                    captureGuideCompletedAt = json.optNullableInstant("capture_guide_completed_at")
                )
            }.getOrNull()
        }
        if (parsed != null) return parsed
        return AndroidOnboardingState(firstLaunchAt = now).also(::saveOnboardingState)
    }

    fun completeCaptureGuide(now: Instant = Instant.now()): AndroidOnboardingState {
        val completed = onboardingState(now).copy(captureGuideCompletedAt = now)
        saveOnboardingState(completed)
        return completed
    }

    fun dictionaryEnrichmentState(cardId: String): DictionaryEnrichmentState? {
        val card = findCard(cardId) ?: return null
        return derivedDictionaryEnrichmentState(card, persistedDictionaryEnrichmentStates()[cardId])
    }

    private fun derivedDictionaryEnrichmentState(
        card: VocabularyCard,
        persisted: DictionaryEnrichmentState?
    ): DictionaryEnrichmentState {
        return when {
            persisted?.status == DictionaryEnrichmentStatus.USER_LOCKED -> persisted
            card.definitionSourcePrimary == "user" -> DictionaryEnrichmentState(
                cardId = card.id,
                status = DictionaryEnrichmentStatus.USER_LOCKED,
                attemptedAt = persisted?.attemptedAt,
                requestId = persisted?.requestId
            )
            card.meaningKo.isNotBlank() -> DictionaryEnrichmentState(
                cardId = card.id,
                status = DictionaryEnrichmentStatus.RESOLVED,
                attemptedAt = persisted?.attemptedAt,
                requestId = persisted?.requestId
            )
            persisted != null -> persisted
            else -> DictionaryEnrichmentState(card.id, DictionaryEnrichmentStatus.PENDING)
        }
    }

    fun dictionaryEnrichmentStates(): List<DictionaryEnrichmentState> {
        val persisted = persistedDictionaryEnrichmentStates()
        return cards().map { card -> derivedDictionaryEnrichmentState(card, persisted[card.id]) }
    }

    internal fun cardsNeedingDictionaryEnrichment(limit: Int): List<VocabularyCard> {
        if (limit <= 0) return emptyList()
        val persisted = persistedDictionaryEnrichmentStates()
        return cards().asSequence()
            .filter { it.meaningKo.isBlank() }
            .filter { card ->
                val state = derivedDictionaryEnrichmentState(card, persisted[card.id])
                state.status == DictionaryEnrichmentStatus.PENDING ||
                    (state.status == DictionaryEnrichmentStatus.FAILED &&
                        state.failure in setOf(
                            DictionaryEnrichmentFailure.NETWORK,
                            DictionaryEnrichmentFailure.PROVIDER_FAILURE
                        ))
            }
            .take(limit)
            .toList()
    }

    internal fun hasCardsNeedingDictionaryEnrichment(): Boolean {
        return cardsNeedingDictionaryEnrichment(limit = 1).isNotEmpty()
    }

    internal fun recordDictionaryEnrichmentFailure(
        cardIds: Collection<String>,
        failure: DictionaryEnrichmentFailure,
        attemptedAt: Instant,
        requestId: String? = null
    ) = synchronized(CARD_MUTATION_LOCK) {
        if (cardIds.isEmpty()) return@synchronized
        val existing = persistedDictionaryEnrichmentStates().toMutableMap()
        cardIds.forEach { cardId ->
            val card = findCard(cardId)
            if (card != null && card.meaningKo.isBlank() && card.definitionSourcePrimary != "user") {
                existing[cardId] = DictionaryEnrichmentState(
                    cardId = cardId,
                    status = DictionaryEnrichmentStatus.FAILED,
                    failure = failure,
                    attemptedAt = attemptedAt,
                    requestId = normalizeRequestId(requestId)
                )
            }
        }
        commitStoreTransaction(StoreTransaction(dictionaryEnrichmentStates = existing.values))
    }

    internal fun markDictionaryEnrichmentPending(
        cardIds: Collection<String>
    ) = synchronized(CARD_MUTATION_LOCK) {
        if (cardIds.isEmpty()) return@synchronized
        val existing = persistedDictionaryEnrichmentStates().toMutableMap()
        cardIds.forEach { cardId ->
            val card = findCard(cardId)
            if (card != null && card.meaningKo.isBlank() &&
                derivedDictionaryEnrichmentState(card, existing[cardId]).status != DictionaryEnrichmentStatus.USER_LOCKED
            ) {
                existing[cardId] = DictionaryEnrichmentState(
                    cardId = cardId,
                    status = DictionaryEnrichmentStatus.PENDING,
                    attemptedAt = existing[cardId]?.attemptedAt,
                    requestId = existing[cardId]?.requestId
                )
            }
        }
        commitStoreTransaction(StoreTransaction(dictionaryEnrichmentStates = existing.values))
    }

    fun retryDictionaryEnrichment(cardId: String? = null) = synchronized(CARD_MUTATION_LOCK) {
        val storedCards = allCards(includeDeleted = true)
        val cardsById = storedCards.filter { it.deletedAt == null }.associateBy { it.id }
        val updatedCards = storedCards.associateBy { it.id }.toMutableMap()
        val existing = persistedDictionaryEnrichmentStates().toMutableMap()
        val targetIds = if (cardId == null) cardsById.keys else setOf(cardId)
        val now = Instant.now()
        targetIds.forEach { id ->
            val card = cardsById[id]
            if (card != null && card.meaningKo.isBlank()) {
                if (card.definitionSourcePrimary == "user") {
                    updatedCards[id] = card.copy(
                        definitionSourcePrimary = "dictionary_retry",
                        updatedAt = now,
                        version = card.version + 1
                    )
                }
                existing[id] = DictionaryEnrichmentState(
                    cardId = id,
                    status = DictionaryEnrichmentStatus.PENDING
                )
            }
        }
        commitStoreTransaction(
            StoreTransaction(
                cards = updatedCards.values.toList(),
                dictionaryEnrichmentStates = existing.values
            )
        )
        enqueueDictionaryEnrichmentSafely()
    }

    internal fun applyDictionaryEnrichment(
        cardId: String,
        result: DictionaryLookupResult,
        now: Instant
    ): VocabularyCard? = synchronized(CARD_MUTATION_LOCK) {
        val storedCards = allCards(includeDeleted = true)
        val current = storedCards.firstOrNull { it.id == cardId && it.deletedAt == null }
            ?: return@synchronized null
        if (current.meaningKo.isNotBlank() || current.definitionSourcePrimary == "user") {
            val state = dictionaryStateForCard(
                card = current,
                timestamp = result.attemptedAt ?: now,
                requestId = result.requestId
            )
            commitStoreTransaction(
                StoreTransaction(dictionaryEnrichmentStates = dictionaryStatesReplacing(state))
            )
            return@synchronized null
        }
        if (result.status != DictionaryLookupStatus.RESOLVED || result.meaningKo.isBlank()) {
            return@synchronized null
        }

        val schedulerSettings = settings().toSchedulerSettings()
        val firstDueAt = DueTimeJitter.apply(
            Scheduler.initialDueAt(now, schedulerSettings),
            now,
            schedulerSettings
        )
        val updated = current.copy(
            meaningKo = result.meaningKo.trim(),
            definitionEn = result.definitionEn.ifBlank { current.definitionEn }.trim(),
            partOfSpeech = result.partOfSpeech.ifBlank { current.partOfSpeech }.trim(),
            exampleSentence = current.exampleSentence.ifBlank { result.exampleSentence }.trim(),
            definitionSourcePrimary = result.sourceType,
            dueAt = firstDueAt,
            updatedAt = now,
            version = current.version + 1
        )
        val candidate = buildDefinitionCandidate(
            cardId = updated.id,
            result = DictionaryResult(
                lemma = updated.lemma,
                displayWord = updated.displayWord,
                meaningKo = result.meaningKo,
                definitionEn = result.definitionEn,
                partOfSpeech = result.partOfSpeech,
                exampleSentence = result.exampleSentence,
                rawSource = result.meaningKo.ifBlank { result.definitionEn },
                sourceType = result.sourceType,
                confidence = result.confidence,
                sourceSentence = "",
                sourceApp = updated.sourceAppPrimary,
                sourceUrl = updated.sourceUrlPrimary
            ),
            selected = true,
            now = result.attemptedAt ?: now,
            originDeviceId = deviceId(),
            baseCardVersion = current.version
        )
        val state = dictionaryStateForCard(
            card = updated,
            timestamp = result.attemptedAt ?: now,
            requestId = result.requestId
        )
        val allCandidates = (definitionCandidates() + candidate).toMutableList()
        result.senses.take(20).forEach { sense ->
            if ((sense.meaningKo.isNotBlank() || sense.definitionEn.isNotBlank()) && allCandidates.none {
                    it.cardId == updated.id && it.parsedMeaningKo == sense.meaningKo &&
                        it.parsedDefinitionEn == sense.definitionEn && it.partOfSpeech == sense.partOfSpeech
                }) {
                allCandidates += candidate.copy(
                    id = UUID.randomUUID().toString(), rawText = listOf(sense.meaningKo, sense.definitionEn).joinToString("\n"),
                    parsedMeaningKo = sense.meaningKo, parsedDefinitionEn = sense.definitionEn,
                    partOfSpeech = sense.partOfSpeech, exampleSentence = sense.exampleSentence, selectedAsPrimary = false
                )
            }
        }
        commitStoreTransaction(
            StoreTransaction(
                cards = storedCards.map { if (it.id == updated.id) updated else it },
                definitionCandidates = allCandidates,
                dictionaryEnrichmentStates = dictionaryStatesReplacing(state)
            )
        )
        findCard(updated.id) ?: updated
    }

    internal fun applyDictionaryDefinitionFallback(
        cardId: String,
        result: DictionaryLookupResult,
        now: Instant
    ): Boolean = synchronized(CARD_MUTATION_LOCK) {
        val storedCards = allCards(includeDeleted = true)
        val current = storedCards.firstOrNull { it.id == cardId && it.deletedAt == null }
            ?: return@synchronized false
        if (
            current.meaningKo.isNotBlank() ||
            current.definitionSourcePrimary == "user" ||
            result.definitionEn.isBlank()
        ) {
            return@synchronized false
        }
        val updated = current.copy(
            definitionEn = result.definitionEn.trim(),
            partOfSpeech = result.partOfSpeech.ifBlank { current.partOfSpeech }.trim(),
            exampleSentence = current.exampleSentence.ifBlank { result.exampleSentence }.trim(),
            updatedAt = now,
            version = current.version + 1
        )
        val candidate = buildDefinitionCandidate(
            cardId = updated.id,
            result = DictionaryResult(
                lemma = updated.lemma,
                displayWord = updated.displayWord,
                meaningKo = "",
                definitionEn = result.definitionEn,
                partOfSpeech = result.partOfSpeech,
                exampleSentence = result.exampleSentence,
                rawSource = result.definitionEn,
                sourceType = result.sourceType,
                confidence = result.confidence,
                sourceSentence = "",
                sourceApp = updated.sourceAppPrimary,
                sourceUrl = updated.sourceUrlPrimary
            ),
            selected = false,
            now = result.attemptedAt ?: now
        )
        val state = DictionaryEnrichmentState(
            cardId = updated.id,
            status = DictionaryEnrichmentStatus.FAILED,
            failure = DictionaryEnrichmentFailure.NOT_FOUND,
            attemptedAt = result.attemptedAt ?: now,
            requestId = normalizeRequestId(result.requestId)
        )
        commitStoreTransaction(
            StoreTransaction(
                cards = storedCards.map { if (it.id == updated.id) updated else it },
                definitionCandidates = definitionCandidates() + candidate,
                dictionaryEnrichmentStates = dictionaryStatesReplacing(state)
            )
        )
        true
    }

    fun recordCloudSyncSuccess(skippedSummary: String = "", requestId: String? = null) {
        val previous = cloudSyncStatus()
        saveCloudSyncStatus(
            previous.copy(
                lastSuccessAt = Instant.now(),
                lastError = "",
                lastSkippedSummary = skippedSummary,
                lastRequestId = normalizeRequestId(requestId).orEmpty()
            )
        )
    }

    fun recordCloudSyncFailure(error: String, requestId: String? = null) {
        val previous = cloudSyncStatus()
        saveCloudSyncStatus(
            previous.copy(
                lastFailureAt = Instant.now(),
                lastError = error.take(MAX_SYNC_ERROR_LENGTH),
                lastRequestId = normalizeRequestId(requestId).orEmpty()
            )
        )
    }

    fun recordSyncWorkerHeartbeat(now: Instant = Instant.now()) {
        saveCloudSyncStatus(cloudSyncStatus().copy(lastWorkerRunAt = now))
    }

    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun isUiRefreshKey(key: String?): Boolean {
        return key in setOf(
            KEY_CARDS,
            KEY_CONTEXTS,
            KEY_DEFINITION_CANDIDATES,
            KEY_EVENTS,
            KEY_SETTINGS,
            KEY_CLOUD_SYNC_STATUS,
            KEY_DICTIONARY_ENRICHMENT_STATES
        )
    }

    fun saveSettings(settings: UserSettingsData) {
        saveSettings(settings, preserveUpdatedAt = false)
    }

    fun markRecommendationsIntroduced(now: Instant) {
        saveSettings(settings().copy(recommendationsIntroducedAt = now))
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    fun findCard(id: String): VocabularyCard? {
        return allCards(includeDeleted = true).firstOrNull { it.id == id && it.deletedAt == null }
    }

    fun userWordCount(): Int {
        return cards().count { it.wordbook == Wordbook.USER }
    }

    fun addCard(
        displayWord: String,
        meaningKo: String,
        exampleSentence: String,
        dueAt: Instant,
        now: Instant,
        definitionEn: String = "",
        partOfSpeech: String = "",
        phonetic: String = "",
        sourceApp: String = "",
        sourceUrl: String = "",
        captureMethod: CaptureMethod = CaptureMethod.MANUAL,
        definitionSourcePrimary: String = "user",
        wordbook: Wordbook = Wordbook.USER
    ): VocabularyCard {
        val result = DictionaryResult(
            lemma = CaptureNormalizer.lemma(displayWord),
            displayWord = CaptureNormalizer.normalizeText(displayWord),
            meaningKo = meaningKo.trim(),
            definitionEn = definitionEn.trim(),
            partOfSpeech = partOfSpeech.trim(),
            phonetic = phonetic.trim(),
            exampleSentence = exampleSentence.trim(),
            rawSource = definitionEn.ifBlank { meaningKo },
            sourceType = definitionSourcePrimary,
            confidence = if (meaningKo.isBlank()) 0.0 else 1.0,
            sourceSentence = exampleSentence.trim(),
            sourceApp = sourceApp,
            sourceUrl = sourceUrl
        )
        return addResolvedCard(result, captureMethod, dueAt, now, wordbook)
    }

    fun addResolvedCard(
        result: DictionaryResult,
        captureMethod: CaptureMethod,
        dueAt: Instant,
        now: Instant,
        wordbook: Wordbook = Wordbook.USER
    ): VocabularyCard = synchronized(CARD_MUTATION_LOCK) {
        val normalizedLemma = result.lemma.ifBlank { CaptureNormalizer.lemma(result.displayWord) }
        val storedCards = allCards(includeDeleted = true)
        val existing = storedCards.firstOrNull {
            it.deletedAt == null && it.lemma.equals(normalizedLemma, ignoreCase = true)
        }
        if (existing != null) {
            val promotedWordbook = if (existing.wordbook == Wordbook.RECOMMENDED && wordbook == Wordbook.USER) Wordbook.USER else existing.wordbook
            val shouldUpdatePrimary = result.meaningKo.isNotBlank() &&
                (existing.meaningKo.isBlank() || result.sourceType == "user" || existing.wordbook == Wordbook.RECOMMENDED)
            val updated = existing.copy(
                displayWord = if (wordbook == Wordbook.USER) result.displayWord else existing.displayWord,
                meaningKo = if (shouldUpdatePrimary) result.meaningKo else existing.meaningKo,
                definitionEn = if (shouldUpdatePrimary) result.definitionEn else existing.definitionEn,
                partOfSpeech = if (shouldUpdatePrimary) result.partOfSpeech else existing.partOfSpeech,
                phonetic = if (shouldUpdatePrimary) result.phonetic else existing.phonetic,
                exampleSentence = result.sourceSentence.ifBlank { existing.exampleSentence },
                sourceAppPrimary = result.sourceApp.ifBlank { existing.sourceAppPrimary },
                sourceUrlPrimary = CaptureProvenanceExtractor.sanitizeUrl(result.sourceUrl).ifBlank { existing.sourceUrlPrimary },
                definitionSourcePrimary = if (shouldUpdatePrimary) result.sourceType else existing.definitionSourcePrimary,
                wordbook = promotedWordbook,
                updatedAt = now,
                version = existing.version + 1
            )
            val context = buildContext(updated.id, result, captureMethod, now)
            val candidate = buildDefinitionCandidate(
                updated.id,
                result,
                selected = shouldUpdatePrimary,
                now = now,
                originDeviceId = deviceId().takeIf { shouldUpdatePrimary },
                baseCardVersion = existing.version.takeIf { shouldUpdatePrimary }
            )
            val state = dictionaryStateForCard(updated, now)
            commitStoreTransaction(
                StoreTransaction(
                    cards = storedCards.map { if (it.id == updated.id) updated else it },
                    contexts = context?.let { contexts() + it },
                    definitionCandidates = definitionCandidates() + candidate,
                    dictionaryEnrichmentStates = dictionaryStatesReplacing(state)
                )
            )
            if (state.status == DictionaryEnrichmentStatus.PENDING) {
                enqueueDictionaryEnrichmentSafely()
            }
            return@synchronized findCard(updated.id) ?: updated
        }

        val id = UUID.randomUUID().toString()
        val card = VocabularyCard(
            id = id,
            lemma = normalizedLemma,
            displayWord = result.displayWord.ifBlank { normalizedLemma },
            meaningKo = result.meaningKo.trim(),
            definitionEn = result.definitionEn.trim(),
            partOfSpeech = result.partOfSpeech.trim(),
            phonetic = result.phonetic.trim(),
            exampleSentence = result.sourceSentence.ifBlank { result.exampleSentence }.trim(),
            sourceAppPrimary = result.sourceApp,
            sourceUrlPrimary = CaptureProvenanceExtractor.sanitizeUrl(result.sourceUrl),
            definitionSourcePrimary = result.sourceType,
            status = CardStatus.NEW,
            wordbook = wordbook,
            dueAt = dueAt,
            intervalDays = 0,
            lapseCount = 0,
            notificationId = id.hashCode() and 0x7fffffff,
            createdAt = now,
            updatedAt = now
        )
        val context = buildContext(card.id, result, captureMethod, now)
        val candidate = buildDefinitionCandidate(card.id, result, selected = true, now = now)
        val state = dictionaryStateForCard(card, now)
        commitStoreTransaction(
            StoreTransaction(
                cards = storedCards + card,
                contexts = context?.let { contexts() + it },
                definitionCandidates = definitionCandidates() + candidate,
                dictionaryEnrichmentStates = dictionaryStatesReplacing(state)
            )
        )
        if (state.status == DictionaryEnrichmentStatus.PENDING) {
            enqueueDictionaryEnrichmentSafely()
        }
        findCard(card.id) ?: card
    }

    fun addRecommendedCard(
        result: DictionaryResult,
        captureMethod: CaptureMethod,
        now: Instant
    ): VocabularyCard {
        return addResolvedCard(
            result = result,
            captureMethod = captureMethod,
            dueAt = now,
            now = now,
            wordbook = Wordbook.RECOMMENDED
        )
    }

    fun markRecommendationStarted(cardId: String, now: Instant) = synchronized(CARD_MUTATION_LOCK) {
        val storedCards = allCards(includeDeleted = true)
        val card = storedCards.firstOrNull { it.id == cardId && it.deletedAt == null } ?: return@synchronized
        val schedulerSettings = settings().toSchedulerSettings()
        val dueAt = DueTimeJitter.apply(Scheduler.initialDueAt(now, schedulerSettings), now, schedulerSettings)
        val updated = card.copy(
            recommendationStartedAt = now,
            dueAt = dueAt,
            updatedAt = now,
            version = card.version + 1
        )
        commitStoreTransaction(
            StoreTransaction(
                cards = storedCards.map { if (it.id == cardId) updated else it },
                recommendationFeedback = recommendationFeedback() +
                    buildRecommendationFeedback(card, "started", now)
            )
        )
    }

    fun softDeleteRecommendation(
        cardId: String,
        action: String,
        now: Instant
    ) = synchronized(CARD_MUTATION_LOCK) {
        val storedCards = allCards(includeDeleted = true)
        val card = storedCards.firstOrNull { it.id == cardId && it.deletedAt == null } ?: return@synchronized
        val deleted = card.copy(deletedAt = now, updatedAt = now, version = card.version + 1)
        commitStoreTransaction(
            StoreTransaction(
                cards = storedCards.map { if (it.id == cardId) deleted else it },
                recommendationFeedback = recommendationFeedback() +
                    buildRecommendationFeedback(card, action, now)
            )
        )
    }

    fun softDeleteCard(cardId: String, now: Instant): VocabularyCard? {
        val card = findCard(cardId) ?: return null
        val deleted = card.copy(deletedAt = now, updatedAt = now, version = card.version + 1)
        updateCard(deleted)
        return deleted
    }

    fun updateCard(card: VocabularyCard) = synchronized(CARD_MUTATION_LOCK) {
        commitStoreTransaction(
            StoreTransaction(
                cards = allCards(includeDeleted = true).map { if (it.id == card.id) card else it }
            )
        )
    }

    /** Atomic revision check + event ID deduplication for retried G2 answers. */
    fun applyG2Response(cardId: String, revision: String, actionId: String, response: ReviewResponse, now: Instant): String = synchronized(CARD_MUTATION_LOCK) {
        if (events().any { it.metadata["g2_action_id"] == actionId }) return@synchronized "applied"
        require(response in listOf(ReviewResponse.REMEMBERED, ReviewResponse.FORGOTTEN, ReviewResponse.UNAVAILABLE))
        val card = findCard(cardId) ?: return@synchronized "stale"
        if ("${card.version}:${card.updatedAt}" != revision || card.dueAt.isAfter(now) || card.status == CardStatus.SUSPENDED) return@synchronized "stale"
        val config = settings().toSchedulerSettings()
        val result = DueTimeJitter.apply(Scheduler.applyReview(
            card.toSchedulerCard(), response, config, now,
            recentResponses(card.id, 2), unavailableCountToday(card.id, config.timezone, now),
            forgottenCountToday(card.id, config.timezone, now)
        ), now, config)
        updateCardAndAppendEvent(card.withSchedulerCard(result.card, now), result.event.copy(
            metadata = result.event.metadata + mapOf("source" to "even_g2", "g2_action_id" to actionId)
        ), now)
        "applied"
    }

    fun updateCardAndAppendEvent(card: VocabularyCard, event: ReviewEventDraft, now: Instant) {
        updateCardsAndAppendEvents(listOf(card), listOf(event), now)
    }

    fun updateCardsAndAppendEvents(
        updatedCards: Collection<VocabularyCard>,
        eventDrafts: Collection<ReviewEventDraft>,
        now: Instant
    ) = synchronized(CARD_MUTATION_LOCK) {
        if (updatedCards.isEmpty() && eventDrafts.isEmpty()) return@synchronized
        val cardsById = updatedCards.associateBy { it.id }
        val storedEvents = eventDrafts.map { event -> storedEvent(event, now) }
        commitStoreTransaction(
            StoreTransaction(
                cards = updatedCards.takeIf { it.isNotEmpty() }?.let {
                    allCards(includeDeleted = true).map { current -> cardsById[current.id] ?: current }
                },
                events = storedEvents.takeIf { it.isNotEmpty() }?.let { events() + it }
            )
        )
    }

    fun updateCardDetails(
        cardId: String,
        meaningKo: String,
        exampleSentence: String,
        now: Instant = Instant.now()
    ): CardEditResult? = synchronized(CARD_MUTATION_LOCK) {
        val storedCards = allCards(includeDeleted = true)
        val before = storedCards.firstOrNull { it.id == cardId && it.deletedAt == null }
            ?: return@synchronized null
        val trimmedMeaning = meaningKo.trim()
        val trimmedExample = exampleSentence.trim()
        val after = before.copy(
            meaningKo = trimmedMeaning,
            exampleSentence = trimmedExample,
            definitionSourcePrimary = if (trimmedMeaning != before.meaningKo) "user" else before.definitionSourcePrimary,
            updatedAt = now,
            version = before.version + 1
        )
        val updatedCandidates = definitionCandidates().toMutableList()
        buildMeaningCandidateIfNeeded(before, updatedCandidates, now)?.let(updatedCandidates::add)
        if (after.meaningKo.isNotBlank() || after.definitionEn.isNotBlank()) {
            updatedCandidates += buildDefinitionCandidate(
                cardId = after.id,
                result = DictionaryResult(
                    lemma = after.lemma,
                    displayWord = after.displayWord,
                    meaningKo = after.meaningKo,
                    definitionEn = after.definitionEn,
                    partOfSpeech = after.partOfSpeech,
                    phonetic = after.phonetic,
                    exampleSentence = after.exampleSentence,
                    rawSource = after.meaningKo.ifBlank { after.definitionEn },
                    sourceType = "user",
                    confidence = 1.0,
                    sourceSentence = after.exampleSentence,
                    sourceApp = after.sourceAppPrimary,
                    sourceUrl = after.sourceUrlPrimary
                ),
                selected = true,
                now = now,
                originDeviceId = deviceId(),
                baseCardVersion = before.version
            )
        }
        val state = dictionaryStateForCard(after, now)
        commitStoreTransaction(
            StoreTransaction(
                cards = storedCards.map { if (it.id == after.id) after else it },
                definitionCandidates = updatedCandidates,
                events = events() + buildManualEditEvent(before, after, now),
                dictionaryEnrichmentStates = dictionaryStatesReplacing(state)
            )
        )
        if (state.status == DictionaryEnrichmentStatus.PENDING) {
            enqueueDictionaryEnrichmentSafely()
        }
        CardEditResult(before = before, after = after)
    }

    fun selectDefinitionCandidate(
        cardId: String,
        candidateId: String,
        now: Instant = Instant.now()
    ): CardEditResult? = synchronized(CARD_MUTATION_LOCK) {
        val storedCards = allCards(includeDeleted = true)
        val before = storedCards.firstOrNull { it.id == cardId && it.deletedAt == null }
            ?: return@synchronized null
        val updatedCandidates = definitionCandidates().toMutableList()
        val candidate = updatedCandidates.firstOrNull {
            it.id == candidateId && it.cardId == cardId
        } ?: return@synchronized null
        val selectedMeaning = candidate.parsedMeaningKo.trim()
        if (selectedMeaning.isBlank()) return@synchronized null

        buildMeaningCandidateIfNeeded(before, updatedCandidates, now)?.let(updatedCandidates::add)
        val schedulerSettings = settings().toSchedulerSettings()
        val dueAt = if (before.meaningKo.isBlank()) {
            DueTimeJitter.apply(Scheduler.initialDueAt(now, schedulerSettings), now, schedulerSettings)
        } else {
            before.dueAt
        }
        val after = before.copy(
            meaningKo = selectedMeaning,
            definitionEn = candidate.parsedDefinitionEn,
            partOfSpeech = candidate.partOfSpeech,
            phonetic = candidate.phonetic,
            exampleSentence = candidate.exampleSentence.ifBlank { before.exampleSentence },
            definitionSourcePrimary = candidate.source,
            dueAt = dueAt,
            updatedAt = now,
            version = before.version + 1
        )
        updatedCandidates += StoredDefinitionCandidate(
            id = UUID.randomUUID().toString(),
            cardId = after.id,
            source = after.definitionSourcePrimary,
            rawText = candidate.rawText,
            parsedMeaningKo = after.meaningKo,
            parsedDefinitionEn = after.definitionEn,
            partOfSpeech = after.partOfSpeech,
            phonetic = after.phonetic,
            exampleSentence = after.exampleSentence,
            confidence = candidate.confidence,
            selectedAsPrimary = true,
            createdAt = now,
            originDeviceId = deviceId(),
            baseCardVersion = before.version
        )
        val event = StoredReviewEvent(
            id = UUID.randomUUID().toString(),
            cardId = cardId,
            response = ReviewResponse.MANUAL_EDIT,
            previousStatus = before.status,
            nextStatus = after.status,
            previousDueAt = before.dueAt,
            nextDueAt = after.dueAt,
            previousIntervalDays = before.intervalDays,
            nextIntervalDays = after.intervalDays,
            clientCreatedAt = now,
            metadata = mapOf(
                "action" to "select_definition_candidate",
                "candidate_id" to candidate.id,
                "source" to candidate.source
            )
        )
        val states = persistedDictionaryEnrichmentStates().toMutableMap()
        states[after.id] = DictionaryEnrichmentState(
            cardId = after.id,
            status = DictionaryEnrichmentStatus.USER_LOCKED,
            attemptedAt = now
        )
        commitStoreTransaction(
            StoreTransaction(
                cards = storedCards.map { if (it.id == after.id) after else it },
                definitionCandidates = updatedCandidates,
                events = events() + event,
                dictionaryEnrichmentStates = states.values
            )
        )
        CardEditResult(before = before, after = after)
    }

    fun appendEvent(event: ReviewEventDraft, now: Instant) = synchronized(CARD_MUTATION_LOCK) {
        commitStoreTransaction(StoreTransaction(events = events() + storedEvent(event, now)))
    }

    private fun storedEvent(event: ReviewEventDraft, now: Instant): StoredReviewEvent {
        return StoredReviewEvent(
            id = UUID.randomUUID().toString(),
            cardId = event.cardId,
            response = event.response,
            previousStatus = event.previousStatus,
            nextStatus = event.nextStatus,
            previousDueAt = event.previousDueAt,
            nextDueAt = event.nextDueAt,
            previousIntervalDays = event.previousIntervalDays,
            nextIntervalDays = event.nextIntervalDays,
            clientCreatedAt = now,
            metadata = event.metadata
        )
    }

    private fun buildManualEditEvent(
        before: VocabularyCard,
        after: VocabularyCard,
        now: Instant
    ): StoredReviewEvent {
        return StoredReviewEvent(
            id = UUID.randomUUID().toString(),
            cardId = after.id,
            response = ReviewResponse.MANUAL_EDIT,
            previousStatus = before.status,
            nextStatus = after.status,
            previousDueAt = before.dueAt,
            nextDueAt = after.dueAt,
            previousIntervalDays = before.intervalDays,
            nextIntervalDays = after.intervalDays,
            clientCreatedAt = now,
            metadata = mapOf("action" to "manual_edit", "fields" to "meaning_ko,example_sentence")
        )
    }

    fun recentResponses(cardId: String, limit: Int): List<ReviewResponse> {
        return events()
            .filter {
                it.cardId == cardId &&
                    it.response in setOf(
                        ReviewResponse.REMEMBERED,
                        ReviewResponse.FORGOTTEN,
                        ReviewResponse.UNAVAILABLE,
                        ReviewResponse.REVEAL
                    )
            }
            .takeLast(limit)
            .map { it.response }
    }

    fun unavailableCountToday(cardId: String, zoneId: ZoneId, now: Instant): Int {
        val today = now.atZone(zoneId).toLocalDate()
        return events().count {
            it.cardId == cardId &&
                it.response == ReviewResponse.UNAVAILABLE &&
                it.clientCreatedAt.atZone(zoneId).toLocalDate() == today
        }
    }

    fun forgottenCountToday(cardId: String, zoneId: ZoneId, now: Instant): Int {
        val today = now.atZone(zoneId).toLocalDate()
        return events().count {
            it.cardId == cardId &&
                it.response == ReviewResponse.FORGOTTEN &&
                it.clientCreatedAt.atZone(zoneId).toLocalDate() == today
        }
    }

    fun weakWordIds(): Set<String> {
        return cards().filter { card ->
            val cardEvents = events().filter { it.cardId == card.id && it.response in setOf(ReviewResponse.REMEMBERED, ReviewResponse.FORGOTTEN) }
            val forgotten = cardEvents.count { it.response == ReviewResponse.FORGOTTEN }
            card.lapseCount >= 3 || (cardEvents.size >= 4 && forgotten.toDouble() / cardEvents.size >= 0.5)
        }.map { it.id }.toSet()
    }

    fun exportSyncBatchJson(): String {
        return SyncPayloadFactory.build(
            cards = allCards(includeDeleted = true),
            contexts = contexts(),
            definitionCandidates = definitionCandidates(),
            reviewEvents = events(),
            recommendationFeedback = recommendationFeedback(),
            settings = settings(),
            deviceId = deviceId()
        ).toString()
    }

    fun importSyncBatchJson(raw: String) = synchronized(CARD_MUTATION_LOCK) {
        val payload = SyncPayloadFactory.parse(raw)
        val canonicalCards = DuplicateCardMerger.canonicalize(mergeCards(allCards(includeDeleted = true), payload.cards))
        commitStoreTransaction(
            StoreTransaction(
                cards = canonicalCards.cards,
                contexts = mergeById(contexts(), payload.contexts) { it.id }
                    .map { it.remapCardId(canonicalCards.cardIdMap) },
                definitionCandidates = mergeById(
                    definitionCandidates(),
                    payload.definitionCandidates
                ) { it.id }.map { it.remapCardId(canonicalCards.cardIdMap) },
                events = mergeById(events(), payload.reviewEvents) { it.id }
                    .map { it.remapCardId(canonicalCards.cardIdMap) },
                recommendationFeedback = mergeById(
                    recommendationFeedback(),
                    payload.recommendationFeedback
                ) { it.id }.map { it.remapCardId(canonicalCards.cardIdMap) },
                dictionaryEnrichmentStates = remapDictionaryEnrichmentStates(
                    canonicalCards.cardIdMap
                ),
                settings = payload.settings?.let(::mergedSettingsOrNull)
            )
        )
    }

    fun syncWithCloud(client: CloudSyncClient): CloudSyncResult {
        val localBatch = SyncPayloadFactory.build(
            cards = allCards(includeDeleted = true),
            contexts = contexts(),
            definitionCandidates = definitionCandidates(),
            reviewEvents = events(),
            recommendationFeedback = recommendationFeedback(),
            settings = settings(),
            deviceId = deviceId()
        )
        val sameEndpoint = prefs.getString("sync_cursor_endpoint", null) == client.cursorEndpoint
        val acknowledged = if (sameEndpoint && prefs.getString("sync_cursor", null) != null) runCatching { JSONObject(prefs.getString("sync_upload_fingerprints", "{}")!!) }.getOrDefault(JSONObject()) else JSONObject()
        var pushResult = client.push(com.vocabreminder.android.sync.SyncUploadState.pending(localBatch, acknowledged))
        val cursor = if (sameEndpoint) prefs.getString("sync_cursor", null) else null
        var snapshot = client.pullSnapshot(cursor)
        if (snapshot.optBoolean("sync_reset") && acknowledged.length() > 0) {
            pushResult = client.push(localBatch)
            snapshot = client.pullSnapshot()
        }
        val rawSnapshot = snapshot.toString()
        importSyncBatchJson(rawSnapshot)
        // Advance only after the import commits; interrupted commits replay safely.
        val editor = prefs.edit().putString("sync_cursor_endpoint", client.cursorEndpoint)
        if (snapshot.has("sync_cursor")) editor.putString("sync_cursor", snapshot.getString("sync_cursor"))
        else editor.remove("sync_cursor")
        editor.putString("sync_upload_fingerprints", if (skippedSummary(pushResult).isBlank()) com.vocabreminder.android.sync.SyncUploadState.fingerprints(localBatch).toString() else "{}")
        check(editor.commit()) { "동기화 진행 상태를 저장하지 못했습니다." }
        return CloudSyncResult(
            snapshotJson = rawSnapshot,
            skippedSummary = skippedSummary(pushResult),
            requestId = client.lastRequestId
        )
    }

    private fun buildContext(
        cardId: String,
        result: DictionaryResult,
        captureMethod: CaptureMethod,
        now: Instant
    ): StoredContext? {
        val sourceSentence = result.sourceSentence.ifBlank { result.exampleSentence }
        if (sourceSentence.isBlank() &&
            result.sourceApp.isBlank() &&
            result.sourceUrl.isBlank() &&
            result.sourceTitle.isBlank()
        ) return null
        return StoredContext(
            id = UUID.randomUUID().toString(),
            cardId = cardId,
            sourceSentence = sourceSentence,
            sourceUrl = CaptureProvenanceExtractor.sanitizeUrl(result.sourceUrl),
            sourceApp = result.sourceApp,
            pageTitle = result.sourceTitle.trim().take(300),
            captureMethod = captureMethod,
            createdAt = now
        )
    }

    private fun buildDefinitionCandidate(
        cardId: String,
        result: DictionaryResult,
        selected: Boolean,
        now: Instant,
        originDeviceId: String? = null,
        baseCardVersion: Int? = null
    ): StoredDefinitionCandidate {
        return StoredDefinitionCandidate(
            id = UUID.randomUUID().toString(),
            cardId = cardId,
            source = result.sourceType,
            rawText = result.rawSource,
            parsedMeaningKo = result.meaningKo,
            parsedDefinitionEn = result.definitionEn,
            partOfSpeech = result.partOfSpeech,
            phonetic = result.phonetic,
            exampleSentence = result.exampleSentence,
            confidence = result.confidence,
            selectedAsPrimary = selected,
            createdAt = now,
            originDeviceId = originDeviceId,
            baseCardVersion = baseCardVersion
        )
    }

    private fun buildMeaningCandidateIfNeeded(
        card: VocabularyCard,
        existingCandidates: List<StoredDefinitionCandidate>,
        now: Instant
    ): StoredDefinitionCandidate? {
        if (card.meaningKo.isBlank() && card.definitionEn.isBlank()) return null
        val alreadyPreserved = existingCandidates.any {
            it.cardId == card.id &&
                it.parsedMeaningKo == card.meaningKo &&
                it.parsedDefinitionEn == card.definitionEn
        }
        if (alreadyPreserved) return null

        return buildDefinitionCandidate(
            cardId = card.id,
            result = DictionaryResult(
                lemma = card.lemma,
                displayWord = card.displayWord,
                meaningKo = card.meaningKo,
                definitionEn = card.definitionEn,
                partOfSpeech = card.partOfSpeech,
                phonetic = card.phonetic,
                exampleSentence = card.exampleSentence,
                rawSource = card.meaningKo.ifBlank { card.definitionEn },
                sourceType = card.definitionSourcePrimary,
                confidence = 1.0,
                sourceSentence = card.exampleSentence,
                sourceApp = card.sourceAppPrimary,
                sourceUrl = card.sourceUrlPrimary
            ),
            selected = false,
            now = now
        )
    }

    private fun buildRecommendationFeedback(
        card: VocabularyCard,
        action: String,
        now: Instant
    ): RecommendationFeedback {
        return RecommendationFeedback(
            id = UUID.randomUUID().toString(),
            cardId = card.id,
            lemma = card.lemma,
            action = action,
            createdAt = now
        )
    }

    private data class StoreTransaction(
        val cards: List<VocabularyCard>? = null,
        val contexts: List<StoredContext>? = null,
        val definitionCandidates: List<StoredDefinitionCandidate>? = null,
        val events: List<StoredReviewEvent>? = null,
        val recommendationFeedback: List<RecommendationFeedback>? = null,
        val dictionaryEnrichmentStates: Collection<DictionaryEnrichmentState>? = null,
        val settings: UserSettingsData? = null
    )

    private fun commitStoreTransaction(transaction: StoreTransaction) {
        // Cards and their provenance are separate JSON collections in one preferences file.
        // Stage every affected key on one editor and synchronously persist it so a process
        // death can expose only the old snapshot or the complete new snapshot, never a mix.
        val editor = prefs.edit()
        if (transaction.cards != null || transaction.definitionCandidates != null) {
            val candidates = (transaction.definitionCandidates ?: definitionCandidates()).groupBy { it.cardId }
            val projected = (transaction.cards ?: allCards(includeDeleted = true)).map { card ->
                card.copy(primaryDefinitionCandidateId = DefinitionSelection.primaryId(card, candidates[card.id].orEmpty()))
            }
            editor.putString(KEY_CARDS, cardsJson(projected))
        }
        transaction.contexts?.let { editor.putString(KEY_CONTEXTS, contextsJson(it)) }
        transaction.definitionCandidates?.let {
            editor.putString(KEY_DEFINITION_CANDIDATES, definitionCandidatesJson(it))
        }
        transaction.events?.let { putEvents(editor, it) }
        transaction.recommendationFeedback?.let {
            editor.putString(KEY_RECOMMENDATION_FEEDBACK, recommendationFeedbackJson(it))
        }
        transaction.dictionaryEnrichmentStates?.let {
            editor.putString(KEY_DICTIONARY_ENRICHMENT_STATES, dictionaryEnrichmentStatesJson(it))
        }
        transaction.settings?.let { editor.putString(KEY_SETTINGS, it.toJson().toString()) }

        check(transactionCommitter(editor)) {
            "Could not durably commit vocabulary store transaction"
        }
    }

    private fun cardsJson(cards: List<VocabularyCard>): String {
        val array = JSONArray()
        cards.forEach { array.put(it.toJson()) }
        return array.toString()
    }

    private fun contextsJson(contexts: List<StoredContext>): String {
        val array = JSONArray()
        contexts.forEach { array.put(it.toJson()) }
        return array.toString()
    }

    private fun definitionCandidatesJson(candidates: List<StoredDefinitionCandidate>): String {
        val array = JSONArray()
        candidates.forEach { array.put(it.toJson()) }
        return array.toString()
    }

    private fun persistedDictionaryEnrichmentStates(): Map<String, DictionaryEnrichmentState> {
        val raw = prefs.getString(KEY_DICTIONARY_ENRICHMENT_STATES, "[]").orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            val cardId = json.optString("card_id").trim()
            val status = runCatching {
                DictionaryEnrichmentStatus.valueOf(json.optString("status").uppercase())
            }.getOrNull()
            if (cardId.isBlank() || status == null) return@mapNotNull null
            val failure = runCatching {
                json.optString("failure")
                    .takeIf { it.isNotBlank() }
                    ?.let { DictionaryEnrichmentFailure.valueOf(it.uppercase()) }
            }.getOrNull()
            DictionaryEnrichmentState(
                cardId = cardId,
                status = status,
                failure = failure,
                attemptedAt = json.optNullableInstant("attempted_at"),
                requestId = normalizeRequestId(json.optString("request_id"))
            )
        }.associateBy { it.cardId }
    }

    private fun dictionaryEnrichmentStatesJson(states: Collection<DictionaryEnrichmentState>): String {
        val array = JSONArray()
        states.sortedBy { it.cardId }.forEach { state ->
            array.put(
                JSONObject()
                    .put("card_id", state.cardId)
                    .put("status", state.status.name.lowercase())
                    .put("failure", state.failure?.name?.lowercase() ?: JSONObject.NULL)
                    .put("attempted_at", state.attemptedAt?.toString() ?: JSONObject.NULL)
                    .put("request_id", state.requestId ?: JSONObject.NULL)
            )
        }
        return array.toString()
    }

    private fun dictionaryStateForCard(
        card: VocabularyCard,
        timestamp: Instant,
        requestId: String? = null
    ): DictionaryEnrichmentState {
        return when {
            card.definitionSourcePrimary == "user" -> DictionaryEnrichmentState(
                cardId = card.id,
                status = DictionaryEnrichmentStatus.USER_LOCKED,
                attemptedAt = timestamp,
                requestId = normalizeRequestId(requestId)
            )
            card.meaningKo.isBlank() -> DictionaryEnrichmentState(
                cardId = card.id,
                status = DictionaryEnrichmentStatus.PENDING,
                requestId = normalizeRequestId(requestId)
            )
            else -> DictionaryEnrichmentState(
                cardId = card.id,
                status = DictionaryEnrichmentStatus.RESOLVED,
                attemptedAt = timestamp,
                requestId = normalizeRequestId(requestId)
            )
        }
    }

    private fun dictionaryStatesReplacing(state: DictionaryEnrichmentState): Collection<DictionaryEnrichmentState> {
        return persistedDictionaryEnrichmentStates().toMutableMap().apply {
            this[state.cardId] = state
        }.values
    }

    private fun enqueueDictionaryEnrichmentSafely() {
        try {
            DictionaryEnrichmentWorker.enqueue(applicationContext)
        } catch (_: IllegalStateException) {
            // WorkManager is unavailable only during very early startup or isolated unit tests.
            // The persisted pending state is picked up by the next app/sync scheduling pass.
        }
    }

    private fun putEvents(editor: SharedPreferences.Editor, events: List<StoredReviewEvent>) {
        val (active, archived) = pruneEventsForArchive(events)
        val array = JSONArray()
        active.forEach { array.put(it.toJson()) }
        editor.putString(KEY_EVENTS, array.toString())
        if (archived.isNotEmpty()) {
            val mergedArchive = (archivedEvents() + archived)
                .associateBy { it.id }
                .values
                .sortedBy { it.clientCreatedAt }
            val archiveArray = JSONArray()
            mergedArchive.forEach { archiveArray.put(it.toJson()) }
            editor.putString(KEY_EVENTS_ARCHIVE, archiveArray.toString())
        }
    }

    fun archivedEvents(): List<StoredReviewEvent> {
        val array = JSONArray(prefs.getString(KEY_EVENTS_ARCHIVE, "[]"))
        return (0 until array.length()).map { parseEvent(array.getJSONObject(it)) }
            .sortedBy { it.clientCreatedAt }
    }

    private fun recommendationFeedbackJson(feedback: List<RecommendationFeedback>): String {
        val array = JSONArray()
        feedback.forEach { array.put(it.toJson()) }
        return array.toString()
    }

    private fun saveSettings(settings: UserSettingsData, preserveUpdatedAt: Boolean) {
        val storedSettings = if (preserveUpdatedAt) settings else settings.copy(updatedAt = Instant.now())
        prefs.edit().putString(KEY_SETTINGS, storedSettings.toJson().toString()).apply()
    }

    private fun mergedSettingsOrNull(incoming: UserSettingsData): UserSettingsData? {
        val local = settings()
        return if (
            incoming.updatedAt > local.updatedAt ||
            (incoming.updatedAt == Instant.EPOCH && local.updatedAt == Instant.EPOCH)
        ) {
            // enableWatchReplyReview is device-local (the paired watch belongs to this
            // phone); sync payloads never carry it, so keep the local value on merge.
            incoming.copy(enableWatchReplyReview = local.enableWatchReplyReview)
        } else {
            null
        }
    }

    fun deviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null && DEVICE_ID_PATTERN.matches(existing)) return existing
        val generated = "android-${UUID.randomUUID().toString().take(8)}"
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    private fun mergeCards(local: List<VocabularyCard>, incoming: List<VocabularyCard>): List<VocabularyCard> {
        val byId = local.associateBy { it.id }.toMutableMap()
        for (card in incoming) {
            val existing = byId[card.id]
            byId[card.id] = when {
                existing == null -> card
                card.version > existing.version -> card
                card.version < existing.version -> existing
                card.updatedAt > existing.updatedAt -> card
                card.updatedAt < existing.updatedAt -> existing
                card.deletedAt != null && existing.deletedAt == null -> card
                card.deletedAt == null && existing.deletedAt != null -> existing
                card.deletedAt != null && existing.deletedAt != null && card.deletedAt > existing.deletedAt -> card
                card.deletedAt != null && existing.deletedAt != null && card.deletedAt < existing.deletedAt -> existing
                card.stableMergeKey() > existing.stableMergeKey() -> card
                else -> existing
            }
        }
        return byId.values.toList()
    }

    private fun VocabularyCard.stableMergeKey(): String {
        return listOf(
            lemma,
            displayWord,
            meaningKo,
            definitionEn,
            partOfSpeech,
            exampleSentence,
            definitionSourcePrimary,
            status.name.lowercase(),
            wordbook.name.lowercase(),
            dueAt.toEpochMilli().toString(),
            intervalDays.toString(),
            lapseCount.toString(),
            intervalBeforeLapse?.toString().orEmpty(),
            recommendationStartedAt?.toEpochMilli()?.toString().orEmpty(),
            createdAt.toEpochMilli().toString()
        ).joinToString(separator = "\u001F")
    }

    private fun StoredContext.remapCardId(idMap: Map<String, String>): StoredContext {
        return idMap[cardId]?.let { copy(cardId = it) } ?: this
    }

    private fun StoredDefinitionCandidate.remapCardId(idMap: Map<String, String>): StoredDefinitionCandidate {
        return idMap[cardId]?.let { copy(cardId = it) } ?: this
    }

    private fun StoredReviewEvent.remapCardId(idMap: Map<String, String>): StoredReviewEvent {
        return idMap[cardId]?.let { copy(cardId = it) } ?: this
    }

    private fun RecommendationFeedback.remapCardId(idMap: Map<String, String>): RecommendationFeedback {
        return idMap[cardId]?.let { copy(cardId = it) } ?: this
    }

    private fun remapDictionaryEnrichmentStates(
        idMap: Map<String, String>
    ): Collection<DictionaryEnrichmentState> {
        return persistedDictionaryEnrichmentStates().values
            .map { state -> idMap[state.cardId]?.let { state.copy(cardId = it) } ?: state }
            .groupBy { it.cardId }
            .mapValues { (_, states) ->
                states.maxWith(
                    compareBy<DictionaryEnrichmentState>(
                        { it.attemptedAt ?: Instant.EPOCH },
                        { it.status.name },
                        { it.failure?.name.orEmpty() },
                        { it.requestId.orEmpty() }
                    )
                )
            }
            .values
    }

    private fun <T> mergeById(local: List<T>, incoming: List<T>, idOf: (T) -> String): List<T> {
        val byId = local.associateBy(idOf).toMutableMap()
        incoming.forEach { byId.putIfAbsent(idOf(it), it) }
        return byId.values.toList()
    }

    private fun VocabularyCard.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("lemma", lemma)
            .put("display_word", displayWord)
            .put("meaning_ko", meaningKo)
            .put("definition_en", definitionEn)
            .put("part_of_speech", partOfSpeech)
            .put("phonetic", phonetic)
            .put("example_sentence", exampleSentence)
            .put("source_app_primary", sourceAppPrimary)
            .put("source_url_primary", sourceUrlPrimary)
            .put("definition_source_primary", definitionSourcePrimary)
            .put("primary_definition_candidate_id", primaryDefinitionCandidateId ?: JSONObject.NULL)
            .put("status", status.name)
            .put("wordbook", wordbook.name)
            .put("due_at", dueAt.toString())
            .put("interval_days", intervalDays)
            .put("lapse_count", lapseCount)
            .put("interval_before_lapse", intervalBeforeLapse)
            .put("recommendation_started_at", recommendationStartedAt?.toString())
            .put("deleted_at", deletedAt?.toString())
            .put("notification_id", notificationId)
            .put("created_at", createdAt.toString())
            .put("updated_at", updatedAt.toString())
            .put("version", version)
    }

    private fun StoredContext.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("card_id", cardId)
            .put("source_sentence", sourceSentence)
            .put("source_url", sourceUrl)
            .put("source_app", sourceApp)
            .put("page_title", pageTitle)
            .put("capture_method", captureMethod.wireName)
            .put("created_at", createdAt.toString())
    }

    private fun StoredDefinitionCandidate.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("card_id", cardId)
            .put("source", source)
            .put("raw_text", rawText)
            .put("parsed_meaning_ko", parsedMeaningKo)
            .put("parsed_definition_en", parsedDefinitionEn)
            .put("part_of_speech", partOfSpeech)
            .put("phonetic", phonetic)
            .put("example_sentence", exampleSentence)
            .put("confidence", confidence)
            .put("selected_as_primary", selectedAsPrimary)
            .put("origin_device_id", originDeviceId ?: JSONObject.NULL)
            .put("base_card_version", baseCardVersion ?: JSONObject.NULL)
            .put("created_at", createdAt.toString())
    }

    private fun StoredReviewEvent.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("card_id", cardId)
            .put("response", response.name)
            .put("previous_status", previousStatus.name)
            .put("next_status", nextStatus.name)
            .put("previous_due_at", previousDueAt.toString())
            .put("next_due_at", nextDueAt.toString())
            .put("previous_interval_days", previousIntervalDays)
            .put("next_interval_days", nextIntervalDays)
            .put("client_created_at", clientCreatedAt.toString())
            .put("metadata", JSONObject(metadata))
    }

    private fun RecommendationFeedback.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("card_id", cardId)
            .put("lemma", lemma)
            .put("action", action)
            .put("created_at", createdAt.toString())
    }

    private fun UserSettingsData.toJson(): JSONObject {
        return JSONObject()
            .put("daily_word_goal", dailyWordGoal)
            .put("max_queue_size", maxQueueSize)
            .put("snooze_duration_minutes", snoozeDurationMinutes)
            .put("quiet_hours_start", quietHoursStart.toString())
            .put("quiet_hours_end", quietHoursEnd.toString())
            .put("timezone", timezone.id)
            .put("enable_word_recommendations", enableWordRecommendations)
            .put("enable_watch_reply_review", enableWatchReplyReview)
            .put("recommendations_introduced_at", recommendationsIntroducedAt?.toString())
            .put("settings_updated_at", updatedAt.toString())
    }

    private fun parseCard(json: JSONObject): VocabularyCard {
        return VocabularyCard(
            id = json.getString("id"),
            lemma = json.getString("lemma"),
            displayWord = json.getString("display_word"),
            meaningKo = json.optString("meaning_ko"),
            definitionEn = json.optString("definition_en"),
            partOfSpeech = json.optString("part_of_speech"),
            phonetic = json.optString("phonetic"),
            exampleSentence = json.optString("example_sentence"),
            sourceAppPrimary = json.optString("source_app_primary"),
            sourceUrlPrimary = CaptureProvenanceExtractor.sanitizeUrl(json.optString("source_url_primary")),
            definitionSourcePrimary = json.optString("definition_source_primary", "user"),
            primaryDefinitionCandidateId = json.optString("primary_definition_candidate_id").takeIf { it.isNotBlank() && it != "null" },
            status = CardStatus.valueOf(json.getString("status")),
            wordbook = Wordbook.valueOf(json.optString("wordbook", Wordbook.USER.name)),
            dueAt = Instant.parse(json.getString("due_at")),
            intervalDays = json.getInt("interval_days"),
            lapseCount = json.getInt("lapse_count"),
            intervalBeforeLapse = json.optInstantInt("interval_before_lapse"),
            recommendationStartedAt = json.optInstant("recommendation_started_at"),
            deletedAt = json.optInstant("deleted_at"),
            notificationId = json.getInt("notification_id"),
            createdAt = Instant.parse(json.getString("created_at")),
            updatedAt = Instant.parse(json.getString("updated_at")),
            version = json.optInt("version", 1)
        )
    }

    private fun parseContext(json: JSONObject): StoredContext {
        return StoredContext(
            id = json.getString("id"),
            cardId = json.getString("card_id"),
            sourceSentence = json.optString("source_sentence"),
            sourceUrl = CaptureProvenanceExtractor.sanitizeUrl(json.optString("source_url")),
            sourceApp = json.optString("source_app"),
            pageTitle = json.optString("page_title").trim().take(300),
            captureMethod = CaptureMethod.values().firstOrNull { it.wireName == json.optString("capture_method") } ?: CaptureMethod.MANUAL,
            createdAt = Instant.parse(json.getString("created_at"))
        )
    }

    private fun parseDefinitionCandidate(json: JSONObject): StoredDefinitionCandidate {
        return StoredDefinitionCandidate(
            id = json.getString("id"),
            cardId = json.getString("card_id"),
            source = json.optString("source"),
            rawText = json.optString("raw_text"),
            parsedMeaningKo = json.optString("parsed_meaning_ko"),
            parsedDefinitionEn = json.optString("parsed_definition_en"),
            partOfSpeech = json.optString("part_of_speech"),
            phonetic = json.optString("phonetic"),
            exampleSentence = json.optString("example_sentence"),
            confidence = json.optDouble("confidence", 0.0),
            selectedAsPrimary = json.optBoolean("selected_as_primary", false),
            createdAt = Instant.parse(json.getString("created_at")),
            originDeviceId = json.optString("origin_device_id")
                .trim()
                .takeIf { it.isNotEmpty() },
            baseCardVersion = json.optInt("base_card_version")
                .takeIf { json.has("base_card_version") && !json.isNull("base_card_version") && it > 0 }
        )
    }

    private fun parseEvent(json: JSONObject): StoredReviewEvent {
        val metadataJson = json.optJSONObject("metadata")
        val metadata = if (metadataJson == null) {
            emptyMap()
        } else {
            metadataJson.keys().asSequence().associateWith { metadataJson.optString(it) }
        }
        return StoredReviewEvent(
            id = json.getString("id"),
            cardId = json.getString("card_id"),
            response = ReviewResponse.valueOf(json.getString("response")),
            previousStatus = CardStatus.valueOf(json.getString("previous_status")),
            nextStatus = CardStatus.valueOf(json.getString("next_status")),
            previousDueAt = Instant.parse(json.getString("previous_due_at")),
            nextDueAt = Instant.parse(json.getString("next_due_at")),
            previousIntervalDays = json.getInt("previous_interval_days"),
            nextIntervalDays = json.getInt("next_interval_days"),
            clientCreatedAt = Instant.parse(json.getString("client_created_at")),
            metadata = metadata
        )
    }

    private fun parseRecommendationFeedback(json: JSONObject): RecommendationFeedback {
        return RecommendationFeedback(
            id = json.getString("id"),
            cardId = json.getString("card_id"),
            lemma = json.getString("lemma"),
            action = json.getString("action"),
            createdAt = Instant.parse(json.getString("created_at"))
        )
    }

    private fun parseSettings(json: JSONObject): UserSettingsData {
        return UserSettingsData(
            dailyWordGoal = json.optInt("daily_word_goal", 10),
            maxQueueSize = json.optInt("max_queue_size", 20),
            snoozeDurationMinutes = json.optLong("snooze_duration_minutes", 60),
            quietHoursStart = parseLocalTimeOrDefault(json.optString("quiet_hours_start", "23:00"), LocalTime.of(23, 0)),
            quietHoursEnd = parseLocalTimeOrDefault(json.optString("quiet_hours_end", "09:00"), LocalTime.of(9, 0)),
            timezone = parseZoneOrDefault(json.optString("timezone", "Asia/Seoul"), ZoneId.of("Asia/Seoul")),
            enableWordRecommendations = json.optBoolean("enable_word_recommendations", true),
            enableWatchReplyReview = json.optBoolean("enable_watch_reply_review", true),
            recommendationsIntroducedAt = json.optInstant("recommendations_introduced_at"),
            updatedAt = json.optInstant("settings_updated_at") ?: Instant.EPOCH
        )
    }

    private fun JSONObject.optInstant(name: String): Instant? {
        if (!has(name) || isNull(name)) return null
        val value = optString(name)
        return if (value.isBlank() || value == "null") null else Instant.parse(value)
    }

    private fun JSONObject.optInstantInt(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        return getInt(name)
    }

    private fun JSONObject.optNullableInstant(name: String): Instant? {
        if (!has(name) || isNull(name)) return null
        val value = optString(name)
        return if (value.isBlank()) null else Instant.parse(value)
    }

    private fun saveCloudSyncStatus(status: CloudSyncStatus) {
        prefs.edit().putString(
            KEY_CLOUD_SYNC_STATUS,
            JSONObject()
                .put("last_success_at", status.lastSuccessAt?.toString() ?: JSONObject.NULL)
                .put("last_failure_at", status.lastFailureAt?.toString() ?: JSONObject.NULL)
                .put("last_error", status.lastError)
                .put("last_skipped_summary", status.lastSkippedSummary)
                .put("last_request_id", status.lastRequestId)
                .put("last_worker_run_at", status.lastWorkerRunAt?.toString() ?: JSONObject.NULL)
                .toString()
        ).apply()
    }

    private fun saveOnboardingState(state: AndroidOnboardingState) {
        prefs.edit().putString(
            KEY_ONBOARDING,
            JSONObject()
                .put("first_launch_at", state.firstLaunchAt.toString())
                .put(
                    "capture_guide_completed_at",
                    state.captureGuideCompletedAt?.toString() ?: JSONObject.NULL
                )
                .toString()
        ).apply()
    }

    private fun skippedSummary(response: JSONObject): String {
        val skipped = response.optJSONObject("skipped") ?: return ""
        val parts = mutableListOf<String>()
        val names = listOf("cards", "contexts", "definition_candidates", "review_events", "recommendation_feedback", "settings")
        for (name in names) {
            val count = skipped.optInt(name, 0)
            if (count > 0) parts += "$name $count"
        }
        val reasonCount = skipped.optJSONArray("reasons")?.length() ?: 0
        if (reasonCount > 0) parts += "reasons $reasonCount"
        return parts.joinToString(", ")
    }

    private fun pruneEventsForArchive(events: List<StoredReviewEvent>): Pair<List<StoredReviewEvent>, List<StoredReviewEvent>> {
        if (events.size <= MAX_ACTIVE_EVENTS) return events to emptyList()
        val lastSuccess = cloudSyncStatus().lastSuccessAt ?: return events to emptyList()
        val archiveCutoff = minOf(lastSuccess, Instant.now().minusSeconds(EVENT_ARCHIVE_SAFETY_WINDOW_SECONDS))
        val sorted = events.sortedBy { it.clientCreatedAt }
        val archiveTarget = events.size - MAX_ACTIVE_EVENTS
        val archivedIds = sorted
            .filter { it.clientCreatedAt.isBefore(archiveCutoff) }
            .take(archiveTarget)
            .map { it.id }
            .toSet()
        if (archivedIds.isEmpty()) return events to emptyList()
        return sorted.filterNot { it.id in archivedIds } to sorted.filter { it.id in archivedIds }
    }

    private fun parseLocalTimeOrDefault(value: String, default: LocalTime): LocalTime {
        return runCatching { LocalTime.parse(value) }.getOrDefault(default)
    }

    private fun parseZoneOrDefault(value: String, default: ZoneId): ZoneId {
        return runCatching { ZoneId.of(value) }.getOrDefault(default)
    }

    private companion object {
        const val KEY_CARDS = "cards"
        const val KEY_CONTEXTS = "contexts"
        const val KEY_DEFINITION_CANDIDATES = "definition_candidates"
        const val KEY_EVENTS = "review_events"
        const val KEY_EVENTS_ARCHIVE = "review_events_archive"
        const val KEY_RECOMMENDATION_FEEDBACK = "recommendation_feedback"
        const val KEY_SETTINGS = "settings"
        const val KEY_CLOUD_SYNC = "cloud_sync"
        const val KEY_CLOUD_SYNC_STATUS = "cloud_sync_status"
        const val KEY_ONBOARDING = "onboarding"
        const val KEY_DICTIONARY_ENRICHMENT_STATES = "dictionary_enrichment_states"
        const val KEY_DEVICE_ID = "device_id"
        const val MAX_ACTIVE_EVENTS = 5_000
        const val MAX_SYNC_ERROR_LENGTH = 200
        const val EVENT_ARCHIVE_SAFETY_WINDOW_SECONDS = 48L * 60L * 60L
        val DEVICE_ID_PATTERN = Regex("^android-[0-9a-fA-F]{8}$")
        val CARD_MUTATION_LOCK = Any()
    }
}

internal data class CanonicalCards(
    val cards: List<VocabularyCard>,
    val cardIdMap: Map<String, String>
)

internal object DuplicateCardMerger {
    fun canonicalize(cards: List<VocabularyCard>): CanonicalCards {
        val idMap = mutableMapOf<String, String>()
        val duplicateIds = mutableSetOf<String>()
        val mergedById = mutableMapOf<String, VocabularyCard>()
        cards.filter { it.deletedAt == null }
            .groupBy { it.lemma.trim().lowercase() }
            .filterKeys { it.isNotBlank() }
            .values
            .filter { it.size > 1 }
            .forEach { duplicates ->
                val canonical = duplicates.maxWith(cardIdentityPriority)
                mergedById[canonical.id] = mergeDuplicateCards(canonical, duplicates)
                duplicates.filterNot { it.id == canonical.id }.forEach { duplicate ->
                    idMap[duplicate.id] = canonical.id
                    duplicateIds += duplicate.id
                }
            }
        return CanonicalCards(
            cards = cards.filterNot { it.id in duplicateIds }.map { mergedById[it.id] ?: it },
            cardIdMap = idMap
        )
    }

    private fun mergeDuplicateCards(canonical: VocabularyCard, duplicates: List<VocabularyCard>): VocabularyCard {
        val progress = duplicates.maxWith(cardProgressPriority)
        val content = duplicates.maxWith(cardContentPriority)
        val shouldFillContent = canonical.meaningKo.isBlank() && content.meaningKo.isNotBlank()
        return canonical.copy(
            meaningKo = if (shouldFillContent) content.meaningKo else canonical.meaningKo,
            definitionEn = if (shouldFillContent) content.definitionEn else canonical.definitionEn,
            partOfSpeech = if (shouldFillContent) content.partOfSpeech else canonical.partOfSpeech,
            phonetic = if (shouldFillContent) content.phonetic else canonical.phonetic,
            exampleSentence = canonical.exampleSentence.ifBlank { content.exampleSentence },
            sourceAppPrimary = canonical.sourceAppPrimary.ifBlank { content.sourceAppPrimary },
            sourceUrlPrimary = canonical.sourceUrlPrimary.ifBlank { content.sourceUrlPrimary },
            definitionSourcePrimary = if (shouldFillContent) content.definitionSourcePrimary else canonical.definitionSourcePrimary,
            status = progress.status,
            dueAt = progress.dueAt,
            intervalDays = progress.intervalDays,
            lapseCount = progress.lapseCount,
            intervalBeforeLapse = progress.intervalBeforeLapse,
            recommendationStartedAt = progress.recommendationStartedAt ?: canonical.recommendationStartedAt,
            wordbook = if (duplicates.any { it.wordbook == Wordbook.USER }) Wordbook.USER else canonical.wordbook,
            updatedAt = duplicates.maxOf { it.updatedAt },
            version = duplicates.maxOf { it.version }
        )
    }

    private val cardIdentityPriority = compareBy<VocabularyCard>(
        { if (it.wordbook == Wordbook.USER) 1 else 0 },
        { if (it.meaningKo.isNotBlank()) 1 else 0 },
        { it.version },
        { it.updatedAt }
    )

    private val cardProgressPriority = compareBy<VocabularyCard>(
        { if (it.recommendationStartedAt != null) 1 else 0 },
        { statusProgressRank(it.status) },
        { it.intervalDays },
        { it.lapseCount },
        { it.version },
        { it.updatedAt }
    )

    private val cardContentPriority = compareBy<VocabularyCard>(
        { if (it.meaningKo.isNotBlank()) 1 else 0 },
        { if (it.definitionSourcePrimary == "user") 1 else 0 },
        { if (it.wordbook == Wordbook.USER) 1 else 0 },
        { it.version },
        { it.updatedAt }
    )

    private fun statusProgressRank(status: CardStatus): Int {
        return when (status) {
            CardStatus.NEW -> 0
            CardStatus.LEARNING -> 1
            CardStatus.RELEARNING -> 2
            CardStatus.REVIEW -> 3
            CardStatus.MASTERED -> 4
            CardStatus.SUSPENDED -> 1
        }
    }
}
