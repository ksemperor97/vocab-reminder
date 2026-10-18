package com.vocabreminder.android.sync

import com.vocabreminder.android.dictionary.DictionaryFallback
import com.vocabreminder.android.dictionary.DictionaryLookupErrorCode
import com.vocabreminder.android.dictionary.DictionaryLookupRequest
import com.vocabreminder.android.dictionary.DictionaryLookupResult
import com.vocabreminder.android.dictionary.DictionaryLookupStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.time.Instant
import java.util.Locale
import java.util.UUID

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

/**
 * Exchanges a pairing code for this device's own token. The request carries no
 * credentials; the code is the proof.
 */
fun claimPairing(
    serverBaseUrl: String,
    code: String,
    deviceName: String,
    transport: CloudSyncTransport = HttpUrlConnectionSyncTransport()
): com.vocabreminder.android.data.PairedServer {
    val base = serverBaseUrl.trim().trimEnd('/')
    require(base.startsWith("https://") || base.startsWith("http://")) { "서버 주소는 https://로 시작해야 해요." }
    val body = JSONObject().put("code", code.filter(Char::isDigit)).put("device_name", deviceName).put("platform", "android")
    val response = transport.postJson("$base/pair/claim", body, bearerToken = "", requestId = UUID.randomUUID().toString(), readTimeoutMillis = 15_000)
    val token = response.body.optString("token")
    if (token.isBlank()) {
        throw CloudSyncException(response.body.optString("error").ifBlank { "연결하지 못했어요." }, response.requestId)
    }
    return com.vocabreminder.android.data.PairedServer(base, token, response.body.optString("device_id"))
}

class CloudSyncException(
    message: String,
    val requestId: String? = null,
    cause: Throwable? = null,
    val httpStatus: Int? = null
) : RuntimeException(message, cause)

data class CloudHttpResponse(
    val body: JSONObject,
    val requestId: String
)

interface CloudSyncTransport {
    fun postJson(
        url: String,
        body: JSONObject,
        bearerToken: String,
        requestId: String
    ): CloudHttpResponse

    fun getJson(
        url: String,
        bearerToken: String,
        requestId: String
    ): CloudHttpResponse

    /** A POST that may wait longer for its answer (context meanings call an LLM on the server). */
    fun postJson(
        url: String,
        body: JSONObject,
        bearerToken: String,
        requestId: String,
        readTimeoutMillis: Int
    ): CloudHttpResponse = postJson(url, body, bearerToken, requestId)
}

class HttpUrlConnectionSyncTransport : CloudSyncTransport {
    override fun postJson(
        url: String,
        body: JSONObject,
        bearerToken: String,
        requestId: String
    ): CloudHttpResponse = postJson(url, body, bearerToken, requestId, DEFAULT_READ_TIMEOUT_MILLIS)

    override fun postJson(
        url: String,
        body: JSONObject,
        bearerToken: String,
        requestId: String,
        readTimeoutMillis: Int
    ): CloudHttpResponse {
        val safeRequestId = normalizeUuidRequestId(requestId) ?: UUID.randomUUID().toString()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 3_000
            readTimeout = readTimeoutMillis
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $bearerToken")
            setRequestProperty("User-Agent", HTTP_USER_AGENT)
            setRequestProperty(REQUEST_ID_HEADER, safeRequestId)
        }

        connection.outputStream.use { output ->
            output.write(body.toString().toByteArray(Charsets.UTF_8))
        }

        return readResponse(connection, safeRequestId)
    }

    override fun getJson(
        url: String,
        bearerToken: String,
        requestId: String
    ): CloudHttpResponse {
        val safeRequestId = normalizeUuidRequestId(requestId) ?: UUID.randomUUID().toString()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 3_000
            readTimeout = DEFAULT_READ_TIMEOUT_MILLIS
            setRequestProperty("Authorization", "Bearer $bearerToken")
            setRequestProperty("User-Agent", HTTP_USER_AGENT)
            setRequestProperty(REQUEST_ID_HEADER, safeRequestId)
        }
        return readResponse(connection, safeRequestId)
    }

    private fun readResponse(
        connection: HttpURLConnection,
        clientRequestId: String
    ): CloudHttpResponse {
        val statusCode = connection.responseCode
        val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
        val responseText = stream?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
        }.orEmpty()
        val responseJson = responseText.takeIf { it.isNotBlank() }
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val responseRequestId = normalizeRequestId(connection.getHeaderField(REQUEST_ID_HEADER))
            ?: responseJson?.requestIdFromBody()
            ?: clientRequestId

        if (statusCode !in 200..299) {
            // Never include the response body here: upstream failures can echo request data.
            throw CloudSyncException("Sync HTTP $statusCode.", responseRequestId, httpStatus = statusCode)
        }
        if (responseText.isBlank()) {
            throw CloudSyncException("Sync response was empty.", responseRequestId)
        }
        if (responseJson == null) {
            throw CloudSyncException("Sync response was invalid.", responseRequestId)
        }
        return CloudHttpResponse(responseJson, responseRequestId)
    }

    private companion object {
        const val DEFAULT_READ_TIMEOUT_MILLIS = 5_000
        const val HTTP_USER_AGENT = "VocabReminderAndroid/0.1"
        const val REQUEST_ID_HEADER = "X-Request-Id"
    }
}

