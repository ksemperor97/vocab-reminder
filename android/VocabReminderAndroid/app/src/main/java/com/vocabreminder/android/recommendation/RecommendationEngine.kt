package com.vocabreminder.android.recommendation

import com.vocabreminder.android.capture.CaptureMethod
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.data.VocabularyCard
import com.vocabreminder.android.dictionary.DictionaryResult
import com.vocabreminder.android.scheduler.CardStatus
import com.vocabreminder.android.scheduler.ReviewResponse
import com.vocabreminder.android.scheduler.SchedulerSettings
import com.vocabreminder.android.scheduler.Wordbook
import java.time.Instant
import kotlin.math.abs

data class RecommendationSeed(
    val lemma: String,
    val meaningKo: String,
    val definitionEn: String,
    val partOfSpeech: String,
    val exampleSentence: String,
    val frequencyRank: Int,
    val topic: String
)

data class UserVocabularySignal(
    val lemma: String,
    val lapseCount: Int = 0,
    val rememberedCount: Int = 0,
    val forgottenCount: Int = 0
)

data class RecommendationFeedbackSignal(
    val lemma: String,
    val action: String
)

data class UserLevelProfile(
    val lowerRank: Int,
    val upperRank: Int,
    val preferredTopics: Set<String>
)

object RecommendationEngine {
    // Source ranks/counts are derived from words/subtlex-word-frequencies (ISC), itself based on SUBTLEXus.
    // See shared/recommendations/README.md for attribution and licensing notes.
    val catalog: List<RecommendationSeed> = RecommendationCatalog.seeds

    fun ensureRecommendations(store: LocalVocabularyStore, settings: SchedulerSettings, now: Instant): Int {
        val activeCards = store.cards()
        val userCards = activeCards.filter { it.wordbook == Wordbook.USER }
        val dueUserCount = userCards.count { it.isDueReviewCandidate(now) }
        val existingRecommendationCount = activeCards.count { it.isActiveRecommendationCandidate() }
        val existing = store.allCards(includeDeleted = true).map { it.lemma }.toSet()
        val reviewEvents = store.events()
        val userSignals = userCards.map { card ->
            val events = reviewEvents.filter { it.cardId == card.id }
            UserVocabularySignal(
                lemma = card.lemma,
                lapseCount = card.lapseCount,
                rememberedCount = events.count { it.response == ReviewResponse.REMEMBERED },
                forgottenCount = events.count { it.response == ReviewResponse.FORGOTTEN }
            )
        }
        val feedback = store.recommendationFeedback().map {
            RecommendationFeedbackSignal(lemma = it.lemma, action = it.action)
        }
        val selected = chooseRecommendations(
            userSignals = userSignals,
            feedbackSignals = feedback,
            existingLemmas = existing,
            dueUserCount = dueUserCount,
            settings = settings,
            limit = recommendationFillLimit(
                dueUserCount = dueUserCount,
                existingRecommendationCount = existingRecommendationCount,
                settings = settings
            )
        )

        selected.forEach { seed ->
            store.addRecommendedCard(
                result = seed.toDictionaryResult(),
                captureMethod = CaptureMethod.RECOMMENDATION,
                now = now
            )
        }
        return selected.size
    }

    fun recommendationFillLimit(
        dueUserCount: Int,
        existingRecommendationCount: Int,
        settings: SchedulerSettings
    ): Int {
        val activeLimit = minOf(settings.dailyWordGoal, settings.maxQueueSize)
        return (activeLimit - dueUserCount.coerceAtLeast(0) - existingRecommendationCount.coerceAtLeast(0)).coerceAtLeast(0)
    }

