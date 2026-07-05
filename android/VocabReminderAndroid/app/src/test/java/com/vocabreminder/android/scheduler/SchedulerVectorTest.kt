package com.vocabreminder.android.scheduler

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

class SchedulerVectorTest {
    private val vectors = JSONArray(readVectors())

    @Test
    fun sharedSchedulerVectorsPass() {
        assertTrue("Expected at least 15 scheduler vectors", vectors.length() >= 15)

        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            when (vector.getString("kind")) {
                "applyReview" -> assertApplyReviewVector(vector)
                "planDay" -> assertPlanDayVector(vector)
                else -> error("Unknown vector kind: ${vector.getString("kind")}")
            }
        }
    }

    @Test
    fun dueTimeJitterSpreadsBaseDueTimes() {
        val now = Instant.parse("2026-07-03T00:00:00Z")
        val settings = SchedulerSettings()
        val baseDueAt = Scheduler.initialDueAt(now, settings)
        val earliest = DueTimeJitter.apply(baseDueAt, now, settings) { it.first }
        val latest = DueTimeJitter.apply(baseDueAt, now, settings) { it.last }

        assertFalse(earliest == latest)
        assertTrue(!earliest.isBefore(now.plusSeconds(60)))

        val result = Scheduler.applyReview(
            card = SchedulerCard(id = "jitter-review", status = CardStatus.NEW, intervalDays = 0, dueAt = now),
            response = ReviewResponse.REMEMBERED,
            now = now
        )
        val jittered = DueTimeJitter.apply(result, now, settings) { it.last }

        assertTrue(jittered.card.dueAt.isAfter(result.card.dueAt))
        assertEquals(jittered.card.dueAt, jittered.event.nextDueAt)
        assertTrue(jittered.event.metadata.containsKey("due_jitter_seconds"))
    }

    private fun assertApplyReviewVector(vector: JSONObject) {
        val now = Instant.parse(vector.getString("now"))
        val settings = parseSettings(vector.optJSONObject("settings"))
        val card = parseCard(vector.getJSONObject("given"), now)
        val response = parseResponse(vector.getString("response"))
        val recentResponses = vector.optJSONArray("recent_responses")?.let { responses ->
            (0 until responses.length()).map { parseResponse(responses.getString(it)) }
        } ?: emptyList()

        val result = Scheduler.applyReview(
            card = card,
            response = response,
            settings = settings,
            now = now,
            recentResponses = recentResponses,
            unavailableCountToday = vector.optInt("unavailable_count_today", 0),
            forgottenCountToday = vector.optInt("forgotten_count_today", 0)
        )

        val then = vector.getJSONObject("then")
        assertEquals(vector.getString("name"), parseStatus(then.getString("status")), result.card.status)
        assertEquals(vector.getString("name"), then.getInt("interval_days"), result.card.intervalDays)

        if (then.has("interval_before_lapse")) {
            assertEquals(vector.getString("name"), then.getInt("interval_before_lapse"), result.card.intervalBeforeLapse)
        }
        if (then.has("lapse_count")) {
            assertEquals(vector.getString("name"), then.getInt("lapse_count"), result.card.lapseCount)
        }
        if (then.has("event_previous_interval_days")) {
            assertEquals(vector.getString("name"), then.getInt("event_previous_interval_days"), result.event.previousIntervalDays)
        }
        if (then.has("due_in_minutes")) {
            assertDurationMinutesInRange(vector.getString("name"), now, result.card.dueAt, then.getJSONArray("due_in_minutes"))
        }
        if (then.has("local_due_time_between")) {
            assertLocalDueTime(vector.getString("name"), result.card.dueAt, settings, then.getJSONArray("local_due_time_between"))
        }
    }

    private fun assertPlanDayVector(vector: JSONObject) {
        val now = Instant.parse(vector.getString("now"))
        val settings = parseSettings(vector.optJSONObject("settings"))
        val cards = vector.getJSONArray("cards").let { cardsJson ->
            (0 until cardsJson.length()).map { parseCard(cardsJson.getJSONObject(it), now) }
        }

        val result = Scheduler.planDay(
            cards = cards,
            settings = settings,
            now = now,
            userWordCount = vector.optInt("user_word_count", 0)
        )

        val then = vector.getJSONObject("then")
        if (then.has("notification_count")) {
            assertEquals(vector.getString("name"), then.getInt("notification_count"), result.notifications.size)
        }
        if (then.has("recommended_count")) {
            assertEquals(vector.getString("name"), then.getInt("recommended_count"), result.notifications.count { it.wordbook == Wordbook.RECOMMENDED })
        }
        if (then.has("rebalanced_count")) {
            assertEquals(vector.getString("name"), then.getInt("rebalanced_count"), result.rebalancedCards.size)
        }
        if (then.has("notification_ids")) {
            assertEquals(vector.getString("name"), readStringList(then.getJSONArray("notification_ids")), result.notifications.map { it.id })
        }
        if (then.has("rebalanced_ids")) {
            assertEquals(vector.getString("name"), readStringList(then.getJSONArray("rebalanced_ids")).sorted(), result.rebalancedCards.map { it.id }.sorted())
        }
        if (then.has("rebalanced_due_in_days")) {
            val range = then.getJSONArray("rebalanced_due_in_days")
            result.rebalancedCards.forEach { card ->
                val days = Duration.between(now, card.dueAt).toHours() / 24
                assertTrue("${vector.getString("name")} ${card.id}", days >= range.getLong(0))
                assertTrue("${vector.getString("name")} ${card.id}", days <= range.getLong(1))
            }
        }
        if (then.optBoolean("unchanged_memory_state", false)) {
            val originalById = cards.associateBy { it.id }
            result.rebalancedCards.forEach { card ->
                val original = originalById.getValue(card.id)
                assertEquals(vector.getString("name"), original.status, card.status)
                assertEquals(vector.getString("name"), original.intervalDays, card.intervalDays)
                assertEquals(vector.getString("name"), original.lapseCount, card.lapseCount)
                assertFalse(vector.getString("name"), card.dueAt == original.dueAt)
            }
        }
    }

    private fun parseCard(json: JSONObject, now: Instant): SchedulerCard {
        return SchedulerCard(
            id = json.getString("id"),
            status = parseStatus(json.getString("status")),
            intervalDays = json.getInt("interval_days"),
            dueAt = json.optString("due_at").takeIf { it.isNotBlank() }?.let(Instant::parse) ?: now,
            lapseCount = json.optInt("lapse_count", 0),
            intervalBeforeLapse = if (json.has("interval_before_lapse")) json.getInt("interval_before_lapse") else null,
            meaningKo = json.optString("meaning_ko", "meaning"),
            wordbook = when (json.optString("wordbook", "user")) {
                "recommended" -> Wordbook.RECOMMENDED
                else -> Wordbook.USER
            },
            recommendationStartedAt = json.optString("recommendation_started_at").takeIf { it.isNotBlank() }?.let(Instant::parse),
            deletedAt = json.optString("deleted_at").takeIf { it.isNotBlank() }?.let(Instant::parse)
        )
    }

    private fun parseSettings(json: JSONObject?): SchedulerSettings {
        if (json == null) return SchedulerSettings()
        return SchedulerSettings(
            dailyWordGoal = json.optInt("daily_word_goal", 10),
            maxQueueSize = json.optInt("max_queue_size", 20),
            snoozeDurationMinutes = json.optLong("snooze_duration_minutes", 60),
            quietHoursStart = parseTime(json.optString("quiet_hours_start", "23:00")),
            quietHoursEnd = parseTime(json.optString("quiet_hours_end", "09:00")),
            timezone = ZoneId.of(json.optString("timezone", "Asia/Seoul")),
            maxUnavailablePerCardPerDay = json.optInt("max_unavailable_per_card_per_day", 2),
            enableWordRecommendations = json.optBoolean("enable_word_recommendations", true)
        )
    }

    private fun parseStatus(status: String): CardStatus {
        return CardStatus.valueOf(status.uppercase())
    }

    private fun parseResponse(response: String): ReviewResponse {
        return ReviewResponse.valueOf(response.uppercase())
    }

    private fun parseTime(value: String): LocalTime {
        val parts = value.split(":")
        return LocalTime.of(parts[0].toInt(), parts[1].toInt())
    }

    private fun assertDurationMinutesInRange(name: String, now: Instant, dueAt: Instant, range: JSONArray) {
        val minutes = Duration.between(now, dueAt).toMinutes()
        assertTrue("$name due minutes $minutes < ${range.getLong(0)}", minutes >= range.getLong(0))
        assertTrue("$name due minutes $minutes > ${range.getLong(1)}", minutes <= range.getLong(1))
    }

    private fun assertLocalDueTime(name: String, dueAt: Instant, settings: SchedulerSettings, range: JSONArray) {
        val localTime = dueAt.atZone(settings.timezone).toLocalTime()
        val start = parseTime(range.getString(0))
        val end = parseTime(range.getString(1))
        assertTrue("$name local due $localTime before $start", !localTime.isBefore(start))
        assertTrue("$name local due $localTime after $end", localTime <= end)
    }

    private fun readStringList(array: JSONArray): List<String> {
        return (0 until array.length()).map { array.getString(it) }
    }

    private fun readVectors(): String {
        val candidates = listOf(
            File("../../../shared/test-vectors/scheduler.json"),
            File("../../shared/test-vectors/scheduler.json"),
            File("../shared/test-vectors/scheduler.json"),
            File("shared/test-vectors/scheduler.json"),
            File(System.getProperty("user.dir"), "../../../shared/test-vectors/scheduler.json"),
            File(System.getProperty("user.dir"), "../../shared/test-vectors/scheduler.json")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Could not find shared/test-vectors/scheduler.json from ${System.getProperty("user.dir")}")
    }
}
