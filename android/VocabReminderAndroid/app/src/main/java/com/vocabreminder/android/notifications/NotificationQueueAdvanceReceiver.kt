package com.vocabreminder.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.scheduler.CardStatus
import com.vocabreminder.android.scheduler.Wordbook
import java.time.Instant

class NotificationQueueAdvanceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ADVANCE_QUEUE) return
        when (intent.getStringExtra(ReminderNotifications.EXTRA_DISMISSED_KIND)) {
            ReminderNotifications.DISMISSED_KIND_FEEDBACK -> {
                val cardId = intent.getStringExtra(ReminderNotifications.EXTRA_CARD_ID)
                if (cardId != null) {
                    LocalVocabularyStore(context).findCard(cardId)?.let { card ->
                        ReminderNotifications.cancel(context, card)
                    }
                }
                ReminderNotifications.clearActivePrompt(context, cardId)
                DailyReviewPlanner.planAndSchedule(context)
            }
            ReminderNotifications.DISMISSED_KIND_PROMPT -> {
                handlePromptDismiss(context, intent.getStringExtra(ReminderNotifications.EXTRA_CARD_ID))
            }
            else -> {
                intent.getStringExtra(ReminderNotifications.EXTRA_CARD_ID)?.let { cardId ->
                    ReminderNotifications.clearActivePrompt(context, cardId)
                }
                DailyReviewPlanner.planAndSchedule(context)
            }
        }
    }

    private fun handlePromptDismiss(context: Context, cardId: String?) {
        if (cardId == null) {
            DailyReviewPlanner.planAndSchedule(context)
            return
        }

        val store = LocalVocabularyStore(context)
        val card = store.findCard(cardId)
        if (card == null) {
            ReminderNotifications.clearStalePrompt(context, store)
            DailyReviewPlanner.planAndSchedule(context)
            return
        }

        val now = Instant.now()
        if (card.dueAt.isAfter(now)) {
            return
        }

        if (card.status == CardStatus.SUSPENDED) {
            ReminderNotifications.clearActivePrompt(context, card.id)
            ReminderNotifications.cancel(context, card)
            DailyReviewPlanner.planAndSchedule(context)
            return
        }

        if (card.wordbook == Wordbook.RECOMMENDED && card.recommendationStartedAt == null) {
            ReminderNotifications.clearActivePrompt(context, card.id)
            DailyReviewPlanner.planAndSchedule(context)
            return
        }

        ReviewActionReceiver.applyUnavailableResponse(context, store, card, now)
    }

    companion object {
        const val ACTION_ADVANCE_QUEUE = "com.vocabreminder.android.ACTION_ADVANCE_NOTIFICATION_QUEUE"
    }
}
