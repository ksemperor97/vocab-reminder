package com.vocabreminder.android.data

import java.time.Instant
import java.util.Locale

sealed interface CardHistoryEntry {
    val id: String
    val occurredAt: Instant

    data class ContextEntry(
        val context: StoredContext
    ) : CardHistoryEntry {
        override val id: String = "context:${context.id}"
        override val occurredAt: Instant = context.createdAt
    }

    data class DefinitionEntry(
        val candidate: StoredDefinitionCandidate,
        val isCurrentPrimary: Boolean,
        val isConflictCandidate: Boolean
    ) : CardHistoryEntry {
        override val id: String = "definition:${candidate.id}"
        override val occurredAt: Instant = candidate.createdAt
    }

    data class ReviewEntry(
        val event: StoredReviewEvent
    ) : CardHistoryEntry {
        override val id: String = "event:${event.id}"
        override val occurredAt: Instant = event.clientCreatedAt
    }

    data class SyncConflictEntry(
        override val occurredAt: Instant,
        val candidateIds: List<String>
    ) : CardHistoryEntry {
        override val id: String = "sync-conflict:${occurredAt}:${candidateIds.sorted().joinToString(",")}"
    }
}

object CardHistoryBuilder {
    fun build(
        card: VocabularyCard,
        contexts: List<StoredContext>,
        candidates: List<StoredDefinitionCandidate>,
        events: List<StoredReviewEvent>
    ): List<CardHistoryEntry> {
        val cardContexts = contexts.filter { it.cardId == card.id }
        val cardCandidates = candidates.filter { it.cardId == card.id }
        val cardEvents = events.filter { it.cardId == card.id }
        val cardProjection = card.definitionProjection()
        val primaryCandidateId = cardCandidates
            .filter {
                val projection = it.definitionProjection()
                projection.hasContent && projection.matches(cardProjection)
            }
            .maxWithOrNull(
                compareBy<StoredDefinitionCandidate> { if (it.selectedAsPrimary) 1 else 0 }
                    .thenBy { it.createdAt }
                    .thenBy { it.id }
            )
            ?.id
        val selectedCandidates = cardCandidates.filter {
            it.selectedAsPrimary && it.definitionProjection().hasContent
        }
        val conflictCandidateIds = selectedCandidates.mapNotNullTo(mutableSetOf()) { candidate ->
            val originDeviceId = candidate.originDeviceId?.trim().orEmpty()
            val baseCardVersion = candidate.baseCardVersion
            val projection = candidate.definitionProjection()
            val conflicts = originDeviceId.isNotEmpty() && baseCardVersion != null &&
                selectedCandidates.any { other ->
                    other.id != candidate.id &&
                        other.baseCardVersion == baseCardVersion &&
                        other.originDeviceId?.trim()?.let { it.isNotEmpty() && it != originDeviceId } == true &&
                        other.definitionProjection() != projection
                }
            candidate.id.takeIf { conflicts }
        }
        val hasDefinitionConflict = conflictCandidateIds.isNotEmpty()

        val entries = buildList {
            addAll(cardContexts.map { CardHistoryEntry.ContextEntry(it) })
            addAll(
                cardCandidates.map { candidate ->
                    CardHistoryEntry.DefinitionEntry(
                        candidate = candidate,
                        isCurrentPrimary = candidate.id == primaryCandidateId,
                        isConflictCandidate = candidate.id in conflictCandidateIds
                    )
                }
            )
            addAll(cardEvents.map { CardHistoryEntry.ReviewEntry(it) })
            if (hasDefinitionConflict) {
                val conflictCandidates = selectedCandidates.filter { it.id in conflictCandidateIds }
                add(
                    CardHistoryEntry.SyncConflictEntry(
                        occurredAt = conflictCandidates.maxOf { it.createdAt },
                        candidateIds = conflictCandidates.map { it.id }.sorted()
                    )
                )
            }
        }
        return entries.sortedWith(
            compareByDescending<CardHistoryEntry> { it.occurredAt }
                .thenBy { it.id }
        )
    }

    private data class DefinitionProjection(
        val meaningKo: String,
        val definitionEn: String,
        val partOfSpeech: String,
        val exampleSentence: String
    ) {
        val hasContent: Boolean
            get() = meaningKo.isNotEmpty() ||
                definitionEn.isNotEmpty() ||
                partOfSpeech.isNotEmpty() ||
                exampleSentence.isNotEmpty()

        fun matches(card: DefinitionProjection): Boolean {
            return meaningKo == card.meaningKo &&
                definitionEn == card.definitionEn &&
                (partOfSpeech.isEmpty() || partOfSpeech == card.partOfSpeech) &&
                (exampleSentence.isEmpty() || exampleSentence == card.exampleSentence)
        }
    }

    private fun VocabularyCard.definitionProjection(): DefinitionProjection {
        return DefinitionProjection(
            meaningKo = normalizeProjectionField(meaningKo),
            definitionEn = normalizeProjectionField(definitionEn),
            partOfSpeech = normalizeProjectionField(partOfSpeech),
            exampleSentence = normalizeProjectionField(exampleSentence)
        )
    }

    private fun StoredDefinitionCandidate.definitionProjection(): DefinitionProjection {
        return DefinitionProjection(
            meaningKo = normalizeProjectionField(parsedMeaningKo),
            definitionEn = normalizeProjectionField(parsedDefinitionEn),
            partOfSpeech = normalizeProjectionField(partOfSpeech),
            exampleSentence = normalizeProjectionField(exampleSentence)
        )
    }

    private fun normalizeProjectionField(value: String): String {
        return value.trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)
    }
}
