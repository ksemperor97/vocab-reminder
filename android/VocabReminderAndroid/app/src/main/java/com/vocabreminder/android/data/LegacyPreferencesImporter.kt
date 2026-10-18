package com.vocabreminder.android.data

import android.content.SharedPreferences
import com.vocabreminder.android.capture.CaptureMethod
import com.vocabreminder.android.capture.CaptureProvenanceExtractor
import com.vocabreminder.android.scheduler.CardStatus
import com.vocabreminder.android.scheduler.ReviewResponse
import com.vocabreminder.android.scheduler.Wordbook
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Local (non-wire) JSON for settings, which carry device-only fields. */
internal object SettingsJson {
    fun encode(settings: UserSettingsData): String = JSONObject()
        .put("daily_word_goal", settings.dailyWordGoal)
        .put("max_queue_size", settings.maxQueueSize)
        .put("snooze_duration_minutes", settings.snoozeDurationMinutes)
        .put("quiet_hours_start", settings.quietHoursStart.toString())
        .put("quiet_hours_end", settings.quietHoursEnd.toString())
        .put("timezone", settings.timezone.id)
        .put("enable_word_recommendations", settings.enableWordRecommendations)
        .put("enable_watch_reply_review", settings.enableWatchReplyReview)
        .put("recommendations_introduced_at", settings.recommendationsIntroducedAt?.toString())
        .put("settings_updated_at", settings.updatedAt.toString())
        .toString()

    fun decode(raw: String): UserSettingsData {
        val json = JSONObject(raw)
        return UserSettingsData(
            dailyWordGoal = json.optInt("daily_word_goal", 10),
            maxQueueSize = json.optInt("max_queue_size", 20),
            snoozeDurationMinutes = json.optLong("snooze_duration_minutes", 60),
            quietHoursStart = runCatching { LocalTime.parse(json.optString("quiet_hours_start", "23:00")) }.getOrDefault(LocalTime.of(23, 0)),
            quietHoursEnd = runCatching { LocalTime.parse(json.optString("quiet_hours_end", "09:00")) }.getOrDefault(LocalTime.of(9, 0)),
            timezone = runCatching { ZoneId.of(json.optString("timezone", "Asia/Seoul")) }.getOrDefault(ZoneId.of("Asia/Seoul")),
            enableWordRecommendations = json.optBoolean("enable_word_recommendations", true),
            enableWatchReplyReview = json.optBoolean("enable_watch_reply_review", true),
            recommendationsIntroducedAt = json.optInstant("recommendations_introduced_at"),
            updatedAt = json.optInstant("settings_updated_at") ?: Instant.EPOCH
        )
    }
}

/**
 * One-time move from the 0.3/0.4 SharedPreferences store (whole collections as
 * JSON strings) into [VocabularyDatabase]. Everything imported is queued for
 * upload and the incremental cursor is dropped, so the first sync after the
 * upgrade is a full, idempotent push and pull.
 */
internal object LegacyPreferencesImporter {
    private val legacyKeys = listOf(
        "cards", "contexts", "definition_candidates", "review_events", "review_events_archive",
        "recommendation_feedback", "settings", "dictionary_enrichment_states",
        "sync_cursor", "sync_cursor_endpoint", "sync_upload_fingerprints"
    )

    fun hasLegacyData(prefs: SharedPreferences): Boolean = legacyKeys.any(prefs::contains)

    fun import(prefs: SharedPreferences, store: LocalVocabularyStore) {
        if (!hasLegacyData(prefs)) return
        val cards = prefs.array("cards").map(::parseCard)
        val contexts = prefs.array("contexts").map(::parseContext)
        val candidates = prefs.array("definition_candidates").map(::parseCandidate)
        val events = (prefs.array("review_events_archive") + prefs.array("review_events")).map(::parseEvent)
            .associateBy { it.id }.values.toList()
        val feedback = prefs.array("recommendation_feedback").map(::parseFeedback)
        val settings = prefs.getString("settings", null)?.let(SettingsJson::decode)
        val enrichment = prefs.array("dictionary_enrichment_states").mapNotNull(::parseEnrichmentState)
        store.importLegacy(cards, contexts, candidates, events, feedback, settings, enrichment)
        val editor = prefs.edit()
        legacyKeys.forEach(editor::remove)
        check(editor.commit()) { "Could not clear the legacy preferences store" }
    }

