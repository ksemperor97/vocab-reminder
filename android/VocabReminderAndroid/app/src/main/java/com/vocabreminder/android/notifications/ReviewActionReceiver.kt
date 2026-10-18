package com.vocabreminder.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.data.VocabularyCard
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
        applyReviewResponse(context, store, card, response, now)
    }

    companion object {
        internal fun applyUnavailableResponse(
            context: Context,
            store: LocalVocabularyStore,
            card: VocabularyCard,
            now: Instant = NotificationClock.now()
        ) {
            applyReviewResponse(context, store, card, ReviewResponse.UNAVAILABLE, now)
        }

        internal fun applyReviewResponse(
            context: Context,
            store: LocalVocabularyStore,
            card: VocabularyCard,
            response: ReviewResponse,
            now: Instant
        ) {
            val settings = store.settings().toSchedulerSettings()
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
            store.updateCardAndAppendEvent(updated, result.event, now)

            when (response) {
                ReviewResponse.REMEMBERED -> ReminderNotifications.showRememberedConfirmation(context, updated, now)
                ReviewResponse.FORGOTTEN -> ReminderNotifications.showForgottenMeaning(context, updated, now)
                ReviewResponse.UNAVAILABLE -> {
                    ReminderNotifications.clearActivePrompt(context, updated.id)
                    ReminderNotifications.cancel(context, updated)
                }
                else -> {
                    ReminderNotifications.clearActivePrompt(context, updated.id)
                    ReminderNotifications.cancel(context, updated)
                }
            }

            ReminderScheduler.schedule(context, updated, now)
            if (response != ReviewResponse.REMEMBERED && response != ReviewResponse.FORGOTTEN) {
                ReminderNotifications.scheduleQueueAdvance(context, now = now)
            }
            SyncWorker.enqueueImmediate(context)
        }
    }
}
