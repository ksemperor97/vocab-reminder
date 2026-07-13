package com.vocabreminder.android.sync

import com.vocabreminder.android.capture.CaptureMethod
import com.vocabreminder.android.data.RecommendationFeedback
import com.vocabreminder.android.data.StoredContext
import com.vocabreminder.android.data.StoredDefinitionCandidate
import com.vocabreminder.android.data.StoredReviewEvent
import com.vocabreminder.android.data.UserSettingsData
import com.vocabreminder.android.data.VocabularyCard
import com.vocabreminder.android.scheduler.CardStatus
import com.vocabreminder.android.scheduler.ReviewResponse
import com.vocabreminder.android.scheduler.Wordbook
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

data class SyncPayload(
    val cards: List<VocabularyCard>,
    val contexts: List<StoredContext>,
    val definitionCandidates: List<StoredDefinitionCandidate>,
    val reviewEvents: List<StoredReviewEvent>,
    val recommendationFeedback: List<RecommendationFeedback>,
    val settings: UserSettingsData?
)

object SyncPayloadFactory {
    fun build(
        cards: List<VocabularyCard>,
        contexts: List<StoredContext>,
        definitionCandidates: List<StoredDefinitionCandidate>,
        reviewEvents: List<StoredReviewEvent>,
        recommendationFeedback: List<RecommendationFeedback>,
        settings: UserSettingsData,
        deviceId: String
    ): JSONObject {
        return JSONObject()
            .put("cards", JSONArray(cards.map { it.toSyncJson(deviceId) }))
            .put("contexts", JSONArray(contexts.map { it.toSyncJson(deviceId) }))
            .put("definition_candidates", JSONArray(definitionCandidates.map { it.toSyncJson() }))
            .put("review_events", JSONArray(reviewEvents.map { it.toSyncJson(deviceId) }))
            .put("recommendation_feedback", JSONArray(recommendationFeedback.map { it.toSyncJson() }))
            .put("settings", settings.toSyncJson())
    }

    fun parse(raw: String): SyncPayload {
        return parse(JSONObject(raw))
    }

    fun parse(json: JSONObject): SyncPayload {
        return SyncPayload(
            cards = json.array("cards").mapObjects { it.toCard() },
            contexts = json.array("contexts").mapObjects { it.toContext() },
            definitionCandidates = json.array("definition_candidates").mapObjects { it.toDefinitionCandidate() },
            reviewEvents = json.array("review_events").mapObjects { it.toReviewEvent() },
            recommendationFeedback = json.array("recommendation_feedback").mapObjects { it.toRecommendationFeedback() },
            settings = json.optJSONObject("settings")?.toSettings()
        )
    }

