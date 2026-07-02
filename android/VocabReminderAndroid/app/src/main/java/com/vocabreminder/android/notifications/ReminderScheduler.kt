package com.vocabreminder.android.notifications

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.vocabreminder.android.data.VocabularyCard
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

object ReminderScheduler {
    fun schedule(context: Context, card: VocabularyCard, now: Instant = Instant.now()) {
        if (card.meaningKo.isBlank()) return
        val delayMillis = Duration.between(now, card.dueAt).toMillis().coerceAtLeast(0)
        val request = OneTimeWorkRequestBuilder<ReviewNotificationWorker>()
            .setInputData(workDataOf(ReviewNotificationWorker.KEY_CARD_ID to card.id))
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .addTag("review:${card.id}")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "review:${card.id}",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
