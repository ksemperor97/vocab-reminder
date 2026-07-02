package com.vocabreminder.android.recommendation

import com.vocabreminder.android.scheduler.SchedulerSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecommendationEngineTest {
    @Test
    fun sharedRecommendationVectorsPass() {
        val vectors = JSONArray(readSharedRecommendationVectors())
        assertTrue("Expected recommendation vectors", vectors.length() >= 7)

        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            when (vector.getString("kind")) {
                "chooseRecommendations" -> assertChooseRecommendationsVector(vector)
                "fillLimit" -> assertFillLimitVector(vector)
                "estimateLevel" -> assertEstimateLevelVector(vector)
                else -> error("Unknown recommendation vector kind: ${vector.getString("kind")}")
            }
        }
    }

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

    @Test
    fun dismissedFeedbackShiftsAwayFromDismissedTopic() {
        val selected = RecommendationEngine.chooseRecommendations(
            userSignals = List(50) { UserVocabularySignal("subtle") },
            feedbackSignals = listOf(RecommendationFeedbackSignal("subtle", "dismissed")),
            existingLemmas = emptySet(),
            dueUserCount = 0,
            settings = SchedulerSettings(dailyWordGoal = 10),
            limit = 4
        )

        assertEquals(4, selected.size)
        assertTrue(selected.none { it.lemma == "subtle" })
        assertTrue(selected.none { it.topic == "academic" })
    }

    private fun assertChooseRecommendationsVector(vector: JSONObject) {
        val selected = RecommendationEngine.chooseRecommendations(
            userSignals = parseUserSignals(vector.optJSONArray("user_signals")),
            feedbackSignals = parseFeedbackSignals(vector.optJSONArray("feedback_signals")),
            existingLemmas = parseStringSet(vector.optJSONArray("existing_lemmas")),
            dueUserCount = vector.optInt("due_user_count", 0),
            settings = parseSettings(vector.optJSONObject("settings")),
            limit = if (vector.has("limit")) vector.getInt("limit") else RecommendationEngine.recommendationFillLimit(
                dueUserCount = vector.optInt("due_user_count", 0),
                existingRecommendationCount = vector.optInt("existing_recommendation_count", 0),
                settings = parseSettings(vector.optJSONObject("settings"))
            )
        )
        val then = vector.getJSONObject("then")
        assertEquals(vector.getString("name"), then.getInt("selected_count"), selected.size)
        then.optJSONArray("frequency_rank_between")?.let { range ->
            assertTrue(vector.getString("name"), selected.all { it.frequencyRank in range.getInt(0)..range.getInt(1) })
        }
        then.optJSONArray("first_topic_in")?.let { topics ->
            assertTrue(vector.getString("name"), selected.first().topic in parseStringSet(topics))
        }
        then.optJSONArray("excluded_lemmas")?.let { lemmas ->
            assertTrue(vector.getString("name"), selected.none { it.lemma in parseStringSet(lemmas) })
        }
        then.optJSONArray("excluded_topics")?.let { topics ->
            assertTrue(vector.getString("name"), selected.none { it.topic in parseStringSet(topics) })
        }
    }

    private fun assertFillLimitVector(vector: JSONObject) {
        val fillLimit = RecommendationEngine.recommendationFillLimit(
            dueUserCount = vector.getInt("due_user_count"),
            existingRecommendationCount = vector.getInt("existing_recommendation_count"),
            settings = parseSettings(vector.optJSONObject("settings"))
        )
        assertEquals(vector.getString("name"), vector.getJSONObject("then").getInt("fill_limit"), fillLimit)
    }

    private fun assertEstimateLevelVector(vector: JSONObject) {
        val baseline = RecommendationEngine.estimateLevel(parseUserSignals(vector.optJSONArray("user_signals")))
        val result = RecommendationEngine.estimateLevel(
            parseUserSignals(vector.optJSONArray("user_signals")),
            parseFeedbackSignals(vector.optJSONArray("feedback_signals"))
        )
        val then = vector.getJSONObject("then")
        if (then.optBoolean("lower_rank_greater_than_baseline", false)) {
            assertTrue(vector.getString("name"), result.lowerRank > baseline.lowerRank)
        }
        if (then.optBoolean("upper_rank_greater_than_baseline", false)) {
            assertTrue(vector.getString("name"), result.upperRank > baseline.upperRank)
        }
    }

    private fun parseUserSignals(signals: JSONArray?): List<UserVocabularySignal> {
        if (signals == null) return emptyList()
        return (0 until signals.length()).flatMap { index ->
            val signal = signals.getJSONObject(index)
            List(signal.optInt("count", 1)) {
                UserVocabularySignal(
                    lemma = signal.getString("lemma"),
                    lapseCount = signal.optInt("lapse_count", 0),
                    rememberedCount = signal.optInt("remembered_count", 0),
                    forgottenCount = signal.optInt("forgotten_count", 0)
                )
            }
        }
    }

    private fun parseFeedbackSignals(signals: JSONArray?): List<RecommendationFeedbackSignal> {
        if (signals == null) return emptyList()
        return (0 until signals.length()).map { index ->
            val signal = signals.getJSONObject(index)
            RecommendationFeedbackSignal(
                lemma = signal.getString("lemma"),
                action = signal.getString("action")
            )
        }
    }

    private fun parseSettings(settings: JSONObject?): SchedulerSettings {
        return SchedulerSettings(
            dailyWordGoal = settings?.optInt("daily_word_goal", 10) ?: 10,
            maxQueueSize = settings?.optInt("max_queue_size", 20) ?: 20,
            enableWordRecommendations = settings?.optBoolean("enable_word_recommendations", true) ?: true
        )
    }

    private fun parseStringSet(values: JSONArray?): Set<String> {
        if (values == null) return emptySet()
        return (0 until values.length()).map { values.getString(it) }.toSet()
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

    private fun readSharedRecommendationVectors(): String {
        val candidates = listOf(
            File("../../../shared/test-vectors/recommendations.json"),
            File("../../shared/test-vectors/recommendations.json"),
            File("../shared/test-vectors/recommendations.json"),
            File("shared/test-vectors/recommendations.json"),
            File(System.getProperty("user.dir"), "../../../shared/test-vectors/recommendations.json"),
            File(System.getProperty("user.dir"), "../../shared/test-vectors/recommendations.json")
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("Could not find shared/test-vectors/recommendations.json from ${System.getProperty("user.dir")}")
    }
}
