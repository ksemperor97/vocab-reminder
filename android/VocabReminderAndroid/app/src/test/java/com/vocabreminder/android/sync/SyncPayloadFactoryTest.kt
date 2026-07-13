package com.vocabreminder.android.sync

import com.vocabreminder.android.capture.CaptureMethod
import com.vocabreminder.android.data.StoredContext
import com.vocabreminder.android.data.StoredDefinitionCandidate
import com.vocabreminder.android.data.UserSettingsData
import com.vocabreminder.android.data.VocabularyCard
import com.vocabreminder.android.scheduler.CardStatus
import com.vocabreminder.android.scheduler.ReviewResponse
import com.vocabreminder.android.scheduler.Wordbook
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SyncPayloadFactoryTest {
    @Test
    fun exportsSyncShapedLowercasePayload() {
        val now = Instant.parse("2026-07-02T00:00:00Z")
        val payload = SyncPayloadFactory.build(
            cards = listOf(
                VocabularyCard(
                    id = "card-1",
                    lemma = "tentative",
                    displayWord = "tentative",
                    meaningKo = "잠정적인",
                    status = CardStatus.NEW,
                    dueAt = now,
                    intervalDays = 0,
                    lapseCount = 0,
                    notificationId = 1,
                    createdAt = now,
                    updatedAt = now
                )
            ),
            contexts = listOf(
                StoredContext(
                    id = "context-1",
                    cardId = "card-1",
                    sourceSentence = "The results are tentative.",
                    sourceUrl = "",
                    sourceApp = "test",
                    captureMethod = CaptureMethod.ANDROID_PROCESS_TEXT,
                    createdAt = now
                )
            ),
            definitionCandidates = listOf(
                StoredDefinitionCandidate(
                    id = "candidate-1",
                    cardId = "card-1",
                    source = "user",
                    rawText = "잠정적인",
                    parsedMeaningKo = "잠정적인",
                    parsedDefinitionEn = "",
                    partOfSpeech = "",
                    phonetic = "",
                    exampleSentence = "",
                    confidence = 1.0,
                    selectedAsPrimary = true,
                    createdAt = now
                )
            ),
            reviewEvents = emptyList(),
            recommendationFeedback = emptyList(),
            settings = UserSettingsData(recommendationsIntroducedAt = now, updatedAt = now),
            deviceId = "android-1234abcd"
        )

        assertEquals("new", payload.getJSONArray("cards").getJSONObject(0).getString("status"))
        assertEquals("user", payload.getJSONArray("cards").getJSONObject(0).getString("wordbook"))
        assertEquals("android-1234abcd", payload.getJSONArray("cards").getJSONObject(0).getString("created_device_id"))
        assertEquals("android_process_text", payload.getJSONArray("contexts").getJSONObject(0).getString("capture_method"))
        assertEquals("android-1234abcd", payload.getJSONArray("contexts").getJSONObject(0).getString("created_device_id"))
        assertEquals(10, payload.getJSONObject("settings").getInt("daily_word_goal"))
        assertEquals("2026-07-02T00:00:00Z", payload.getJSONObject("settings").getString("recommendations_introduced_at"))
        assertEquals("2026-07-02T00:00:00Z", payload.getJSONObject("settings").getString("settings_updated_at"))
    }

    @Test
    fun parsesSyncShapedSnapshotBackIntoLocalModels() {
        val payload = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("id", "card-1")
                        .put("lemma", "tentative")
                        .put("display_word", "tentative")
                        .put("wordbook", "recommended")
                        .put("meaning_ko", "잠정적인")
                        .put("definition_en", "not certain")
                        .put("status", "new")
                        .put("due_at", "2026-07-02T00:10:00Z")
                        .put("interval_days", 0)
                        .put("lapse_count", 0)
                        .put("created_at", "2026-07-02T00:00:00Z")
                        .put("updated_at", "2026-07-02T00:00:00Z")
                )
            )
            .put(
                "review_events",
                JSONArray().put(
                    JSONObject()
                        .put("id", "event-1")
                        .put("card_id", "card-1")
                        .put("response", "remembered")
                        .put("previous_status", "new")
                        .put("next_status", "learning")
                        .put("previous_due_at", "2026-07-02T00:10:00Z")
                        .put("next_due_at", "2026-07-03T00:10:00Z")
                        .put("previous_interval_days", 0)
                        .put("next_interval_days", 1)
                        .put("client_created_at", "2026-07-02T00:11:00Z")
                )
            )
            .put(
                "settings",
                JSONObject()
                    .put("daily_word_goal", 7)
                    .put("recommendations_introduced_at", "2026-07-02T00:30:00Z")
                    .put("settings_updated_at", "2026-07-02T00:40:00Z")
            )

        val parsed = SyncPayloadFactory.parse(payload)

        assertEquals(CardStatus.NEW, parsed.cards.first().status)
        assertEquals(Wordbook.RECOMMENDED, parsed.cards.first().wordbook)
        assertEquals(ReviewResponse.REMEMBERED, parsed.reviewEvents.first().response)
        assertEquals(7, parsed.settings?.dailyWordGoal)
        assertEquals(Instant.parse("2026-07-02T00:30:00Z"), parsed.settings?.recommendationsIntroducedAt)
        assertEquals(Instant.parse("2026-07-02T00:40:00Z"), parsed.settings?.updatedAt)
    }

    @Test
    fun deviceIdFlowsIntoReviewEvents() {
        val now = Instant.parse("2026-07-02T00:00:00Z")
        val payload = SyncPayloadFactory.build(
            cards = emptyList(),
            contexts = emptyList(),
            definitionCandidates = emptyList(),
            reviewEvents = listOf(
                com.vocabreminder.android.data.StoredReviewEvent(
                    id = "event-1",
                    cardId = "card-1",
                    response = ReviewResponse.REMEMBERED,
                    previousStatus = CardStatus.NEW,
                    nextStatus = CardStatus.REVIEW,
                    previousDueAt = now,
                    nextDueAt = now.plusSeconds(3600),
                    previousIntervalDays = 0,
                    nextIntervalDays = 1,
                    clientCreatedAt = now
                )
            ),
            recommendationFeedback = emptyList(),
            settings = UserSettingsData(updatedAt = now),
            deviceId = "android-1234abcd"
        )

        assertTrue(payload.getJSONArray("review_events").getJSONObject(0).getString("device_id").matches(Regex("^android-[0-9a-f]{8}$")))
        assertEquals("android-1234abcd", payload.getJSONArray("review_events").getJSONObject(0).getString("device_id"))
        assertTrue(!payload.getJSONArray("review_events").getJSONObject(0).has("server_created_at"))
    }

    @Test
    fun recommendationStartedAtRoundTripsThroughCardPayload() {
        val now = Instant.parse("2026-07-02T00:00:00Z")
        val startedAt = now.plusSeconds(60)
        val payload = SyncPayloadFactory.build(
            cards = listOf(
                VocabularyCard(
                    id = "started-recommendation",
                    lemma = "tentative",
                    displayWord = "tentative",
                    meaningKo = "잠정적인",
                    status = CardStatus.LEARNING,
                    wordbook = Wordbook.RECOMMENDED,
                    dueAt = now.plusSeconds(600),
                    intervalDays = 1,
                    lapseCount = 0,
                    recommendationStartedAt = startedAt,
                    notificationId = 42,
                    createdAt = now,
                    updatedAt = startedAt,
                    version = 2
                )
            ),
            contexts = emptyList(),
            definitionCandidates = emptyList(),
            reviewEvents = emptyList(),
            recommendationFeedback = emptyList(),
            settings = UserSettingsData(updatedAt = now),
            deviceId = "android-1234abcd"
        )

        assertEquals(
            startedAt.toString(),
            payload.getJSONArray("cards").getJSONObject(0).getString("recommendation_started_at")
        )
        assertEquals(startedAt, SyncPayloadFactory.parse(payload).cards.single().recommendationStartedAt)
    }
}
