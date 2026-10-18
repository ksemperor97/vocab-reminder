package com.vocabreminder.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class CloudSyncStatusTest {
    private lateinit var store: LocalVocabularyStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = LocalVocabularyStore(context)
        store.clearAll()
    }

    @Test
    fun successStoresLastSuccessAndSkippedSummary() {
        val requestId = "151708e5-64d1-447f-99bd-7b0e9bc9683e"
        store.recordCloudSyncFailure("Sync HTTP 401")
        store.recordCloudSyncSuccess("cards 1, reasons 1", requestId)

        val status = store.cloudSyncStatus()

        assertNotNull(status.lastSuccessAt)
        assertEquals("", status.lastError)
        assertEquals("cards 1, reasons 1", status.lastSkippedSummary)
        assertEquals(requestId, status.lastRequestId)
    }

    @Test
    fun failureStoresLastErrorWithoutClearingLastSuccess() {
        store.recordCloudSyncSuccess()
        val successAt = store.cloudSyncStatus().lastSuccessAt
        val requestId = "a02b61af-870b-4fae-97d8-1728ab328657"

        store.recordCloudSyncFailure("Network timeout", requestId)

        val status = store.cloudSyncStatus()
        assertEquals(successAt, status.lastSuccessAt)
        assertNotNull(status.lastFailureAt)
        assertEquals("Network timeout", status.lastError)
        assertEquals(requestId, status.lastRequestId)
    }

    @Test
    fun failureMessageIsTruncatedForUi() {
        store.recordCloudSyncFailure("x".repeat(260))

        assertEquals(200, store.cloudSyncStatus().lastError.length)
    }

    @Test
    fun unsafeRequestIdIsNotPersisted() {
        store.recordCloudSyncFailure("Network timeout", "not a uuid")

        assertEquals("", store.cloudSyncStatus().lastRequestId)
    }

    @Test
    fun workerHeartbeatIsPersistedSeparatelyFromSyncOutcome() {
        val heartbeat = Instant.parse("2026-07-13T09:00:00Z")

        store.recordSyncWorkerHeartbeat(heartbeat)

        val status = store.cloudSyncStatus()
        assertEquals(heartbeat, status.lastWorkerRunAt)
        assertEquals(null, status.lastSuccessAt)
        assertEquals(null, status.lastFailureAt)
    }
}
