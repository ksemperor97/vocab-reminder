package com.vocabreminder.android.sync

import com.vocabreminder.android.scheduler.Wordbook
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudSyncClientTest {
    @Test
    fun sendsRawBatchAndPullsSnapshot() {
        val snapshot = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("id", "card-remote")
                        .put("lemma", "resilient")
                        .put("display_word", "resilient")
                        .put("meaning_ko", "회복력 있는")
                        .put("wordbook", "recommended")
                        .put("status", "new")
                        .put("due_at", "2026-07-02T00:00:00Z")
                        .put("interval_days", 0)
                        .put("lapse_count", 0)
                        .put("created_at", "2026-07-02T00:00:00Z")
                        .put("updated_at", "2026-07-02T00:00:00Z")
                )
            )
            .put("contexts", JSONArray())
            .put("definition_candidates", JSONArray())
            .put("review_events", JSONArray())
            .put("recommendation_feedback", JSONArray())
            .put("settings", JSONObject().put("daily_word_goal", 8))
        val transport = FakeTransport(
            mutableListOf(
                JSONObject().put("applied", JSONObject().put("cards", 1)),
                snapshot
            )
        )
        val client = CloudSyncClient(
            CloudSyncConfig(
                serverBaseUrl = "https://vocab-sync.example.invalid/",
                bearerToken = "token-123"
            ),
            transport
        )

        val pulled = client.pushAndPull(JSONObject().put("cards", JSONArray()))
        val parsed = SyncPayloadFactory.parse(pulled)

        assertEquals(2, transport.requests.size)
        assertEquals("POST", transport.requests[0].method)
        assertEquals("GET", transport.requests[1].method)
        assertEquals("https://vocab-sync.example.invalid/sync/batch", transport.requests[0].url)
        assertEquals("https://vocab-sync.example.invalid/sync/snapshot", transport.requests[1].url)
        assertEquals("token-123", transport.requests[0].bearerToken)
        assertEquals(0, transport.requests[0].body?.getJSONArray("cards")?.length())
        assertEquals(null, transport.requests[1].body)
        assertEquals(Wordbook.RECOMMENDED, parsed.cards.first().wordbook)
        assertEquals(8, parsed.settings?.dailyWordGoal)
    }

    @Test
    fun throwsReadableErrorForCallableFailure() {
        val transport = FakeTransport(
            mutableListOf(
                JSONObject().put("error", JSONObject().put("message", "Authentication required."))
            )
        )
        val client = CloudSyncClient(
            CloudSyncConfig(
                serverBaseUrl = "https://vocab-sync.example.invalid",
                bearerToken = "token-123"
            ),
            transport
        )

        val error = assertThrows(CloudSyncException::class.java) {
            client.pullSnapshot()
        }

        assertEquals("Authentication required.", error.message)
    }

    @Test
    fun chunksLargeSnapshotsAndAggregatesPushResults() {
        val cards = JSONArray()
        repeat(401) { index -> cards.put(JSONObject().put("id", "card-$index")) }
        val localBatch = JSONObject()
            .put("cards", cards)
            .put("contexts", JSONArray())
            .put("definition_candidates", JSONArray())
            .put("review_events", JSONArray())
            .put("recommendation_feedback", JSONArray())
            .put("settings", JSONObject().put("daily_word_goal", 10))
        val transport = FakeTransport(
            mutableListOf(
                JSONObject()
                    .put("applied", JSONObject().put("cards", 400))
                    .put("skipped", JSONObject().put("cards", 0).put("reasons", JSONArray())),
                JSONObject()
                    .put("applied", JSONObject().put("cards", 1).put("settings", 1))
                    .put("skipped", JSONObject().put("cards", 0).put("reasons", JSONArray()))
            )
        )
        val client = CloudSyncClient(
            CloudSyncConfig("https://vocab-sync.example.invalid", "token-123"),
            transport
        )

        val result = client.push(localBatch)

        assertEquals(2, transport.requests.size)
        assertEquals(400, transport.requests[0].body!!.getJSONArray("cards").length())
        assertEquals(1, transport.requests[1].body!!.getJSONArray("cards").length())
        assertEquals(true, transport.requests[0].body!!.isNull("settings"))
        assertEquals(10, transport.requests[1].body!!.getJSONObject("settings").getInt("daily_word_goal"))
        assertEquals(401, result.getJSONObject("applied").getInt("cards"))
        assertEquals(1, result.getJSONObject("applied").getInt("settings"))
    }

    private data class Request(
        val method: String,
        val url: String,
        val body: JSONObject?,
        val bearerToken: String
    )

    private class FakeTransport(
        private val responses: MutableList<JSONObject>
    ) : CloudSyncTransport {
        val requests = mutableListOf<Request>()

        override fun postJson(url: String, body: JSONObject, bearerToken: String): JSONObject {
            requests += Request(method = "POST", url = url, body = body, bearerToken = bearerToken)
            return responses.removeAt(0)
        }

        override fun getJson(url: String, bearerToken: String): JSONObject {
            requests += Request(method = "GET", url = url, body = null, bearerToken = bearerToken)
            return responses.removeAt(0)
        }
    }
}
