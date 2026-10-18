import Foundation
import VocabCore

// vocab-check: checks and a headless driver that run the real VocabCore code.
//
//   vocab-check vectors
//       Shared vectors (scheduler, capture normalisation, card merge) against
//       the Mac implementation.
//   vocab-check store --data-dir DIR add WORD [MEANING]
//   vocab-check store --data-dir DIR review WORD remembered|forgotten|unavailable
//   vocab-check store --data-dir DIR edit WORD MEANING
//   vocab-check store --data-dir DIR delete WORD
//   vocab-check store --data-dir DIR sync URL TOKEN
//   vocab-check store --data-dir DIR list
//       Drives LocalVocabularyStore exactly as the menu bar app does (SQLite,
//       outbox, merge, sync), so end-to-end runs can use the Mac as a device.

struct CheckFailure: Error, CustomStringConvertible {
    var description: String
    init(_ description: String) { self.description = description }
}

func require(_ condition: Bool, _ message: @autoclosure () -> String) throws {
    if !condition { throw CheckFailure(message()) }
}

func repoFile(_ relative: String) throws -> URL {
    var directory = URL(filePath: FileManager.default.currentDirectoryPath)
    for _ in 0..<6 {
        let candidate = directory.appending(path: relative)
        if FileManager.default.fileExists(atPath: candidate.path) { return candidate }
        directory = directory.deletingLastPathComponent()
    }
    throw CheckFailure("Could not find \(relative) above \(FileManager.default.currentDirectoryPath)")
}

func jsonArray(_ relative: String) throws -> [[String: Any]] {
    try JSONSerialization.jsonObject(with: Data(contentsOf: repoFile(relative))) as? [[String: Any]] ?? []
}

func isoDate(_ value: String) -> Date? { SyncBatchFactory.parseISO8601(value) }

// MARK: - Vectors

