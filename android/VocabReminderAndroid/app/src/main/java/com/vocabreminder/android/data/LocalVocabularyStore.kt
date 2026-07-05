package com.vocabreminder.android.data

import android.content.Context
import com.vocabreminder.android.BuildConfig
import com.vocabreminder.android.capture.CaptureMethod
import com.vocabreminder.android.capture.CaptureNormalizer
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
import com.vocabreminder.android.sync.SyncPayloadFactory
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
    val createdAt: Instant
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
    val lastSkippedSummary: String = ""
)

data class CloudSyncResult(
    val snapshotJson: String,
    val skippedSummary: String
)

class LocalVocabularyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("vocab_reminder_store", Context.MODE_PRIVATE)

    fun cards(): List<VocabularyCard> {
        return allCards(includeDeleted = false).sortedBy { it.createdAt }
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
            lastSkippedSummary = json.optString("last_skipped_summary")
        )
    }

    fun recordCloudSyncSuccess(skippedSummary: String = "") {
        saveCloudSyncStatus(
            cloudSyncStatus().copy(
                lastSuccessAt = Instant.now(),
                lastError = "",
                lastSkippedSummary = skippedSummary
            )
        )
    }

    fun recordCloudSyncFailure(error: String) {
        saveCloudSyncStatus(
            cloudSyncStatus().copy(
                lastFailureAt = Instant.now(),
                lastError = error
            )
        )
    }

    fun saveSettings(settings: UserSettingsData) {
        saveSettings(settings, preserveUpdatedAt = false)
    }

    fun markRecommendationsIntroduced(now: Instant) {
        saveSettings(settings().copy(recommendationsIntroducedAt = now))
    }

    fun clearAll() {
        prefs.edit().clear().commit()
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
    ): VocabularyCard {
        val normalizedLemma = result.lemma.ifBlank { CaptureNormalizer.lemma(result.displayWord) }
        val existing = cards().firstOrNull { it.lemma.equals(normalizedLemma, ignoreCase = true) }
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
                sourceUrlPrimary = result.sourceUrl.ifBlank { existing.sourceUrlPrimary },
                definitionSourcePrimary = if (shouldUpdatePrimary) result.sourceType else existing.definitionSourcePrimary,
                wordbook = promotedWordbook,
                updatedAt = now,
                version = existing.version + 1
            )
            updateCard(updated)
            appendContext(updated.id, result, captureMethod, now)
            appendDefinitionCandidate(updated.id, result, selected = shouldUpdatePrimary, now = now)
            return updated
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
            sourceUrlPrimary = result.sourceUrl,
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
        saveCards(allCards(includeDeleted = true) + card)
        appendContext(card.id, result, captureMethod, now)
        appendDefinitionCandidate(card.id, result, selected = true, now = now)
        return card
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

    fun markRecommendationStarted(cardId: String, now: Instant) {
        val card = findCard(cardId) ?: return
        val schedulerSettings = settings().toSchedulerSettings()
        val dueAt = DueTimeJitter.apply(Scheduler.initialDueAt(now, schedulerSettings), now, schedulerSettings)
        updateCard(card.copy(recommendationStartedAt = now, dueAt = dueAt, updatedAt = now, version = card.version + 1))
        appendRecommendationFeedback(card, "started", now)
    }

    fun softDeleteRecommendation(cardId: String, action: String, now: Instant) {
        val card = findCard(cardId) ?: return
        appendRecommendationFeedback(card, action, now)
        updateCard(card.copy(deletedAt = now, updatedAt = now, version = card.version + 1))
    }

    fun softDeleteCard(cardId: String, now: Instant): VocabularyCard? {
        val card = findCard(cardId) ?: return null
        val deleted = card.copy(deletedAt = now, updatedAt = now, version = card.version + 1)
        updateCard(deleted)
        return deleted
    }

    fun updateCard(card: VocabularyCard) {
        saveCards(allCards(includeDeleted = true).map { if (it.id == card.id) card else it })
    }

    fun appendEvent(event: ReviewEventDraft, now: Instant) {
        val stored = StoredReviewEvent(
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
        saveEvents(events() + stored)
    }

    fun recentResponses(cardId: String, limit: Int): List<ReviewResponse> {
        return events()
            .filter { it.cardId == cardId }
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

    fun importSyncBatchJson(raw: String) {
        val payload = SyncPayloadFactory.parse(raw)
        val canonicalCards = DuplicateCardMerger.canonicalize(mergeCards(allCards(includeDeleted = true), payload.cards))
        saveCards(canonicalCards.cards)
        saveContexts(mergeById(contexts(), payload.contexts) { it.id }.map { it.remapCardId(canonicalCards.cardIdMap) })
        saveDefinitionCandidates(mergeById(definitionCandidates(), payload.definitionCandidates) { it.id }.map { it.remapCardId(canonicalCards.cardIdMap) })
        saveEvents(mergeById(events(), payload.reviewEvents) { it.id }.map { it.remapCardId(canonicalCards.cardIdMap) })
        saveRecommendationFeedback(mergeById(recommendationFeedback(), payload.recommendationFeedback) { it.id }.map { it.remapCardId(canonicalCards.cardIdMap) })
        payload.settings?.let { mergeSettings(it) }
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
        val pushResult = client.push(localBatch)
        val snapshot = client.pullSnapshot()
        val rawSnapshot = snapshot.toString()
        importSyncBatchJson(rawSnapshot)
        return CloudSyncResult(
            snapshotJson = rawSnapshot,
            skippedSummary = skippedSummary(pushResult)
        )
    }

    private fun appendContext(cardId: String, result: DictionaryResult, captureMethod: CaptureMethod, now: Instant) {
        val sourceSentence = result.sourceSentence.ifBlank { result.exampleSentence }
        if (sourceSentence.isBlank() && result.sourceApp.isBlank()) return
        val context = StoredContext(
            id = UUID.randomUUID().toString(),
            cardId = cardId,
            sourceSentence = sourceSentence,
            sourceUrl = result.sourceUrl,
            sourceApp = result.sourceApp,
            captureMethod = captureMethod,
            createdAt = now
        )
        saveContexts(contexts() + context)
    }

    private fun appendDefinitionCandidate(cardId: String, result: DictionaryResult, selected: Boolean, now: Instant) {
        val candidate = StoredDefinitionCandidate(
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
            createdAt = now
        )
        saveDefinitionCandidates(definitionCandidates() + candidate)
    }

    private fun appendRecommendationFeedback(card: VocabularyCard, action: String, now: Instant) {
        val feedback = RecommendationFeedback(
            id = UUID.randomUUID().toString(),
            cardId = card.id,
            lemma = card.lemma,
            action = action,
            createdAt = now
        )
        saveRecommendationFeedback(recommendationFeedback() + feedback)
    }

    private fun saveCards(cards: List<VocabularyCard>) {
        val array = JSONArray()
        cards.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_CARDS, array.toString()).apply()
    }

    private fun saveContexts(contexts: List<StoredContext>) {
        val array = JSONArray()
        contexts.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_CONTEXTS, array.toString()).apply()
    }

    private fun saveDefinitionCandidates(candidates: List<StoredDefinitionCandidate>) {
        val array = JSONArray()
        candidates.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_DEFINITION_CANDIDATES, array.toString()).apply()
    }

    private fun saveEvents(events: List<StoredReviewEvent>) {
        val array = JSONArray()
        events.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_EVENTS, array.toString()).apply()
    }

    private fun saveRecommendationFeedback(feedback: List<RecommendationFeedback>) {
        val array = JSONArray()
        feedback.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_RECOMMENDATION_FEEDBACK, array.toString()).apply()
    }

    private fun saveSettings(settings: UserSettingsData, preserveUpdatedAt: Boolean) {
        val storedSettings = if (preserveUpdatedAt) settings else settings.copy(updatedAt = Instant.now())
        prefs.edit().putString(KEY_SETTINGS, storedSettings.toJson().toString()).apply()
    }

    private fun mergeSettings(incoming: UserSettingsData) {
        val local = settings()
        if (incoming.updatedAt > local.updatedAt || (incoming.updatedAt == Instant.EPOCH && local.updatedAt == Instant.EPOCH)) {
            saveSettings(incoming, preserveUpdatedAt = true)
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
            byId[card.id] = if (existing == null || card.updatedAt > existing.updatedAt || card.version > existing.version) {
                card
            } else {
                existing
            }
        }
        return byId.values.toList()
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
            sourceUrlPrimary = json.optString("source_url_primary"),
            definitionSourcePrimary = json.optString("definition_source_primary", "user"),
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
            sourceUrl = json.optString("source_url"),
            sourceApp = json.optString("source_app"),
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
            createdAt = Instant.parse(json.getString("created_at"))
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
            quietHoursStart = LocalTime.parse(json.optString("quiet_hours_start", "23:00")),
            quietHoursEnd = LocalTime.parse(json.optString("quiet_hours_end", "09:00")),
            timezone = ZoneId.of(json.optString("timezone", "Asia/Seoul")),
            enableWordRecommendations = json.optBoolean("enable_word_recommendations", true),
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

    private companion object {
        const val KEY_CARDS = "cards"
        const val KEY_CONTEXTS = "contexts"
        const val KEY_DEFINITION_CANDIDATES = "definition_candidates"
        const val KEY_EVENTS = "review_events"
        const val KEY_RECOMMENDATION_FEEDBACK = "recommendation_feedback"
        const val KEY_SETTINGS = "settings"
        const val KEY_CLOUD_SYNC = "cloud_sync"
        const val KEY_CLOUD_SYNC_STATUS = "cloud_sync_status"
        const val KEY_DEVICE_ID = "device_id"
        val DEVICE_ID_PATTERN = Regex("^android-[0-9a-fA-F]{8}$")
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
