package com.vocabreminder.android.data

import com.vocabreminder.android.dictionary.DictionaryLookupResult
import com.vocabreminder.android.dictionary.DictionaryLookupStatus
import com.vocabreminder.android.dictionary.DictionaryResult
import com.vocabreminder.android.scheduler.DueTimeJitter
import com.vocabreminder.android.scheduler.Scheduler
import com.vocabreminder.android.sync.normalizeRequestId
import java.time.Instant
import java.util.UUID

// Background dictionary enrichment: cards saved without a meaning are filled in
// later, unless the user has written or chosen the meaning themselves.

fun LocalVocabularyStore.dictionaryEnrichmentState(cardId: String): DictionaryEnrichmentState? {
    val card = findCard(cardId) ?: return null
    return derivedDictionaryEnrichmentState(card, persistedDictionaryEnrichmentStates()[cardId])
}

fun LocalVocabularyStore.dictionaryEnrichmentStates(): List<DictionaryEnrichmentState> {
    val persisted = persistedDictionaryEnrichmentStates()
    return cards().map { card -> derivedDictionaryEnrichmentState(card, persisted[card.id]) }
}

internal fun LocalVocabularyStore.cardsNeedingDictionaryEnrichment(limit: Int): List<VocabularyCard> {
    if (limit <= 0) return emptyList()
    val persisted = persistedDictionaryEnrichmentStates()
    return cards().asSequence()
        .filter { it.meaningKo.isBlank() }
        .filter { card ->
            val state = derivedDictionaryEnrichmentState(card, persisted[card.id])
            state.status == DictionaryEnrichmentStatus.PENDING ||
                (state.status == DictionaryEnrichmentStatus.FAILED &&
                    state.failure in setOf(DictionaryEnrichmentFailure.NETWORK, DictionaryEnrichmentFailure.PROVIDER_FAILURE))
        }
        .take(limit)
        .toList()
}

internal fun LocalVocabularyStore.hasCardsNeedingDictionaryEnrichment(): Boolean =
    cardsNeedingDictionaryEnrichment(limit = 1).isNotEmpty()

internal fun LocalVocabularyStore.recordDictionaryEnrichmentFailure(
    cardIds: Collection<String>,
    failure: DictionaryEnrichmentFailure,
    attemptedAt: Instant,
    requestId: String? = null
) = synchronized(LocalVocabularyStore.CARD_MUTATION_LOCK) {
    val states = cardIds.mapNotNull { cardId ->
        val card = findCard(cardId)
        if (card != null && card.meaningKo.isBlank() && card.definitionSourcePrimary != "user") {
            DictionaryEnrichmentState(cardId, DictionaryEnrichmentStatus.FAILED, failure, attemptedAt, normalizeRequestId(requestId))
        } else {
            null
        }
    }
    if (states.isNotEmpty()) commitLocal(LocalVocabularyStore.LocalChanges(enrichmentStates = states))
}

internal fun LocalVocabularyStore.markDictionaryEnrichmentPending(cardIds: Collection<String>) =
    synchronized(LocalVocabularyStore.CARD_MUTATION_LOCK) {
        val existing = persistedDictionaryEnrichmentStates()
        val states = cardIds.mapNotNull { cardId ->
            val card = findCard(cardId)
            if (card != null && card.meaningKo.isBlank() &&
                derivedDictionaryEnrichmentState(card, existing[cardId]).status != DictionaryEnrichmentStatus.USER_LOCKED
            ) {
                DictionaryEnrichmentState(
                    cardId = cardId,
                    status = DictionaryEnrichmentStatus.PENDING,
                    attemptedAt = existing[cardId]?.attemptedAt,
                    requestId = existing[cardId]?.requestId
                )
            } else {
                null
            }
        }
        if (states.isNotEmpty()) commitLocal(LocalVocabularyStore.LocalChanges(enrichmentStates = states))
    }

