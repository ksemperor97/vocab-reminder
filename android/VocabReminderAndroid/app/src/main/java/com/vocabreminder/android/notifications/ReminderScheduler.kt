package com.vocabreminder.android.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.data.VocabularyCard
import com.vocabreminder.android.scheduler.CardStatus
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

object ReminderScheduler {
    fun reconcileAll(
        context: Context,
        store: LocalVocabularyStore = LocalVocabularyStore(context),
        now: Instant = NotificationClock.now()
    ) {
        store.allCards(includeDeleted = true).forEach { schedule(context, it, now) }
    }

    fun schedule(context: Context, card: VocabularyCard, now: Instant = NotificationClock.now()) {
        scheduleAt(context, card, card.dueAt, now)
    }

    fun scheduleAt(
        context: Context,
        card: VocabularyCard,
        triggerAt: Instant,
        now: Instant = NotificationClock.now()
    ) {
        if (card.meaningKo.isBlank() || card.deletedAt != null || card.status == CardStatus.MASTERED || card.status == CardStatus.SUSPENDED) {
            cancel(context, card)
            return
        }
        val delayMillis = Duration.between(now, triggerAt).toMillis().coerceAtLeast(0)
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
        scheduleAlarm(context, card, now, delayMillis)
    }

    fun cancel(context: Context, card: VocabularyCard) {
        WorkManager.getInstance(context).cancelUniqueWork("review:${card.id}")
        reviewDuePendingIntent(context, card, PendingIntent.FLAG_NO_CREATE)?.let { pendingIntent ->
            context.getSystemService(AlarmManager::class.java).cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    private fun scheduleAlarm(context: Context, card: VocabularyCard, now: Instant, delayMillis: Long) {
        val triggerAtMillis = now.toEpochMilli() + delayMillis
        val pendingIntent = reviewDuePendingIntent(context, card, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    }

    private fun reviewDuePendingIntent(context: Context, card: VocabularyCard, flag: Int): PendingIntent? {
        val intent = Intent(context, ReviewAlarmReceiver::class.java)
            .setAction(ReviewAlarmReceiver.ACTION_REVIEW_DUE)
            .putExtra(ReviewNotificationWorker.KEY_CARD_ID, card.id)
        return PendingIntent.getBroadcast(
            context,
            card.notificationId,
            intent,
            flag or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
