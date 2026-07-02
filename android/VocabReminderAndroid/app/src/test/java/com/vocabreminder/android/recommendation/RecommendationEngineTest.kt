package com.vocabreminder.android.recommendation

import com.vocabreminder.android.scheduler.SchedulerSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecommendationEngineTest {
    @Test
    fun catalogIsGeneratedFromSharedRecommendationData() {
        val sharedCatalog = JSONObject(readSharedRecommendationCatalog())
            .getJSONArray("entries")

        assertTrue("Expected production-sized MVP catalogue", RecommendationEngine.catalog.size >= 100)
        assertEquals(sharedCatalog.length(), RecommendationEngine.catalog.size)
        assertEquals(RecommendationEngine.catalog.size, RecommendationEngine.catalog.map { it.lemma }.toSet().size)
        assertTrue(RecommendationEngine.catalog.all { it.meaningKo.isNotBlank() && it.frequencyRank > 0 })
        assertTrue(RecommendationEngine.catalog.map { it.topic }.toSet().containsAll(setOf("academic", "methods", "statistics", "neuroscience")))
        val byLemma = RecommendationEngine.catalog.associateBy { it.lemma }
        for (index in 0 until sharedCatalog.length()) {
            val shared = sharedCatalog.getJSONObject(index)
            val seed = byLemma.getValue(shared.getString("lemma"))
            assertEquals(seed.frequencyRank, shared.getInt("frequencyRank"))
            assertEquals(seed.topic, shared.getString("topic"))
            assertEquals(seed.meaningKo, shared.getString("meaningKo"))
        }
    }

    @Test
    fun recommendationsStayOffBeforeFiftyUserWords() {
        val selected = RecommendationEngine.chooseRecommendations(
            userSignals = List(49) { UserVocabularySignal("unknown-$it") },
            feedbackSignals = emptyList(),
            existingLemmas = emptySet(),
            dueUserCount = 0,
            settings = SchedulerSettings(dailyWordGoal = 10)
        )

        assertEquals(emptyList<RecommendationSeed>(), selected)
    }

    @Test
    fun dueReviewsFillGoalBeforeRecommendations() {
        val selected = RecommendationEngine.chooseRecommendations(
            userSignals = List(50) { UserVocabularySignal("unknown-$it") },
            feedbackSignals = emptyList(),
            existingLemmas = emptySet(),
            dueUserCount = 10,
            settings = SchedulerSettings(dailyWordGoal = 10)
        )

        assertEquals(emptyList<RecommendationSeed>(), selected)
    }

    @Test
    fun existingRecommendationsOccupyOpenSlotsBeforeGeneratingMore() {
        val settings = SchedulerSettings(dailyWordGoal = 10, maxQueueSize = 20)

        val oneOpenSlot = RecommendationEngine.recommendationFillLimit(
            dueUserCount = 3,
            existingRecommendationCount = 6,
            settings = settings
        )
        val noOpenSlots = RecommendationEngine.recommendationFillLimit(
            dueUserCount = 3,
            existingRecommendationCount = 7,
            settings = settings
        )

        assertEquals(1, oneOpenSlot)
        assertEquals(0, noOpenSlots)
    }

    @Test
    fun recommendationFillLimitRespectsQueueCap() {
        val fillLimit = RecommendationEngine.recommendationFillLimit(
            dueUserCount = 1,
            existingRecommendationCount = 1,
            settings = SchedulerSettings(dailyWordGoal = 10, maxQueueSize = 4)
        )

        assertEquals(2, fillLimit)
    }

    @Test
    fun recommendationsUseFrequencyBoundaryAndPreferredTopics() {
        val userSignals = List(12) { UserVocabularySignal("subtle") } +
            List(12) { UserVocabularySignal("plausible") } +
            List(12) { UserVocabularySignal("hypothesis") } +
            List(14) { UserVocabularySignal("tentative") }

        val selected = RecommendationEngine.chooseRecommendations(
            userSignals = userSignals,
            feedbackSignals = emptyList(),
            existingLemmas = setOf("subtle", "plausible", "hypothesis", "tentative"),
            dueUserCount = 6,
            settings = SchedulerSettings(dailyWordGoal = 10),
            limit = 4
        )

        assertEquals(4, selected.size)
        assertTrue(selected.all { it.frequencyRank in 11_000..35_000 })
        assertTrue(selected.first().topic in setOf("academic", "methods"))
    }

    @Test
    fun knownFeedbackShiftsRecommendationBandHarder() {
        val userSignals = List(50) { UserVocabularySignal("plausible") }
        val before = RecommendationEngine.estimateLevel(userSignals)
        val after = RecommendationEngine.estimateLevel(
            userSignals,
            feedbackSignals = listOf(
                RecommendationFeedbackSignal("subtle", "known"),
                RecommendationFeedbackSignal("preliminary", "known")
            )
        )

        assertTrue(after.lowerRank > before.lowerRank)
        assertTrue(after.upperRank > before.upperRank)
    }

    private fun readSharedRecommendationCatalog(): String {
        val candidates = listOf(
            File("../../../shared/recommendations/recommendation_catalog.json"),
            File("../../shared/recommendations/recommendation_catalog.json"),
            File("../shared/recommendations/recommendation_catalog.json"),
            File("shared/recommendations/recommendation_catalog.json"),
            File(System.getProperty("user.dir"), "../../../shared/recommendations/recommendation_catalog.json"),
            File(System.getProperty("user.dir"), "../../shared/recommendations/recommendation_catalog.json")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Could not find shared/recommendations/recommendation_catalog.json from ${System.getProperty("user.dir")}")
    }
}
