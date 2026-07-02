package com.vocabreminder.android.recommendation

import com.vocabreminder.android.capture.CaptureMethod
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.dictionary.DictionaryResult
import com.vocabreminder.android.scheduler.SchedulerSettings
import java.time.Instant

data class RecommendationSeed(
    val lemma: String,
    val meaningKo: String,
    val definitionEn: String,
    val partOfSpeech: String,
    val exampleSentence: String,
    val frequencyBand: Int,
    val topic: String
)

object RecommendationEngine {
    private val seeds = listOf(
        RecommendationSeed("ubiquitous", "어디에나 있는, 아주 흔한", "present or found everywhere", "adjective", "Smartphones are ubiquitous these days.", 9000, "general-academic"),
        RecommendationSeed("salient", "두드러진, 현저한", "most noticeable or important", "adjective", "The salient feature was the delayed response.", 9500, "academic"),
        RecommendationSeed("mitigate", "완화하다, 줄이다", "to make something less harmful or serious", "verb", "The protocol mitigated measurement noise.", 10000, "academic"),
        RecommendationSeed("plausible", "그럴듯한, 타당해 보이는", "seeming reasonable or probable", "adjective", "The authors offered a plausible explanation.", 8500, "academic"),
        RecommendationSeed("ambiguous", "애매한, 여러 뜻으로 해석되는", "open to more than one interpretation", "adjective", "The ambiguous result required another experiment.", 8000, "academic"),
        RecommendationSeed("subtle", "미묘한, 감지하기 어려운", "delicate or not obvious", "adjective", "The change produced a subtle improvement.", 7000, "academic"),
        RecommendationSeed("coherent", "일관된, 논리적으로 연결된", "logical and consistent", "adjective", "The model produced a coherent summary.", 9500, "academic"),
        RecommendationSeed("empirical", "경험적 자료에 근거한", "based on observation or experiment", "adjective", "The claim needs empirical support.", 11000, "academic")
    )

    fun ensureRecommendations(store: LocalVocabularyStore, settings: SchedulerSettings, now: Instant): Int {
        if (!settings.enableWordRecommendations || store.userWordCount() < 50) return 0

        val existing = store.allCards(includeDeleted = true).map { it.lemma }.toSet()
        val dueUserCount = store.cards().count { it.wordbook == com.vocabreminder.android.scheduler.Wordbook.USER && !it.dueAt.isAfter(now) }
        val desired = (settings.dailyWordGoal - dueUserCount).coerceAtLeast(0)
        if (desired == 0) return 0

        val missing = seeds.filter { it.lemma !in existing }.take(desired)
        missing.forEach { seed ->
            store.addRecommendedCard(
                result = seed.toDictionaryResult(),
                captureMethod = CaptureMethod.RECOMMENDATION,
                now = now
            )
        }
        return missing.size
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
}
