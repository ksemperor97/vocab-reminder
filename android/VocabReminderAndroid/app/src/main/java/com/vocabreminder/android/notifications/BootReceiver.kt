package com.vocabreminder.android.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.sync.SyncWorker

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        Thread {
            try {
                BootRecoveryCoordinator.recover(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}

object BootRecoveryCoordinator {
    fun recover(context: Context): PlanSummary {
        SyncWorker.ensureScheduled(context)
        val store = LocalVocabularyStore(context)
        ReminderNotifications.clearStalePrompt(context, store)
        ReminderNotifications.restoreActiveNotification(context, store)
        val summary = DailyReviewPlanner.planAndSchedule(context)
        QuietHoursBoundaryScheduler.schedule(context)
        return summary
    }
}
