import AppKit
import Foundation

@MainActor
final class LocalVocabularyStore: ObservableObject {
    static let shared = LocalVocabularyStore()

    @Published private(set) var cards: [VocabularyCard] = []
    @Published private(set) var contexts: [StoredContext] = []
    @Published private(set) var definitionCandidates: [StoredDefinitionCandidate] = []
    @Published private(set) var reviewEvents: [StoredReviewEvent] = []
    @Published var settings = UserSettingsData()
    @Published var preview: DictionaryResult?
    @Published var pendingAnalysis: CaptureAnalysis?
    @Published var message = ""

    private let storeURL: URL
    private let syncURL: URL

    init() {
        let appSupport = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            .appendingPathComponent("VocabReminder", isDirectory: true)
        try? FileManager.default.createDirectory(at: appSupport, withIntermediateDirectories: true)
        storeURL = appSupport.appendingPathComponent("vocab-data.json")
        syncURL = appSupport.appendingPathComponent("vocab-reminder-sync.json")
        load()
    }

    var activeCards: [VocabularyCard] {
        cards.filter { $0.deletedAt == nil }.sorted { $0.createdAt > $1.createdAt }
    }

    var dueCount: Int {
        activeCards.filter { $0.meaningKo.isEmpty == false && $0.dueAt <= Date() && $0.status != .mastered && $0.status != .suspended }.count
    }

    var weakCount: Int {
        activeCards.filter { card in
            let events = reviewEvents.filter { $0.cardId == card.id && ($0.response == .remembered || $0.response == .forgotten) }
            let forgotten = events.filter { $0.response == .forgotten }.count
            return card.lapseCount >= 3 || (events.count >= 4 && Double(forgotten) / Double(events.count) >= 0.5)
        }.count
    }

    func captureClipboard() {
        let text = NSPasteboard.general.string(forType: .string) ?? ""
        capture(text: text, method: .macClipboardHotkey, sourceApp: "Clipboard")
    }

    func capture(text: String, method: CaptureMethod = .manual, sourceApp: String = "Mac") {
        let analysis = CaptureNormalizer.analyze(text)
        preview = nil
        pendingAnalysis = nil
        guard !analysis.normalizedText.isEmpty else {
            message = "저장할 텍스트가 없습니다."
            return
        }
        if analysis.needsWordSelection {
            pendingAnalysis = analysis
            message = "문장이 들어왔습니다. 저장할 단어를 선택하세요."
            return
        }
        preview = DictionaryResolver.resolve(analysis, sourceApp: sourceApp)
        message = preview?.meaningKo.isEmpty == false ? "저장 전 preview를 확인하세요." : "뜻을 찾지 못했습니다. 직접 입력하거나 일단 저장할 수 있습니다."
    }

    func selectCandidate(_ candidate: String) {
        guard let pendingAnalysis else { return }
        let selected = CaptureNormalizer.analyzeSelection(rawText: pendingAnalysis.rawText, selectedWord: candidate)
        preview = DictionaryResolver.resolve(selected, sourceApp: "Mac")
        preview?.sourceSentence = selected.sourceSentence
        self.pendingAnalysis = nil
        message = "저장 전 preview를 확인하세요."
    }

    func savePreview(meaningOverride: String, exampleOverride: String, method: CaptureMethod = .macClipboardHotkey) {
        guard var result = preview else { return }
        let now = Date()
        result.meaningKo = meaningOverride.trimmingCharacters(in: .whitespacesAndNewlines)
        result.exampleSentence = exampleOverride.trimmingCharacters(in: .whitespacesAndNewlines)
        result.sourceSentence = result.exampleSentence.isEmpty ? result.sourceSentence : result.exampleSentence
        if result.meaningKo != preview?.meaningKo {
            result.sourceType = "user"
        }
        let card = addResolvedCard(result, captureMethod: method, dueAt: Scheduler.initialDueAt(now: now, settings: schedulerSettings()), now: now)
        preview = nil
        pendingAnalysis = nil
        message = card.meaningKo.isEmpty ? "\(card.displayWord) 뜻 없이 저장됨" : "\(card.displayWord) 저장됨"
    }

