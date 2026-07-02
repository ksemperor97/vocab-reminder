package com.vocabreminder.android.sync

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class FirebasePasswordAuthConfig(
    val webApiKey: String,
    val email: String,
    val password: String
) {
    init {
        require(webApiKey.isNotBlank()) { "webApiKey must not be blank." }
        require(email.isNotBlank()) { "email must not be blank." }
        require(password.isNotBlank()) { "password must not be blank." }
    }
}

data class FirebaseAuthSession(
    val idToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
    val email: String,
    val localId: String
)

class FirebaseAuthException(message: String) : RuntimeException(message)

interface FirebaseAuthTransport {
    fun postJson(url: String, body: JSONObject): JSONObject
    fun postForm(url: String, body: String): JSONObject
}

class HttpUrlConnectionFirebaseAuthTransport : FirebaseAuthTransport {
    override fun postJson(url: String, body: JSONObject): JSONObject {
        return post(
            url = url,
            body = body.toString(),
            contentType = "application/json; charset=utf-8"
        )
    }

    override fun postForm(url: String, body: String): JSONObject {
        return post(
            url = url,
            body = body,
            contentType = "application/x-www-form-urlencoded"
        )
    }

    private fun post(url: String, body: String, contentType: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Content-Type", contentType)
        }

        connection.outputStream.use { output ->
            output.write(body.toByteArray(Charsets.UTF_8))
        }

        val statusCode = connection.responseCode
        val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
        val responseText = stream?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
        }.orEmpty()

        if (responseText.isBlank()) {
            throw FirebaseAuthException("Firebase Auth response was empty.")
        }
        val json = JSONObject(responseText)
        if (statusCode !in 200..299) {
            throw FirebaseAuthException(json.optJSONObject("error")?.optString("message") ?: "Firebase Auth HTTP $statusCode")
        }
        return json
    }
}

class FirebaseAuthRestClient(
    private val transport: FirebaseAuthTransport = HttpUrlConnectionFirebaseAuthTransport()
) {
    fun signInWithPassword(config: FirebasePasswordAuthConfig): FirebaseAuthSession {
        val response = transport.postJson(
            url = signInUrl(config.webApiKey),
            body = JSONObject()
                .put("email", config.email)
                .put("password", config.password)
                .put("returnSecureToken", true)
        )
        response.optJSONObject("error")?.let { error ->
            throw FirebaseAuthException(error.optString("message", "Firebase Auth sign-in failed."))
        }
        val idToken = response.optString("idToken")
        if (idToken.isBlank()) {
            throw FirebaseAuthException("Firebase Auth sign-in response missing idToken.")
        }
        return FirebaseAuthSession(
            idToken = idToken,
            refreshToken = response.optString("refreshToken"),
            expiresInSeconds = response.optString("expiresIn", "0").toLongOrNull() ?: 0L,
            email = response.optString("email", config.email),
            localId = response.optString("localId")
        )
    }

    fun refreshIdToken(webApiKey: String, refreshToken: String): FirebaseAuthSession {
        require(webApiKey.isNotBlank()) { "webApiKey must not be blank." }
        require(refreshToken.isNotBlank()) { "refreshToken must not be blank." }
        val response = transport.postForm(
            url = refreshUrl(webApiKey),
            body = "grant_type=refresh_token&refresh_token=${urlEncode(refreshToken)}"
        )
        response.optJSONObject("error")?.let { error ->
            throw FirebaseAuthException(error.optString("message", "Firebase Auth refresh failed."))
        }
        val idToken = response.optString("id_token")
        if (idToken.isBlank()) {
            throw FirebaseAuthException("Firebase Auth refresh response missing id_token.")
        }
        return FirebaseAuthSession(
            idToken = idToken,
            refreshToken = response.optString("refresh_token", refreshToken),
            expiresInSeconds = response.optString("expires_in", "0").toLongOrNull() ?: 0L,
            email = "",
            localId = response.optString("user_id")
        )
    }

    private fun signInUrl(apiKey: String): String {
        return "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${urlEncode(apiKey)}"
    }

    private fun refreshUrl(apiKey: String): String {
        return "https://securetoken.googleapis.com/v1/token?key=${urlEncode(apiKey)}"
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}
