package com.vocabreminder.android.scheduler

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

enum class CardStatus {
    NEW,
    LEARNING,
    REVIEW,
    RELEARNING,
    MASTERED,
    SUSPENDED
}

enum class ReviewResponse {
    REMEMBERED,
    FORGOTTEN,
    UNAVAILABLE,
    REVEAL,
    MANUAL_EDIT,
    SUSPEND,
    RESTORE
}

enum class Wordbook {
    USER,
    RECOMMENDED
}

data class SchedulerCard(
    val id: String,
    val status: CardStatus,
    val intervalDays: Int,
    val dueAt: Instant,
    val lapseCount: Int = 0,
    val intervalBeforeLapse: Int? = null,
    val meaningKo: String = "meaning",
    val wordbook: Wordbook = Wordbook.USER,
    val deletedAt: Instant? = null
)

data class SchedulerSettings(
    val dailyWordGoal: Int = 10,
    val maxQueueSize: Int = 20,
    val snoozeDurationMinutes: Long = 60,
    val quietHoursStart: LocalTime = LocalTime.of(23, 0),
    val quietHoursEnd: LocalTime = LocalTime.of(9, 0),
    val timezone: ZoneId = ZoneId.of("Asia/Seoul"),
    val maxUnavailablePerCardPerDay: Int = 2,
    val enableWordRecommendations: Boolean = true
)

data class ReviewEventDraft(
    val cardId: String,
    val response: ReviewResponse,
    val previousStatus: CardStatus,
    val nextStatus: CardStatus,
    val previousDueAt: Instant,
    val nextDueAt: Instant,
    val previousIntervalDays: Int,
    val nextIntervalDays: Int,
    val metadata: Map<String, String> = emptyMap()
)

data class ReviewResult(
    val card: SchedulerCard,
    val event: ReviewEventDraft
)

data class PlanDayResult(
    val notifications: List<SchedulerCard>,
    val rebalancedCards: List<SchedulerCard>,
    val rebalanceEvents: List<ReviewEventDraft>
)

object Scheduler {
    private const val MAX_REVIEW_INTERVAL_DAYS = 90
    private const val FIRST_NOTIFICATION_DELAY_MINUTES = 10L
    private const val FORGOTTEN_RECHECK_MINUTES = 20L
    private const val QUIET_HOURS_RELEASE_OFFSET_MINUTES = 30L

    fun initialDueAt(now: Instant, settings: SchedulerSettings = SchedulerSettings()): Instant {
        // guide 6.3: new card first notification is save time + 10 minutes.
        return avoidQuietHours(now.plus(Duration.ofMinutes(FIRST_NOTIFICATION_DELAY_MINUTES)), settings)
    }

    fun applyReview(
        card: SchedulerCard,
        response: ReviewResponse,
        settings: SchedulerSettings = SchedulerSettings(),
        now: Instant,
        recentResponses: List<ReviewResponse> = emptyList(),
        unavailableCountToday: Int = 0
    ): ReviewResult {
        val updated = when (response) {
            ReviewResponse.REMEMBERED -> remembered(card, settings, now, recentResponses)
            ReviewResponse.FORGOTTEN -> forgotten(card, settings, now)
            ReviewResponse.UNAVAILABLE -> unavailable(card, settings, now, unavailableCountToday)
            ReviewResponse.REVEAL -> reveal(card, settings, now)
            ReviewResponse.SUSPEND -> card.copy(status = CardStatus.SUSPENDED, dueAt = now)
            ReviewResponse.RESTORE -> card.copy(status = CardStatus.LEARNING, dueAt = avoidQuietHours(now.plus(Duration.ofDays(1)), settings))
            ReviewResponse.MANUAL_EDIT -> card.copy(dueAt = avoidQuietHours(card.dueAt, settings))
        }

        return ReviewResult(
            card = updated,
            event = ReviewEventDraft(
                cardId = card.id,
                response = response,
                previousStatus = card.status,
                nextStatus = updated.status,
                previousDueAt = card.dueAt,
                nextDueAt = updated.dueAt,
                previousIntervalDays = card.intervalDays,
                nextIntervalDays = updated.intervalDays
            )
        )
    }