fun LocalVocabularyStore.retryDictionaryEnrichment(cardId: String? = null) = synchronized(LocalVocabularyStore.CARD_MUTATION_LOCK) {
    val now = Instant.now()
    val targets = (if (cardId == null) allCards() else listOfNotNull(findCard(cardId))).filter { it.meaningKo.isBlank() }
    val unlocked = targets.filter { it.definitionSourcePrimary == "user" }
        .map { it.copy(definitionSourcePrimary = "dictionary_retry", updatedAt = now, version = it.version + 1) }
    commitLocal(
        LocalVocabularyStore.LocalChanges(
            cards = unlocked,
            enrichmentStates = targets.map { DictionaryEnrichmentState(it.id, DictionaryEnrichmentStatus.PENDING) }
        )
    )
    enqueueDictionaryEnrichmentSafely()
}

internal fun LocalVocabularyStore.applyDictionaryEnrichment(
    cardId: String,
    result: DictionaryLookupResult,
    now: Instant
): VocabularyCard? = synchronized(LocalVocabularyStore.CARD_MUTATION_LOCK) {
    val current = findCard(cardId) ?: return@synchronized null
    if (current.meaningKo.isNotBlank() || current.definitionSourcePrimary == "user") {
        val state = dictionaryStateForCard(current, result.attemptedAt ?: now, result.requestId)
        commitLocal(LocalVocabularyStore.LocalChanges(enrichmentStates = listOf(state)))
        return@synchronized null
    }
    if (result.status != DictionaryLookupStatus.RESOLVED || result.meaningKo.isBlank()) return@synchronized null

    val schedulerSettings = settings().toSchedulerSettings()
    val firstDueAt = DueTimeJitter.apply(Scheduler.initialDueAt(now, schedulerSettings), now, schedulerSettings)
    val updated = current.copy(
        meaningKo = result.meaningKo.trim(),
        definitionEn = result.definitionEn.ifBlank { current.definitionEn }.trim(),
        partOfSpeech = result.partOfSpeech.ifBlank { current.partOfSpeech }.trim(),
        exampleSentence = current.exampleSentence.ifBlank { result.exampleSentence }.trim(),
        definitionSourcePrimary = result.sourceType,
        dueAt = firstDueAt,
        updatedAt = now,
        version = current.version + 1
    )
    val primary = buildDefinitionCandidate(
        cardId = updated.id,
        result = lookupAsDictionaryResult(updated, result, meaningKo = result.meaningKo),
        selected = true,
        now = result.attemptedAt ?: now,
        originDeviceId = deviceId(),
        baseCardVersion = current.version
    )
    val candidates = mutableListOf(primary)
    val existing = definitionCandidatesFor(updated.id) + primary
    // The server picked this meaning from the captured sentence; the dictionary
    // gloss it set aside stays restorable under its own source.
    result.dictionary?.let { fallback ->
        val duplicate = existing.any { it.source == fallback.sourceType && it.parsedMeaningKo == fallback.meaningKo }
        if (fallback.meaningKo.isNotBlank() && !duplicate) {
            candidates += primary.copy(
                id = UUID.randomUUID().toString(),
                source = fallback.sourceType,
                rawText = listOf(fallback.meaningKo, fallback.definitionEn).filter { it.isNotBlank() }.joinToString("\n"),
                parsedMeaningKo = fallback.meaningKo,
                parsedDefinitionEn = fallback.definitionEn,
                partOfSpeech = fallback.partOfSpeech,
                exampleSentence = fallback.exampleSentence,
                confidence = fallback.confidence,
                selectedAsPrimary = false
            )
        }
    }
    val senseSource = result.dictionary?.sourceType ?: primary.source
    result.senses.take(20).forEach { sense ->
        val duplicate = (existing + candidates).any {
            it.parsedMeaningKo == sense.meaningKo && it.parsedDefinitionEn == sense.definitionEn && it.partOfSpeech == sense.partOfSpeech
        }
        if ((sense.meaningKo.isNotBlank() || sense.definitionEn.isNotBlank()) && !duplicate) {
            candidates += primary.copy(
                id = UUID.randomUUID().toString(),
                source = senseSource,
                rawText = listOf(sense.meaningKo, sense.definitionEn).joinToString("\n"),
                parsedMeaningKo = sense.meaningKo,
                parsedDefinitionEn = sense.definitionEn,
                partOfSpeech = sense.partOfSpeech,
                exampleSentence = sense.exampleSentence,
                selectedAsPrimary = false
            )
        }
    }
    commitLocal(
        LocalVocabularyStore.LocalChanges(
            cards = listOf(updated),
            candidates = candidates,
            enrichmentStates = listOf(dictionaryStateForCard(updated, result.attemptedAt ?: now, result.requestId))
        )
    )
    findCard(updated.id) ?: updated
}