    private fun VocabularyCard.toSyncJson(deviceId: String): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("lemma", lemma)
            .put("display_word", displayWord)
            .put("language", "en")
            .put("wordbook", wordbook.name.lowercase())
            .put("meaning_ko", meaningKo)
            .put("definition_en", definitionEn)
            .put("part_of_speech", partOfSpeech.ifBlank { JSONObject.NULL })
            .put("phonetic", phonetic.ifBlank { JSONObject.NULL })
            .put("audio_url", JSONObject.NULL)
            .put("source_sentence_primary", exampleSentence.ifBlank { JSONObject.NULL })
            .put("source_url_primary", sourceUrlPrimary.ifBlank { JSONObject.NULL })
            .put("source_app_primary", sourceAppPrimary.ifBlank { JSONObject.NULL })
            .put("definition_source_primary", definitionSourcePrimary)
            .put("status", status.name.lowercase())
            .put("due_at", dueAt.toString())
            .put("interval_days", intervalDays)
            .put("ease", 2.5)
            .put("stability", JSONObject.NULL)
            .put("difficulty", JSONObject.NULL)
            .put("lapse_count", lapseCount)
            .put("interval_before_lapse", intervalBeforeLapse ?: JSONObject.NULL)
            .put("recommendation_started_at", recommendationStartedAt?.toString() ?: JSONObject.NULL)
            .put("deleted_at", deletedAt?.toString() ?: JSONObject.NULL)
            .put("created_device_id", deviceId)
            .put("created_at", createdAt.toString())
            .put("updated_at", updatedAt.toString())
            .put("version", version)
    }

    private fun StoredContext.toSyncJson(deviceId: String): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("card_id", cardId)
            .put("source_sentence", sourceSentence)
            .put("surrounding_text", JSONObject.NULL)
            .put("source_url", sourceUrl.ifBlank { JSONObject.NULL })
            .put("page_title", JSONObject.NULL)
            .put("source_app", sourceApp.ifBlank { JSONObject.NULL })
            .put("capture_method", captureMethod.wireName)
            .put("created_device_id", deviceId)
            .put("created_at", createdAt.toString())
    }

    private fun StoredDefinitionCandidate.toSyncJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("card_id", cardId)
            .put("source", source)
            .put("raw_text", rawText)
            .put("parsed_meaning_ko", parsedMeaningKo.ifBlank { JSONObject.NULL })
            .put("parsed_definition_en", parsedDefinitionEn.ifBlank { JSONObject.NULL })
            .put("part_of_speech", partOfSpeech.ifBlank { JSONObject.NULL })
            .put("phonetic", phonetic.ifBlank { JSONObject.NULL })
            .put("example_sentence", exampleSentence.ifBlank { JSONObject.NULL })
            .put("confidence", confidence)
            .put("selected_as_primary", selectedAsPrimary)
            .put("created_at", createdAt.toString())
    }

    private fun StoredReviewEvent.toSyncJson(deviceId: String): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("card_id", cardId)
            .put("device_id", deviceId)
            .put("response", response.name.lowercase())
            .put("previous_status", previousStatus.name.lowercase())
            .put("next_status", nextStatus.name.lowercase())
            .put("previous_due_at", previousDueAt.toString())
            .put("next_due_at", nextDueAt.toString())
            .put("previous_interval_days", previousIntervalDays)
            .put("next_interval_days", nextIntervalDays)
            .put("client_created_at", clientCreatedAt.toString())
            .put("metadata", JSONObject(metadata))
    }

    private fun RecommendationFeedback.toSyncJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("card_id", cardId)
            .put("lemma", lemma)
            .put("action", action)
            .put("created_at", createdAt.toString())
    }

    private fun UserSettingsData.toSyncJson(): JSONObject {
        return JSONObject()
            .put("max_queue_size", maxQueueSize)
            .put("snooze_duration_minutes", snoozeDurationMinutes)
            .put("daily_word_goal", dailyWordGoal)
            .put("quiet_hours_start", quietHoursStart.toString())
            .put("quiet_hours_end", quietHoursEnd.toString())
            .put("timezone", timezone.id)
            .put("enable_android_notifications", true)
            .put("enable_mac_notifications", true)
            .put("show_meaning_on_lockscreen", false)
            .put("show_meaning_after_remembered", true)
            .put("auto_generate_korean_meaning", true)
            .put("save_source_url", true)
            .put("save_surrounding_text", false)
            .put("enable_word_recommendations", enableWordRecommendations)
            .put("recommendations_introduced_at", recommendationsIntroducedAt?.toString() ?: JSONObject.NULL)
            .put("settings_updated_at", updatedAt.toString())
    }

    private fun JSONObject.toCard(): VocabularyCard {
        val id = string("id")
        val lemma = string("lemma")
        val dueAt = instant("due_at")
        return VocabularyCard(
            id = id,
            lemma = lemma,
            displayWord = string("display_word", lemma),
            meaningKo = string("meaning_ko"),
            definitionEn = string("definition_en"),
            partOfSpeech = string("part_of_speech"),
            phonetic = string("phonetic"),
            exampleSentence = string("source_sentence_primary", string("example_sentence")),
            sourceAppPrimary = string("source_app_primary"),
            sourceUrlPrimary = string("source_url_primary"),
            definitionSourcePrimary = string("definition_source_primary", "unknown"),
            status = enumValue(string("status", CardStatus.NEW.name), CardStatus.NEW),
            wordbook = enumValue(string("wordbook", Wordbook.USER.name), Wordbook.USER),
            dueAt = dueAt,
            intervalDays = optInt("interval_days", 0),
            lapseCount = optInt("lapse_count", 0),
            intervalBeforeLapse = nullableInt("interval_before_lapse"),
            recommendationStartedAt = nullableInstant("recommendation_started_at"),
            deletedAt = nullableInstant("deleted_at"),
            notificationId = optInt("notification_id", id.hashCode() and 0x7fffffff),
            createdAt = nullableInstant("created_at") ?: dueAt,
            updatedAt = nullableInstant("updated_at") ?: dueAt,
            version = optInt("version", 1)
        )
    }

    private fun JSONObject.toContext(): StoredContext {
        return StoredContext(
            id = string("id"),
            cardId = string("card_id"),
            sourceSentence = string("source_sentence"),
            sourceUrl = string("source_url"),
            sourceApp = string("source_app"),
            captureMethod = CaptureMethod.values().firstOrNull { it.wireName == string("capture_method") } ?: CaptureMethod.MANUAL,
            createdAt = instant("created_at")
        )
    }

    private fun JSONObject.toDefinitionCandidate(): StoredDefinitionCandidate {
        return StoredDefinitionCandidate(
            id = string("id"),
            cardId = string("card_id"),
            source = string("source"),
            rawText = string("raw_text"),
            parsedMeaningKo = string("parsed_meaning_ko"),
            parsedDefinitionEn = string("parsed_definition_en"),
            partOfSpeech = string("part_of_speech"),
            phonetic = string("phonetic"),
            exampleSentence = string("example_sentence"),
            confidence = optDouble("confidence", 0.0),
            selectedAsPrimary = optBoolean("selected_as_primary", false),
            createdAt = instant("created_at")
        )
    }

    private fun JSONObject.toReviewEvent(): StoredReviewEvent {
        val metadataJson = optJSONObject("metadata")
        val metadata = metadataJson?.keys()?.asSequence()?.associateWith { metadataJson.optString(it) } ?: emptyMap()
        return StoredReviewEvent(
            id = string("id"),
            cardId = string("card_id"),
            response = enumValue(string("response", ReviewResponse.UNAVAILABLE.name), ReviewResponse.UNAVAILABLE),
            previousStatus = enumValue(string("previous_status", CardStatus.NEW.name), CardStatus.NEW),
            nextStatus = enumValue(string("next_status", CardStatus.NEW.name), CardStatus.NEW),
            previousDueAt = instant("previous_due_at"),
            nextDueAt = instant("next_due_at"),
            previousIntervalDays = optInt("previous_interval_days", 0),
            nextIntervalDays = optInt("next_interval_days", 0),
            clientCreatedAt = instant("client_created_at"),
            metadata = metadata
        )
    }

    private fun JSONObject.toRecommendationFeedback(): RecommendationFeedback {
        return RecommendationFeedback(
            id = string("id"),
            cardId = string("card_id"),
            lemma = string("lemma"),
            action = string("action"),
            createdAt = instant("created_at")
        )
    }

    private fun JSONObject.toSettings(): UserSettingsData {
        return UserSettingsData(
            dailyWordGoal = optInt("daily_word_goal", 10),
            maxQueueSize = optInt("max_queue_size", 20),
            snoozeDurationMinutes = optLong("snooze_duration_minutes", 60),
            quietHoursStart = parseLocalTimeOrDefault(string("quiet_hours_start", "23:00"), LocalTime.of(23, 0)),
            quietHoursEnd = parseLocalTimeOrDefault(string("quiet_hours_end", "09:00"), LocalTime.of(9, 0)),
            timezone = parseZoneOrDefault(string("timezone", "Asia/Seoul"), ZoneId.of("Asia/Seoul")),
            enableWordRecommendations = optBoolean("enable_word_recommendations", true),
            enableWatchReplyReview = optBoolean("enable_watch_reply_review", true),
            recommendationsIntroducedAt = nullableInstant("recommendations_introduced_at"),
            updatedAt = nullableInstant("settings_updated_at") ?: Instant.EPOCH
        )
    }

    private fun JSONObject.array(name: String): JSONArray {
        return optJSONArray(name) ?: JSONArray()
    }

    private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> {
        return (0 until length()).map { transform(getJSONObject(it)) }
    }

    private inline fun <reified T : Enum<T>> enumValue(raw: String, fallback: T): T {
        return enumValues<T>().firstOrNull { it.name == raw.uppercase() } ?: fallback
    }

    private fun JSONObject.string(name: String, fallback: String = ""): String {
        if (!has(name) || isNull(name)) return fallback
        val value = optString(name, fallback)
        return if (value == "null") fallback else value
    }

    private fun JSONObject.instant(name: String): Instant {
        return nullableInstant(name) ?: Instant.EPOCH
    }

    private fun JSONObject.nullableInstant(name: String): Instant? {
        val value = string(name)
        return if (value.isBlank()) null else Instant.parse(value)
    }

    private fun JSONObject.nullableInt(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        return getInt(name)
    }

    private fun parseLocalTimeOrDefault(value: String, default: LocalTime): LocalTime {
        return runCatching { LocalTime.parse(value) }.getOrDefault(default)
    }

    private fun parseZoneOrDefault(value: String, default: ZoneId): ZoneId {
        return runCatching { ZoneId.of(value) }.getOrDefault(default)
    }
}
