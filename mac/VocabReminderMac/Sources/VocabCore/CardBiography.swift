import Foundation

package enum CardBiographyKind: Equatable {
    case context(StoredContext)
    case definitionCandidate(StoredDefinitionCandidate)
    case review(StoredReviewEvent)
    case sync(StoredSyncEvent)
}

package struct CardBiographyItem: Identifiable, Equatable {
    package init(
        id: String,
        occurredAt: Date,
        kind: CardBiographyKind
    ) {
        self.id = id
        self.occurredAt = occurredAt
        self.kind = kind
    }

    package var id: String
    package var occurredAt: Date
    package var kind: CardBiographyKind
}

package enum CardBiographyBuilder {
    package static func items(
        card: VocabularyCard,
        contexts: [StoredContext],
        candidates: [StoredDefinitionCandidate],
        reviewEvents: [StoredReviewEvent],
        syncEvents: [StoredSyncEvent]
    ) -> [CardBiographyItem] {
        let contextItems = contexts
            .filter { $0.cardId == card.id }
            .map { CardBiographyItem(id: "context:\($0.id)", occurredAt: $0.createdAt, kind: .context($0)) }
        let candidateItems = candidates
            .filter { $0.cardId == card.id }
            .map {
                CardBiographyItem(
                    id: "candidate:\($0.id)",
                    occurredAt: $0.createdAt,
                    kind: .definitionCandidate($0)
                )
            }
        let reviewItems = reviewEvents
            .filter { $0.cardId == card.id }
            .map { CardBiographyItem(id: "review:\($0.id)", occurredAt: $0.clientCreatedAt, kind: .review($0)) }
        // Sync is global state. Only show events after this card was created,
        // and cap the global heartbeat history so it cannot bury card-specific
        // capture/edit/review evidence.
        let syncItems = syncEvents
            .filter { $0.createdAt >= card.createdAt }
            .sorted { $0.createdAt < $1.createdAt }
            .suffix(20)
            .map { CardBiographyItem(id: "sync:\($0.id)", occurredAt: $0.createdAt, kind: .sync($0)) }

        return (contextItems + candidateItems + reviewItems + syncItems).sorted {
            if $0.occurredAt != $1.occurredAt { return $0.occurredAt > $1.occurredAt }
            return $0.id > $1.id
        }
    }

    package static func currentPrimaryCandidateID(
        card: VocabularyCard,
        candidates: [StoredDefinitionCandidate]
    ) -> String? {
        let contentMatches = candidates.filter {
            $0.cardId == card.id && candidateMatchesCurrentPrimary($0, card: card)
        }
        if let selectedID = card.primaryDefinitionCandidateId, contentMatches.contains(where: { $0.id == selectedID }) { return selectedID }
        let sourceMatches = contentMatches.filter {
            $0.source.trimmingCharacters(in: .whitespacesAndNewlines) ==
                card.definitionSourcePrimary.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return (sourceMatches.isEmpty ? contentMatches : sourceMatches)
            .max {
                if $0.createdAt != $1.createdAt { return $0.createdAt < $1.createdAt }
                return $0.id < $1.id
            }?
            .id
    }

    package static func preservedAlternativeCandidateIDs(
        card: VocabularyCard,
        candidates: [StoredDefinitionCandidate]
    ) -> Set<String> {
        let selectedCandidates = candidates.filter {
            $0.cardId == card.id &&
                $0.selectedAsPrimary &&
                projection(for: $0).hasContent
        }
        return Set(selectedCandidates.compactMap { candidate in
            guard let baseCardVersion = candidate.baseCardVersion,
                  let originDeviceId = candidate.originDeviceId?
                    .trimmingCharacters(in: .whitespacesAndNewlines),
                  originDeviceId.isEmpty == false
            else { return nil }
            let candidateProjection = projection(for: candidate)
            let conflicts = selectedCandidates.contains { other in
                guard other.id != candidate.id,
                      other.baseCardVersion == baseCardVersion,
                      let otherDeviceId = other.originDeviceId?
                        .trimmingCharacters(in: .whitespacesAndNewlines),
                      otherDeviceId.isEmpty == false,
                      otherDeviceId != originDeviceId
                else { return false }
                return projection(for: other) != candidateProjection
            }
            return conflicts ? candidate.id : nil
        })
    }

    package static func candidateConflicts(
        _ candidate: StoredDefinitionCandidate,
        card: VocabularyCard,
        candidates: [StoredDefinitionCandidate]
    ) -> Bool {
        preservedAlternativeCandidateIDs(card: card, candidates: candidates).contains(candidate.id)
    }

    private static func candidateMatchesCurrentPrimary(
        _ candidate: StoredDefinitionCandidate,
        card: VocabularyCard
    ) -> Bool {
        let candidateProjection = projection(for: candidate)
        return candidateProjection.hasContent && candidateProjection == projection(for: card)
    }

    private struct DefinitionProjection: Hashable {
        var meaningKo: String
        var definitionEn: String
        var partOfSpeech: String
        var exampleSentence: String

        var hasContent: Bool {
            meaningKo.isEmpty == false ||
                definitionEn.isEmpty == false ||
                partOfSpeech.isEmpty == false ||
                exampleSentence.isEmpty == false
        }
    }

    private static func projection(for card: VocabularyCard) -> DefinitionProjection {
        DefinitionProjection(
            meaningKo: normalizeProjectionField(card.meaningKo),
            definitionEn: normalizeProjectionField(card.definitionEn),
            partOfSpeech: normalizeProjectionField(card.partOfSpeech),
            exampleSentence: normalizeProjectionField(card.exampleSentence)
        )
    }

    private static func projection(for candidate: StoredDefinitionCandidate) -> DefinitionProjection {
        DefinitionProjection(
            meaningKo: normalizeProjectionField(candidate.parsedMeaningKo),
            definitionEn: normalizeProjectionField(candidate.parsedDefinitionEn),
            partOfSpeech: normalizeProjectionField(candidate.partOfSpeech),
            exampleSentence: normalizeProjectionField(candidate.exampleSentence)
        )
    }

    private static func normalizeProjectionField(_ value: String) -> String {
        value
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
            .lowercased(with: Locale(identifier: "en_US_POSIX"))
    }
}
