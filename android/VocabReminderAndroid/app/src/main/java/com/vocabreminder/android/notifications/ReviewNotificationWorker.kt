package com.vocabreminder.android.notifications

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class ReviewNotificationWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        val cardId = inputData.getString(KEY_CARD_ID) ?: return Result.failure()
        ReviewDueDispatcher.showDueOrReschedule(applicationContext, cardId)
        return Result.success()
    }

    companion object {
        const val KEY_CARD_ID = "card_id"
    }
}
