package com.vocabreminder.android.sync

import com.vocabreminder.android.dictionary.DictionaryLookupErrorCode
import com.vocabreminder.android.dictionary.DictionaryLookupStatus
import com.vocabreminder.android.scheduler.Wordbook
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class CloudSyncClientTest {
    @Test
    fun looksUpAuthenticatedDictionaryBatchAndParsesBothFieldStyles() {
        val transport = FakeTransport(
            mutableListOf(
                JSONObject().put(
                    "results",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("status", "resolved")
                                .put("lemma", "resilient")
                                .put("meaningKo", "회복력 있는")
                                .put("definitionEn", "able to recover quickly")
                                .put("partOfSpeech", "adjective")
                                .put("exampleSentence", "A resilient system recovers.")
                                .put("sourceType", "mac_dictionary")
                                .put("confidence", 0.78)
                                .put("attemptedAt", "2026-07-13T01:02:03Z")
                                .put("errorCode", JSONObject.NULL)
                        )
                        .put(
                            JSONObject()
                                .put("status", "pending")
                                .put("lemma", "missing")
                                .put("meaning_ko", "")
                                .put("definition_en", "")
                                .put("source_type", "none")
                                .put("error_code", "not_found")
                        )
                )
            )
        )
        val client = CloudSyncClient(
            CloudSyncConfig("https://vocab-sync.example.invalid/", "secret-token"),
            transport
        )

        val results = client.lookupDictionaryBatch(listOf("Resilient", "missing"))

        assertEquals(1, transport.requests.size)
        assertEquals("POST", transport.requests.single().method)
        assertEquals("https://vocab-sync.example.invalid/dict/batch", transport.requests.single().url)
        assertEquals("secret-token", transport.requests.single().bearerToken)
        assertEquals("resilient", transport.requests.single().body!!.getJSONArray("lemmas").getString(0))
        assertEquals(DictionaryLookupStatus.RESOLVED, results[0].status)
        assertEquals("회복력 있는", results[0].meaningKo)
        assertEquals("mac_dictionary", results[0].sourceType)
        assertEquals(DictionaryLookupStatus.PENDING, results[1].status)
        assertEquals(DictionaryLookupErrorCode.NOT_FOUND, results[1].errorCode)
        assertEquals(transport.requests.single().requestId, results[0].requestId)
        assertEquals(transport.requests.single().requestId, results[1].requestId)
    }

    @Test
    fun rejectsMismatchedDictionaryResponseInsteadOfApplyingWrongWord() {
        val transport = FakeTransport(
            mutableListOf(
                JSONObject().put(
                    "results",
                    JSONArray().put(
                        JSONObject()
                            .put("status", "resolved")
                            .put("lemma", "different")
                            .put("meaningKo", "다른 뜻")
                    )
                )
            )
        )
        val client = CloudSyncClient(
            CloudSyncConfig("https://vocab-sync.example.invalid", "secret-token"),
            transport
        )

        assertThrows(CloudSyncException::class.java) {
            client.lookupDictionaryBatch(listOf("expected"))
        }
    }

    @Test
    fun rejectsDictionaryBatchAboveServerSafeCapBeforeSending() {
        val transport = FakeTransport(mutableListOf())
        val client = CloudSyncClient(
            CloudSyncConfig("https://vocab-sync.example.invalid", "secret-token"),
            transport
        )

        assertThrows(IllegalArgumentException::class.java) {
            client.lookupDictionaryBatch(List(CloudSyncClient.MAX_DICTIONARY_ITEMS_PER_BATCH + 1) { "word" })
        }
        assertEquals(0, transport.requests.size)
    }

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
        assertNotEquals(transport.requests[0].requestId, transport.requests[1].requestId)
        assertTrue(transport.requests.all { runCatching { UUID.fromString(it.requestId) }.isSuccess })
        assertEquals(Wordbook.RECOMMENDED, parsed.cards.first().wordbook)
        assertEquals(8, parsed.settings?.dailyWordGoal)
    }

    @Test
    fun throwsReadableErrorForCallableFailure() {
        val responseRequestId = "server-request-123"
        val transport = FakeTransport(
            mutableListOf(
                JSONObject().put("error", JSONObject().put("message", "Authentication required."))
            ),
            mutableListOf(responseRequestId)
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
        assertEquals(responseRequestId, error.requestId)
        assertEquals(responseRequestId, client.lastRequestId)
    }

    @Test
    fun invalidRequestIdFactoryFallsBackToSafeUuid() {
        val transport = FakeTransport(mutableListOf(JSONObject().put("ok", true)))
        val client = CloudSyncClient(
            CloudSyncConfig("https://vocab-sync.example.invalid", "token-123"),
            transport,
            requestIdFactory = { "server-request-123" }
        )

        client.health()

        assertTrue(runCatching { UUID.fromString(transport.requests.single().requestId) }.isSuccess)
        assertEquals(transport.requests.single().requestId, client.lastRequestId)
    }

    @Test
    fun httpTransportSendsRequestIdAndPreservesResponseHeader() {
        val clientRequestId = "f9beec17-75dc-4bbb-9dbe-4c0b80fb97df"
        val serverRequestId = "151708e5-64d1-447f-99bd-7b0e9bc9683e"
        val server = OneShotHttpServer(
            statusCode = 200,
            body = "{\"ok\":true}",
            responseHeaders = mapOf("X-Request-Id" to serverRequestId)
        )
        try {
            val client = CloudSyncClient(
                CloudSyncConfig("http://127.0.0.1:${server.port}", "token-123"),
                HttpUrlConnectionSyncTransport(),
                requestIdFactory = { clientRequestId }
            )

            client.health()

            assertEquals(clientRequestId, server.observedRequestId.get())
            assertEquals(serverRequestId, client.lastRequestId)
        } finally {
            server.close()
        }
    }

    @Test
    fun httpFailurePreservesJsonRequestIdWithoutExposingResponseBody() {
        val serverRequestId = "a02b61af-870b-4fae-97d8-1728ab328657"
        val server = OneShotHttpServer(
            statusCode = 500,
            body = JSONObject()
                .put(
                    "error",
                    JSONObject()
                        .put("message", "raw-lemma-secret token-should-not-leak")
                        .put("request_id", serverRequestId)
                )
                .toString()
        )
        try {
            val client = CloudSyncClient(
                CloudSyncConfig("http://127.0.0.1:${server.port}", "token-123")
            )

            val error = assertThrows(CloudSyncException::class.java) { client.health() }

            assertEquals("Sync HTTP 500.", error.message)
            assertEquals(serverRequestId, error.requestId)
            assertFalse(error.message.orEmpty().contains("raw-lemma-secret"))
            assertFalse(error.message.orEmpty().contains("token-should-not-leak"))
        } finally {
            server.close()
        }
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
        val bearerToken: String,
        val requestId: String
    )

    private class FakeTransport(
        private val responses: MutableList<JSONObject>,
        private val responseRequestIds: MutableList<String> = mutableListOf()
    ) : CloudSyncTransport {
        val requests = mutableListOf<Request>()

        override fun postJson(
            url: String,
            body: JSONObject,
            bearerToken: String,
            requestId: String
        ): CloudHttpResponse {
            requests += Request(
                method = "POST",
                url = url,
                body = body,
                bearerToken = bearerToken,
                requestId = requestId
            )
            return response(requestId)
        }

        override fun getJson(
            url: String,
            bearerToken: String,
            requestId: String
        ): CloudHttpResponse {
            requests += Request(
                method = "GET",
                url = url,
                body = null,
                bearerToken = bearerToken,
                requestId = requestId
            )
            return response(requestId)
        }

        private fun response(clientRequestId: String): CloudHttpResponse {
            val responseRequestId = if (responseRequestIds.isEmpty()) {
                clientRequestId
            } else {
                responseRequestIds.removeAt(0)
            }
            return CloudHttpResponse(responses.removeAt(0), responseRequestId)
        }
    }

    private class OneShotHttpServer(
        private val statusCode: Int,
        private val body: String,
        private val responseHeaders: Map<String, String> = emptyMap()
    ) {
        private val socket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port: Int = socket.localPort
        val observedRequestId = AtomicReference("")
        private val serverThread = thread(start = true, isDaemon = true, name = "cloud-sync-test-server") {
            runCatching {
                socket.accept().use { client ->
                    val input = client.getInputStream().bufferedReader()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isBlank()) break
                        if (line.startsWith("X-Request-Id:", ignoreCase = true)) {
                            observedRequestId.set(line.substringAfter(':').trim())
                        }
                    }
                    val responseBody = body.toByteArray(Charsets.UTF_8)
                    val reason = if (statusCode in 200..299) "OK" else "Error"
                    val headers = buildString {
                        append("HTTP/1.1 $statusCode $reason\r\n")
                        append("Content-Type: application/json\r\n")
                        append("Content-Length: ${responseBody.size}\r\n")
                        append("Connection: close\r\n")
                        responseHeaders.forEach { (name, value) -> append("$name: $value\r\n") }
                        append("\r\n")
                    }.toByteArray(Charsets.US_ASCII)
                    client.getOutputStream().use { output ->
                        output.write(headers)
                        output.write(responseBody)
                        output.flush()
                    }
                }
            }
        }

        fun close() {
            serverThread.join(2_000)
            socket.close()
        }
    }
}
