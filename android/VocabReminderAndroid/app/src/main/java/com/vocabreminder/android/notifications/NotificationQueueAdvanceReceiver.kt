package com.vocabreminder.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NotificationQueueAdvanceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ADVANCE_QUEUE) return
        DailyReviewPlanner.planAndSchedule(context)
    }

    companion object {
        const val ACTION_ADVANCE_QUEUE = "com.vocabreminder.android.ACTION_ADVANCE_NOTIFICATION_QUEUE"
    }
}
