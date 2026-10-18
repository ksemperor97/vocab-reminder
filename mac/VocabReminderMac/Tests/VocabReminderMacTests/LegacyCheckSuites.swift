import Foundation
@preconcurrency import UserNotifications
import Testing
@testable import VocabCore

enum LegacyCheckSuites {

    @MainActor
    static func assertMacDeviceIdPersistence() throws {
        let suiteName = "VocabReminderMacDeviceIdTest-\(UUID().uuidString)"
        guard let defaults = UserDefaults(suiteName: suiteName) else {
            throw CheckError("Could not create device id defaults suite")
        }
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let first = SyncBatchFactory.deviceId(defaults: defaults)
        let second = SyncBatchFactory.deviceId(defaults: defaults)

        try require(first == second, "Mac device id should persist across reads")
        try require(first.range(of: #"^mac-[0-9a-fA-F]{8}$"#, options: .regularExpression) != nil, "Mac device id should use mac-XXXXXXXX format")
    }

    static func assertSettingsTimestampMerge() throws {
        let local = StoreSnapshot(
            cards: [],
            contexts: [],
            definitionCandidates: [],
            reviewEvents: [],
            recommendationFeedback: [],
            settings: UserSettingsData(dailyWordGoal: 5, updatedAt: Date(timeIntervalSince1970: 200))
        )
        let olderIncoming = StoreSnapshot(
            cards: [],
            contexts: [],
            definitionCandidates: [],
            reviewEvents: [],
            recommendationFeedback: [],
            settings: UserSettingsData(dailyWordGoal: 9, updatedAt: Date(timeIntervalSince1970: 100))
        )
        let newerIncoming = StoreSnapshot(
            cards: [],
            contexts: [],
            definitionCandidates: [],
            reviewEvents: [],
            recommendationFeedback: [],
            settings: UserSettingsData(dailyWordGoal: 12, updatedAt: Date(timeIntervalSince1970: 300))
        )

        try require(SyncBatchFactory.merge(local: local, incoming: olderIncoming).settings.dailyWordGoal == 5, "Mac sync should keep newer local settings")
        try require(SyncBatchFactory.merge(local: local, incoming: newerIncoming).settings.dailyWordGoal == 12, "Mac sync should apply newer incoming settings")
    }

    static func assertCloudBatchChunking() throws {
        let payload: [String: Any] = [
            "cards": (0..<401).map { ["id": "card-\($0)"] },
            "contexts": [],
            "definition_candidates": [],
            "review_events": [],
            "recommendation_feedback": [],
            "settings": ["daily_word_goal": 9]
        ]
        let batches = CloudSyncClient.splitIntoBatches(payload)
        try require(batches.count == 2, "401 Mac sync items should split into two batches")
        try require((batches[0]["cards"] as? [Any])?.count == 400, "First Mac sync batch should contain 400 cards")
        try require((batches[1]["cards"] as? [Any])?.count == 1, "Second Mac sync batch should contain one card")
        try require(batches[0]["settings"] is NSNull, "Settings must not be sent before all entity chunks succeed")
        try require((batches[1]["settings"] as? [String: Any])?["daily_word_goal"] as? Int == 9, "Settings should be sent only in the final chunk")
    }

    @MainActor
    static func assertLocalRecommendedCapturePromotion() throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appending(path: "VocabReminderSelfCheck-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let recommendedPayload: [String: Any] = [
            "cards": [
                [
                    "id": "recommended-card",
                    "lemma": "ubiquitous",
                    "display_word": "ubiquitous",
                    "meaning_ko": "어디에나 있는, 아주 흔한",
                    "definition_en": "present or found everywhere",
                    "wordbook": "recommended",
                    "status": "learning",
                    "due_at": "1970-01-04T00:00:00Z",
                    "interval_days": 3,
                    "lapse_count": 1,
                    "created_at": "1970-01-01T00:00:00Z",
                    "updated_at": "1970-01-01T00:00:00Z",
                    "version": 2
                ]
            ],
            "contexts": [],
            "definition_candidates": [],
            "review_events": [],
            "recommendation_feedback": [],
            "settings": ["daily_word_goal": 10, "enable_word_recommendations": false]
        ]
        let syncURL = tempDir.appending(path: "vocab-reminder-sync.json")
        try JSONSerialization.data(withJSONObject: recommendedPayload).write(to: syncURL)

        let store = LocalVocabularyStore(appSupportURL: tempDir, scheduleNotifications: false)
        store.importSyncSnapshot()
        try require(store.activeCards.count == 1, "Mac local promotion setup should import one recommended card")

        store.capture(text: "Ubiquitous", method: .macClipboardHotkey, sourceApp: "Self-check")
        guard let preview = store.preview else {
            throw CheckError("Mac local promotion should create a capture preview")
        }
        store.savePreview(
            meaningOverride: preview.meaningKo,
            exampleOverride: preview.sourceSentence.isEmpty ? preview.exampleSentence : preview.sourceSentence
        )

        let cards = store.activeCards
        try require(cards.count == 1, "Mac local capture should not duplicate recommended lemma")
        let promoted = cards[0]
        try require(promoted.wordbook == .user, "Mac local capture should promote recommended word to user wordbook")
        try require(promoted.status == .learning, "Mac local capture should preserve recommended progress status")
        try require(promoted.intervalDays == 3, "Mac local capture should preserve recommended interval")
        try require(promoted.lapseCount == 1, "Mac local capture should preserve recommended lapse count")
        try require(store.contexts.filter { $0.cardId == "recommended-card" }.count == 1, "Mac local capture should attach a real context to promoted recommendation")
        store.softDeleteCard(promoted)
        try require(store.activeCards.isEmpty, "Mac delete should remove card from active list")
        try require(store.cards.first?.deletedAt != nil, "Mac delete should mark deletedAt")
    }

    @MainActor
    static func assertDictionaryExtraction() throws {
        let koreanDefinition = """
        resilient
        /rɪˈzɪljənt/ adjective
        1. 회복력 있는, 탄력 있는
        2. 곧 기운을 차리는
        """
        try require(DictionaryResolver.extractKoreanMeaning(from: koreanDefinition) == "회복력 있는, 탄력 있는", "Mac dictionary should extract the first Korean meaning")

        let analysis = CaptureNormalizer.analyze("resilient")
        let result = DictionaryResolver.resolve(analysis, sourceApp: "Self-check") { _ in koreanDefinition }
        try require(result.meaningKo == "회복력 있는, 탄력 있는", "Mac dictionary resolver should use parsed Korean meaning")

        let newAceDefinition = "re·sil·ient | rizíljənt┃-liənt | 형용사1.〈물체가〉 되튀는; 탄력이 있는(buoyant) ▸ Rubber is more resilient than wood.고무는 나무보다 탄력이 크다. 2.〈사람이〉 곧 기운을 차리는"
        try require(DictionaryResolver.extractKoreanMeaning(from: newAceDefinition) == "되튀는", "Mac New Ace dictionary should extract Korean meaning without POS prefix")
        let newAceResult = DictionaryResolver.resolve(analysis, sourceApp: "Self-check") { _ in newAceDefinition }
        try require(newAceResult.meaningKo == "되튀는", "Mac dictionary resolver should prefer New Ace Korean meaning")

        let englishAnalysis = CaptureNormalizer.analyze("pervasive")
        let englishOnly = "pervasive per·va·sive | adjective spreading widely throughout an area or a group of people: ageism is pervasive and entrenched in our society"
        let englishResult = DictionaryResolver.resolve(englishAnalysis, sourceApp: "Self-check") { _ in englishOnly }
        try require(englishResult.meaningKo.isEmpty, "English-only Mac dictionary result should not invent Korean meaning")
        try require(englishResult.definitionEn == "spreading widely throughout an area or a group of people", "English-only Mac dictionary result should parse definitionEn")
        try require(englishResult.exampleSentence == "ageism is pervasive and entrenched in our society", "English-only Mac dictionary result should parse first example")
        try require(englishResult.partOfSpeech == "adjective", "English-only Mac dictionary result should parse part of speech")
    }

    static func assertMacSettingsFallback() throws {
        let payload: [String: Any] = [
            "cards": [],
            "contexts": [],
            "definition_candidates": [],
            "review_events": [],
            "recommendation_feedback": [],
            "settings": [
                "daily_word_goal": 12,
                "quiet_hours_start": "25:00",
                "quiet_hours_end": "99:99",
                "timezone": "Not/AZone",
                "settings_updated_at": "2026-07-03T05:00:00Z"
            ]
        ]
        let snapshot = try SyncBatchFactory.localSnapshot(from: JSONSerialization.data(withJSONObject: payload))
        try require(snapshot.settings.quietHoursStart == "23:00", "Mac settings import should default invalid quiet start")
        try require(snapshot.settings.quietHoursEnd == "09:00", "Mac settings import should default invalid quiet end")
        try require(snapshot.settings.timezone == "Asia/Seoul", "Mac settings import should default invalid timezone")
    }

    @MainActor
    static func assertMacNotificationReviewAction(now: Date) throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appending(path: "VocabReminderSelfCheck-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let reviewPayload: [String: Any] = [
            "cards": [
                [
                    "id": "review-card",
                    "lemma": "review-card",
                    "display_word": "review-card",
                    "meaning_ko": "meaning",
                    "definition_en": "",
                    "part_of_speech": "",
                    "source_sentence_primary": "A review-card example.",
                    "definition_source_primary": "test",
                    "wordbook": "user",
                    "status": "new",
                    "due_at": "1970-01-01T00:00:00Z",
                    "interval_days": 0,
                    "lapse_count": 0,
                    "created_at": "1970-01-01T00:00:00Z",
                    "updated_at": "1970-01-01T00:00:00Z",
                    "version": 1
                ]
            ],
            "contexts": [],
            "definition_candidates": [],
            "review_events": [],
            "recommendation_feedback": [],
            "settings": [
                "daily_word_goal": 10,
                "enable_word_recommendations": false
            ]
        ]
        let syncURL = tempDir.appending(path: "vocab-reminder-sync.json")
        try JSONSerialization.data(withJSONObject: reviewPayload).write(to: syncURL)

        let store = LocalVocabularyStore(appSupportURL: tempDir, scheduleNotifications: false)
        store.importSyncSnapshot()
        try require(store.activeCards.count == 1, "Mac notification review setup should import one user card")

        store.updateCardDetails(
            cardId: "review-card",
            displayWord: "edited-card",
            lemma: "Edited",
            meaningKo: "edited meaning",
            definitionEn: "edited definition",
            partOfSpeech: "noun",
            exampleSentence: "Edited example."
        )
        guard let editedCard = store.activeCards.first(where: { $0.id == "review-card" }) else {
            throw CheckError("Mac card edit should keep the edited card active")
        }
        try require(editedCard.displayWord == "edited-card", "Mac card edit should update display word")
        try require(editedCard.lemma == "edited", "Mac card edit should normalize lemma")
        try require(editedCard.meaningKo == "edited meaning", "Mac card edit should update meaning")
        try require(editedCard.definitionSourcePrimary == "user", "Mac card edit should mark edited meaning as user source")
        try require(editedCard.exampleSentence == "Edited example.", "Mac card edit should update example sentence")
        let eventCountBeforeReview = store.reviewEvents.count
        try require(
            store.reviewEvents.last?.response == .manualEdit,
            "Mac card edit should append a manual-edit biography event"
        )

        guard let updatedCard = MacNotificationScheduler.handleAction(MacNotificationAction.forgotten, cardId: "review-card", store: store) else {
            throw CheckError("Mac notification forgotten action should return the updated card")
        }
        try require(
            store.reviewEvents.count == eventCountBeforeReview + 1,
            "Mac notification forgotten action should append one review event"
        )
        try require(store.reviewEvents.last?.response == .forgotten, "Mac notification forgotten action should map to forgotten response")
        try require(store.message.hasPrefix("복습 기록됨"), "Mac notification review action should use normal review feedback")
        let feedback = MacNotificationScheduler.feedbackContent(for: MacNotificationAction.forgotten, card: updatedCard)
        try require(feedback?.body.contains("뜻: edited meaning") == true, "Mac notification forgotten action should show the meaning")
        try require(feedback?.body.contains("예문: Edited example.") == true, "Mac notification forgotten action should show the example sentence")
        try require(MacNotificationScheduler.producesFeedback(action: MacNotificationAction.forgotten), "Mac notification forgotten action should hold the queue for feedback")
        try require(MacNotificationScheduler.producesFeedback(action: MacNotificationAction.remembered), "Mac notification remembered action should hold the queue for feedback")
        try require(!MacNotificationScheduler.producesFeedback(action: MacNotificationAction.unavailable), "Mac notification unavailable action should not hold the queue for feedback")
        try require(
            MacNotificationScheduler.shouldAcknowledgeFeedback(
                action: MacNotificationAction.feedbackAcknowledge,
                category: MacNotificationAction.feedbackCategory
            ),
            "Mac notification feedback acknowledge action should release the queue"
        )
        try require(
            MacNotificationScheduler.shouldAcknowledgeFeedback(
                action: UNNotificationDismissActionIdentifier,
                category: MacNotificationAction.feedbackCategory
            ),
            "Mac notification feedback dismissal should release the queue"
        )
        try require(
            !MacNotificationScheduler.shouldAcknowledgeFeedback(
                action: MacNotificationAction.feedbackAcknowledge,
                category: MacNotificationAction.reviewCategory
            ),
            "Mac notification acknowledge action should only apply to feedback notifications"
        )

        _ = store.review(cardId: "review-card", response: .unavailable)
        _ = store.review(cardId: "review-card", response: .unavailable)
        guard let lastReviewAt = store.reviewEvents.last?.clientCreatedAt else {
            throw CheckError("Mac unavailable action should record a review event")
        }
        try require(
            store.processedNotificationCardIdsToday(now: lastReviewAt).count == 1,
            "Mac daily budget should count repeated responses for one card only once"
        )

        let settings = SchedulerSettings()
        let schedulerCard = SchedulerCard(
            id: updatedCard.id,
            status: updatedCard.status,
            intervalDays: updatedCard.intervalDays,
            dueAt: updatedCard.dueAt,
            lapseCount: updatedCard.lapseCount,
            intervalBeforeLapse: updatedCard.intervalBeforeLapse,
            meaningKo: updatedCard.meaningKo,
            wordbook: updatedCard.wordbook,
            deletedAt: updatedCard.deletedAt
        )
        let firstUnavailable = Scheduler.applyReview(
            card: schedulerCard,
            response: .unavailable,
            settings: settings,
            now: now,
            unavailableCountToday: 0
        )
        let secondUnavailable = Scheduler.applyReview(
            card: firstUnavailable.card,
            response: .unavailable,
            settings: settings,
            now: now,
            unavailableCountToday: 1
        )
        let expectedFirstDue = Scheduler.avoidQuietHours(
            now.addingTimeInterval(TimeInterval(settings.snoozeDurationMinutes * 60)),
            settings: settings
        )
        let expectedSecondDue = Scheduler.avoidQuietHours(
            now.addingTimeInterval(24 * 60 * 60),
            settings: settings
        )
        try require(
            firstUnavailable.card.dueAt == expectedFirstDue,
            "First Mac unavailable should use the short snooze before quiet-hours adjustment"
        )
        try require(
            secondUnavailable.card.dueAt == expectedSecondDue,
            "Second Mac unavailable today should move to tomorrow before quiet-hours adjustment"
        )
    }

