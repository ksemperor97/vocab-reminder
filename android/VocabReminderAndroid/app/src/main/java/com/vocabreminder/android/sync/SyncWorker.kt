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
            ReminderNotifications.clearStalePrompt(applicationContext, store)
            DailyReviewPlanner.planAndSchedule(applicationContext)
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
        internal const val DEBOUNCED_WORK = "local-sync-debounced"
        internal const val PERIODIC_INTERVAL_HOURS = 2L
        internal val PERIODIC_WORK_POLICY = ExistingPeriodicWorkPolicy.UPDATE

        private val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(networkConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                PERIODIC_WORK_POLICY,
                request
            )
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

        fun enqueueDebounced(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(5, TimeUnit.MINUTES)
                .setConstraints(networkConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                DEBOUNCED_WORK,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