    func review(card: VocabularyCard, response: ReviewResponse) {
        let now = Date()
        let recent = reviewEvents.filter { $0.cardId == card.id }.suffix(2).map(\.response)
        let result = Scheduler.applyReview(
            card: card.schedulerCard,
            response: response,
            settings: schedulerSettings(),
            now: now,
            recentResponses: Array(recent)
        )
        update(card: card.applying(result.card, now: now))
        append(event: result.event, now: now)
    }

    func rebalanceDueCards() {
        let now = Date()
        let due = activeCards.filter { !$0.meaningKo.isEmpty && $0.dueAt <= now }.map(\.schedulerCard)
        let results = Scheduler.autoRebalance(cards: due, settings: schedulerSettings(), now: now, trigger: "manual")
        for result in results {
            if let original = cards.first(where: { $0.id == result.card.id }) {
                update(card: original.applying(result.card, now: now))
                append(event: result.event, now: now)
            }
        }
        message = "복습 재조정 \(results.count)개"
    }

    func exportSyncSnapshot() {
        save()
        try? FileManager.default.copyItemReplacingExisting(from: storeURL, to: syncURL)
        message = "Sync snapshot exported: \(syncURL.path)"
    }

    func importSyncSnapshot() {
        guard FileManager.default.fileExists(atPath: syncURL.path) else {
            message = "가져올 sync snapshot이 없습니다."
            return
        }
        try? FileManager.default.copyItemReplacingExisting(from: syncURL, to: storeURL)
        load()
        message = "Sync snapshot imported"
    }

    private func addResolvedCard(_ result: DictionaryResult, captureMethod: CaptureMethod, dueAt: Date, now: Date, wordbook: Wordbook = .user) -> VocabularyCard {
        if let existingIndex = cards.firstIndex(where: { $0.deletedAt == nil && $0.lemma == result.lemma }) {
            var card = cards[existingIndex]
            let promote = card.wordbook == .recommended && wordbook == .user
            let shouldUpdatePrimary = !result.meaningKo.isEmpty && (card.meaningKo.isEmpty || result.sourceType == "user" || promote)
            if shouldUpdatePrimary {
                card.meaningKo = result.meaningKo
                card.definitionEn = result.definitionEn
                card.partOfSpeech = result.partOfSpeech
                card.definitionSourcePrimary = result.sourceType
            }
            if promote { card.wordbook = .user }
            card.exampleSentence = result.sourceSentence.isEmpty ? card.exampleSentence : result.sourceSentence
            card.updatedAt = now
            card.version += 1
            cards[existingIndex] = card
            appendContext(cardId: card.id, result: result, captureMethod: captureMethod, now: now)
            appendCandidate(cardId: card.id, result: result, selected: shouldUpdatePrimary, now: now)
            save()
            return card
        }

        let card = VocabularyCard(
            id: UUID().uuidString,
            lemma: result.lemma,
            displayWord: result.displayWord,
            meaningKo: result.meaningKo,
            definitionEn: result.definitionEn,
            partOfSpeech: result.partOfSpeech,
            exampleSentence: result.sourceSentence.isEmpty ? result.exampleSentence : result.sourceSentence,
            definitionSourcePrimary: result.sourceType,
            status: .new,
            wordbook: wordbook,
            dueAt: dueAt,
            intervalDays: 0,
            lapseCount: 0,
            createdAt: now,
            updatedAt: now,
            version: 1
        )
        cards.append(card)
        appendContext(cardId: card.id, result: result, captureMethod: captureMethod, now: now)
        appendCandidate(cardId: card.id, result: result, selected: true, now: now)
        save()
        return card
    }

    private func update(card: VocabularyCard) {
        guard let index = cards.firstIndex(where: { $0.id == card.id }) else { return }
        cards[index] = card
        save()
    }

    private func append(event: ReviewEventDraft, now: Date) {
        reviewEvents.append(
            StoredReviewEvent(
                id: UUID().uuidString,
                cardId: event.cardId,
                response: event.response,
                previousStatus: event.previousStatus,
                nextStatus: event.nextStatus,
                previousDueAt: event.previousDueAt,
                nextDueAt: event.nextDueAt,
                previousIntervalDays: event.previousIntervalDays,
                nextIntervalDays: event.nextIntervalDays,
                clientCreatedAt: now,
                metadata: event.metadata
            )
        )
        save()
    }

