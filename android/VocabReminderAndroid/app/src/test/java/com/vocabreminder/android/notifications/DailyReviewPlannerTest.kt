package com.vocabreminder.android.notifications

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.vocabreminder.android.R
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
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DailyReviewPlannerTest {
    private val defaultNow = Instant.parse("2026-07-11T01:00:00Z") // 10:00 Asia/Seoul
    private lateinit var context: Context
    private lateinit var store: LocalVocabularyStore

    @Before
    fun setUp() {
        NotificationClock.replaceForTest(Clock.fixed(defaultNow, ZoneOffset.UTC))
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        store = LocalVocabularyStore(context)
        store.clearAll()
        ReminderNotifications.clearActivePrompt(context)
        shadowOf(notificationManager()).setNotificationsEnabled(true)
        notificationManager().cancelAll()
    }

    @After
    fun tearDown() {
        NotificationClock.resetForTest()
    }

    private fun importServerRecommendations(now: Instant, count: Int = 10) {
        val cards = org.json.JSONArray()
        repeat(count) { index ->
            cards.put(org.json.JSONObject().put("id", "server-recommendation-$index")
                .put("lemma", "serveronlyword$index").put("display_word", "serveronlyword$index")
                .put("meaning_ko", "서버 뜻 $index").put("status", "new").put("wordbook", "recommended")
                .put("due_at", now.toString()).put("created_at", now.toString()).put("updated_at", now.toString())
                .put("version", 1).put("created_device_id", "recommendation-server"))
        }
        store.importSyncBatchJson(org.json.JSONObject().put("cards", cards).toString())
    }

    @Test
    fun quietHoursKeepSyncedRecommendationsWithoutPostingPrompts() {
        val now = Instant.parse("2026-07-03T15:00:00Z")
        importServerRecommendations(now)
        val summary = DailyReviewPlanner.planAndSchedule(context, now)
        assertEquals(0, summary.generatedRecommendations)
        assertEquals(0, summary.scheduledNotifications)
        assertEquals(10, store.cards().size)
        assertNull(ReminderNotifications.activePromptCardId(context))
    }

    @Test
    fun newDayNeverCreatesLocalRecommendations() {
        val now = Instant.parse("2026-07-03T00:00:00Z")
        importServerRecommendations(now, 1)
        val card = store.cards().single()
        store.markRecommendationStarted(card.id, now)
        val tomorrow = now.plusSeconds(86400)
        store.updateCard(store.findCard(card.id)!!.copy(dueAt = tomorrow.plusSeconds(86400)))
        assertEquals(0, DailyReviewPlanner.planAndSchedule(context, tomorrow).generatedRecommendations)
        assertEquals(1, store.cards().size)
    }

    @Test
    fun emptyWordbookWaitsForServerInsteadOfGeneratingLocalWords() {
        val summary = DailyReviewPlanner.planAndSchedule(context, defaultNow)
        assertEquals(0, summary.generatedRecommendations)
        assertEquals(0, summary.scheduledNotifications)
        assertTrue(store.cards().isEmpty())
    }

    @Test
    fun plannerDisplaysWordsAbsentFromTheOldBundledCatalogue() {
        importServerRecommendations(defaultNow)
        val summary = DailyReviewPlanner.planAndSchedule(context, defaultNow)
        assertEquals(0, summary.generatedRecommendations)
        assertEquals(10, summary.scheduledNotifications)
        assertEquals(10, store.cards().size)
        assertTrue(store.cards().all { it.meaningKo.startsWith("서버 뜻") })
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
        store.saveSettings(store.settings().copy(enableWordRecommendations = false))
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
        val immediate = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SyncWorker.IMMEDIATE_WORK)
            .get()
        assertEquals(1, immediate.size)
        assertEquals(WorkInfo.State.ENQUEUED, immediate.first().state)
    }

    @Test
    fun rememberedMeaningWaitsForDismissWithoutTimeoutOrQueueAlarm() {
        store.saveSettings(store.settings().copy(enableWordRecommendations = false))
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val firstPromptId = seedDueQueueAndShowFirstPrompt()

        sendReviewAction(firstPromptId, ReviewResponse.REMEMBERED)

        val updated = store.findCard(firstPromptId)!!
        val notification = notificationFor(updated.notificationId)
        assertEquals(0L, notification.timeoutAfter)
        assertNotNull(notification.deleteIntent)
        assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(firstPromptId, ReminderNotifications.activePromptCardId(context))
        assertEquals(NotificationPhase.FEEDBACK, ReminderNotifications.activeNotificationState(context)?.phase)
        assertEquals(
            NotificationFeedbackKind.REMEMBERED,
            ReminderNotifications.activeNotificationState(context)?.feedbackKind
        )
        assertFalse(hasQueueAdvanceAlarm())

        DailyReviewPlanner.planAndSchedule(context)

        assertEquals(firstPromptId, ReminderNotifications.activePromptCardId(context))
        assertEquals(1, postedNotificationCount())
    }

    @Test
    fun feedbackDeleteIntentClearsSlotAndShowsNextPrompt() {
        store.saveSettings(store.settings().copy(enableWordRecommendations = false))
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
    fun promptDeleteBeforeActionWaitsAndDoesNotOverrideRememberedResponse() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val firstPromptId = seedDueQueueAndShowFirstPrompt()
        val firstCard = store.findCard(firstPromptId)!!
        val promptNotification = notificationFor(firstCard.notificationId)
        val promptState = ReminderNotifications.activeNotificationState(context)!!

        NotificationManagerCompat.from(context).cancel(firstCard.notificationId)
        fireDeleteIntent(promptNotification, completePromptDismiss = false)
        sendReviewAction(firstPromptId, ReviewResponse.REMEMBERED)
        NotificationQueueAdvanceReceiver.completePromptDismissAfterGrace(
            context,
            firstPromptId,
            promptState.postedAt.toEpochMilli()
        )

        val events = store.events().filter { it.cardId == firstPromptId }
        assertEquals(1, events.size)
        assertEquals(ReviewResponse.REMEMBERED, events.single().response)
        assertEquals(NotificationPhase.FEEDBACK, ReminderNotifications.activeNotificationState(context)?.phase)
        assertEquals(firstPromptId, ReminderNotifications.activePromptCardId(context))
    }

    @Test
    fun duplicateRememberedBroadcastKeepsFeedbackAndWritesOneEvent() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val firstPromptId = seedDueQueueAndShowFirstPrompt()

        sendReviewAction(firstPromptId, ReviewResponse.REMEMBERED)
        sendReviewAction(firstPromptId, ReviewResponse.REMEMBERED)

        val state = ReminderNotifications.activeNotificationState(context)
        assertEquals(1, store.events().count { it.cardId == firstPromptId })
        assertEquals(firstPromptId, state?.cardId)
        assertEquals(NotificationPhase.FEEDBACK, state?.phase)
        assertEquals(NotificationFeedbackKind.REMEMBERED, state?.feedbackKind)
        assertNotNull(notificationFor(store.findCard(firstPromptId)!!.notificationId))
    }

    @Test
    fun duplicateRecommendationStartBroadcastKeepsMeaningAndWritesOneDecision() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val recommendationId = seedRecommendationPrompt()
        val startIntent = Intent(context, RecommendationActionReceiver::class.java)
            .setAction(ReminderNotifications.ACTION_RECOMMENDATION)
            .putExtra(ReminderNotifications.EXTRA_CARD_ID, recommendationId)
            .putExtra(
                ReminderNotifications.EXTRA_RECOMMENDATION_ACTION,
                ReminderNotifications.RECOMMENDATION_START
            )

        RecommendationActionReceiver().onReceive(context, startIntent)
        RecommendationActionReceiver().onReceive(context, startIntent)

        val state = ReminderNotifications.activeNotificationState(context)
        assertEquals(listOf("started"), store.recommendationFeedback().map { it.action })
        assertEquals(recommendationId, state?.cardId)
        assertEquals(NotificationPhase.FEEDBACK, state?.phase)
        assertEquals(NotificationFeedbackKind.RECOMMENDATION, state?.feedbackKind)
        assertNotNull(notificationFor(store.findCard(recommendationId)!!.notificationId))
    }

    @Test
    fun recommendationStartMeaningAdvancesOnlyAfterFeedbackDismiss() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val now = Instant.parse("2026-07-03T00:00:00Z")
        val farFuture = NotificationClock.now().plusSeconds(7 * 24 * 60 * 60)
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
        val now = NotificationClock.now()
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
    fun recommendationSwipeSuppressesCardForTwentyFourHoursAndAdvancesWithoutFeedback() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val now = NotificationClock.now()
        addFutureUserCards(count = 50, now = now)
        store.markRecommendationsIntroduced(now)
        importServerRecommendations(now)
        DailyReviewPlanner.planAndSchedule(context, now)
        val dismissedId = requireNotNull(ReminderNotifications.activePromptCardId(context))
        val dismissedCard = store.findCard(dismissedId)!!
        assertEquals(Wordbook.RECOMMENDED, dismissedCard.wordbook)
        val notification = notificationFor(dismissedCard.notificationId)

        NotificationManagerCompat.from(context).cancel(dismissedCard.notificationId)
        fireDeleteIntent(notification)

        val nextId = ReminderNotifications.activePromptCardId(context)
        assertNotNull(nextId)
        assertNotEquals(dismissedId, nextId)
        assertNotNull(store.findCard(dismissedId))
        assertTrue(store.events().isEmpty())
        assertTrue(store.recommendationFeedback().isEmpty())
        assertTrue(ReminderNotifications.isRecommendationSuppressed(context, dismissedId, now.plusSeconds(60)))
        assertFalse(ReviewDueDispatcher.showDueOrReschedule(context, dismissedId, now.plusSeconds(60)))
        assertEquals(nextId, ReminderNotifications.activePromptCardId(context))
        assertNull(shadowOf(notificationManager()).getNotification(dismissedCard.notificationId))

        // Device-local SharedPreferences survive store/process reconstruction, then expire lazily.
        val reconstructedStore = LocalVocabularyStore(context)
        assertNotNull(reconstructedStore.findCard(dismissedId))
        assertTrue(ReminderNotifications.isRecommendationSuppressed(context, dismissedId, now.plusSeconds(60)))
        assertFalse(ReminderNotifications.isRecommendationSuppressed(context, dismissedId, now.plusSeconds(25 * 60 * 60)))
    }

    @Test
    fun watchReviewReplyCommandsMatchNotificationActions() {
        listOf(
            "네" to ReviewResponse.REMEMBERED,
            "외움" to ReviewResponse.REMEMBERED,
            "아니오" to ReviewResponse.FORGOTTEN,
            "잊음" to ReviewResponse.FORGOTTEN,
            "나중에 연락드리겠습니다" to ReviewResponse.UNAVAILABLE,
            "나중에" to ReviewResponse.UNAVAILABLE
        ).forEachIndexed { index, (reply, expected) ->
            if (index > 0) setUp()
            shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            assertWatchReviewReply(reply, expected)
        }
    }

    @Test
    fun watchRecommendationReplyCommandsMatchNotificationActions() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val startId = seedRecommendationPrompt()

        fireWatchReply(startId, ReminderNotifications.REPLY_KIND_RECOMMENDATION_PROMPT, "네")

        val started = store.findCard(startId)!!
        assertNotNull(started.recommendationStartedAt)
        assertEquals(listOf("started"), store.recommendationFeedback().map { it.action })
        assertEquals(startId, ReminderNotifications.activePromptCardId(context))

        setUp()
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val knownId = seedRecommendationPrompt()

        fireWatchReply(knownId, ReminderNotifications.REPLY_KIND_RECOMMENDATION_PROMPT, "알아요")

        assertNull(store.findCard(knownId))
        assertEquals(listOf("known"), store.recommendationFeedback().map { it.action })

        setUp()
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val dismissedId = seedRecommendationPrompt()

        fireWatchReply(dismissedId, ReminderNotifications.REPLY_KIND_RECOMMENDATION_PROMPT, "아니오")

        assertNull(store.findCard(dismissedId))
        assertEquals(listOf("dismissed"), store.recommendationFeedback().map { it.action })
    }

    @Test
    fun unknownWatchReplyRepublishesPromptWithoutChangingState() {
        listOf("ㅋㅋ", "감사합니다", "네, 감사합니다", "좋습니다", "아니").forEachIndexed { index, reply ->
            if (index > 0) setUp()
            assertUnknownWatchReplyRepublishesPrompt(reply)
        }
    }

    @Test
    fun phoneActionsStayButtonsAndWatchReplyLivesInWearableExtension() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val firstPromptId = seedDueQueueAndShowFirstPrompt()
        val promptNotification = notificationFor(store.findCard(firstPromptId)!!.notificationId)

        assertEquals(listOf("외움", "잊음", "나중에"), promptNotification.actionTitles())

        val promptInput = promptNotification.singleWearableReplyRemoteInput()
        assertTrue(promptInput.allowFreeFormInput)
        assertEquals(listOf("네", "아니오", "나중에 연락드리겠습니다"), promptInput.choices!!.map { it.toString() })

        sendReviewAction(firstPromptId, ReviewResponse.REMEMBERED)

        val feedbackNotification = notificationFor(store.findCard(firstPromptId)!!.notificationId)
        assertEquals(listOf("다음"), feedbackNotification.actionTitles())
        val feedbackInput = feedbackNotification.singleWearableReplyRemoteInput()
        assertTrue(feedbackInput.allowFreeFormInput)
        assertEquals(listOf("다음"), feedbackInput.choices!!.map { it.toString() })
    }

    @Test
    fun lastCardWatchAnswerImmediatelyShowsMeaningWithPhoneNextButton() {
        store.saveSettings(store.settings().copy(enableWordRecommendations = false))
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val now = NotificationClock.now()
        val card = store.addCard(
            displayWord = "lastword",
            meaningKo = "마지막 뜻",
            exampleSentence = "The last card still reveals its meaning.",
            dueAt = now,
            now = now.minusSeconds(1)
        )
        assertTrue(ReminderNotifications.showReview(context, card, now = now))

        fireWatchReply(card.id, ReminderNotifications.REPLY_KIND_REVIEW_PROMPT, "네")

        val feedback = notificationFor(card.notificationId)
        assertEquals(NotificationPhase.FEEDBACK, ReminderNotifications.activeNotificationState(context)?.phase)
        assertEquals(NotificationFeedbackKind.REMEMBERED, ReminderNotifications.activeNotificationState(context)?.feedbackKind)
        assertTrue(feedback.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("마지막 뜻"))
        assertEquals(R.layout.notification_feedback_compact, feedback.contentView.layoutId)
        assertEquals(listOf("다음"), feedback.actionTitles())
        assertEquals(0, feedback.flags and Notification.FLAG_ONLY_ALERT_ONCE)

        fireFeedbackNextAction(feedback)

        assertNull(ReminderNotifications.activeNotificationState(context))
        assertEquals(0, postedNotificationCount())
    }

    @Test
    fun futureCardWatchReplyDoesNotWriteEvent() {
        store.saveSettings(store.settings().copy(enableWordRecommendations = false))
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val now = NotificationClock.now()
        val card = store.addCard(
            displayWord = "futurereply",
            meaningKo = "meaning",
            exampleSentence = "Future reply example.",
            dueAt = now,
            now = now.minusSeconds(1)
        )
        DailyReviewPlanner.planAndSchedule(context, now)
        store.updateCard(card.copy(dueAt = now.plusSeconds(3600), updatedAt = now, version = card.version + 1))

        fireWatchReply(card.id, ReminderNotifications.REPLY_KIND_REVIEW_PROMPT, "네")

        assertEquals(0, store.events().size)
        assertNull(ReminderNotifications.activePromptCardId(context))
    }

    @Test
    fun feedbackWatchReplyAdvancesQueueAndDuplicateDeleteIntentDoesNotDoublePost() {
        store.saveSettings(store.settings().copy(enableWordRecommendations = false))
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val firstPromptId = seedDueQueueAndShowFirstPrompt()
        sendReviewAction(firstPromptId, ReviewResponse.REMEMBERED)
        val updated = store.findCard(firstPromptId)!!
        val meaningNotification = notificationFor(updated.notificationId)

        fireWatchReply(firstPromptId, ReminderNotifications.REPLY_KIND_FEEDBACK, "나중에")

        val nextPromptId = ReminderNotifications.activePromptCardId(context)
        assertNotNull(nextPromptId)
        assertNotEquals(firstPromptId, nextPromptId)
        assertEquals(1, postedNotificationCount())

        fireDeleteIntent(meaningNotification)

        assertEquals(nextPromptId, ReminderNotifications.activePromptCardId(context))
        assertEquals(1, postedNotificationCount())
    }

    @Test
    fun watchReplySettingRemovesRemoteInputAction() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        store.saveSettings(store.settings().copy(enableWatchReplyReview = false))
        val firstPromptId = seedDueQueueAndShowFirstPrompt()

        val notification = notificationFor(store.findCard(firstPromptId)!!.notificationId)

        assertFalse(notification.actionTitles().contains("답장"))
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
    fun immediateSyncStaleCleanupKeepsFutureDatedFeedbackVisible() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val firstPromptId = seedDueQueueAndShowFirstPrompt()

        sendReviewAction(firstPromptId, ReviewResponse.FORGOTTEN)
        val updated = store.findCard(firstPromptId)!!
        val now = NotificationClock.now()
        val cleared = ReminderNotifications.clearStalePrompt(context, store, now)
        DailyReviewPlanner.planAndSchedule(context, now)

        val state = ReminderNotifications.activeNotificationState(context)
        assertFalse(cleared)
        assertTrue(updated.dueAt.isAfter(now))
        assertEquals(firstPromptId, state?.cardId)
        assertEquals(NotificationPhase.FEEDBACK, state?.phase)
        assertEquals(NotificationFeedbackKind.FORGOTTEN, state?.feedbackKind)
        assertNotNull(shadowOf(notificationManager()).getNotification(updated.notificationId))
    }

    @Test
    fun feedbackIsRestoredAfterProcessLossEvenDuringQuietHours() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val firstPromptId = seedDueQueueAndShowFirstPrompt()
        sendReviewAction(firstPromptId, ReviewResponse.REMEMBERED)
        val updated = store.findCard(firstPromptId)!!
        val stateBefore = ReminderNotifications.activeNotificationState(context)!!
        val restoreAt = stateBefore.postedAt.atZone(store.settings().timezone)
            .toLocalDate()
            .atTime(23, 30)
            .atZone(store.settings().timezone)
            .toInstant()

        NotificationManagerCompat.from(context).cancel(updated.notificationId)
        assertNull(shadowOf(notificationManager()).getNotification(updated.notificationId))

        assertTrue(ReminderNotifications.restoreActiveNotification(context, store, restoreAt))
        assertNotNull(shadowOf(notificationManager()).getNotification(updated.notificationId))
        assertEquals(NotificationPhase.FEEDBACK, ReminderNotifications.activeNotificationState(context)?.phase)
        assertEquals(NotificationFeedbackKind.REMEMBERED, ReminderNotifications.activeNotificationState(context)?.feedbackKind)
    }

    @Test
    fun feedbackActionDuringQuietHoursImmediatelyShowsSolicitedMeaning() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val promptAt = Instant.parse("2026-07-11T13:59:00Z") // 22:59 Asia/Seoul
        val quietResponseAt = promptAt.plusSeconds(2 * 60)
        val card = store.addCard(
            displayWord = "boundary-feedback",
            meaningKo = "경계 피드백",
            exampleSentence = "Feedback waits until quiet hours end.",
            dueAt = promptAt,
            now = promptAt.minusSeconds(1)
        )
        assertTrue(ReminderNotifications.showReview(context, card, now = promptAt))

        ReviewActionReceiver.applyReviewResponse(
            context,
            store,
            card,
            ReviewResponse.REMEMBERED,
            quietResponseAt
        )

        val state = ReminderNotifications.activeNotificationState(context)
        assertEquals(NotificationPhase.FEEDBACK, state?.phase)
        assertEquals(NotificationFeedbackKind.REMEMBERED, state?.feedbackKind)
        assertNotNull(shadowOf(notificationManager()).getNotification(card.notificationId))
    }

    @Test
    fun quietHoursUseExactAsiaSeoulBoundaries() {
        val settings = store.settings()
        val beforeStart = Instant.parse("2026-07-11T13:59:00Z") // 22:59 Asia/Seoul
        val atStart = Instant.parse("2026-07-11T14:00:00Z") // 23:00 Asia/Seoul
        val atMidnight = Instant.parse("2026-07-11T15:00:00Z") // 00:00 Asia/Seoul
        val beforeEnd = Instant.parse("2026-07-11T23:59:00Z") // 08:59 Asia/Seoul
        val atEnd = Instant.parse("2026-07-12T00:00:00Z") // 09:00 Asia/Seoul

        assertFalse(QuietHoursBoundaryScheduler.isQuietHour(beforeStart, settings))
        assertTrue(QuietHoursBoundaryScheduler.isQuietHour(atStart, settings))
        assertTrue(QuietHoursBoundaryScheduler.isQuietHour(atMidnight, settings))
        assertTrue(QuietHoursBoundaryScheduler.isQuietHour(beforeEnd, settings))
        assertFalse(QuietHoursBoundaryScheduler.isQuietHour(atEnd, settings))
    }

    @Test
    fun promptRestorePreservesOriginalFourHourClock() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val postedAt = Instant.parse("2026-07-11T01:00:00Z")
        val card = store.addCard(
            displayWord = "clock-preserved",
            meaningKo = "시계 보존",
            exampleSentence = "Restore does not restart the clock.",
            dueAt = postedAt,
            now = postedAt.minusSeconds(1)
        )
        assertTrue(ReminderNotifications.showReview(context, card, now = postedAt))
        NotificationManagerCompat.from(context).cancel(card.notificationId)

        assertTrue(ReminderNotifications.restoreActiveNotification(context, store, postedAt.plusSeconds(60 * 60)))

        assertEquals(postedAt, ReminderNotifications.activeNotificationState(context)?.postedAt)
    }

    @Test
    fun legacyActivePromptMigratesTimestampAndRestoresReminderAlarmEvenWhenNotificationIsActive() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val postedAt = Instant.parse("2026-07-11T01:00:00Z")
        val card = store.addCard(
            displayWord = "legacy-prompt",
            meaningKo = "레거시",
            exampleSentence = "Legacy state is migrated.",
            dueAt = postedAt,
            now = postedAt.minusSeconds(1)
        )
        assertTrue(ReminderNotifications.showReview(context, card, now = postedAt))
        UnansweredPromptReminderScheduler.cancel(context, card.id)
        context.getSharedPreferences("vocab_notification_queue", Context.MODE_PRIVATE)
            .edit()
            .remove("active_phase")
            .remove("active_feedback_kind")
            .remove("active_posted_at")
            .remove("active_reminder_count")
            .remove("active_reminder_date")
            .commit()

        assertTrue(ReminderNotifications.restoreActiveNotification(context, store, postedAt.plusSeconds(60)))

        assertNotNull(ReminderNotifications.activeNotificationState(context))
        assertTrue(hasUnansweredPromptAlarm(card.id))
    }

    @Test
    fun unansweredPromptRemindsEveryFourHoursAtMostTwicePerDayWithoutReviewEvent() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val firstPostedAt = Instant.parse("2026-07-11T01:00:00Z") // 10:00 Asia/Seoul
        val card = store.addCard(
            displayWord = "unanswered",
            meaningKo = "미응답",
            exampleSentence = "An unanswered prompt returns later.",
            dueAt = firstPostedAt,
            now = firstPostedAt.minusSeconds(1)
        )
        assertTrue(ReminderNotifications.showReview(context, card, now = firstPostedAt))

        var state = ReminderNotifications.activeNotificationState(context)!!
        assertTrue(
            UnansweredPromptReminderScheduler.handle(
                context,
                card.id,
                state.postedAt.toEpochMilli(),
                firstPostedAt.plusSeconds(4 * 60 * 60)
            )
        )
        state = ReminderNotifications.activeNotificationState(context)!!
        assertEquals(1, state.reminderCount)

        assertTrue(
            UnansweredPromptReminderScheduler.handle(
                context,
                card.id,
                state.postedAt.toEpochMilli(),
                state.postedAt.plusSeconds(4 * 60 * 60)
            )
        )
        state = ReminderNotifications.activeNotificationState(context)!!
        assertEquals(2, state.reminderCount)

        assertFalse(
            UnansweredPromptReminderScheduler.handle(
                context,
                card.id,
                state.postedAt.toEpochMilli(),
                state.postedAt.plusSeconds(4 * 60 * 60)
            )
        )
        assertEquals(2, ReminderNotifications.activeNotificationState(context)?.reminderCount)
        assertTrue(store.events().isEmpty())
    }

    @Test
    fun unansweredPromptDoesNotRemindDuringQuietHours() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val postedAt = Instant.parse("2026-07-11T11:00:00Z") // 20:00 Asia/Seoul
        val card = store.addCard(
            displayWord = "quietunanswered",
            meaningKo = "조용한 미응답",
            exampleSentence = "Quiet hours remain quiet.",
            dueAt = postedAt,
            now = postedAt.minusSeconds(1)
        )
        assertTrue(ReminderNotifications.showReview(context, card, now = postedAt))
        val state = ReminderNotifications.activeNotificationState(context)!!

        val shown = UnansweredPromptReminderScheduler.handle(
            context,
            card.id,
            state.postedAt.toEpochMilli(),
            postedAt.plusSeconds(4 * 60 * 60)
        )

        assertFalse(shown)
        assertEquals(0, ReminderNotifications.activeNotificationState(context)?.reminderCount)
        assertTrue(store.events().isEmpty())
    }

    @Test
    fun plannerDoesNotRefillRecommendationAlreadyProcessedToday() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val now = Instant.parse("2026-07-11T01:00:00Z")
        addFutureUserCards(count = 50, now = now)

        importServerRecommendations(now)
        assertEquals(0, DailyReviewPlanner.planAndSchedule(context, now).generatedRecommendations)
        val processed = store.cards().first { it.wordbook == Wordbook.RECOMMENDED }
        ReminderNotifications.clearActivePrompt(context)
        store.softDeleteRecommendation(processed.id, "known", now.plusSeconds(60))

        val second = DailyReviewPlanner.planAndSchedule(context, now.plusSeconds(120))

        assertEquals(0, second.generatedRecommendations)
        assertEquals(9, store.cards().count { it.wordbook == Wordbook.RECOMMENDED })
        assertEquals(1, store.recommendationFeedback().map { it.cardId }.distinct().size)
    }

    @Test
    fun plannerReconcilesFutureCardIntoWorkManagerAndAlarmManager() {
        val now = Instant.parse("2026-07-11T01:00:00Z")
        val card = store.addCard(
            displayWord = "future-sync-card",
            meaningKo = "미래 동기화 카드",
            exampleSentence = "Future cards still receive an alarm.",
            dueAt = now.plusSeconds(6 * 60 * 60),
            now = now
        )

        DailyReviewPlanner.planAndSchedule(context, now)

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("review:${card.id}")
            .get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        assertTrue(
            shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.any { alarm ->
                val intent = shadowOf(alarm.operation).savedIntent
                intent.action == ReviewAlarmReceiver.ACTION_REVIEW_DUE &&
                    intent.getStringExtra(ReviewNotificationWorker.KEY_CARD_ID) == card.id
            }
        )
    }

    @Test
    fun quietHourDueDispatchDefersLocallyWithoutChangingSyncedCard() {
        val now = Instant.parse("2026-07-11T15:00:00Z") // 00:00 Asia/Seoul
        val card = store.addCard(
            displayWord = "quiet-due",
            meaningKo = "조용한 시간",
            exampleSentence = "Quiet deferral is local scheduling state.",
            dueAt = now,
            now = now.minusSeconds(60)
        )

        assertFalse(ReviewDueDispatcher.showDueOrReschedule(context, card.id, now))

        val after = store.findCard(card.id)!!
        assertEquals(card.dueAt, after.dueAt)
        assertEquals(card.version, after.version)
        assertNull(shadowOf(notificationManager()).getNotification(card.notificationId))
        assertTrue(
            shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.any { alarm ->
                val intent = shadowOf(alarm.operation).savedIntent
                intent.action == ReviewAlarmReceiver.ACTION_REVIEW_DUE &&
                    intent.getStringExtra(ReviewNotificationWorker.KEY_CARD_ID) == card.id
            }
        )
    }

    @Test
    fun alarmAndWorkerDuplicateDeliveryPostsOneAlertOnceNotification() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val now = Instant.parse("2026-07-11T01:00:00Z")
        val card = store.addCard(
            displayWord = "duplicate-trigger",
            meaningKo = "중복 방지",
            exampleSentence = "Two schedulers produce one notification.",
            dueAt = now,
            now = now.minusSeconds(1)
        )

        assertTrue(ReviewDueDispatcher.showDueOrReschedule(context, card.id, now))
        assertFalse(ReviewDueDispatcher.showDueOrReschedule(context, card.id, now))

        val notification = notificationFor(card.notificationId)
        assertEquals(1, postedNotificationCount())
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
    }

    @Test
    fun globallyBlockedNotificationSettingPreventsPersistentPromptClaim() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(notificationManager()).setNotificationsEnabled(false)
        val now = Instant.parse("2026-07-11T01:00:00Z")
        val card = store.addCard(
            displayWord = "blocked-channel",
            meaningKo = "차단됨",
            exampleSentence = "Blocked notifications stay blocked.",
            dueAt = now,
            now = now.minusSeconds(1)
        )

        assertFalse(ReminderNotifications.showReview(context, card, now = now))
        assertNull(ReminderNotifications.activeNotificationState(context))
        assertEquals(0, postedNotificationCount())
    }

    @Test
    fun blockedNotificationChannelPreventsPersistentPromptClaim() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notificationManager().deleteNotificationChannel(ReminderNotifications.CHANNEL_ID)
        notificationManager().createNotificationChannel(
            NotificationChannel(
                ReminderNotifications.CHANNEL_ID,
                "blocked",
                NotificationManager.IMPORTANCE_NONE
            )
        )
        val now = Instant.parse("2026-07-11T01:00:00Z")
        val card = store.addCard(
            displayWord = "blocked-channel-specific",
            meaningKo = "채널 차단됨",
            exampleSentence = "A blocked channel remains silent.",
            dueAt = now,
            now = now.minusSeconds(1)
        )

        assertFalse(ReminderNotifications.showReview(context, card, now = now))
        assertNull(ReminderNotifications.activeNotificationState(context))
        assertEquals(0, postedNotificationCount())
    }

    @Test
    fun staleReviewActionOnlyClearsNotificationWithoutWritingEvent() {
        store.saveSettings(store.settings().copy(enableWordRecommendations = false))
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val now = NotificationClock.now()
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

    @Test
    fun editingMissingMeaningSchedulesReviewWork() {
        val now = Instant.parse("2026-07-03T00:00:00Z")
        val card = store.addCard(
            displayWord = "holdword",
            meaningKo = "",
            exampleSentence = "Hold example.",
            dueAt = now.plusSeconds(600),
            now = now
        )

        val result = store.updateCardDetails(
            cardId = card.id,
            meaningKo = "채운 뜻",
            exampleSentence = "Edited hold example.",
            now = now.plusSeconds(10)
        )!!
        if (result.filledMissingMeaning) {
            ReminderScheduler.schedule(context, result.after, now)
        }

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("review:${card.id}")
            .get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.first().state)
    }

    @Test
    fun bootRecoverySchedulesSyncPlannerAndQuietHourBoundaries() {
        store.saveSettings(store.settings().copy(enableWordRecommendations = false))
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val now = NotificationClock.now()
        store.addCard(
            displayWord = "bootword",
            meaningKo = "부팅 뜻",
            exampleSentence = "Boot example.",
            dueAt = now.minusSeconds(60),
            now = now.minusSeconds(120)
        )

        val summary = BootRecoveryCoordinator.recover(context)
        val syncInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(SyncWorker.PERIODIC_WORK)
            .get()

        assertEquals(1, summary.scheduledNotifications)
        assertEquals(1, syncInfos.size)
        assertEquals(WorkInfo.State.ENQUEUED, syncInfos.first().state)
        assertTrue(hasQuietBoundaryAlarm(QuietHoursBoundaryScheduler.ACTION_QUIET_START))
        assertTrue(hasQuietBoundaryAlarm(QuietHoursBoundaryScheduler.ACTION_QUIET_END))
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

    private fun seedRecommendationPrompt(): String {
        val now = NotificationClock.now()
        addFutureUserCards(count = 50, now = now)
        store.markRecommendationsIntroduced(now)
        val recommendation = store.addCard(
            displayWord = "watchrecommend",
            meaningKo = "추천 뜻",
            exampleSentence = "Watch recommendation example.",
            dueAt = now,
            now = now.minusSeconds(1),
            wordbook = Wordbook.RECOMMENDED
        )
        assertTrue(ReminderNotifications.showRecommendation(context, recommendation))
        return recommendation.id
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

    private fun assertWatchReviewReply(reply: String, expected: ReviewResponse) {
        val firstPromptId = seedDueQueueAndShowFirstPrompt()

        fireWatchReply(firstPromptId, ReminderNotifications.REPLY_KIND_REVIEW_PROMPT, reply)

        val event = store.events().single { it.cardId == firstPromptId }
        assertEquals(expected, event.response)
        when (expected) {
            ReviewResponse.REMEMBERED,
            ReviewResponse.FORGOTTEN -> assertEquals(firstPromptId, ReminderNotifications.activePromptCardId(context))
            else -> assertNotEquals(firstPromptId, ReminderNotifications.activePromptCardId(context))
        }
    }

    private fun assertUnknownWatchReplyRepublishesPrompt(reply: String) {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val firstPromptId = seedDueQueueAndShowFirstPrompt()
        val before = store.findCard(firstPromptId)!!

        fireWatchReply(firstPromptId, ReminderNotifications.REPLY_KIND_REVIEW_PROMPT, reply)

        val after = store.findCard(firstPromptId)!!
        val notification = notificationFor(after.notificationId)
        assertEquals(0, store.events().size)
        assertEquals(before.dueAt, after.dueAt)
        assertEquals(firstPromptId, ReminderNotifications.activePromptCardId(context))
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Fit3 답장: 네"))
    }

    private fun fireWatchReply(cardId: String, replyKind: String, reply: String) {
        val intent = Intent(context, WatchReplyReceiver::class.java)
            .setAction(WatchReplyReceiver.ACTION_WATCH_REPLY)
            .putExtra(ReminderNotifications.EXTRA_CARD_ID, cardId)
            .putExtra(ReminderNotifications.EXTRA_REPLY_KIND, replyKind)
        val results = Bundle().apply {
            putCharSequence(ReminderNotifications.WATCH_REPLY_RESULT_KEY, reply)
        }
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(ReminderNotifications.WATCH_REPLY_RESULT_KEY).build()),
            intent,
            results
        )
        WatchReplyReceiver().onReceive(context, intent)
    }

    private fun notificationFor(notificationId: Int): Notification {
        return requireNotNull(shadowOf(notificationManager()).getNotification(notificationId))
    }

    private fun Notification.actionTitles(): List<String> {
        return actions?.map { it.title.toString() } ?: emptyList()
    }

    private fun Notification.singleWearableReplyRemoteInput(): RemoteInput {
        val replyAction = NotificationCompat.WearableExtender(this).actions.single { it.title.toString() == "답장" }
        return requireNotNull(replyAction.remoteInputs).single()
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

    private fun hasQuietBoundaryAlarm(action: String): Boolean {
        return shadowOf(context.getSystemService(AlarmManager::class.java))
            .scheduledAlarms
            .any { alarm ->
                shadowOf(alarm.operation).savedIntent.action == action
            }
    }

    private fun hasUnansweredPromptAlarm(cardId: String): Boolean {
        return shadowOf(context.getSystemService(AlarmManager::class.java))
            .scheduledAlarms
            .any { alarm ->
                val intent = shadowOf(alarm.operation).savedIntent
                intent.action == UnansweredPromptReminderReceiver.ACTION_UNANSWERED_PROMPT &&
                    intent.getStringExtra(UnansweredPromptReminderReceiver.EXTRA_CARD_ID) == cardId
            }
    }

    private fun fireDeleteIntent(notification: Notification, completePromptDismiss: Boolean = true) {
        val intent = shadowOf(notification.deleteIntent).savedIntent
        NotificationQueueAdvanceReceiver().onReceive(context, intent)
        if (completePromptDismiss &&
            intent.getStringExtra(ReminderNotifications.EXTRA_DISMISSED_KIND) == ReminderNotifications.DISMISSED_KIND_PROMPT
        ) {
            val cardId = requireNotNull(intent.getStringExtra(ReminderNotifications.EXTRA_CARD_ID))
            val state = requireNotNull(ReminderNotifications.activeNotificationState(context))
            NotificationQueueAdvanceReceiver.completePromptDismissAfterGrace(
                context = context,
                cardId = cardId,
                expectedPostedAtMillis = state.postedAt.toEpochMilli()
            )
        }
    }

    private fun fireFeedbackNextAction(notification: Notification) {
        val nextAction = requireNotNull(notification.actions).single { it.title.toString() == "다음" }
        val intent = shadowOf(nextAction.actionIntent).savedIntent
        NotificationQueueAdvanceReceiver().onReceive(context, intent)
    }

    private fun notificationManager(): NotificationManager {
        return context.getSystemService(NotificationManager::class.java)
    }
}