func checkSchedulerVectors() throws -> Int {
    let vectors = try jsonArray("shared/test-vectors/scheduler.json")
    try require(vectors.count >= 15, "Expected at least 15 scheduler vectors")
    for vector in vectors {
        let name = vector["name"] as? String ?? "?"
        let now = isoDate(vector["now"] as! String)!
        let settings = schedulerSettings(vector["settings"] as? [String: Any])
        let then = vector["then"] as! [String: Any]
        switch vector["kind"] as? String {
        case "applyReview":
            let result = Scheduler.applyReview(
                card: schedulerCard(vector["given"] as! [String: Any], now: now),
                response: ReviewResponse(rawValue: vector["response"] as! String)!,
                settings: settings,
                now: now,
                recentResponses: (vector["recent_responses"] as? [String] ?? []).compactMap(ReviewResponse.init(rawValue:)),
                unavailableCountToday: vector["unavailable_count_today"] as? Int ?? 0,
                forgottenCountToday: vector["forgotten_count_today"] as? Int ?? 0
            )
            try require(result.card.status.rawValue == then["status"] as? String, "\(name): status")
            try require(result.card.intervalDays == then["interval_days"] as? Int, "\(name): interval")
            if let value = then["interval_before_lapse"] as? Int { try require(result.card.intervalBeforeLapse == value, "\(name): interval_before_lapse") }
            if let value = then["lapse_count"] as? Int { try require(result.card.lapseCount == value, "\(name): lapse_count") }
            if let value = then["event_previous_interval_days"] as? Int { try require(result.event.previousIntervalDays == value, "\(name): event interval") }
            if let range = then["due_in_minutes"] as? [Int] {
                let minutes = Int(result.card.dueAt.timeIntervalSince(now) / 60)
                try require(minutes >= range[0] && minutes <= range[1], "\(name): due in \(minutes) minutes")
            }
            if let range = then["local_due_time_between"] as? [String] {
                var calendar = Calendar(identifier: .gregorian)
                calendar.timeZone = TimeZone(identifier: settings.timezone)!
                let parts = calendar.dateComponents([.hour, .minute], from: result.card.dueAt)
                let local = (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
                try require(local >= clockMinutes(range[0]) && local <= clockMinutes(range[1]), "\(name): local due time")
            }
        case "planDay":
            let cards = (vector["cards"] as! [[String: Any]]).map { schedulerCard($0, now: now) }
            let result = Scheduler.planDay(cards: cards, settings: settings, now: now, userWordCount: vector["user_word_count"] as? Int ?? 0)
            if let value = then["notification_count"] as? Int { try require(result.notifications.count == value, "\(name): notification count") }
            if let value = then["recommended_count"] as? Int {
                try require(result.notifications.filter { $0.wordbook == .recommended }.count == value, "\(name): recommended count")
            }
            if let value = then["rebalanced_count"] as? Int { try require(result.rebalancedCards.count == value, "\(name): rebalanced count") }
            if let ids = then["notification_ids"] as? [String] { try require(result.notifications.map(\.id) == ids, "\(name): notification ids") }
            if let ids = then["rebalanced_ids"] as? [String] {
                try require(result.rebalancedCards.map(\.id).sorted() == ids.sorted(), "\(name): rebalanced ids")
            }
        default:
            throw CheckFailure("Unknown scheduler vector kind in \(name)")
        }
    }
    return vectors.count
}

func checkCaptureVectors() throws -> Int {
    let vectors = try jsonArray("shared/test-vectors/capture_normalization.json")
    for vector in vectors {
        let name = vector["name"] as? String ?? "?"
        let analysis = CaptureNormalizer.analyze(vector["raw"] as! String)
        let then = vector["then"] as! [String: Any]
        if let expected = then["needs_word_selection"] as? Bool { try require(analysis.needsWordSelection == expected, "\(name): needs_word_selection") }
        if let expected = then["candidates"] as? [String] { try require(analysis.candidates == expected, "\(name): candidates") }
        if let expected = then["display_word"] as? String { try require(analysis.displayWord == expected, "\(name): display_word") }
        if let expected = then["lemma"] as? String { try require(analysis.lemma == expected, "\(name): lemma") }
        for word in then["candidates_include"] as? [String] ?? [] { try require(analysis.candidates.contains(word), "\(name): missing \(word)") }
        for word in then["candidates_exclude"] as? [String] ?? [] { try require(!analysis.candidates.contains(word), "\(name): included \(word)") }
    }
    return vectors.count
}

func checkCardMergeVectors() throws -> Int {
    let document = try JSONSerialization.jsonObject(with: Data(contentsOf: repoFile("shared/test-vectors/card_merge.json"))) as! [String: Any]
    let cases = document["cases"] as! [[String: Any]]
    for vector in cases {
        let name = vector["name"] as! String
        let existing = try SyncBatchFactory.cardPayload(vector["existing"] as! [String: Any])
        let incoming = try SyncBatchFactory.cardPayload(vector["incoming"] as! [String: Any])
        for (label, merged) in [("existing<-incoming", CardMerge.merge(existing, incoming)), ("incoming<-existing", CardMerge.merge(incoming, existing))] {
            let actual = SyncBatchFactory.card(merged, deviceId: "mac-check")
            for (field, expected) in vector["expected"] as! [String: Any] {
                try require(sameValue(actual[field], expected), "\(name) [\(label)] \(field): \(String(describing: actual[field])) != \(expected)")
            }
        }
    }
    return cases.count
}

func sameValue(_ actual: Any?, _ expected: Any) -> Bool {
    let actualIsNull = actual == nil || actual is NSNull
    if expected is NSNull { return actualIsNull }
    if let expected = expected as? String, let actual = actual as? String {
        if let left = isoDate(expected), let right = isoDate(actual) { return abs(left.timeIntervalSince(right)) < 0.001 }
        return expected == actual
    }
    if let expected = expected as? NSNumber, let actual = actual as? NSNumber { return expected == actual }
    return false
}

func schedulerCard(_ json: [String: Any], now: Date) -> SchedulerCard {
    SchedulerCard(
        id: json["id"] as! String,
        status: CardStatus(rawValue: json["status"] as! String)!,
        intervalDays: json["interval_days"] as! Int,
        dueAt: (json["due_at"] as? String).flatMap(isoDate) ?? now,
        lapseCount: json["lapse_count"] as? Int ?? 0,
        intervalBeforeLapse: json["interval_before_lapse"] as? Int,
        meaningKo: json["meaning_ko"] as? String ?? "meaning",
        wordbook: (json["wordbook"] as? String) == "recommended" ? .recommended : .user,
        deletedAt: (json["deleted_at"] as? String).flatMap(isoDate)
    )
}

func schedulerSettings(_ json: [String: Any]?) -> SchedulerSettings {
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

func clockMinutes(_ value: String) -> Int {
    let parts = value.split(separator: ":").compactMap { Int($0) }
    return parts.count == 2 ? parts[0] * 60 + parts[1] : -1
}

// MARK: - Store driver

@MainActor
func runStore(_ arguments: [String]) async throws {
    guard arguments.count >= 3, arguments[0] == "--data-dir" else {
        throw CheckFailure("usage: vocab-check store --data-dir DIR <add|add-context|review|edit|delete|sync|enrich|list> ...")
    }
    let directory = URL(filePath: arguments[1], directoryHint: .isDirectory)
    let store = LocalVocabularyStore(
        appSupportURL: directory,
        scheduleNotifications: false,
        previewDictionaryLookup: { _ in nil }
    )
    if let error = store.storageError { throw CheckFailure(error) }
    let command = arguments[2]
    let rest = Array(arguments.dropFirst(3))

    func activeCard(_ word: String) throws -> VocabularyCard {
        let lemma = CaptureNormalizer.analyze(word).lemma
        guard let card = store.activeCards.first(where: { $0.lemma.lowercased() == lemma.lowercased() }) else {
            throw CheckFailure("no active card for \(word)")
        }
        return card
    }

    switch command {
    case "add":
        guard let word = rest.first else { throw CheckFailure("add WORD [MEANING]") }
        store.capture(text: word, method: .manual, sourceApp: "vocab-check")
        guard let preview = store.preview else { throw CheckFailure("capture produced no preview: \(store.message)") }
        store.savePreview(meaningOverride: rest.count > 1 ? rest[1] : preview.meaningKo, exampleOverride: preview.exampleSentence)
    case "add-context":
        // Captures a sentence and picks WORD from it, like selecting a word in the capture panel.
        guard rest.count == 2 else { throw CheckFailure("add-context WORD SENTENCE") }
        store.capture(text: rest[1], method: .manual, sourceApp: "vocab-check")
        if store.pendingAnalysis != nil { store.selectCandidate(rest[0]) }
        guard let preview = store.preview else { throw CheckFailure("capture produced no preview: \(store.message)") }
        store.savePreview(meaningOverride: preview.meaningKo, exampleOverride: preview.sourceSentence)
    case "review":
        guard rest.count == 2, let response = ReviewResponse(rawValue: rest[1]) else {
            throw CheckFailure("review WORD remembered|forgotten|unavailable")
        }
        store.review(card: try activeCard(rest[0]), response: response)
    case "edit":
        guard rest.count == 2 else { throw CheckFailure("edit WORD MEANING") }
        let card = try activeCard(rest[0])
        store.updateCardDetails(
            cardId: card.id,
            displayWord: card.displayWord,
            lemma: card.lemma,
            meaningKo: rest[1],
            definitionEn: card.definitionEn,
            partOfSpeech: card.partOfSpeech,
            exampleSentence: card.exampleSentence
        )
    case "delete":
        guard let word = rest.first else { throw CheckFailure("delete WORD") }
        store.softDeleteCard(try activeCard(word))
    case "sync":
        guard rest.count == 2 else { throw CheckFailure("sync URL TOKEN") }
        let summary = try await store.syncWithServer(serverBaseURL: rest[0], authToken: rest[1])
        print("synced cards=\(summary.cards) events=\(summary.reviewEvents) skipped=\(summary.skippedSummary.isEmpty ? "none" : summary.skippedSummary)")
    case "enrich":
        // The dictionary step of the menu bar app's auto sync: blank cards, then context meanings.
        guard rest.count == 2 else { throw CheckFailure("enrich URL TOKEN") }
        let defaults = UserDefaults(suiteName: "vocab-check.\(directory.path)") ?? .standard
        let controller = MacAutoSyncController(defaults: defaults)
        let enrichment = await controller.performDictionaryEnrichment(
            store: store,
            config: CloudSyncConfig(serverBaseURL: rest[0], bearerToken: rest[1])
        )
        let summary = try await store.syncWithServer(serverBaseURL: rest[0], authToken: rest[1])
        print("enriched applied=\(enrichment.appliedCount) synced cards=\(summary.cards)")
    case "list":
        break
    default:
        throw CheckFailure("unknown store command \(command)")
    }
    if let error = store.storageError { throw CheckFailure(error) }

    let rows = store.cards.sorted { $0.lemma < $1.lemma }.map { card -> [String: Any] in
        [
            "id": card.id,
            "lemma": card.lemma,
            "meaning_ko": card.meaningKo,
            "definition_source_primary": card.definitionSourcePrimary,
            "example_sentence": card.exampleSentence,
            "status": card.status.rawValue,
            "interval_days": card.intervalDays,
            "wordbook": card.wordbook.rawValue,
            "deleted": card.deletedAt != nil,
            "merged_into": card.mergedInto as Any? ?? NSNull(),
            "content_version": card.contentVersion,
            "schedule_version": card.scheduleVersion,
            "contexts": store.contexts.filter { $0.cardId == card.id }.count
        ]
    }
    let output = try JSONSerialization.data(withJSONObject: ["cards": rows, "pending_upload": try store.pendingUpload().digests.count], options: [.sortedKeys])
    print(String(decoding: output, as: UTF8.self))
}

// MARK: - Entry point

let arguments = Array(CommandLine.arguments.dropFirst())
do {
    switch arguments.first {
    case "vectors":
        let scheduler = try checkSchedulerVectors()
        let capture = try checkCaptureVectors()
        let merge = try checkCardMergeVectors()
        print("vectors passed: scheduler \(scheduler), capture \(capture), card merge \(merge)")
    case "store":
        try await runStore(Array(arguments.dropFirst()))
    default:
        print("usage: vocab-check vectors | vocab-check store --data-dir DIR <command> ...")
        exit(64)
    }
} catch {
    FileHandle.standardError.write(Data("vocab-check failed: \(error)\n".utf8))
    exit(1)
}
