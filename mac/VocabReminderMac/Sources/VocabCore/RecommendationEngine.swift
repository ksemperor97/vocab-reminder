import Foundation

// The computer server owns the catalogue and recommendation policy.
package enum RecommendationEngine {
    package static func reason(lemma: String, contexts: [String]) -> String {
        "컴퓨터에서 준비한 추천"
    }

    package static func normalizedLemma(_ lemma: String) -> String {
        lemma.precomposedStringWithCompatibilityMapping.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }
}