    private func appendContext(cardId: String, result: DictionaryResult, captureMethod: CaptureMethod, now: Date) {
        let sentence = result.sourceSentence.isEmpty ? result.exampleSentence : result.sourceSentence
        if sentence.isEmpty && result.sourceApp.isEmpty { return }
        contexts.append(StoredContext(id: UUID().uuidString, cardId: cardId, sourceSentence: sentence, sourceApp: result.sourceApp, captureMethod: captureMethod, createdAt: now))
    }

    private func appendCandidate(cardId: String, result: DictionaryResult, selected: Bool, now: Date) {
        definitionCandidates.append(
            StoredDefinitionCandidate(
                id: UUID().uuidString,
                cardId: cardId,
                source: result.sourceType,
                rawText: result.rawSource,
                parsedMeaningKo: result.meaningKo,
                parsedDefinitionEn: result.definitionEn,
                partOfSpeech: result.partOfSpeech,
                exampleSentence: result.exampleSentence,
                confidence: result.confidence,
                selectedAsPrimary: selected,
                createdAt: now
            )
        )
    }

    private func schedulerSettings() -> SchedulerSettings {
        SchedulerSettings(
            dailyWordGoal: settings.dailyWordGoal,
            maxQueueSize: settings.maxQueueSize,
            snoozeDurationMinutes: settings.snoozeDurationMinutes,
            quietHoursStart: settings.quietHoursStart,
            quietHoursEnd: settings.quietHoursEnd,
            timezone: settings.timezone,
            enableWordRecommendations: settings.enableWordRecommendations
        )
    }

    private func load() {
        guard let data = try? Data(contentsOf: storeURL) else {
            save()
            return
        }
        do {
            let snapshot = try JSONDecoder.vocab.decode(StoreSnapshot.self, from: data)
            cards = snapshot.cards
            contexts = snapshot.contexts
            definitionCandidates = snapshot.definitionCandidates
            reviewEvents = snapshot.reviewEvents
            settings = snapshot.settings
        } catch {
            message = "저장소를 읽지 못했습니다: \(error.localizedDescription)"
        }
    }

    private func save() {
        let snapshot = StoreSnapshot(cards: cards, contexts: contexts, definitionCandidates: definitionCandidates, reviewEvents: reviewEvents, settings: settings)
        if let data = try? JSONEncoder.vocab.encode(snapshot) {
            try? data.write(to: storeURL, options: .atomic)
        }
    }
}

private struct StoreSnapshot: Codable {
    var cards: [VocabularyCard]
    var contexts: [StoredContext]
    var definitionCandidates: [StoredDefinitionCandidate]
    var reviewEvents: [StoredReviewEvent]
    var settings: UserSettingsData
}

private extension VocabularyCard {
    var schedulerCard: SchedulerCard {
        SchedulerCard(id: id, status: status, intervalDays: intervalDays, dueAt: dueAt, lapseCount: lapseCount, intervalBeforeLapse: intervalBeforeLapse, meaningKo: meaningKo, wordbook: wordbook, deletedAt: deletedAt)
    }

    func applying(_ schedulerCard: SchedulerCard, now: Date) -> VocabularyCard {
        var copy = self
        copy.status = schedulerCard.status
        copy.dueAt = schedulerCard.dueAt
        copy.intervalDays = schedulerCard.intervalDays
        copy.lapseCount = schedulerCard.lapseCount
        copy.intervalBeforeLapse = schedulerCard.intervalBeforeLapse
        copy.updatedAt = now
        copy.version += 1
        return copy
    }
}

private extension JSONEncoder {
    static var vocab: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }
}

private extension JSONDecoder {
    static var vocab: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }
}

private extension FileManager {
    func copyItemReplacingExisting(from source: URL, to destination: URL) throws {
        if fileExists(atPath: destination.path) {
            try removeItem(at: destination)
        }
        try copyItem(at: source, to: destination)
    }
}
