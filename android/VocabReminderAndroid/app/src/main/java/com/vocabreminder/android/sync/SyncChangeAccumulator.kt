package com.vocabreminder.android.sync

import org.json.JSONArray
import org.json.JSONObject

class SyncChangeAccumulator {
    private val collections = keys.associateWith { linkedMapOf<String, JSONObject>() }
    private var settings: JSONObject? = null
    private var reset = false
    var cursor: String = ""
        private set

    fun append(page: JSONObject, previousCursor: String) {
        val next = page.optString("sync_cursor")
        if (next.isBlank() || !page.has("has_more") || (page.getBoolean("has_more") && next == previousCursor)) {
            throw CloudSyncException("동기화 변경 응답 형식 오류")
        }
        reset = reset || page.optBoolean("reset")
        keys.forEach { key ->
            val rows = page.optJSONArray(key) ?: throw CloudSyncException("동기화 목록 형식 오류")
            repeat(rows.length()) { index ->
                val row = rows.getJSONObject(index)
                val id = row.optString("id")
                if (id.isBlank()) throw CloudSyncException("동기화 항목 ID 오류")
                collections.getValue(key)[id] = row
            }
        }
        page.optJSONObject("settings")?.let { settings = it }
        cursor = next
    }

    fun snapshot(): JSONObject = JSONObject().apply {
        keys.forEach { put(it, JSONArray(collections.getValue(it).values.toList())) }
        put("settings", settings ?: JSONObject.NULL)
        put("sync_cursor", cursor)
        put("sync_reset", reset)
    }

    companion object {
        private val keys = listOf("cards", "contexts", "definition_candidates", "review_events", "recommendation_feedback")
    }
}
