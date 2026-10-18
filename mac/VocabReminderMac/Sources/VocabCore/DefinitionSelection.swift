import Foundation

/// Persist the selected candidate alongside the legacy text projection.
/// Missing/invalid pointers from old clients are repaired deterministically.
package enum DefinitionSelection {
    package static func primaryID(card: VocabularyCard, candidates: [StoredDefinitionCandidate]) -> String? {
        let matching = candidates.filter {
            $0.cardId == card.id && !$0.parsedMeaningKo.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
            $0.parsedMeaningKo.trimmingCharacters(in: .whitespacesAndNewlines) == card.meaningKo.trimmingCharacters(in: .whitespacesAndNewlines) &&
            $0.parsedDefinitionEn.trimmingCharacters(in: .whitespacesAndNewlines) == card.definitionEn.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return matching.max {
            let leftSource = $0.source == card.definitionSourcePrimary
            let rightSource = $1.source == card.definitionSourcePrimary
            if leftSource != rightSource { return !leftSource }
            if $0.selectedAsPrimary != $1.selectedAsPrimary { return !$0.selectedAsPrimary }
            if $0.createdAt != $1.createdAt { return $0.createdAt < $1.createdAt }
            return $0.id < $1.id
        }?.id
    }
}
