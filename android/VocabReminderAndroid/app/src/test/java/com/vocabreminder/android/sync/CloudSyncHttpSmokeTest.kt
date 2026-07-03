package com.vocabreminder.android.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

class CloudSyncHttpSmokeTest {
    @Test
    fun roundtripsWithLocalNodeSyncServer() {
        val env = System.getenv()
        val serverUrl = env["VOCAB_SYNC_SMOKE_URL"].orEmpty()
        val token = env["VOCAB_SYNC_SMOKE_TOKEN"].orEmpty()
        val required = env["VOCAB_SYNC_SMOKE_REQUIRED"] == "1"
        if (serverUrl.isBlank() || token.isBlank()) {
            if (required) fail("VOCAB_SYNC_SMOKE_URL and VOCAB_SYNC_SMOKE_TOKEN are required.")
            assumeTrue("Cloud sync HTTP smoke env was not provided.", false)
        }

        val client = CloudSyncClient(CloudSyncConfig(serverUrl, token))
        val health = client.health()
        assertEquals(true, health.getBoolean("ok"))
        assertEquals(0, health.getInt("cards"))

        val wrongToken = CloudSyncClient(CloudSyncConfig(serverUrl, "wrong-token"))
        val authError = assertThrows(CloudSyncException::class.java) {
            wrongToken.health()
        }
        assertTrue(authError.message.orEmpty().contains("401"))

        val pushResult = client.push(fullBatch())
        assertEquals(1, pushResult.getJSONObject("applied").getInt("cards"))
        assertEquals(1, pushResult.getJSONObject("applied").getInt("review_events"))
        assertEquals(1, pushResult.getJSONObject("applied").getInt("settings"))

        val snapshot = client.pullSnapshot()
        assertEquals("resilient", snapshot.getJSONArray("cards").getJSONObject(0).getString("lemma"))
        assertEquals(8, snapshot.getJSONObject("settings").getInt("daily_word_goal"))

        val duplicate = client.push(
            JSONObject().put(
                "review_events",
                JSONArray().put(fullBatch().getJSONArray("review_events").getJSONObject(0))
            )
        )
        assertEquals(0, duplicate.getJSONObject("applied").getInt("review_events"))

        val badCard = JSONObject(fullBatch().getJSONArray("cards").getJSONObject(0).toString())
            .put("id", "bad-card")
            .put("due_at", "not-a-date")
        val broken = client.push(JSONObject().put("cards", JSONArray().put(badCard)))
        assertEquals(1, broken.getJSONObject("skipped").getInt("cards"))
        assertTrue(
            broken.getJSONObject("skipped")
                .getJSONArray("reasons")
                .getJSONObject(0)
                .getString("reason")
                .contains("due_at")
        )
    }

    private fun fullBatch(): JSONObject {
        return JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("id", "android-smoke-card")
                        .put("lemma", "resilient")
                        .put("display_word", "resilient")
                        .put("language", "en")
                        .put("wordbook", "user")
                        .put("meaning_ko", "회복력 있는")
                        .put("definition_en", "Able to recover quickly.")
                        .put("part_of_speech", "adjective")
                        .put("source_sentence_primary", "A resilient system recovers quickly.")
                        .put("source_app_primary", "android-smoke")
                        .put("definition_source_primary", "user")
                        .put("status", "new")
                        .put("due_at", "2026-07-03T00:00:00.000Z")
                        .put("interval_days", 0)
                        .put("lapse_count", 0)
                        .put("created_device_id", "android-smoke")
                        .put("created_at", "2026-07-03T00:00:00.000Z")
                        .put("updated_at", "2026-07-03T00:00:00.000Z")
                        .put("version", 1)
                )
            )
            .put(
                "review_events",
                JSONArray().put(
                    JSONObject()
                        .put("id", "android-smoke-event")
                        .put("card_id", "android-smoke-card")
                        .put("device_id", "android-smoke")
                        .put("response", "remembered")
                        .put("previous_status", "new")
                        .put("next_status", "review")
                        .put("previous_due_at", "2026-07-03T00:00:00.000Z")
                        .put("next_due_at", "2026-07-04T00:00:00.000Z")
                        .put("previous_interval_days", 0)
                        .put("next_interval_days", 1)
                        .put("client_created_at", "2026-07-03T00:00:00.000Z")
                        .put("server_created_at", "2026-07-03T00:00:00.000Z")
                        .put("metadata", JSONObject())
                )
            )
            .put(
                "settings",
                JSONObject()
                    .put("max_queue_size", 20)
                    .put("snooze_duration_minutes", 60)
                    .put("daily_word_goal", 8)
                    .put("quiet_hours_start", "23:00")
                    .put("quiet_hours_end", "09:00")
                    .put("timezone", "Asia/Seoul")
                    .put("enable_android_notifications", true)
                    .put("enable_mac_notifications", true)
                    .put("show_meaning_on_lockscreen", false)
                    .put("show_meaning_after_remembered", true)
                    .put("auto_generate_korean_meaning", true)
                    .put("save_source_url", true)
                    .put("save_surrounding_text", false)
                    .put("enable_word_recommendations", true)
                    .put("recommendations_introduced_at", JSONObject.NULL)
            )
    }
}
