import Foundation

struct RecommendationSeed: Equatable {
    var lemma: String
    var meaningKo: String
    var definitionEn: String
    var partOfSpeech: String
    var exampleSentence: String
    var frequencyRank: Int
    var topic: String
}

struct UserVocabularySignal: Equatable {
    var lemma: String
    var lapseCount: Int = 0
    var rememberedCount: Int = 0
    var forgottenCount: Int = 0
}

struct RecommendationFeedbackSignal: Equatable {
    var lemma: String
    var action: String
}

struct UserLevelProfile: Equatable {
    var lowerRank: Int
    var upperRank: Int
    var preferredTopics: Set<String>
}

enum RecommendationEngine {
    // Source ranks/counts are derived from words/subtlex-word-frequencies (ISC), itself based on SUBTLEXus.
    // See shared/recommendations/README.md for attribution and licensing notes.
    static let catalog: [RecommendationSeed] = RecommendationCatalog.seeds

    static func recommendationFillLimit(
        dueUserCount: Int,
        existingRecommendationCount: Int,
        settings: SchedulerSettings
    ) -> Int {
        let activeLimit = min(settings.dailyWordGoal, settings.maxQueueSize)
        return max(0, activeLimit - max(0, dueUserCount) - max(0, existingRecommendationCount))
    }

    static func chooseRecommendations(
        userSignals: [UserVocabularySignal],
        feedbackSignals: [RecommendationFeedbackSignal],
        existingLemmas: Set<String>,
        dueUserCount: Int,
        settings: SchedulerSettings,
        limit: Int? = nil
    ) -> [RecommendationSeed] {
        let fillLimit = limit ?? recommendationFillLimit(
            dueUserCount: dueUserCount,
            existingRecommendationCount: 0,
            settings: settings
        )
        guard settings.enableWordRecommendations, userSignals.count >= 50, fillLimit > 0 else {
            return []
        }

        let profile = estimateLevel(userSignals: userSignals, feedbackSignals: feedbackSignals)
        let excluded = Set(feedbackSignals
            .filter { $0.action == "known" || $0.action == "dismissed" }
            .map(\.lemma))
            .union(existingLemmas)
        let dismissedTopics = Set(feedbackSignals
            .filter { $0.action == "dismissed" }
            .compactMap { catalogByLemma[$0.lemma]?.topic })

        return catalog
            .filter { excluded.contains($0.lemma) == false }
            .sorted { left, right in
                let leftInBand = profile.lowerRank...profile.upperRank ~= left.frequencyRank
                let rightInBand = profile.lowerRank...profile.upperRank ~= right.frequencyRank
                if leftInBand != rightInBand { return leftInBand && !rightInBand }

                let leftDismissed = dismissedTopics.contains(left.topic)
                let rightDismissed = dismissedTopics.contains(right.topic)
                if leftDismissed != rightDismissed { return !leftDismissed && rightDismissed }

                let leftPreferred = profile.preferredTopics.contains(left.topic)
                let rightPreferred = profile.preferredTopics.contains(right.topic)
                if leftPreferred != rightPreferred { return leftPreferred && !rightPreferred }

                let leftDistance = distanceFromBand(left.frequencyRank, profile: profile)
                let rightDistance = distanceFromBand(right.frequencyRank, profile: profile)
                if leftDistance != rightDistance { return leftDistance < rightDistance }

                return left.frequencyRank < right.frequencyRank
            }
            .prefix(fillLimit)
            .map { $0 }
    }

    static func estimateLevel(
        userSignals: [UserVocabularySignal],
        feedbackSignals: [RecommendationFeedbackSignal] = []
    ) -> UserLevelProfile {
        let rankedSignals: [(UserVocabularySignal, RecommendationSeed)] = userSignals.compactMap { signal in
            guard let seed = catalogByLemma[signal.lemma] else { return nil }
            return (signal, seed)
        }
        let ranks = rankedSignals.map { $0.1.frequencyRank }.sorted()
        let baseCenter: Int
        if ranks.isEmpty {
            baseCenter = 16_000
        } else {
            let index = min(Int(Double(ranks.count) * 0.70), ranks.count - 1)
            baseCenter = ranks[index]
        }

        let weakPenalty = rankedSignals.filter { signal, _ in
            let total = signal.rememberedCount + signal.forgottenCount
            return signal.lapseCount >= 3 || (total >= 4 && signal.forgottenCount * 2 >= total)
        }.count * 700
        let knownBoost = feedbackSignals.filter { $0.action == "known" }.count * 1_500
        let center = min(55_000, max(8_000, baseCenter + knownBoost - weakPenalty))
        let topicCounts = Dictionary(grouping: rankedSignals, by: { $0.1.topic })
            .mapValues(\.count)
        let preferredTopics = Set(topicCounts
            .sorted { left, right in
                if left.value != right.value { return left.value > right.value }
                return left.key < right.key
            }
            .prefix(3)
            .map(\.key))

        return UserLevelProfile(
            lowerRank: max(5_000, center - 4_000),
            upperRank: min(70_000, center + 10_000),
            preferredTopics: preferredTopics.isEmpty ? Set(["academic", "methods"]) : preferredTopics
        )
    }

    private static let catalogByLemma = Dictionary(uniqueKeysWithValues: catalog.map { ($0.lemma, $0) })

    private static func distanceFromBand(_ rank: Int, profile: UserLevelProfile) -> Int {
        if rank < profile.lowerRank { return profile.lowerRank - rank }
        if rank > profile.upperRank { return rank - profile.upperRank }
        return abs(rank - ((profile.lowerRank + profile.upperRank) / 2))
    }
}
