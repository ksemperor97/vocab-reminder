package com.vocabreminder.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.scheduler.CardStatus
import com.vocabreminder.android.sync.SyncWorker
import java.time.Instant

class RecommendationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderNotifications.ACTION_RECOMMENDATION) return

        val cardId = intent.getStringExtra(ReminderNotifications.EXTRA_CARD_ID) ?: return
        val action = intent.getStringExtra(ReminderNotifications.EXTRA_RECOMMENDATION_ACTION) ?: return
        val store = LocalVocabularyStore(context)
        val card = store.findCard(cardId)
        if (card == null) {
            ReminderNotifications.clearStalePrompt(context, store)
            return
        }
        val now = Instant.now()
        if (card.status == CardStatus.SUSPENDED || card.dueAt.isAfter(now)) {
            ReminderNotifications.clearActivePrompt(context, card.id)
            ReminderNotifications.cancel(context, card)
            ReminderNotifications.scheduleQueueAdvance(context)
            return
        }
        ReminderNotifications.clearActivePrompt(context, card.id)

        when (action) {
            ReminderNotifications.RECOMMENDATION_START -> {
                // [시작하기] reveals the meaning, then moves the recommendation into review.
                store.markRecommendationStarted(card.id, now)
                val updated = store.findCard(card.id) ?: return
                ReminderNotifications.showRecommendationMeaning(context, updated)
                ReminderScheduler.schedule(context, updated, now)
                ReminderNotifications.scheduleQueueAdvance(context, delayMillis = 8_000)
                SyncWorker.enqueueDebounced(context)
            }
            ReminderNotifications.RECOMMENDATION_KNOWN -> {
                store.softDeleteRecommendation(card.id, "known", now)
                ReminderNotifications.cancel(context, card)
                ReminderNotifications.scheduleQueueAdvance(context)
                SyncWorker.enqueueDebounced(context)
            }
            ReminderNotifications.RECOMMENDATION_DISMISS -> {
                store.softDeleteRecommendation(card.id, "dismissed", now)
                ReminderNotifications.cancel(context, card)
                ReminderNotifications.scheduleQueueAdvance(context)
                SyncWorker.enqueueDebounced(context)
            }
        }
    }
}