    @MainActor
    static func assertMacNotificationStateMachine(now: Date) throws {
        let suiteName = "VocabReminderMacNotificationState-\(UUID().uuidString)"
        guard let defaults = UserDefaults(suiteName: suiteName) else {
            throw CheckError("Could not create notification state defaults suite")
        }
        defer { defaults.removePersistentDomain(forName: suiteName) }

        let hold = FeedbackHoldState(
            phase: "feedback",
            action: MacNotificationAction.forgotten,
            cardId: "held-card",
            identifier: MacNotificationScheduler.notificationIdentifier(cardId: "held-card")
        )
        MacNotificationScheduler.saveFeedbackHold(hold, defaults: defaults)
        try require(MacNotificationScheduler.loadFeedbackHold(defaults: defaults) == hold, "Mac feedback hold should survive app restart")
        MacNotificationScheduler.saveFeedbackHold(nil, defaults: defaults)
        try require(MacNotificationScheduler.loadFeedbackHold(defaults: defaults) == nil, "Mac feedback acknowledge should clear persisted hold")

        let settings = UserSettingsData(
            quietHoursStart: "23:00",
            quietHoursEnd: "09:00",
            timezone: "Asia/Seoul"
        )
        let firstPrompt = parseDate("2026-07-11T00:30:00Z") // 09:30 KST
        var reminderState = PromptReminderState(cardId: "rolling-card", firstPromptAt: firstPrompt)
        let firstReminder = MacNotificationScheduler.nextUnansweredReminderDate(
            state: reminderState,
            settings: settings,
            now: firstPrompt
        )
        try require(abs(firstReminder.timeIntervalSince(firstPrompt) - 4 * 60 * 60) < 1, "First unanswered reminder should wait four hours")
        reminderState = MacNotificationScheduler.recordingUnansweredReminder(in: reminderState, at: firstReminder, settings: settings)
        let secondReminder = MacNotificationScheduler.nextUnansweredReminderDate(state: reminderState, settings: settings, now: firstReminder)
        try require(abs(secondReminder.timeIntervalSince(firstReminder) - 4 * 60 * 60) < 1, "Second unanswered reminder should wait another four hours")
        reminderState = MacNotificationScheduler.recordingUnansweredReminder(in: reminderState, at: secondReminder, settings: settings)
        let nextDayReminder = MacNotificationScheduler.nextUnansweredReminderDate(state: reminderState, settings: settings, now: secondReminder)
        var seoulCalendar = Calendar(identifier: .gregorian)
        seoulCalendar.timeZone = TimeZone(identifier: "Asia/Seoul")!
        try require(!seoulCalendar.isDate(nextDayReminder, inSameDayAs: secondReminder), "A third same-day reminder must roll to the next day")
        reminderState = MacNotificationScheduler.recordingUnansweredReminder(in: reminderState, at: nextDayReminder, settings: settings)
        let nextDaySecond = MacNotificationScheduler.nextUnansweredReminderDate(state: reminderState, settings: settings, now: nextDayReminder)
        try require(
            seoulCalendar.isDate(nextDaySecond, inSameDayAs: nextDayReminder) && abs(nextDaySecond.timeIntervalSince(nextDayReminder) - 4 * 60 * 60) < 1,
            "Long-unanswered prompts should resume up to two reminders on each later day"
        )

        let quietMidnight = parseDate("2026-07-11T15:00:00Z")
        let quietRelease = MacNotificationScheduler.allowedDeliveryDate(quietMidnight, settings: settings)
        let releaseComponents = seoulCalendar.dateComponents([.hour, .minute], from: quietRelease)
        try require(releaseComponents.hour == 9 && releaseComponents.minute == 30, "Mac quiet-hours prompt should recover at 09:30")
        try require(MacNotificationScheduler.isQuietHours(quietMidnight, settings: settings), "Mac should detect overnight quiet hours")
        try require(MacNotificationScheduler.nextQuietBoundary(after: quietMidnight, settings: settings) != nil, "Mac should schedule a quiet-hours boundary refresh")

        try require(MacNotificationScheduler.producesFeedback(action: MacNotificationAction.start), "Recommendation start should hold the queue for meaning feedback")
        var recommendation = notificationCard(id: "recommendation", wordbook: .recommended, dueAt: firstPrompt)
        recommendation.exampleSentence = "A recommendation example."
        let startFeedback = MacNotificationScheduler.feedbackContent(for: MacNotificationAction.start, card: recommendation)
        try require(startFeedback?.body.contains("뜻: meaning") == true, "Recommendation start feedback should reveal the meaning")
        try require(
            MacNotificationScheduler.resolvedPromptAction(
                action: UNNotificationDismissActionIdentifier,
                category: MacNotificationAction.reviewCategory
            ) == MacNotificationAction.unavailable,
            "Review prompt swipe should map to unavailable"
        )
        try require(
            MacNotificationScheduler.resolvedPromptAction(
                action: UNNotificationDismissActionIdentifier,
                category: MacNotificationAction.recommendationCategory
            ) == MacNotificationAction.advanceRecommendation,
            "Recommendation prompt swipe should advance without feedback"
        )
        try require(
            MacNotificationScheduler.failsafeIdentifier(cardId: "one-slot") ==
                MacNotificationScheduler.notificationIdentifier(cardId: "one-slot") + ".failsafe",
            "Mac unanswered failsafe should belong to the same card family"
        )

        var staleReview = notificationCard(id: "stale-review", wordbook: .user, dueAt: now.addingTimeInterval(600))
        staleReview.status = .review
        let currentPrompt = PromptReminderState(cardId: staleReview.id, firstPromptAt: now)
        try require(
            !MacNotificationScheduler.shouldHandlePromptAction(
                action: MacNotificationAction.remembered,
                card: staleReview,
                promptState: currentPrompt,
                feedbackHold: nil,
                now: now
            ),
            "A sync-moved future card must reject a stale notification action"
        )
        staleReview.dueAt = now
        try require(
            MacNotificationScheduler.shouldHandlePromptAction(
                action: MacNotificationAction.remembered,
                card: staleReview,
                promptState: currentPrompt,
                feedbackHold: nil,
                now: now
            ),
            "The current due review prompt should accept one action"
        )
        try require(
            !MacNotificationScheduler.shouldHandlePromptAction(
                action: MacNotificationAction.remembered,
                card: staleReview,
                promptState: currentPrompt,
                feedbackHold: hold,
                now: now
            ),
            "A persisted feedback hold must reject duplicate review callbacks"
        )

        var relearning = notificationCard(id: "relearning", wordbook: .user, dueAt: now)
        relearning.status = .relearning
        var review = notificationCard(id: "review", wordbook: .user, dueAt: now.addingTimeInterval(-3600))
        review.status = .review
        try require(
            MacNotificationPlan.scheduledCards(cards: [review, relearning], settings: UserSettingsData()).first?.id == "relearning",
            "Mac notification plan should use shared relearning-first scheduler priority"
        )
        try require(
            MacNotificationPlan.scheduledCards(
                cards: [review, relearning],
                settings: UserSettingsData(dailyWordGoal: 2, maxQueueSize: 20),
                processedCardIdsToday: ["done-1", "done-2"]
            ).isEmpty,
            "Mac notification plan must stop when today's distinct-card budget is exhausted"
        )

        let epochCard = mergeTestCard(
            id: "epoch-due",
            meaning: "damaged",
            version: 1,
            updatedAt: Date(timeIntervalSince1970: 0),
            dueAt: Date(timeIntervalSince1970: 0)
        )
        try require(
            MacNotificationPlan.scheduledCards(cards: [epochCard], settings: UserSettingsData()).isEmpty,
            "Mac must not notify an epoch-damaged card before startup sync repairs it"
        )
    }

