package com.vocabreminder.android.data

import com.vocabreminder.android.capture.CaptureMethod
import com.vocabreminder.android.scheduler.CardStatus
import com.vocabreminder.android.scheduler.ReviewResponse
import com.vocabreminder.android.scheduler.Wordbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CardHistoryBuilderTest {
    private val base = Instant.parse("2026-07-13T00:00:00Z")

    @Test
    fun crossDeviceEditsFromTheSameBaseVersionAreMarkedAsAConflict() {
        val card = VocabularyCard(
            id = "card",
            lemma = "test",
            displayWord = "test",
            meaningKo = "zeta",
            definitionEn = "The current definition",
            partOfSpeech = "Noun",
            exampleSentence = "A current example.",
            status = CardStatus.NEW,
            wordbook = Wordbook.USER,
            dueAt = base,
            intervalDays = 0,
            lapseCount = 0,
            notificationId = 1,
            createdAt = base,
            updatedAt = base.plusSeconds(30)
        )
        val sameMeaningWrongDefinition = candidate(
            id = "same-meaning-wrong-definition",
            meaning = "zeta",
            definition = "A stale definition",
            createdAt = base.plusSeconds(20),
            originDeviceId = "android-a",
            baseCardVersion = 1
        )
        val zeta = candidate(
            id = "zeta",
            meaning = "  ZETA  ",
            definition = "  the   CURRENT definition ",
            partOfSpeech = " noun ",
            exampleSentence = "A   current example.",
            createdAt = base.plusSeconds(25),
            originDeviceId = "mac-b",
            baseCardVersion = 1
        )
        val context = StoredContext(
            id = "context",
            cardId = card.id,
            sourceSentence = "A source sentence.",
            sourceUrl = "https://example.com/article",
            sourceApp = "com.example.reader",
            pageTitle = "Article",
            captureMethod = CaptureMethod.ANDROID_SHARE,
            createdAt = base.plusSeconds(10)
        )
        val event = StoredReviewEvent(
            id = "event",
            cardId = card.id,
            response = ReviewResponse.MANUAL_EDIT,
            previousStatus = CardStatus.NEW,
            nextStatus = CardStatus.NEW,
            previousDueAt = base,
            nextDueAt = base,
            previousIntervalDays = 0,
            nextIntervalDays = 0,
            clientCreatedAt = base.plusSeconds(30)
        )

        val history = CardHistoryBuilder.build(
            card,
            listOf(context),
            listOf(sameMeaningWrongDefinition, zeta),
            listOf(event)
        )

        assertTrue(history.first() is CardHistoryEntry.ReviewEntry)
        assertEquals(base.plusSeconds(30), history.first().occurredAt)
        assertTrue(history.any { it is CardHistoryEntry.SyncConflictEntry })
        val definitions = history.filterIsInstance<CardHistoryEntry.DefinitionEntry>()
        assertEquals(1, definitions.count { it.isCurrentPrimary })
        assertEquals("zeta", definitions.single { it.isCurrentPrimary }.candidate.id)
        assertTrue(definitions.all { it.isConflictCandidate })
        assertEquals(base.plusSeconds(10), history.last().occurredAt)
    }

    @Test
    fun repeatedSelectedCandidatesWithTheSameNormalizedProjectionAreNotAlternatives() {
        val card = VocabularyCard(
            id = "card",
            lemma = "test",
            displayWord = "test",
            meaningKo = "뜻",
            definitionEn = "definition",
            partOfSpeech = "noun",
            exampleSentence = "An example.",
            status = CardStatus.NEW,
            wordbook = Wordbook.USER,
            dueAt = base,
            intervalDays = 0,
            lapseCount = 0,
            notificationId = 1,
            createdAt = base,
            updatedAt = base
        )
        val first = candidate(
            "first",
            "뜻",
            createdAt = base.plusSeconds(1),
            originDeviceId = "android-a",
            baseCardVersion = 1
        )
        val duplicate = candidate(
            "duplicate",
            " 뜻 ",
            definition = " DEFINITION ",
            partOfSpeech = "NOUN",
            exampleSentence = "An   example.",
            createdAt = base.plusSeconds(2),
            originDeviceId = "mac-b",
            baseCardVersion = 1
        )

        val history = CardHistoryBuilder.build(card, emptyList(), listOf(first, duplicate), emptyList())

        assertTrue(history.none { it is CardHistoryEntry.SyncConflictEntry })
        assertTrue(history.filterIsInstance<CardHistoryEntry.DefinitionEntry>().none { it.isConflictCandidate })
        assertEquals("duplicate", history.filterIsInstance<CardHistoryEntry.DefinitionEntry>()
            .single { it.isCurrentPrimary }.candidate.id)
    }

    @Test
    fun sequentialEditsAreNotMarkedAsAConflict() {
        val card = VocabularyCard(
            id = "card",
            lemma = "test",
            displayWord = "test",
            meaningKo = "세 번째 뜻",
            definitionEn = "definition",
            partOfSpeech = "noun",
            exampleSentence = "An example.",
            status = CardStatus.NEW,
            wordbook = Wordbook.USER,
            dueAt = base,
            intervalDays = 0,
            lapseCount = 0,
            notificationId = 1,
            createdAt = base,
            updatedAt = base.plusSeconds(2),
            version = 3
        )
        val firstEdit = candidate(
            "first-edit",
            "두 번째 뜻",
            createdAt = base.plusSeconds(1),
            originDeviceId = "android-a",
            baseCardVersion = 1
        )
        val secondEdit = candidate(
            "second-edit",
            "세 번째 뜻",
            createdAt = base.plusSeconds(2),
            originDeviceId = "android-a",
            baseCardVersion = 2
        )

        val history = CardHistoryBuilder.build(card, emptyList(), listOf(firstEdit, secondEdit), emptyList())

        assertTrue(history.none { it is CardHistoryEntry.SyncConflictEntry })
        assertTrue(history.filterIsInstance<CardHistoryEntry.DefinitionEntry>().none { it.isConflictCandidate })
    }

    @Test
    fun legacyCandidatesWithoutLineageRemainVisibleButAreNotConflicts() {
        val card = VocabularyCard(
            id = "card",
            lemma = "test",
            displayWord = "test",
            meaningKo = "새 뜻",
            definitionEn = "definition",
            partOfSpeech = "noun",
            exampleSentence = "An example.",
            status = CardStatus.NEW,
            wordbook = Wordbook.USER,
            dueAt = base,
            intervalDays = 0,
            lapseCount = 0,
            notificationId = 1,
            createdAt = base,
            updatedAt = base
        )
        val old = candidate("old", "옛 뜻")
        val current = candidate("current", "새 뜻")

        val history = CardHistoryBuilder.build(card, emptyList(), listOf(old, current), emptyList())

        assertEquals(2, history.filterIsInstance<CardHistoryEntry.DefinitionEntry>().size)
        assertTrue(history.none { it is CardHistoryEntry.SyncConflictEntry })
    }

    private fun candidate(
        id: String,
        meaning: String,
        definition: String = "definition",
        partOfSpeech: String = "noun",
        exampleSentence: String = "An example.",
        createdAt: Instant = base.plusSeconds(20),
        originDeviceId: String? = null,
        baseCardVersion: Int? = null
    ): StoredDefinitionCandidate {
        return StoredDefinitionCandidate(
            id = id,
            cardId = "card",
            source = "user",
            rawText = meaning,
            parsedMeaningKo = meaning,
            parsedDefinitionEn = definition,
            partOfSpeech = partOfSpeech,
            phonetic = "",
            exampleSentence = exampleSentence,
            confidence = 1.0,
            selectedAsPrimary = true,
            createdAt = createdAt,
            originDeviceId = originDeviceId,
            baseCardVersion = baseCardVersion
        )
    }
}
