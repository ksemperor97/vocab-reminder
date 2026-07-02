package com.vocabreminder.android.sync

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class CloudSyncConfig(
    val functionsBaseUrl: String,
    val bearerToken: String
) {
    init {
        require(functionsBaseUrl.isNotBlank()) { "functionsBaseUrl must not be blank." }
        require(bearerToken.isNotBlank()) { "bearerToken must not be blank." }
    }

    fun endpoint(functionName: String): String {
        return functionsBaseUrl.trimEnd('/') + "/" + functionName
    }
}

class CloudSyncException(message: String) : RuntimeException(message)

interface CloudSyncTransport {
    fun postJson(url: String, body: JSONObject, bearerToken: String): JSONObject
}

class HttpUrlConnectionSyncTransport : CloudSyncTransport {
    override fun postJson(url: String, body: JSONObject, bearerToken: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $bearerToken")
        }

        connection.outputStream.use { output ->
            output.write(body.toString().toByteArray(Charsets.UTF_8))
        }

        val statusCode = connection.responseCode
        val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
        val responseText = stream?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
        }.orEmpty()

        if (statusCode !in 200..299) {
            throw CloudSyncException("Callable HTTP $statusCode: $responseText")
        }
        if (responseText.isBlank()) {
            throw CloudSyncException("Callable response was empty.")
        }
        return JSONObject(responseText)
    }
}

class CloudSyncClient(
    private val config: CloudSyncConfig,
    private val transport: CloudSyncTransport = HttpUrlConnectionSyncTransport()
) {
    fun push(localBatch: JSONObject): JSONObject {
        return call("syncBatch", localBatch)
    }

    fun pullSnapshot(): JSONObject {
        return call("syncSnapshot", JSONObject())
    }

    fun pushAndPull(localBatch: JSONObject): JSONObject {
        push(localBatch)
        return pullSnapshot()
    }

    private fun call(functionName: String, data: JSONObject): JSONObject {
        val response = transport.postJson(
            url = config.endpoint(functionName),
            body = JSONObject().put("data", data),
            bearerToken = config.bearerToken
        )
        response.optJSONObject("error")?.let { error ->
            throw CloudSyncException(error.optString("message", "Callable $functionName failed."))
        }
        return response.optJSONObject("result")
            ?: throw CloudSyncException("Callable $functionName response missing result.")
    }
}
