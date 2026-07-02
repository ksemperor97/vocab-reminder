import Darwin
import Foundation

enum SchedulerVectorCommand {
    static func runAndExitIfRequested() {
        guard CommandLine.arguments.contains("--check-scheduler-vectors") else { return }
        do {
            try run()
            print("Scheduler vectors passed")
            exit(0)
        } catch {
            fputs("Scheduler vector check failed: \(error)\n", stderr)
            exit(1)
        }
    }

    private static func run() throws {
        let vectors = try JSONSerialization.jsonObject(with: Data(contentsOf: vectorURL())) as! [[String: Any]]
        try require(vectors.count >= 15, "Expected at least 15 vectors")
        for vector in vectors {
            let kind = vector["kind"] as! String
            if kind == "applyReview" {
                try assertApplyReview(vector)
            } else if kind == "planDay" {
                try assertPlanDay(vector)
            } else {
                throw CheckError("Unknown vector kind \(kind)")
            }
        }

        let sentence = CaptureNormalizer.analyze("The results are tentative, but the pattern is robust.")
        try require(sentence.needsWordSelection, "Sentence should require word selection")
        try require(sentence.candidates.contains("tentative"), "Missing tentative candidate")
        try require(sentence.candidates.contains("robust"), "Missing robust candidate")
        let robust = DictionaryResolver.resolve("Robustly,")
        try require(robust.lemma == "robust", "Expected robust lemma, got \(robust.lemma)")
        try require(robust.meaningKo == "견고한, 안정적인", "Unexpected robust meaning")
        let syncPayload = try SyncBatchFactory.jsonData(
            cards: [
                VocabularyCard(
                    id: "card-1",
                    lemma: "tentative",
                    displayWord: "tentative",
                    meaningKo: "잠정적인",
                    definitionEn: "",
                    partOfSpeech: "",
                    exampleSentence: "The results are tentative.",
                    definitionSourcePrimary: "user",
                    status: .new,
                    wordbook: .user,
                    dueAt: Date(timeIntervalSince1970: 0),
                    intervalDays: 0,
                    lapseCount: 0,
                    createdAt: Date(timeIntervalSince1970: 0),
                    updatedAt: Date(timeIntervalSince1970: 0),
                    version: 1
                )
            ],
            contexts: [],
            definitionCandidates: [],
            reviewEvents: [],
            settings: UserSettingsData(recommendationsIntroducedAt: Date(timeIntervalSince1970: 0))
        )
        let syncJson = try JSONSerialization.jsonObject(with: syncPayload) as! [String: Any]
        let cards = syncJson["cards"] as! [[String: Any]]
        try require(cards.first?["status"] as? String == "new", "Mac sync card status should be lowercase")
        let settingsJson = syncJson["settings"] as! [String: Any]
        try require(settingsJson["recommendations_introduced_at"] as? String == "1970-01-01T00:00:00Z", "Mac sync should export recommendation intro timestamp")
        let importedSnapshot = try SyncBatchFactory.localSnapshot(from: syncPayload)
        try require(importedSnapshot.cards.first?.status == .new, "Mac sync import should restore status")
        try require(importedSnapshot.cards.first?.wordbook == .user, "Mac sync import should restore wordbook")
        try require(importedSnapshot.settings.recommendationsIntroducedAt == Date(timeIntervalSince1970: 0), "Mac sync import should restore recommendation intro timestamp")
    }

    private static func assertApplyReview(_ vector: [String: Any]) throws {
        let now = parseDate(vector["now"] as! String)
        let settings = parseSettings(vector["settings"] as? [String: Any])
        let card = parseCard(vector["given"] as! [String: Any], now: now)
        let response = parseResponse(vector["response"] as! String)
        let recentResponses = (vector["recent_responses"] as? [String] ?? []).map(parseResponse)
        let unavailableCount = vector["unavailable_count_today"] as? Int ?? 0
        let result = Scheduler.applyReview(card: card, response: response, settings: settings, now: now, recentResponses: recentResponses, unavailableCountToday: unavailableCount)
        let then = vector["then"] as! [String: Any]
        let name = vector["name"] as! String
        try require(result.card.status == parseStatus(then["status"] as! String), "\(name): status")
        try require(result.card.intervalDays == then["interval_days"] as! Int, "\(name): interval")
        if let value = then["interval_before_lapse"] as? Int {
            try require(result.card.intervalBeforeLapse == value, "\(name): interval_before_lapse")
        }
        if let value = then["lapse_count"] as? Int {
            try require(result.card.lapseCount == value, "\(name): lapse_count")
        }
        if let value = then["event_previous_interval_days"] as? Int {
            try require(result.event.previousIntervalDays == value, "\(name): event previous interval")
        }
        if let range = then["due_in_minutes"] as? [Int] {
            let minutes = Int(result.card.dueAt.timeIntervalSince(now) / 60)
            try require(minutes >= range[0] && minutes <= range[1], "\(name): due minutes \(minutes)")
        }
        if let range = then["local_due_time_between"] as? [String] {
            let local = localMinutes(result.card.dueAt, settings: settings)
            try require(local >= minutes(range[0])! && local <= minutes(range[1])!, "\(name): local due")
        }
    }

