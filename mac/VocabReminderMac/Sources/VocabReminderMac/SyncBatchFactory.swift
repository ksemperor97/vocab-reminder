import Foundation

enum SyncBatchFactory {
    static func build(
        cards: [VocabularyCard],
        contexts: [StoredContext],
        definitionCandidates: [StoredDefinitionCandidate],
        reviewEvents: [StoredReviewEvent],
        recommendationFeedback: [RecommendationFeedback] = [],
        settings: UserSettingsData
    ) -> [String: Any] {
        [
            "cards": cards.map(card),
            "contexts": contexts.map(context),
            "definition_candidates": definitionCandidates.map(candidate),
            "review_events": reviewEvents.map(reviewEvent),
            "recommendation_feedback": recommendationFeedback.map(feedback),
            "settings": settingsPayload(settings)
        ]
    }

    static func jsonData(
        cards: [VocabularyCard],
        contexts: [StoredContext],
        definitionCandidates: [StoredDefinitionCandidate],
        reviewEvents: [StoredReviewEvent],
        recommendationFeedback: [RecommendationFeedback] = [],
        settings: UserSettingsData
    ) throws -> Data {
        let payload = build(
            cards: cards,
            contexts: contexts,
            definitionCandidates: definitionCandidates,
            reviewEvents: reviewEvents,
            recommendationFeedback: recommendationFeedback,
            settings: settings
        )
        return try JSONSerialization.data(withJSONObject: payload, options: [.prettyPrinted, .sortedKeys])
    }

    static func localSnapshot(from data: Data) throws -> StoreSnapshot {
        if let snapshot = try? JSONDecoder.vocab.decode(StoreSnapshot.self, from: data) {
            return canonicalizeDuplicateLemmas(snapshot)
        }
        guard let payload = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ImportError.invalidPayload
        }