    static func assertDueTimeJitter() throws {
        let now = Date(timeIntervalSince1970: 0)
        let settings = SchedulerSettings()
        let baseDueAt = Scheduler.initialDueAt(now: now, settings: settings)
        let earliest = DueTimeJitter.apply(to: baseDueAt, now: now, settings: settings) { range in
            range.lowerBound
        }
        let latest = DueTimeJitter.apply(to: baseDueAt, now: now, settings: settings) { range in
            range.upperBound
        }
        try require(earliest != latest, "Due jitter should spread identical base due times")
        try require(earliest >= now.addingTimeInterval(60), "Due jitter should not schedule immediate follow-ups")

        let card = SchedulerCard(id: "jitter-review", status: .new, intervalDays: 0, dueAt: now)
        let result = Scheduler.applyReview(card: card, response: .remembered, now: now)
        let jittered = DueTimeJitter.apply(to: result, now: now, settings: settings) { range in
            range.upperBound
        }
        try require(jittered.card.dueAt > result.card.dueAt, "Review jitter should shift next due time")
        try require(jittered.event.nextDueAt == jittered.card.dueAt, "Review jitter should keep event and card due times aligned")
        try require(jittered.event.metadata["due_jitter_seconds"] != nil, "Review jitter should record jitter metadata")
    }

