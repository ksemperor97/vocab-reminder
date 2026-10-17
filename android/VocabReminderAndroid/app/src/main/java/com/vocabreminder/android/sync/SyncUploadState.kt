package com.vocabreminder.android.sync

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object SyncUploadState {
    private val collections = listOf("cards", "contexts", "definition_candidates", "review_events", "recommendation_feedback")

    fun fingerprints(payload: JSONObject): JSONObject = JSONObject().apply {
        collections.forEach { key ->
            val rows = payload.optJSONArray(key) ?: JSONArray()
            repeat(rows.length()) {
                val row = rows.getJSONObject(it)
                put("$key:${row.getString("id")}", digest(row))
            }
        }
        payload.optJSONObject("settings")?.let { put("settings:main", digest(it)) }
    }

    fun pending(payload: JSONObject, acknowledged: JSONObject): JSONObject {
        val current = fingerprints(payload)
        return JSONObject(payload.toString()).apply {
            collections.forEach { key ->
                val rows = payload.optJSONArray(key) ?: JSONArray()
                val pending = JSONArray()
                repeat(rows.length()) {
                    val row = rows.getJSONObject(it)
                    val id = "$key:${row.getString("id") }"
                    if (acknowledged.optString(id) != current.optString(id)) pending.put(row)
                }
                put(key, pending)
            }
            if (acknowledged.optString("settings:main") == current.optString("settings:main")) put("settings", JSONObject.NULL)
        }
    }

    private fun digest(value: Any): String = MessageDigest.getInstance("SHA-256")
        .digest(canonical(value).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
}
