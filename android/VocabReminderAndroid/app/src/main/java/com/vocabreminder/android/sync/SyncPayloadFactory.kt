package com.vocabreminder.android.sync

import com.vocabreminder.android.data.RecommendationFeedback
import com.vocabreminder.android.data.StoredContext
import com.vocabreminder.android.data.StoredDefinitionCandidate
import com.vocabreminder.android.data.StoredReviewEvent
import com.vocabreminder.android.data.UserSettingsData
import com.vocabreminder.android.data.VocabularyCard
import org.json.JSONArray
import org.json.JSONObject

object SyncPayloadFactory {
    fun build(
        cards: List<VocabularyCard>,
        contexts: List<StoredContext>,
        definitionCandidates: List<StoredDefinitionCandidate>,
        reviewEvents: List<StoredReviewEvent>,
        recommendationFeedback: List<RecommendationFeedback>,
        settings: UserSettingsData
    ): JSONObject {
        return JSONObject()
            .put("cards", JSONArray(cards.map { it.toSyncJson() }))
            .put("contexts", JSONArray(contexts.map { it.toSyncJson() }))
            .put("definition_candidates", JSONArray(definitionCandidates.map { it.toSyncJson() }))
            .put("review_events", JSONArray(reviewEvents.map { it.toSyncJson() }))
            .put("recommendation_feedback", JSONArray(recommendationFeedback.map { it.toSyncJson() }))
            .put("settings", settings.toSyncJson())
    }

    private fun VocabularyCard.toSyncJson(): JSONObject {
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
            .put("deleted_at", deletedAt?.toString() ?: JSONObject.NULL)
            .put("created_device_id", "android-local")
            .put("created_at", createdAt.toString())
            .put("updated_at", updatedAt.toString())
            .put("version", version)
    }

    private fun StoredContext.toSyncJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("card_id", cardId)
            .put("source_sentence", sourceSentence)
            .put("surrounding_text", JSONObject.NULL)
            .put("source_url", sourceUrl.ifBlank { JSONObject.NULL })
            .put("page_title", JSONObject.NULL)
            .put("source_app", sourceApp.ifBlank { JSONObject.NULL })
            .put("capture_method", captureMethod.wireName)
            .put("created_device_id", "android-local")
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

    private fun StoredReviewEvent.toSyncJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("card_id", cardId)
            .put("device_id", "android-local")
            .put("response", response.name.lowercase())
            .put("previous_status", previousStatus.name.lowercase())
            .put("next_status", nextStatus.name.lowercase())
            .put("previous_due_at", previousDueAt.toString())
            .put("next_due_at", nextDueAt.toString())
            .put("previous_interval_days", previousIntervalDays)
            .put("next_interval_days", nextIntervalDays)
            .put("client_created_at", clientCreatedAt.toString())
            .put("server_created_at", clientCreatedAt.toString())
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
    }
}