    static func parseStatus(_ value: String) -> CardStatus { CardStatus(rawValue: value)! }
    static func parseResponse(_ value: String) -> ReviewResponse { ReviewResponse(rawValue: value)! }
    static func parseDate(_ value: String) -> Date { ISO8601DateFormatter().date(from: value)! }

    static func notificationCard(
        id: String,
        wordbook: Wordbook,
        dueAt: Date,
        recommendationStartedAt: Date? = nil
    ) -> VocabularyCard {
        VocabularyCard(
            id: id,
            lemma: id,
            displayWord: id,
            meaningKo: "meaning",
            definitionEn: "",
            partOfSpeech: "",
            exampleSentence: "",
            definitionSourcePrimary: "test",
            status: .new,
            wordbook: wordbook,
            dueAt: dueAt,
            intervalDays: 0,
            lapseCount: 0,
            recommendationStartedAt: recommendationStartedAt,
            createdAt: dueAt,
            updatedAt: dueAt,
            version: 1
        )
    }

    static func mergeTestCard(
        id: String,
        meaning: String,
        version: Int,
        updatedAt: Date,
        dueAt: Date = Date(timeIntervalSince1970: 600)
    ) -> VocabularyCard {
        VocabularyCard(
            id: id,
            lemma: id,
            displayWord: id,
            meaningKo: meaning,
            definitionEn: "",
            partOfSpeech: "",
            exampleSentence: "",
            definitionSourcePrimary: "test",
            status: .review,
            wordbook: .user,
            dueAt: dueAt,
            intervalDays: 3,
            lapseCount: 0,
            createdAt: Date(timeIntervalSince1970: 10),
            updatedAt: updatedAt,
            version: version
        )
    }

