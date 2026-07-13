package com.vocabreminder.android.notifications

import android.content.Context
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.recommendation.RecommendationEngine
import com.vocabreminder.android.scheduler.Scheduler
import com.vocabreminder.android.scheduler.ReviewResponse
import com.vocabreminder.android.scheduler.Wordbook
import java.time.Instant

object DailyReviewPlanner {
    fun planAndSchedule(context: Context, now: Instant = Instant.now()): PlanSummary {
        val store = LocalVocabularyStore(context)
        val settingsData = store.settings()
        ReminderScheduler.reconcileAll(context, store, now)
        if (QuietHoursBoundaryScheduler.isQuietHour(now, settingsData)) {
            QuietHoursBoundaryScheduler.withdrawActiveNotifications(context)
            // A meaning shown in direct response to an action/reply is solicited feedback,
            // so it remains available even while unsolicited prompts stay quiet.
            ReminderNotifications.restoreActiveNotification(context, store, now)
            return PlanSummary(
                generatedRecommendations = 0,
                scheduledNotifications = 0,
                rebalancedCards = 0,
                recommendationIntroShown = false
            )
        }

        ReminderNotifications.restoreActiveNotification(context, store, now)
        val processedToday = processedCardIdsToday(store, settingsData.timezone, now).size
        val settings = settingsData.toSchedulerSettings().copy(
            dailyWordGoal = (settingsData.dailyWordGoal - processedToday).coerceAtLeast(0)
        )
        ReminderNotifications.pruneExpiredRecommendationSuppressions(context, now)
        val generated = RecommendationEngine.ensureRecommendations(store, settings, now)
        val introShown = if (generated > 0 && settingsData.recommendationsIntroducedAt == null) {
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

        result.rebalancedCards.forEach { schedulerCard ->
            cardsById[schedulerCard.id]?.let { original ->
                val updated = original.withSchedulerCard(schedulerCard, now)
                store.updateCard(updated)
            }
        }
        result.rebalanceEvents.forEach { store.appendEvent(it, now) }

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

    private fun processedCardIdsToday(
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

    fun rebalanceDueCards(context: Context, now: Instant = Instant.now()): Int {
        val store = LocalVocabularyStore(context)
        val settings = store.settings().toSchedulerSettings()
        val dueCards = store.cards()
            .filter { it.meaningKo.isNotBlank() && !it.dueAt.isAfter(now) }
            .map { it.toSchedulerCard() }
        val rebalanced = Scheduler.autoRebalance(dueCards, settings, now, trigger = "manual")
        val byId = store.cards().associateBy { it.id }
        rebalanced.forEach { result ->
            byId[result.card.id]?.let { store.updateCard(it.withSchedulerCard(result.card, now)) }
            store.appendEvent(result.event, now)
        }
        return rebalanced.size
    }
}

data class PlanSummary(
    val generatedRecommendations: Int,
    val scheduledNotifications: Int,
    val rebalancedCards: Int,
    val recommendationIntroShown: Boolean = false
)