fun interface DictionaryLookupClient {
    fun lookupDictionary(items: List<DictionaryLookupRequest>): List<DictionaryLookupResult>
}

class CloudSyncClient(
    private val config: CloudSyncConfig,
    private val transport: CloudSyncTransport = HttpUrlConnectionSyncTransport(),
    private val requestIdFactory: () -> String = { UUID.randomUUID().toString() }
) : DictionaryLookupClient {
    @Volatile
    var lastRequestId: String? = null
        private set

    private var supportsChanges = false
    val cursorEndpoint: String get() = config.serverBaseUrl.trimEnd('/')

    fun push(localBatch: JSONObject): JSONObject {
        val aggregate = emptyPushResult()
        splitIntoBatches(localBatch).forEach { batch ->
            val response = postJson(config.endpoint("sync/batch"), batch)
            val result = response.body.throwIfError(response.requestId)
            supportsChanges = supportsChanges || result.optInt("sync_protocol") >= 2
            mergePushResult(aggregate, result)
        }
        return aggregate
    }

    fun pullSnapshot(cursor: String? = null): JSONObject {
        if (!supportsChanges) {
            val response = getJson(config.endpoint("sync/snapshot"))
            return response.body.throwIfError(response.requestId)
        }
        var next = cursor.orEmpty()
        val collected = SyncChangeAccumulator()
        repeat(10_000) {
            val escaped = java.net.URLEncoder.encode(next, "UTF-8")
            val response = getJson(config.endpoint("sync/changes?limit=400&cursor=$escaped"))
            val page = response.body.throwIfError(response.requestId)
            collected.append(page, next)
            next = collected.cursor
            if (!page.getBoolean("has_more")) return collected.snapshot()
        }
        throw CloudSyncException("동기화 변경 페이지가 너무 많습니다.", lastRequestId)
    }

    /** A 6-digit code another device can use to pair with this server (10 minutes, once). */
    fun createPairingCode(): String {
        val response = postJson(config.endpoint("pair/code"), JSONObject())
        return response.body.throwIfError(response.requestId).getString("code")
    }

    fun health(): JSONObject {
        val response = getJson(config.endpoint("health"))
        return response.body.throwIfError(response.requestId)
    }

    fun lookupDictionaryBatch(lemmas: List<String>): List<DictionaryLookupResult> {
        checkDictionaryBatchSize(lemmas.size)
        val normalized = lemmas.map(::normalizeDictionaryLemma)
        return postDictionaryBatch(JSONObject().put("lemmas", JSONArray(normalized)), normalized, SHORT_READ_TIMEOUT_MILLIS)
    }

    /** Sends each lemma with the sentence it was captured in, so the server can pick the meaning that sentence uses. */
    override fun lookupDictionary(items: List<DictionaryLookupRequest>): List<DictionaryLookupResult> {
        checkDictionaryBatchSize(items.size)
        val normalized = items.map { DictionaryLookupRequest(normalizeDictionaryLemma(it.lemma), it.sentence.trim().take(MAX_CONTEXT_SENTENCE_LENGTH)) }
        val body = JSONObject().put("items", JSONArray(normalized.map { item ->
            JSONObject().put("lemma", item.lemma).apply { if (item.sentence.isNotBlank()) put("sentence", item.sentence) }
        }))
        val timeout = if (normalized.any { it.sentence.isNotBlank() }) CONTEXT_READ_TIMEOUT_MILLIS else SHORT_READ_TIMEOUT_MILLIS
        return try {
            postDictionaryBatch(body, normalized.map { it.lemma }, timeout)
        } catch (error: CloudSyncException) {
            // A server older than context meanings only reads {lemmas}; answers then carry no contextStatus.
            if (error.httpStatus != 400) throw error
            lookupDictionaryBatch(normalized.map { it.lemma })
        }
    }

    private fun checkDictionaryBatchSize(size: Int) {
        if (size == 0 || size > MAX_DICTIONARY_ITEMS_PER_BATCH) {
            throw IllegalArgumentException(
                "Dictionary batch must contain between 1 and $MAX_DICTIONARY_ITEMS_PER_BATCH lemmas."
            )
        }
    }

    private fun postDictionaryBatch(body: JSONObject, lemmas: List<String>, readTimeoutMillis: Int): List<DictionaryLookupResult> {
        val httpResponse = executeRequest { requestId ->
            transport.postJson(
                url = config.endpoint("dict/batch"),
                body = body,
                bearerToken = config.bearerToken,
                requestId = requestId,
                readTimeoutMillis = readTimeoutMillis
            )
        }
        val response = httpResponse.body.throwIfError(httpResponse.requestId)
        val results = response.optJSONArray("results")
            ?: throw CloudSyncException(
                "Dictionary response did not contain results.",
                httpResponse.requestId
            )
        if (results.length() != lemmas.size) {
            throw CloudSyncException(
                "Dictionary response count did not match the request.",
                httpResponse.requestId
            )
        }
        return lemmas.indices.map { index ->
            parseDictionaryLookupResult(
                results.optJSONObject(index),
                lemmas[index],
                httpResponse.requestId
            )
        }
    }

    fun pushAndPull(localBatch: JSONObject): JSONObject {
        push(localBatch)
        return pullSnapshot()
    }

    private fun JSONObject.throwIfError(requestId: String): JSONObject {
        optJSONObject("error")?.let { error ->
            val message = error.optString("message", "Sync failed.")
                .trim()
                .replace(Regex("\\s+"), " ")
                .take(MAX_REMOTE_ERROR_LENGTH)
                .ifBlank { "Sync failed." }
            throw CloudSyncException(message, requestIdFromBody() ?: requestId)
        }
        return this
    }

    private fun postJson(url: String, body: JSONObject): CloudHttpResponse {
        return executeRequest { requestId ->
            transport.postJson(
                url = url,
                body = body,
                bearerToken = config.bearerToken,
                requestId = requestId
            )
        }
    }

    private fun getJson(url: String): CloudHttpResponse {
        return executeRequest { requestId ->
            transport.getJson(
                url = url,
                bearerToken = config.bearerToken,
                requestId = requestId
            )
        }
    }

    private fun executeRequest(
        operation: (requestId: String) -> CloudHttpResponse
    ): CloudHttpResponse {
        val clientRequestId = runCatching { normalizeUuidRequestId(requestIdFactory()) }
            .getOrNull()
            ?: UUID.randomUUID().toString()
        return try {
            val response = operation(clientRequestId)
            val responseRequestId = normalizeRequestId(response.requestId) ?: clientRequestId
            lastRequestId = responseRequestId
            if (response.requestId == responseRequestId) response else response.copy(requestId = responseRequestId)
        } catch (error: CloudSyncException) {
            val responseRequestId = normalizeRequestId(error.requestId) ?: clientRequestId
            lastRequestId = responseRequestId
            if (error.requestId == responseRequestId) throw error
            throw CloudSyncException(error.message ?: "Sync request failed.", responseRequestId, error, error.httpStatus)
        } catch (error: Exception) {
            lastRequestId = clientRequestId
            throw CloudSyncException("Sync request failed.", clientRequestId, error)
        }
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
        const val MAX_DICTIONARY_ITEMS_PER_BATCH = 50
        // The server spends at most ~20 s starting LLM calls plus 15 s per call.
        internal const val CONTEXT_READ_TIMEOUT_MILLIS = 45_000
        private const val SHORT_READ_TIMEOUT_MILLIS = 5_000
        private const val MAX_CONTEXT_SENTENCE_LENGTH = 1_000
        private const val MAX_REMOTE_ERROR_LENGTH = 160
        private val COLLECTION_KEYS = listOf(
            "cards",
            "contexts",
            "definition_candidates",
            "review_events",
            "recommendation_feedback"
        )
    }
}

