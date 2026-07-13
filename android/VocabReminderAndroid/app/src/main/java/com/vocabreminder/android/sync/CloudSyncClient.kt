package com.vocabreminder.android.sync

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class CloudSyncConfig(
    val serverBaseUrl: String,
    val bearerToken: String
) {
    init {
        require(serverBaseUrl.isNotBlank()) { "serverBaseUrl must not be blank." }
        require(bearerToken.isNotBlank()) { "bearerToken must not be blank." }
    }

    fun endpoint(path: String): String {
        return serverBaseUrl.trimEnd('/') + "/" + path.trimStart('/')
    }
}

class CloudSyncException(message: String) : RuntimeException(message)

interface CloudSyncTransport {
    fun postJson(url: String, body: JSONObject, bearerToken: String): JSONObject
    fun getJson(url: String, bearerToken: String): JSONObject
}

class HttpUrlConnectionSyncTransport : CloudSyncTransport {
    override fun postJson(url: String, body: JSONObject, bearerToken: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 3_000
            readTimeout = 5_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $bearerToken")
        }

        connection.outputStream.use { output ->
            output.write(body.toString().toByteArray(Charsets.UTF_8))
        }

        return readResponse(connection)
    }

    override fun getJson(url: String, bearerToken: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 3_000
            readTimeout = 5_000
            setRequestProperty("Authorization", "Bearer $bearerToken")
        }
        return readResponse(connection)
    }

    private fun readResponse(connection: HttpURLConnection): JSONObject {
        val statusCode = connection.responseCode
        val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
        val responseText = stream?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
        }.orEmpty()

        if (statusCode !in 200..299) {
            throw CloudSyncException("Sync HTTP $statusCode: $responseText")
        }
        if (responseText.isBlank()) {
            throw CloudSyncException("Sync response was empty.")
        }
        return JSONObject(responseText)
    }
}

class CloudSyncClient(
    private val config: CloudSyncConfig,
    private val transport: CloudSyncTransport = HttpUrlConnectionSyncTransport()
) {
    fun push(localBatch: JSONObject): JSONObject {
        val aggregate = emptyPushResult()
        splitIntoBatches(localBatch).forEach { batch ->
            val result = transport.postJson(
                url = config.endpoint("sync/batch"),
                body = batch,
                bearerToken = config.bearerToken
            ).throwIfError()
            mergePushResult(aggregate, result)
        }
        return aggregate
    }

    fun pullSnapshot(): JSONObject {
        return transport.getJson(
            url = config.endpoint("sync/snapshot"),
            bearerToken = config.bearerToken
        ).throwIfError()
    }

    fun health(): JSONObject {
        return transport.getJson(
            url = config.endpoint("health"),
            bearerToken = config.bearerToken
        ).throwIfError()
    }

    fun pushAndPull(localBatch: JSONObject): JSONObject {
        push(localBatch)
        return pullSnapshot()
    }

    private fun JSONObject.throwIfError(): JSONObject {
        optJSONObject("error")?.let { error ->
            throw CloudSyncException(error.optString("message", "Sync failed."))
        }
        return this
    }

    internal fun splitIntoBatches(localBatch: JSONObject): List<JSONObject> {
        val arrays = COLLECTION_KEYS.associateWith { key ->
            localBatch.optJSONArray(key) ?: org.json.JSONArray()
        }
        val batchCount = maxOf(
            1,
            arrays.values.maxOfOrNull { array ->
                (array.length() + MAX_ITEMS_PER_BATCH - 1) / MAX_ITEMS_PER_BATCH
            } ?: 1
        )
        val settings = if (localBatch.has("settings")) localBatch.opt("settings") else JSONObject.NULL

        return (0 until batchCount).map { batchIndex ->
            val batch = JSONObject()
            COLLECTION_KEYS.forEach { key ->
                val source = arrays.getValue(key)
                val start = batchIndex * MAX_ITEMS_PER_BATCH
                val end = minOf(source.length(), start + MAX_ITEMS_PER_BATCH)
                val slice = org.json.JSONArray()
                for (index in start until end) slice.put(source.get(index))
                batch.put(key, slice)
            }
            // Apply settings only after every entity chunk has been accepted. Retrying a
            // partially completed upload is safe because every collection is idempotent.
            batch.put("settings", if (batchIndex == batchCount - 1) settings else JSONObject.NULL)
        }
    }

    private fun emptyPushResult(): JSONObject {
        return JSONObject()
            .put("applied", JSONObject())
            .put("skipped", JSONObject().put("reasons", org.json.JSONArray()))
    }

    private fun mergePushResult(aggregate: JSONObject, result: JSONObject) {
        mergeCountObject(aggregate.getJSONObject("applied"), result.optJSONObject("applied"))
        val aggregateSkipped = aggregate.getJSONObject("skipped")
        val resultSkipped = result.optJSONObject("skipped")
        mergeCountObject(aggregateSkipped, resultSkipped, ignoredKeys = setOf("reasons"))
        val aggregateReasons = aggregateSkipped.getJSONArray("reasons")
        val resultReasons = resultSkipped?.optJSONArray("reasons")
        if (resultReasons != null) {
            for (index in 0 until resultReasons.length()) aggregateReasons.put(resultReasons.get(index))
        }
    }

    private fun mergeCountObject(
        aggregate: JSONObject,
        incoming: JSONObject?,
        ignoredKeys: Set<String> = emptySet()
    ) {
        if (incoming == null) return
        incoming.keys().forEach { key ->
            if (key !in ignoredKeys) {
                aggregate.put(key, aggregate.optInt(key, 0) + incoming.optInt(key, 0))
            }
        }
    }

    companion object {
        internal const val MAX_ITEMS_PER_BATCH = 400
        private val COLLECTION_KEYS = listOf(
            "cards",
            "contexts",
            "definition_candidates",
            "review_events",
            "recommendation_feedback"
        )
    }
}
