package com.vocabreminder.android.recommendation

import com.vocabreminder.android.capture.CaptureMethod
import com.vocabreminder.android.data.LocalVocabularyStore
import com.vocabreminder.android.dictionary.DictionaryResult
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
    val catalog: List<RecommendationSeed> = listOf(
        RecommendationSeed("subtle", "미묘한, 감지하기 어려운", "delicate or not obvious", "adjective", "The change produced a subtle improvement.", 5566, "academic"),
        RecommendationSeed("preliminary", "예비의, 초기 단계의", "coming before a more important action or event", "adjective", "These are preliminary results.", 7066, "academic"),
        RecommendationSeed("substantial", "상당한, 실질적인", "large in amount, value, or importance", "adjective", "The model showed a substantial improvement.", 8446, "academic"),
        RecommendationSeed("threshold", "기준점, 문턱값", "the level at which something starts to happen", "noun", "Responses above the threshold were counted.", 9788, "statistics"),
        RecommendationSeed("dense", "빽빽한, 조밀한", "closely compacted together", "adjective", "The dense matrix was hard to inspect.", 11486, "methods"),
        RecommendationSeed("compelling", "설득력 있는, 강력한", "evoking interest or attention in a powerful way", "adjective", "The evidence was compelling.", 12186, "academic"),
        RecommendationSeed("trajectory", "궤적, 진행 경로", "the path followed by a moving object or process", "noun", "The learning trajectory changed over time.", 12459, "methods"),
        RecommendationSeed("plausible", "그럴듯한, 타당해 보이는", "seeming reasonable or probable", "adjective", "The authors offered a plausible explanation.", 13330, "academic"),
        RecommendationSeed("bias", "편향, 편견", "a systematic tendency or prejudice", "noun", "Sampling bias affected the result.", 13443, "statistics"),
        RecommendationSeed("adaptation", "적응, 조정", "the process of changing to fit new conditions", "noun", "Adaptation occurred after repeated exposure.", 13523, "neuroscience"),
        RecommendationSeed("suppress", "억제하다, 누르다", "to prevent something from being expressed", "verb", "The mask suppressed the response.", 14026, "neuroscience"),
        RecommendationSeed("prospective", "장래의, 전향적인", "expected or likely to happen in the future", "adjective", "The prospective analysis was preregistered.", 14926, "methods"),
        RecommendationSeed("subsequent", "그 다음의, 후속의", "coming after something in time", "adjective", "Subsequent trials used a shorter delay.", 15388, "academic"),
        RecommendationSeed("approximate", "대략의, 근사적인", "close to the actual value but not exact", "adjective", "The approximate value was sufficient.", 15415, "statistics"),
        RecommendationSeed("confound", "교란 요인, 혼동시키다", "to confuse or mix effects together", "verb", "Motion can confound the measurement.", 16083, "methods"),
        RecommendationSeed("artifact", "인공 산물, 잡음", "an unintended result of a process or measurement", "noun", "The signal contained a measurement artifact.", 16645, "methods"),
        RecommendationSeed("contingent", "조건부의, ~에 달린", "depending on something else", "adjective", "The effect was contingent on task difficulty.", 16696, "academic"),
        RecommendationSeed("coherent", "일관된, 논리적으로 연결된", "logical and consistent", "adjective", "The model produced a coherent summary.", 17048, "academic"),
        RecommendationSeed("facilitate", "촉진하다, 쉽게 하다", "to make an action or process easier", "verb", "Feedback facilitated learning.", 17531, "neuroscience"),
        RecommendationSeed("hypothesis", "가설", "a proposed explanation made for testing", "noun", "The hypothesis predicted a delayed response.", 18879, "academic"),
        RecommendationSeed("validate", "검증하다, 타당성을 확인하다", "to check that something is sound or accurate", "verb", "A second dataset validated the model.", 19496, "methods"),
        RecommendationSeed("complement", "보완하다, 보완물", "to add to something in a useful way", "verb", "The behavioral result complemented the neural data.", 19918, "academic"),
        RecommendationSeed("latent", "잠재적인, 숨어 있는", "present but not visible or active", "adjective", "The latent variable captured task difficulty.", 19950, "statistics"),
        RecommendationSeed("ambiguous", "애매한, 여러 뜻으로 해석되는", "open to more than one interpretation", "adjective", "The ambiguous result required another experiment.", 20465, "academic"),
        RecommendationSeed("replicate", "재현하다, 반복하다", "to repeat a study or result", "verb", "The lab tried to replicate the finding.", 20588, "methods"),
        RecommendationSeed("paradigm", "패러다임, 실험 틀", "a typical example or model", "noun", "The paradigm separated memory and attention.", 20673, "methods"),
        RecommendationSeed("robust", "견고한, 안정적인", "strong and unlikely to fail", "adjective", "The model showed robust decoding performance.", 20743, "statistics"),
        RecommendationSeed("derive", "도출하다, 얻다", "to obtain something from a source", "verb", "The score was derived from reaction times.", 21072, "methods"),
        RecommendationSeed("converge", "수렴하다, 한 점으로 모이다", "to come together toward one point", "verb", "The estimates converge after many trials.", 21937, "statistics"),
        RecommendationSeed("infer", "추론하다", "to reach a conclusion from evidence", "verb", "The model inferred the hidden state.", 23151, "statistics"),
        RecommendationSeed("stimulus", "자극", "something that causes a response", "noun", "The stimulus elicited a strong response.", 24323, "neuroscience"),
        RecommendationSeed("elicit", "이끌어내다, 유도하다", "to draw out a response, answer, or fact", "verb", "The stimulus elicited a strong response.", 24533, "neuroscience"),
        RecommendationSeed("tentative", "잠정적인, 임시의", "not certain or fixed", "adjective", "The results are tentative.", 24715, "academic"),
        RecommendationSeed("empirical", "경험적 자료에 근거한", "based on observation or experiment", "adjective", "The claim needs empirical support.", 25594, "academic"),
        RecommendationSeed("ubiquitous", "어디에나 있는, 아주 흔한", "present or found everywhere", "adjective", "Smartphones are ubiquitous these days.", 25633, "general"),
        RecommendationSeed("mitigate", "완화하다, 줄이다", "to make something less harmful or serious", "verb", "The protocol mitigated measurement noise.", 26651, "methods"),
        RecommendationSeed("negligible", "무시할 만한, 사소한", "so small as to be unimportant", "adjective", "The residual error was negligible.", 27140, "statistics"),
        RecommendationSeed("variance", "분산, 변동성", "the degree to which values differ", "noun", "The variance increased in the noisy condition.", 28071, "statistics"),
        RecommendationSeed("decoding", "해독, 디코딩", "the process of converting signals into information", "noun", "Decoding accuracy was above chance.", 28526, "neuroscience"),
        RecommendationSeed("pervasive", "널리 퍼진, 만연한", "spreading widely through an area or group", "adjective", "The bias was pervasive across datasets.", 29739, "academic"),
        RecommendationSeed("retrospective", "회고적인, 후향적인", "looking back on or dealing with past events", "adjective", "The retrospective analysis used existing records.", 30633, "methods"),
        RecommendationSeed("salient", "두드러진, 현저한", "most noticeable or important", "adjective", "The salient feature was the delayed response.", 32241, "neuroscience"),
        RecommendationSeed("inference", "추론", "a conclusion reached from evidence", "noun", "Inference depended on the prior distribution.", 33336, "statistics"),
        RecommendationSeed("criterion", "기준, 판단 기준", "a standard by which something is judged", "noun", "The exclusion criterion was defined in advance.", 33929, "methods"),
        RecommendationSeed("sparse", "드문드문한, 희소한", "thinly distributed or scattered", "adjective", "The sparse representation improved interpretability.", 34121, "statistics"),
        RecommendationSeed("aggregate", "합산하다, 집계하다", "to collect into a whole", "verb", "The results were aggregated across subjects.", 35039, "methods"),
        RecommendationSeed("intrinsic", "본질적인, 내재적인", "belonging naturally to something", "adjective", "The task measured intrinsic motivation.", 35213, "academic"),
        RecommendationSeed("gradient", "기울기, 변화도", "a rate of change across space or value", "noun", "The gradient was steeper in the control group.", 37167, "statistics"),
        RecommendationSeed("equivocal", "애매한, 분명하지 않은", "open to more than one interpretation", "adjective", "The evidence remained equivocal.", 38706, "academic"),
        RecommendationSeed("encoding", "부호화, 인코딩", "the process of converting information into a form", "noun", "Encoding improved after repeated exposure.", 39479, "neuroscience"),
        RecommendationSeed("hierarchical", "계층적인", "arranged in levels of importance", "adjective", "The hierarchical model captured subject effects.", 43643, "statistics"),
        RecommendationSeed("modulate", "조절하다, 변화시키다", "to influence or adjust something", "verb", "Attention modulated the neural response.", 43834, "neuroscience"),
        RecommendationSeed("generalize", "일반화하다", "to apply more broadly", "verb", "The finding may not generalize to children.", 46393, "methods"),
        RecommendationSeed("plasticity", "가소성", "the ability to change or adapt", "noun", "Plasticity was strongest early in training.", 46947, "neuroscience"),
        RecommendationSeed("attenuate", "약화시키다, 감소시키다", "to reduce the force or effect of something", "verb", "The control reduced but did not attenuate the effect.", 49112, "neuroscience"),
        RecommendationSeed("homogeneous", "동질적인", "of the same kind throughout", "adjective", "The sample was relatively homogeneous.", 50243, "methods"),
        RecommendationSeed("mediated", "매개된, 중재된", "brought about through an intermediate factor", "adjective", "The effect was mediated by attention.", 56308, "statistics"),
        RecommendationSeed("heterogeneous", "이질적인, 다양한", "diverse in character or content", "adjective", "The population was heterogeneous.", 59533, "methods"),
        RecommendationSeed("canonical", "표준적인, 정식의", "accepted as standard or authoritative", "adjective", "The canonical response appeared after 300 ms.", 62053, "academic"),
        RecommendationSeed("diverge", "갈라지다, 달라지다", "to separate or become different", "verb", "The two trajectories diverge after training.", 63833, "statistics")
    )

    fun ensureRecommendations(store: LocalVocabularyStore, settings: SchedulerSettings, now: Instant): Int {
        val userCards = store.cards().filter { it.wordbook == Wordbook.USER }
        val dueUserCount = userCards.count { !it.dueAt.isAfter(now) }
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
            settings = settings
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

    fun chooseRecommendations(
        userSignals: List<UserVocabularySignal>,
        feedbackSignals: List<RecommendationFeedbackSignal>,
        existingLemmas: Set<String>,
        dueUserCount: Int,
        settings: SchedulerSettings,
        limit: Int = (settings.dailyWordGoal - dueUserCount).coerceAtLeast(0)
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
}
