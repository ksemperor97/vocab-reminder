package com.vocabreminder.android.data

import java.time.Instant

enum class DictionaryEnrichmentStatus {
    PENDING,
    FAILED,
    RESOLVED,
    USER_LOCKED
}

enum class DictionaryEnrichmentFailure {
    NETWORK,
    NOT_FOUND,
    PROVIDER_FAILURE
}

data class DictionaryEnrichmentState(
    val cardId: String,
    val status: DictionaryEnrichmentStatus,
    val failure: DictionaryEnrichmentFailure? = null,
    val attemptedAt: Instant? = null,
    val requestId: String? = null
)
