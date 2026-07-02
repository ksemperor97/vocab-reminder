package com.vocabreminder.android.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FirebaseAuthRestClientTest {
    @Test
    fun signsInWithEmailPasswordAndReturnsIdToken() {
        val transport = FakeAuthTransport(
            JSONObject()
                .put("idToken", "id-token-123")
                .put("refreshToken", "refresh-token-123")
                .put("expiresIn", "3600")
                .put("email", "user@example.com")
                .put("localId", "uid-123")
        )
        val client = FirebaseAuthRestClient(transport)

        val session = client.signInWithPassword(
            FirebasePasswordAuthConfig(
                webApiKey = "api key",
                email = "user@example.com",
                password = "secret"
            )
        )

        assertEquals("https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=api+key", transport.url)
        assertEquals("user@example.com", transport.body.getString("email"))
        assertEquals("secret", transport.body.getString("password"))
        assertEquals(true, transport.body.getBoolean("returnSecureToken"))
        assertEquals("id-token-123", session.idToken)
        assertEquals("refresh-token-123", session.refreshToken)
        assertEquals(3600L, session.expiresInSeconds)
        assertEquals("uid-123", session.localId)
    }

    @Test
    fun throwsReadableErrorForAuthFailure() {
        val transport = FakeAuthTransport(
            JSONObject().put("error", JSONObject().put("message", "INVALID_PASSWORD"))
        )
        val client = FirebaseAuthRestClient(transport)

        val error = assertThrows(FirebaseAuthException::class.java) {
            client.signInWithPassword(
                FirebasePasswordAuthConfig(
                    webApiKey = "api-key",
                    email = "user@example.com",
                    password = "wrong"
                )
            )
        }

        assertEquals("INVALID_PASSWORD", error.message)
    }

    @Test
    fun refreshesIdTokenWithStoredRefreshToken() {
        val transport = FakeAuthTransport(
            JSONObject()
                .put("id_token", "fresh-id-token")
                .put("refresh_token", "fresh-refresh-token")
                .put("expires_in", "3600")
                .put("user_id", "uid-123")
        )
        val client = FirebaseAuthRestClient(transport)

        val session = client.refreshIdToken(
            webApiKey = "api key",
            refreshToken = "refresh token"
        )

        assertEquals("https://securetoken.googleapis.com/v1/token?key=api+key", transport.url)
        assertEquals("grant_type=refresh_token&refresh_token=refresh+token", transport.formBody)
        assertEquals("fresh-id-token", session.idToken)
        assertEquals("fresh-refresh-token", session.refreshToken)
        assertEquals(3600L, session.expiresInSeconds)
        assertEquals("uid-123", session.localId)
    }

    private class FakeAuthTransport(
        private val response: JSONObject
    ) : FirebaseAuthTransport {
        lateinit var url: String
        lateinit var body: JSONObject
        lateinit var formBody: String

        override fun postJson(url: String, body: JSONObject): JSONObject {
            this.url = url
            this.body = body
            return response
        }

        override fun postForm(url: String, body: String): JSONObject {
            this.url = url
            this.formBody = body
            return response
        }
    }
}