        return canonicalizeDuplicateLemmas(StoreSnapshot(
            cards: array(payload["cards"]).map(cardPayload),
            contexts: array(payload["contexts"]).map(contextPayload),
            definitionCandidates: array(payload["definition_candidates"]).map(candidatePayload),
            reviewEvents: array(payload["review_events"]).map(reviewEventPayload),
            recommendationFeedback: array(payload["recommendation_feedback"]).map(feedbackPayload),
            settings: settingsPayload(payload["settings"] as? [String: Any] ?? [:])
        ))
    }

    private static func card(_ card: VocabularyCard) -> [String: Any] {
        [
            "id": card.id,
            "lemma": card.lemma,
            "display_word": card.displayWord,
            "language": "en",
            "wordbook": card.wordbook.rawValue,
            "meaning_ko": card.meaningKo,
            "definition_en": card.definitionEn,
            "part_of_speech": nullable(card.partOfSpeech),
            "phonetic": NSNull(),
            "audio_url": NSNull(),
            "source_sentence_primary": nullable(card.exampleSentence),
            "source_url_primary": NSNull(),
            "source_app_primary": NSNull(),
            "definition_source_primary": card.definitionSourcePrimary,
            "status": card.status.rawValue,
            "due_at": iso(card.dueAt),
            "interval_days": card.intervalDays,
            "ease": 2.5,
            "stability": NSNull(),
            "difficulty": NSNull(),
            "lapse_count": card.lapseCount,
            "interval_before_lapse": card.intervalBeforeLapse.map { $0 as Any } ?? NSNull(),
            "deleted_at": card.deletedAt.map { iso($0) as Any } ?? NSNull(),
            "created_device_id": "mac-local",
            "created_at": iso(card.createdAt),
            "updated_at": iso(card.updatedAt),
            "version": card.version
        ]
    }

    private static func context(_ context: StoredContext) -> [String: Any] {
        [
            "id": context.id,
            "card_id": context.cardId,
            "source_sentence": context.sourceSentence,
            "surrounding_text": NSNull(),
            "source_url": NSNull(),
            "page_title": NSNull(),
            "source_app": nullable(context.sourceApp),
            "capture_method": context.captureMethod.rawValue,
            "created_device_id": "mac-local",
            "created_at": iso(context.createdAt)
        ]
    }

    private static func candidate(_ candidate: StoredDefinitionCandidate) -> [String: Any] {
        [
            "id": candidate.id,
            "card_id": candidate.cardId,
            "source": candidate.source,
            "raw_text": candidate.rawText,
            "parsed_meaning_ko": nullable(candidate.parsedMeaningKo),
            "parsed_definition_en": nullable(candidate.parsedDefinitionEn),
            "part_of_speech": nullable(candidate.partOfSpeech),
            "phonetic": NSNull(),
            "example_sentence": nullable(candidate.exampleSentence),
            "confidence": candidate.confidence,
            "selected_as_primary": candidate.selectedAsPrimary,
            "created_at": iso(candidate.createdAt)
        ]
    }

    private static func reviewEvent(_ event: StoredReviewEvent) -> [String: Any] {
        [
            "id": event.id,
            "card_id": event.cardId,
            "device_id": "mac-local",
            "response": event.response.rawValue,
            "previous_status": event.previousStatus.rawValue,
            "next_status": event.nextStatus.rawValue,
            "previous_due_at": iso(event.previousDueAt),
            "next_due_at": iso(event.nextDueAt),
            "previous_interval_days": event.previousIntervalDays,
            "next_interval_days": event.nextIntervalDays,
            "client_created_at": iso(event.clientCreatedAt),
            "server_created_at": iso(event.clientCreatedAt),
            "metadata": event.metadata
        ]
    }

    private static func feedback(_ feedback: RecommendationFeedback) -> [String: Any] {
        [
            "id": feedback.id,
            "card_id": feedback.cardId,
            "lemma": feedback.lemma,
            "action": feedback.action,
            "created_at": iso(feedback.createdAt)
        ]
    }

    private static func settingsPayload(_ settings: UserSettingsData) -> [String: Any] {
        [
            "max_queue_size": settings.maxQueueSize,
            "snooze_duration_minutes": settings.snoozeDurationMinutes,
            "daily_word_goal": settings.dailyWordGoal,
            "quiet_hours_start": settings.quietHoursStart,
            "quiet_hours_end": settings.quietHoursEnd,
            "timezone": settings.timezone,
            "enable_android_notifications": true,
            "enable_mac_notifications": true,
            "show_meaning_on_lockscreen": false,
            "show_meaning_after_remembered": true,
            "auto_generate_korean_meaning": true,
            "save_source_url": true,
            "save_surrounding_text": false,
            "enable_word_recommendations": settings.enableWordRecommendations,
            "recommendations_introduced_at": settings.recommendationsIntroducedAt.map { iso($0) as Any } ?? NSNull()
        ]
    }

    private static func nullable(_ value: String) -> Any {
        value.isEmpty ? NSNull() : value
    }

    private static func iso(_ date: Date) -> String {
        ISO8601DateFormatter().string(from: date)
    }

    private static func array(_ value: Any?) -> [[String: Any]] {
        value as? [[String: Any]] ?? []
    }

    private static func cardPayload(_ payload: [String: Any]) -> VocabularyCard {
        VocabularyCard(
            id: string(payload, "id"),
            lemma: string(payload, "lemma"),
            displayWord: string(payload, "display_word", fallback: string(payload, "lemma")),
            meaningKo: string(payload, "meaning_ko"),
            definitionEn: string(payload, "definition_en"),
            partOfSpeech: string(payload, "part_of_speech"),
            exampleSentence: string(payload, "source_sentence_primary"),
            definitionSourcePrimary: string(payload, "definition_source_primary", fallback: "unknown"),
            status: CardStatus(rawValue: string(payload, "status", fallback: "new")) ?? .new,
            wordbook: Wordbook(rawValue: string(payload, "wordbook", fallback: "user")) ?? .user,
            dueAt: date(payload, "due_at"),
            intervalDays: int(payload, "interval_days"),
            lapseCount: int(payload, "lapse_count"),
            intervalBeforeLapse: optionalInt(payload, "interval_before_lapse"),
            recommendationStartedAt: optionalDate(payload, "recommendation_started_at"),
            deletedAt: optionalDate(payload, "deleted_at"),
            createdAt: date(payload, "created_at"),
            updatedAt: date(payload, "updated_at"),
            version: int(payload, "version", fallback: 1)
        )
    }

    private static func contextPayload(_ payload: [String: Any]) -> StoredContext {
        StoredContext(
            id: string(payload, "id"),
            cardId: string(payload, "card_id"),
            sourceSentence: string(payload, "source_sentence"),
            sourceApp: string(payload, "source_app"),
            captureMethod: CaptureMethod(rawValue: string(payload, "capture_method", fallback: "manual")) ?? .manual,
            createdAt: date(payload, "created_at")
        )
    }

    private static func candidatePayload(_ payload: [String: Any]) -> StoredDefinitionCandidate {
        StoredDefinitionCandidate(
            id: string(payload, "id"),
            cardId: string(payload, "card_id"),
            source: string(payload, "source"),
            rawText: string(payload, "raw_text"),
            parsedMeaningKo: string(payload, "parsed_meaning_ko"),
            parsedDefinitionEn: string(payload, "parsed_definition_en"),
            partOfSpeech: string(payload, "part_of_speech"),
            exampleSentence: string(payload, "example_sentence"),
            confidence: double(payload, "confidence"),
            selectedAsPrimary: bool(payload, "selected_as_primary"),
            createdAt: date(payload, "created_at")
        )
    }

    private static func reviewEventPayload(_ payload: [String: Any]) -> StoredReviewEvent {
        StoredReviewEvent(
            id: string(payload, "id"),
            cardId: string(payload, "card_id"),
            response: ReviewResponse(rawValue: string(payload, "response", fallback: "unavailable")) ?? .unavailable,
            previousStatus: CardStatus(rawValue: string(payload, "previous_status", fallback: "new")) ?? .new,
            nextStatus: CardStatus(rawValue: string(payload, "next_status", fallback: "new")) ?? .new,
            previousDueAt: date(payload, "previous_due_at"),
            nextDueAt: date(payload, "next_due_at"),
            previousIntervalDays: int(payload, "previous_interval_days"),
            nextIntervalDays: int(payload, "next_interval_days"),
            clientCreatedAt: date(payload, "client_created_at"),
            metadata: payload["metadata"] as? [String: String] ?? [:]
        )
    }

    private static func feedbackPayload(_ payload: [String: Any]) -> RecommendationFeedback {
        RecommendationFeedback(
            id: string(payload, "id"),
            cardId: string(payload, "card_id"),
            lemma: string(payload, "lemma"),
            action: string(payload, "action"),
            createdAt: date(payload, "created_at")
        )
    }

    private static func settingsPayload(_ payload: [String: Any]) -> UserSettingsData {
        UserSettingsData(
            dailyWordGoal: int(payload, "daily_word_goal", fallback: 10),
            maxQueueSize: int(payload, "max_queue_size", fallback: 20),
            snoozeDurationMinutes: int(payload, "snooze_duration_minutes", fallback: 60),
            quietHoursStart: string(payload, "quiet_hours_start", fallback: "23:00"),
            quietHoursEnd: string(payload, "quiet_hours_end", fallback: "09:00"),
            timezone: string(payload, "timezone", fallback: "Asia/Seoul"),
            enableWordRecommendations: bool(payload, "enable_word_recommendations", fallback: true),
            recommendationsIntroducedAt: optionalDate(payload, "recommendations_introduced_at")
        )
    }

    private static func string(_ payload: [String: Any], _ key: String, fallback: String = "") -> String {
        guard let value = payload[key], !(value is NSNull) else { return fallback }
        return value as? String ?? fallback
    }

    private static func int(_ payload: [String: Any], _ key: String, fallback: Int = 0) -> Int {
        guard let value = payload[key], !(value is NSNull) else { return fallback }
        return value as? Int ?? (value as? NSNumber)?.intValue ?? fallback
    }

    private static func optionalInt(_ payload: [String: Any], _ key: String) -> Int? {
        guard let value = payload[key], !(value is NSNull) else { return nil }
        return value as? Int ?? (value as? NSNumber)?.intValue
    }

    private static func double(_ payload: [String: Any], _ key: String, fallback: Double = 0) -> Double {
        guard let value = payload[key], !(value is NSNull) else { return fallback }
        return value as? Double ?? (value as? NSNumber)?.doubleValue ?? fallback
    }

    private static func bool(_ payload: [String: Any], _ key: String, fallback: Bool = false) -> Bool {
        guard let value = payload[key], !(value is NSNull) else { return fallback }
        return value as? Bool ?? (value as? NSNumber)?.boolValue ?? fallback
    }

    private static func date(_ payload: [String: Any], _ key: String) -> Date {
        optionalDate(payload, key) ?? Date(timeIntervalSince1970: 0)
    }

    private static func optionalDate(_ payload: [String: Any], _ key: String) -> Date? {
        guard let value = payload[key], !(value is NSNull) else { return nil }
        guard let raw = value as? String else { return nil }
        return ISO8601DateFormatter().date(from: raw)
    }

    private static func canonicalizeDuplicateLemmas(_ snapshot: StoreSnapshot) -> StoreSnapshot {
        var cardIdMap: [String: String] = [:]
        var duplicateIds = Set<String>()
        let activeByLemma = Dictionary(grouping: snapshot.cards.filter { card in
            card.deletedAt == nil && card.lemma.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
        }) { card in
            card.lemma.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        }

        for duplicates in activeByLemma.values where duplicates.count > 1 {
            guard let canonical = duplicates.max(by: lowerPriority) else { continue }
            for duplicate in duplicates where duplicate.id != canonical.id {
                cardIdMap[duplicate.id] = canonical.id
                duplicateIds.insert(duplicate.id)
            }
        }
        guard cardIdMap.isEmpty == false else { return snapshot }

        var normalized = snapshot
        normalized.cards = snapshot.cards.filter { duplicateIds.contains($0.id) == false }
        normalized.contexts = snapshot.contexts.map { context in
            var copy = context
            copy.cardId = cardIdMap[context.cardId] ?? context.cardId
            return copy
        }
        normalized.definitionCandidates = snapshot.definitionCandidates.map { candidate in
            var copy = candidate
            copy.cardId = cardIdMap[candidate.cardId] ?? candidate.cardId
            return copy
        }
        normalized.reviewEvents = snapshot.reviewEvents.map { event in
            var copy = event
            copy.cardId = cardIdMap[event.cardId] ?? event.cardId
            return copy
        }
        normalized.recommendationFeedback = snapshot.recommendationFeedback.map { feedback in
            var copy = feedback
            copy.cardId = cardIdMap[feedback.cardId] ?? feedback.cardId
            return copy
        }
        return normalized
    }

    private static func lowerPriority(_ lhs: VocabularyCard, _ rhs: VocabularyCard) -> Bool {
        if lhs.wordbook != rhs.wordbook {
            return lhs.wordbook != .user && rhs.wordbook == .user
        }
        if lhs.meaningKo.isEmpty != rhs.meaningKo.isEmpty {
            return lhs.meaningKo.isEmpty && rhs.meaningKo.isEmpty == false
        }
        if (lhs.recommendationStartedAt != nil) != (rhs.recommendationStartedAt != nil) {
            return lhs.recommendationStartedAt == nil && rhs.recommendationStartedAt != nil
        }
        let lhsStatus = statusProgressRank(lhs.status)
        let rhsStatus = statusProgressRank(rhs.status)
        if lhsStatus != rhsStatus {
            return lhsStatus < rhsStatus
        }
        if lhs.intervalDays != rhs.intervalDays {
            return lhs.intervalDays < rhs.intervalDays
        }
        if lhs.version != rhs.version {
            return lhs.version < rhs.version
        }
        return lhs.updatedAt < rhs.updatedAt
    }

    private static func statusProgressRank(_ status: CardStatus) -> Int {
        switch status {
        case .new:
            return 0
        case .learning:
            return 1
        case .relearning:
            return 2
        case .review:
            return 3
        case .mastered:
            return 4
        case .suspended:
            return 1
        }
    }
}

enum ImportError: Error {
    case invalidPayload
}
