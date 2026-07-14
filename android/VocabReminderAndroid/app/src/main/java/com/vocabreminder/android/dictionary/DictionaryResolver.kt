package com.vocabreminder.android.dictionary

import com.vocabreminder.android.capture.CaptureAnalysis
import com.vocabreminder.android.capture.CaptureNormalizer
import com.vocabreminder.android.recommendation.RecommendationCatalog

data class DictionaryResult(
    val lemma: String,
    val displayWord: String,
    val meaningKo: String,
    val definitionEn: String,
    val partOfSpeech: String = "",
    val phonetic: String = "",
    val exampleSentence: String = "",
    val rawSource: String = "",
    val sourceType: String,
    val confidence: Double = 0.0,
    val sourceSentence: String = "",
    val sourceApp: String = "",
    val sourceUrl: String = "",
    val sourceTitle: String = ""
)

private data class LocalEntry(
    val meaningKo: String,
    val definitionEn: String,
    val partOfSpeech: String,
    val exampleSentence: String
)

object DictionaryResolver {
    private val localEntries = mapOf(
        "tentative" to LocalEntry(
            meaningKo = "잠정적인, 임시의",
            definitionEn = "not certain or fixed",
            partOfSpeech = "adjective",
            exampleSentence = "The results are tentative."
        ),
        "robust" to LocalEntry(
            meaningKo = "견고한, 안정적인",
            definitionEn = "strong and unlikely to fail",
            partOfSpeech = "adjective",
            exampleSentence = "The model showed robust decoding performance."
        ),
        "behemoth" to LocalEntry(
            meaningKo = "거대한 것, 거대한 짐승",
            definitionEn = "something enormous, powerful, or hard to control",
            partOfSpeech = "noun",
            exampleSentence = "The startup became a behemoth in the software industry."
        ),
        "look" to LocalEntry(
            meaningKo = "보다, 바라보다; 찾아보다",
            definitionEn = "to direct your eyes toward something; to search or check",
            partOfSpeech = "verb",
            exampleSentence = "She was looking for local dining spots."
        ),
        "elicit" to LocalEntry(
            meaningKo = "이끌어내다, 유도하다",
            definitionEn = "to draw out a response, answer, or fact",
            partOfSpeech = "verb",
            exampleSentence = "The stimulus elicited a strong response."
        ),
        "ubiquitous" to LocalEntry(
            meaningKo = "어디에나 있는, 아주 흔한",
            definitionEn = "present or found everywhere",
            partOfSpeech = "adjective",
            exampleSentence = "Smartphones are ubiquitous these days."
        ),
        "salient" to LocalEntry(
            meaningKo = "두드러진, 현저한",
            definitionEn = "most noticeable or important",
            partOfSpeech = "adjective",
            exampleSentence = "The salient feature was the delayed response."
        ),
        "resilient" to LocalEntry(
            meaningKo = "회복력 있는, 탄력 있는",
            definitionEn = "able to recover quickly from difficulty",
            partOfSpeech = "adjective",
            exampleSentence = "A resilient system recovers quickly."
        ),
        "mitigate" to LocalEntry(
            meaningKo = "완화하다, 줄이다",
            definitionEn = "to make something less harmful or serious",
            partOfSpeech = "verb",
            exampleSentence = "The protocol mitigated measurement noise."
        )
    )
    private val catalogEntries by lazy {
        RecommendationCatalog.seeds.associate { seed ->
            seed.lemma to LocalEntry(
                meaningKo = seed.meaningKo,
                definitionEn = seed.definitionEn,
                partOfSpeech = seed.partOfSpeech,
                exampleSentence = seed.exampleSentence
            )
        }
    }

    fun resolve(
        rawText: String,
        sourceApp: String = "",
        sourceUrl: String = "",
        sourceTitle: String = ""
    ): DictionaryResult {
        return resolve(CaptureNormalizer.analyze(rawText), sourceApp, sourceUrl, sourceTitle)
    }

    fun resolve(
        analysis: CaptureAnalysis,
        sourceApp: String = "",
        sourceUrl: String = "",
        sourceTitle: String = ""
    ): DictionaryResult {
        val entry = localEntries[analysis.lemma]
            ?: localEntries[analysis.displayWord.lowercase()]
            ?: catalogEntries[analysis.lemma]
            ?: catalogEntries[analysis.displayWord.lowercase()]
        val exampleSentence = analysis.sourceSentence.ifBlank { entry?.exampleSentence.orEmpty() }
        return if (entry != null) {
            // guide 10.4: Android uses app/local dictionary before cloud fallback.
            DictionaryResult(
                lemma = analysis.lemma,
                displayWord = analysis.displayWord,
                meaningKo = entry.meaningKo,
                definitionEn = entry.definitionEn,
                partOfSpeech = entry.partOfSpeech,
                exampleSentence = exampleSentence,
                rawSource = "${entry.partOfSpeech}: ${entry.definitionEn}",
                sourceType = "local_dictionary",
                confidence = 0.82,
                sourceSentence = analysis.sourceSentence,
                sourceApp = sourceApp,
                sourceUrl = sourceUrl,
                sourceTitle = sourceTitle
            )
        } else {
            // guide 2.8/10.8: saving is allowed even when automatic meaning lookup fails.
            DictionaryResult(
                lemma = analysis.lemma,
                displayWord = analysis.displayWord,
                meaningKo = "",
                definitionEn = "",
                exampleSentence = analysis.sourceSentence,
                rawSource = "",
                sourceType = "pending_capture",
                confidence = 0.0,
                sourceSentence = analysis.sourceSentence,
                sourceApp = sourceApp,
                sourceUrl = sourceUrl,
                sourceTitle = sourceTitle
            )
        }
    }
}
