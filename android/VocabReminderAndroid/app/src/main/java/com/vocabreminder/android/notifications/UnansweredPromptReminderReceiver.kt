package com.vocabreminder.android.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.data.UserSettingsData
import com.vocabreminder.android.scheduler.Scheduler
import java.time.Duration
import java.time.Instant

class UnansweredPromptReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_UNANSWERED_PROMPT) return
        val cardId = intent.getStringExtra(EXTRA_CARD_ID) ?: return
        val expectedPostedAt = intent.getLongExtra(EXTRA_EXPECTED_POSTED_AT, -1L)
        if (expectedPostedAt < 0L) return
        UnansweredPromptReminderScheduler.handle(context, cardId, expectedPostedAt)
    }

    companion object {
        const val ACTION_UNANSWERED_PROMPT = "com.vocabreminder.android.ACTION_UNANSWERED_PROMPT"
        const val EXTRA_CARD_ID = "unanswered_card_id"
        const val EXTRA_EXPECTED_POSTED_AT = "unanswered_expected_posted_at"
    }
}

object UnansweredPromptReminderScheduler {
    val REMINDER_INTERVAL: Duration = Duration.ofHours(4)
    const val MAX_REMINDERS_PER_DAY = 2
    private const val REQUEST_CODE_OFFSET = 0x4000_0000

    fun schedule(context: Context, state: ActiveNotificationState) {
        if (state.phase != NotificationPhase.PROMPT) {
            cancel(context, state.cardId)
            return
        }
        val settings = LocalVocabularyStore(context).settings()
        val target = nextTrigger(state, settings)
        scheduleAt(context, state, target)
    }

    fun cancel(context: Context, cardId: String) {
        pendingIntent(context, cardId, 0L, PendingIntent.FLAG_NO_CREATE)?.let { pendingIntent ->
            context.getSystemService(AlarmManager::class.java).cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    fun isDue(state: ActiveNotificationState, now: Instant): Boolean {
        return !now.isBefore(state.postedAt.plus(REMINDER_INTERVAL))
    }

    fun canRemindToday(state: ActiveNotificationState, settings: UserSettingsData, now: Instant): Boolean {
        val today = now.atZone(settings.timezone).toLocalDate()
        return state.reminderDate != today || state.reminderCount < MAX_REMINDERS_PER_DAY
    }

    internal fun handle(
        context: Context,
        cardId: String,
        expectedPostedAtMillis: Long,
        now: Instant = Instant.now()
    ): Boolean {
        val state = ReminderNotifications.activeNotificationState(context) ?: return false
        if (state.cardId != cardId || state.phase != NotificationPhase.PROMPT) return false
        if (state.postedAt.toEpochMilli() != expectedPostedAtMillis) return false

        val store = LocalVocabularyStore(context)
        if (ReminderNotifications.clearStalePrompt(context, store, now)) return false
        val settings = store.settings()
        if (!isDue(state, now)) {
            scheduleAt(context, state, state.postedAt.plus(REMINDER_INTERVAL))
            return false
        }
        if (QuietHoursBoundaryScheduler.isQuietHour(now, settings)) {
            scheduleAt(context, state, Scheduler.avoidQuietHours(now, settings.toSchedulerSettings()))
            return false
        }
        if (!canRemindToday(state, settings, now)) {
            scheduleAt(context, state, nextDayRelease(now, settings))
            return false
        }
        ReminderNotifications.ensureChannel(context)
        if (!ReminderNotifications.canNotify(context)) {
            scheduleAt(context, state, now.plus(REMINDER_INTERVAL))
            return false
        }

        val card = store.findCard(cardId) ?: return false
        val shown = ReminderNotifications.showUnansweredPromptReminder(context, card, now)
        if (!shown) scheduleAt(context, state, now.plus(REMINDER_INTERVAL))
        return shown
    }

    private fun nextTrigger(state: ActiveNotificationState, settings: UserSettingsData): Instant {
        val candidate = if (state.reminderCount >= MAX_REMINDERS_PER_DAY) {
            nextDayRelease(state.postedAt, settings)
        } else {
            state.postedAt.plus(REMINDER_INTERVAL)
        }
        return if (QuietHoursBoundaryScheduler.isQuietHour(candidate, settings)) {
            Scheduler.avoidQuietHours(candidate, settings.toSchedulerSettings())
        } else {
            candidate
        }
    }

    private fun nextDayRelease(now: Instant, settings: UserSettingsData): Instant {
        val candidate = now.atZone(settings.timezone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(settings.timezone)
            .toInstant()
        return if (QuietHoursBoundaryScheduler.isQuietHour(candidate, settings)) {
            Scheduler.avoidQuietHours(candidate, settings.toSchedulerSettings())
        } else {
            candidate
        }
    }

    private fun scheduleAt(context: Context, state: ActiveNotificationState, triggerAt: Instant) {
        val pendingIntent = pendingIntent(
            context,
            state.cardId,
            state.postedAt.toEpochMilli(),
            PendingIntent.FLAG_UPDATE_CURRENT
        ) ?: return
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt.toEpochMilli(), pendingIntent)
    }

    private fun pendingIntent(
        context: Context,
        cardId: String,
        expectedPostedAtMillis: Long,
        flag: Int
    ): PendingIntent? {
        val intent = Intent(context, UnansweredPromptReminderReceiver::class.java)
            .setAction(UnansweredPromptReminderReceiver.ACTION_UNANSWERED_PROMPT)
            .putExtra(UnansweredPromptReminderReceiver.EXTRA_CARD_ID, cardId)
            .putExtra(UnansweredPromptReminderReceiver.EXTRA_EXPECTED_POSTED_AT, expectedPostedAtMillis)
        return PendingIntent.getBroadcast(
            context,
            cardId.hashCode() xor REQUEST_CODE_OFFSET,
            intent,
            flag or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