    fun planDay(
        cards: List<SchedulerCard>,
        settings: SchedulerSettings = SchedulerSettings(),
        now: Instant,
        userWordCount: Int = 0
    ): PlanDayResult {
        // guide 6.4, 6.7, 6.9: user due cards fill the goal first; overflow is rebalanced.
        val dueUserCards = cards
            .filter { it.wordbook == Wordbook.USER && it.isNotificationEligible() && !it.dueAt.isAfter(now) }
            .sortedWith(priorityComparator(now))

        val activeLimit = min(settings.dailyWordGoal, settings.maxQueueSize)
        val selectedDue = dueUserCards.take(activeLimit)
        val overflow = dueUserCards.drop(activeLimit)

        val remainingSlots = activeLimit - selectedDue.size
        val recommendationCards = if (
            remainingSlots > 0 &&
            settings.enableWordRecommendations &&
            userWordCount >= 50
        ) {
            cards
                .filter { it.wordbook == Wordbook.RECOMMENDED && it.isNotificationEligible() }
                .sortedWith(compareBy<SchedulerCard> { it.id })
                .take(remainingSlots)
        } else {
            emptyList()
        }

        val rebalance = autoRebalance(overflow, settings, now, trigger = "auto")
        return PlanDayResult(
            notifications = selectedDue + recommendationCards,
            rebalancedCards = rebalance.map { it.card },
            rebalanceEvents = rebalance.map { it.event }
        )
    }

    fun autoRebalance(
        cards: List<SchedulerCard>,
        settings: SchedulerSettings = SchedulerSettings(),
        now: Instant,
        trigger: String = "auto"
    ): List<ReviewResult> {
        if (cards.isEmpty()) return emptyList()

        val lowPriorityFirst = cards.sortedWith(priorityComparator(now).reversed())
        return lowPriorityFirst.mapIndexed { index, card ->
            val dayOffset = (index % 7) + 1L
            val dueAt = avoidQuietHours(now.plus(Duration.ofDays(dayOffset)), settings)
            val updated = card.copy(dueAt = dueAt)
            ReviewResult(
                card = updated,
                event = ReviewEventDraft(
                    cardId = card.id,
                    response = ReviewResponse.MANUAL_EDIT,
                    previousStatus = card.status,
                    nextStatus = updated.status,
                    previousDueAt = card.dueAt,
                    nextDueAt = updated.dueAt,
                    previousIntervalDays = card.intervalDays,
                    nextIntervalDays = updated.intervalDays,
                    metadata = mapOf("action" to "rebalance", "trigger" to trigger)
                )
            )
        }
    }

    fun avoidQuietHours(candidate: Instant, settings: SchedulerSettings): Instant {
        val zone = settings.timezone
        val local = LocalDateTime.ofInstant(candidate, zone)
        val time = local.toLocalTime()

        if (!isWithinQuietHours(time, settings.quietHoursStart, settings.quietHoursEnd)) {
            return candidate
        }

        val endToday = local.toLocalDate().atTime(settings.quietHoursEnd)
        val release = if (time < settings.quietHoursEnd && settings.quietHoursStart > settings.quietHoursEnd) {
            endToday
        } else {
            endToday.plusDays(1)
        }

        return release
            .plusMinutes(QUIET_HOURS_RELEASE_OFFSET_MINUTES)
            .atZone(zone)
            .toInstant()
    }

    private fun remembered(
        card: SchedulerCard,
        settings: SchedulerSettings,
        now: Instant,
        recentResponses: List<ReviewResponse>
    ): SchedulerCard {
        val nextInterval = when (card.status) {
            CardStatus.NEW -> 1
            CardStatus.LEARNING -> nextLearningInterval(card.intervalDays)
            CardStatus.REVIEW -> min(card.intervalDays.coerceAtLeast(1) * 2, MAX_REVIEW_INTERVAL_DAYS)
            CardStatus.RELEARNING -> max(1, min(floor((card.intervalBeforeLapse ?: 1) * 0.5).toInt(), 7))
            CardStatus.MASTERED -> MAX_REVIEW_INTERVAL_DAYS
            CardStatus.SUSPENDED -> card.intervalDays
        }

        val nextStatus = when (card.status) {
            CardStatus.NEW -> CardStatus.LEARNING
            CardStatus.LEARNING -> if (nextInterval >= 7) CardStatus.REVIEW else CardStatus.LEARNING
            CardStatus.REVIEW -> if (nextInterval >= 90 && recentResponses.takeLast(2).all { it == ReviewResponse.REMEMBERED } && recentResponses.size >= 2) {
                CardStatus.MASTERED
            } else {
                CardStatus.REVIEW
            }
            CardStatus.RELEARNING -> if (nextInterval >= 7) CardStatus.REVIEW else CardStatus.LEARNING
            CardStatus.MASTERED -> CardStatus.MASTERED
            CardStatus.SUSPENDED -> CardStatus.SUSPENDED
        }

        return card.copy(
            status = nextStatus,
            intervalDays = nextInterval,
            dueAt = avoidQuietHours(now.plus(Duration.ofDays(nextInterval.toLong())), settings)
        )
    }

