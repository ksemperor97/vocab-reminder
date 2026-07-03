package com.vocabreminder.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.scheduler.CardStatus
import com.vocabreminder.android.scheduler.DueTimeJitter
import com.vocabreminder.android.scheduler.ReviewResponse
import com.vocabreminder.android.scheduler.Scheduler
import com.vocabreminder.android.sync.SyncWorker
import java.time.Instant

class ReviewActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderNotifications.ACTION_REVIEW) return

        val cardId = intent.getStringExtra(ReminderNotifications.EXTRA_CARD_ID) ?: return
        val response = intent.getStringExtra(ReminderNotifications.EXTRA_RESPONSE)
            ?.let(ReviewResponse::valueOf)
            ?: return

        val store = LocalVocabularyStore(context)
        val card = store.findCard(cardId)
        if (card == null) {
            ReminderNotifications.clearStalePrompt(context, store)
            return
        }
        val now = Instant.now()
        val settings = store.settings().toSchedulerSettings()
        if (card.status == CardStatus.SUSPENDED || card.dueAt.isAfter(now)) {
            ReminderNotifications.clearActivePrompt(context, card.id)
            ReminderNotifications.cancel(context, card)
            ReminderNotifications.scheduleQueueAdvance(context)
            return
        }
        ReminderNotifications.clearActivePrompt(context, card.id)
        val result = DueTimeJitter.apply(
            Scheduler.applyReview(
                card = card.toSchedulerCard(),
                response = response,
                settings = settings,
                now = now,
                recentResponses = store.recentResponses(card.id, 2),
                unavailableCountToday = store.unavailableCountToday(card.id, settings.timezone, now),
                forgottenCountToday = store.forgottenCountToday(card.id, settings.timezone, now)
            ),
            now,
            settings
        )

        val updated = card.withSchedulerCard(result.card, now)
        store.updateCard(updated)
        store.appendEvent(result.event, now)

        when (response) {
            ReviewResponse.REMEMBERED -> ReminderNotifications.showRememberedConfirmation(context, updated)
            ReviewResponse.FORGOTTEN -> ReminderNotifications.showForgottenMeaning(context, updated)
            ReviewResponse.UNAVAILABLE -> ReminderNotifications.cancel(context, updated)
            else -> ReminderNotifications.cancel(context, updated)
        }

        ReminderScheduler.schedule(context, updated, now)
        ReminderNotifications.scheduleQueueAdvance(
            context,
            delayMillis = when (response) {
                ReviewResponse.REMEMBERED -> 5_000
                ReviewResponse.FORGOTTEN -> 8_000
                else -> 0
            }
        )
        SyncWorker.enqueueDebounced(context)
    }
}
