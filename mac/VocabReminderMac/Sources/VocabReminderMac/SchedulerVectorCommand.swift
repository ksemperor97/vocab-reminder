import Darwin
import Foundation
@preconcurrency import UserNotifications

enum SchedulerVectorCommand {
    @MainActor
    static func runAndExitIfRequested() {
        if CommandLine.arguments.contains("--check-notification-status") {
            runNotificationStatusAndExit()
        }
        if CommandLine.arguments.contains("--check-cloud-sync-smoke") {
            runCloudSyncSmokeAndExit()
        }
        guard CommandLine.arguments.contains("--check-scheduler-vectors") else { return }
        do {
            try run(now: Date())
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

    private static func runCloudSyncSmokeAndExit() {
        let env = ProcessInfo.processInfo.environment
        guard let serverURL = env["VOCAB_SYNC_SMOKE_URL"], serverURL.isEmpty == false else {
            fputs("VOCAB_SYNC_SMOKE_URL is required\n", stderr)
            exit(64)
        }
        guard let token = env["VOCAB_SYNC_SMOKE_TOKEN"], token.isEmpty == false else {
            fputs("VOCAB_SYNC_SMOKE_TOKEN is required\n", stderr)
            exit(64)
        }

        let semaphore = DispatchSemaphore(value: 0)
        let result = CloudSyncSmokeResultBox()
        Task.detached {
            do {
                try await runCloudSyncSmoke(serverURL: serverURL, token: token)
                result.message = "Mac cloud sync smoke passed"
                result.exitCode = 0
            } catch {
                result.message = "Mac cloud sync smoke failed: \(error)"
                result.exitCode = 1
            }
            semaphore.signal()
        }

        if semaphore.wait(timeout: .now() + 20) == .timedOut {
            fputs("Mac cloud sync smoke timed out\n", stderr)
            exit(1)
        }
        if result.exitCode == 0 {
            print(result.message)
        } else {
            fputs("\(result.message)\n", stderr)
        }
        exit(Int32(result.exitCode))
    }

    private static func runCloudSyncSmoke(serverURL: String, token: String) async throws {
        let client = CloudSyncClient(config: CloudSyncConfig(serverBaseURL: serverURL, bearerToken: token))
        let health = try await client.health()
        try require(health["ok"] as? Bool == true, "Mac cloud sync health should return ok=true")
        try require(health["cards"] as? Int == 0, "Mac cloud sync smoke should start with zero cards")

        do {
            _ = try await CloudSyncClient(
                config: CloudSyncConfig(serverBaseURL: serverURL, bearerToken: "wrong-token")
            ).health()
            throw CheckError("Mac cloud sync wrong token should fail")
        } catch CloudSyncError.http(let status, _) {
            try require(status == 401, "Mac cloud sync wrong token should return 401")
        }

        let pushResult = try await client.push(localBatch: cloudSyncSmokeBatch())
        let applied = pushResult["applied"] as? [String: Any]
        try require(applied?["cards"] as? Int == 1, "Mac cloud sync push should apply one card")
        try require(applied?["review_events"] as? Int == 1, "Mac cloud sync push should apply one review event")

        let snapshotData = try await client.pullSnapshot()
        let snapshot = try JSONSerialization.jsonObject(with: snapshotData) as? [String: Any]
        let cards = snapshot?["cards"] as? [[String: Any]]
        try require(cards?.first?["lemma"] as? String == "resilient", "Mac cloud sync snapshot should return pushed card")

        let duplicate = try await client.push(localBatch: [
            "review_events": cloudSyncSmokeBatch()["review_events"] as! [[String: Any]]
        ])
        let duplicateApplied = duplicate["applied"] as? [String: Any]
        try require(duplicateApplied?["review_events"] as? Int == 0, "Mac cloud sync duplicate review event should be idempotent")

        var badCard = (cloudSyncSmokeBatch()["cards"] as! [[String: Any]])[0]
        badCard["id"] = "bad-card"
        badCard["due_at"] = "not-a-date"
        let broken = try await client.push(localBatch: ["cards": [badCard]])
        let skipped = broken["skipped"] as? [String: Any]
        try require(skipped?["cards"] as? Int == 1, "Mac cloud sync broken card should be skipped")
    }

    private final class CloudSyncSmokeResultBox: @unchecked Sendable {
        var message = ""
        var exitCode = 1
    }

    private static func describeNotificationRequest(_ request: UNNotificationRequest) -> String {
        [
            request.identifier,
            request.content.title,
            request.content.body.replacingOccurrences(of: "\n", with: " / "),
            request.content.categoryIdentifier
        ].joined(separator: "|")
    }

    private static func cloudSyncSmokeBatch() -> [String: Any] {
        [
            "cards": [
                [
                    "id": "mac-smoke-card",
                    "lemma": "resilient",
                    "display_word": "resilient",
                    "language": "en",
                    "wordbook": "user",
                    "meaning_ko": "회복력 있는",
                    "definition_en": "Able to recover quickly.",
                    "part_of_speech": "adjective",
                    "source_sentence_primary": "A resilient system recovers quickly.",
                    "source_app_primary": "mac-smoke",
                    "definition_source_primary": "user",
                    "status": "new",
                    "due_at": "2026-07-03T00:00:00.000Z",
                    "interval_days": 0,
                    "lapse_count": 0,
                    "created_device_id": "mac-smoke",
                    "created_at": "2026-07-03T00:00:00.000Z",
                    "updated_at": "2026-07-03T00:00:00.000Z",
                    "version": 1
                ]
            ],
            "review_events": [
                [
                    "id": "mac-smoke-event",
                    "card_id": "mac-smoke-card",
                    "device_id": "mac-smoke",
                    "response": "remembered",
                    "previous_status": "new",
                    "next_status": "review",
                    "previous_due_at": "2026-07-03T00:00:00.000Z",
                    "next_due_at": "2026-07-04T00:00:00.000Z",
                    "previous_interval_days": 0,
                    "next_interval_days": 1,
                    "client_created_at": "2026-07-03T00:00:00.000Z",
                    "server_created_at": "2026-07-03T00:00:00.000Z",
                    "metadata": [:]
                ]
            ],
            "settings": [
                "max_queue_size": 20,
                "snooze_duration_minutes": 60,
                "daily_word_goal": 8,
                "quiet_hours_start": "23:00",
                "quiet_hours_end": "09:00",
                "timezone": "Asia/Seoul",
                "enable_android_notifications": true,
                "enable_mac_notifications": true,
                "show_meaning_on_lockscreen": false,
                "show_meaning_after_remembered": true,
                "auto_generate_korean_meaning": true,
                "save_source_url": true,
                "save_surrounding_text": false,
                "enable_word_recommendations": true,
                "recommendations_introduced_at": NSNull()
            ]
        ]
    }

    private final class NotificationStatusBox: @unchecked Sendable {
        var settingsStatus = "unknown"
        var pending: [String] = []
        var delivered: [String] = []
        var pendingDetails: [String] = []
        var deliveredDetails: [String] = []
    }

    @MainActor
    private static func run(now: Date) throws {
        try verifySharedSchedulerVectors()

        let sentence = CaptureNormalizer.analyze("The results are tentative, but the pattern is robust.")
        try require(sentence.needsWordSelection, "Sentence should require word selection")
        try require(sentence.candidates.contains("tentative"), "Missing tentative candidate")
        try require(sentence.candidates.contains("robust"), "Missing robust candidate")
        try assertCaptureVectors()
        let robust = DictionaryResolver.resolve("Robustly,")
        try require(robust.lemma == "robust", "Expected robust lemma, got \(robust.lemma)")
        try require(robust.meaningKo.isEmpty == false, "Mac dictionary should provide a Korean robust meaning")
        let localRobust = DictionaryResolver.resolve(CaptureNormalizer.analyze("Robustly,"), sourceApp: "Self-check") { _ in nil }
        try require(localRobust.meaningKo == "견고한, 안정적인", "Local robust fallback should stay available")
        let behemoth = DictionaryResolver.resolve(CaptureNormalizer.analyze("behemoth"), sourceApp: "Self-check") { _ in nil }
        try require(behemoth.lemma == "behemoth", "Expected behemoth lemma, got \(behemoth.lemma)")
        try require(behemoth.meaningKo == "거대한 것, 거대한 짐승", "Unexpected behemoth meaning")
        let looking = DictionaryResolver.resolve(CaptureNormalizer.analyze("looking"), sourceApp: "Self-check") { _ in nil }
        try require(looking.lemma == "look", "Expected look lemma, got \(looking.lemma)")
        try require(looking.meaningKo == "보다, 바라보다; 찾아보다", "Unexpected looking meaning")
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
        try require((cards.first?["created_device_id"] as? String)?.range(of: #"^mac-[0-9a-fA-F]{8}$"#, options: .regularExpression) != nil, "Mac sync should export persistent mac device id")
        let feedbackJson = syncJson["recommendation_feedback"] as! [[String: Any]]
        try require(feedbackJson.first?["action"] as? String == "known", "Mac sync should export recommendation feedback")
        let settingsJson = syncJson["settings"] as! [String: Any]
        try require(settingsJson["recommendations_introduced_at"] as? String == "1970-01-01T00:00:00Z", "Mac sync should export recommendation intro timestamp")
        try require(settingsJson["settings_updated_at"] as? String == "1970-01-01T00:00:00Z", "Mac sync should export settings update timestamp")
        let importedSnapshot = try SyncBatchFactory.localSnapshot(from: syncPayload)
        try require(importedSnapshot.cards.first?.status == .new, "Mac sync import should restore status")
        try require(importedSnapshot.cards.first?.wordbook == .user, "Mac sync import should restore wordbook")
        try require(importedSnapshot.recommendationFeedback.first?.action == "known", "Mac sync import should restore recommendation feedback")
        try require(importedSnapshot.settings.recommendationsIntroducedAt == Date(timeIntervalSince1970: 0), "Mac sync import should restore recommendation intro timestamp")
        try require(importedSnapshot.settings.updatedAt == Date(timeIntervalSince1970: 0), "Mac sync import should restore settings update timestamp")
        try assertMacDeviceIdPersistence()

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
        try assertSettingsTimestampMerge()
        try assertSyncTimestampAndMergeRegression()
        try assertCloudBatchChunking()
        try assertLocalRecommendedCapturePromotion()
        try assertDictionaryExtraction()
        try assertMacStoreBackupRecovery()
        try assertMacSettingsFallback()

        let notificationNow = now
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
        try require(enabledMacNotifications.map(\.id) == ["user-due"], "Mac notifications should show one active prompt at a time")
        let disabledMacNotifications = MacNotificationPlan.scheduledCards(
            cards: notificationCards,
            settings: UserSettingsData(dailyWordGoal: 10, maxQueueSize: 3, enableWordRecommendations: false)
        )
        try require(disabledMacNotifications.map(\.id) == ["user-due"], "Mac notifications should keep one active prompt when recommendations are disabled")
        try assertMacNotificationReviewAction(now: now)
        try assertMacNotificationStateMachine(now: now)
        try require(
            MacAutoSyncController.timerIntervalNanoseconds == 15 * 60 * 1_000_000_000,
            "Mac periodic sync should run every 15 minutes"
        )
        try assertDueTimeJitter()

        try assertRecommendationEngine()
    }

    static func verifySharedSchedulerVectors() throws {
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
    }

    @MainActor
    private static func assertMacDeviceIdPersistence() throws {
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

    private static func assertSettingsTimestampMerge() throws {
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

    private static func assertSyncTimestampAndMergeRegression() throws {
        let microsecondPayload: [String: Any] = [
            "cards": [[
                "id": "android-micro-card",
                "lemma": "fractional",
                "display_word": "fractional",
                "meaning_ko": "소수의",
                "wordbook": "user",
                "status": "review",
                "due_at": "2026-07-12T03:04:05.1Z",
                "interval_days": 3,
                "lapse_count": 0,
                "created_at": "2026-07-11T01:02:03.123456Z",
                "updated_at": "2026-07-11T04:05:06.123456789Z",
                "version": 6
            ]],
            "contexts": [[
                "id": "android-micro-context",
                "card_id": "android-micro-card",
                "source_sentence": "Android emits microseconds.",
                "capture_method": "android_process_text",
                "created_at": "2026-07-11T01:02:03.123456Z"
            ]],
            "definition_candidates": [],
            "review_events": [
                [
                    "id": "event-later",
                    "card_id": "android-micro-card",
                    "response": "remembered",
                    "previous_status": "learning",
                    "next_status": "review",
                    "previous_due_at": "2026-07-11T01:00:00.000001Z",
                    "next_due_at": "2026-07-12T01:00:00.000001Z",
                    "previous_interval_days": 1,
                    "next_interval_days": 3,
                    "client_created_at": "2026-07-11T02:00:00.000002Z",
                    "metadata": [:]
                ],
                [
                    "id": "event-earlier",
                    "card_id": "android-micro-card",
                    "response": "forgotten",
                    "previous_status": "new",
                    "next_status": "relearning",
                    "previous_due_at": "2026-07-11T00:00:00.000001Z",
                    "next_due_at": "2026-07-11T00:20:00.000001Z",
                    "previous_interval_days": 0,
                    "next_interval_days": 0,
                    "client_created_at": "2026-07-11T01:00:00.000001Z",
                    "metadata": [:]
                ]
            ],
            "recommendation_feedback": [],
            "settings": ["settings_updated_at": "2026-07-11T04:05:06.123456Z"]
        ]
        let microsecondData = try JSONSerialization.data(withJSONObject: microsecondPayload)
        let parsed = try SyncBatchFactory.localSnapshot(from: microsecondData)
        guard let parsedCard = parsed.cards.first else { throw CheckError("Fractional timestamp card missing") }
        try require(parsedCard.dueAt > Date(timeIntervalSince1970: 1_700_000_000), "Android one-digit fractional due_at must not become epoch")
        try require(parsedCard.createdAt > Date(timeIntervalSince1970: 1_700_000_000), "Android microsecond created_at must parse")
        try require(parsedCard.updatedAt > parsedCard.createdAt, "Android nanosecond updated_at must parse")
        try require(parsed.reviewEvents.map(\.id) == ["event-later", "event-earlier"], "Raw import preserves source event order until merge")

        var malformed = microsecondPayload
        var malformedCards = malformed["cards"] as! [[String: Any]]
        malformedCards[0]["due_at"] = "not-a-date"
        malformed["cards"] = malformedCards
        do {
            _ = try SyncBatchFactory.localSnapshot(from: JSONSerialization.data(withJSONObject: malformed))
            throw CheckError("Malformed required timestamps must fail import")
        } catch ImportError.invalidDate(let key, _) {
            try require(key == "due_at", "Malformed due_at should name its field")
        }

        let tieTime = Date(timeIntervalSince1970: 2_000)
        let highVersion = mergeTestCard(id: "merge", meaning: "local-v2", version: 2, updatedAt: Date(timeIntervalSince1970: 100))
        let lowVersionNewerTime = mergeTestCard(id: "merge", meaning: "incoming-v1", version: 1, updatedAt: Date(timeIntervalSince1970: 300))
        try require(
            SyncBatchFactory.merge(local: snapshot(cards: [highVersion]), incoming: snapshot(cards: [lowVersionNewerTime])).cards[0].meaningKo == "local-v2",
            "Card merge must compare version before updatedAt"
        )

        let equalVersionOld = mergeTestCard(id: "merge", meaning: "old", version: 3, updatedAt: Date(timeIntervalSince1970: 100))
        let equalVersionNew = mergeTestCard(id: "merge", meaning: "new", version: 3, updatedAt: Date(timeIntervalSince1970: 300))
        try require(
            SyncBatchFactory.merge(local: snapshot(cards: [equalVersionOld]), incoming: snapshot(cards: [equalVersionNew])).cards[0].meaningKo == "new",
            "Equal-version card merge must use updatedAt"
        )

        let activeAlpha = mergeTestCard(id: "tie", meaning: "alpha", version: 4, updatedAt: tieTime)
        let activeOmega = mergeTestCard(id: "tie", meaning: "omega", version: 4, updatedAt: tieTime)
        let forwardTie = SyncBatchFactory.merge(local: snapshot(cards: [activeAlpha]), incoming: snapshot(cards: [activeOmega])).cards[0]
        let reverseTie = SyncBatchFactory.merge(local: snapshot(cards: [activeOmega]), incoming: snapshot(cards: [activeAlpha])).cards[0]
        try require(forwardTie == reverseTie, "Exact active-card ties must converge independent of merge direction")

        var tombstone = activeAlpha
        tombstone.deletedAt = tieTime
        let forwardDelete = SyncBatchFactory.merge(local: snapshot(cards: [activeOmega]), incoming: snapshot(cards: [tombstone])).cards[0]
        let reverseDelete = SyncBatchFactory.merge(local: snapshot(cards: [tombstone]), incoming: snapshot(cards: [activeOmega])).cards[0]
        try require(forwardDelete.deletedAt != nil && reverseDelete.deletedAt != nil, "Exact card ties must prefer tombstones")
        var laterTombstone = tombstone
        laterTombstone.deletedAt = tieTime.addingTimeInterval(60)
        let forwardTombstone = SyncBatchFactory.merge(local: snapshot(cards: [tombstone]), incoming: snapshot(cards: [laterTombstone])).cards[0]
        let reverseTombstone = SyncBatchFactory.merge(local: snapshot(cards: [laterTombstone]), incoming: snapshot(cards: [tombstone])).cards[0]
        try require(
            forwardTombstone.deletedAt == laterTombstone.deletedAt && reverseTombstone.deletedAt == laterTombstone.deletedAt,
            "Exact tombstone ties must prefer the later deletion in both directions"
        )

        let epochLocal = mergeTestCard(
            id: "fractional-merge",
            meaning: "damaged",
            version: 6,
            updatedAt: Date(timeIntervalSince1970: 0),
            dueAt: Date(timeIntervalSince1970: 0)
        )
        var serverCard = parsedCard
        serverCard.id = "fractional-merge"
        let repairedMerge = SyncBatchFactory.merge(local: snapshot(cards: [epochLocal]), incoming: snapshot(cards: [serverCard]))
        try require(repairedMerge.cards[0].dueAt == serverCard.dueAt, "Equal-version fractional server due_at must repair local epoch damage")
        try require(repairedMerge.cards[0].updatedAt == serverCard.updatedAt, "Equal-version fractional server updated_at must repair local epoch damage")

        let epochEvent = mergeTestEvent(id: "same-event", clientCreatedAt: Date(timeIntervalSince1970: 0), response: .unavailable)
        let normalEvent = mergeTestEvent(id: "same-event", clientCreatedAt: Date(timeIntervalSince1970: 500), response: .remembered)
        let earlierEvent = mergeTestEvent(id: "earlier-event", clientCreatedAt: Date(timeIntervalSince1970: 400), response: .forgotten)
        let repairedEvents = SyncBatchFactory.merge(
            local: snapshot(reviewEvents: [epochEvent]),
            incoming: snapshot(reviewEvents: [normalEvent, earlierEvent])
        ).reviewEvents
        try require(repairedEvents.map(\.id) == ["earlier-event", "same-event"], "Merged review events must be chronological")
        try require(repairedEvents.last?.response == .remembered, "Same-ID normal review timestamp must replace epoch damage")
        let collidingEvent = mergeTestEvent(
            id: "same-event",
            clientCreatedAt: Date(timeIntervalSince1970: 900),
            response: .forgotten
        )
        let collisionProtectedEvent = SyncBatchFactory.merge(
            local: snapshot(reviewEvents: [normalEvent]),
            incoming: snapshot(reviewEvents: [collidingEvent])
        ).reviewEvents[0]
        try require(
            collisionProtectedEvent.response == .remembered,
            "A normal same-ID append-only event must keep the first payload even when a collision has a later timestamp"
        )

        let epochContext = StoredContext(
            id: "same-context",
            cardId: "event-card",
            sourceSentence: "damaged",
            sourceApp: "Mac",
            captureMethod: .manual,
            createdAt: Date(timeIntervalSince1970: 0)
        )
        var normalContext = epochContext
        normalContext.sourceSentence = "repaired"
        normalContext.createdAt = Date(timeIntervalSince1970: 600)
        let epochCandidate = StoredDefinitionCandidate(
            id: "same-candidate",
            cardId: "event-card",
            source: "test",
            rawText: "damaged",
            parsedMeaningKo: "",
            parsedDefinitionEn: "",
            partOfSpeech: "",
            exampleSentence: "",
            confidence: 0,
            selectedAsPrimary: false,
            createdAt: Date(timeIntervalSince1970: 0)
        )
        var normalCandidate = epochCandidate
        normalCandidate.rawText = "repaired"
        normalCandidate.createdAt = Date(timeIntervalSince1970: 700)
        let epochFeedback = RecommendationFeedback(
            id: "same-feedback",
            cardId: "event-card",
            lemma: "event-card",
            action: "damaged",
            createdAt: Date(timeIntervalSince1970: 0)
        )
        var normalFeedback = epochFeedback
        normalFeedback.action = "known"
        normalFeedback.createdAt = Date(timeIntervalSince1970: 800)
        let repairedRelated = SyncBatchFactory.merge(
            local: snapshot(
                contexts: [epochContext],
                definitionCandidates: [epochCandidate],
                recommendationFeedback: [epochFeedback]
            ),
            incoming: snapshot(
                contexts: [normalContext],
                definitionCandidates: [normalCandidate],
                recommendationFeedback: [normalFeedback]
            )
        )
        try require(repairedRelated.contexts[0].sourceSentence == "repaired", "Same-ID normal context should replace epoch damage")
        try require(repairedRelated.definitionCandidates[0].rawText == "repaired", "Same-ID normal candidate should replace epoch damage")
        try require(repairedRelated.recommendationFeedback[0].action == "known", "Same-ID normal feedback should replace epoch damage")
        var collidingContext = normalContext
        collidingContext.sourceSentence = "mutated"
        collidingContext.createdAt = Date(timeIntervalSince1970: 900)
        var collidingFeedback = normalFeedback
        collidingFeedback.action = "dismissed"
        collidingFeedback.createdAt = Date(timeIntervalSince1970: 900)
        let collisionProtectedRelated = SyncBatchFactory.merge(
            local: snapshot(contexts: [normalContext], recommendationFeedback: [normalFeedback]),
            incoming: snapshot(contexts: [collidingContext], recommendationFeedback: [collidingFeedback])
        )
        try require(
            collisionProtectedRelated.contexts[0].sourceSentence == "repaired",
            "A normal same-ID context collision must keep the first payload"
        )
        try require(
            collisionProtectedRelated.recommendationFeedback[0].action == "known",
            "A normal same-ID feedback collision must keep the first payload"
        )

        let epochCreationPayload: [String: Any] = [
            "cards": [[
                "id": "epoch-created",
                "lemma": "epoch-created",
                "display_word": "epoch-created",
                "meaning_ko": "복구",
                "wordbook": "user",
                "status": "new",
                "due_at": "2026-07-12T03:04:05.123456Z",
                "interval_days": 0,
                "lapse_count": 0,
                "created_at": "1970-01-01T00:00:00Z",
                "updated_at": "2026-07-11T03:04:05.123456Z",
                "version": 2
            ]],
            "contexts": [[
                "id": "epoch-evidence",
                "card_id": "epoch-created",
                "source_sentence": "Evidence timestamp.",
                "capture_method": "android_process_text",
                "created_at": "2026-07-10T01:02:03.654321Z"
            ]],
            "definition_candidates": [],
            "review_events": [],
            "recommendation_feedback": [],
            "settings": [:]
        ]
        let epochCreationData = try JSONSerialization.data(withJSONObject: epochCreationPayload)
        let epochRepaired = try SyncBatchFactory.localSnapshot(from: epochCreationData).cards[0]
        try require(epochRepaired.createdAt > Date(timeIntervalSince1970: 1_700_000_000), "Related context should repair epoch card.createdAt")
        try require(epochRepaired.version == 3, "Creation-date repair should bump version for server propagation")
        try require(SyncBatchFactory.epochCreatedCardIds(in: epochCreationData) == ["epoch-created"], "Epoch server card IDs should be detectable for repair push")

        let outbound = SyncBatchFactory.build(
            cards: [],
            contexts: [],
            definitionCandidates: [],
            reviewEvents: [normalEvent],
            settings: UserSettingsData(),
            deviceId: "mac-test"
        )
        let outboundEvent = (outbound["review_events"] as! [[String: Any]])[0]
        try require(outboundEvent["server_created_at"] == nil, "Mac must not claim server-owned review event timestamps")

        var startedRecommendation = mergeTestCard(id: "started-rec", meaning: "시작됨", version: 2, updatedAt: tieTime)
        startedRecommendation.wordbook = .recommended
        startedRecommendation.recommendationStartedAt = tieTime
        let startedPayload = SyncBatchFactory.build(
            cards: [startedRecommendation],
            contexts: [],
            definitionCandidates: [],
            reviewEvents: [],
            settings: UserSettingsData(),
            deviceId: "mac-test"
        )
        let startedCardPayload = (startedPayload["cards"] as! [[String: Any]])[0]
        try require(startedCardPayload["recommendation_started_at"] as? String != nil, "Mac must sync recommendation_started_at to other devices")
    }

    private static func assertCloudBatchChunking() throws {
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

    @MainActor
    private static func assertDictionaryExtraction() throws {
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
        try require(DictionaryResolver.extractKoreanMeaning(from: newAceDefinition) == "되튀는; 탄력이 있는", "Mac New Ace dictionary should extract Korean meaning without POS prefix")
        let newAceResult = DictionaryResolver.resolve(analysis, sourceApp: "Self-check") { _ in newAceDefinition }
        try require(newAceResult.meaningKo == "되튀는; 탄력이 있는", "Mac dictionary resolver should prefer New Ace Korean meaning")

        let englishAnalysis = CaptureNormalizer.analyze("pervasive")
        let englishOnly = "pervasive per·va·sive | adjective spreading widely throughout an area or a group of people: ageism is pervasive and entrenched in our society"
        let englishResult = DictionaryResolver.resolve(englishAnalysis, sourceApp: "Self-check") { _ in englishOnly }
        try require(englishResult.meaningKo.isEmpty, "English-only Mac dictionary result should not invent Korean meaning")
        try require(englishResult.definitionEn == "spreading widely throughout an area or a group of people", "English-only Mac dictionary result should parse definitionEn")
        try require(englishResult.exampleSentence == "ageism is pervasive and entrenched in our society", "English-only Mac dictionary result should parse first example")
        try require(englishResult.partOfSpeech == "adjective", "English-only Mac dictionary result should parse part of speech")
    }

    @MainActor
    private static func assertMacStoreBackupRecovery() throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appending(path: "VocabReminderBackupSelfCheck-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let storeURL = tempDir.appending(path: "vocab-data.json")
        let backupURL = tempDir.appending(path: "vocab-data.json.bak")
        let card = VocabularyCard(
            id: "backup-card",
            lemma: "backup",
            displayWord: "backup",
            meaningKo: "백업",
            definitionEn: "a spare copy",
            partOfSpeech: "noun",
            exampleSentence: "Keep a backup.",
            definitionSourcePrimary: "test",
            status: .new,
            wordbook: .user,
            dueAt: Date(timeIntervalSince1970: 600),
            intervalDays: 0,
            lapseCount: 0,
            createdAt: Date(timeIntervalSince1970: 0),
            updatedAt: Date(timeIntervalSince1970: 0),
            version: 1
        )
        let snapshot = StoreSnapshot(
            cards: [card],
            contexts: [],
            definitionCandidates: [],
            reviewEvents: [],
            recommendationFeedback: [],
            settings: UserSettingsData()
        )
        try Data("{".utf8).write(to: storeURL)
        try JSONEncoder.vocab.encode(snapshot).write(to: backupURL)

        let store = LocalVocabularyStore(appSupportURL: tempDir, scheduleNotifications: false)
        try require(store.activeCards.map(\.id) == ["backup-card"], "Mac store should recover cards from backup")
        try require(store.message == "백업에서 복구했습니다", "Mac store should expose backup recovery message")

        let missingMainDir = tempDir.appending(path: "missing-main", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: missingMainDir, withIntermediateDirectories: true)
        let missingMainBackup = missingMainDir.appending(path: "vocab-data.json.bak")
        try JSONEncoder.vocab.encode(snapshot).write(to: missingMainBackup)
        let missingMainStore = LocalVocabularyStore(appSupportURL: missingMainDir, scheduleNotifications: false)
        try require(missingMainStore.activeCards.map(\.id) == ["backup-card"], "Mac store should recover when only the backup file exists")
        try require(
            FileManager.default.fileExists(atPath: missingMainDir.appending(path: "vocab-data.json").path),
            "Backup-only recovery should restore the main store file"
        )
    }

    private static func assertMacSettingsFallback() throws {
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
    private static func assertMacNotificationReviewAction(now: Date) throws {
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
    private static func assertMacNotificationStateMachine(now: Date) throws {
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

    private static func assertDueTimeJitter() throws {
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

    private static func mergeTestCard(
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

    private static func mergeTestEvent(
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

    private static func snapshot(
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
            if let displayWord = then["display_word"] as? String {
                try require(analysis.displayWord == displayWord, "\(name): display_word \(analysis.displayWord)")
            }
            if let lemma = then["lemma"] as? String {
                try require(analysis.lemma == lemma, "\(name): lemma \(analysis.lemma)")
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