    private static func assertPlanDay(_ vector: [String: Any]) throws {
        let now = parseDate(vector["now"] as! String)
        let settings = parseSettings(vector["settings"] as? [String: Any])
        let cards = (vector["cards"] as! [[String: Any]]).map { parseCard($0, now: now) }
        let result = Scheduler.planDay(cards: cards, settings: settings, now: now, userWordCount: vector["user_word_count"] as? Int ?? 0)
        let then = vector["then"] as! [String: Any]
        let name = vector["name"] as! String
        if let value = then["notification_count"] as? Int {
            try require(result.notifications.count == value, "\(name): notification count")
        }
        if let value = then["recommended_count"] as? Int {
            try require(result.notifications.filter { $0.wordbook == .recommended }.count == value, "\(name): recommended count")
        }
        if let value = then["rebalanced_count"] as? Int {
            try require(result.rebalancedCards.count == value, "\(name): rebalanced count")
        }
        if let ids = then["notification_ids"] as? [String] {
            try require(result.notifications.map(\.id) == ids, "\(name): notification ids")
        }
        if let ids = then["rebalanced_ids"] as? [String] {
            try require(result.rebalancedCards.map(\.id).sorted() == ids.sorted(), "\(name): rebalanced ids")
        }
        if let range = then["rebalanced_due_in_days"] as? [Int] {
            for card in result.rebalancedCards {
                let days = Int(card.dueAt.timeIntervalSince(now) / (24 * 60 * 60))
                try require(days >= range[0] && days <= range[1], "\(name): rebalanced due days")
            }
        }
        if then["unchanged_memory_state"] as? Bool == true {
            let originalById = Dictionary(uniqueKeysWithValues: cards.map { ($0.id, $0) })
            for card in result.rebalancedCards {
                let original = originalById[card.id]!
                try require(original.status == card.status, "\(name): status changed")
                try require(original.intervalDays == card.intervalDays, "\(name): interval changed")
                try require(original.lapseCount == card.lapseCount, "\(name): lapse changed")
                try require(original.dueAt != card.dueAt, "\(name): due unchanged")
            }
        }
    }

    private static func parseCard(_ json: [String: Any], now: Date) -> SchedulerCard {
        SchedulerCard(
            id: json["id"] as! String,
            status: parseStatus(json["status"] as! String),
            intervalDays: json["interval_days"] as! Int,
            dueAt: (json["due_at"] as? String).map(parseDate) ?? now,
            lapseCount: json["lapse_count"] as? Int ?? 0,
            intervalBeforeLapse: json["interval_before_lapse"] as? Int,
            meaningKo: json["meaning_ko"] as? String ?? "meaning",
            wordbook: (json["wordbook"] as? String) == "recommended" ? .recommended : .user,
            deletedAt: (json["deleted_at"] as? String).map(parseDate)
        )
    }

    private static func parseSettings(_ json: [String: Any]?) -> SchedulerSettings {
        SchedulerSettings(
            dailyWordGoal: json?["daily_word_goal"] as? Int ?? 10,
            maxQueueSize: json?["max_queue_size"] as? Int ?? 20,
            snoozeDurationMinutes: json?["snooze_duration_minutes"] as? Int ?? 60,
            quietHoursStart: json?["quiet_hours_start"] as? String ?? "23:00",
            quietHoursEnd: json?["quiet_hours_end"] as? String ?? "09:00",
            timezone: json?["timezone"] as? String ?? "Asia/Seoul",
            maxUnavailablePerCardPerDay: json?["max_unavailable_per_card_per_day"] as? Int ?? 2,
            enableWordRecommendations: json?["enable_word_recommendations"] as? Bool ?? true
        )
    }

    private static func parseStatus(_ value: String) -> CardStatus { CardStatus(rawValue: value)! }
    private static func parseResponse(_ value: String) -> ReviewResponse { ReviewResponse(rawValue: value)! }
    private static func parseDate(_ value: String) -> Date { ISO8601DateFormatter().date(from: value)! }

    private static func vectorURL() throws -> URL {
        let candidates = [
            URL(filePath: "../../../shared/test-vectors/scheduler.json"),
            URL(filePath: "../../shared/test-vectors/scheduler.json"),
            URL(filePath: "../shared/test-vectors/scheduler.json"),
            URL(filePath: "shared/test-vectors/scheduler.json"),
            URL(filePath: FileManager.default.currentDirectoryPath).appending(path: "../../../shared/test-vectors/scheduler.json"),
            URL(filePath: FileManager.default.currentDirectoryPath).appending(path: "../../shared/test-vectors/scheduler.json")
        ]
        if let url = candidates.first(where: { FileManager.default.fileExists(atPath: $0.path) }) {
            return url
        }
        throw CheckError("Could not find shared/test-vectors/scheduler.json")
    }

    private static func localMinutes(_ date: Date, settings: SchedulerSettings) -> Int {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: settings.timezone)!
        let parts = calendar.dateComponents([.hour, .minute], from: date)
        return (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
    }

    private static func minutes(_ value: String) -> Int? {
        let parts = value.split(separator: ":").compactMap { Int($0) }
        guard parts.count == 2 else { return nil }
        return parts[0] * 60 + parts[1]
    }

    private static func require(_ condition: Bool, _ message: String) throws {
        if !condition { throw CheckError(message) }
    }
}

private struct CheckError: Error, CustomStringConvertible {
    var description: String
    init(_ description: String) { self.description = description }
}
