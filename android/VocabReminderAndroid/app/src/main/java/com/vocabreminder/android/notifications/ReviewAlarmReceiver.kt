package com.vocabreminder.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReviewAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REVIEW_DUE) return
        val cardId = intent.getStringExtra(ReviewNotificationWorker.KEY_CARD_ID) ?: return
        ReviewDueDispatcher.showDueOrReschedule(context, cardId)
    }

    companion object {
        const val ACTION_REVIEW_DUE = "com.vocabreminder.android.ACTION_REVIEW_DUE"
    }
}
