package com.vocabreminder.android.data

import com.vocabreminder.android.sync.SyncPayloadFactory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Runs shared/test-vectors/card_merge.json, the same cases the server and Mac check. */
class CardMergeVectorTest {
    private val vectors = JSONObject(File("../../../shared/test-vectors/card_merge.json").readText())

    @Test
    fun everySharedVectorMergesIdenticallyInBothDirections() {
        val cases = vectors.getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val vector = cases.getJSONObject(index)
            val existing = card(vector.getJSONObject("existing"))
            val incoming = card(vector.getJSONObject("incoming"))
            for ((label, merged) in listOf(
                "existing<-incoming" to CardMerge.merge(existing, incoming),
                "incoming<-existing" to CardMerge.merge(incoming, existing)
            )) {
                val actual = with(SyncPayloadFactory) { merged.toSyncJson("android-test") }
                val expected = vector.getJSONObject("expected")
                for (field in expected.keys()) {
                    assertEquals(
                        "${vector.getString("name")} [$label] $field",
                        normalize(expected.get(field)),
                        normalize(actual.opt(field))
                    )
                }
            }
        }
    }

    @Test
    fun localWritesAdvanceOnlyTheGroupThatChanged() {
        val base = card(vectors.getJSONArray("cases").getJSONObject(0).getJSONObject("incoming"))
        val reviewed = CardMerge.stamp(base, base.copy(intervalDays = base.intervalDays + 3, updatedAt = base.updatedAt.plusSeconds(60)))
        assertEquals(base.contentVersion, reviewed.contentVersion)
        assertEquals(base.scheduleVersion + 1, reviewed.scheduleVersion)

        val edited = CardMerge.stamp(base, base.copy(meaningKo = "새 뜻", updatedAt = base.updatedAt.plusSeconds(60)))
        assertEquals(base.contentVersion + 1, edited.contentVersion)
        assertEquals(base.scheduleVersion, edited.scheduleVersion)
        assertEquals(base.updatedAt.plusSeconds(60), edited.contentUpdatedAt)
    }

    private fun card(json: JSONObject) = with(SyncPayloadFactory) { json.toCard() }

    // Instants are compared as instants; JSON null and absent are the same.
    private fun normalize(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is String -> runCatching { java.time.Instant.parse(value).toString() }.getOrDefault(value)
        else -> value.toString()
    }
}
