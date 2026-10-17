import Foundation

/// Field-group card merge (sync protocol 3), mirroring server/src/card_merge.js
/// and the Android CardMerge.
///
/// Content (meaning, definition, example, provenance) and schedule (status, due
/// date, interval, lapses) each win by their own clock, so an edit on one
/// device and a review on another both survive. Deletion, wordbook promotion
/// and the recommendation start only move one way. Duplicate lemmas are folded
/// by the server, never here. shared/test-vectors/card_merge.json pins every rule.
package enum CardMerge {
    package static func merge(_ existing: VocabularyCard, _ incoming: VocabularyCard) -> VocabularyCard {
        let content = compare(
            (incoming.contentVersion, incoming.contentUpdatedAt, contentKey(incoming)),
            (existing.contentVersion, existing.contentUpdatedAt, contentKey(existing))
        ) > 0 ? incoming : existing
        let schedule = compare(
            (incoming.scheduleVersion, incoming.scheduleUpdatedAt, scheduleKey(incoming)),
            (existing.scheduleVersion, existing.scheduleUpdatedAt, scheduleKey(existing))
        ) > 0 ? incoming : existing
        let incomingIsNewer = incoming.version != existing.version
            ? incoming.version > existing.version
            : incoming.updatedAt > existing.updatedAt
        var merged = incomingIsNewer ? incoming : existing

        merged.id = existing.id
        merged.lemma = existing.lemma
        merged.displayWord = content.displayWord
        merged.meaningKo = content.meaningKo
        merged.definitionEn = content.definitionEn
        merged.partOfSpeech = content.partOfSpeech
        merged.phonetic = content.phonetic
        merged.exampleSentence = content.exampleSentence
        merged.sourceAppPrimary = content.sourceAppPrimary
        merged.sourceUrlPrimary = content.sourceUrlPrimary
        merged.definitionSourcePrimary = content.definitionSourcePrimary
        merged.primaryDefinitionCandidateId = content.primaryDefinitionCandidateId
        merged.contentVersionValue = content.contentVersion
        merged.contentUpdatedAtValue = content.contentUpdatedAt
        merged.status = schedule.status
        merged.dueAt = schedule.dueAt
        merged.intervalDays = schedule.intervalDays
        merged.lapseCount = schedule.lapseCount
        merged.intervalBeforeLapse = schedule.intervalBeforeLapse
        merged.scheduleVersionValue = schedule.scheduleVersion
        merged.scheduleUpdatedAtValue = schedule.scheduleUpdatedAt
        merged.wordbook = existing.wordbook == .user || incoming.wordbook == .user ? .user : .recommended
        merged.recommendationStartedAt = earliest(existing.recommendationStartedAt, incoming.recommendationStartedAt)
        merged.deletedAt = earliest(existing.deletedAt, incoming.deletedAt)
        merged.mergedInto = merged.deletedAt == nil ? nil : (existing.mergedInto ?? incoming.mergedInto)
        merged.createdAt = min(existing.createdAt, incoming.createdAt)
        return merged
    }

    /// Advances the clock of whichever field group a local write changed.
    package static func stamp(previous: VocabularyCard?, next: VocabularyCard) -> VocabularyCard {
        var stamped = next
        guard let previous else { return pinned(stamped) }
        // Start from the stored clocks: a clock left implicit would otherwise
        // follow the whole-card version and move without its fields changing.
        stamped.contentVersionValue = max(next.contentVersionValue ?? previous.contentVersion, previous.contentVersion)
        stamped.contentUpdatedAtValue = next.contentUpdatedAtValue ?? previous.contentUpdatedAt
        stamped.scheduleVersionValue = max(next.scheduleVersionValue ?? previous.scheduleVersion, previous.scheduleVersion)
        stamped.scheduleUpdatedAtValue = next.scheduleUpdatedAtValue ?? previous.scheduleUpdatedAt
        if contentKey(previous) != contentKey(next), stamped.contentVersion <= previous.contentVersion {
            stamped.contentVersionValue = previous.contentVersion + 1
            stamped.contentUpdatedAtValue = next.updatedAt
        }
        if scheduleKey(previous) != scheduleKey(next), stamped.scheduleVersion <= previous.scheduleVersion {
            stamped.scheduleVersionValue = previous.scheduleVersion + 1
            stamped.scheduleUpdatedAtValue = next.updatedAt
        }
        if stamped != previous, stamped.version <= previous.version {
            stamped.version = previous.version + 1
        }
        return stamped
    }

    /// Makes implicit clocks explicit so later whole-card bumps leave them alone.
    package static func pinned(_ card: VocabularyCard) -> VocabularyCard {
        var copy = card
        copy.contentVersionValue = card.contentVersion
        copy.contentUpdatedAtValue = card.contentUpdatedAt
        copy.scheduleVersionValue = card.scheduleVersion
        copy.scheduleUpdatedAtValue = card.scheduleUpdatedAt
        return copy
    }

    // Field for field the same as groupKey() in card_merge.js: wire values,
    // nulls as "", due_at as epoch milliseconds.
    package static func contentKey(_ card: VocabularyCard) -> String {
        [
            card.displayWord,
            card.meaningKo,
            card.definitionEn,
            card.partOfSpeech,
            card.phonetic,
            card.exampleSentence,
            card.sourceAppPrimary,
            card.sourceUrlPrimary,
            card.definitionSourcePrimary,
            card.primaryDefinitionCandidateId ?? ""
        ].joined(separator: "\u{1f}")
    }

    package static func scheduleKey(_ card: VocabularyCard) -> String {
        [
            card.status.rawValue,
            String(Int64((card.dueAt.timeIntervalSince1970 * 1_000).rounded(.down))),
            String(card.intervalDays),
            String(card.lapseCount),
            card.intervalBeforeLapse.map(String.init) ?? "",
            "2.5",
            "",
            ""
        ].joined(separator: "\u{1f}")
    }

    private static func compare(_ a: (Int, Date, String), _ b: (Int, Date, String)) -> Int {
        if a.0 != b.0 { return a.0 > b.0 ? 1 : -1 }
        if a.1 != b.1 { return a.1 > b.1 ? 1 : -1 }
        // UTF-16 code-unit order, as JavaScript and Kotlin compare strings.
        let left = Array(a.2.utf16)
        let right = Array(b.2.utf16)
        if left == right { return 0 }
        return left.lexicographicallyPrecedes(right) ? -1 : 1
    }

    private static func earliest(_ a: Date?, _ b: Date?) -> Date? {
        switch (a, b) {
        case let (a?, b?): return min(a, b)
        case let (a?, nil): return a
        case let (nil, b?): return b
        default: return nil
        }
    }
}
