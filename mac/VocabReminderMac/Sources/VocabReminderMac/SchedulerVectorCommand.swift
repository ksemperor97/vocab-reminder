import Darwin
import Foundation
@preconcurrency import UserNotifications

enum SchedulerVectorCommand {
    @MainActor
    static func runAndExitIfRequested() {
        if CommandLine.arguments.contains("--check-notification-status") {
            runNotificationStatusAndExit()
        }
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

    private static func runNotificationStatusAndExit() {
        guard let bundleIdentifier = Bundle.main.bundleIdentifier else {
            print("bundleIdentifier=nil")
            exit(2)
        }

        let center = UNUserNotificationCenter.current()
        let semaphore = DispatchSemaphore(value: 0)
        let status = NotificationStatusBox()

        center.getNotificationSettings { settings in
            status.settingsStatus = String(describing: settings.authorizationStatus)
            center.getPendingNotificationRequests { requests in
                status.pending = requests.map(\.identifier).sorted()
                status.pendingDetails = requests.map(Self.describeNotificationRequest).sorted()
                center.getDeliveredNotifications { notifications in
                    status.delivered = notifications.map(\.request.identifier).sorted()
                    status.deliveredDetails = notifications.map { Self.describeNotificationRequest($0.request) }.sorted()
                    semaphore.signal()
                }
            }
        }

        _ = semaphore.wait(timeout: .now() + 5)
        let pendingText = status.pending.joined(separator: ",")
        let deliveredText = status.delivered.joined(separator: ",")
        print("bundleIdentifier=\(bundleIdentifier)")
        print("authorizationStatus=\(status.settingsStatus)")
        print("pending=\(pendingText)")
        print("delivered=\(deliveredText)")
        for detail in status.pendingDetails {
            print("pendingDetail=\(detail)")
        }
        for detail in status.deliveredDetails {
            print("deliveredDetail=\(detail)")
        }
        exit(0)
    }

    private static func describeNotificationRequest(_ request: UNNotificationRequest) -> String {
        [
            request.identifier,
            request.content.title,
            request.content.body.replacingOccurrences(of: "\n", with: " / "),
            request.content.categoryIdentifier
        ].joined(separator: "|")
    }

    private final class NotificationStatusBox: @unchecked Sendable {
        var settingsStatus = "unknown"
        var pending: [String] = []
        var delivered: [String] = []
        var pendingDetails: [String] = []
        var deliveredDetails: [String] = []
    }

    @MainActor
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
        try assertCaptureVectors()
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
            recommendationFeedback: [
                RecommendationFeedback(
                    id: "feedback-1",
                    cardId: "card-1",
                    lemma: "tentative",
                    action: "known",
                    createdAt: Date(timeIntervalSince1970: 0)
                )
            ],
            settings: UserSettingsData(recommendationsIntroducedAt: Date(timeIntervalSince1970: 0))
        )
        let syncJson = try JSONSerialization.jsonObject(with: syncPayload) as! [String: Any]
        let cards = syncJson["cards"] as! [[String: Any]]
        try require(cards.first?["status"] as? String == "new", "Mac sync card status should be lowercase")
        let feedbackJson = syncJson["recommendation_feedback"] as! [[String: Any]]
        try require(feedbackJson.first?["action"] as? String == "known", "Mac sync should export recommendation feedback")
        let settingsJson = syncJson["settings"] as! [String: Any]
        try require(settingsJson["recommendations_introduced_at"] as? String == "1970-01-01T00:00:00Z", "Mac sync should export recommendation intro timestamp")
        let importedSnapshot = try SyncBatchFactory.localSnapshot(from: syncPayload)
        try require(importedSnapshot.cards.first?.status == .new, "Mac sync import should restore status")
        try require(importedSnapshot.cards.first?.wordbook == .user, "Mac sync import should restore wordbook")
        try require(importedSnapshot.recommendationFeedback.first?.action == "known", "Mac sync import should restore recommendation feedback")
        try require(importedSnapshot.settings.recommendationsIntroducedAt == Date(timeIntervalSince1970: 0), "Mac sync import should restore recommendation intro timestamp")

