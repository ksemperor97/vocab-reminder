package com.vocabreminder.android.notifications

import android.content.Context
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.scheduler.CardStatus
import com.vocabreminder.android.scheduler.Scheduler
import com.vocabreminder.android.scheduler.SchedulerSettings
import com.vocabreminder.android.scheduler.Wordbook
import java.time.Instant

object ReviewDueDispatcher {
    fun showDueOrReschedule(context: Context, cardId: String, now: Instant = NotificationClock.now()): Boolean {
        val store = LocalVocabularyStore(context)
        val card = store.findCard(cardId) ?: return false
        val settings = store.settings().toSchedulerSettings()

        if (card.status == CardStatus.MASTERED || card.status == CardStatus.SUSPENDED || card.meaningKo.isBlank()) {
            ReminderScheduler.cancel(context, card)
            return false
        }

        if (card.dueAt.isAfter(now.plusSeconds(30))) {
            ReminderScheduler.schedule(context, card, now)
            return false
        }

        if (card.wordbook == Wordbook.RECOMMENDED && card.recommendationStartedAt == null) {
            val suppressedUntil = ReminderNotifications.recommendationSuppressedUntil(context, card.id, now)
            if (suppressedUntil != null) {
                ReminderNotifications.cancel(context, card)
                ReminderScheduler.scheduleAt(context, card, suppressedUntil, now)
                return false
            }
        }

        if (isQuietHour(now, settings)) {
            val releaseDue = Scheduler.avoidQuietHours(now, settings)
            ReminderNotifications.cancel(context, card)
            // Quiet-hours deferral is device-local scheduling state, not learning progress;
            // mutating the synced due/version here causes cross-device divergence.
            ReminderScheduler.scheduleAt(context, card, releaseDue, now)
            return false
        }

        if (card.wordbook == Wordbook.RECOMMENDED && card.recommendationStartedAt == null) {
            return ReminderNotifications.showRecommendation(context, card, now = now)
        } else {
            return ReminderNotifications.showReview(context, card, now = now)
        }
    }

    private fun isQuietHour(now: Instant, settings: SchedulerSettings): Boolean {
        val localTime = now.atZone(settings.timezone).toLocalTime()
        return if (settings.quietHoursStart < settings.quietHoursEnd) {
            !localTime.isBefore(settings.quietHoursStart) && localTime < settings.quietHoursEnd
        } else {
            !localTime.isBefore(settings.quietHoursStart) || localTime < settings.quietHoursEnd
        }
    }
}
