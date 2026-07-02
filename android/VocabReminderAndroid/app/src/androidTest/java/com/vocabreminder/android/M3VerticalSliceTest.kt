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
import com.vocabreminder.android.notifications.ReminderNotifications
import com.vocabreminder.android.notifications.ReviewActionReceiver
import com.vocabreminder.android.scheduler.CardStatus
import com.vocabreminder.android.scheduler.ReviewResponse
import org.junit.Assert.assertEquals
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
