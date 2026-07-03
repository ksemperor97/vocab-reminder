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
        return transport.postJson(
            url = config.endpoint("sync/batch"),
            body = localBatch,
            bearerToken = config.bearerToken
        ).throwIfError()
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
}
