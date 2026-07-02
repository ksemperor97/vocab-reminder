package com.vocabreminder.android.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.scheduler.Wordbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
