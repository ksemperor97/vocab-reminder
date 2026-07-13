package com.vocabreminder.android.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.data.UserSettingsData
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime

object QuietHoursBoundaryScheduler {
    const val ACTION_QUIET_START = "com.vocabreminder.android.ACTION_QUIET_START"
    const val ACTION_QUIET_END = "com.vocabreminder.android.ACTION_QUIET_END"

    private const val REQUEST_QUIET_START = 80_001
    private const val REQUEST_QUIET_END = 80_002

    fun schedule(context: Context, now: Instant = Instant.now()) {
        val settings = LocalVocabularyStore(context).settings()
        scheduleBoundary(context, ACTION_QUIET_START, REQUEST_QUIET_START, nextOccurrence(settings.quietHoursStart, settings, now))
        scheduleBoundary(context, ACTION_QUIET_END, REQUEST_QUIET_END, nextOccurrence(settings.quietHoursEnd, settings, now))

        if (isQuietHour(now, settings)) {
            withdrawActiveNotifications(context)
        }
    }

    fun handleBoundary(context: Context, action: String, now: Instant = Instant.now()) {
        when (action) {
            ACTION_QUIET_START -> withdrawActiveNotifications(context)
            ACTION_QUIET_END -> {
                val store = LocalVocabularyStore(context)
                ReminderNotifications.restoreActiveNotification(context, store, now)
                DailyReviewPlanner.planAndSchedule(context, now)
            }
        }
        schedule(context, now)
    }

    fun isQuietHour(now: Instant, settings: UserSettingsData): Boolean {
        val time = now.atZone(settings.timezone).toLocalTime()
        return isWithinQuietHours(time, settings.quietHoursStart, settings.quietHoursEnd)
    }

    fun withdrawActiveNotifications(context: Context) {
        val store = LocalVocabularyStore(context)
        val solicitedFeedbackCardId = ReminderNotifications.activeNotificationState(context)
            ?.takeIf { it.phase == NotificationPhase.FEEDBACK }
            ?.cardId
        store.cards()
            .filterNot { it.id == solicitedFeedbackCardId }
            .forEach { ReminderNotifications.cancel(context, it) }
        ReminderNotifications.cancelRecommendationIntro(context)
    }

    private fun scheduleBoundary(context: Context, action: String, requestCode: Int, triggerAt: Instant) {
        val intent = Intent(context, QuietHoursReceiver::class.java).setAction(action)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt.toEpochMilli(), pendingIntent)
    }

    private fun nextOccurrence(time: LocalTime, settings: UserSettingsData, now: Instant): Instant {
        val localNow = LocalDateTime.ofInstant(now, settings.timezone)
        var candidate = localNow.toLocalDate().atTime(time)
        if (!candidate.isAfter(localNow)) {
            candidate = candidate.plusDays(1)
        }
        return candidate.atZone(settings.timezone).toInstant()
    }

    private fun isWithinQuietHours(time: LocalTime, start: LocalTime, end: LocalTime): Boolean {
        return if (start < end) {
            !time.isBefore(start) && time < end
        } else {
            !time.isBefore(start) || time < end
        }
    }
}
