package com.vocabreminder.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vocabreminder.android.capture.CaptureMethod
import com.vocabreminder.android.dictionary.DictionaryResolver
import com.vocabreminder.android.scheduler.CardStatus
import com.vocabreminder.android.scheduler.Wordbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LocalVocabularyStoreRecommendationTest {
    private lateinit var store: LocalVocabularyStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        store = LocalVocabularyStore(context)
        store.clearAll()
    }

    @Test
    fun userCapturePromotesRecommendedCardAndKeepsProgress() {
        val now = Instant.parse("2026-07-03T00:00:00Z")
        val recommended = store.addRecommendedCard(
            result = DictionaryResolver.resolve("ubiquitous"),
            captureMethod = CaptureMethod.RECOMMENDATION,
            now = now
        )
        store.updateCard(
            recommended.copy(
                status = CardStatus.REVIEW,
                intervalDays = 14,
                lapseCount = 2,
                dueAt = now.plusSeconds(14 * 24 * 60 * 60),
                recommendationStartedAt = now.plusSeconds(60)
            )
        )

        val captured = store.addResolvedCard(
            result = DictionaryResolver.resolve("Ubiquitous"),
            captureMethod = CaptureMethod.ANDROID_PROCESS_TEXT,
            dueAt = now.plusSeconds(10 * 60),
            now = now.plusSeconds(120),
            wordbook = Wordbook.USER
        )

        assertEquals(1, store.cards().size)
        assertEquals(recommended.id, captured.id)
        assertEquals(Wordbook.USER, captured.wordbook)
        assertEquals(CardStatus.REVIEW, captured.status)
        assertEquals(14, captured.intervalDays)
        assertEquals(2, captured.lapseCount)
        assertEquals(now.plusSeconds(14 * 24 * 60 * 60), captured.dueAt)
        assertEquals(now.plusSeconds(60), captured.recommendationStartedAt)
        assertEquals(2, store.contexts().size)
        assertTrue(store.contexts().all { it.cardId == captured.id })
    }
}
