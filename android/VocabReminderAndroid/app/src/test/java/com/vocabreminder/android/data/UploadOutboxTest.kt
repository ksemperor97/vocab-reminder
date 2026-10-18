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
class UploadOutboxTest {
    private lateinit var store: LocalVocabularyStore
    private val now = Instant.parse("2026-10-01T00:00:00Z")

    @Before
    fun setUp() {
        store = LocalVocabularyStore(ApplicationProvider.getApplicationContext<Context>())
        store.clearAll()
    }

    @Test
    fun onlyUnacknowledgedLocalChangesAreUploaded() {
        val card = store.addCard("outbox", "보낼 편지함", "", now, now)
        val first = store.pendingUpload()
        assertEquals(listOf(card.id), ids(first.payload.getJSONArray("cards")))
        store.acknowledgeUpload(first.digests)
        assertTrue(store.pendingUpload().isEmpty)

        store.softDeleteCard(card.id, now.plusSeconds(60))
        val second = store.pendingUpload()
        assertEquals(listOf(card.id), ids(second.payload.getJSONArray("cards")))
        assertTrue(second.payload.getJSONArray("cards").getJSONObject(0).has("deleted_at"))
    }

    @Test
    fun aRowEditedDuringUploadStaysQueued() {
        val card = store.addCard("race", "경합", "", now, now)
        val inFlight = store.pendingUpload()
        store.updateCardDetails(card.id, "경합 중 수정", "", now.plusSeconds(5))
        store.acknowledgeUpload(inFlight.digests)
        assertEquals(listOf(card.id), ids(store.pendingUpload().payload.getJSONArray("cards")))
    }

    @Test
    fun serverRowsAreNotEchoedBack() {
        store.importSyncBatchJson(
            JSONObject().put("cards", JSONArray().put(
                JSONObject("""{"id":"server-card","lemma":"echo","display_word":"echo","wordbook":"user","meaning_ko":"메아리",
                    "status":"new","due_at":"2026-10-01T00:00:00Z","interval_days":0,"lapse_count":0,
                    "created_at":"2026-10-01T00:00:00Z","updated_at":"2026-10-01T00:00:00Z","version":1}""")
            )).toString()
        )
        assertTrue(store.pendingUpload().isEmpty)
        assertEquals("메아리", store.findCard("server-card")?.meaningKo)
    }

    private fun ids(array: JSONArray) = (0 until array.length()).map { array.getJSONObject(it).getString("id") }
}
