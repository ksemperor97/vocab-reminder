package com.vocabreminder.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.data.VocabularyCard
import com.vocabreminder.android.scheduler.CardStatus
import com.vocabreminder.android.scheduler.ReviewResponse
import java.time.Instant

class WatchReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_WATCH_REPLY) return
        val cardId = intent.getStringExtra(ReminderNotifications.EXTRA_CARD_ID) ?: return
        val replyKind = intent.getStringExtra(ReminderNotifications.EXTRA_REPLY_KIND) ?: return
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(ReminderNotifications.WATCH_REPLY_RESULT_KEY)
            ?.toString()
            ?.trim()
            .orEmpty()
        val now = NotificationClock.now()

        if (replyKind == ReminderNotifications.REPLY_KIND_FEEDBACK) {
            NotificationQueueAdvanceReceiver.advanceAfterFeedbackDismiss(context, cardId, now)
            return
        }

        val store = LocalVocabularyStore(context)
        val card = store.findCard(cardId)
        if (card == null) {
            ReminderNotifications.clearStalePrompt(context, store)
            return
        }

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

        when (replyKind) {
            ReminderNotifications.REPLY_KIND_REVIEW_PROMPT -> handleReviewReply(context, store, card, reply, now)
            ReminderNotifications.REPLY_KIND_RECOMMENDATION_PROMPT -> handleRecommendationReply(context, store, card, reply, now)
        }
    }

    private fun handleReviewReply(
        context: Context,
        store: LocalVocabularyStore,
        card: VocabularyCard,
        reply: String,
        now: Instant
    ) {
        val response = when (normalizeReply(reply)) {
            in REMEMBERED_REPLIES -> ReviewResponse.REMEMBERED
            in FORGOTTEN_REPLIES -> ReviewResponse.FORGOTTEN
            in LATER_REPLIES -> ReviewResponse.UNAVAILABLE
            else -> null
        }
        if (response == null) {
            ReminderNotifications.showReview(context, card, COMMAND_HINT_REVIEW, now)
            return
        }
        ReviewActionReceiver.applyReviewResponse(context, store, card, response, now)
    }

    private fun handleRecommendationReply(
        context: Context,
        store: LocalVocabularyStore,
        card: VocabularyCard,
        reply: String,
        now: Instant
    ) {
        val action = when (normalizeReply(reply)) {
            in START_REPLIES -> ReminderNotifications.RECOMMENDATION_START
            in KNOWN_REPLIES -> ReminderNotifications.RECOMMENDATION_KNOWN
            in DISMISS_REPLIES -> ReminderNotifications.RECOMMENDATION_DISMISS
            else -> null
        }
        if (action == null) {
            ReminderNotifications.showRecommendation(context, card, COMMAND_HINT_RECOMMENDATION, now)
            return
        }
        RecommendationActionReceiver.applyRecommendationAction(context, store, card, action, now)
    }

    private fun normalizeReply(reply: String): String {
        return reply.filterNot { it.isWhitespace() || it in setOf(',', '.', '!', '?', '，', '。', '！', '？') }
    }

    companion object {
        const val ACTION_WATCH_REPLY = "com.vocabreminder.android.ACTION_WATCH_REPLY"
        private val REMEMBERED_REPLIES = setOf("네", "외움")
        private val FORGOTTEN_REPLIES = setOf("아니오", "잊음")
        private val LATER_REPLIES = setOf("나중에연락드리겠습니다", "나중에")
        private val START_REPLIES = setOf("네")
        private val KNOWN_REPLIES = setOf("알아요")
        private val DISMISS_REPLIES = setOf("아니오")
        private const val COMMAND_HINT_REVIEW = "Fit3 답장: 네 · 아니오 · 나중에 연락드리겠습니다"
        private const val COMMAND_HINT_RECOMMENDATION = "Fit3 답장: 네 · 알아요 · 아니오"
    }
}
