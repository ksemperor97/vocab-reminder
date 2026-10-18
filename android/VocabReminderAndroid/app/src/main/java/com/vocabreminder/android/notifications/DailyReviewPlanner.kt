package com.vocabreminder.android.notifications

import android.content.Context
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.scheduler.Scheduler
import com.vocabreminder.android.scheduler.ReviewResponse
import com.vocabreminder.android.scheduler.Wordbook
import java.time.Instant

object DailyReviewPlanner {
    fun planAndSchedule(context: Context, now: Instant = NotificationClock.now()): PlanSummary {
        val store = LocalVocabularyStore(context)
        val settingsData = store.settings()
        ReminderScheduler.reconcileAll(context, store, now)
        val processedToday = processedCardIdsToday(store, settingsData.timezone, now).size
        val settings = settingsData.toSchedulerSettings().copy(
            dailyWordGoal = (settingsData.dailyWordGoal - processedToday).coerceAtLeast(0)
        )
        ReminderNotifications.pruneExpiredRecommendationSuppressions(context, now)
        // New cards arrive through sync; planning must never create local recommendations.
        val generated = 0
        if (QuietHoursBoundaryScheduler.isQuietHour(now, settingsData)) {
            QuietHoursBoundaryScheduler.withdrawActiveNotifications(context)
            // A meaning shown in direct response to an action/reply is solicited feedback,
            // so it remains available even while unsolicited prompts stay quiet.
            ReminderNotifications.restoreActiveNotification(context, store, now)
            return PlanSummary(
                generatedRecommendations = generated,
                scheduledNotifications = 0,
                rebalancedCards = 0,
                recommendationIntroShown = false
            )
        }

        ReminderNotifications.restoreActiveNotification(context, store, now)
        val introShown = if (store.cards().any { it.wordbook == Wordbook.RECOMMENDED && it.recommendationStartedAt == null } && settingsData.recommendationsIntroducedAt == null) {
            ReminderNotifications.showRecommendationIntro(context, now).also { shown ->
                if (shown) store.markRecommendationsIntroduced(now)
            }
        } else {
            false
        }
        val activeCards = store.cards()
        val cardsById = activeCards.associateBy { it.id }
        val result = Scheduler.planDay(
            cards = activeCards
                .filterNot { card ->
                    card.wordbook == Wordbook.RECOMMENDED &&
                        card.recommendationStartedAt == null &&
                        ReminderNotifications.isRecommendationSuppressed(context, card.id, now)
                }
                .map { it.toSchedulerCard() },
            settings = settings,
            now = now,
            userWordCount = store.userWordCount()
        )

        val rebalancedCards = result.rebalancedCards.mapNotNull { schedulerCard ->
            cardsById[schedulerCard.id]?.let { original ->
                original.withSchedulerCard(schedulerCard, now)
            }
        }
        store.updateCardsAndAppendEvents(rebalancedCards, result.rebalanceEvents, now)

        val queuedNotifications = result.notifications.mapNotNull { schedulerCard ->
            store.findCard(schedulerCard.id)
        }
        if (!ReminderNotifications.hasActivePrompt(context)) {
            queuedNotifications.firstOrNull()?.let { card ->
                if (card.wordbook == Wordbook.RECOMMENDED && card.recommendationStartedAt == null) {
                    ReminderNotifications.showRecommendation(context, card, now = now)
                } else {
                    ReminderNotifications.showReview(context, card, now = now)
                }
            }
        }

        return PlanSummary(
            generatedRecommendations = generated,
            scheduledNotifications = queuedNotifications.size,
            rebalancedCards = result.rebalancedCards.size,
            recommendationIntroShown = introShown
        )
    }

    fun processedCardIdsToday(
        store: LocalVocabularyStore,
        timezone: java.time.ZoneId,
        now: Instant
    ): Set<String> {
        val today = now.atZone(timezone).toLocalDate()
        val reviewed = store.events()
            .asSequence()
            .filter {
                it.response in setOf(
                    ReviewResponse.REMEMBERED,
                    ReviewResponse.FORGOTTEN,
                    ReviewResponse.UNAVAILABLE,
                    ReviewResponse.REVEAL
                )
            }
            .filter { it.clientCreatedAt.atZone(timezone).toLocalDate() == today }
            .map { it.cardId }
        val recommendationDecisions = store.recommendationFeedback()
            .asSequence()
            .filter { it.createdAt.atZone(timezone).toLocalDate() == today }
            .map { it.cardId }
        return (reviewed + recommendationDecisions).toSet()
    }

    fun rebalanceDueCards(context: Context, now: Instant = NotificationClock.now()): Int {
        val store = LocalVocabularyStore(context)
        val settings = store.settings().toSchedulerSettings()
        val dueCards = store.cards()
            .filter { it.meaningKo.isNotBlank() && !it.dueAt.isAfter(now) }
            .map { it.toSchedulerCard() }
        val rebalanced = Scheduler.autoRebalance(dueCards, settings, now, trigger = "manual")
        val byId = store.cards().associateBy { it.id }
        val updatedCards = rebalanced.mapNotNull { result ->
            byId[result.card.id]?.withSchedulerCard(result.card, now)
        }
        store.updateCardsAndAppendEvents(updatedCards, rebalanced.map { it.event }, now)
        return rebalanced.size
    }
}

data class PlanSummary(
    val generatedRecommendations: Int,
    val scheduledNotifications: Int,
    val rebalancedCards: Int,
    val recommendationIntroShown: Boolean = false
)