internal fun LocalVocabularyStore.applyDictionaryDefinitionFallback(
    cardId: String,
    result: DictionaryLookupResult,
    now: Instant
): Boolean = synchronized(LocalVocabularyStore.CARD_MUTATION_LOCK) {
    val current = findCard(cardId) ?: return@synchronized false
    if (current.meaningKo.isNotBlank() || current.definitionSourcePrimary == "user" || result.definitionEn.isBlank()) {
        return@synchronized false
    }
    val updated = current.copy(
        definitionEn = result.definitionEn.trim(),
        partOfSpeech = result.partOfSpeech.ifBlank { current.partOfSpeech }.trim(),
        exampleSentence = current.exampleSentence.ifBlank { result.exampleSentence }.trim(),
        updatedAt = now,
        version = current.version + 1
    )
    commitLocal(
        LocalVocabularyStore.LocalChanges(
            cards = listOf(updated),
            candidates = listOf(
                buildDefinitionCandidate(
                    cardId = updated.id,
                    result = lookupAsDictionaryResult(updated, result, meaningKo = ""),
                    selected = false,
                    now = result.attemptedAt ?: now
                )
            ),
            enrichmentStates = listOf(
                DictionaryEnrichmentState(
                    cardId = updated.id,
                    status = DictionaryEnrichmentStatus.FAILED,
                    failure = DictionaryEnrichmentFailure.NOT_FOUND,
                    attemptedAt = result.attemptedAt ?: now,
                    requestId = normalizeRequestId(result.requestId)
                )
            )
        )
    )
    true
}

private fun derivedDictionaryEnrichmentState(card: VocabularyCard, persisted: DictionaryEnrichmentState?): DictionaryEnrichmentState =
    when {
        persisted?.status == DictionaryEnrichmentStatus.USER_LOCKED -> persisted
        card.definitionSourcePrimary == "user" -> DictionaryEnrichmentState(
            card.id, DictionaryEnrichmentStatus.USER_LOCKED, attemptedAt = persisted?.attemptedAt, requestId = persisted?.requestId
        )
        card.meaningKo.isNotBlank() -> DictionaryEnrichmentState(
            card.id, DictionaryEnrichmentStatus.RESOLVED, attemptedAt = persisted?.attemptedAt, requestId = persisted?.requestId
        )
        persisted != null -> persisted
        else -> DictionaryEnrichmentState(card.id, DictionaryEnrichmentStatus.PENDING)
    }

private fun lookupAsDictionaryResult(card: VocabularyCard, result: DictionaryLookupResult, meaningKo: String) = DictionaryResult(
    lemma = card.lemma,
    displayWord = card.displayWord,
    meaningKo = meaningKo,
    definitionEn = result.definitionEn,
    partOfSpeech = result.partOfSpeech,
    exampleSentence = result.exampleSentence,
    rawSource = meaningKo.ifBlank { result.definitionEn },
    sourceType = result.sourceType,
    confidence = result.confidence,
    sourceSentence = "",
    sourceApp = card.sourceAppPrimary,
    sourceUrl = card.sourceUrlPrimary
)
