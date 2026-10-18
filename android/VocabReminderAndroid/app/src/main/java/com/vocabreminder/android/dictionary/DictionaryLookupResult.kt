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
    val senses: List<DictionarySense> = emptyList(),
    /** How the server used the sentence: resolved, unresolved, failed, deferred, disabled, no_context; null from older servers. */
    val contextStatus: String? = null,
    val contextSentence: String = "",
    /** The dictionary result the server set aside when it picked a meaning from the sentence. */
    val dictionary: DictionaryFallback? = null
)

data class DictionaryLookupRequest(
    val lemma: String,
    val sentence: String = ""
)

data class DictionaryFallback(
    val meaningKo: String,
    val definitionEn: String = "",
    val partOfSpeech: String = "",
    val exampleSentence: String = "",
    val sourceType: String = "dictionary",
    val confidence: Double = 0.0
)

data class DictionarySense(
    val meaningKo: String,
    val definitionEn: String = "",
    val partOfSpeech: String = "",
    val exampleSentence: String = ""
)