    fun chooseRecommendations(
        userSignals: List<UserVocabularySignal>,
        feedbackSignals: List<RecommendationFeedbackSignal>,
        existingLemmas: Set<String>,
        dueUserCount: Int,
        settings: SchedulerSettings,
        limit: Int = recommendationFillLimit(
            dueUserCount = dueUserCount,
            existingRecommendationCount = 0,
            settings = settings
        )
    ): List<RecommendationSeed> {
        // guide 24.1/24.3: recommendations only start after 50 user cards and never displace due reviews.
        if (!settings.enableWordRecommendations || userSignals.size < 50 || limit <= 0) return emptyList()

        val profile = estimateLevel(userSignals, feedbackSignals)
        val excluded = feedbackSignals
            .filter { it.action == "known" || it.action == "dismissed" }
            .map { it.lemma }
            .toSet() + existingLemmas
        val dismissedTopics = feedbackSignals
            .filter { it.action == "dismissed" }
            .mapNotNull { catalogByLemma[it.lemma]?.topic }
            .toSet()

        return catalog
            .filter { it.lemma !in excluded }
            .sortedWith(
                compareByDescending<RecommendationSeed> { it.frequencyRank in profile.lowerRank..profile.upperRank }
                    .thenByDescending { it.topic in profile.preferredTopics }
                    .thenBy { if (it.topic in dismissedTopics) 1 else 0 }
                    .thenBy { distanceFromBand(it.frequencyRank, profile) }
                    .thenBy { it.frequencyRank }
            )
            .take(limit)
    }

    fun estimateLevel(
        userSignals: List<UserVocabularySignal>,
        feedbackSignals: List<RecommendationFeedbackSignal> = emptyList()
    ): UserLevelProfile {
        val rankedSignals = userSignals.mapNotNull { signal ->
            catalogByLemma[signal.lemma]?.let { seed -> signal to seed }
        }
        val ranks = rankedSignals.map { it.second.frequencyRank }.sorted()
        val baseCenter = if (ranks.isEmpty()) {
            16_000
        } else {
            ranks[(ranks.size * 0.70).toInt().coerceAtMost(ranks.lastIndex)]
        }
        val weakPenalty = rankedSignals.count { (signal, _) ->
            signal.lapseCount >= 3 || (signal.rememberedCount + signal.forgottenCount >= 4 && signal.forgottenCount * 2 >= signal.rememberedCount + signal.forgottenCount)
        } * 700
        val knownBoost = feedbackSignals.count { it.action == "known" } * 1_500
        val center = (baseCenter + knownBoost - weakPenalty).coerceIn(8_000, 55_000)
        val preferredTopics = rankedSignals
            .groupingBy { it.second.topic }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key }
            .toSet()
            .ifEmpty { setOf("academic", "methods") }

        return UserLevelProfile(
            lowerRank = (center - 4_000).coerceAtLeast(5_000),
            upperRank = (center + 10_000).coerceAtMost(70_000),
            preferredTopics = preferredTopics
        )
    }

    private val catalogByLemma = catalog.associateBy { it.lemma }

    private fun distanceFromBand(rank: Int, profile: UserLevelProfile): Int {
        return when {
            rank < profile.lowerRank -> profile.lowerRank - rank
            rank > profile.upperRank -> rank - profile.upperRank
            else -> abs(rank - ((profile.lowerRank + profile.upperRank) / 2))
        }
    }

    private fun RecommendationSeed.toDictionaryResult(): DictionaryResult {
        return DictionaryResult(
            lemma = lemma,
            displayWord = lemma,
            meaningKo = meaningKo,
            definitionEn = definitionEn,
            partOfSpeech = partOfSpeech,
            exampleSentence = exampleSentence,
            rawSource = "$partOfSpeech: $definitionEn",
            sourceType = "local_dictionary",
            confidence = 0.74,
            sourceSentence = exampleSentence,
            sourceApp = "Vocabulary Recommendation"
        )
    }

    private fun VocabularyCard.isDueReviewCandidate(now: Instant): Boolean {
        return meaningKo.isNotBlank() &&
            status != CardStatus.MASTERED &&
            status != CardStatus.SUSPENDED &&
            !dueAt.isAfter(now)
    }

    private fun VocabularyCard.isActiveRecommendationCandidate(): Boolean {
        return wordbook == Wordbook.RECOMMENDED &&
            meaningKo.isNotBlank() &&
            status != CardStatus.MASTERED &&
            status != CardStatus.SUSPENDED
    }
}
