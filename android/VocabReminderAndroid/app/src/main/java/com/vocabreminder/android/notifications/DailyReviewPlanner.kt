package com.vocabreminder.android.notifications

import android.content.Context
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.recommendation.RecommendationEngine
import com.vocabreminder.android.scheduler.Scheduler
import com.vocabreminder.android.scheduler.Wordbook
import java.time.Instant

object DailyReviewPlanner {
    fun planAndSchedule(context: Context, now: Instant = Instant.now()): PlanSummary {
        val store = LocalVocabularyStore(context)
        val settings = store.settings().toSchedulerSettings()
        val generated = RecommendationEngine.ensureRecommendations(store, settings, now)
        val cardsById = store.cards().associateBy { it.id }
        val result = Scheduler.planDay(
            cards = store.cards().map { it.toSchedulerCard() },
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

        var scheduled = 0
        result.notifications.forEach { schedulerCard ->
            val card = store.findCard(schedulerCard.id) ?: return@forEach
            if (card.wordbook == Wordbook.RECOMMENDED && card.recommendationStartedAt == null) {
                ReminderNotifications.showRecommendation(context, card)
                scheduled += 1
            } else {
                ReminderScheduler.schedule(context, card, now)
                scheduled += 1
            }
        }

        return PlanSummary(
            generatedRecommendations = generated,
            scheduledNotifications = scheduled,
            rebalancedCards = result.rebalancedCards.size
        )
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
    val rebalancedCards: Int
)