    private fun SharedPreferences.array(key: String): List<JSONObject> {
        val array = runCatching { JSONArray(getString(key, "[]")) }.getOrElse { JSONArray() }
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun parseCard(json: JSONObject): VocabularyCard = VocabularyCard(
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
        intervalBeforeLapse = if (json.has("interval_before_lapse") && !json.isNull("interval_before_lapse")) json.getInt("interval_before_lapse") else null,
        recommendationStartedAt = json.optInstant("recommendation_started_at"),
        deletedAt = json.optInstant("deleted_at"),
        notificationId = json.getInt("notification_id"),
        createdAt = Instant.parse(json.getString("created_at")),
        updatedAt = Instant.parse(json.getString("updated_at")),
        version = json.optInt("version", 1)
    )

    private fun parseContext(json: JSONObject): StoredContext = StoredContext(
        id = json.getString("id"),
        cardId = json.getString("card_id"),
        sourceSentence = json.optString("source_sentence"),
        sourceUrl = CaptureProvenanceExtractor.sanitizeUrl(json.optString("source_url")),
        sourceApp = json.optString("source_app"),
        pageTitle = json.optString("page_title").trim().take(300),
        captureMethod = CaptureMethod.values().firstOrNull { it.wireName == json.optString("capture_method") } ?: CaptureMethod.MANUAL,
        createdAt = Instant.parse(json.getString("created_at"))
    )

    private fun parseCandidate(json: JSONObject): StoredDefinitionCandidate = StoredDefinitionCandidate(
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
        originDeviceId = json.optString("origin_device_id").trim().takeIf { it.isNotEmpty() && it != "null" },
        baseCardVersion = json.optInt("base_card_version")
            .takeIf { json.has("base_card_version") && !json.isNull("base_card_version") && it > 0 }
    )

    private fun parseEvent(json: JSONObject): StoredReviewEvent {
        val metadataJson = json.optJSONObject("metadata")
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
            metadata = metadataJson?.keys()?.asSequence()?.associateWith { metadataJson.optString(it) } ?: emptyMap()
        )
    }

    private fun parseFeedback(json: JSONObject): RecommendationFeedback = RecommendationFeedback(
        id = json.getString("id"),
        cardId = json.getString("card_id"),
        lemma = json.getString("lemma"),
        action = json.getString("action"),
        createdAt = Instant.parse(json.getString("created_at"))
    )

    private fun parseEnrichmentState(json: JSONObject): DictionaryEnrichmentState? =
        DictionaryEnrichmentStateJson.decode(json)
}

internal object DictionaryEnrichmentStateJson {
    fun encode(state: DictionaryEnrichmentState): JSONObject = JSONObject()
        .put("card_id", state.cardId)
        .put("status", state.status.name.lowercase())
        .put("failure", state.failure?.name?.lowercase() ?: JSONObject.NULL)
        .put("attempted_at", state.attemptedAt?.toString() ?: JSONObject.NULL)
        .put("request_id", state.requestId ?: JSONObject.NULL)

    fun decode(json: JSONObject): DictionaryEnrichmentState? {
        val cardId = json.optString("card_id").trim()
        val status = runCatching { DictionaryEnrichmentStatus.valueOf(json.optString("status").uppercase()) }.getOrNull()
        if (cardId.isBlank() || status == null) return null
        val failure = runCatching {
            json.optString("failure").takeIf { it.isNotBlank() && it != "null" }
                ?.let { DictionaryEnrichmentFailure.valueOf(it.uppercase()) }
        }.getOrNull()
        return DictionaryEnrichmentState(
            cardId = cardId,
            status = status,
            failure = failure,
            attemptedAt = json.optInstant("attempted_at"),
            requestId = com.vocabreminder.android.sync.normalizeRequestId(json.optString("request_id").takeIf { it != "null" })
        )
    }
}

internal fun JSONObject.optInstant(name: String): Instant? {
    if (!has(name) || isNull(name)) return null
    val value = optString(name)
    return if (value.isBlank() || value == "null") null else Instant.parse(value)
}
