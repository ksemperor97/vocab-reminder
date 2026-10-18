package com.vocabreminder.android.data

import android.content.Context
import com.vocabreminder.android.BuildConfig
import com.vocabreminder.android.capture.CaptureMethod
import com.vocabreminder.android.capture.CaptureNormalizer
import com.vocabreminder.android.capture.CaptureProvenanceExtractor
import com.vocabreminder.android.dictionary.DictionaryResult
import com.vocabreminder.android.recommendation.RecommendationEngine
import com.vocabreminder.android.scheduler.CardStatus
import com.vocabreminder.android.scheduler.DueTimeJitter
import com.vocabreminder.android.scheduler.ReviewEventDraft
import com.vocabreminder.android.scheduler.ReviewResponse
import com.vocabreminder.android.scheduler.Scheduler
import com.vocabreminder.android.scheduler.Wordbook
import com.vocabreminder.android.sync.DictionaryEnrichmentWorker
import com.vocabreminder.android.sync.SyncPayloadFactory
import com.vocabreminder.android.sync.normalizeRequestId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Process-wide revision that UI observes instead of preference listeners. */
object StoreChanges {
    private val mutableRevision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = mutableRevision

    internal fun notifyChanged() = mutableRevision.update { it + 1 }
}

/**
 * The device's replica of the vocabulary: cards, their provenance and history,
 * recommendation feedback and settings, stored row-per-entity in SQLite.
 *
 * Local writes go through [commitLocal], which stamps card field-group clocks,
 * keeps the primary-definition pointer in step with candidates, and queues the
 * changed rows in the upload outbox, all in one transaction. Server data comes
 * in through [importSyncBatchJson] and is merged with [CardMerge]; the server
 * alone folds duplicate lemmas.
 */
