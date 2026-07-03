package com.vocabreminder.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LocalVocabularyStoreSyncTest {
    private lateinit var store: LocalVocabularyStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = LocalVocabularyStore(context)
        store.clearAll()
    }

    @Test
    fun importSyncBatchKeepsNewerLocalSettings() {
        val newer = "2026-07-03T02:00:00Z"
        store.importSyncBatchJson(syncPayload(settings(goal = 11, updatedAt = newer)).toString())

        store.importSyncBatchJson(
            syncPayload(settings(goal = 3, updatedAt = "2026-07-03T01:00:00Z")).toString()
        )

        assertEquals(11, store.settings().dailyWordGoal)
        assertEquals(Instant.parse(newer), store.settings().updatedAt)
    }

    @Test
    fun importSyncBatchAppliesNewerIncomingSettingsWithoutRestamping() {
        val newer = "2026-07-03T03:00:00Z"
        store.importSyncBatchJson(syncPayload(settings(goal = 11, updatedAt = "2026-07-03T02:00:00Z")).toString())
        store.importSyncBatchJson(syncPayload(settings(goal = 12, updatedAt = newer)).toString())

        assertEquals(12, store.settings().dailyWordGoal)
        assertEquals(Instant.parse(newer), store.settings().updatedAt)
    }

    @Test
    fun deviceIdIsPersistentAndUsedInExportedPayload() {
        val first = store.deviceId()
        val second = store.deviceId()

        assertEquals(first, second)
        assertTrue(first.matches(Regex("^android-[0-9a-f]{8}$")))

        store.addCard(
            displayWord = "stable",
            meaningKo = "안정적인",
            exampleSentence = "A stable identifier is useful.",
            dueAt = Instant.parse("2026-07-03T00:00:00Z"),
            now = Instant.parse("2026-07-03T00:00:00Z")
        )
        val exported = JSONObject(store.exportSyncBatchJson())
        assertEquals(first, exported.getJSONArray("cards").getJSONObject(0).getString("created_device_id"))
        assertEquals(first, exported.getJSONArray("contexts").getJSONObject(0).getString("created_device_id"))
        assertEquals(first, store.deviceId())
    }

    private fun syncPayload(settings: JSONObject): JSONObject {
        return JSONObject()
            .put("cards", JSONArray())
            .put("contexts", JSONArray())
            .put("definition_candidates", JSONArray())
            .put("review_events", JSONArray())
            .put("recommendation_feedback", JSONArray())
            .put("settings", settings)
    }

    private fun settings(goal: Int, updatedAt: String): JSONObject {
        return JSONObject()
            .put("daily_word_goal", goal)
            .put("max_queue_size", 20)
            .put("snooze_duration_minutes", 60)
            .put("quiet_hours_start", "23:00")
            .put("quiet_hours_end", "09:00")
            .put("timezone", "Asia/Seoul")
            .put("enable_word_recommendations", true)
            .put("settings_updated_at", updatedAt)
    }
}