        let duplicatePayload: [String: Any] = [
            "cards": [
                [
                    "id": "mac-card",
                    "lemma": "tentative",
                    "display_word": "tentative",
                    "meaning_ko": "잠정적인",
                    "wordbook": "user",
                    "status": "new",
                    "due_at": "1970-01-01T00:10:00Z",
                    "interval_days": 0,
                    "lapse_count": 0,
                    "created_at": "1970-01-01T00:00:00Z",
                    "updated_at": "1970-01-01T00:00:00Z",
                    "version": 1
                ],
                [
                    "id": "android-card",
                    "lemma": "tentative",
                    "display_word": "tentative",
                    "meaning_ko": "temporary",
                    "wordbook": "recommended",
                    "status": "review",
                    "due_at": "1970-01-15T00:00:00Z",
                    "interval_days": 14,
                    "lapse_count": 2,
                    "recommendation_started_at": "1970-01-01T00:05:00Z",
                    "created_at": "1970-01-01T00:01:00Z",
                    "updated_at": "1970-01-01T00:01:00Z",
                    "version": 2
                ]
            ],
            "contexts": [
                [
                    "id": "mac-context",
                    "card_id": "mac-card",
                    "source_sentence": "The mac result is tentative.",
                    "capture_method": "mac_clipboard_hotkey",
                    "created_at": "1970-01-01T00:00:00Z"
                ],
                [
                    "id": "android-context",
                    "card_id": "android-card",
                    "source_sentence": "The android result is tentative.",
                    "capture_method": "android_process_text",
                    "created_at": "1970-01-01T00:01:00Z"
                ]
            ],
            "recommendation_feedback": [
                [
                    "id": "mac-feedback",
                    "card_id": "mac-card",
                    "lemma": "tentative",
                    "action": "known",
                    "created_at": "1970-01-01T00:00:00Z"
                ],
                [
                    "id": "android-feedback",
                    "card_id": "android-card",
                    "lemma": "tentative",
                    "action": "dismissed",
                    "created_at": "1970-01-01T00:01:00Z"
                ]
            ],
            "settings": ["daily_word_goal": 10]
        ]
        let duplicateData = try JSONSerialization.data(withJSONObject: duplicatePayload)
        let duplicateSnapshot = try SyncBatchFactory.localSnapshot(from: duplicateData)
        try require(duplicateSnapshot.cards.count == 1, "Mac sync import should merge duplicate lemma cards")
        try require(duplicateSnapshot.cards[0].wordbook == .user, "Mac sync import should keep user wordbook when promoting recommended duplicate")
        try require(duplicateSnapshot.cards[0].status == .review, "Mac sync import should preserve recommended progress status")
        try require(duplicateSnapshot.cards[0].intervalDays == 14, "Mac sync import should preserve recommended progress interval")
        try require(duplicateSnapshot.cards[0].lapseCount == 2, "Mac sync import should preserve recommended progress lapse count")
        try require(duplicateSnapshot.contexts.count == 2, "Mac sync import should keep both duplicate contexts")
        try require(duplicateSnapshot.contexts.allSatisfy { $0.cardId == duplicateSnapshot.cards[0].id }, "Mac sync import should remap duplicate contexts")
        try require(duplicateSnapshot.recommendationFeedback.allSatisfy { $0.cardId == duplicateSnapshot.cards[0].id }, "Mac sync import should remap duplicate feedback")
        let localOnlyCard = VocabularyCard(
            id: "local-only-card",
            lemma: "coherent",
            displayWord: "coherent",
            meaningKo: "일관된",
            definitionEn: "",
            partOfSpeech: "",
            exampleSentence: "The local note is coherent.",
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
        let localSnapshot = StoreSnapshot(
            cards: [localOnlyCard],
            contexts: [
                StoredContext(
                    id: "local-only-context",
                    cardId: "local-only-card",
                    sourceSentence: "The local note is coherent.",
                    sourceApp: "Mac",
                    captureMethod: .manual,
                    createdAt: Date(timeIntervalSince1970: 0)
                )
            ],
            definitionCandidates: [],
            reviewEvents: [],
            recommendationFeedback: [],
            settings: UserSettingsData()
        )
        let mergedSnapshot = SyncBatchFactory.merge(local: localSnapshot, incoming: duplicateSnapshot)
        try require(mergedSnapshot.cards.count == 2, "Mac sync UI import should merge incoming cards without dropping local-only cards")
        try require(mergedSnapshot.contexts.count == 3, "Mac sync UI import should merge local and incoming contexts")
        let idempotentSnapshot = SyncBatchFactory.merge(local: mergedSnapshot, incoming: duplicateSnapshot)
        try require(idempotentSnapshot.cards.count == mergedSnapshot.cards.count, "Mac sync UI import should be idempotent for cards")
        try require(idempotentSnapshot.contexts.count == mergedSnapshot.contexts.count, "Mac sync UI import should be idempotent for contexts")
        try assertLocalRecommendedCapturePromotion()

        let notificationNow = Date()
        let notificationCards = [
            notificationCard(id: "rec-unstarted", wordbook: .recommended, dueAt: notificationNow),
            notificationCard(id: "user-due", wordbook: .user, dueAt: notificationNow),
            notificationCard(id: "rec-started", wordbook: .recommended, dueAt: notificationNow.addingTimeInterval(120), recommendationStartedAt: notificationNow),
            notificationCard(id: "user-future", wordbook: .user, dueAt: notificationNow.addingTimeInterval(86_400))
        ]
        let enabledMacNotifications = MacNotificationPlan.scheduledCards(
            cards: notificationCards,
            settings: UserSettingsData(dailyWordGoal: 10, maxQueueSize: 3)
        )
        try require(enabledMacNotifications.map(\.id) == ["user-due", "rec-unstarted", "rec-started"], "Mac notifications should not let future user cards displace due recommendation alerts")
        let disabledMacNotifications = MacNotificationPlan.scheduledCards(
            cards: notificationCards,
            settings: UserSettingsData(dailyWordGoal: 10, maxQueueSize: 3, enableWordRecommendations: false)
        )
        try require(disabledMacNotifications.map(\.id) == ["user-due", "user-future"], "Mac notifications should stop recommendation alerts when recommendations are disabled")

        try assertRecommendationEngine()

        let signInURL = try FirebaseAuthRequestFactory.signInURL(webApiKey: "api key")
        try require(signInURL.absoluteString == "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=api%20key", "Mac Firebase sign-in URL should include encoded Web API key")
        let signInBody = try JSONSerialization.jsonObject(
            with: FirebaseAuthRequestFactory.signInBody(email: "user@example.com", password: "secret")
        ) as! [String: Any]
        try require(signInBody["returnSecureToken"] as? Bool == true, "Mac Firebase sign-in should request secure token")
        let signInSession = try FirebaseAuthRequestFactory.parseSignIn([
            "idToken": "id-token",
            "refreshToken": "refresh-token",
            "expiresIn": "3600",
            "email": "user@example.com",
            "localId": "uid-1"
        ], fallbackEmail: "")
        try require(signInSession.idToken == "id-token", "Mac Firebase sign-in should parse idToken")
        try require(signInSession.refreshToken == "refresh-token", "Mac Firebase sign-in should parse refreshToken")
        let refreshURL = try FirebaseAuthRequestFactory.refreshURL(webApiKey: "api key")
        try require(refreshURL.absoluteString == "https://securetoken.googleapis.com/v1/token?key=api%20key", "Mac Firebase refresh URL should include encoded Web API key")
        let refreshBody = String(data: FirebaseAuthRequestFactory.refreshBody(refreshToken: "refresh token"), encoding: .utf8)
        try require(refreshBody == "grant_type=refresh_token&refresh_token=refresh+token", "Mac Firebase refresh body should be form encoded")
        let refreshedSession = try FirebaseAuthRequestFactory.parseRefresh([
            "id_token": "fresh-id-token",
            "refresh_token": "fresh-refresh-token",
            "expires_in": "3600",
            "user_id": "uid-1"
        ], existingRefreshToken: "refresh-token")
        try require(refreshedSession.idToken == "fresh-id-token", "Mac Firebase refresh should parse id_token")
        try require(refreshedSession.refreshToken == "fresh-refresh-token", "Mac Firebase refresh should parse refresh_token")
    }

