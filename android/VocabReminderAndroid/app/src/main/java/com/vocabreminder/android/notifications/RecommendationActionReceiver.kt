package com.vocabreminder.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.data.VocabularyCard
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
        val now = NotificationClock.now()
        val activeState = ReminderNotifications.activeNotificationState(context)
        if (activeState == null || activeState.cardId != card.id || activeState.phase != NotificationPhase.PROMPT) {
            if (activeState?.cardId == card.id && activeState.phase == NotificationPhase.FEEDBACK) {
                ReminderNotifications.restoreActiveNotification(context, store, now)
            }
            return
        }
        if (card.status == CardStatus.SUSPENDED || card.dueAt.isAfter(now)) {
            ReminderNotifications.clearActivePrompt(context, card.id)
            ReminderNotifications.cancel(context, card)
            ReminderNotifications.scheduleQueueAdvance(context, now = now)
            return
        }

        applyRecommendationAction(context, store, card, action, now)
    }

    companion object {
        internal fun applyRecommendationAction(
            context: Context,
            store: LocalVocabularyStore,
            card: VocabularyCard,
            action: String,
            now: Instant = NotificationClock.now()
        ) {
            when (action) {
                ReminderNotifications.RECOMMENDATION_START -> {
                    // [시작하기] reveals the meaning, then moves the recommendation into review.
                    store.markRecommendationStarted(card.id, now)
                    val updated = store.findCard(card.id) ?: return
                    ReminderNotifications.showRecommendationMeaning(context, updated, now)
                    ReminderScheduler.schedule(context, updated, now)
                    SyncWorker.enqueueImmediate(context)
                }
                ReminderNotifications.RECOMMENDATION_KNOWN -> {
                    ReminderNotifications.clearActivePrompt(context, card.id)
                    store.softDeleteRecommendation(card.id, "known", now)
                    ReminderNotifications.cancel(context, card)
                    ReminderNotifications.scheduleQueueAdvance(context, now = now)
                    SyncWorker.enqueueImmediate(context)
                }
                ReminderNotifications.RECOMMENDATION_DISMISS -> {
                    ReminderNotifications.clearActivePrompt(context, card.id)
                    store.softDeleteRecommendation(card.id, "dismissed", now)
                    ReminderNotifications.cancel(context, card)
                    ReminderNotifications.scheduleQueueAdvance(context, now = now)
                    SyncWorker.enqueueImmediate(context)
                }
            }
        }
    }
}
