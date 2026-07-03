package com.vocabreminder.android.notifications

import android.content.Context
import android.content.Intent
import android.Manifest
import android.app.Application
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
}
