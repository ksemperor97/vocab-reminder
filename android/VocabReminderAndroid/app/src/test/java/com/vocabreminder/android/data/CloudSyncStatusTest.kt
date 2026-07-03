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
        store.recordCloudSyncFailure("Sync HTTP 401")
        store.recordCloudSyncSuccess("cards 1, reasons 1")

        val status = store.cloudSyncStatus()

        assertNotNull(status.lastSuccessAt)
        assertEquals("", status.lastError)
        assertEquals("cards 1, reasons 1", status.lastSkippedSummary)
    }

    @Test
    fun failureStoresLastErrorWithoutClearingLastSuccess() {
        store.recordCloudSyncSuccess()
        val successAt = store.cloudSyncStatus().lastSuccessAt

        store.recordCloudSyncFailure("Network timeout")

        val status = store.cloudSyncStatus()
        assertEquals(successAt, status.lastSuccessAt)
        assertNotNull(status.lastFailureAt)
        assertEquals("Network timeout", status.lastError)
    }
}
