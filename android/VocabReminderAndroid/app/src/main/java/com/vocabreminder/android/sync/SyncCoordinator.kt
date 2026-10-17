package com.vocabreminder.android.sync

import com.vocabreminder.android.data.CloudSyncResult
import com.vocabreminder.android.data.LocalVocabularyStore
import org.json.JSONObject

/**
 * One sync round: upload the outbox, then pull what changed since the last
 * cursor. The upload always runs (even when empty) because it is what tells
 * the server to top up today's recommendations; reads never write.
 */
class SyncCoordinator(private val store: LocalVocabularyStore) {
    fun sync(client: CloudSyncClient): CloudSyncResult {
        val endpoint = client.cursorEndpoint
        var cursor = store.syncCursor(endpoint)
        if (cursor == null) store.queueEverythingForUpload()

        var upload = store.pendingUpload()
        var pushResult = client.push(upload.payload)
        var snapshot = client.pullSnapshot(cursor)
        if (snapshot.optBoolean("sync_reset") && cursor != null) {
            // The server's change log was reset (restore or new database):
            // send everything again and take a full copy back.
            store.queueEverythingForUpload()
            upload = store.pendingUpload()
            pushResult = client.push(upload.payload)
            cursor = null
            snapshot = client.pullSnapshot()
        }

        store.importSyncBatch(snapshot)
        val skipped = skippedSummary(pushResult)
        // The server answered for every row. Validation skips are deterministic, so
        // retrying them each round would only re-send the same rows; they are
        // reported through the skipped summary and re-sent if edited again.
        store.acknowledgeUpload(upload.digests)
        // Advance only after the import commits; an interrupted round replays safely.
        store.saveSyncCursor(endpoint, snapshot.optString("sync_cursor").takeIf { it.isNotBlank() })
        return CloudSyncResult(
            snapshotJson = snapshot.toString(),
            skippedSummary = skipped,
            requestId = client.lastRequestId
        )
    }

    private fun skippedSummary(response: JSONObject): String {
        val skipped = response.optJSONObject("skipped") ?: return ""
        val parts = mutableListOf<String>()
        for (name in listOf("cards", "contexts", "definition_candidates", "review_events", "recommendation_feedback", "settings")) {
            val count = skipped.optInt(name, 0)
            if (count > 0) parts += "$name $count"
        }
        val reasonCount = skipped.optJSONArray("reasons")?.length() ?: 0
        if (reasonCount > 0) parts += "reasons $reasonCount"
        return parts.joinToString(", ")
    }
}