class LocalVocabularyStore private constructor(
    context: Context,
    private val beforeCommit: () -> Boolean,
    private val afterCommit: () -> Unit,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit
) {
    constructor(context: Context) : this(context, { true }, {}, Unit)

    /**
     * Test hooks: [beforeCommit] returning false simulates the process dying
     * before the transaction commits; [afterCommit] throwing simulates dying
     * right after it did.
     */
    internal constructor(
        context: Context,
        beforeCommit: () -> Boolean = { true },
        afterCommit: () -> Unit = {}
    ) : this(context, beforeCommit, afterCommit, Unit)

    internal val applicationContext: Context = context.applicationContext
    private val prefs = applicationContext.getSharedPreferences("vocab_reminder_store", Context.MODE_PRIVATE)
    internal val db = VocabularyDatabase.get(applicationContext)

    init {
        synchronized(CARD_MUTATION_LOCK) {
            if (LegacyPreferencesImporter.hasLegacyData(prefs)) LegacyPreferencesImporter.import(prefs, this)
        }
    }

    // ---- Queries ---------------------------------------------------------------------------

    fun cards(): List<VocabularyCard> {
        val excluded = db.strings("SELECT lemma FROM recommendation_feedback WHERE action IN ('known', 'dismissed')")
            .map(RecommendationEngine::normalizedLemma).toSet()
        return allCards(includeDeleted = false).filter {
            it.wordbook != Wordbook.RECOMMENDED || RecommendationEngine.normalizedLemma(it.lemma) !in excluded
        }
    }

    fun allCards(includeDeleted: Boolean = false): List<VocabularyCard> {
        val filter = if (includeDeleted) "" else "WHERE deleted_at IS NULL"
        return db.bodies("SELECT body FROM cards $filter").map(::card).sortedBy { it.createdAt }
    }

    fun findCard(id: String): VocabularyCard? =
        db.bodies("SELECT body FROM cards WHERE id = ? AND deleted_at IS NULL", id).firstOrNull()?.let(::card)

    internal fun storedCard(id: String): VocabularyCard? =
        db.bodies("SELECT body FROM cards WHERE id = ?", id).firstOrNull()?.let(::card)

    fun contexts(): List<StoredContext> =
        db.bodies("SELECT body FROM contexts").map { with(SyncPayloadFactory) { it.toContext() } }.sortedBy { it.createdAt }

    fun definitionCandidates(): List<StoredDefinitionCandidate> =
        db.bodies("SELECT body FROM definition_candidates").map(::candidate).sortedBy { it.createdAt }

    internal fun definitionCandidatesFor(cardId: String): List<StoredDefinitionCandidate> =
        db.bodies("SELECT body FROM definition_candidates WHERE card_id = ?", cardId).map(::candidate).sortedBy { it.createdAt }

    fun events(): List<StoredReviewEvent> =
        db.bodies("SELECT body FROM review_events").map(::event).sortedBy { it.clientCreatedAt }

    internal fun eventsFor(cardId: String): List<StoredReviewEvent> =
        db.bodies("SELECT body FROM review_events WHERE card_id = ?", cardId).map(::event).sortedBy { it.clientCreatedAt }

    fun recommendationFeedback(): List<RecommendationFeedback> =
        db.bodies("SELECT body FROM recommendation_feedback")
            .map { with(SyncPayloadFactory) { it.toRecommendationFeedback() } }
            .sortedBy { it.createdAt }

    fun settings(): UserSettingsData = db.kv(KEY_SETTINGS)?.let(SettingsJson::decode) ?: UserSettingsData()

    fun userWordCount(): Int = cards().count { it.wordbook == Wordbook.USER }

    fun recentResponses(cardId: String, limit: Int): List<ReviewResponse> = eventsFor(cardId)
        .filter { it.response in COUNTED_RESPONSES }
        .takeLast(limit)
        .map { it.response }

    fun unavailableCountToday(cardId: String, zoneId: ZoneId, now: Instant): Int =
        countToday(cardId, ReviewResponse.UNAVAILABLE, zoneId, now)

    fun forgottenCountToday(cardId: String, zoneId: ZoneId, now: Instant): Int =
        countToday(cardId, ReviewResponse.FORGOTTEN, zoneId, now)

    private fun countToday(cardId: String, response: ReviewResponse, zoneId: ZoneId, now: Instant): Int {
        val today = now.atZone(zoneId).toLocalDate()
        return db.strings(
            "SELECT client_created_at FROM review_events WHERE card_id = ? AND response = ?",
            cardId, response.name.lowercase()
        ).count { Instant.parse(it).atZone(zoneId).toLocalDate() == today }
    }

    fun weakWordIds(): Set<String> {
        val outcomes = db.rows("SELECT card_id, response FROM review_events WHERE response IN ('remembered', 'forgotten')")
            .groupBy({ it[0] }, { it[1] })
        return cards().filter { card ->
            val responses = outcomes[card.id].orEmpty()
            val forgotten = responses.count { it == "forgotten" }
            card.lapseCount >= 3 || (responses.size >= 4 && forgotten.toDouble() / responses.size >= 0.5)
        }.map { it.id }.toSet()
    }

    // ---- Device-local preferences ----------------------------------------------------------

    private val pairedServers = PairedServerStore(applicationContext)

    /** The server this device paired with, if any; it wins over any built-in address and token. */
    fun pairedServer(): PairedServer? = pairedServers.load()

    fun savePairedServer(server: PairedServer) = pairedServers.save(server)

    fun clearPairedServer() = pairedServers.clear()

    fun cloudSyncCredentials(): CloudSyncCredentials {
        pairedServers.load()?.let { return CloudSyncCredentials(serverBaseUrl = it.serverBaseUrl, bearerToken = it.token) }
        val defaults = CloudSyncCredentials()
        if (!BuildConfig.DEBUG) return defaults
        val raw = prefs.getString(KEY_CLOUD_SYNC, null) ?: return defaults
        val json = JSONObject(raw)
        return CloudSyncCredentials(
            serverBaseUrl = json.optString("server_base_url", json.optString("functions_base_url", defaults.serverBaseUrl))
                .ifBlank { defaults.serverBaseUrl },
            bearerToken = json.optString("bearer_token").ifBlank { defaults.bearerToken }
        )
    }

    fun saveCloudSyncCredentials(credentials: CloudSyncCredentials) {
        if (!BuildConfig.DEBUG) return
        prefs.edit().putString(
            KEY_CLOUD_SYNC,
            JSONObject().put("server_base_url", credentials.serverBaseUrl).put("bearer_token", credentials.bearerToken).toString()
        ).apply()
    }

    fun cloudSyncStatus(): CloudSyncStatus {
        val raw = prefs.getString(KEY_CLOUD_SYNC_STATUS, null) ?: return CloudSyncStatus()
        val json = JSONObject(raw)
        return CloudSyncStatus(
            lastSuccessAt = json.optInstant("last_success_at"),
            lastFailureAt = json.optInstant("last_failure_at"),
            lastError = json.optString("last_error"),
            lastSkippedSummary = json.optString("last_skipped_summary"),
            lastRequestId = normalizeRequestId(json.optString("last_request_id")).orEmpty(),
            lastWorkerRunAt = json.optInstant("last_worker_run_at")
        )
    }

    fun recordCloudSyncSuccess(skippedSummary: String = "", requestId: String? = null) {
        saveCloudSyncStatus(
            cloudSyncStatus().copy(
                lastSuccessAt = Instant.now(),
                lastError = "",
                lastSkippedSummary = skippedSummary,
                lastRequestId = normalizeRequestId(requestId).orEmpty()
            )
        )
    }

    fun recordCloudSyncFailure(error: String, requestId: String? = null) {
        saveCloudSyncStatus(
            cloudSyncStatus().copy(
                lastFailureAt = Instant.now(),
                lastError = error.take(MAX_SYNC_ERROR_LENGTH),
                lastRequestId = normalizeRequestId(requestId).orEmpty()
            )
        )
    }

    fun recordSyncWorkerHeartbeat(now: Instant = Instant.now()) {
        saveCloudSyncStatus(cloudSyncStatus().copy(lastWorkerRunAt = now))
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
        ).commit()
        StoreChanges.notifyChanged()
    }

    fun onboardingState(now: Instant = Instant.now()): AndroidOnboardingState {
        val parsed = prefs.getString(KEY_ONBOARDING, null)?.let { value ->
            runCatching {
                val json = JSONObject(value)
                AndroidOnboardingState(
                    firstLaunchAt = json.optInstant("first_launch_at") ?: now,
                    captureGuideCompletedAt = json.optInstant("capture_guide_completed_at")
                )
            }.getOrNull()
        }
        return parsed ?: AndroidOnboardingState(firstLaunchAt = now).also(::saveOnboardingState)
    }

    fun completeCaptureGuide(now: Instant = Instant.now()): AndroidOnboardingState =
        onboardingState(now).copy(captureGuideCompletedAt = now).also(::saveOnboardingState)

    private fun saveOnboardingState(state: AndroidOnboardingState) {
        prefs.edit().putString(
            KEY_ONBOARDING,
            JSONObject()
                .put("first_launch_at", state.firstLaunchAt.toString())
                .put("capture_guide_completed_at", state.captureGuideCompletedAt?.toString() ?: JSONObject.NULL)
                .toString()
        ).apply()
    }

    fun deviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null && DEVICE_ID_PATTERN.matches(existing)) return existing
        val generated = "android-${UUID.randomUUID().toString().take(8)}"
        prefs.edit().putString(KEY_DEVICE_ID, generated).commit()
        return generated
    }

    fun clearAll() {
        db.clear()
        prefs.edit().clear().commit()
        StoreChanges.notifyChanged()
    }

    // ---- Settings --------------------------------------------------------------------------

    fun saveSettings(settings: UserSettingsData) {
        commitLocal(LocalChanges(settings = settings.copy(updatedAt = Instant.now())))
    }

    fun markRecommendationsIntroduced(now: Instant) {
        saveSettings(settings().copy(recommendationsIntroducedAt = now))
    }

    // ---- Card mutations --------------------------------------------------------------------

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
        // Capturing a word this device already has adds context to it instead of a
        // second card. (Two devices racing on the same word are folded by the server.)
        val existing = db.bodies(
            "SELECT body FROM cards WHERE lemma = ? AND deleted_at IS NULL", normalizedLemma.trim().lowercase()
        ).firstOrNull()?.let(::card)
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
            val candidate = buildDefinitionCandidate(
                updated.id,
                result,
                selected = shouldUpdatePrimary,
                now = now,
                originDeviceId = deviceId().takeIf { shouldUpdatePrimary },
                baseCardVersion = existing.version.takeIf { shouldUpdatePrimary }
            )
            val state = dictionaryStateForCard(updated, now)
            commitLocal(
                LocalChanges(
                    cards = listOf(updated),
                    contexts = listOfNotNull(buildContext(updated.id, result, captureMethod, now)),
                    candidates = listOf(candidate).filter(::hasDefinitionText),
                    enrichmentStates = listOf(state)
                )
            )
            if (state.status == DictionaryEnrichmentStatus.PENDING) enqueueDictionaryEnrichmentSafely()
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
            updatedAt = now,
            createdDeviceId = deviceId()
        )
        val state = dictionaryStateForCard(card, now)
        commitLocal(
            LocalChanges(
                cards = listOf(card),
                contexts = listOfNotNull(buildContext(card.id, result, captureMethod, now)),
                candidates = listOf(buildDefinitionCandidate(card.id, result, selected = true, now = now)).filter(::hasDefinitionText),
                enrichmentStates = listOf(state)
            )
        )
        if (state.status == DictionaryEnrichmentStatus.PENDING) enqueueDictionaryEnrichmentSafely()
        findCard(card.id) ?: card
    }

    fun addRecommendedCard(result: DictionaryResult, captureMethod: CaptureMethod, now: Instant): VocabularyCard =
        addResolvedCard(result = result, captureMethod = captureMethod, dueAt = now, now = now, wordbook = Wordbook.RECOMMENDED)

    fun markRecommendationStarted(cardId: String, now: Instant) = synchronized(CARD_MUTATION_LOCK) {
        val card = findCard(cardId) ?: return@synchronized
        val schedulerSettings = settings().toSchedulerSettings()
        val dueAt = DueTimeJitter.apply(Scheduler.initialDueAt(now, schedulerSettings), now, schedulerSettings)
        commitLocal(
            LocalChanges(
                cards = listOf(card.copy(recommendationStartedAt = now, dueAt = dueAt, updatedAt = now, version = card.version + 1)),
                feedback = listOf(buildRecommendationFeedback(card, "started", now))
            )
        )
    }

    fun softDeleteRecommendation(cardId: String, action: String, now: Instant) = synchronized(CARD_MUTATION_LOCK) {
        val card = findCard(cardId) ?: return@synchronized
        commitLocal(
            LocalChanges(
                cards = listOf(card.copy(deletedAt = now, updatedAt = now, version = card.version + 1)),
                feedback = listOf(buildRecommendationFeedback(card, action, now))
            )
        )
    }

    fun softDeleteCard(cardId: String, now: Instant): VocabularyCard? {
        val card = findCard(cardId) ?: return null
        val deleted = card.copy(deletedAt = now, updatedAt = now, version = card.version + 1)
        updateCard(deleted)
        return storedCard(cardId) ?: deleted
    }

    fun updateCard(card: VocabularyCard) = synchronized(CARD_MUTATION_LOCK) {
        commitLocal(LocalChanges(cards = listOf(card)))
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
        commitLocal(LocalChanges(cards = updatedCards.toList(), events = eventDrafts.map { storedEvent(it, now) }))
    }

    fun appendEvent(event: ReviewEventDraft, now: Instant) = synchronized(CARD_MUTATION_LOCK) {
        commitLocal(LocalChanges(events = listOf(storedEvent(event, now))))
    }

    fun updateCardDetails(
        cardId: String,
        meaningKo: String,
        exampleSentence: String,
        now: Instant = Instant.now()
    ): CardEditResult? = synchronized(CARD_MUTATION_LOCK) {
        val before = findCard(cardId) ?: return@synchronized null
        val trimmedMeaning = meaningKo.trim()
        val after = before.copy(
            meaningKo = trimmedMeaning,
            exampleSentence = exampleSentence.trim(),
            definitionSourcePrimary = if (trimmedMeaning != before.meaningKo) "user" else before.definitionSourcePrimary,
            updatedAt = now,
            version = before.version + 1
        )
        val candidates = mutableListOf<StoredDefinitionCandidate>()
        buildMeaningCandidateIfNeeded(before, definitionCandidatesFor(before.id), now)?.let(candidates::add)
        if (after.meaningKo.isNotBlank() || after.definitionEn.isNotBlank()) {
            candidates += buildDefinitionCandidate(
                cardId = after.id,
                result = after.asDictionaryResult(sourceType = "user", confidence = 1.0),
                selected = true,
                now = now,
                originDeviceId = deviceId(),
                baseCardVersion = before.version
            )
        }
        val state = dictionaryStateForCard(after, now)
        commitLocal(
            LocalChanges(
                cards = listOf(after),
                candidates = candidates,
                events = listOf(manualEditEvent(before, after, now)),
                enrichmentStates = listOf(state)
            )
        )
        if (state.status == DictionaryEnrichmentStatus.PENDING) enqueueDictionaryEnrichmentSafely()
        CardEditResult(before = before, after = findCard(cardId) ?: after)
    }

    fun selectDefinitionCandidate(
        cardId: String,
        candidateId: String,
        now: Instant = Instant.now()
    ): CardEditResult? = synchronized(CARD_MUTATION_LOCK) {
        val before = findCard(cardId) ?: return@synchronized null
        val existingCandidates = definitionCandidatesFor(cardId)
        val candidate = existingCandidates.firstOrNull { it.id == candidateId } ?: return@synchronized null
        val selectedMeaning = candidate.parsedMeaningKo.trim()
        if (selectedMeaning.isBlank()) return@synchronized null

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
        val candidates = listOfNotNull(buildMeaningCandidateIfNeeded(before, existingCandidates, now)) + StoredDefinitionCandidate(
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
            metadata = mapOf("action" to "select_definition_candidate", "candidate_id" to candidate.id, "source" to candidate.source)
        )
        commitLocal(
            LocalChanges(
                cards = listOf(after),
                candidates = candidates,
                events = listOf(event),
                enrichmentStates = listOf(
                    DictionaryEnrichmentState(cardId = after.id, status = DictionaryEnrichmentStatus.USER_LOCKED, attemptedAt = now)
                )
            )
        )
        CardEditResult(before = before, after = findCard(cardId) ?: after)
    }

    // ---- Sync ------------------------------------------------------------------------------

    fun exportSyncBatchJson(): String = SyncPayloadFactory.build(
        cards = allCards(includeDeleted = true),
        contexts = contexts(),
        definitionCandidates = definitionCandidates(),
        reviewEvents = events(),
        recommendationFeedback = recommendationFeedback(),
        settings = settings(),
        deviceId = deviceId()
    ).toString()

    fun importSyncBatchJson(raw: String) = importSyncBatch(JSONObject(raw))

    /**
     * Applies server (or exported) rows. Cards merge by field group; history rows
     * take the incoming copy, which carries any re-pointing the server did when
     * it folded duplicates. A merged card that differs from what arrived holds
     * local edits the server has not seen, so it stays queued for upload.
     */
    internal fun importSyncBatch(payload: JSONObject) = synchronized(CARD_MUTATION_LOCK) {
        val device = deviceId()
        db.transaction { tx ->
            payload.objects("cards").forEach { raw ->
                val incoming = card(raw)
                val existing = storedCard(incoming.id)
                val merged = existing?.let { CardMerge.merge(it, incoming) } ?: incoming
                if (merged != existing) db.putCard(tx, merged, cardJson(merged, device))
                if (merged != incoming) db.markOutbox(tx, "cards", merged.id)
            }
            payload.objects("contexts").forEach { db.putContext(tx, with(SyncPayloadFactory) { it.toContext() }, it) }
            payload.objects("definition_candidates").forEach { db.putCandidate(tx, candidate(it), it) }
            payload.objects("review_events").forEach { db.putEvent(tx, event(it), it) }
            payload.objects("recommendation_feedback").forEach {
                db.putFeedback(tx, with(SyncPayloadFactory) { it.toRecommendationFeedback() }, it)
            }
            payload.optJSONObject("settings")
                ?.let { with(SyncPayloadFactory) { it.toSettings() } }
                ?.let(::mergedSettingsOrNull)
                ?.let { db.putKv(tx, KEY_SETTINGS, SettingsJson.encode(it)) }
            check(beforeCommit()) { "Could not durably commit vocabulary store transaction" }
        }
        StoreChanges.notifyChanged()
        afterCommit()
    }

    internal data class PendingUpload(val payload: JSONObject, val digests: Map<String, String>) {
        val isEmpty: Boolean get() = digests.isEmpty()
    }

    /** Rows changed on this device since the server last acknowledged them. */
    internal fun pendingUpload(): PendingUpload = synchronized(CARD_MUTATION_LOCK) {
        val payload = JSONObject()
        val digests = linkedMapOf<String, String>()
        VocabularyDatabase.ENTITY_TABLES.forEach { table ->
            val rows = JSONArray()
            db.rows(
                "SELECT t.id, t.body FROM outbox o JOIN $table t ON t.id = o.id WHERE o.collection = ?",
                table
            ).forEach { (id, body) ->
                rows.put(JSONObject(body))
                digests["$table:$id"] = digest(body)
            }
            payload.put(table, rows)
        }
        if (db.strings("SELECT id FROM outbox WHERE collection = 'settings'").isNotEmpty()) {
            val settings = with(SyncPayloadFactory) { settings().toSyncJson() }
            payload.put("settings", settings)
            digests["settings:main"] = digest(SettingsJson.encode(settings()))
        }
        PendingUpload(payload, digests)
    }

    /** Drops outbox rows the server accepted, unless they changed again meanwhile. */
    internal fun acknowledgeUpload(digests: Map<String, String>) = synchronized(CARD_MUTATION_LOCK) {
        db.transaction { tx ->
            digests.forEach { (key, expected) ->
                val (collection, id) = key.split(":", limit = 2)
                val current = if (collection == "settings") {
                    db.kv(KEY_SETTINGS)?.let { digest(SettingsJson.encode(SettingsJson.decode(it))) }
                } else {
                    db.strings("SELECT body FROM $collection WHERE id = ?", id).firstOrNull()?.let(::digest)
                }
                if (current == expected) tx.delete("outbox", "collection = ? AND id = ?", arrayOf(collection, id))
            }
        }
    }

    /** Re-sends everything, e.g. after the server database was restored. */
    internal fun queueEverythingForUpload() = synchronized(CARD_MUTATION_LOCK) {
        db.transaction { tx ->
            VocabularyDatabase.ENTITY_TABLES.forEach { table ->
                tx.execSQL("INSERT OR IGNORE INTO outbox (collection, id) SELECT '$table', id FROM $table")
            }
            if (db.kv(KEY_SETTINGS) != null) db.markOutbox(tx, "settings", "main")
        }
    }

    internal fun syncCursor(endpoint: String): String? =
        if (db.kv(KEY_SYNC_CURSOR_ENDPOINT) == endpoint) db.kv(KEY_SYNC_CURSOR) else null

    internal fun saveSyncCursor(endpoint: String, cursor: String?) = db.transaction { tx ->
        db.putKv(tx, KEY_SYNC_CURSOR_ENDPOINT, endpoint)
        db.putKv(tx, KEY_SYNC_CURSOR, cursor)
    }

    internal fun importLegacy(
        cards: List<VocabularyCard>,
        contexts: List<StoredContext>,
        candidates: List<StoredDefinitionCandidate>,
        events: List<StoredReviewEvent>,
        feedback: List<RecommendationFeedback>,
        settings: UserSettingsData?,
        enrichment: List<DictionaryEnrichmentState>
    ) {
        val device = deviceId()
        db.transaction { tx ->
            cards.forEach { db.putCard(tx, it, cardJson(it, device)) }
            contexts.forEach { db.putContext(tx, it, with(SyncPayloadFactory) { it.toSyncJson(device) }) }
            candidates.forEach { db.putCandidate(tx, it, with(SyncPayloadFactory) { it.toSyncJson() }) }
            events.forEach { db.putEvent(tx, it, with(SyncPayloadFactory) { it.toSyncJson(device) }) }
            feedback.forEach { db.putFeedback(tx, it, with(SyncPayloadFactory) { it.toSyncJson() }) }
            settings?.let { db.putKv(tx, KEY_SETTINGS, SettingsJson.encode(it)) }
            enrichment.forEach { putEnrichmentState(tx, it) }
            db.putKv(tx, KEY_SYNC_CURSOR, null)
        }
        queueEverythingForUpload()
    }

    // ---- Commit ----------------------------------------------------------------------------

    internal data class LocalChanges(
        val cards: List<VocabularyCard> = emptyList(),
        val contexts: List<StoredContext> = emptyList(),
        val candidates: List<StoredDefinitionCandidate> = emptyList(),
        val events: List<StoredReviewEvent> = emptyList(),
        val feedback: List<RecommendationFeedback> = emptyList(),
        val enrichmentStates: List<DictionaryEnrichmentState> = emptyList(),
        val contextAttempts: Map<String, ContextAttempt> = emptyMap(),
        val settings: UserSettingsData? = null
    )

    /** Writes one local change atomically: either every row lands, or none does. */
    internal fun commitLocal(changes: LocalChanges) {
        val device = deviceId()
        db.transaction { tx ->
            changes.candidates.forEach {
                db.putCandidate(tx, it, with(SyncPayloadFactory) { it.toSyncJson() })
                db.markOutbox(tx, "definition_candidates", it.id)
            }
            val touchedCards = changes.cards.associateBy { it.id }.toMutableMap()
            changes.candidates.map { it.cardId }.distinct()
                .filterNot(touchedCards::containsKey)
                .forEach { id -> storedCard(id)?.let { touchedCards[id] = it } }
            touchedCards.values.forEach { card ->
                val previous = storedCard(card.id)
                val projected = card.copy(
                    primaryDefinitionCandidateId = DefinitionSelection.primaryId(card, definitionCandidatesFor(card.id))
                )
                val stamped = CardMerge.stamp(previous, projected)
                if (stamped != previous) {
                    db.putCard(tx, stamped, cardJson(stamped, device))
                    db.markOutbox(tx, "cards", stamped.id)
                }
            }
            changes.contexts.forEach {
                db.putContext(tx, it, with(SyncPayloadFactory) { it.toSyncJson(device) })
                db.markOutbox(tx, "contexts", it.id)
            }
            changes.events.forEach {
                db.putEvent(tx, it, with(SyncPayloadFactory) { it.toSyncJson(device) })
                db.markOutbox(tx, "review_events", it.id)
            }
            changes.feedback.forEach {
                db.putFeedback(tx, it, with(SyncPayloadFactory) { it.toSyncJson() })
                db.markOutbox(tx, "recommendation_feedback", it.id)
            }
            changes.enrichmentStates.forEach { putEnrichmentState(tx, it) }
            if (changes.contextAttempts.isNotEmpty()) {
                db.putKv(tx, KEY_CONTEXT_ATTEMPTS, ContextMeaning.encode(contextAttempts() + changes.contextAttempts))
            }
            changes.settings?.let {
                db.putKv(tx, KEY_SETTINGS, SettingsJson.encode(it))
                db.markOutbox(tx, "settings", "main")
            }
            check(beforeCommit()) { "Could not durably commit vocabulary store transaction" }
        }
        StoreChanges.notifyChanged()
        afterCommit()
    }

    internal fun putEnrichmentState(tx: android.database.sqlite.SQLiteDatabase, state: DictionaryEnrichmentState) {
        tx.insertWithOnConflict(
            "enrichment_states",
            null,
            android.content.ContentValues().apply {
                put("card_id", state.cardId)
                put("body", DictionaryEnrichmentStateJson.encode(state).toString())
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // Device-local bookkeeping, never synced: which card sentences were already sent for a context meaning.
    internal fun contextAttempts(): Map<String, ContextAttempt> = ContextMeaning.decode(db.kv(KEY_CONTEXT_ATTEMPTS))

    internal fun persistedDictionaryEnrichmentStates(): Map<String, DictionaryEnrichmentState> =
        db.bodies("SELECT body FROM enrichment_states").mapNotNull(DictionaryEnrichmentStateJson::decode).associateBy { it.cardId }

    // ---- Builders --------------------------------------------------------------------------

    private fun buildContext(cardId: String, result: DictionaryResult, captureMethod: CaptureMethod, now: Instant): StoredContext? {
        val sourceSentence = result.sourceSentence.ifBlank { result.exampleSentence }
        if (sourceSentence.isBlank() && result.sourceApp.isBlank() && result.sourceUrl.isBlank() && result.sourceTitle.isBlank()) return null
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

    internal fun buildDefinitionCandidate(
        cardId: String,
        result: DictionaryResult,
        selected: Boolean,
        now: Instant,
        originDeviceId: String? = null,
        baseCardVersion: Int? = null
    ) = StoredDefinitionCandidate(
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

    // A capture saved before any meaning is known has nothing to offer as a candidate.
    private fun hasDefinitionText(candidate: StoredDefinitionCandidate) =
        candidate.rawText.isNotBlank() || candidate.parsedMeaningKo.isNotBlank() || candidate.parsedDefinitionEn.isNotBlank()

    internal fun buildMeaningCandidateIfNeeded(
        card: VocabularyCard,
        existingCandidates: List<StoredDefinitionCandidate>,
        now: Instant
    ): StoredDefinitionCandidate? {
        if (card.meaningKo.isBlank() && card.definitionEn.isBlank()) return null
        if (existingCandidates.any { it.parsedMeaningKo == card.meaningKo && it.parsedDefinitionEn == card.definitionEn }) return null
        return buildDefinitionCandidate(
            cardId = card.id,
            result = card.asDictionaryResult(sourceType = card.definitionSourcePrimary, confidence = 1.0),
            selected = false,
            now = now
        )
    }

    private fun VocabularyCard.asDictionaryResult(sourceType: String, confidence: Double) = DictionaryResult(
        lemma = lemma,
        displayWord = displayWord,
        meaningKo = meaningKo,
        definitionEn = definitionEn,
        partOfSpeech = partOfSpeech,
        phonetic = phonetic,
        exampleSentence = exampleSentence,
        rawSource = meaningKo.ifBlank { definitionEn },
        sourceType = sourceType,
        confidence = confidence,
        sourceSentence = exampleSentence,
        sourceApp = sourceAppPrimary,
        sourceUrl = sourceUrlPrimary
    )

    private fun buildRecommendationFeedback(card: VocabularyCard, action: String, now: Instant) = RecommendationFeedback(
        id = UUID.randomUUID().toString(),
        cardId = card.id,
        lemma = card.lemma,
        action = action,
        createdAt = now
    )

    private fun storedEvent(event: ReviewEventDraft, now: Instant) = StoredReviewEvent(
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

    private fun manualEditEvent(before: VocabularyCard, after: VocabularyCard, now: Instant) = StoredReviewEvent(
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

    internal fun dictionaryStateForCard(card: VocabularyCard, timestamp: Instant, requestId: String? = null): DictionaryEnrichmentState =
        when {
            card.definitionSourcePrimary == "user" -> DictionaryEnrichmentState(
                card.id, DictionaryEnrichmentStatus.USER_LOCKED, attemptedAt = timestamp, requestId = normalizeRequestId(requestId)
            )
            card.meaningKo.isBlank() -> DictionaryEnrichmentState(
                card.id, DictionaryEnrichmentStatus.PENDING, requestId = normalizeRequestId(requestId)
            )
            else -> DictionaryEnrichmentState(
                card.id, DictionaryEnrichmentStatus.RESOLVED, attemptedAt = timestamp, requestId = normalizeRequestId(requestId)
            )
        }

    internal fun enqueueDictionaryEnrichmentSafely() {
        try {
            DictionaryEnrichmentWorker.enqueue(applicationContext)
        } catch (_: IllegalStateException) {
            // WorkManager is unavailable only during very early startup or isolated unit tests.
            // The persisted pending state is picked up by the next app/sync scheduling pass.
        }
    }

    private fun mergedSettingsOrNull(incoming: UserSettingsData): UserSettingsData? {
        val local = settings()
        return if (incoming.updatedAt > local.updatedAt || (incoming.updatedAt == Instant.EPOCH && local.updatedAt == Instant.EPOCH)) {
            // enableWatchReplyReview is device-local (the paired watch belongs to this
            // phone); sync payloads never carry it, so keep the local value on merge.
            incoming.copy(enableWatchReplyReview = local.enableWatchReplyReview)
        } else {
            null
        }
    }

    private fun card(json: JSONObject) = with(SyncPayloadFactory) { json.toCard() }
    private fun candidate(json: JSONObject) = with(SyncPayloadFactory) { json.toDefinitionCandidate() }
    private fun event(json: JSONObject) = with(SyncPayloadFactory) { json.toReviewEvent() }
    private fun cardJson(card: VocabularyCard, device: String) = with(SyncPayloadFactory) { card.toSyncJson(device) }

    private fun JSONObject.objects(name: String): List<JSONObject> {
        val array = optJSONArray(name) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    internal companion object {
        private const val KEY_SETTINGS = "settings"
        private const val KEY_CONTEXT_ATTEMPTS = "context_meaning_attempts"
        private const val KEY_SYNC_CURSOR = "sync_cursor"
        private const val KEY_SYNC_CURSOR_ENDPOINT = "sync_cursor_endpoint"
        private const val KEY_CLOUD_SYNC = "cloud_sync"
        private const val KEY_CLOUD_SYNC_STATUS = "cloud_sync_status"
        private const val KEY_ONBOARDING = "onboarding"
        private const val KEY_DEVICE_ID = "device_id"
        private const val MAX_SYNC_ERROR_LENGTH = 200
        private val DEVICE_ID_PATTERN = Regex("^android-[0-9a-fA-F]{8}$")
        private val COUNTED_RESPONSES = setOf(
            ReviewResponse.REMEMBERED, ReviewResponse.FORGOTTEN, ReviewResponse.UNAVAILABLE, ReviewResponse.REVEAL
        )
        val CARD_MUTATION_LOCK = Any()
    }
}
