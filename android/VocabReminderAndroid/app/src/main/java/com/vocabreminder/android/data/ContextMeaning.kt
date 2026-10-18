package com.vocabreminder.android.data

import com.vocabreminder.android.dictionary.DictionaryLookupResult
import com.vocabreminder.android.dictionary.DictionaryResult
import com.vocabreminder.android.scheduler.ReviewResponse
import com.vocabreminder.android.scheduler.Wordbook
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

// Context meanings (guide 10.6, 10.8): the sync server can pick the Korean
// meaning that fits the sentence a word was captured in. A card that already
// has a dictionary meaning is asked once per lemma + sentence; the answer
// replaces the meaning only when it came from the sentence (llm_context) and
// the user has not pinned a meaning. Priority: user > llm_context > dictionary.
// Shared contract: shared/test-vectors/context_meanings.json.

internal data class ContextAttempt(val key: String, val retryAt: Instant?)

internal object ContextMeaning {
    const val SOURCE = "llm_context"
    private val SOON: Duration = Duration.ofMinutes(15)
    private val LATER: Duration = Duration.ofHours(24)
    private val WHITESPACE = Regex("\\s+")

    fun sentence(card: VocabularyCard): String = card.exampleSentence.trim().replace(WHITESPACE, " ").take(1_000)

