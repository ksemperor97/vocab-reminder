import CryptoKit
import Foundation

// Context meanings (guide 10.6, 10.8): the sync server can pick the Korean
// meaning that fits the sentence a word was captured in. A card that already
// has a dictionary meaning is asked once per lemma + sentence; the answer
// replaces the meaning only when it came from the sentence (llm_context) and
// the user has not pinned a meaning. Priority: user > llm_context > dictionary.
// Shared contract: shared/test-vectors/context_meanings.json.
package enum ContextMeaning {
    package static let source = "llm_context"
    package static let soonRetryDelay: TimeInterval = 15 * 60
    package static let laterRetryDelay: TimeInterval = 24 * 60 * 60

    package static func sentence(of card: VocabularyCard) -> String {
        String(
            card.exampleSentence
                .split(whereSeparator: \.isWhitespace)
                .joined(separator: " ")
                .prefix(1_000)
        )
    }

    package static func key(of card: VocabularyCard) -> String {
        let material = "\(card.lemma.trimmingCharacters(in: .whitespacesAndNewlines).lowercased())\u{0}\(sentence(of: card))"
        return SHA256.hash(data: Data(material.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// How long to wait before sending the same sentence again; nil means never.
    package static func retryDelay(contextStatus: String?) -> TimeInterval? {
        switch contextStatus {
        case "resolved", "unresolved", "no_context":
            return nil
        case "failed", "deferred":
            return soonRetryDelay
        default:
            // "disabled", or a server that predates context meanings.
            return laterRetryDelay
        }
    }

    package static func isResolvedFromContext(_ result: CloudDictionaryLookupResult) -> Bool {
        result.contextStatus == "resolved" &&
            result.sourceType == source &&
            result.meaningKo.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
    }
}

private struct ContextMeaningAttempt: Codable, Equatable {
    var key: String
    var retryAt: Date?
}

/// Device-local record of which card sentences were already sent, so each one
/// is asked once (or again after a failure delay), never on every sync.
@MainActor
package final class ContextMeaningLedger {
    private let defaults: UserDefaults
    private let key: String
    private var entries: [String: ContextMeaningAttempt]

    package init(defaults: UserDefaults, key: String) {
        self.defaults = defaults
        self.key = key
        if let data = defaults.data(forKey: key),
           let decoded = try? JSONDecoder.vocab.decode([String: ContextMeaningAttempt].self, from: data) {
            entries = decoded
        } else {
            entries = [:]
        }
    }

    package func isEligible(cardId: String, contextKey: String, at now: Date) -> Bool {
        guard let entry = entries[cardId], entry.key == contextKey else { return true }
        guard let retryAt = entry.retryAt else { return false }
        return retryAt <= now
    }

    package func retryAt(cardId: String) -> Date?? {
        entries[cardId].map(\.retryAt)
    }

    package func record(cardId: String, contextKey: String, contextStatus: String?, at now: Date) {
        entries[cardId] = ContextMeaningAttempt(
            key: contextKey,
            retryAt: ContextMeaning.retryDelay(contextStatus: contextStatus).map { now.addingTimeInterval($0) }
        )
        persist()
    }

    package func prune(validCardIds: Set<String>) {
        let oldCount = entries.count
        entries = entries.filter { validCardIds.contains($0.key) }
        if entries.count != oldCount { persist() }
    }

    private func persist() {
        guard let data = try? JSONEncoder.vocab.encode(entries) else { return }
        defaults.set(data, forKey: key)
    }
}
