package com.vocabreminder.android

import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.data.VocabularyCard
import com.vocabreminder.android.dictionary.DictionaryResolver
import com.vocabreminder.android.notifications.DailyReviewPlanner
import com.vocabreminder.android.notifications.RecommendationActionReceiver
import com.vocabreminder.android.notifications.ReminderNotifications
import com.vocabreminder.android.notifications.ReviewActionReceiver
import com.vocabreminder.android.notifications.ReviewAlarmReceiver
import com.vocabreminder.android.scheduler.CardStatus
import com.vocabreminder.android.scheduler.ReviewResponse
import com.vocabreminder.android.scheduler.Wordbook
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.io.File

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
        openAddSheet()
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
        openAddSheet()
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
    fun syncImportMergesDuplicateLemmaAndKeepsBothContexts() {
        val now = Instant.now()
        store.addCard(
            displayWord = "tentative",
            meaningKo = "temporary",
            exampleSentence = "The local result is tentative.",
            dueAt = now.plusSeconds(600),
            now = now
        )
        val payload = JSONObject()
            .put(
                "cards",
                JSONArray().put(
                    JSONObject()
                        .put("id", "remote-card")
                        .put("lemma", "tentative")
                        .put("display_word", "tentative")
                        .put("meaning_ko", "잠정적인")
                        .put("wordbook", "user")
                        .put("status", "new")
                        .put("due_at", now.plusSeconds(600).toString())
                        .put("interval_days", 0)
                        .put("lapse_count", 0)
                        .put("created_at", now.plusSeconds(1).toString())
                        .put("updated_at", now.plusSeconds(1).toString())
                        .put("version", 1)
                )
            )
            .put(
                "contexts",
                JSONArray().put(
                    JSONObject()
                        .put("id", "remote-context")
                        .put("card_id", "remote-card")
                        .put("source_sentence", "The remote result is tentative.")
                        .put("capture_method", "mac_clipboard_hotkey")
                        .put("created_at", now.plusSeconds(1).toString())
                )
            )

        store.importSyncBatchJson(payload.toString())

        val card = store.cards().single()
        val importedContexts = store.contexts()
        assertEquals("tentative", card.lemma)
        assertEquals(2, importedContexts.size)
        assertTrue(importedContexts.all { it.cardId == card.id })
        assertTrue(importedContexts.map { it.sourceSentence }.contains("The local result is tentative."))
        assertTrue(importedContexts.map { it.sourceSentence }.contains("The remote result is tentative."))
    }

    @Test
    fun localSyncButtonsExportAndImportSnapshot() {
        val now = Instant.now()
        val syncFile = File(composeRule.activity.getExternalFilesDir(null), "vocab-reminder-sync.json")
        syncFile.delete()
        store.addCard(
            displayWord = "tentative",
            meaningKo = "temporary",
            exampleSentence = "The local result is tentative.",
            dueAt = now.plusSeconds(600),
            now = now
        )

        openSyncSettings()
        composeRule.onNodeWithTag("local_sync_export_button").performScrollTo().performClick()

        assertTrue(syncFile.exists())
        assertTrue(syncFile.readText().contains("\"cards\""))

        store.clearAll()
        composeRule.onNodeWithTag("local_sync_import_button").performScrollTo().performClick()

        val card = store.cards().single()
        assertEquals("tentative", card.lemma)
        assertEquals("temporary", card.meaningKo)
    }

    @Test
    fun deleteButtonSoftDeletesUserCard() {
        openAddSheet()
        composeRule.onNodeWithTag("word_field").performTextInput("tentative")
        composeRule.onNodeWithTag("meaning_field").performTextInput("temporary")
        composeRule.onNodeWithTag("example_field").performTextInput("The results are tentative.")
        composeRule.onNodeWithTag("save_button").performClick()

        composeRule.onNodeWithTag("delete_card_tentative").performScrollTo().performClick()

        assertEquals(0, store.cards().size)
        assertTrue(store.allCards(includeDeleted = true).single().deletedAt != null)
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
    fun recommendationNotificationShowsIntroMeaningBeforeStarted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("pm grant ${composeRule.activity.packageName} android.permission.POST_NOTIFICATIONS")
        device.executeShellCommand("cmd notification cancel-all")
        Thread.sleep(500)

        val now = Instant.now()
        val recommended = store.addRecommendedCard(
            result = DictionaryResolver.resolve("mitigate"),
            captureMethod = com.vocabreminder.android.capture.CaptureMethod.RECOMMENDATION,
            now = now
        )

        composeRule.activityRule.scenario.onActivity { activity ->
            ReminderNotifications.showRecommendation(activity, recommended)
        }

        val firstDump = waitForNotificationDump(device, "android.title=String (오늘의 새 단어: mitigate)")
        assertTrue(firstDump.contains("알고 있는 단어인가요?"))
        assertTrue("recommendation intro should expose meaning as an introduction", firstDump.contains(recommended.meaningKo))

        sendRecommendationAction(recommended, ReminderNotifications.RECOMMENDATION_START)
        val startedDump = waitForNotificationDump(device, "android.text=String (뜻: ${recommended.meaningKo})")
        assertTrue(startedDump.contains("android.text=String (뜻: ${recommended.meaningKo})"))
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
    fun dailyPlannerGeneratesRecommendationSlotsAfterFiftyUserWords() {
        val now = Instant.parse("2026-07-03T00:00:00Z")
        repeat(50) { index ->
            store.addCard(
                displayWord = "seedword$index",
                meaningKo = "meaning $index",
                exampleSentence = "Example sentence $index.",
                dueAt = now.plusSeconds(24 * 60 * 60),
                now = now.minusSeconds((index + 1).toLong())
            )
        }

        val summary = DailyReviewPlanner.planAndSchedule(composeRule.activity, now)
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

    @Test
    fun alarmReceiverShowsDueReviewNotificationWithoutMeaning() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("pm grant ${composeRule.activity.packageName} android.permission.POST_NOTIFICATIONS")
        device.executeShellCommand("cmd notification cancel-all")
        Thread.sleep(500)

        val now = Instant.now()
        val card = store.addCard(
            displayWord = "salient",
            meaningKo = "noticeable",
            exampleSentence = "The salient cue was delayed.",
            dueAt = now.minusSeconds(1),
            now = now
        )

        composeRule.activityRule.scenario.onActivity { activity ->
            ReviewAlarmReceiver().onReceive(
                activity,
                Intent(activity, ReviewAlarmReceiver::class.java)
                    .setAction(ReviewAlarmReceiver.ACTION_REVIEW_DUE)
                    .putExtra("card_id", card.id)
            )
        }

        val dump = waitForNotificationDump(device, "android.title=String (salient)")
        assertTrue(dump.contains("android.text=String (무슨 뜻이었지?)"))
        assertTrue("alarm recall notification must not expose meaning", !dump.contains("noticeable"))
    }

    private fun openAddSheet() {
        composeRule.onNodeWithTag("add_word_fab").performClick()
    }

    private fun openSyncSettings() {
        composeRule.onNodeWithTag("tab_settings").performClick()
        composeRule.onNodeWithTag("open_sync_settings_button").performScrollTo().performClick()
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
