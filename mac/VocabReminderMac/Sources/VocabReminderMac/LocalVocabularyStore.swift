import AppKit
import Foundation

@MainActor
final class LocalVocabularyStore: ObservableObject {
    static let shared = LocalVocabularyStore()

    @Published private(set) var cards: [VocabularyCard] = []
    @Published private(set) var contexts: [StoredContext] = []
    @Published private(set) var definitionCandidates: [StoredDefinitionCandidate] = []
    @Published private(set) var reviewEvents: [StoredReviewEvent] = []
    @Published private(set) var recommendationFeedback: [RecommendationFeedback] = []
    @Published var settings = UserSettingsData()
    @Published var preview: DictionaryResult?
    @Published var pendingAnalysis: CaptureAnalysis?
    @Published var message = ""
    @Published var notificationWarning = ""

    private let storeURL: URL
    private let syncURL: URL
    private var backupURL: URL { storeURL.appendingPathExtension("bak") }
    private let scheduleNotifications: Bool

    init(appSupportURL: URL? = nil, scheduleNotifications: Bool = true) {
        let appSupport = appSupportURL ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            .appendingPathComponent("VocabReminder", isDirectory: true)
        try? FileManager.default.createDirectory(at: appSupport, withIntermediateDirectories: true)
        storeURL = appSupport.appendingPathComponent("vocab-data.json")
        syncURL = appSupport.appendingPathComponent("vocab-reminder-sync.json")
        self.scheduleNotifications = scheduleNotifications
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
        let text = NSPasteboard.general.string(forType: .string)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard text.isEmpty == false else {
            preview = nil
            pendingAnalysis = nil
            message = "클립보드에 텍스트가 없습니다"
            return
        }
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
        if preview?.meaningKo.isEmpty == false || preview?.definitionEn.isEmpty == false {
            message = "저장 전 preview를 확인하세요."
        } else {
            message = "뜻을 찾지 못했습니다. 직접 입력하거나 일단 저장할 수 있습니다."
        }
    }

    func selectCandidate(_ candidate: String) {
        guard let pendingAnalysis else { return }
        let selected = CaptureNormalizer.analyzeSelection(rawText: pendingAnalysis.rawText, selectedWord: candidate)
        preview = DictionaryResolver.resolve(selected, sourceApp: "Mac")
        preview?.sourceSentence = selected.sourceSentence
        self.pendingAnalysis = nil
        message = "저장 전 preview를 확인하세요."
    }

    func persistSettings() {
        settings.updatedAt = Date()
        save()
        enqueueAutoSync()
    }

    func refreshNotifications() {
        guard scheduleNotifications else { return }
        MacNotificationScheduler.shared.reschedule(
            cards: activeCards,
            settings: settings,
            processedCardIdsToday: processedNotificationCardIdsToday()
        )
    }

