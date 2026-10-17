package com.vocabreminder.android.sync

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncChangeAccumulatorTest {
    private fun page(cursor: String, more: Boolean, cards: String = "[]") = JSONObject("""{
        "cards": $cards, "contexts": [], "definition_candidates": [], "review_events": [],
        "recommendation_feedback": [], "settings": null, "sync_cursor": "$cursor", "has_more": $more
    }""")

    @Test fun paginatedChangesKeepLatestVersionAndCursor() {
        val accumulator = SyncChangeAccumulator()
        accumulator.append(page("epoch:1", true, """[{"id":"a","version":1}]"""), "")
        accumulator.append(page("epoch:2", false, """[{"id":"a","version":2}]"""), "epoch:1")
        val result = accumulator.snapshot()
        assertEquals(1, result.getJSONArray("cards").length())
        assertEquals(2, result.getJSONArray("cards").getJSONObject(0).getInt("version"))
        assertEquals("epoch:2", result.getString("sync_cursor"))
    }

    @Test fun nonAdvancingCursorIsRejected() {
        assertThrows(CloudSyncException::class.java) {
            SyncChangeAccumulator().append(page("epoch:1", true), "epoch:1")
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class SyncUploadStateTest {
    @Test fun acknowledgementsSendOnlyChangedRecordsIncludingDeletion() {
        val initial = JSONObject("""{"cards":[{"id":"a","version":1},{"id":"b","version":1}],"settings":{"daily_word_goal":10}}""")
        val acknowledged = SyncUploadState.fingerprints(initial)
        val unchanged = SyncUploadState.pending(initial, acknowledged)
        assertEquals(0, unchanged.getJSONArray("cards").length())
        assertTrue(unchanged.isNull("settings"))
        val changed = JSONObject(initial.toString())
        changed.getJSONArray("cards").getJSONObject(0).put("version", 2).put("deleted_at", "2026-09-08T00:00:00Z")
        val pending = SyncUploadState.pending(changed, acknowledged).getJSONArray("cards")
        assertEquals(1, pending.length())
        assertEquals("a", pending.getJSONObject(0).getString("id"))
        assertTrue(pending.getJSONObject(0).has("deleted_at"))
        assertEquals(2, SyncUploadState.pending(changed, JSONObject()).getJSONArray("cards").length())
    }
}