    fun key(card: VocabularyCard): String = MessageDigest.getInstance("SHA-256")
        .digest("${card.lemma.trim().lowercase()}\u0000${sentence(card)}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    /** When to ask again for the same sentence; null means never. */
    fun retryAt(contextStatus: String?, now: Instant): Instant? = when (contextStatus) {
        "resolved", "unresolved", "no_context" -> null
        "failed", "deferred" -> now.plus(SOON)
        else -> now.plus(LATER) // "disabled", or a server that predates context meanings
    }

    fun isResolvedFromContext(result: DictionaryLookupResult): Boolean =
        result.contextStatus == "resolved" && result.sourceType == SOURCE && result.meaningKo.isNotBlank()

    fun encode(attempts: Map<String, ContextAttempt>): String = JSONObject().apply {
        attempts.forEach { (cardId, attempt) ->
            put(cardId, JSONObject().put("key", attempt.key).put("retry_at", attempt.retryAt?.toString() ?: JSONObject.NULL))
        }
    }.toString()

    fun decode(raw: String?): Map<String, ContextAttempt> {
        val json = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
        return json.keys().asSequence().mapNotNull { cardId ->
            val entry = json.optJSONObject(cardId) ?: return@mapNotNull null
            val key = entry.optString("key").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            cardId to ContextAttempt(key, runCatching { entry.optInstant("retry_at") }.getOrNull())
        }.toMap()
    }
}

/** The user typed this meaning, chose a candidate, or restored one, on any device. */
internal fun LocalVocabularyStore.isMeaningPinned(card: VocabularyCard): Boolean =
    card.definitionSourcePrimary == "user" ||
        persistedDictionaryEnrichmentStates()[card.id]?.status == DictionaryEnrichmentStatus.USER_LOCKED ||
        eventsFor(card.id).any { it.response == ReviewResponse.MANUAL_EDIT && it.metadata["action"] != "rebalance" }

internal fun LocalVocabularyStore.cardsNeedingContextMeaning(limit: Int, now: Instant): List<VocabularyCard> {
    if (limit <= 0) return emptyList()
    val attempts = contextAttempts()
    return cards().asSequence()
        .filter { it.wordbook == Wordbook.USER && it.meaningKo.isNotBlank() }
        .filter { it.definitionSourcePrimary != "user" && it.definitionSourcePrimary != ContextMeaning.SOURCE }
        .filter { ContextMeaning.sentence(it).isNotBlank() }
        .filter { card ->
            val attempt = attempts[card.id]
            attempt == null || attempt.key != ContextMeaning.key(card) || (attempt.retryAt != null && !attempt.retryAt.isAfter(now))
        }
        .filterNot(::isMeaningPinned)
        .take(limit)
        .toList()
}

internal fun LocalVocabularyStore.hasCardsNeedingContextMeaning(now: Instant): Boolean =
    cardsNeedingContextMeaning(limit = 1, now = now).isNotEmpty()

/** Records that this card's sentence was sent, so it is not sent again before retryAt. */
internal fun LocalVocabularyStore.recordContextAttempt(card: VocabularyCard, result: DictionaryLookupResult?, now: Instant) =
    synchronized(LocalVocabularyStore.CARD_MUTATION_LOCK) {
        val current = findCard(card.id) ?: return@synchronized
        if (ContextMeaning.key(current) != ContextMeaning.key(card)) return@synchronized
        commitLocal(
            LocalVocabularyStore.LocalChanges(
                contextAttempts = mapOf(card.id to ContextAttempt(ContextMeaning.key(card), ContextMeaning.retryAt(result?.contextStatus, now)))
            )
        )
    }

/**
 * Applies a context result to a card that already has a meaning. Returns the
 * updated card when its meaning changed. A pinned card keeps its meaning and
 * gains the context meaning as an unselected candidate.
 */
internal fun LocalVocabularyStore.applyContextMeaning(
    requested: VocabularyCard,
    result: DictionaryLookupResult,
    now: Instant
): VocabularyCard? = synchronized(LocalVocabularyStore.CARD_MUTATION_LOCK) {
    val current = findCard(requested.id) ?: return@synchronized null
    val key = ContextMeaning.key(requested)
    if (current.meaningKo.isBlank() || ContextMeaning.key(current) != key) return@synchronized null
    val attempt = mapOf(current.id to ContextAttempt(key, ContextMeaning.retryAt(result.contextStatus, now)))
    if (!ContextMeaning.isResolvedFromContext(result) || current.definitionSourcePrimary == ContextMeaning.SOURCE) {
        commitLocal(LocalVocabularyStore.LocalChanges(contextAttempts = attempt))
        return@synchronized null
    }

    val existing = definitionCandidatesFor(current.id)
    val meaning = result.meaningKo.trim()
    val partOfSpeech = result.partOfSpeech.ifBlank { current.partOfSpeech }.trim()
    if (isMeaningPinned(current)) {
        val alreadyKept = existing.any { it.source == ContextMeaning.SOURCE && it.parsedMeaningKo == meaning }
        commitLocal(
            LocalVocabularyStore.LocalChanges(
                candidates = if (alreadyKept) emptyList() else listOf(contextCandidate(current, result, meaning, partOfSpeech, selected = false, now = now)),
                contextAttempts = attempt
            )
        )
        return@synchronized null
    }

    val updated = current.copy(
        meaningKo = meaning,
        partOfSpeech = partOfSpeech,
        definitionSourcePrimary = ContextMeaning.SOURCE,
        updatedAt = now,
        version = current.version + 1
    )
    commitLocal(
        LocalVocabularyStore.LocalChanges(
            cards = listOf(updated),
            candidates = listOfNotNull(buildMeaningCandidateIfNeeded(current, existing, now)) +
                contextCandidate(updated, result, meaning, partOfSpeech, selected = true, now = now, baseCardVersion = current.version),
            contextAttempts = attempt
        )
    )
    findCard(updated.id) ?: updated
}

private fun LocalVocabularyStore.contextCandidate(
    card: VocabularyCard,
    result: DictionaryLookupResult,
    meaning: String,
    partOfSpeech: String,
    selected: Boolean,
    now: Instant,
    baseCardVersion: Int? = null
) = buildDefinitionCandidate(
    cardId = card.id,
    result = DictionaryResult(
        lemma = card.lemma,
        displayWord = card.displayWord,
        meaningKo = meaning,
        definitionEn = card.definitionEn,
        partOfSpeech = partOfSpeech,
        exampleSentence = result.contextSentence.ifBlank { card.exampleSentence },
        rawSource = meaning,
        sourceType = ContextMeaning.SOURCE,
        confidence = result.confidence
    ),
    selected = selected,
    now = result.attemptedAt ?: now,
    originDeviceId = deviceId().takeIf { selected },
    baseCardVersion = baseCardVersion
)