    static func mergeTestEvent(
        id: String,
        clientCreatedAt: Date,
        response: ReviewResponse
    ) -> StoredReviewEvent {
        StoredReviewEvent(
            id: id,
            cardId: "event-card",
            response: response,
            previousStatus: .new,
            nextStatus: .learning,
            previousDueAt: Date(timeIntervalSince1970: 10),
            nextDueAt: Date(timeIntervalSince1970: 20),
            previousIntervalDays: 0,
            nextIntervalDays: 1,
            clientCreatedAt: clientCreatedAt,
            metadata: [:]
        )
    }

    static func snapshot(
        cards: [VocabularyCard] = [],
        contexts: [StoredContext] = [],
        definitionCandidates: [StoredDefinitionCandidate] = [],
        reviewEvents: [StoredReviewEvent] = [],
        recommendationFeedback: [RecommendationFeedback] = []
    ) -> StoreSnapshot {
        StoreSnapshot(
            cards: cards,
            contexts: contexts,
            definitionCandidates: definitionCandidates,
            reviewEvents: reviewEvents,
            recommendationFeedback: recommendationFeedback,
            settings: UserSettingsData()
        )
    }

    static func require(_ condition: Bool, _ message: String) throws {
        if !condition { throw CheckError(message) }
    }
}

