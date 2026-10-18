import CryptoKit
import Foundation

@MainActor
package final class LocalVocabularyStore: ObservableObject {
    package static let shared = LocalVocabularyStore()

    @Published package private(set) var cards: [VocabularyCard] = []
    @Published package private(set) var contexts: [StoredContext] = []
    @Published package private(set) var definitionCandidates: [StoredDefinitionCandidate] = []
    @Published package private(set) var reviewEvents: [StoredReviewEvent] = []
    @Published package private(set) var syncEvents: [StoredSyncEvent] = []
    @Published package private(set) var recommendationFeedback: [RecommendationFeedback] = []
    @Published package var settings = UserSettingsData()
    @Published package var preview: DictionaryResult?
    @Published package var pendingAnalysis: CaptureAnalysis?
    @Published package var message = ""
    @Published package var notificationWarning = ""
    @Published package private(set) var dictionaryEnrichmentStatus = DictionaryEnrichmentStatus.idle
    @Published package private(set) var lastCaptureDiagnostic = CaptureDiagnosticStore.load()

    @Published package private(set) var storageError: String?
    private var committedSnapshot: StoreSnapshot?
    private var syncCursor: String?
    private var syncCursorEndpoint: String?
    private var mutationDepth = 0
    private var pendingSave = false
    private var pendingSync = false
    /// Test hook run inside the commit transaction; throwing simulates a failed write.
    private let beforeCommit: () throws -> Void
    /// While server rows are being applied, saves neither stamp clocks nor queue uploads.
    private var applyingRemote = false
    private var remoteRowsToUpload: Set<String> = []

    private let database: VocabularyDatabase?
    private let legacyStoreURL: URL
    private let syncURL: URL
    private let scheduleNotifications: Bool
    private let previewDictionaryLookup: ((String) async throws -> CloudDictionaryLookupResult?)?
    private var previewLookupTask: Task<Void, Never>?
    private var previewLookupID: UUID?
    private var pendingCaptureMethod: CaptureMethod?
    private var pendingCaptureSourceURL: String?
    private var pendingCaptureSourceApp = ""

    package init(
        appSupportURL: URL? = nil,
        scheduleNotifications: Bool = true,
        previewDictionaryLookup: ((String) async throws -> CloudDictionaryLookupResult?)? = nil,
        beforeCommit: @escaping () throws -> Void = {}
    ) {
        // VOCAB_REMINDER_DATA_DIR isolates end-to-end runs from the user's real data.
        let appSupport = appSupportURL
            ?? ProcessInfo.processInfo.environment["VOCAB_REMINDER_DATA_DIR"].map { URL(filePath: $0, directoryHint: .isDirectory) }
            ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
                .appendingPathComponent("VocabReminder", isDirectory: true)
        try? FileManager.default.createDirectory(at: appSupport, withIntermediateDirectories: true)
        legacyStoreURL = appSupport.appendingPathComponent("vocab-data.json")
        syncURL = appSupport.appendingPathComponent("vocab-reminder-sync.json")
        do {
            database = try VocabularyDatabase(url: appSupport.appendingPathComponent("vocab.sqlite"))
        } catch {
            database = nil
            storageError = error.localizedDescription
        }
        self.beforeCommit = beforeCommit
        self.scheduleNotifications = scheduleNotifications
        self.previewDictionaryLookup = previewDictionaryLookup
        load()
    }

    package var activeCards: [VocabularyCard] {
        let excluded = Set(recommendationFeedback.filter { ["known", "dismissed"].contains($0.action) }
            .map { RecommendationEngine.normalizedLemma($0.lemma) })
        return cards.filter {
            $0.deletedAt == nil && ($0.wordbook != .recommended || !excluded.contains(RecommendationEngine.normalizedLemma($0.lemma)))
        }.sorted { $0.createdAt > $1.createdAt }
    }

    package var dueCount: Int {
        activeCards.filter { $0.meaningKo.isEmpty == false && $0.dueAt <= Date() && $0.status != .mastered && $0.status != .suspended }.count
    }

    package var weakCount: Int {
        activeCards.filter { card in
            let events = reviewEvents.filter { $0.cardId == card.id && ($0.response == .remembered || $0.response == .forgotten) }
            let forgotten = events.filter { $0.response == .forgotten }.count
            return card.lapseCount >= 3 || (events.count >= 4 && Double(forgotten) / Double(events.count) >= 0.5)
        }.count
    }

    /// The app reads the pasteboard; the store stays free of AppKit.
    package func captureClipboard(_ clipboardText: String?) {
        let text = clipboardText?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard text.isEmpty == false else {
            preview = nil
            pendingAnalysis = nil
            message = "클립보드에 텍스트가 없습니다"
            recordCaptureFailure(method: .macClipboardHotkey, detailCode: "clipboard_empty")
            return
        }
        capture(
            text: text,
            method: .macClipboardHotkey,
            sourceApp: MacCaptureSource.clipboard
        )
    }

    package func capture(
        text: String,
        method: CaptureMethod = .manual,
        sourceApp: String = "",
        sourceURL: String? = nil
    ) {
        cancelPreviewLookup()
        let canonicalSourceApp = MacCaptureSource.canonicalSourceApp(
            method: method,
            declaredSourceApp: sourceApp
        )
        pendingCaptureMethod = method
        pendingCaptureSourceURL = SourceURLSanitizer.sanitize(sourceURL)
        pendingCaptureSourceApp = canonicalSourceApp
        let analysis = CaptureNormalizer.analyze(text)
        preview = nil
        pendingAnalysis = nil
        guard !analysis.normalizedText.isEmpty else {
            message = "저장할 텍스트가 없습니다."
            recordCaptureFailure(method: method, detailCode: "normalized_text_empty")
            return
        }
        if analysis.needsWordSelection {
            pendingAnalysis = analysis
            message = "문장이 들어왔습니다. 저장할 단어를 선택하세요."
            recordCaptureSuccess(method: method, detailCode: "selection_required")
            return
        }
        preview = DictionaryResolver.resolve(analysis, sourceApp: canonicalSourceApp)
        recordCaptureSuccess(method: method, detailCode: "preview_ready")
        if preview?.meaningKo.isEmpty == false {
            message = "저장 전 preview를 확인하세요."
        } else {
            startPreviewLookup(lemma: analysis.lemma)
        }
    }

    package func selectCandidate(_ candidate: String) {
        cancelPreviewLookup()
        guard let pendingAnalysis else { return }
        let selected = CaptureNormalizer.analyzeSelection(rawText: pendingAnalysis.rawText, selectedWord: candidate)
        preview = DictionaryResolver.resolve(selected, sourceApp: pendingCaptureSourceApp)
        preview?.sourceSentence = selected.sourceSentence
        self.pendingAnalysis = nil
        recordCaptureSuccess(method: pendingCaptureMethod ?? .manual, detailCode: "candidate_selected")
        if preview?.meaningKo.isEmpty == false {
            message = "저장 전 preview를 확인하세요."
        } else {
            startPreviewLookup(lemma: selected.lemma)
        }
    }

    private func startPreviewLookup(lemma: String) {
        let lookupID = UUID()
        previewLookupID = lookupID
        message = "한국어 뜻 찾는 중..."
        previewLookupTask = Task { @MainActor [weak self] in
            do {
                guard let self else { return }
                let result: CloudDictionaryLookupResult?
                if let previewDictionaryLookup {
                    result = try await previewDictionaryLookup(lemma)
                } else if let credentials = SyncDefaults.effectiveCredentials() {
                    result = try await CloudSyncClient(
                        config: CloudSyncConfig(
                            serverBaseURL: credentials.serverURL,
                            bearerToken: credentials.bearerToken
                        )
                    ).lookupDictionary(lemmas: [lemma]).first
                } else {
                    result = nil
                }
                guard !Task.isCancelled,
                      previewLookupID == lookupID,
                      var current = preview,
                      current.lemma == lemma,
                      current.meaningKo.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                      let result,
                      result.outcome == .resolved
                else {
                    if !Task.isCancelled, previewLookupID == lookupID {
                        message = "한국어 뜻을 찾지 못했습니다. 직접 입력하거나 일단 저장할 수 있습니다."
                    }
                    return
                }
                current.meaningKo = result.meaningKo.trimmingCharacters(in: .whitespacesAndNewlines)
                if current.definitionEn.isEmpty {
                    current.definitionEn = result.definitionEn.trimmingCharacters(in: .whitespacesAndNewlines)
                }
                if current.partOfSpeech.isEmpty {
                    current.partOfSpeech = result.partOfSpeech.trimmingCharacters(in: .whitespacesAndNewlines)
                }
                if current.exampleSentence.isEmpty {
                    current.exampleSentence = result.exampleSentence.trimmingCharacters(in: .whitespacesAndNewlines)
                }
                current.sourceType = result.sourceType.isEmpty ? "server_dictionary" : result.sourceType
                current.confidence = min(max(result.confidence, 0), 1)
                preview = current
                message = "한국어 뜻을 찾았습니다. 저장 전 preview를 확인하세요."
            } catch {
                guard !Task.isCancelled, self?.previewLookupID == lookupID else { return }
                self?.message = "한국어 뜻 조회가 지연되고 있습니다. 직접 입력하거나 일단 저장할 수 있습니다."
            }
        }
    }

    private func cancelPreviewLookup() {
        previewLookupTask?.cancel()
        previewLookupTask = nil
        previewLookupID = nil
    }

    package func persistSettings() {
        _ = performMutation {
            settings.updatedAt = Date()
            save()
            _ = ensureRecommendations()
            enqueueAutoSync()

        }
    }

    package func refreshNotifications() {
        guard scheduleNotifications, mutationDepth == 0, storageError == nil else { return }
        MacNotificationScheduler.shared.reschedule(
            cards: activeCards,
            settings: settings,
            processedCardIdsToday: processedNotificationCardIdsToday()
        )
    }

    package func processedNotificationCardIdsToday(now: Date = Date()) -> Set<String> {
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

    package func fillRecommendations() {
        message = "컴퓨터에서 준비한 추천을 동기화합니다."
        enqueueAutoSync()
    }

    @discardableResult
    package func ensureRecommendations(now: Date = Date()) -> Int {
        // Compatibility entry point: new cards are created only by the sync server.
        0
    }

    package func savePreview(
        meaningOverride: String,
        exampleOverride: String,
        method: CaptureMethod? = nil
    ) {
        _ = performMutation {
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
            let resolvedMethod = method ?? pendingCaptureMethod ?? .manual
            let card = addResolvedCard(
                result,
                captureMethod: resolvedMethod,
                sourceURL: pendingCaptureSourceURL,
                dueAt: dueAt,
                now: now
            )
            cancelPreviewLookup()
            preview = nil
            pendingAnalysis = nil
            recordCaptureSuccess(method: resolvedMethod, detailCode: "saved")
            pendingCaptureMethod = nil
            pendingCaptureSourceURL = nil
            pendingCaptureSourceApp = ""
            let wasIntroduced = settings.recommendationsIntroducedAt != nil
            let generated = ensureRecommendations(now: now)
            message = (card.meaningKo.isEmpty ? "\(card.displayWord) 뜻 없이 저장됨" : "\(card.displayWord) 저장됨") + recommendationMessageSuffix(generated: generated, wasIntroduced: wasIntroduced)
            enqueueAutoSync()

        }
    }

    package func cancelCapture() {
        cancelPreviewLookup()
        preview = nil
        pendingAnalysis = nil
        pendingCaptureMethod = nil
        pendingCaptureSourceURL = nil
        pendingCaptureSourceApp = ""
    }

    package func recordCaptureFailure(
        method: CaptureMethod,
        detailCode: String,
        mechanismOverride: String? = nil
    ) {
        recordCaptureDiagnostic(
            CaptureDiagnosticRecord(
                outcome: .failed,
                mechanism: mechanismOverride ?? diagnosticMechanism(method),
                detailCode: detailCode,
                occurredAt: Date()
            )
        )
    }

    package func review(card: VocabularyCard, response: ReviewResponse) {
        _ = performMutation {
            let now = Date()
            let recent = schedulerRecentResponses(cardId: card.id, limit: 2)
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
    }

    package func schedulerRecentResponses(cardId: String, limit: Int) -> [ReviewResponse] {
        let countedResponses: Set<ReviewResponse> = [.remembered, .forgotten, .unavailable, .reveal]
        return Array(
            reviewEvents
                .filter { $0.cardId == cardId && countedResponses.contains($0.response) }
                .suffix(max(0, limit))
                .map(\.response)
        )
    }

    @discardableResult
    package func review(cardId: String, response: ReviewResponse) -> VocabularyCard? {
        guard let card = cards.first(where: { $0.id == cardId }) else { return nil }
        review(card: card, response: response)
        return cards.first { $0.id == cardId }
    }

    package func updateCardDetails(
        cardId: String,
        displayWord: String,
        lemma: String,
        meaningKo: String,
        definitionEn: String,
        partOfSpeech: String,
        exampleSentence: String
    ) {
        _ = performMutation {
            guard let index = cards.firstIndex(where: { $0.id == cardId && $0.deletedAt == nil }) else { return }
            let now = Date()
            let previous = cards[index]
            let trimmedDisplayWord = displayWord.trimmingCharacters(in: .whitespacesAndNewlines)
            let trimmedLemma = lemma.trimmingCharacters(in: .whitespacesAndNewlines)
            let trimmedMeaning = meaningKo.trimmingCharacters(in: .whitespacesAndNewlines)
            var updated = previous
            updated.displayWord = trimmedDisplayWord.isEmpty ? updated.displayWord : trimmedDisplayWord
            updated.lemma = (trimmedLemma.isEmpty ? updated.displayWord : trimmedLemma).lowercased()
            updated.meaningKo = trimmedMeaning
            updated.definitionEn = definitionEn.trimmingCharacters(in: .whitespacesAndNewlines)
            updated.partOfSpeech = partOfSpeech.trimmingCharacters(in: .whitespacesAndNewlines)
            updated.exampleSentence = exampleSentence.trimmingCharacters(in: .whitespacesAndNewlines)
            if updated.meaningKo != previous.meaningKo {
                updated.definitionSourcePrimary = "user"
            }
            updated.updatedAt = now
            updated.version += 1
            ensureCardMeaningCandidate(previous, now: now)
            appendManualMeaningCandidate(updated, baseCardVersion: previous.version, now: now)
            update(card: updated)
            appendManualEditEvent(
                previous: previous,
                updated: updated,
                action: "card_edit",
                metadata: [:],
                now: now
            )
            message = "\(updated.displayWord) 수정됨"
            enqueueAutoSync()

        }
    }

    package func restoreDefinitionCandidate(cardId: String, candidateId: String) {
        _ = performMutation {
            guard let cardIndex = cards.firstIndex(where: { $0.id == cardId && $0.deletedAt == nil }),
                  let candidate = definitionCandidates.first(where: { $0.id == candidateId && $0.cardId == cardId })
            else {
                message = "복구할 뜻 후보를 찾지 못했습니다."
                return
            }
            let previous = cards[cardIndex]
            var updated = previous
            updated.meaningKo = candidate.parsedMeaningKo.trimmingCharacters(in: .whitespacesAndNewlines)
            updated.definitionEn = candidate.parsedDefinitionEn.trimmingCharacters(in: .whitespacesAndNewlines)
            updated.partOfSpeech = candidate.partOfSpeech.trimmingCharacters(in: .whitespacesAndNewlines)
            if candidate.exampleSentence.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false {
                updated.exampleSentence = candidate.exampleSentence.trimmingCharacters(in: .whitespacesAndNewlines)
            }
            updated.definitionSourcePrimary = candidate.source.isEmpty ? "unknown" : candidate.source

            guard definitionContent(of: updated) != definitionContent(of: previous) ||
                    updated.definitionSourcePrimary != previous.definitionSourcePrimary
            else {
                message = "이미 현재 primary 뜻입니다."
                return
            }

            let now = Date()
            if previous.meaningKo.isEmpty, updated.meaningKo.isEmpty == false {
                let schedulerSettings = schedulerSettings()
                updated.dueAt = DueTimeJitter.apply(
                    to: Scheduler.initialDueAt(now: now, settings: schedulerSettings),
                    now: now,
                    settings: schedulerSettings
                )
            }
            updated.updatedAt = now
            updated.version += 1
            ensureCardMeaningCandidate(previous, now: now)
            definitionCandidates.append(
                StoredDefinitionCandidate(
                    id: UUID().uuidString,
                    cardId: cardId,
                    source: updated.definitionSourcePrimary,
                    rawText: candidate.rawText,
                    parsedMeaningKo: updated.meaningKo,
                    parsedDefinitionEn: updated.definitionEn,
                    partOfSpeech: updated.partOfSpeech,
                    exampleSentence: updated.exampleSentence,
                    confidence: candidate.confidence,
                    selectedAsPrimary: true,
                    createdAt: now,
                    originDeviceId: SyncBatchFactory.deviceId(),
                    baseCardVersion: previous.version
                )
            )
            cards[cardIndex] = updated
            save()
            appendManualEditEvent(
                previous: previous,
                updated: updated,
                action: "definition_candidate_restored",
                metadata: [
                    "candidate_id": candidate.id,
                    "candidate_source": candidate.source
                ],
                now: now
            )
            message = "\(updated.displayWord) 뜻 후보를 primary로 복구했습니다."
            enqueueAutoSync()

        }
    }

    package func rebalanceDueCards() {
        _ = performMutation {
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
    }

    package func markRecommendationStarted(_ card: VocabularyCard) {
        _ = performMutation {
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
    }

    package func markRecommendationStarted(cardId: String) {
        guard let card = cards.first(where: { $0.id == cardId }) else { return }
        markRecommendationStarted(card)
    }

    package func softDeleteRecommendation(_ card: VocabularyCard, action: String) {
        _ = performMutation {
            guard card.wordbook == .recommended, card.deletedAt == nil, ["known", "dismissed"].contains(action) else { return }
            let now = Date()
            appendRecommendationFeedback(card: card, action: action, now: now)
            var updated = card
            updated.deletedAt = now
            updated.updatedAt = now
            updated.version += 1
            update(card: updated)
            _ = ensureRecommendations(now: now)
            message = action == "known" ? "\(card.displayWord) 알고 있음으로 처리" : "\(card.displayWord) 관심 없음으로 처리"
            enqueueAutoSync()

        }
    }

    package func softDeleteRecommendation(cardId: String, action: String) {
        guard let card = cards.first(where: { $0.id == cardId }) else { return }
        softDeleteRecommendation(card, action: action)
    }

    package func softDeleteCard(_ card: VocabularyCard) {
        _ = performMutation {
            guard card.deletedAt == nil else { return }
            let now = Date()
            var updated = card
            updated.deletedAt = now
            updated.updatedAt = now
            updated.version += 1
            update(card: updated)
            if scheduleNotifications {
                MacNotificationScheduler.shared.cancel(card: updated)
            }
            message = "\(card.displayWord) 삭제됨"
            enqueueAutoSync()

        }
    }

    package func exportSyncSnapshot() {
        guard save() else { return }
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

    package func importSyncSnapshot() {
        _ = performMutation {
            guard FileManager.default.fileExists(atPath: syncURL.path) else {
                message = "가져올 sync snapshot이 없습니다."
                return
            }
            do {
                let snapshot = try SyncBatchFactory.localSnapshot(from: Data(contentsOf: syncURL))
                beginApplyingRemote(snapshot)
                defer { endApplyingRemote() }
                let merged = SyncBatchFactory.merge(
                    local: StoreSnapshot(
                        cards: cards,
                        contexts: contexts,
                        definitionCandidates: definitionCandidates,
                        reviewEvents: reviewEvents,
                        syncEvents: syncEvents,
                        recommendationFeedback: recommendationFeedback,
                        settings: settings
                    ),
                    incoming: snapshot
                )
                cards = merged.cards
                contexts = merged.contexts
                definitionCandidates = merged.definitionCandidates
                reviewEvents = merged.reviewEvents
                syncEvents = merged.syncEvents
                recommendationFeedback = merged.recommendationFeedback
                settings = merged.settings
                save()
                message = "Sync batch imported · cards \(activeCards.count)"
                enqueueAutoSync()
            } catch {
                message = "Sync batch import failed: \(error.localizedDescription)"
            }

        }
    }

    /// One sync round: upload the outbox, then pull what changed since the last
    /// cursor. The upload always runs, even empty, because it is what lets the
    /// server top up today's recommendations; reads never write.
    package func syncWithServer(serverBaseURL: String, authToken: String, session: URLSession = .shared) async throws -> CloudSyncSummary {
        try requireSaved()
        let client = CloudSyncClient(
            config: CloudSyncConfig(serverBaseURL: serverBaseURL, bearerToken: authToken),
            session: session
        )
        let endpoint = serverBaseURL.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        var cursor = syncCursorEndpoint == endpoint ? syncCursor : nil
        if cursor == nil { try queueEverythingForUpload() }
        var upload = try pendingUpload()
        var pushResult = try await client.push(localBatch: try upload.payload())
        var snapshotData = try await client.pullSnapshot(cursor: cursor)
        var cursorPayload = try JSONSerialization.jsonObject(with: snapshotData) as? [String: Any]
        if cursorPayload?["sync_reset"] as? Bool == true, cursor != nil {
            // The server's change log was reset (restore or new database):
            // send everything again and take a full copy back.
            try queueEverythingForUpload()
            upload = try pendingUpload()
            pushResult = try await client.push(localBatch: try upload.payload())
            cursor = nil
            snapshotData = try await client.pullSnapshot()
            cursorPayload = try JSONSerialization.jsonObject(with: snapshotData) as? [String: Any]
        }
        let snapshot = try SyncBatchFactory.localSnapshot(from: snapshotData)
        beginApplyingRemote(snapshot)
        defer { endApplyingRemote() }
        let merged = SyncBatchFactory.merge(
            local: StoreSnapshot(
                cards: cards,
                contexts: contexts,
                definitionCandidates: definitionCandidates,
                reviewEvents: reviewEvents,
                syncEvents: syncEvents,
                recommendationFeedback: recommendationFeedback,
                settings: settings
            ),
            incoming: snapshot
        )
        cards = merged.cards
        contexts = merged.contexts
        definitionCandidates = merged.definitionCandidates
        reviewEvents = merged.reviewEvents
        syncEvents = merged.syncEvents
        recommendationFeedback = merged.recommendationFeedback
        settings = merged.settings
        syncCursor = cursorPayload?["sync_cursor"] as? String
        syncCursorEndpoint = endpoint
        try requireSaved()
        // The server answered for every row; validation skips are deterministic
        // and are reported below instead of being re-sent each round.
        try acknowledgeUpload(upload.digests)
        let skipped = skippedSummary(pushResult)
        appendSyncEvent(
            succeeded: true,
            summary: skipped.isEmpty
                ? "카드 \(cards.count), 이벤트 \(reviewEvents.count) 동기화"
                : "동기화 완료 · 스킵 \(skipped)",
            now: Date()
        )
        return CloudSyncSummary(cards: cards.count, reviewEvents: reviewEvents.count, skippedSummary: skipped)
    }

    /// Server cards that merged into something different hold local edits the
    /// server has not seen yet; they stay queued for upload.
    private func beginApplyingRemote(_ incoming: StoreSnapshot) {
        applyingRemote = true
        let local = Dictionary(uniqueKeysWithValues: cards.map { ($0.id, $0) })
        remoteRowsToUpload = Set(incoming.cards.compactMap { card in
            guard let existing = local[card.id], CardMerge.merge(existing, card) != card else { return nil }
            return card.id
        })
    }

    private func endApplyingRemote() {
        applyingRemote = false
        remoteRowsToUpload = []
    }

    package func recordSyncFailure(_ error: Error, now: Date = Date()) {
        let summary: String
        if let cloudError = error as? CloudSyncError {
            switch cloudError {
            case .http(let status, let requestID):
                summary = "동기화 실패 · HTTP \(status) · 요청 \(String(requestID.prefix(12)))"
            case .server:
                if let requestID = cloudError.requestID {
                    summary = "동기화 실패 · 네트워크/응답 · 요청 \(String(requestID.prefix(12)))"
                } else {
                    summary = "동기화 실패 · 네트워크/응답"
                }
            case .invalidConfig:
                summary = "동기화 실패 · 설정 확인 필요"
            case .invalidResponse:
                summary = "동기화 실패 · 응답 형식"
            }
        } else {
            summary = "동기화 실패 · 로컬 처리"
        }
        appendSyncEvent(succeeded: false, summary: summary, now: now)
    }

    package func pendingDictionaryEnrichmentTargets() -> [DictionaryEnrichmentTarget] {
        cards
            .filter {
                $0.deletedAt == nil &&
                    $0.meaningKo.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
                    $0.definitionSourcePrimary != "user" &&
                    !$0.lemma.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            }
            .sorted {
                if $0.createdAt != $1.createdAt { return $0.createdAt < $1.createdAt }
                return $0.id < $1.id
            }
            .map {
                DictionaryEnrichmentTarget(
                    cardId: $0.id,
                    lemma: Self.normalizedDictionaryLemma($0.lemma),
                    sentence: ContextMeaning.sentence(of: $0),
                    contextKey: ContextMeaning.key(of: $0)
                )
            }
    }

    /// The user typed this meaning, chose a candidate, or restored one, on any device.
    package func isMeaningPinned(_ card: VocabularyCard) -> Bool {
        card.definitionSourcePrimary == "user" ||
            reviewEvents.contains {
                $0.cardId == card.id && $0.response == .manualEdit && $0.metadata["action"] != "rebalance"
            }
    }

    /// Cards with a dictionary meaning whose captured sentence may pick a better one.
    package func pendingContextMeaningTargets(
        isEligible: (_ cardId: String, _ contextKey: String) -> Bool
    ) -> [DictionaryEnrichmentTarget] {
        cards
            .filter {
                $0.deletedAt == nil &&
                    $0.wordbook == .user &&
                    $0.meaningKo.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false &&
                    $0.definitionSourcePrimary != "user" &&
                    $0.definitionSourcePrimary != ContextMeaning.source &&
                    ContextMeaning.sentence(of: $0).isEmpty == false &&
                    Self.normalizedDictionaryLemma($0.lemma).isEmpty == false
            }
            .sorted {
                if $0.createdAt != $1.createdAt { return $0.createdAt < $1.createdAt }
                return $0.id < $1.id
            }
            .filter { isEligible($0.id, ContextMeaning.key(of: $0)) && isMeaningPinned($0) == false }
            .map {
                DictionaryEnrichmentTarget(
                    cardId: $0.id,
                    lemma: Self.normalizedDictionaryLemma($0.lemma),
                    sentence: ContextMeaning.sentence(of: $0),
                    contextKey: ContextMeaning.key(of: $0)
                )
            }
    }

    /// Applies context results to cards that already have a meaning. Only an
    /// llm_context pick replaces a meaning, never a pinned one (that card gains
    /// the pick as an unselected candidate), and the schedule is left alone.
    @discardableResult
    package func applyContextMeanings(
        _ pairs: [(target: DictionaryEnrichmentTarget, result: CloudDictionaryLookupResult)],
        now: Date = Date()
    ) -> [String] {
        var appliedCardIds: [String] = []
        var changed = false
        for (target, result) in pairs where ContextMeaning.isResolvedFromContext(result) {
            guard let index = cards.firstIndex(where: { $0.id == target.cardId }),
                  cards[index].deletedAt == nil,
                  cards[index].meaningKo.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false,
                  cards[index].definitionSourcePrimary != ContextMeaning.source,
                  ContextMeaning.key(of: cards[index]) == target.contextKey
            else { continue }
            let previous = cards[index]
            let meaning = result.meaningKo.trimmingCharacters(in: .whitespacesAndNewlines)
            let partOfSpeech = result.partOfSpeech.trimmingCharacters(in: .whitespacesAndNewlines)
            var picked = previous
            picked.meaningKo = meaning
            if partOfSpeech.isEmpty == false { picked.partOfSpeech = partOfSpeech }

            if isMeaningPinned(previous) {
                let alreadyKept = definitionCandidates.contains {
                    $0.cardId == previous.id && $0.source == ContextMeaning.source && $0.parsedMeaningKo == meaning
                }
                if alreadyKept == false {
                    appendContextCandidate(picked, result: result, selected: false, now: now)
                    changed = true
                }
                continue
            }

            ensureCardMeaningCandidate(previous, now: now)
            picked.definitionSourcePrimary = ContextMeaning.source
            picked.updatedAt = now
            picked.version += 1
            cards[index] = picked
            appendContextCandidate(picked, result: result, selected: true, now: now, baseCardVersion: previous.version)
            appliedCardIds.append(picked.id)
            changed = true
        }
        if changed {
            guard save() else { return [] }
        }
        return appliedCardIds
    }

    private func appendContextCandidate(
        _ card: VocabularyCard,
        result: CloudDictionaryLookupResult,
        selected: Bool,
        now: Date,
        baseCardVersion: Int? = nil
    ) {
        definitionCandidates.append(
            StoredDefinitionCandidate(
                id: UUID().uuidString,
                cardId: card.id,
                source: ContextMeaning.source,
                rawText: card.meaningKo,
                parsedMeaningKo: card.meaningKo,
                parsedDefinitionEn: card.definitionEn,
                partOfSpeech: card.partOfSpeech,
                exampleSentence: result.contextSentence.isEmpty ? card.exampleSentence : result.contextSentence,
                confidence: min(max(result.confidence, 0), 1),
                selectedAsPrimary: selected,
                createdAt: now,
                originDeviceId: selected ? SyncBatchFactory.deviceId() : nil,
                baseCardVersion: baseCardVersion
            )
        )
    }

    @discardableResult
    package func applyDictionaryEnrichment(
        _ results: [CloudDictionaryLookupResult],
        requestedTargets: [DictionaryEnrichmentTarget],
        now: Date = Date()
    ) -> DictionaryEnrichmentApplySummary {
        var resultByLemma: [String: CloudDictionaryLookupResult] = [:]
        for result in results where result.outcome == .resolved {
            let lemma = Self.normalizedDictionaryLemma(result.lemma)
            if resultByLemma[lemma] == nil {
                resultByLemma[lemma] = result
            }
        }

        var appliedCardIds: [String] = []
        let schedulerSettings = schedulerSettings()
        for target in requestedTargets {
            guard let index = cards.firstIndex(where: { $0.id == target.cardId }),
                  cards[index].deletedAt == nil,
                  cards[index].meaningKo.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  cards[index].definitionSourcePrimary != "user",
                  Self.normalizedDictionaryLemma(cards[index].lemma) == target.lemma,
                  let result = resultByLemma[target.lemma]
            else {
                continue
            }

            let meaning = result.meaningKo.trimmingCharacters(in: .whitespacesAndNewlines)
            guard meaning.isEmpty == false else { continue }

            let baseCardVersion = cards[index].version
            var card = cards[index]
            card.meaningKo = meaning
            let definitionEn = result.definitionEn.trimmingCharacters(in: .whitespacesAndNewlines)
            let partOfSpeech = result.partOfSpeech.trimmingCharacters(in: .whitespacesAndNewlines)
            let exampleSentence = result.exampleSentence.trimmingCharacters(in: .whitespacesAndNewlines)
            if definitionEn.isEmpty == false { card.definitionEn = definitionEn }
            if partOfSpeech.isEmpty == false { card.partOfSpeech = partOfSpeech }
            if card.exampleSentence.isEmpty, exampleSentence.isEmpty == false {
                card.exampleSentence = exampleSentence
            }
            card.definitionSourcePrimary = result.sourceType.isEmpty ? "server_dictionary" : result.sourceType
            card.dueAt = DueTimeJitter.apply(
                to: Scheduler.initialDueAt(now: now, settings: schedulerSettings),
                now: now,
                settings: schedulerSettings
            )
            card.updatedAt = now
            card.version += 1
            cards[index] = card

            definitionCandidates.append(
                StoredDefinitionCandidate(
                    id: UUID().uuidString,
                    cardId: card.id,
                    source: card.definitionSourcePrimary,
                    rawText: [meaning, definitionEn].filter { !$0.isEmpty }.joined(separator: "\n"),
                    parsedMeaningKo: meaning,
                    parsedDefinitionEn: definitionEn,
                    partOfSpeech: partOfSpeech,
                    exampleSentence: exampleSentence,
                    confidence: min(max(result.confidence, 0), 1),
                    selectedAsPrimary: true,
                    createdAt: now,
                    originDeviceId: SyncBatchFactory.deviceId(),
                    baseCardVersion: baseCardVersion
                )
            )
            // The server picked this meaning from the captured sentence; the
            // dictionary gloss it set aside stays restorable under its own source.
            if let fallback = result.dictionary,
               fallback.meaningKo.isEmpty == false,
               definitionCandidates.contains(where: { $0.cardId == card.id && $0.source == fallback.sourceType && $0.parsedMeaningKo == fallback.meaningKo }) == false {
                definitionCandidates.append(StoredDefinitionCandidate(
                    id: UUID().uuidString, cardId: card.id, source: fallback.sourceType,
                    rawText: [fallback.meaningKo, fallback.definitionEn].filter { !$0.isEmpty }.joined(separator: "\n"),
                    parsedMeaningKo: fallback.meaningKo, parsedDefinitionEn: fallback.definitionEn,
                    partOfSpeech: fallback.partOfSpeech, exampleSentence: fallback.exampleSentence,
                    confidence: min(max(fallback.confidence, 0), 1), selectedAsPrimary: false,
                    createdAt: now, originDeviceId: SyncBatchFactory.deviceId(), baseCardVersion: baseCardVersion
                ))
            }
            let senseSource = result.dictionary?.sourceType ?? card.definitionSourcePrimary
            for sense in result.senses where !sense.meaningKo.isEmpty || !sense.definitionEn.isEmpty {
                guard !definitionCandidates.contains(where: { $0.cardId == card.id && $0.parsedMeaningKo == sense.meaningKo && $0.parsedDefinitionEn == sense.definitionEn && $0.partOfSpeech == sense.partOfSpeech }) else { continue }
                definitionCandidates.append(StoredDefinitionCandidate(
                    id: UUID().uuidString, cardId: card.id, source: senseSource,
                    rawText: [sense.meaningKo, sense.definitionEn].joined(separator: "\n"),
                    parsedMeaningKo: sense.meaningKo, parsedDefinitionEn: sense.definitionEn,
                    partOfSpeech: sense.partOfSpeech, exampleSentence: sense.exampleSentence,
                    confidence: min(max(result.confidence, 0), 1), selectedAsPrimary: false,
                    createdAt: now, originDeviceId: SyncBatchFactory.deviceId(), baseCardVersion: baseCardVersion
                ))
            }
            appliedCardIds.append(card.id)
        }

        if appliedCardIds.isEmpty == false {
            // save() also rebuilds the notification queue. Blank cards are not
            // eligible for review, so resetting dueAt above makes this their
            // first real notification rather than replaying an old stale date.
            guard save() else { return DictionaryEnrichmentApplySummary(appliedCardIds: []) }
        }
        return DictionaryEnrichmentApplySummary(appliedCardIds: appliedCardIds)
    }

    package func retryDictionaryEnrichment(cardId: String) {
        _ = performMutation {
            guard let index = cards.firstIndex(where: {
                $0.id == cardId &&
                    $0.deletedAt == nil &&
                    $0.meaningKo.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            }) else { return }
            let now = Date()
            var updated = cards[index]
            updated.definitionSourcePrimary = "dictionary_retry"
            updated.updatedAt = now
            updated.version += 1
            update(card: updated)
            MacAutoSyncController.shared.clearDictionaryEnrichmentRetry(cardId: cardId)
            message = "\(updated.displayWord) 다시 해석 요청됨"
            enqueueAutoSync()

        }
    }

    package func setDictionaryEnrichmentStatus(_ status: DictionaryEnrichmentStatus) {
        dictionaryEnrichmentStatus = status
    }

    private static func normalizedDictionaryLemma(_ value: String) -> String {
        normalizeCloudDictionaryLemma(value)
    }

    private func addResolvedCard(
        _ result: DictionaryResult,
        captureMethod: CaptureMethod,
        sourceURL: String? = nil,
        dueAt: Date,
        now: Date,
        wordbook: Wordbook = .user
    ) -> VocabularyCard {
        if let existingIndex = cards.firstIndex(where: { $0.deletedAt == nil && $0.lemma == result.lemma }) {
            var card = cards[existingIndex]
            let baseCardVersion = card.version
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
            appendContext(
                cardId: card.id,
                result: result,
                captureMethod: captureMethod,
                sourceURL: sourceURL,
                now: now
            )
            appendCandidate(
                cardId: card.id,
                result: result,
                selected: shouldUpdatePrimary,
                now: now,
                originDeviceId: shouldUpdatePrimary ? SyncBatchFactory.deviceId() : nil,
                baseCardVersion: shouldUpdatePrimary ? baseCardVersion : nil
            )
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
        appendContext(
            cardId: card.id,
            result: result,
            captureMethod: captureMethod,
            sourceURL: sourceURL,
            now: now
        )
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

    private func appendSyncEvent(succeeded: Bool, summary: String, now: Date) {
        syncEvents.append(
            StoredSyncEvent(
                id: UUID().uuidString,
                succeeded: succeeded,
                summary: summary,
                createdAt: now
            )
        )
        if syncEvents.count > 100 {
            syncEvents = Array(syncEvents.sorted { $0.createdAt < $1.createdAt }.suffix(100))
        }
        save()
    }

    private func appendManualEditEvent(
        previous: VocabularyCard,
        updated: VocabularyCard,
        action: String,
        metadata: [String: String],
        now: Date
    ) {
        append(
            event: ReviewEventDraft(
                cardId: updated.id,
                response: .manualEdit,
                previousStatus: previous.status,
                nextStatus: updated.status,
                previousDueAt: previous.dueAt,
                nextDueAt: updated.dueAt,
                previousIntervalDays: previous.intervalDays,
                nextIntervalDays: updated.intervalDays,
                metadata: metadata.merging(["action": action]) { current, _ in current }
            ),
            now: now
        )
    }

    private func definitionContent(of card: VocabularyCard) -> [String] {
        [
            card.meaningKo.trimmingCharacters(in: .whitespacesAndNewlines),
            card.definitionEn.trimmingCharacters(in: .whitespacesAndNewlines),
            card.partOfSpeech.trimmingCharacters(in: .whitespacesAndNewlines),
            card.exampleSentence.trimmingCharacters(in: .whitespacesAndNewlines)
        ]
    }

    private func appendContext(
        cardId: String,
        result: DictionaryResult,
        captureMethod: CaptureMethod,
        sourceURL: String?,
        now: Date
    ) {
        let sentence = result.sourceSentence.isEmpty ? result.exampleSentence : result.sourceSentence
        if sentence.isEmpty && result.sourceApp.isEmpty && sourceURL == nil { return }
        contexts.append(
            StoredContext(
                id: UUID().uuidString,
                cardId: cardId,
                sourceSentence: sentence,
                sourceURL: SourceURLSanitizer.sanitize(sourceURL),
                sourceApp: result.sourceApp,
                captureMethod: captureMethod,
                createdAt: now
            )
        )
    }

    private func appendCandidate(
        cardId: String,
        result: DictionaryResult,
        selected: Bool,
        now: Date,
        originDeviceId: String? = nil,
        baseCardVersion: Int? = nil
    ) {
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
                createdAt: now,
                originDeviceId: originDeviceId,
                baseCardVersion: baseCardVersion
            )
        )
    }

    private func ensureCardMeaningCandidate(_ card: VocabularyCard, now: Date) {
        guard !card.meaningKo.isEmpty || !card.definitionEn.isEmpty else { return }
        let alreadyPreserved = definitionCandidates.contains {
            $0.cardId == card.id &&
                $0.parsedMeaningKo == card.meaningKo &&
                $0.parsedDefinitionEn == card.definitionEn
        }
        guard !alreadyPreserved else { return }
        appendStoredMeaningCandidate(card, source: card.definitionSourcePrimary, selected: false, now: now)
    }

    private func appendManualMeaningCandidate(
        _ card: VocabularyCard,
        baseCardVersion: Int,
        now: Date
    ) {
        guard !card.meaningKo.isEmpty || !card.definitionEn.isEmpty else { return }
        appendStoredMeaningCandidate(
            card,
            source: "user",
            selected: true,
            now: now,
            originDeviceId: SyncBatchFactory.deviceId(),
            baseCardVersion: baseCardVersion
        )
    }

    private func appendStoredMeaningCandidate(
        _ card: VocabularyCard,
        source: String,
        selected: Bool,
        now: Date,
        originDeviceId: String? = nil,
        baseCardVersion: Int? = nil
    ) {
        definitionCandidates.append(
            StoredDefinitionCandidate(
                id: UUID().uuidString,
                cardId: card.id,
                source: source.isEmpty ? "user" : source,
                rawText: card.meaningKo.isEmpty ? card.definitionEn : card.meaningKo,
                parsedMeaningKo: card.meaningKo,
                parsedDefinitionEn: card.definitionEn,
                partOfSpeech: card.partOfSpeech,
                exampleSentence: card.exampleSentence,
                confidence: 1,
                selectedAsPrimary: selected,
                createdAt: now,
                originDeviceId: originDeviceId,
                baseCardVersion: baseCardVersion
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

    package var recommendationStatusMessage: String {
        let now = Date()
        if !settings.enableWordRecommendations { return "추천이 꺼져 있습니다. 설정에서 추천 단어를 켜세요." }
        let remaining = max(0, settings.dailyWordGoal - processedNotificationCardIdsToday(now: now).count)
        if remaining == 0 { return "오늘 학습 목표를 완료했습니다. 내일 새 추천을 준비합니다." }
        let due = activeCards.filter { ($0.wordbook == .user || $0.recommendationStartedAt != nil) && $0.isDueReviewCandidate(now) }.count
        if due >= min(remaining, settings.maxQueueSize) { return "기존 단어 복습을 먼저 진행합니다. 여유가 생기면 추천합니다." }
        let waiting = activeCards.filter(\.isActiveRecommendationCandidate).count
        if waiting > 0 { return "추천 \(waiting)개가 준비되어 있습니다. 잠자는 시간에는 알림을 쉬어요." }
        return "컴퓨터에서 새 추천을 준비합니다. 동기화하면 받아올 수 있어요."
    }

    private func recommendationWaitingMessage() -> String { recommendationStatusMessage }

    package func maintainRecommendations(now: Date = Date()) {
        let generated = ensureRecommendations(now: now)
        if generated > 0 { enqueueAutoSync() }
        refreshNotifications()
    }

    private func recordCaptureSuccess(method: CaptureMethod, detailCode: String) {
        recordCaptureDiagnostic(
            CaptureDiagnosticRecord(
                outcome: .succeeded,
                mechanism: diagnosticMechanism(method),
                detailCode: detailCode,
                occurredAt: Date()
            )
        )
    }

    private func recordCaptureDiagnostic(_ record: CaptureDiagnosticRecord) {
        lastCaptureDiagnostic = record
        if mutationDepth == 0 { CaptureDiagnosticStore.save(record) }
    }

    private func diagnosticMechanism(_ method: CaptureMethod) -> String {
        switch method {
        case .macClipboardHotkey:
            return "clipboard_hotkey"
        case .macService:
            return pendingCaptureSourceApp == MacCaptureSource.urlScheme ? "url_scheme" : "mac_service"
        case .manual:
            return "manual"
        case .recommendation:
            return "recommendation"
        case .androidProcessText:
            return "android_process_text"
        case .androidShare:
            return "android_share"
        }
    }

    private func load() {
        guard let database else {
            message = storageError ?? "저장소를 열지 못했습니다"
            return
        }
        do {
            let isEmpty = try database.strings("SELECT id FROM cards LIMIT 1").isEmpty &&
                (try database.value(forKey: "settings")) == nil
            if isEmpty, FileManager.default.fileExists(atPath: legacyStoreURL.path) {
                try importLegacyStore()
            } else {
                apply(try readDatabase())
                committedSnapshot = snapshot()
            }
            refreshNotifications()
        } catch {
            storageError = "저장소를 읽지 못했습니다: \(error.localizedDescription)"
            message = storageError!
        }
    }

    /// One-time move from the pre-SQLite vocab-data.json. Everything is queued
    /// for upload and the cursor dropped, so the next sync is a full exchange.
    private func importLegacyStore() throws {
        let legacy = try JSONDecoder.vocab.decode(StoreSnapshot.self, from: Data(contentsOf: legacyStoreURL))
        apply(StoreSnapshot(
            cards: legacy.cards,
            contexts: legacy.contexts,
            definitionCandidates: legacy.definitionCandidates,
            reviewEvents: legacy.reviewEvents,
            syncEvents: legacy.syncEvents,
            recommendationFeedback: legacy.recommendationFeedback,
            settings: legacy.settings
        ))
        committedSnapshot = nil
        guard save() else { throw StoreWriteError.failed(storageError ?? "저장 실패") }
        try queueEverythingForUpload()
        let migrated = legacyStoreURL.appendingPathExtension("migrated")
        try? FileManager.default.removeItem(at: migrated)
        try FileManager.default.moveItem(at: legacyStoreURL, to: migrated)
    }

    private func readDatabase() throws -> StoreSnapshot {
        guard let database else { throw StoreWriteError.failed(storageError ?? "저장소 없음") }
        let settings = try database.value(forKey: "settings")
            .flatMap { try JSONDecoder.vocab.decode(UserSettingsData.self, from: Data($0.utf8)) } ?? UserSettingsData()
        let syncEvents = try database.value(forKey: "sync_events")
            .flatMap { try JSONDecoder.vocab.decode([StoredSyncEvent].self, from: Data($0.utf8)) } ?? []
        return StoreSnapshot(
            cards: try database.bodies("cards").map(SyncBatchFactory.cardPayload),
            contexts: try database.bodies("contexts").map(SyncBatchFactory.contextPayload),
            definitionCandidates: try database.bodies("definition_candidates").map(SyncBatchFactory.candidatePayload),
            reviewEvents: try database.bodies("review_events").map(SyncBatchFactory.reviewEventPayload)
                .sorted { $0.clientCreatedAt < $1.clientCreatedAt },
            syncEvents: syncEvents,
            recommendationFeedback: try database.bodies("recommendation_feedback").map(SyncBatchFactory.feedbackPayload),
            settings: settings,
            syncCursor: try database.value(forKey: "sync_cursor"),
            syncCursorEndpoint: try database.value(forKey: "sync_cursor_endpoint")
        )
    }

    private func snapshot() -> StoreSnapshot {
        let candidatesByCard = Dictionary(grouping: definitionCandidates, by: \.cardId)
        cards = cards.map { card in
            var updated = card
            updated.primaryDefinitionCandidateId = DefinitionSelection.primaryID(card: card, candidates: candidatesByCard[card.id, default: []])
            return updated
        }
        return StoreSnapshot(
            cards: cards,
            contexts: contexts,
            definitionCandidates: definitionCandidates,
            reviewEvents: reviewEvents,
            syncEvents: syncEvents,
            recommendationFeedback: recommendationFeedback,
            settings: settings,
            syncCursor: syncCursor,
            syncCursorEndpoint: syncCursorEndpoint
        )
    }

    /// Writes only the rows that differ from the last committed state, in one
    /// transaction. Local edits stamp card field-group clocks and join the
    /// upload outbox; rows applied from the server do neither.
    @discardableResult
    private func save() -> Bool {
        if mutationDepth > 0 {
            pendingSave = true
            return true
        }
        guard let database else {
            storageError = storageError ?? "저장소를 열지 못했습니다"
            message = storageError!
            return false
        }
        let previous = committedSnapshot
        var state = snapshot()
        let previousCards = Dictionary(uniqueKeysWithValues: (previous?.cards ?? []).map { ($0.id, $0) })
        if !applyingRemote {
            state.cards = state.cards.map { card in
                guard let old = previousCards[card.id] else { return CardMerge.pinned(card) }
                return old == card ? card : CardMerge.stamp(previous: old, next: card)
            }
        }
        let deviceId = SyncBatchFactory.deviceId()
        do {
            try database.transaction {
                for card in changed(state.cards, from: previous?.cards) {
                    try database.run(
                        "INSERT OR REPLACE INTO cards (id, lemma, deleted_at, created_at, body) VALUES (?, ?, ?, ?, ?)",
                        [card.id, card.lemma.trimmingCharacters(in: .whitespaces).lowercased(),
                         card.deletedAt.map(SyncBatchFactory.iso), SyncBatchFactory.iso(card.createdAt),
                         try json(SyncBatchFactory.card(card, deviceId: deviceId))]
                    )
                    if !applyingRemote || remoteRowsToUpload.contains(card.id) { try database.markOutbox("cards", id: card.id) }
                }
                for context in changed(state.contexts, from: previous?.contexts) {
                    try database.run(
                        "INSERT OR REPLACE INTO contexts (id, card_id, created_at, body) VALUES (?, ?, ?, ?)",
                        [context.id, context.cardId, SyncBatchFactory.iso(context.createdAt),
                         try json(SyncBatchFactory.context(context, deviceId: deviceId))]
                    )
                    if !applyingRemote { try database.markOutbox("contexts", id: context.id) }
                }
                for candidate in changed(state.definitionCandidates, from: previous?.definitionCandidates) {
                    try database.run(
                        "INSERT OR REPLACE INTO definition_candidates (id, card_id, created_at, body) VALUES (?, ?, ?, ?)",
                        [candidate.id, candidate.cardId, SyncBatchFactory.iso(candidate.createdAt),
                         try json(SyncBatchFactory.candidate(candidate))]
                    )
                    if !applyingRemote { try database.markOutbox("definition_candidates", id: candidate.id) }
                }
                for event in changed(state.reviewEvents, from: previous?.reviewEvents) {
                    try database.run(
                        "INSERT OR REPLACE INTO review_events (id, card_id, response, client_created_at, body) VALUES (?, ?, ?, ?, ?)",
                        [event.id, event.cardId, event.response.rawValue, SyncBatchFactory.iso(event.clientCreatedAt),
                         try json(SyncBatchFactory.reviewEvent(event, deviceId: deviceId))]
                    )
                    if !applyingRemote { try database.markOutbox("review_events", id: event.id) }
                }
                for feedback in changed(state.recommendationFeedback, from: previous?.recommendationFeedback) {
                    try database.run(
                        "INSERT OR REPLACE INTO recommendation_feedback (id, card_id, lemma, action, created_at, body) VALUES (?, ?, ?, ?, ?, ?)",
                        [feedback.id, feedback.cardId, feedback.lemma, feedback.action, SyncBatchFactory.iso(feedback.createdAt),
                         try json(SyncBatchFactory.feedback(feedback))]
                    )
                    if !applyingRemote { try database.markOutbox("recommendation_feedback", id: feedback.id) }
                }
                if previous?.settings != state.settings {
                    try database.setValue(String(decoding: try JSONEncoder.vocab.encode(state.settings), as: UTF8.self), forKey: "settings")
                    if !applyingRemote { try database.markOutbox("settings", id: "main") }
                }
                if previous?.syncEvents != state.syncEvents {
                    try database.setValue(String(decoding: try JSONEncoder.vocab.encode(state.syncEvents), as: UTF8.self), forKey: "sync_events")
                }
                try database.setValue(state.syncCursor, forKey: "sync_cursor")
                try database.setValue(state.syncCursorEndpoint, forKey: "sync_cursor_endpoint")
                try beforeCommit()
            }
            cards = state.cards
            committedSnapshot = state
            storageError = nil
            refreshNotifications()
            return true
        } catch {
            if let previous { apply(previous) }
            storageError = "저장 실패: \(error.localizedDescription)"
            message = storageError!
            return false
        }
    }

    private func changed<T: Identifiable & Equatable>(_ current: [T], from previous: [T]?) -> [T] where T.ID == String {
        let old = Dictionary((previous ?? []).map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        return current.filter { old[$0.id] != $0 }
    }

    private func json(_ object: [String: Any]) throws -> String {
        String(decoding: try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]), as: UTF8.self)
    }

    // MARK: Upload outbox

    package struct PendingUpload: Sendable {
        package var payloadData: Data
        package var digests: [String: String]

        /// A fresh dictionary per send, so nothing shared crosses into the client.
        package func payload() throws -> [String: Any] {
            try JSONSerialization.jsonObject(with: payloadData) as? [String: Any] ?? [:]
        }
    }

    /// Rows changed on this device since the server last acknowledged them.
    package func pendingUpload() throws -> PendingUpload {
        guard let database else { throw StoreWriteError.failed(storageError ?? "저장소 없음") }
        var payload: [String: Any] = [:]
        var digests: [String: String] = [:]
        for table in VocabularyDatabase.entityTables {
            let rows = try database.rows(
                "SELECT t.id, t.body FROM outbox o JOIN \(table) t ON t.id = o.id WHERE o.collection = ?", [table]
            )
            payload[table] = try rows.compactMap { row -> [String: Any]? in
                guard let id = row[0], let body = row[1] else { return nil }
                digests["\(table):\(id)"] = Self.digest(body)
                return try JSONSerialization.jsonObject(with: Data(body.utf8)) as? [String: Any]
            }
        }
        if try !database.strings("SELECT id FROM outbox WHERE collection = 'settings'").isEmpty,
           let raw = try database.value(forKey: "settings") {
            payload["settings"] = SyncBatchFactory.settingsPayload(settings)
            digests["settings:main"] = Self.digest(raw)
        }
        return PendingUpload(payloadData: try JSONSerialization.data(withJSONObject: payload), digests: digests)
    }

    /// Drops acknowledged rows from the outbox unless they changed again meanwhile.
    package func acknowledgeUpload(_ digests: [String: String]) throws {
        guard let database else { return }
        try database.transaction {
            for (key, expected) in digests {
                let parts = key.split(separator: ":", maxSplits: 1).map(String.init)
                let current = parts[0] == "settings"
                    ? try database.value(forKey: "settings")
                    : try database.strings("SELECT body FROM \(parts[0]) WHERE id = ?", [parts[1]]).first
                if current.map(Self.digest) == expected {
                    try database.run("DELETE FROM outbox WHERE collection = ? AND id = ?", [parts[0], parts[1]])
                }
            }
        }
    }

    package func queueEverythingForUpload() throws {
        guard let database else { return }
        try database.transaction {
            for table in VocabularyDatabase.entityTables {
                try database.execute("INSERT OR IGNORE INTO outbox (collection, id) SELECT '\(table)', id FROM \(table)")
            }
            if try database.value(forKey: "settings") != nil { try database.markOutbox("settings", id: "main") }
        }
    }

    private static func digest(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// Delay persistence and side effects until the entire user operation succeeds.
    private func performMutation<T>(_ body: () -> T) -> T? {
        if mutationDepth > 0 { return body() }
        let oldPreview = preview
        let oldAnalysis = pendingAnalysis
        let oldMethod = pendingCaptureMethod
        let oldURL = pendingCaptureSourceURL
        let oldSource = pendingCaptureSourceApp
        let oldDiagnostic = lastCaptureDiagnostic
        mutationDepth = 1
        pendingSave = false
        pendingSync = false
        let result = body()
        mutationDepth = 0
        guard !pendingSave || save() else {
            preview = oldPreview
            pendingAnalysis = oldAnalysis
            pendingCaptureMethod = oldMethod
            pendingCaptureSourceURL = oldURL
            pendingCaptureSourceApp = oldSource
            lastCaptureDiagnostic = oldDiagnostic
            pendingSync = false
            return nil
        }
        if lastCaptureDiagnostic != oldDiagnostic, let diagnostic = lastCaptureDiagnostic {
            CaptureDiagnosticStore.save(diagnostic)
        }
        if pendingSync { enqueueAutoSync() }
        return result
    }

    private func requireSaved() throws {
        guard save() else { throw StoreWriteError.failed(storageError ?? "저장 실패") }
    }

    private enum StoreWriteError: LocalizedError {
        case failed(String)
        var errorDescription: String? {
            switch self { case .failed(let message): return message }
        }
    }

    private func apply(_ snapshot: StoreSnapshot) {
        syncCursor = snapshot.syncCursor
        syncCursorEndpoint = snapshot.syncCursorEndpoint
        cards = snapshot.cards
        contexts = snapshot.contexts
        definitionCandidates = snapshot.definitionCandidates
        reviewEvents = snapshot.reviewEvents
        syncEvents = snapshot.syncEvents
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

    private func enqueueAutoSync() {
        if mutationDepth > 0 { pendingSync = true; return }
        guard storageError == nil else { return }
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

package struct CloudSyncSummary {
    package init(
        cards: Int,
        reviewEvents: Int,
        skippedSummary: String = ""
    ) {
        self.cards = cards
        self.reviewEvents = reviewEvents
        self.skippedSummary = skippedSummary
    }

    package var cards: Int
    package var reviewEvents: Int
    package var skippedSummary: String = ""
}

package struct DictionaryEnrichmentTarget: Hashable, Sendable {
    package init(
        cardId: String,
        lemma: String,
        sentence: String = "",
        contextKey: String = ""
    ) {
        self.cardId = cardId
        self.lemma = lemma
        self.sentence = sentence
        self.contextKey = contextKey
    }

    package var cardId: String
    package var lemma: String
    /// The captured sentence sent with the lemma, so the server can pick the meaning it uses.
    package var sentence: String
    package var contextKey: String
}

package struct DictionaryEnrichmentApplySummary: Equatable, Sendable {
    package init(
        appliedCardIds: [String]
    ) {
        self.appliedCardIds = appliedCardIds
    }

    package var appliedCardIds: [String]

    package var appliedCount: Int { appliedCardIds.count }
}

package struct DictionaryEnrichmentStatus: Equatable, Sendable {
    package init(
        pendingCount: Int = 0,
        resolvedCount: Int = 0,
        notFoundCount: Int = 0,
        providerFailureCount: Int = 0,
        endpointUnavailable: Bool = false,
        nextRetryAt: Date? = nil
    ) {
        self.pendingCount = pendingCount
        self.resolvedCount = resolvedCount
        self.notFoundCount = notFoundCount
        self.providerFailureCount = providerFailureCount
        self.endpointUnavailable = endpointUnavailable
        self.nextRetryAt = nextRetryAt
    }

    package var pendingCount: Int = 0
    package var resolvedCount: Int = 0
    package var notFoundCount: Int = 0
    package var providerFailureCount: Int = 0
    package var endpointUnavailable = false
    package var nextRetryAt: Date?

    package static let idle = DictionaryEnrichmentStatus()

    package var userFacingSummary: String {
        if endpointUnavailable {
            return "서버 사전 기능을 사용할 수 없어 기존 동기화만 유지합니다."
        }
        var parts: [String] = []
        if pendingCount > 0 { parts.append("대기 \(pendingCount)") }
        if resolvedCount > 0 { parts.append("완료 \(resolvedCount)") }
        if notFoundCount > 0 { parts.append("미등록 \(notFoundCount)") }
        if providerFailureCount > 0 { parts.append("제공자 오류 \(providerFailureCount)") }
        if let nextRetryAt, pendingCount + notFoundCount + providerFailureCount > 0 {
            parts.append("재시도 \(nextRetryAt.formatted(date: .omitted, time: .shortened))")
        }
        guard parts.isEmpty == false else { return "" }
        return "사전 보강 · " + parts.joined(separator: " · ")
    }
}

package struct StoreSnapshot: Codable {
    package var cards: [VocabularyCard]
    package var contexts: [StoredContext]
    package var definitionCandidates: [StoredDefinitionCandidate]
    package var reviewEvents: [StoredReviewEvent]
    package var syncEvents: [StoredSyncEvent]
    package var recommendationFeedback: [RecommendationFeedback]
    package var settings: UserSettingsData
    package var syncCursor: String?
    package var syncCursorEndpoint: String?

    package init(
        cards: [VocabularyCard],
        contexts: [StoredContext],
        definitionCandidates: [StoredDefinitionCandidate],
        reviewEvents: [StoredReviewEvent],
        syncEvents: [StoredSyncEvent] = [],
        recommendationFeedback: [RecommendationFeedback] = [],
        settings: UserSettingsData,
        syncCursor: String? = nil,
        syncCursorEndpoint: String? = nil
    ) {
        self.cards = cards
        self.contexts = contexts
        self.definitionCandidates = definitionCandidates
        self.reviewEvents = reviewEvents
        self.syncEvents = syncEvents
        self.recommendationFeedback = recommendationFeedback
        self.settings = settings
        self.syncCursor = syncCursor
        self.syncCursorEndpoint = syncCursorEndpoint
    }

    private enum CodingKeys: String, CodingKey {
        case cards
        case contexts
        case definitionCandidates
        case reviewEvents
        case syncEvents
        case recommendationFeedback
        case settings
        case syncCursor
        case syncCursorEndpoint
    }

    package init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        cards = try container.decode([VocabularyCard].self, forKey: .cards)
        contexts = try container.decode([StoredContext].self, forKey: .contexts)
        definitionCandidates = try container.decode([StoredDefinitionCandidate].self, forKey: .definitionCandidates)
        reviewEvents = try container.decode([StoredReviewEvent].self, forKey: .reviewEvents)
        syncEvents = try container.decodeIfPresent([StoredSyncEvent].self, forKey: .syncEvents) ?? []
        recommendationFeedback = try container.decodeIfPresent([RecommendationFeedback].self, forKey: .recommendationFeedback) ?? []
        settings = try container.decode(UserSettingsData.self, forKey: .settings)
        syncCursor = try container.decodeIfPresent(String.self, forKey: .syncCursor)
        syncCursorEndpoint = try container.decodeIfPresent(String.self, forKey: .syncCursorEndpoint)
    }
}

private extension VocabularyCard {
    var schedulerCard: SchedulerCard {
        SchedulerCard(id: id, status: status, intervalDays: intervalDays, dueAt: dueAt, lapseCount: lapseCount, intervalBeforeLapse: intervalBeforeLapse, meaningKo: meaningKo, wordbook: wordbook, deletedAt: deletedAt, recommendationStartedAt: recommendationStartedAt)
    }

    func isDueReviewCandidate(_ now: Date) -> Bool {
        !meaningKo.isEmpty &&
            status != .mastered &&
            status != .suspended &&
            dueAt <= now
    }

    var isActiveRecommendationCandidate: Bool {
        wordbook == .recommended &&
            recommendationStartedAt == nil &&
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

extension JSONEncoder {
    package static var vocab: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }
}

extension JSONDecoder {
    package static var vocab: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }
}
