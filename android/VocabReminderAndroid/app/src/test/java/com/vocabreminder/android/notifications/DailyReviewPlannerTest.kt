package com.vocabreminder.android.notifications

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.scheduler.ReviewResponse
import com.vocabreminder.android.scheduler.Wordbook
import com.vocabreminder.android.sync.SyncWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DailyReviewPlannerTest {
    private lateinit var context: Context
    private lateinit var store: LocalVocabularyStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        store = LocalVocabularyStore(context)
        store.clearAll()
        ReminderNotifications.clearActivePrompt(context)
    }

    @Test
    fun planAndScheduleDoesNotRecommendBeforeFiftyUserWords() {
        val now = Instant.parse("2026-07-03T00:00:00Z")
        addFutureUserCards(count = 49, now = now)

        val summary = DailyReviewPlanner.planAndSchedule(context, now)

        assertEquals(0, summary.generatedRecommendations)
        assertEquals(0, summary.scheduledNotifications)
        assertEquals(0, store.cards().count { it.wordbook == Wordbook.RECOMMENDED })
    }

    @Test
    fun planAndScheduleFillsRecommendationSlotsAfterFiftyUserWords() {
        val now = Instant.parse("2026-07-03T00:00:00Z")
        addFutureUserCards(count = 50, now = now)

        val summary = DailyReviewPlanner.planAndSchedule(context, now)
        val recommendations = store.cards().filter { it.wordbook == Wordbook.RECOMMENDED }

        assertEquals(10, summary.generatedRecommendations)
        assertEquals(10, summary.scheduledNotifications)
        assertEquals(0, summary.rebalancedCards)
        assertEquals(summary.generatedRecommendations, recommendations.size)
        assertTrue(recommendations.all { it.meaningKo.isNotBlank() })
        assertTrue(recommendations.all { it.recommendationStartedAt == null })
        if (summary.recommendationIntroShown) {
            assertEquals(now, store.settings().recommendationsIntroducedAt)
        } else {
            assertNull(store.settings().recommendationsIntroducedAt)
        }
    }

    @Test
    fun planAndScheduleDoesNotRecommendWhenDueReviewsFillDailyGoal() {
        val now = Instant.parse("2026-07-03T00:00:00Z")
        repeat(10) { index ->
            store.addCard(
                displayWord = "dueword$index",
                meaningKo = "meaning $index",
                exampleSentence = "Due example sentence $index.",
                dueAt = now,
                now = now.minusSeconds((index + 1).toLong())
            )
        }
        addFutureUserCards(count = 40, now = now.minusSeconds(100))

        val summary = DailyReviewPlanner.planAndSchedule(context, now)

        assertEquals(0, summary.generatedRecommendations)
        assertEquals(10, summary.scheduledNotifications)
        assertEquals(0, summary.rebalancedCards)
        assertEquals(0, store.cards().count { it.wordbook == Wordbook.RECOMMENDED })
    }

    @Test
    fun dueQueueShowsOnePromptAndAdvancesAfterAction() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val now = Instant.parse("2026-07-03T00:00:00Z")
        repeat(2) { index ->
            store.addCard(
                displayWord = "queueword$index",
                meaningKo = "meaning $index",
                exampleSentence = "Queue example sentence $index.",
                dueAt = now,
                now = now.minusSeconds((index + 1).toLong())
            )
        }

        val summary = DailyReviewPlanner.planAndSchedule(context, now)
        val firstPromptId = ReminderNotifications.activePromptCardId(context)

        assertEquals(2, summary.scheduledNotifications)
        assertNotNull(firstPromptId)

        ReviewActionReceiver().onReceive(
            context,
            Intent(context, ReviewActionReceiver::class.java)
                .setAction(ReminderNotifications.ACTION_REVIEW)
                .putExtra(ReminderNotifications.EXTRA_CARD_ID, firstPromptId)
                .putExtra(ReminderNotifications.EXTRA_RESPONSE, ReviewResponse.UNAVAILABLE.name)
        )

        val nextPromptId = ReminderNotifications.activePromptCardId(context)
        assertNotNull(nextPromptId)
        assertNotEquals(firstPromptId, nextPromptId)
        val debounced = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SyncWorker.DEBOUNCED_WORK)
            .get()
        assertEquals(1, debounced.size)
        assertEquals(WorkInfo.State.ENQUEUED, debounced.first().state)
    }

    @Test
    fun rememberedMeaningWaitsForDismissWithoutTimeoutOrQueueAlarm() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val firstPromptId = seedDueQueueAndShowFirstPrompt()

        sendReviewAction(firstPromptId, ReviewResponse.REMEMBERED)

        val updated = store.findCard(firstPromptId)!!
        val notification = notificationFor(updated.notificationId)
        assertEquals(0L, notification.timeoutAfter)
        assertNotNull(notification.deleteIntent)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(firstPromptId, ReminderNotifications.activePromptCardId(context))
        assertFalse(hasQueueAdvanceAlarm())

        DailyReviewPlanner.planAndSchedule(context)

        assertEquals(firstPromptId, ReminderNotifications.activePromptCardId(context))
        assertEquals(1, postedNotificationCount())
    }

    @Test
    fun feedbackDeleteIntentClearsSlotAndShowsNextPrompt() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val firstPromptId = seedDueQueueAndShowFirstPrompt()
        sendReviewAction(firstPromptId, ReviewResponse.FORGOTTEN)
        val updated = store.findCard(firstPromptId)!!
        val meaningNotification = notificationFor(updated.notificationId)

        NotificationManagerCompat.from(context).cancel(updated.notificationId)
        fireDeleteIntent(meaningNotification)

        val nextPromptId = ReminderNotifications.activePromptCardId(context)
        assertNotNull(nextPromptId)
        assertNotEquals(firstPromptId, nextPromptId)
        assertEquals(1, postedNotificationCount())
    }

    @Test
    fun reviewPromptDismissRecordsUnavailableAndShowsNextPrompt() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val firstPromptId = seedDueQueueAndShowFirstPrompt()
        val firstCard = store.findCard(firstPromptId)!!
        val promptNotification = notificationFor(firstCard.notificationId)

        NotificationManagerCompat.from(context).cancel(firstCard.notificationId)
        fireDeleteIntent(promptNotification)

        val unavailableEvents = store.events().filter { it.cardId == firstPromptId && it.response == ReviewResponse.UNAVAILABLE }
        val updated = store.findCard(firstPromptId)!!
        val nextPromptId = ReminderNotifications.activePromptCardId(context)
        assertEquals(1, unavailableEvents.size)
        assertTrue(updated.dueAt.isAfter(firstCard.dueAt))
        assertNotNull(nextPromptId)
        assertNotEquals(firstPromptId, nextPromptId)
    }

    @Test
    fun stalePromptDismissAfterActionDoesNotRecordDuplicateUnavailable() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val firstPromptId = seedDueQueueAndShowFirstPrompt()
        val firstCard = store.findCard(firstPromptId)!!
        val promptNotification = notificationFor(firstCard.notificationId)

        sendReviewAction(firstPromptId, ReviewResponse.REMEMBERED)
        fireDeleteIntent(promptNotification)

        val events = store.events().filter { it.cardId == firstPromptId }
        assertEquals(1, events.size)
        assertEquals(ReviewResponse.REMEMBERED, events.single().response)
        assertEquals(firstPromptId, ReminderNotifications.activePromptCardId(context))
    }

    @Test
    fun recommendationStartMeaningAdvancesOnlyAfterFeedbackDismiss() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val now = Instant.parse("2026-07-03T00:00:00Z")
        val farFuture = Instant.now().plusSeconds(7 * 24 * 60 * 60)
        repeat(50) { index ->
            store.addCard(
                displayWord = "futureuser$index",
                meaningKo = "meaning $index",
                exampleSentence = "Future example $index.",
                dueAt = farFuture,
                now = now.minusSeconds((index + 10).toLong())
            )
        }
        val recommendation = store.addCard(
            displayWord = "recommendme",
            meaningKo = "추천 뜻",
            exampleSentence = "Recommendation example.",
            dueAt = now,
            now = now.minusSeconds(1),
            wordbook = Wordbook.RECOMMENDED
        )
        val nextDue = store.addCard(
            displayWord = "nextdue",
            meaningKo = "다음 뜻",
            exampleSentence = "Next due example.",
            dueAt = now,
            now = now.minusSeconds(2)
        )

        assertTrue(ReminderNotifications.showRecommendation(context, recommendation))
        RecommendationActionReceiver().onReceive(
            context,
            Intent(context, RecommendationActionReceiver::class.java)
                .setAction(ReminderNotifications.ACTION_RECOMMENDATION)
                .putExtra(ReminderNotifications.EXTRA_CARD_ID, recommendation.id)
                .putExtra(ReminderNotifications.EXTRA_RECOMMENDATION_ACTION, ReminderNotifications.RECOMMENDATION_START)
        )

        val started = store.findCard(recommendation.id)!!
        val notification = notificationFor(started.notificationId)
        assertEquals(0L, notification.timeoutAfter)
        assertNotNull(notification.deleteIntent)
        assertEquals(recommendation.id, ReminderNotifications.activePromptCardId(context))
        assertFalse(hasQueueAdvanceAlarm())

        NotificationManagerCompat.from(context).cancel(started.notificationId)
        fireDeleteIntent(notification)

        assertEquals(nextDue.id, ReminderNotifications.activePromptCardId(context))
        assertEquals(listOf("started"), store.recommendationFeedback().map { it.action })
    }

    @Test
    fun startedRecommendationDoesNotReturnBeforeDueAfterMeaningDismiss() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val now = Instant.now()
        addFutureUserCards(count = 50, now = now)
        store.markRecommendationsIntroduced(now)
        val recommendation = store.addCard(
            displayWord = "startonly",
            meaningKo = "추천 뜻",
            exampleSentence = "Started recommendation example.",
            dueAt = now,
            now = now.minusSeconds(1),
            wordbook = Wordbook.RECOMMENDED
        )
        store.addCard(
            displayWord = "stillrecommend",
            meaningKo = "다른 추천 뜻",
            exampleSentence = "Another recommendation example.",
            dueAt = now,
            now = now.minusSeconds(2),
            wordbook = Wordbook.RECOMMENDED
        )

        assertTrue(ReminderNotifications.showRecommendation(context, recommendation))
        RecommendationActionReceiver().onReceive(
            context,
            Intent(context, RecommendationActionReceiver::class.java)
                .setAction(ReminderNotifications.ACTION_RECOMMENDATION)
                .putExtra(ReminderNotifications.EXTRA_CARD_ID, recommendation.id)
                .putExtra(ReminderNotifications.EXTRA_RECOMMENDATION_ACTION, ReminderNotifications.RECOMMENDATION_START)
        )

        val started = store.findCard(recommendation.id)!!
        val notification = notificationFor(started.notificationId)
        assertTrue(started.dueAt.isAfter(now))

        NotificationManagerCompat.from(context).cancel(started.notificationId)
        fireDeleteIntent(notification)

        val nextPromptId = ReminderNotifications.activePromptCardId(context)
        assertNotNull(nextPromptId)
        assertNotEquals(started.id, nextPromptId)
        val nextPrompt = store.findCard(nextPromptId!!)!!
        assertEquals(Wordbook.RECOMMENDED, nextPrompt.wordbook)
        assertNull(nextPrompt.recommendationStartedAt)
    }

    @Test
    fun recommendationPromptDismissDoesNotRecordFeedbackAndShowsNextPrompt() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val now = Instant.parse("2026-07-03T00:00:00Z")
        val recommendation = store.addCard(
            displayWord = "dismissrecommend",
            meaningKo = "추천 뜻",
            exampleSentence = "Dismiss recommendation example.",
            dueAt = now,
            now = now.minusSeconds(1),
            wordbook = Wordbook.RECOMMENDED
        )
        val nextDue = store.addCard(
            displayWord = "afterrecommend",
            meaningKo = "다음 뜻",
            exampleSentence = "After recommendation example.",
            dueAt = now,
            now = now.minusSeconds(2)
        )

        assertTrue(ReminderNotifications.showRecommendation(context, recommendation))
        val notification = notificationFor(recommendation.notificationId)

        NotificationManagerCompat.from(context).cancel(recommendation.notificationId)
        fireDeleteIntent(notification)

        assertTrue(store.recommendationFeedback().isEmpty())
        assertEquals(nextDue.id, ReminderNotifications.activePromptCardId(context))
    }

    @Test
    fun clearStalePromptCancelsFutureActivePromptAndAllowsNextDueCard() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val now = Instant.parse("2026-07-03T00:00:00Z")
        repeat(2) { index ->
            store.addCard(
                displayWord = "stalequeue$index",
                meaningKo = "meaning $index",
                exampleSentence = "Stale queue example $index.",
                dueAt = now,
                now = now.minusSeconds((index + 1).toLong())
            )
        }
        DailyReviewPlanner.planAndSchedule(context, now)
        val firstPromptId = ReminderNotifications.activePromptCardId(context)
        assertNotNull(firstPromptId)
        val firstCard = store.findCard(firstPromptId!!)!!
        store.updateCard(
            firstCard.copy(
                dueAt = now.plusSeconds(3600),
                updatedAt = now,
                version = firstCard.version + 1
            )
        )

        val cleared = ReminderNotifications.clearStalePrompt(context, store, now)
        DailyReviewPlanner.planAndSchedule(context, now)

        val nextPromptId = ReminderNotifications.activePromptCardId(context)
        assertTrue(cleared)
        assertNotNull(nextPromptId)
        assertNotEquals(firstPromptId, nextPromptId)
    }

    @Test
    fun clearStalePromptKeepsDueActivePrompt() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val now = Instant.parse("2026-07-03T00:00:00Z")
        store.addCard(
            displayWord = "freshprompt",
            meaningKo = "meaning",
            exampleSentence = "Fresh prompt example.",
            dueAt = now,
            now = now.minusSeconds(1)
        )
        DailyReviewPlanner.planAndSchedule(context, now)
        val activePromptId = ReminderNotifications.activePromptCardId(context)

        val cleared = ReminderNotifications.clearStalePrompt(context, store, now)

        assertFalse(cleared)
        assertEquals(activePromptId, ReminderNotifications.activePromptCardId(context))
    }

    @Test
    fun staleReviewActionOnlyClearsNotificationWithoutWritingEvent() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val now = Instant.now()
        val card = store.addCard(
            displayWord = "lateaction",
            meaningKo = "meaning",
            exampleSentence = "Late action example.",
            dueAt = now,
            now = now.minusSeconds(1)
        )
        DailyReviewPlanner.planAndSchedule(context, now)
        store.updateCard(card.copy(dueAt = now.plusSeconds(3600), updatedAt = now, version = card.version + 1))

        ReviewActionReceiver().onReceive(
            context,
            Intent(context, ReviewActionReceiver::class.java)
                .setAction(ReminderNotifications.ACTION_REVIEW)
                .putExtra(ReminderNotifications.EXTRA_CARD_ID, card.id)
                .putExtra(ReminderNotifications.EXTRA_RESPONSE, ReviewResponse.REMEMBERED.name)
        )

        assertEquals(0, store.events().size)
        assertNull(ReminderNotifications.activePromptCardId(context))
    }

    private fun addFutureUserCards(count: Int, now: Instant) {
        repeat(count) { index ->
            store.addCard(
                displayWord = "seedword$index",
                meaningKo = "meaning $index",
                exampleSentence = "Example sentence $index.",
                dueAt = now.plusSeconds(24 * 60 * 60),
                now = now.minusSeconds((index + 1).toLong())
            )
        }
    }

    private fun seedDueQueueAndShowFirstPrompt(): String {
        val now = Instant.parse("2026-07-03T00:00:00Z")
        repeat(2) { index ->
            store.addCard(
                displayWord = "flowword$index",
                meaningKo = "meaning $index",
                exampleSentence = "Flow example sentence $index.",
                dueAt = now,
                now = now.minusSeconds((index + 1).toLong())
            )
        }
        DailyReviewPlanner.planAndSchedule(context, now)
        return requireNotNull(ReminderNotifications.activePromptCardId(context))
    }

    private fun sendReviewAction(cardId: String, response: ReviewResponse) {
        ReviewActionReceiver().onReceive(
            context,
            Intent(context, ReviewActionReceiver::class.java)
                .setAction(ReminderNotifications.ACTION_REVIEW)
                .putExtra(ReminderNotifications.EXTRA_CARD_ID, cardId)
                .putExtra(ReminderNotifications.EXTRA_RESPONSE, response.name)
        )
    }

    private fun notificationFor(notificationId: Int): Notification {
        return requireNotNull(shadowOf(notificationManager()).getNotification(notificationId))
    }

    private fun postedNotificationCount(): Int {
        return shadowOf(notificationManager()).getAllNotifications().size
    }

    private fun hasQueueAdvanceAlarm(): Boolean {
        return shadowOf(context.getSystemService(AlarmManager::class.java))
            .scheduledAlarms
            .any { alarm ->
                shadowOf(alarm.operation).savedIntent.action == NotificationQueueAdvanceReceiver.ACTION_ADVANCE_QUEUE
            }
    }

    private fun fireDeleteIntent(notification: Notification) {
        val intent = shadowOf(notification.deleteIntent).savedIntent
        NotificationQueueAdvanceReceiver().onReceive(context, intent)
    }

    private fun notificationManager(): NotificationManager {
        return context.getSystemService(NotificationManager::class.java)
    }
}
