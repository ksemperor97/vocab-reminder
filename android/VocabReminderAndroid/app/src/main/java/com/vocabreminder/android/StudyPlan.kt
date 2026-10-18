package com.vocabreminder.android

import com.vocabreminder.android.data.StoredContext
import com.vocabreminder.android.data.StoredReviewEvent
import com.vocabreminder.android.data.VocabularyCard
import com.vocabreminder.android.scheduler.CardStatus
import com.vocabreminder.android.scheduler.ReviewResponse
import com.vocabreminder.android.scheduler.Wordbook
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * In-app study uses the same schedule as the notifications: a session answers
 * the cards that are due, through AppViewModel.reviewCard, which also cancels
 * and reschedules their notifications. Practice sessions (nothing due) do not
 * touch the schedule.
 */
internal data class StudySession(
    val cardIds: List<String>,
    val practice: Boolean,
    val index: Int = 0,
    val revealed: Boolean = false,
    val remembered: Int = 0,
    val forgotten: Int = 0,
    val later: Int = 0
) {
    val finished: Boolean get() = index >= cardIds.size
    val currentId: String? get() = cardIds.getOrNull(index)
}

internal data class TodaySummary(
    val dueNew: Int,
    val dueReview: Int,
    val reviewedToday: Int,
    val dailyGoal: Int,
    val streakDays: Int,
    val shakyCount: Int,
    val practiceCount: Int,
    val nextDue: VocabularyCard?
) {
    val due: Int get() = dueNew + dueReview
}

internal object StudyPlan {
    private const val PRACTICE_SIZE = 10
    private val answered = setOf(ReviewResponse.REMEMBERED, ReviewResponse.FORGOTTEN)

    fun isStudyable(card: VocabularyCard): Boolean =
        card.deletedAt == null &&
            card.meaningKo.isNotBlank() &&
            card.status != CardStatus.SUSPENDED &&
            card.status != CardStatus.MASTERED &&
            (card.wordbook == Wordbook.USER || card.recommendationStartedAt != null)

    /** Due cards, oldest due first: the same set the notifications would ask about. */
    fun dueQueue(cards: List<VocabularyCard>, now: Instant): List<VocabularyCard> =
        cards.filter { isStudyable(it) && !it.dueAt.isAfter(now) }.sortedBy { it.dueAt }

    /** When nothing is due: the shakiest words first, then the soonest due. */
    fun practiceQueue(cards: List<VocabularyCard>, weakIds: Set<String>): List<VocabularyCard> =
        cards.filter { isStudyable(it) }
            .sortedWith(compareByDescending<VocabularyCard> { it.id in weakIds }.thenByDescending { it.lapseCount }.thenBy { it.dueAt })
            .take(PRACTICE_SIZE)

    fun today(
        cards: List<VocabularyCard>,
        events: List<StoredReviewEvent>,
        weakIds: Set<String>,
        dailyGoal: Int,
        timezone: ZoneId,
        now: Instant
    ): TodaySummary {
        val due = dueQueue(cards, now)
        val today = now.atZone(timezone).toLocalDate()
        val answeredDays = events.filter { it.response in answered }
            .map { it.clientCreatedAt.atZone(timezone).toLocalDate() }
            .toSet()
        val reviewedToday = events
            .filter { it.response in answered && it.clientCreatedAt.atZone(timezone).toLocalDate() == today }
            .map { it.cardId }
            .toSet()
            .size
        val studyable = cards.filter { isStudyable(it) }
        return TodaySummary(
            dueNew = due.count { it.status == CardStatus.NEW },
            dueReview = due.count { it.status != CardStatus.NEW },
            reviewedToday = reviewedToday,
            dailyGoal = dailyGoal.coerceAtLeast(1),
            streakDays = streak(answeredDays, today),
            shakyCount = studyable.count { it.id in weakIds || it.status == CardStatus.RELEARNING },
            practiceCount = studyable.size,
            nextDue = studyable.filter { it.dueAt.isAfter(now) }.minByOrNull { it.dueAt }
        )
    }

    /** Days in a row with at least one answer, counting back from today (or yesterday if today has none yet). */
    fun streak(days: Set<LocalDate>, today: LocalDate): Int {
        var day = if (today in days) today else today.minusDays(1)
        var count = 0
        while (day in days) {
            count += 1
            day = day.minusDays(1)
        }
        return count
    }

    /** The sentence the word was captured in, or the card's example. */
    fun sentence(card: VocabularyCard, contexts: List<StoredContext>): String =
        contexts.filter { it.cardId == card.id && it.sourceSentence.isNotBlank() }
            .maxByOrNull { it.createdAt }
            ?.sourceSentence
            ?: card.exampleSentence
}