    private fun forgotten(card: SchedulerCard, settings: SchedulerSettings, now: Instant): SchedulerCard {
        // guide 6.3, 13.2: forgotten preserves the previous interval and rechecks after 10-30 minutes.
        val previousInterval = if (card.status == CardStatus.MASTERED) MAX_REVIEW_INTERVAL_DAYS else card.intervalDays
        return card.copy(
            status = CardStatus.RELEARNING,
            intervalDays = 0,
            intervalBeforeLapse = previousInterval,
            lapseCount = card.lapseCount + 1,
            dueAt = avoidQuietHours(now.plus(Duration.ofMinutes(FORGOTTEN_RECHECK_MINUTES)), settings)
        )
    }

    private fun unavailable(
        card: SchedulerCard,
        settings: SchedulerSettings,
        now: Instant,
        unavailableCountToday: Int
    ): SchedulerCard {
        // guide 6.3: the second unavailable in a day moves the card to tomorrow.
        val delay = if (unavailableCountToday + 1 >= settings.maxUnavailablePerCardPerDay) {
            Duration.ofDays(1)
        } else {
            Duration.ofMinutes(settings.snoozeDurationMinutes)
        }
        return card.copy(dueAt = avoidQuietHours(now.plus(delay), settings))
    }

    private fun reveal(card: SchedulerCard, settings: SchedulerSettings, now: Instant): SchedulerCard {
        // guide 6.2: reveal is a weak failure, without increasing lapse_count.
        val nextInterval = max(1, floor(card.intervalDays * 0.5).toInt())
        return card.copy(
            intervalDays = nextInterval,
            dueAt = avoidQuietHours(now.plus(Duration.ofDays(nextInterval.toLong())), settings)
        )
    }

    private fun nextLearningInterval(currentIntervalDays: Int): Int {
        return when {
            currentIntervalDays < 1 -> 1
            currentIntervalDays < 3 -> 3
            else -> 7
        }
    }

    private fun SchedulerCard.isNotificationEligible(): Boolean {
        return status != CardStatus.MASTERED &&
            status != CardStatus.SUSPENDED &&
            deletedAt == null &&
            meaningKo.isNotBlank()
    }

    private fun priorityComparator(now: Instant): Comparator<SchedulerCard> {
        return compareByDescending<SchedulerCard> { priorityRank(it) }
            .thenComparator { left, right -> compareWithinPriority(left, right, now) }
            .thenByDescending { it.lapseCount }
            .thenBy { it.id }
    }

    private fun priorityRank(card: SchedulerCard): Int {
        return when (card.status) {
            CardStatus.RELEARNING -> 4
            CardStatus.LEARNING -> 3
            CardStatus.REVIEW -> 2
            CardStatus.NEW -> 1
            CardStatus.MASTERED,
            CardStatus.SUSPENDED -> 0
        }
    }

    private fun compareWithinPriority(left: SchedulerCard, right: SchedulerCard, now: Instant): Int {
        return when {
            left.status == CardStatus.LEARNING && right.status == CardStatus.LEARNING ->
                left.dueAt.compareTo(right.dueAt)
            left.status == CardStatus.REVIEW && right.status == CardStatus.REVIEW ->
                reviewOverdueRatio(right, now).compareTo(reviewOverdueRatio(left, now))
            else -> 0
        }
    }

    private fun reviewOverdueRatio(card: SchedulerCard, now: Instant): Double {
        val overdueMinutes = max(0.0, Duration.between(card.dueAt, now).toMinutes().toDouble())
        val intervalMinutes = max(1.0, card.intervalDays * 24.0 * 60.0)
        return overdueMinutes / intervalMinutes
    }

    private fun isWithinQuietHours(time: LocalTime, start: LocalTime, end: LocalTime): Boolean {
        return if (start < end) {
            !time.isBefore(start) && time < end
        } else {
            !time.isBefore(start) || time < end
        }
    }
}
