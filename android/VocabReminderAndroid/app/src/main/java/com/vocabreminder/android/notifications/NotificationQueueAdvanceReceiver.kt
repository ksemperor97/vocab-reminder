package com.vocabreminder.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.scheduler.CardStatus
import com.vocabreminder.android.scheduler.Wordbook
import java.time.Instant

class NotificationQueueAdvanceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ADVANCE_QUEUE) return
        when (intent.getStringExtra(ReminderNotifications.EXTRA_DISMISSED_KIND)) {
            ReminderNotifications.DISMISSED_KIND_FEEDBACK -> {
                advanceAfterFeedbackDismiss(context, intent.getStringExtra(ReminderNotifications.EXTRA_CARD_ID))
            }
            ReminderNotifications.DISMISSED_KIND_PROMPT -> {
                handlePromptDismiss(
                    context,
                    intent.getStringExtra(ReminderNotifications.EXTRA_CARD_ID),
                    goAsync()
                )
            }
            else -> {
                intent.getStringExtra(ReminderNotifications.EXTRA_CARD_ID)?.let { cardId ->
                    ReminderNotifications.clearActivePrompt(context, cardId)
                }
                DailyReviewPlanner.planAndSchedule(context)
            }
        }
    }

    private fun handlePromptDismiss(
        context: Context,
        cardId: String?,
        pendingResult: BroadcastReceiver.PendingResult?
    ) {
        if (cardId == null) {
            DailyReviewPlanner.planAndSchedule(context)
            pendingResult?.finish()
            return
        }

        val state = ReminderNotifications.activeNotificationState(context)
        if (state?.cardId != cardId || state.phase != NotificationPhase.PROMPT) {
            pendingResult?.finish()
            return
        }
        Handler(Looper.getMainLooper()).postDelayed(
            {
                try {
                    completePromptDismissAfterGrace(
                        context = context.applicationContext,
                        cardId = cardId,
                        expectedPostedAtMillis = state.postedAt.toEpochMilli()
                    )
                } finally {
                    pendingResult?.finish()
                }
            },
            PROMPT_DISMISS_GRACE_MILLIS
        )
    }

    companion object {
        const val ACTION_ADVANCE_QUEUE = "com.vocabreminder.android.ACTION_ADVANCE_NOTIFICATION_QUEUE"
        internal const val PROMPT_DISMISS_GRACE_MILLIS = 750L

        internal fun completePromptDismissAfterGrace(
            context: Context,
            cardId: String,
            expectedPostedAtMillis: Long,
            now: Instant = Instant.now()
        ) {
            val state = ReminderNotifications.activeNotificationState(context) ?: return
            if (state.cardId != cardId ||
                state.phase != NotificationPhase.PROMPT ||
                state.postedAt.toEpochMilli() != expectedPostedAtMillis
            ) return

            val store = LocalVocabularyStore(context)
            val card = store.findCard(cardId)
            if (card == null) {
                ReminderNotifications.clearStalePrompt(context, store, now)
                DailyReviewPlanner.planAndSchedule(context, now)
                return
            }

            if (card.dueAt.isAfter(now)) {
                ReminderNotifications.clearStalePrompt(context, store, now)
                DailyReviewPlanner.planAndSchedule(context, now)
                return
            }

            if (card.status == CardStatus.SUSPENDED) {
                ReminderNotifications.clearActivePrompt(context, card.id)
                ReminderNotifications.cancel(context, card)
                DailyReviewPlanner.planAndSchedule(context, now)
                return
            }

            if (card.wordbook == Wordbook.RECOMMENDED && card.recommendationStartedAt == null) {
                ReminderNotifications.suppressRecommendationAfterSwipe(context, card.id, now)
                ReminderNotifications.clearActivePrompt(context, card.id)
                DailyReviewPlanner.planAndSchedule(context, now)
                return
            }

            ReviewActionReceiver.applyUnavailableResponse(context, store, card, now)
        }

        internal fun advanceAfterFeedbackDismiss(context: Context, cardId: String?) {
            if (cardId != null) {
                LocalVocabularyStore(context).findCard(cardId)?.let { card ->
                    ReminderNotifications.cancel(context, card)
                }
            }
            ReminderNotifications.clearActivePrompt(context, cardId)
            DailyReviewPlanner.planAndSchedule(context)
        }
    }
}
