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
