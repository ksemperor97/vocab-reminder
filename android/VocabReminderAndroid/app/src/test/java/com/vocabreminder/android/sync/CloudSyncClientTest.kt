package com.vocabreminder.android.sync

import com.vocabreminder.android.scheduler.Wordbook
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudSyncClientTest {
    @Test
    fun sendsCallableEnvelopeAndPullsSnapshot() {
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
                JSONObject().put("result", JSONObject().put("updated", JSONObject().put("cards", 1))),
                JSONObject().put("result", snapshot)
            )
        )
        val client = CloudSyncClient(
            CloudSyncConfig(
                functionsBaseUrl = "https://us-central1-demo.cloudfunctions.net/",
                bearerToken = "token-123"
            ),
            transport
        )

        val pulled = client.pushAndPull(JSONObject().put("cards", JSONArray()))
        val parsed = SyncPayloadFactory.parse(pulled)

        assertEquals(2, transport.requests.size)
        assertEquals("https://us-central1-demo.cloudfunctions.net/syncBatch", transport.requests[0].url)
        assertEquals("https://us-central1-demo.cloudfunctions.net/syncSnapshot", transport.requests[1].url)
        assertEquals("token-123", transport.requests[0].bearerToken)
        assertEquals(0, transport.requests[0].body.getJSONObject("data").getJSONArray("cards").length())
        assertEquals(0, transport.requests[1].body.getJSONObject("data").length())
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
                functionsBaseUrl = "https://us-central1-demo.cloudfunctions.net",
                bearerToken = "token-123"
            ),
            transport
        )

        val error = assertThrows(CloudSyncException::class.java) {
            client.pullSnapshot()
        }

        assertEquals("Authentication required.", error.message)
    }

    private data class Request(
        val url: String,
        val body: JSONObject,
        val bearerToken: String
    )

    private class FakeTransport(
        private val responses: MutableList<JSONObject>
    ) : CloudSyncTransport {
        val requests = mutableListOf<Request>()

        override fun postJson(url: String, body: JSONObject, bearerToken: String): JSONObject {
            requests += Request(url = url, body = body, bearerToken = bearerToken)
            return responses.removeAt(0)
        }
    }
}