    @MainActor
    private static func assertLocalRecommendedCapturePromotion() throws {
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
            "settings": ["daily_word_goal": 10]
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
        try require(store.contexts.count == 1, "Mac local capture should attach a real context to promoted recommendation")
        store.softDeleteCard(promoted)
        try require(store.activeCards.isEmpty, "Mac delete should remove card from active list")
        try require(store.cards.first?.deletedAt != nil, "Mac delete should mark deletedAt")
    }

    private static func assertApplyReview(_ vector: [String: Any]) throws {
        let now = parseDate(vector["now"] as! String)
        let settings = parseSettings(vector["settings"] as? [String: Any])
        let card = parseCard(vector["given"] as! [String: Any], now: now)
        let response = parseResponse(vector["response"] as! String)
        let recentResponses = (vector["recent_responses"] as? [String] ?? []).map(parseResponse)
        let unavailableCount = vector["unavailable_count_today"] as? Int ?? 0
        let forgottenCount = vector["forgotten_count_today"] as? Int ?? 0
        let result = Scheduler.applyReview(card: card, response: response, settings: settings, now: now, recentResponses: recentResponses, unavailableCountToday: unavailableCount, forgottenCountToday: forgottenCount)
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

    private static func assertRecommendationEngine() throws {
        let sharedCatalog = try JSONSerialization.jsonObject(with: Data(contentsOf: recommendationCatalogURL())) as! [String: Any]
        let sharedEntries = sharedCatalog["entries"] as! [[String: Any]]

        try require(RecommendationEngine.catalog.count >= 100, "Expected production-sized Mac recommendation catalogue")
        try require(RecommendationEngine.catalog.count == sharedEntries.count, "Mac recommendation catalogue should match shared JSON count")
        try require(Set(RecommendationEngine.catalog.map(\.lemma)).count == RecommendationEngine.catalog.count, "Mac recommendation catalogue should not duplicate lemmas")
        try require(Set(RecommendationEngine.catalog.map(\.topic)).isSuperset(of: ["academic", "methods", "statistics", "neuroscience"]), "Mac recommendation catalogue should include core topics")
        let byLemma = Dictionary(uniqueKeysWithValues: RecommendationEngine.catalog.map { ($0.lemma, $0) })
        for entry in sharedEntries {
            let lemma = entry["lemma"] as! String
            let seed = byLemma[lemma]
            try require(seed?.frequencyRank == entry["frequencyRank"] as? Int, "Mac recommendation catalogue rank mismatch for \(lemma)")
            try require(seed?.topic == entry["topic"] as? String, "Mac recommendation catalogue topic mismatch for \(lemma)")
            try require(seed?.meaningKo == entry["meaningKo"] as? String, "Mac recommendation catalogue meaning mismatch for \(lemma)")
        }

        let vectors = try JSONSerialization.jsonObject(with: Data(contentsOf: recommendationVectorURL())) as! [[String: Any]]
        try require(vectors.count >= 7, "Expected recommendation vectors")
        for vector in vectors {
            let kind = vector["kind"] as! String
            if kind == "chooseRecommendations" {
                try assertChooseRecommendationsVector(vector)
            } else if kind == "fillLimit" {
                try assertFillLimitVector(vector)
            } else if kind == "estimateLevel" {
                try assertEstimateLevelVector(vector)
            } else {
                throw CheckError("Unknown recommendation vector kind \(kind)")
            }
        }
    }

    private static func assertChooseRecommendationsVector(_ vector: [String: Any]) throws {
        let settings = parseSettings(vector["settings"] as? [String: Any])
        let dueUserCount = vector["due_user_count"] as? Int ?? 0
        let limit = vector["limit"] as? Int ?? RecommendationEngine.recommendationFillLimit(
            dueUserCount: dueUserCount,
            existingRecommendationCount: vector["existing_recommendation_count"] as? Int ?? 0,
            settings: settings
        )
        let selected = RecommendationEngine.chooseRecommendations(
            userSignals: parseUserSignals(vector["user_signals"] as? [[String: Any]]),
            feedbackSignals: parseFeedbackSignals(vector["feedback_signals"] as? [[String: Any]]),
            existingLemmas: Set(vector["existing_lemmas"] as? [String] ?? []),
            dueUserCount: dueUserCount,
            settings: settings,
            limit: limit
        )
        let then = vector["then"] as! [String: Any]
        let name = vector["name"] as! String
        try require(selected.count == then["selected_count"] as! Int, "\(name): selected count")
        if let range = then["frequency_rank_between"] as? [Int] {
            try require(selected.allSatisfy { range[0]...range[1] ~= $0.frequencyRank }, "\(name): frequency rank range")
        }
        if let topics = then["first_topic_in"] as? [String], let first = selected.first {
            try require(topics.contains(first.topic), "\(name): first topic")
        }
        if let lemmas = then["excluded_lemmas"] as? [String] {
            try require(selected.allSatisfy { !lemmas.contains($0.lemma) }, "\(name): excluded lemmas")
        }
        if let topics = then["excluded_topics"] as? [String] {
            try require(selected.allSatisfy { !topics.contains($0.topic) }, "\(name): excluded topics")
        }
    }

    private static func assertFillLimitVector(_ vector: [String: Any]) throws {
        let result = RecommendationEngine.recommendationFillLimit(
            dueUserCount: vector["due_user_count"] as! Int,
            existingRecommendationCount: vector["existing_recommendation_count"] as! Int,
            settings: parseSettings(vector["settings"] as? [String: Any])
        )
        let then = vector["then"] as! [String: Any]
        try require(result == then["fill_limit"] as! Int, "\(vector["name"] as! String): fill limit")
    }

    private static func assertEstimateLevelVector(_ vector: [String: Any]) throws {
        let userSignals = parseUserSignals(vector["user_signals"] as? [[String: Any]])
        let baseline = RecommendationEngine.estimateLevel(userSignals: userSignals)
        let result = RecommendationEngine.estimateLevel(
            userSignals: userSignals,
            feedbackSignals: parseFeedbackSignals(vector["feedback_signals"] as? [[String: Any]])
        )
        let then = vector["then"] as! [String: Any]
        let name = vector["name"] as! String
        if then["lower_rank_greater_than_baseline"] as? Bool == true {
            try require(result.lowerRank > baseline.lowerRank, "\(name): lower rank")
        }
        if then["upper_rank_greater_than_baseline"] as? Bool == true {
            try require(result.upperRank > baseline.upperRank, "\(name): upper rank")
        }
    }

    private static func parseUserSignals(_ json: [[String: Any]]?) -> [UserVocabularySignal] {
        (json ?? []).flatMap { signal in
            Array(
                repeating: UserVocabularySignal(
                    lemma: signal["lemma"] as! String,
                    lapseCount: signal["lapse_count"] as? Int ?? 0,
                    rememberedCount: signal["remembered_count"] as? Int ?? 0,
                    forgottenCount: signal["forgotten_count"] as? Int ?? 0
                ),
                count: signal["count"] as? Int ?? 1
            )
        }
    }

    private static func parseFeedbackSignals(_ json: [[String: Any]]?) -> [RecommendationFeedbackSignal] {
        (json ?? []).map { signal in
            RecommendationFeedbackSignal(
                lemma: signal["lemma"] as! String,
                action: signal["action"] as! String
            )
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

    private static func notificationCard(
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

    private static func recommendationCatalogURL() throws -> URL {
        let candidates = [
            URL(filePath: "../../../shared/recommendations/recommendation_catalog.json"),
            URL(filePath: "../../shared/recommendations/recommendation_catalog.json"),
            URL(filePath: "../shared/recommendations/recommendation_catalog.json"),
            URL(filePath: "shared/recommendations/recommendation_catalog.json"),
            URL(filePath: FileManager.default.currentDirectoryPath).appending(path: "../../../shared/recommendations/recommendation_catalog.json"),
            URL(filePath: FileManager.default.currentDirectoryPath).appending(path: "../../shared/recommendations/recommendation_catalog.json")
        ]
        if let url = candidates.first(where: { FileManager.default.fileExists(atPath: $0.path) }) {
            return url
        }
        throw CheckError("Could not find shared/recommendations/recommendation_catalog.json")
    }

    private static func recommendationVectorURL() throws -> URL {
        let candidates = [
            URL(filePath: "../../../shared/test-vectors/recommendations.json"),
            URL(filePath: "../../shared/test-vectors/recommendations.json"),
            URL(filePath: "../shared/test-vectors/recommendations.json"),
            URL(filePath: "shared/test-vectors/recommendations.json"),
            URL(filePath: FileManager.default.currentDirectoryPath).appending(path: "../../../shared/test-vectors/recommendations.json"),
            URL(filePath: FileManager.default.currentDirectoryPath).appending(path: "../../shared/test-vectors/recommendations.json")
        ]
        if let url = candidates.first(where: { FileManager.default.fileExists(atPath: $0.path) }) {
            return url
        }
        throw CheckError("Could not find shared/test-vectors/recommendations.json")
    }

    private static func captureVectorURL() throws -> URL {
        let candidates = [
            URL(filePath: "../../../shared/test-vectors/capture_normalization.json"),
            URL(filePath: "../../shared/test-vectors/capture_normalization.json"),
            URL(filePath: "../shared/test-vectors/capture_normalization.json"),
            URL(filePath: "shared/test-vectors/capture_normalization.json"),
            URL(filePath: FileManager.default.currentDirectoryPath).appending(path: "../../../shared/test-vectors/capture_normalization.json"),
            URL(filePath: FileManager.default.currentDirectoryPath).appending(path: "../../shared/test-vectors/capture_normalization.json")
        ]
        if let url = candidates.first(where: { FileManager.default.fileExists(atPath: $0.path) }) {
            return url
        }
        throw CheckError("Could not find shared/test-vectors/capture_normalization.json")
    }

    private static func assertCaptureVectors() throws {
        let vectors = try JSONSerialization.jsonObject(with: Data(contentsOf: captureVectorURL())) as! [[String: Any]]
        try require(vectors.count >= 3, "Expected capture vectors")
        for vector in vectors {
            let name = vector["name"] as! String
            let analysis = CaptureNormalizer.analyze(vector["raw"] as! String)
            let then = vector["then"] as! [String: Any]
            if let expected = then["needs_word_selection"] as? Bool {
                try require(analysis.needsWordSelection == expected, "\(name): needs_word_selection")
            }
            if let candidates = then["candidates"] as? [String] {
                try require(analysis.candidates == candidates, "\(name): candidates")
            }
            for candidate in then["candidates_include"] as? [String] ?? [] {
                try require(analysis.candidates.contains(candidate), "\(name): missing \(candidate)")
            }
            for candidate in then["candidates_exclude"] as? [String] ?? [] {
                try require(!analysis.candidates.contains(candidate), "\(name): included \(candidate)")
            }
        }
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
