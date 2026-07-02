package com.vocabreminder.android.sync

import com.vocabreminder.android.capture.CaptureMethod
import com.vocabreminder.android.data.StoredContext
import com.vocabreminder.android.data.StoredDefinitionCandidate
import com.vocabreminder.android.data.UserSettingsData
import com.vocabreminder.android.data.VocabularyCard
import com.vocabreminder.android.scheduler.CardStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class SyncPayloadFactoryTest {
    @Test
    fun exportsFirestoreShapedLowercasePayload() {
        val now = Instant.parse("2026-07-02T00:00:00Z")
        val payload = SyncPayloadFactory.build(
            cards = listOf(
                VocabularyCard(
                    id = "card-1",
                    lemma = "tentative",
                    displayWord = "tentative",
                    meaningKo = "잠정적인",
                    status = CardStatus.NEW,
                    dueAt = now,
                    intervalDays = 0,
                    lapseCount = 0,
                    notificationId = 1,
                    createdAt = now,
                    updatedAt = now
                )
            ),
            contexts = listOf(
                StoredContext(
                    id = "context-1",
                    cardId = "card-1",
                    sourceSentence = "The results are tentative.",
                    sourceUrl = "",
                    sourceApp = "test",
                    captureMethod = CaptureMethod.ANDROID_PROCESS_TEXT,
                    createdAt = now
                )
            ),
            definitionCandidates = listOf(
                StoredDefinitionCandidate(
                    id = "candidate-1",
                    cardId = "card-1",
                    source = "user",
                    rawText = "잠정적인",
                    parsedMeaningKo = "잠정적인",
                    parsedDefinitionEn = "",
                    partOfSpeech = "",
                    phonetic = "",
                    exampleSentence = "",
                    confidence = 1.0,
                    selectedAsPrimary = true,
                    createdAt = now
                )
            ),
            reviewEvents = emptyList(),
            recommendationFeedback = emptyList(),
            settings = UserSettingsData()
        )

        assertEquals("new", payload.getJSONArray("cards").getJSONObject(0).getString("status"))
        assertEquals("user", payload.getJSONArray("cards").getJSONObject(0).getString("wordbook"))
        assertEquals("android_process_text", payload.getJSONArray("contexts").getJSONObject(0).getString("capture_method"))
        assertEquals(10, payload.getJSONObject("settings").getInt("daily_word_goal"))
    }
}
