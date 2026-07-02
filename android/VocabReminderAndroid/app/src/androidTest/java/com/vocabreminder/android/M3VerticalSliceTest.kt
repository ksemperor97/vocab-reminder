package com.vocabreminder.android

import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.data.VocabularyCard
import com.vocabreminder.android.dictionary.DictionaryResolver
import com.vocabreminder.android.notifications.RecommendationActionReceiver
import com.vocabreminder.android.notifications.ReminderNotifications
import com.vocabreminder.android.notifications.ReviewActionReceiver
import com.vocabreminder.android.scheduler.CardStatus
import com.vocabreminder.android.scheduler.ReviewResponse
import com.vocabreminder.android.scheduler.Wordbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class M3VerticalSliceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var store: LocalVocabularyStore

    @Before
    fun setUp() {
        composeRule.activityRule.scenario.onActivity { activity ->
            store = LocalVocabularyStore(activity)
            store.clearAll()
        }
    }

    @Test
    fun manualAddCreatesCardDueInTenMinutes() {
        composeRule.onNodeWithTag("word_field").performTextInput("tentative")
        composeRule.onNodeWithTag("meaning_field").performTextInput("temporary")
        composeRule.onNodeWithTag("example_field").performTextInput("The results are tentative.")
        composeRule.onNodeWithTag("save_button").performClick()

        val card = store.cards().single()
        val dueInMinutes = Duration.between(Instant.now(), card.dueAt).toMinutes()
        assertEquals("tentative", card.displayWord)
        assertEquals("temporary", card.meaningKo)
        assertEquals(CardStatus.NEW, card.status)
        assertEquals(0, card.intervalDays)
        assertTrue("first due should be about 10 minutes, was $dueInMinutes", dueInMinutes in 8..11)
    }

    @Test
    fun sentenceCaptureShowsCandidateSelectionAndSavesPreview() {
        composeRule.onNodeWithTag("word_field").performTextInput("The results are tentative, but the pattern is robust.")
        composeRule.onNodeWithTag("preview_button").performClick()
        composeRule.onNodeWithTag("candidate_tentative").performClick()
        composeRule.onNodeWithTag("preview_save_button").performClick()

        val card = store.cards().single()
        assertEquals("tentative", card.lemma)
        assertEquals("잠정적인, 임시의", card.meaningKo)
        assertEquals("The results are tentative, but the pattern is robust.", store.contexts().single().sourceSentence)
        assertEquals("local_dictionary", store.definitionCandidates().single().source)
    }

    @Test
    fun capturedRecommendedWordPromotesToUserWordbookAndKeepsProgress() {
        val now = Instant.now()
        val recommended = store.addRecommendedCard(
            result = DictionaryResolver.resolve("ubiquitous"),
            captureMethod = com.vocabreminder.android.capture.CaptureMethod.RECOMMENDATION,
            now = now
        )
        val progressed = recommended.copy(
            status = CardStatus.LEARNING,
            intervalDays = 3,
            lapseCount = 1,
            dueAt = now.plusSeconds(3 * 24 * 60 * 60)
        )
        store.updateCard(progressed)

        val captured = store.addResolvedCard(
            result = DictionaryResolver.resolve("Ubiquitous"),
            captureMethod = com.vocabreminder.android.capture.CaptureMethod.ANDROID_PROCESS_TEXT,
            dueAt = now.plusSeconds(600),
            now = now.plusSeconds(1),
            wordbook = Wordbook.USER
        )

        assertEquals(1, store.cards().size)
        assertEquals(Wordbook.USER, captured.wordbook)
        assertEquals(CardStatus.LEARNING, captured.status)
        assertEquals(3, captured.intervalDays)
        assertEquals(1, captured.lapseCount)
    }

    @Test
    fun recommendationStartKeepsCardAndRecordsFeedback() {
        val now = Instant.now()
        val recommended = store.addRecommendedCard(
            result = DictionaryResolver.resolve("ubiquitous"),
            captureMethod = com.vocabreminder.android.capture.CaptureMethod.RECOMMENDATION,
            now = now
        )

        sendRecommendationAction(recommended, ReminderNotifications.RECOMMENDATION_START)

        val started = store.findCard(recommended.id)!!
        val dueInMinutes = Duration.between(Instant.now(), started.dueAt).toMinutes()
        assertEquals(Wordbook.RECOMMENDED, started.wordbook)
        assertTrue(started.recommendationStartedAt != null)
        assertTrue("started recommendation should enter review cycle in about 10 minutes, was $dueInMinutes", dueInMinutes in 8..11)
        assertEquals("started", store.recommendationFeedback().single().action)
    }

    @Test
    fun recommendationKnownAndDismissedSoftDeleteCardsAndRecordFeedback() {
        val now = Instant.now()
        val known = store.addRecommendedCard(
            result = DictionaryResolver.resolve("ubiquitous"),
            captureMethod = com.vocabreminder.android.capture.CaptureMethod.RECOMMENDATION,
            now = now
        )
        val dismissed = store.addRecommendedCard(
            result = DictionaryResolver.resolve("tentative"),
            captureMethod = com.vocabreminder.android.capture.CaptureMethod.RECOMMENDATION,
            now = now
        )

        sendRecommendationAction(known, ReminderNotifications.RECOMMENDATION_KNOWN)
        sendRecommendationAction(dismissed, ReminderNotifications.RECOMMENDATION_DISMISS)

        assertNull(store.findCard(known.id))
        assertNull(store.findCard(dismissed.id))
        assertEquals(0, store.cards().count { it.wordbook == Wordbook.RECOMMENDED })
        assertTrue(store.allCards(includeDeleted = true).all { it.deletedAt != null })
        assertEquals(listOf("known", "dismissed"), store.recommendationFeedback().map { it.action })
    }

    @Test
    fun notificationActionsPersistEventsAndUpdateDueState() {
        val now = Instant.now()
        val card = store.addCard(
            displayWord = "tentative",
            meaningKo = "temporary",
            exampleSentence = "The results are tentative.",
            dueAt = now.minusSeconds(1),
            now = now
        )

        sendAction(card, ReviewResponse.FORGOTTEN)

        val forgottenCard = store.findCard(card.id)!!
        val forgottenDue = Duration.between(Instant.now(), forgottenCard.dueAt).toMinutes()
        assertEquals(CardStatus.RELEARNING, forgottenCard.status)
        assertEquals(0, forgottenCard.intervalDays)
        assertEquals(1, forgottenCard.lapseCount)
        assertTrue("forgotten recheck should be 10-30 minutes, was $forgottenDue", forgottenDue in 9..30)
        assertEquals(ReviewResponse.FORGOTTEN, store.events().last().response)

        sendAction(forgottenCard, ReviewResponse.REMEMBERED)

        val rememberedCard = store.findCard(card.id)!!
        assertEquals(CardStatus.LEARNING, rememberedCard.status)
        assertTrue(rememberedCard.intervalDays >= 1)
        assertEquals(ReviewResponse.REMEMBERED, store.events().last().response)

        val beforeSnoozeStatus = rememberedCard.status
        val beforeSnoozeInterval = rememberedCard.intervalDays
        sendAction(rememberedCard, ReviewResponse.UNAVAILABLE)

        val snoozedCard = store.findCard(card.id)!!
        val snoozedDue = Duration.between(Instant.now(), snoozedCard.dueAt).toMinutes()
        assertEquals(beforeSnoozeStatus, snoozedCard.status)
        assertEquals(beforeSnoozeInterval, snoozedCard.intervalDays)
        assertTrue("snooze should be about 60 minutes, was $snoozedDue", snoozedDue in 58..61)
        assertEquals(ReviewResponse.UNAVAILABLE, store.events().last().response)
    }

    @Test
    fun reviewNotificationHidesMeaningUntilForgotten() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("pm grant ${composeRule.activity.packageName} android.permission.POST_NOTIFICATIONS")
        device.executeShellCommand("cmd notification cancel-all")
        Thread.sleep(500)

        val now = Instant.now()
        val card = store.addCard(
            displayWord = "tentative",
            meaningKo = "temporary",
            exampleSentence = "The results are tentative.",
            dueAt = now,
            now = now
        )

        composeRule.activityRule.scenario.onActivity { activity ->
            ReminderNotifications.showReview(activity, card)
        }

        val recallDump = waitForNotificationDump(device, "android.title=String (tentative)")
        assertTrue(recallDump.contains("android.title=String (tentative)"))
        assertTrue(recallDump.contains("android.text=String (무슨 뜻이었지?)"))
        assertTrue("first recall notification must not expose meaning", !recallDump.contains("temporary"))

        sendAction(card, ReviewResponse.FORGOTTEN)
        val forgottenDump = waitForNotificationDump(device, "android.text=String (뜻: temporary)")
        assertTrue(forgottenDump.contains("android.text=String (뜻: temporary)"))
        assertEquals(ReviewResponse.FORGOTTEN, store.events().last().response)
    }

    private fun sendAction(card: VocabularyCard, response: ReviewResponse) {
        composeRule.activityRule.scenario.onActivity { activity ->
            ReviewActionReceiver().onReceive(
                activity,
                Intent(activity, ReviewActionReceiver::class.java)
                    .setAction(ReminderNotifications.ACTION_REVIEW)
                    .putExtra(ReminderNotifications.EXTRA_CARD_ID, card.id)
                    .putExtra(ReminderNotifications.EXTRA_RESPONSE, response.name)
            )
        }
    }

    private fun sendRecommendationAction(card: VocabularyCard, action: String) {
        composeRule.activityRule.scenario.onActivity { activity ->
            RecommendationActionReceiver().onReceive(
                activity,
                Intent(activity, RecommendationActionReceiver::class.java)
                    .setAction(ReminderNotifications.ACTION_RECOMMENDATION)
                    .putExtra(ReminderNotifications.EXTRA_CARD_ID, card.id)
                    .putExtra(ReminderNotifications.EXTRA_RECOMMENDATION_ACTION, action)
            )
        }
    }

    private fun waitForNotificationDump(device: UiDevice, expected: String): String {
        var dump = ""
        repeat(10) {
            dump = device.executeShellCommand("dumpsys notification --noredact")
            if (dump.contains(expected)) return dump
            Thread.sleep(500)
        }
        return dump
    }
}
