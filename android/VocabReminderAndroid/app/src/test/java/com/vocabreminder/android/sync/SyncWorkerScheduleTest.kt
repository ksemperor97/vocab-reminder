package com.vocabreminder.android.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SyncWorkerScheduleTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @Test
    fun periodicSyncUsesTwoHourUpdatePolicy() {
        SyncWorker.ensureScheduled(context)

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SyncWorker.PERIODIC_WORK)
            .get()

        assertEquals(2L, SyncWorker.PERIODIC_INTERVAL_HOURS)
        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, SyncWorker.PERIODIC_WORK_POLICY)
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.first().state)
    }
}
