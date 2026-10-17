import Foundation

/// Local vocabulary-only signals. Captured text never leaves the device here.
enum RecommendationTopics {
    private static let keywords: [String: Set<String>] = [
        "academic": ["research", "theory", "evidence", "hypothesis", "literature", "concept", "논문", "연구", "이론"],
        "methods": ["experiment", "protocol", "measurement", "sample", "replicate", "procedure", "participant", "실험", "측정", "방법"],
        "statistics": ["regression", "variance", "probability", "bayesian", "distribution", "correlation", "estimate", "통계", "확률", "회귀"],
        "neuroscience": ["brain", "neural", "neuron", "cortex", "cognitive", "memory", "attention", "뇌", "신경", "인지"],
        "general": ["travel", "work", "family", "city", "food", "conversation", "생활", "여행", "일상"]
    ]
    static func matches(_ context: String) -> Set<String> {
        let tokens = Set(context.lowercased().components(separatedBy: CharacterSet.letters.inverted))
        return Set(keywords.filter { !$0.value.isDisjoint(with: tokens) }.keys)
    }
}
