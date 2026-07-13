package com.vocabreminder.android.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.notifications.DailyReviewPlanner
import com.vocabreminder.android.notifications.ReminderNotifications
import com.vocabreminder.android.notifications.ReminderScheduler
import java.time.Instant
import java.util.concurrent.TimeUnit

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = LocalVocabularyStore(applicationContext)
        val credentials = store.cloudSyncCredentials()
        val serverUrl = credentials.serverBaseUrl.trim()
        val token = credentials.bearerToken.trim()
        if (serverUrl.isBlank() || token.isBlank()) return Result.success()

        return runCatching {
            val result = store.syncWithCloud(CloudSyncClient(CloudSyncConfig(serverUrl, token)))
            store.recordCloudSyncSuccess(result.skippedSummary)
            val now = Instant.now()
            ReminderScheduler.reconcileAll(applicationContext, store, now)
            ReminderNotifications.clearStalePrompt(applicationContext, store, now)
            ReminderNotifications.restoreActiveNotification(applicationContext, store, now)
            DailyReviewPlanner.planAndSchedule(applicationContext, now)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                store.recordCloudSyncFailure(error.message ?: error::class.java.simpleName)
                Result.retry()
            }
        )
    }

    companion object {
        internal const val PERIODIC_WORK = "local-sync-periodic"
        internal const val IMMEDIATE_WORK = "local-sync-immediate"
        internal const val PERIODIC_INTERVAL_MINUTES = 15L
        internal val PERIODIC_WORK_POLICY = ExistingPeriodicWorkPolicy.UPDATE
        private const val LEGACY_DEBOUNCED_WORK = "local-sync-debounced"

        private val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(networkConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            val workManager = WorkManager.getInstance(context)
            workManager.enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                PERIODIC_WORK_POLICY,
                request
            )
            workManager.cancelUniqueWork(LEGACY_DEBOUNCED_WORK)
        }

        fun enqueueImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(networkConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

    }
}
