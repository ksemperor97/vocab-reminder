package com.vocabreminder.android.data

import com.vocabreminder.android.scheduler.CardStatus
import com.vocabreminder.android.scheduler.Wordbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class DuplicateCardMergerTest {
    @Test
    fun userCaptureWinsIdentityWhileRecommendedProgressIsPreserved() {
        val createdAt = Instant.parse("2026-07-02T00:00:00Z")
        val userCapture = card(
            id = "user-card",
            wordbook = Wordbook.USER,
            status = CardStatus.NEW,
            dueAt = Instant.parse("2026-07-02T00:10:00Z"),
            intervalDays = 0,
            lapseCount = 0,
            createdAt = createdAt.plusSeconds(120),
            updatedAt = createdAt.plusSeconds(120),
            version = 1
        )
        val recommendedProgress = card(
            id = "recommended-card",
            wordbook = Wordbook.RECOMMENDED,
            status = CardStatus.REVIEW,
            dueAt = Instant.parse("2026-07-16T00:00:00Z"),
            intervalDays = 14,
            lapseCount = 2,
            recommendationStartedAt = createdAt.plusSeconds(60),
            createdAt = createdAt,
            updatedAt = createdAt.plusSeconds(60),
            version = 2
        )

        val canonical = DuplicateCardMerger.canonicalize(listOf(userCapture, recommendedProgress))

        val merged = canonical.cards.single()
        assertEquals("user-card", merged.id)
        assertEquals(Wordbook.USER, merged.wordbook)
        assertEquals(CardStatus.REVIEW, merged.status)
        assertEquals(Instant.parse("2026-07-16T00:00:00Z"), merged.dueAt)
        assertEquals(14, merged.intervalDays)
        assertEquals(2, merged.lapseCount)
        assertEquals(createdAt.plusSeconds(60), merged.recommendationStartedAt)
        assertEquals(mapOf("recommended-card" to "user-card"), canonical.cardIdMap)
    }

    @Test
    fun activeDuplicateMergeDoesNotTouchDeletedCards() {
        val now = Instant.parse("2026-07-02T00:00:00Z")
        val active = card(id = "active-card", wordbook = Wordbook.USER, createdAt = now, updatedAt = now)
        val deleted = card(
            id = "deleted-card",
            wordbook = Wordbook.RECOMMENDED,
            deletedAt = now.plusSeconds(1),
            createdAt = now,
            updatedAt = now.plusSeconds(1)
        )

        val canonical = DuplicateCardMerger.canonicalize(listOf(active, deleted))

        assertEquals(2, canonical.cards.size)
        assertNull(canonical.cardIdMap["deleted-card"])
    }

    private fun card(
        id: String,
        wordbook: Wordbook,
        status: CardStatus = CardStatus.NEW,
        dueAt: Instant = Instant.parse("2026-07-02T00:10:00Z"),
        intervalDays: Int = 0,
        lapseCount: Int = 0,
        recommendationStartedAt: Instant? = null,
        deletedAt: Instant? = null,
        createdAt: Instant,
        updatedAt: Instant,
        version: Int = 1
    ): VocabularyCard {
        return VocabularyCard(
            id = id,
            lemma = "ubiquitous",
            displayWord = "ubiquitous",
            meaningKo = "어디에나 있는",
            status = status,
            wordbook = wordbook,
            dueAt = dueAt,
            intervalDays = intervalDays,
            lapseCount = lapseCount,
            recommendationStartedAt = recommendationStartedAt,
            deletedAt = deletedAt,
            notificationId = id.hashCode() and 0x7fffffff,
            createdAt = createdAt,
            updatedAt = updatedAt,
            version = version
        )
    }
}
