package com.vocabreminder.android.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vocabreminder.android.data.LocalVocabularyStore
import java.time.Instant
import java.util.concurrent.TimeUnit

class QuietHoursWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = LocalVocabularyStore(applicationContext)
        val settings = store.settings().toSchedulerSettings()
        val now = Instant.now()
        val localTime = now.atZone(settings.timezone).toLocalTime()
        val inQuiet = if (settings.quietHoursStart < settings.quietHoursEnd) {
            !localTime.isBefore(settings.quietHoursStart) && localTime < settings.quietHoursEnd
        } else {
            !localTime.isBefore(settings.quietHoursStart) || localTime < settings.quietHoursEnd
        }

        if (inQuiet) {
            // guide 6.6/14.5: unanswered notifications are withdrawn during quiet hours.
            store.cards().forEach { ReminderNotifications.cancel(applicationContext, it) }
        } else {
            DailyReviewPlanner.planAndSchedule(applicationContext, now)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "quiet-hours-sweep"

        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<QuietHoursWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