private struct CheckError: Error, CustomStringConvertible {
    var description: String
    init(_ description: String) { self.description = description }
}


/// The store and notification checks that used to run inside the app binary
/// (`--check-scheduler-vectors`). Shared vectors now run via `vocab-check vectors`.
@Suite(.serialized)
@MainActor
struct LegacyCheckSuiteTests {
    @Test func deviceIdPersists() throws { try LegacyCheckSuites.assertMacDeviceIdPersistence() }
    @Test func settingsTimestampMerge() throws { try LegacyCheckSuites.assertSettingsTimestampMerge() }
    @Test func cloudBatchChunking() throws { try LegacyCheckSuites.assertCloudBatchChunking() }
    @Test func localRecommendedCapturePromotion() throws { try LegacyCheckSuites.assertLocalRecommendedCapturePromotion() }
    @Test func dictionaryExtraction() throws { try LegacyCheckSuites.assertDictionaryExtraction() }
    @Test func settingsFallback() throws { try LegacyCheckSuites.assertMacSettingsFallback() }
    @Test func notificationReviewAction() throws { try LegacyCheckSuites.assertMacNotificationReviewAction(now: Date()) }
    @Test func notificationStateMachine() throws { try LegacyCheckSuites.assertMacNotificationStateMachine(now: Date()) }
    @Test func dueTimeJitter() throws { try LegacyCheckSuites.assertDueTimeJitter() }
}