internal fun normalizeDictionaryLemma(value: String): String {
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace('\u2018', '\'')
        .replace('\u2019', '\'')
        .replace(Regex("[\\u2010-\\u2015]"), "-")
        .trim()
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.US)
    if (normalized.codePointCount(0, normalized.length) !in 1..80 ||
        !DICTIONARY_LEMMA_PATTERN.matches(normalized)
    ) {
        throw IllegalArgumentException("Dictionary lemma contains unsupported characters.")
    }
    return normalized
}

private fun parseDictionaryLookupResult(
    json: JSONObject?,
    requestedLemma: String,
    requestId: String
): DictionaryLookupResult {
    if (json == null) {
        throw CloudSyncException("Dictionary response contained an invalid result.", requestId)
    }
    val status = when (json.optString("status").lowercase()) {
        "resolved" -> DictionaryLookupStatus.RESOLVED
        "pending" -> DictionaryLookupStatus.PENDING
        else -> throw CloudSyncException("Dictionary response contained an invalid status.", requestId)
    }
    val responseLemma = runCatching {
        json.stringValue("lemma")
            .ifBlank { requestedLemma }
            .let(::normalizeDictionaryLemma)
    }.getOrElse {
        throw CloudSyncException("Dictionary response contained an invalid lemma.", requestId)
    }
    if (responseLemma != requestedLemma) {
        throw CloudSyncException("Dictionary response lemma did not match the request.", requestId)
    }
    val meaningKo = json.stringValue("meaningKo", "meaning_ko")
    val definitionEn = json.stringValue("definitionEn", "definition_en")
    val errorCode = when (json.stringValue("errorCode", "error_code")) {
        "" -> null
        "not_found" -> DictionaryLookupErrorCode.NOT_FOUND
        "provider_failure" -> DictionaryLookupErrorCode.PROVIDER_FAILURE
        else -> throw CloudSyncException("Dictionary response contained an invalid error code.", requestId)
    }
    if (status == DictionaryLookupStatus.RESOLVED && meaningKo.isBlank() && definitionEn.isBlank()) {
        throw CloudSyncException("Resolved dictionary response did not contain a definition.", requestId)
    }
    if (status == DictionaryLookupStatus.PENDING && errorCode == null) {
        throw CloudSyncException("Pending dictionary response did not contain an error code.", requestId)
    }
    return DictionaryLookupResult(
        status = status,
        lemma = responseLemma,
        meaningKo = meaningKo,
        definitionEn = definitionEn,
        partOfSpeech = json.stringValue("partOfSpeech", "part_of_speech"),
        exampleSentence = json.stringValue("exampleSentence", "example_sentence"),
        sourceType = json.stringValue("sourceType", "source_type").ifBlank { "dictionary" },
        confidence = json.optDouble("confidence", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0,
        attemptedAt = json.stringValue("attemptedAt", "attempted_at")
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { Instant.parse(it) }.getOrNull() },
        errorCode = errorCode,
        requestId = requestId,
        senses = json.optJSONArray("senses")?.let { array ->
            (0 until minOf(array.length(), 20)).mapNotNull { index ->
                array.optJSONObject(index)?.let { sense ->
                    com.vocabreminder.android.dictionary.DictionarySense(
                        sense.stringValue("meaningKo", "meaning_ko"),
                        sense.stringValue("definitionEn", "definition_en"),
                        sense.stringValue("partOfSpeech", "part_of_speech"),
                        sense.stringValue("exampleSentence", "example_sentence")
                    )
                }
            }
        }.orEmpty(),
        contextStatus = json.stringValue("contextStatus", "context_status").ifBlank { null },
        contextSentence = json.stringValue("contextSentence", "context_sentence"),
        dictionary = json.optJSONObject("dictionary")?.let { fallback ->
            DictionaryFallback(
                meaningKo = fallback.stringValue("meaningKo", "meaning_ko"),
                definitionEn = fallback.stringValue("definitionEn", "definition_en"),
                partOfSpeech = fallback.stringValue("partOfSpeech", "part_of_speech"),
                exampleSentence = fallback.stringValue("exampleSentence", "example_sentence"),
                sourceType = fallback.stringValue("sourceType", "source_type").ifBlank { "dictionary" },
                confidence = fallback.optDouble("confidence", 0.0).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
            )
        }?.takeIf { it.meaningKo.isNotBlank() || it.definitionEn.isNotBlank() }
    )
}

private fun JSONObject.requestIdFromBody(): String? {
    return normalizeRequestId(optString("request_id"))
        ?: optJSONObject("error")?.let { normalizeRequestId(it.optString("request_id")) }
}

internal fun normalizeRequestId(value: String?): String? {
    val candidate = value.orEmpty().trim()
    return candidate.takeIf(SAFE_RESPONSE_REQUEST_ID::matches)
}

private fun normalizeUuidRequestId(value: String?): String? {
    return runCatching { UUID.fromString(value.orEmpty().trim()).toString() }.getOrNull()
}

private fun JSONObject.stringValue(vararg names: String): String {
    for (name in names) {
        if (has(name) && !isNull(name)) return optString(name).trim()
    }
    return ""
}

private val DICTIONARY_LEMMA_PATTERN = Regex("^[\\p{L}\\p{M}]+(?:[ '\\-][\\p{L}\\p{M}]+)*$")
private val SAFE_RESPONSE_REQUEST_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$")
