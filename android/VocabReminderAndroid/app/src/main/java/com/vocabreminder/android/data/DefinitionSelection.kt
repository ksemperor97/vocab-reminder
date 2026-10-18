package com.vocabreminder.android.data

/** Persist a deterministic pointer while retaining text projections for old clients. */
object DefinitionSelection {
    fun primaryId(card: VocabularyCard, candidates: List<StoredDefinitionCandidate>): String? = candidates
        .filter { it.cardId == card.id && it.parsedMeaningKo.isNotBlank() &&
            it.parsedMeaningKo.trim() == card.meaningKo.trim() && it.parsedDefinitionEn.trim() == card.definitionEn.trim() }
        .maxWithOrNull(compareBy<StoredDefinitionCandidate> { it.source == card.definitionSourcePrimary }
            .thenBy { it.selectedAsPrimary }.thenBy { it.createdAt }.thenBy { it.id })?.id
}