    func processedNotificationCardIdsToday(now: Date = Date()) -> Set<String> {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: settings.timezone) ?? .current
        let countedResponses: Set<ReviewResponse> = [.remembered, .forgotten, .unavailable, .reveal]
        let reviewed = reviewEvents.compactMap { event -> String? in
            guard countedResponses.contains(event.response),
                  calendar.isDate(event.clientCreatedAt, inSameDayAs: now) else { return nil }
            return event.cardId
        }
        let recommendationDecisions = recommendationFeedback.compactMap { feedback -> String? in
            guard ["started", "known", "dismissed"].contains(feedback.action),
                  calendar.isDate(feedback.createdAt, inSameDayAs: now) else { return nil }
            return feedback.cardId
        }
        return Set(reviewed + recommendationDecisions)
    }

    func fillRecommendations() {
        let wasIntroduced = settings.recommendationsIntroducedAt != nil
        let generated = ensureRecommendations(now: Date())
        message = generated > 0 ? "추천 채우기 완료" + recommendationMessageSuffix(generated: generated, wasIntroduced: wasIntroduced) : recommendationWaitingMessage()
        if generated > 0 { enqueueAutoSync() }
    }

    @discardableResult
    func ensureRecommendations(now: Date = Date()) -> Int {
        let active = activeCards
        let userCards = active.filter { $0.wordbook == .user }
        let dueUserCount = userCards.filter { $0.isDueReviewCandidate(now) }.count
        let existingRecommendationCount = active.filter(\.isActiveRecommendationCandidate).count
        let reviewSignals = userCards.map { card -> UserVocabularySignal in
            let events = reviewEvents.filter { $0.cardId == card.id }
            return UserVocabularySignal(
                lemma: card.lemma,
                lapseCount: card.lapseCount,
                rememberedCount: events.filter { $0.response == .remembered }.count,
                forgottenCount: events.filter { $0.response == .forgotten }.count
            )
        }
        let feedbackSignals = recommendationFeedback.map {
            RecommendationFeedbackSignal(lemma: $0.lemma, action: $0.action)
        }
        let selected = RecommendationEngine.chooseRecommendations(
            userSignals: reviewSignals,
            feedbackSignals: feedbackSignals,
            existingLemmas: Set(cards.map(\.lemma)),
            dueUserCount: dueUserCount,
            settings: schedulerSettings(),
            limit: RecommendationEngine.recommendationFillLimit(
                dueUserCount: dueUserCount,
                existingRecommendationCount: existingRecommendationCount,
                settings: schedulerSettings()
            )
        )
        guard selected.isEmpty == false else { return 0 }

        for seed in selected {
            addRecommendedCard(seed, now: now)
        }
        if settings.recommendationsIntroducedAt == nil {
            settings.recommendationsIntroducedAt = now
            settings.updatedAt = now
            save()
        }
        return selected.count
    }

    func savePreview(meaningOverride: String, exampleOverride: String, method: CaptureMethod = .macClipboardHotkey) {
        guard var result = preview else { return }
        guard result.displayWord.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false,
              result.lemma.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false else {
            message = "단어를 입력하세요."
            return
        }
        let now = Date()
        let schedulerSettings = schedulerSettings()
        let originalMeaning = preview?.meaningKo ?? ""
        result.meaningKo = meaningOverride.trimmingCharacters(in: .whitespacesAndNewlines)
        result.exampleSentence = exampleOverride.trimmingCharacters(in: .whitespacesAndNewlines)
        result.sourceSentence = result.exampleSentence.isEmpty ? result.sourceSentence : result.exampleSentence
        let usedEnglishFallback = originalMeaning.isEmpty && result.meaningKo == result.definitionEn
        if result.meaningKo != originalMeaning && !usedEnglishFallback {
            result.sourceType = "user"
        }
        let dueAt = DueTimeJitter.apply(
            to: Scheduler.initialDueAt(now: now, settings: schedulerSettings),
            now: now,
            settings: schedulerSettings
        )
        let card = addResolvedCard(result, captureMethod: method, dueAt: dueAt, now: now)
        preview = nil
        pendingAnalysis = nil
        let wasIntroduced = settings.recommendationsIntroducedAt != nil
        let generated = ensureRecommendations(now: now)
        message = (card.meaningKo.isEmpty ? "\(card.displayWord) 뜻 없이 저장됨" : "\(card.displayWord) 저장됨") + recommendationMessageSuffix(generated: generated, wasIntroduced: wasIntroduced)
        enqueueAutoSync()
    }

    func review(card: VocabularyCard, response: ReviewResponse) {
        let now = Date()
        let recent = reviewEvents.filter { $0.cardId == card.id }.suffix(2).map(\.response)
        let schedulerSettings = schedulerSettings()
        let result = Scheduler.applyReview(
            card: card.schedulerCard,
            response: response,
            settings: schedulerSettings,
            now: now,
            recentResponses: Array(recent),
            unavailableCountToday: unavailableCountToday(cardId: card.id, settings: schedulerSettings, now: now),
            forgottenCountToday: forgottenCountToday(cardId: card.id, settings: schedulerSettings, now: now)
        )
        let jittered = DueTimeJitter.apply(to: result, now: now, settings: schedulerSettings)
        update(card: card.applying(jittered.card, now: now))
        append(event: jittered.event, now: now)
        let wasIntroduced = settings.recommendationsIntroducedAt != nil
        let generated = ensureRecommendations(now: now)
        message = "복습 기록됨" + recommendationMessageSuffix(generated: generated, wasIntroduced: wasIntroduced)
        enqueueAutoSync()
        // Also cover reviews started from the in-app card controls. Notification
        // actions already caused intermediate saves, but this final pass ensures
        // the queue reflects recommendation fills and the completed review state.
        refreshNotifications()
    }

    @discardableResult
    func review(cardId: String, response: ReviewResponse) -> VocabularyCard? {
        guard let card = cards.first(where: { $0.id == cardId }) else { return nil }
        review(card: card, response: response)
        return cards.first { $0.id == cardId }
    }

    func updateCardDetails(
        cardId: String,
        displayWord: String,
        lemma: String,
        meaningKo: String,
        definitionEn: String,
        partOfSpeech: String,
        exampleSentence: String
    ) {
        guard let index = cards.firstIndex(where: { $0.id == cardId && $0.deletedAt == nil }) else { return }
        let now = Date()
        let trimmedDisplayWord = displayWord.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedLemma = lemma.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedMeaning = meaningKo.trimmingCharacters(in: .whitespacesAndNewlines)
        var updated = cards[index]
        updated.displayWord = trimmedDisplayWord.isEmpty ? updated.displayWord : trimmedDisplayWord
        updated.lemma = (trimmedLemma.isEmpty ? updated.displayWord : trimmedLemma).lowercased()
        updated.meaningKo = trimmedMeaning
        updated.definitionEn = definitionEn.trimmingCharacters(in: .whitespacesAndNewlines)
        updated.partOfSpeech = partOfSpeech.trimmingCharacters(in: .whitespacesAndNewlines)
        updated.exampleSentence = exampleSentence.trimmingCharacters(in: .whitespacesAndNewlines)
        if updated.meaningKo != cards[index].meaningKo {
            updated.definitionSourcePrimary = "user"
        }
        updated.updatedAt = now
        updated.version += 1
        update(card: updated)
        message = "\(updated.displayWord) 수정됨"
        enqueueAutoSync()
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
        let wasIntroduced = settings.recommendationsIntroducedAt != nil
        let generated = ensureRecommendations(now: now)
        message = "복습 재조정 \(results.count)개" + recommendationMessageSuffix(generated: generated, wasIntroduced: wasIntroduced)
        if !results.isEmpty || generated > 0 { enqueueAutoSync() }
    }

    func markRecommendationStarted(_ card: VocabularyCard) {
        guard card.wordbook == .recommended, card.deletedAt == nil else { return }
        let now = Date()
        let schedulerSettings = schedulerSettings()
        var updated = card
        updated.recommendationStartedAt = now
        updated.dueAt = DueTimeJitter.apply(
            to: Scheduler.initialDueAt(now: now, settings: schedulerSettings),
            now: now,
            settings: schedulerSettings
        )
        updated.updatedAt = now
        updated.version += 1
        update(card: updated)
        appendRecommendationFeedback(card: updated, action: "started", now: now)
        message = "\(card.displayWord) 복습 시작"
        enqueueAutoSync()
    }

    func markRecommendationStarted(cardId: String) {
        guard let card = cards.first(where: { $0.id == cardId }) else { return }
        markRecommendationStarted(card)
    }

    func softDeleteRecommendation(_ card: VocabularyCard, action: String) {
        guard card.wordbook == .recommended, card.deletedAt == nil, ["known", "dismissed"].contains(action) else { return }
        let now = Date()
        appendRecommendationFeedback(card: card, action: action, now: now)
        var updated = card
        updated.deletedAt = now
        updated.updatedAt = now
        updated.version += 1
        update(card: updated)
        message = action == "known" ? "\(card.displayWord) 알고 있음으로 처리" : "\(card.displayWord) 관심 없음으로 처리"
        enqueueAutoSync()
    }

    func softDeleteRecommendation(cardId: String, action: String) {
        guard let card = cards.first(where: { $0.id == cardId }) else { return }
        softDeleteRecommendation(card, action: action)
    }

    func softDeleteCard(_ card: VocabularyCard) {
        guard card.deletedAt == nil else { return }
        let now = Date()
        var updated = card
        updated.deletedAt = now
        updated.updatedAt = now
        updated.version += 1
        update(card: updated)
        MacNotificationScheduler.shared.cancel(card: updated)
        message = "\(card.displayWord) 삭제됨"
        enqueueAutoSync()
    }

    func exportSyncSnapshot() {
        save()
        do {
            let data = try SyncBatchFactory.jsonData(
                cards: cards,
                contexts: contexts,
                definitionCandidates: definitionCandidates,
                reviewEvents: reviewEvents,
                recommendationFeedback: recommendationFeedback,
                settings: settings
            )
            try data.write(to: syncURL, options: .atomic)
            message = "Sync batch exported: \(syncURL.path)"
        } catch {
            message = "Sync batch export failed: \(error.localizedDescription)"
        }
    }

    func importSyncSnapshot() {
        guard FileManager.default.fileExists(atPath: syncURL.path) else {
            message = "가져올 sync snapshot이 없습니다."
            return
        }
        do {
            let snapshot = try SyncBatchFactory.localSnapshot(from: Data(contentsOf: syncURL))
            let merged = SyncBatchFactory.merge(
                local: StoreSnapshot(
                    cards: cards,
                    contexts: contexts,
                    definitionCandidates: definitionCandidates,
                    reviewEvents: reviewEvents,
                    recommendationFeedback: recommendationFeedback,
                    settings: settings
                ),
                incoming: snapshot
            )
            cards = merged.cards
            contexts = merged.contexts
            definitionCandidates = merged.definitionCandidates
            reviewEvents = merged.reviewEvents
            recommendationFeedback = merged.recommendationFeedback
            settings = merged.settings
            save()
            message = "Sync batch imported · cards \(activeCards.count)"
            enqueueAutoSync()
        } catch {
            message = "Sync batch import failed: \(error.localizedDescription)"
        }
    }

    func syncWithServer(serverBaseURL: String, authToken: String) async throws -> CloudSyncSummary {
        save()
        let payload = SyncBatchFactory.build(
            cards: cards,
            contexts: contexts,
            definitionCandidates: definitionCandidates,
            reviewEvents: reviewEvents,
            recommendationFeedback: recommendationFeedback,
            settings: settings
        )
        let client = CloudSyncClient(
            config: CloudSyncConfig(serverBaseURL: serverBaseURL, bearerToken: authToken)
        )
        let pushResult = try await client.push(localBatch: payload)
        let snapshotData = try await client.pullSnapshot()
        let epochCreatedCardIds = SyncBatchFactory.epochCreatedCardIds(in: snapshotData)
        let snapshot = try SyncBatchFactory.localSnapshot(from: snapshotData)
        let merged = SyncBatchFactory.merge(
            local: StoreSnapshot(
                cards: cards,
                contexts: contexts,
                definitionCandidates: definitionCandidates,
                reviewEvents: reviewEvents,
                recommendationFeedback: recommendationFeedback,
                settings: settings
            ),
            incoming: snapshot
        )
        cards = merged.cards
        contexts = merged.contexts
        definitionCandidates = merged.definitionCandidates
        reviewEvents = merged.reviewEvents
        recommendationFeedback = merged.recommendationFeedback
        settings = merged.settings
        save()
        if cards.contains(where: { epochCreatedCardIds.contains($0.id) && $0.createdAt > Date(timeIntervalSince1970: 1) }) {
            // The import repaired a legacy epoch fallback using related-record
            // evidence. Push the bumped version in this same sync run so the
            // server snapshot is healed without waiting for another timer tick.
            _ = try await client.push(localBatch: SyncBatchFactory.build(
                cards: cards,
                contexts: contexts,
                definitionCandidates: definitionCandidates,
                reviewEvents: reviewEvents,
                recommendationFeedback: recommendationFeedback,
                settings: settings
            ))
        }
        return CloudSyncSummary(cards: cards.count, reviewEvents: reviewEvents.count, skippedSummary: skippedSummary(pushResult))
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

    private func addRecommendedCard(_ seed: RecommendationSeed, now: Date) {
        _ = addResolvedCard(seed.dictionaryResult, captureMethod: .recommendation, dueAt: now, now: now, wordbook: .recommended)
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

    private func appendRecommendationFeedback(card: VocabularyCard, action: String, now: Date) {
        recommendationFeedback.append(
            RecommendationFeedback(
                id: UUID().uuidString,
                cardId: card.id,
                lemma: card.lemma,
                action: action,
                createdAt: now
            )
        )
        save()
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

    private func forgottenCountToday(cardId: String, settings: SchedulerSettings, now: Date) -> Int {
        responseCountToday(cardId: cardId, response: .forgotten, settings: settings, now: now)
    }

    private func unavailableCountToday(cardId: String, settings: SchedulerSettings, now: Date) -> Int {
        responseCountToday(cardId: cardId, response: .unavailable, settings: settings, now: now)
    }

    private func responseCountToday(
        cardId: String,
        response: ReviewResponse,
        settings: SchedulerSettings,
        now: Date
    ) -> Int {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: settings.timezone) ?? .current
        return reviewEvents.filter {
            $0.cardId == cardId &&
                $0.response == response &&
                calendar.isDate($0.clientCreatedAt, inSameDayAs: now)
        }.count
    }

    private func recommendationMessageSuffix(generated: Int, wasIntroduced: Bool) -> String {
        guard generated > 0 else { return "" }
        let intro = wasIntroduced ? "" : " · 추천 단어장이 시작됐습니다. 설정에서 끌 수 있어요."
        return "\(intro) · 추천 \(generated)개 준비됨"
    }

    private func recommendationWaitingMessage() -> String {
        "단어 50개 이상 저장하면 추천이 시작됩니다 (현재 \(activeCards.filter { $0.wordbook == .user }.count)개)"
    }

    private func load() {
        guard let data = try? Data(contentsOf: storeURL) else {
            if recoverFromBackup() {
                message = "백업에서 복구했습니다"
            } else {
                save()
            }
            return
        }
        do {
            let snapshot = try JSONDecoder.vocab.decode(StoreSnapshot.self, from: data)
            apply(snapshot)
            refreshNotifications()
        } catch {
            if recoverFromBackup() {
                message = "백업에서 복구했습니다"
            } else {
                message = "저장소를 읽지 못했습니다: \(error.localizedDescription)"
            }
        }
    }

    private func save() {
        let snapshot = StoreSnapshot(cards: cards, contexts: contexts, definitionCandidates: definitionCandidates, reviewEvents: reviewEvents, recommendationFeedback: recommendationFeedback, settings: settings)
        do {
            let data = try JSONEncoder.vocab.encode(snapshot)
            try rotateBackupIfNeeded()
            try data.write(to: storeURL, options: .atomic)
        } catch {
            message = "저장 실패: \(error.localizedDescription)"
        }
        refreshNotifications()
    }

    private func recoverFromBackup() -> Bool {
        guard let data = try? Data(contentsOf: backupURL),
              let snapshot = try? JSONDecoder.vocab.decode(StoreSnapshot.self, from: data) else {
            return false
        }
        apply(snapshot)
        try? data.write(to: storeURL, options: .atomic)
        refreshNotifications()
        return true
    }

    private func apply(_ snapshot: StoreSnapshot) {
        cards = snapshot.cards
        contexts = snapshot.contexts
        definitionCandidates = snapshot.definitionCandidates
        reviewEvents = snapshot.reviewEvents
        recommendationFeedback = snapshot.recommendationFeedback
        settings = snapshot.settings
        repairEpochScheduleFieldsFromReviewEvents()
    }

    private func repairEpochScheduleFieldsFromReviewEvents() {
        let epochDamageUpperBound = Date(timeIntervalSince1970: 1)
        let startupSyncGrace = Date().addingTimeInterval(15 * 60)
        cards = cards.map { card in
            guard card.dueAt <= epochDamageUpperBound || card.updatedAt <= epochDamageUpperBound else { return card }
            guard let latest = (reviewEvents
                .filter { event in event.cardId == card.id && event.clientCreatedAt > epochDamageUpperBound })
                .max(by: { $0.clientCreatedAt < $1.clientCreatedAt }) else { return card }
            var repaired = card
            if repaired.dueAt <= epochDamageUpperBound, latest.nextDueAt > epochDamageUpperBound {
                // The event can be old even though its timestamp is valid. Keep
                // damaged cards quiet long enough for startup sync to pull the
                // authoritative fractional timestamp instead of firing a stale
                // review immediately during app launch.
                repaired.dueAt = max(latest.nextDueAt, startupSyncGrace)
            }
            if repaired.updatedAt <= epochDamageUpperBound {
                repaired.updatedAt = latest.clientCreatedAt
            }
            return repaired
        }
    }

    private func rotateBackupIfNeeded() throws {
        let fileManager = FileManager.default
        guard fileManager.fileExists(atPath: storeURL.path) else { return }
        if fileManager.fileExists(atPath: backupURL.path) {
            try fileManager.removeItem(at: backupURL)
        }
        try fileManager.moveItem(at: storeURL, to: backupURL)
    }

    private func enqueueAutoSync() {
        MacAutoSyncController.shared.enqueueAfterLocalChange()
    }

    private func skippedSummary(_ response: [String: Any]) -> String {
        guard let skipped = response["skipped"] as? [String: Any] else { return "" }
        let names = ["cards", "contexts", "definition_candidates", "review_events", "recommendation_feedback", "settings"]
        var parts: [String] = []
        for name in names {
            let count = skipped[name] as? Int ?? 0
            if count > 0 { parts.append("\(name) \(count)") }
        }
        if let reasons = skipped["reasons"] as? [Any], reasons.isEmpty == false {
            parts.append("reasons \(reasons.count)")
        }
        return parts.joined(separator: ", ")
    }
}

struct CloudSyncSummary {
    var cards: Int
    var reviewEvents: Int
    var skippedSummary: String = ""
}

struct StoreSnapshot: Codable {
    var cards: [VocabularyCard]
    var contexts: [StoredContext]
    var definitionCandidates: [StoredDefinitionCandidate]
    var reviewEvents: [StoredReviewEvent]
    var recommendationFeedback: [RecommendationFeedback]
    var settings: UserSettingsData

    init(
        cards: [VocabularyCard],
        contexts: [StoredContext],
        definitionCandidates: [StoredDefinitionCandidate],
        reviewEvents: [StoredReviewEvent],
        recommendationFeedback: [RecommendationFeedback] = [],
        settings: UserSettingsData
    ) {
        self.cards = cards
        self.contexts = contexts
        self.definitionCandidates = definitionCandidates
        self.reviewEvents = reviewEvents
        self.recommendationFeedback = recommendationFeedback
        self.settings = settings
    }

    private enum CodingKeys: String, CodingKey {
        case cards
        case contexts
        case definitionCandidates
        case reviewEvents
        case recommendationFeedback
        case settings
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        cards = try container.decode([VocabularyCard].self, forKey: .cards)
        contexts = try container.decode([StoredContext].self, forKey: .contexts)
        definitionCandidates = try container.decode([StoredDefinitionCandidate].self, forKey: .definitionCandidates)
        reviewEvents = try container.decode([StoredReviewEvent].self, forKey: .reviewEvents)
        recommendationFeedback = try container.decodeIfPresent([RecommendationFeedback].self, forKey: .recommendationFeedback) ?? []
        settings = try container.decode(UserSettingsData.self, forKey: .settings)
    }
}

private extension VocabularyCard {
    var schedulerCard: SchedulerCard {
        SchedulerCard(id: id, status: status, intervalDays: intervalDays, dueAt: dueAt, lapseCount: lapseCount, intervalBeforeLapse: intervalBeforeLapse, meaningKo: meaningKo, wordbook: wordbook, deletedAt: deletedAt)
    }

    func isDueReviewCandidate(_ now: Date) -> Bool {
        !meaningKo.isEmpty &&
            status != .mastered &&
            status != .suspended &&
            dueAt <= now
    }

    var isActiveRecommendationCandidate: Bool {
        wordbook == .recommended &&
            !meaningKo.isEmpty &&
            status != .mastered &&
            status != .suspended
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

private extension RecommendationSeed {
    var dictionaryResult: DictionaryResult {
        DictionaryResult(
            lemma: lemma,
            displayWord: lemma,
            meaningKo: meaningKo,
            definitionEn: definitionEn,
            partOfSpeech: partOfSpeech,
            exampleSentence: exampleSentence,
            rawSource: "\(partOfSpeech): \(definitionEn)",
            sourceType: "local_dictionary",
            confidence: 0.74,
            sourceSentence: exampleSentence,
            sourceApp: "Vocabulary Recommendation"
        )
    }
}

extension JSONEncoder {
    static var vocab: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }
}

extension JSONDecoder {
    static var vocab: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }
}
