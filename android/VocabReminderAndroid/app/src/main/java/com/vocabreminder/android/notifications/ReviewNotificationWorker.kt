package com.vocabreminder.android.notifications

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.scheduler.CardStatus
import com.vocabreminder.android.scheduler.Wordbook
import java.time.Instant

class ReviewNotificationWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        val cardId = inputData.getString(KEY_CARD_ID) ?: return Result.failure()
        val store = LocalVocabularyStore(applicationContext)
        val card = store.findCard(cardId) ?: return Result.success()

        if (card.status == CardStatus.MASTERED || card.status == CardStatus.SUSPENDED || card.meaningKo.isBlank()) {
            return Result.success()
        }

        if (card.dueAt.isAfter(Instant.now().plusSeconds(30))) {
            ReminderScheduler.schedule(applicationContext, card)
            return Result.success()
        }

        if (card.wordbook == Wordbook.RECOMMENDED && card.recommendationStartedAt == null) {
            ReminderNotifications.showRecommendation(applicationContext, card)
        } else {
            ReminderNotifications.showReview(applicationContext, card)
        }
        return Result.success()
    }

    companion object {
        const val KEY_CARD_ID = "card_id"
    }
}
