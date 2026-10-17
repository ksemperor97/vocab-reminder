package com.vocabreminder.android.dictionary

import java.time.Instant

enum class DictionaryLookupStatus {
    RESOLVED,
    PENDING
}

enum class DictionaryLookupErrorCode {
    NOT_FOUND,
    PROVIDER_FAILURE
}

data class DictionaryLookupResult(
    val status: DictionaryLookupStatus,
    val lemma: String,
    val meaningKo: String,
    val definitionEn: String,
    val partOfSpeech: String = "",
    val exampleSentence: String = "",
    val sourceType: String = "dictionary",
    val confidence: Double = 0.0,
    val attemptedAt: Instant? = null,
    val errorCode: DictionaryLookupErrorCode? = null,
    val requestId: String? = null,
    val senses: List<DictionarySense> = emptyList()
)

data class DictionarySense(
    val meaningKo: String,
    val definitionEn: String = "",
    val partOfSpeech: String = "",
    val exampleSentence: String = ""
)
