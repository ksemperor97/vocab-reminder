import Foundation

enum SyncBatchFactory {
    static func build(
        cards: [VocabularyCard],
        contexts: [StoredContext],
        definitionCandidates: [StoredDefinitionCandidate],
        reviewEvents: [StoredReviewEvent],
        recommendationFeedback: [RecommendationFeedback] = [],
        settings: UserSettingsData,
        deviceId: String = SyncBatchFactory.deviceId()
    ) -> [String: Any] {
        [
            "cards": cards.map { card($0, deviceId: deviceId) },
            "contexts": contexts.map { context($0, deviceId: deviceId) },
            "definition_candidates": definitionCandidates.map(candidate),
            "review_events": reviewEvents.map { reviewEvent($0, deviceId: deviceId) },
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
        settings: UserSettingsData,
        deviceId: String = SyncBatchFactory.deviceId()
    ) throws -> Data {
        let payload = build(
            cards: cards,
            contexts: contexts,
            definitionCandidates: definitionCandidates,
            reviewEvents: reviewEvents,
            recommendationFeedback: recommendationFeedback,
            settings: settings,
            deviceId: deviceId
        )
        return try JSONSerialization.data(withJSONObject: payload, options: [.prettyPrinted, .sortedKeys])
    }

    static func localSnapshot(from data: Data) throws -> StoreSnapshot {
        if let snapshot = try? JSONDecoder.vocab.decode(StoreSnapshot.self, from: data) {
            return normalize(snapshot)
        }
        guard let payload = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ImportError.invalidPayload
        }

        return normalize(StoreSnapshot(
            cards: try array(payload["cards"]).map(cardPayload),
            contexts: try array(payload["contexts"]).map(contextPayload),
            definitionCandidates: try array(payload["definition_candidates"]).map(candidatePayload),
            reviewEvents: try array(payload["review_events"]).map(reviewEventPayload),
            recommendationFeedback: try array(payload["recommendation_feedback"]).map(feedbackPayload),
            settings: try settingsPayload(payload["settings"] as? [String: Any] ?? [:])
        ))
    }

    static func merge(local: StoreSnapshot, incoming: StoreSnapshot) -> StoreSnapshot {
        normalize(StoreSnapshot(
            cards: mergeCards(local.cards, incoming.cards),
            contexts: mergeByIdRepairingTimestamp(local.contexts, incoming.contexts, timestamp: \.createdAt),
            definitionCandidates: mergeDefinitionCandidates(local.definitionCandidates, incoming.definitionCandidates),
            reviewEvents: mergeByIdRepairingTimestamp(local.reviewEvents, incoming.reviewEvents, timestamp: \.clientCreatedAt)
                .sorted(by: reviewEventChronologicalOrder),
            syncEvents: mergeByIdRepairingTimestamp(
                local.syncEvents,
                incoming.syncEvents,
                timestamp: \.createdAt
            ),
            recommendationFeedback: mergeByIdRepairingTimestamp(local.recommendationFeedback, incoming.recommendationFeedback, timestamp: \.createdAt),
            settings: mergeSettings(local.settings, incoming.settings)
        ))
    }

    static func deviceId(defaults: UserDefaults = .standard) -> String {
        let key = "deviceId"
        if let existing = defaults.string(forKey: key), existing.range(of: #"^mac-[0-9a-fA-F]{8}$"#, options: .regularExpression) != nil {
            return existing
        }
        let generated = "mac-\(UUID().uuidString.prefix(8).lowercased())"
        defaults.set(generated, forKey: key)
        return generated
    }

    private static func card(_ card: VocabularyCard, deviceId: String) -> [String: Any] {
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
            "recommendation_started_at": card.recommendationStartedAt.map { iso($0) as Any } ?? NSNull(),
            "deleted_at": card.deletedAt.map { iso($0) as Any } ?? NSNull(),
            "created_device_id": deviceId,
            "created_at": iso(card.createdAt),
            "updated_at": iso(card.updatedAt),
            "version": card.version
        ]
    }

    private static func context(_ context: StoredContext, deviceId: String) -> [String: Any] {
        [
            "id": context.id,
            "card_id": context.cardId,
            "source_sentence": context.sourceSentence,
            "surrounding_text": NSNull(),
            "source_url": nullable(context.sourceURL),
            "page_title": nullable(context.pageTitle),
            "source_app": nullable(context.sourceApp),
            "capture_method": context.captureMethod.rawValue,
            "created_device_id": deviceId,
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
            "origin_device_id": candidate.originDeviceId.map { $0 as Any } ?? NSNull(),
            "base_card_version": candidate.baseCardVersion.map { $0 as Any } ?? NSNull(),
            "created_at": iso(candidate.createdAt)
        ]
    }

    private static func reviewEvent(_ event: StoredReviewEvent, deviceId: String) -> [String: Any] {
        [
            "id": event.id,
            "card_id": event.cardId,
            "device_id": deviceId,
            "response": event.response.rawValue,
            "previous_status": event.previousStatus.rawValue,
            "next_status": event.nextStatus.rawValue,
            "previous_due_at": iso(event.previousDueAt),
            "next_due_at": iso(event.nextDueAt),
            "previous_interval_days": event.previousIntervalDays,
            "next_interval_days": event.nextIntervalDays,
            "client_created_at": iso(event.clientCreatedAt),
            "metadata": event.metadata
        ]
    }

    static func epochCreatedCardIds(in data: Data) -> Set<String> {
        guard let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [] }
        return Set(array(payload["cards"]).compactMap { card in
            guard let id = card["id"] as? String,
                  let raw = card["created_at"] as? String,
                  let date = parseISO8601(raw),
                  date <= Date(timeIntervalSince1970: 1) else { return nil }
            return id
        })
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
            "recommendations_introduced_at": settings.recommendationsIntroducedAt.map { iso($0) as Any } ?? NSNull(),
            "settings_updated_at": iso(settings.updatedAt)
        ]
    }

    private static func nullable(_ value: String) -> Any {
        value.isEmpty ? NSNull() : value
    }

    private static func nullable(_ value: String?) -> Any {
        guard let value else { return NSNull() }
        return nullable(value)
    }

    private static func mergeCards(_ local: [VocabularyCard], _ incoming: [VocabularyCard]) -> [VocabularyCard] {
        var byId = Dictionary(uniqueKeysWithValues: local.map { ($0.id, $0) })
        for card in incoming {
            guard let existing = byId[card.id] else {
                byId[card.id] = card
                continue
            }
            byId[card.id] = preferredCard(existing, card)
        }
        return byId.values.sorted { $0.id < $1.id }
    }

    private static func mergeById<T: Identifiable>(_ local: [T], _ incoming: [T]) -> [T] where T.ID == String {
        var byId = Dictionary(uniqueKeysWithValues: local.map { ($0.id, $0) })
        for item in incoming where byId[item.id] == nil {
            byId[item.id] = item
        }
        return byId.values.sorted { $0.id < $1.id }
    }

    private static func mergeByIdRepairingTimestamp<T: Identifiable>(
        _ local: [T],
        _ incoming: [T],
        timestamp: (T) -> Date
    ) -> [T] where T.ID == String {
        var byId = Dictionary(uniqueKeysWithValues: local.map { ($0.id, $0) })
        let epochDamageUpperBound = Date(timeIntervalSince1970: 1)
        for item in incoming {
            guard let existing = byId[item.id] else {
                byId[item.id] = item
                continue
            }
            // Earlier Mac imports converted Android fractional timestamps to
            // epoch. A same-ID server record with its real timestamp repairs
            // that damage on the next sync instead of being ignored forever.
            if timestamp(existing) <= epochDamageUpperBound,
               timestamp(item) > epochDamageUpperBound {
                byId[item.id] = item
            }
        }
        return byId.values.sorted { $0.id < $1.id }
    }

    private static func mergeDefinitionCandidates(
        _ local: [StoredDefinitionCandidate],
        _ incoming: [StoredDefinitionCandidate]
    ) -> [StoredDefinitionCandidate] {
        var byId = Dictionary(uniqueKeysWithValues: local.map { ($0.id, $0) })
        let epochDamageUpperBound = Date(timeIntervalSince1970: 1)
        for candidate in incoming {
            guard let existing = byId[candidate.id] else {
                byId[candidate.id] = candidate
                continue
            }
            // Candidate IDs are append-only event identities. Never mutate a
            // normal record on collision; retain only the one-time legacy epoch
            // repair that predates append-only enforcement.
            if existing.createdAt <= epochDamageUpperBound,
               candidate.createdAt > epochDamageUpperBound {
                byId[candidate.id] = candidate
            }
        }
        return byId.values.sorted { $0.id < $1.id }
    }

    private static func preferredCard(_ first: VocabularyCard, _ second: VocabularyCard) -> VocabularyCard {
        if first.version != second.version {
            return first.version > second.version ? first : second
        }
        if first.updatedAt != second.updatedAt {
            return first.updatedAt > second.updatedAt ? first : second
        }

        // A delete is a state transition, not missing data. If two devices emit
        // the same version/timestamp, keeping the tombstone prevents resurrection.
        if (first.deletedAt != nil) != (second.deletedAt != nil) {
            return first.deletedAt != nil ? first : second
        }
        if let firstDeletedAt = first.deletedAt,
           let secondDeletedAt = second.deletedAt,
           firstDeletedAt != secondDeletedAt {
            return firstDeletedAt > secondDeletedAt ? first : second
        }

        // The remaining exact-version tie must not depend on pull direction.
        // This stable key makes merge(a, b) and merge(b, a) choose the same value.
        return deterministicCardKey(first) >= deterministicCardKey(second) ? first : second
    }

    private static func deterministicCardKey(_ card: VocabularyCard) -> String {
        [
            card.lemma,
            card.displayWord,
            card.meaningKo,
            card.definitionEn,
            card.partOfSpeech,
            card.exampleSentence,
            card.definitionSourcePrimary,
            card.status.rawValue,
            card.wordbook.rawValue,
            epochMillis(card.dueAt),
            String(card.intervalDays),
            String(card.lapseCount),
            card.intervalBeforeLapse.map(String.init) ?? "",
            card.recommendationStartedAt.map(epochMillis) ?? "",
            epochMillis(card.createdAt)
        ].joined(separator: "\u{1f}")
    }

    private static func epochMillis(_ date: Date) -> String {
        String(Int64((date.timeIntervalSince1970 * 1_000).rounded(.towardZero)))
    }

    private static func reviewEventChronologicalOrder(_ left: StoredReviewEvent, _ right: StoredReviewEvent) -> Bool {
        if left.clientCreatedAt != right.clientCreatedAt {
            return left.clientCreatedAt < right.clientCreatedAt
        }
        return left.id < right.id
    }

    private static func mergeSettings(_ local: UserSettingsData, _ incoming: UserSettingsData) -> UserSettingsData {
        if incoming.updatedAt > local.updatedAt {
            return incoming
        }
        if incoming.updatedAt == Date(timeIntervalSince1970: 0), local.updatedAt == Date(timeIntervalSince1970: 0) {
            return incoming
        }
        return local
    }

    private static func iso(_ date: Date) -> String {
        ISO8601DateFormatter().string(from: date)
    }

    private static func array(_ value: Any?) -> [[String: Any]] {
        value as? [[String: Any]] ?? []
    }

    private static func cardPayload(_ payload: [String: Any]) throws -> VocabularyCard {
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
            dueAt: try requiredDate(payload, "due_at"),
            intervalDays: int(payload, "interval_days"),
            lapseCount: int(payload, "lapse_count"),
            intervalBeforeLapse: optionalInt(payload, "interval_before_lapse"),
            recommendationStartedAt: try optionalDate(payload, "recommendation_started_at"),
            deletedAt: try optionalDate(payload, "deleted_at"),
            createdAt: try requiredDate(payload, "created_at"),
            updatedAt: try requiredDate(payload, "updated_at"),
            version: int(payload, "version", fallback: 1)
        )
    }

    private static func contextPayload(_ payload: [String: Any]) throws -> StoredContext {
        StoredContext(
            id: string(payload, "id"),
            cardId: string(payload, "card_id"),
            sourceSentence: string(payload, "source_sentence"),
            sourceURL: SourceURLSanitizer.sanitize(string(payload, "source_url")),
            pageTitle: string(payload, "page_title"),
            sourceApp: string(payload, "source_app"),
            captureMethod: CaptureMethod(rawValue: string(payload, "capture_method", fallback: "manual")) ?? .manual,
            createdAt: try requiredDate(payload, "created_at")
        )
    }

    private static func candidatePayload(_ payload: [String: Any]) throws -> StoredDefinitionCandidate {
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
            createdAt: try requiredDate(payload, "created_at"),
            originDeviceId: optionalString(payload, "origin_device_id"),
            baseCardVersion: optionalInt(payload, "base_card_version").flatMap { $0 > 0 ? $0 : nil }
        )
    }

    private static func reviewEventPayload(_ payload: [String: Any]) throws -> StoredReviewEvent {
        StoredReviewEvent(
            id: string(payload, "id"),
            cardId: string(payload, "card_id"),
            response: ReviewResponse(rawValue: string(payload, "response", fallback: "unavailable")) ?? .unavailable,
            previousStatus: CardStatus(rawValue: string(payload, "previous_status", fallback: "new")) ?? .new,
            nextStatus: CardStatus(rawValue: string(payload, "next_status", fallback: "new")) ?? .new,
            previousDueAt: try requiredDate(payload, "previous_due_at"),
            nextDueAt: try requiredDate(payload, "next_due_at"),
            previousIntervalDays: int(payload, "previous_interval_days"),
            nextIntervalDays: int(payload, "next_interval_days"),
            clientCreatedAt: try requiredDate(payload, "client_created_at"),
            metadata: payload["metadata"] as? [String: String] ?? [:]
        )
    }

    private static func feedbackPayload(_ payload: [String: Any]) throws -> RecommendationFeedback {
        RecommendationFeedback(
            id: string(payload, "id"),
            cardId: string(payload, "card_id"),
            lemma: string(payload, "lemma"),
            action: string(payload, "action"),
            createdAt: try requiredDate(payload, "created_at")
        )
    }

    private static func settingsPayload(_ payload: [String: Any]) throws -> UserSettingsData {
        let quietStart = validHHMM(string(payload, "quiet_hours_start", fallback: "23:00")) ? string(payload, "quiet_hours_start", fallback: "23:00") : "23:00"
        let quietEnd = validHHMM(string(payload, "quiet_hours_end", fallback: "09:00")) ? string(payload, "quiet_hours_end", fallback: "09:00") : "09:00"
        let timezone = TimeZone(identifier: string(payload, "timezone", fallback: "Asia/Seoul")) != nil ? string(payload, "timezone", fallback: "Asia/Seoul") : "Asia/Seoul"
        return UserSettingsData(
            dailyWordGoal: int(payload, "daily_word_goal", fallback: 10),
            maxQueueSize: int(payload, "max_queue_size", fallback: 20),
            snoozeDurationMinutes: int(payload, "snooze_duration_minutes", fallback: 60),
            quietHoursStart: quietStart,
            quietHoursEnd: quietEnd,
            timezone: timezone,
            enableWordRecommendations: bool(payload, "enable_word_recommendations", fallback: true),
            recommendationsIntroducedAt: try optionalDate(payload, "recommendations_introduced_at"),
            updatedAt: try optionalDate(payload, "settings_updated_at") ?? Date(timeIntervalSince1970: 0)
        )
    }

    private static func string(_ payload: [String: Any], _ key: String, fallback: String = "") -> String {
        guard let value = payload[key], !(value is NSNull) else { return fallback }
        return value as? String ?? fallback
    }

    private static func optionalString(_ payload: [String: Any], _ key: String) -> String? {
        let value = string(payload, key).trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
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

    private static func validHHMM(_ value: String) -> Bool {
        let parts = value.split(separator: ":")
        guard parts.count == 2,
              let hour = Int(parts[0]),
              let minute = Int(parts[1]),
              parts[0].count == 2,
              parts[1].count == 2 else {
            return false
        }
        return (0...23).contains(hour) && (0...59).contains(minute)
    }

    private static func requiredDate(_ payload: [String: Any], _ key: String) throws -> Date {
        guard let parsed = try optionalDate(payload, key) else {
            throw ImportError.missingDate(key)
        }
        return parsed
    }

    private static func optionalDate(_ payload: [String: Any], _ key: String) throws -> Date? {
        guard let value = payload[key], !(value is NSNull) else { return nil }
        guard let raw = value as? String else {
            throw ImportError.invalidDate(key: key, value: String(describing: value))
        }
        guard let parsed = parseISO8601(raw) else {
            throw ImportError.invalidDate(key: key, value: raw)
        }
        return parsed
    }

    private static func parseISO8601(_ raw: String) -> Date? {
        // Android/Java commonly emits 3, 6, or 9 fractional digits while the
        // Foundation default formatter only accepts the non-fractional form.
        if raw.contains(".") {
            let fractional = ISO8601DateFormatter()
            fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            if let parsed = fractional.date(from: raw) { return parsed }
        }
        let wholeSeconds = ISO8601DateFormatter()
        wholeSeconds.formatOptions = [.withInternetDateTime]
        return wholeSeconds.date(from: raw)
    }

    private static func normalize(_ snapshot: StoreSnapshot) -> StoreSnapshot {
        repairEpochCardCreationDates(canonicalizeDuplicateLemmas(snapshot))
    }

    private static func repairEpochCardCreationDates(_ snapshot: StoreSnapshot) -> StoreSnapshot {
        let epochDamageUpperBound = Date(timeIntervalSince1970: 1)
        var evidenceByCardId: [String: [Date]] = [:]
        for context in snapshot.contexts where context.createdAt > epochDamageUpperBound {
            evidenceByCardId[context.cardId, default: []].append(context.createdAt)
        }
        for candidate in snapshot.definitionCandidates where candidate.createdAt > epochDamageUpperBound {
            evidenceByCardId[candidate.cardId, default: []].append(candidate.createdAt)
        }
        for event in snapshot.reviewEvents where event.clientCreatedAt > epochDamageUpperBound {
            evidenceByCardId[event.cardId, default: []].append(event.clientCreatedAt)
        }
        for feedback in snapshot.recommendationFeedback where feedback.createdAt > epochDamageUpperBound {
            evidenceByCardId[feedback.cardId, default: []].append(feedback.createdAt)
        }

        var repaired = snapshot
        repaired.cards = snapshot.cards.map { card in
            guard card.createdAt <= epochDamageUpperBound,
                  let evidence = evidenceByCardId[card.id]?.min() else { return card }
            var updated = card
            updated.createdAt = evidence
            // Bump progress metadata once so the repaired creation date is sent
            // back to the server instead of losing to the same-ID epoch record.
            updated.updatedAt = max(updated.updatedAt, evidence)
            updated.version += 1
            return updated
        }
        return repaired
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
            guard let canonical = duplicates.max(by: lowerIdentityPriority) else { continue }
            for duplicate in duplicates where duplicate.id != canonical.id {
                cardIdMap[duplicate.id] = canonical.id
                duplicateIds.insert(duplicate.id)
            }
        }
        guard cardIdMap.isEmpty == false else { return snapshot }

        var normalized = snapshot
        let mergedById = Dictionary(
            uniqueKeysWithValues: activeByLemma.values.compactMap { duplicates -> (String, VocabularyCard)? in
                guard duplicates.count > 1, let canonical = duplicates.max(by: lowerIdentityPriority) else { return nil }
                return (canonical.id, mergeDuplicateCards(canonical: canonical, duplicates: duplicates))
            }
        )
        normalized.cards = snapshot.cards
            .filter { duplicateIds.contains($0.id) == false }
            .map { mergedById[$0.id] ?? $0 }
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

    private static func mergeDuplicateCards(canonical: VocabularyCard, duplicates: [VocabularyCard]) -> VocabularyCard {
        var merged = canonical
        if let content = duplicates.max(by: lowerContentPriority),
           merged.meaningKo.isEmpty,
           content.meaningKo.isEmpty == false {
            merged.meaningKo = content.meaningKo
            merged.definitionEn = content.definitionEn
            merged.partOfSpeech = content.partOfSpeech
            merged.exampleSentence = merged.exampleSentence.isEmpty ? content.exampleSentence : merged.exampleSentence
            merged.definitionSourcePrimary = content.definitionSourcePrimary
        }
        if let progress = duplicates.max(by: lowerProgressPriority) {
            merged.status = progress.status
            merged.dueAt = progress.dueAt
            merged.intervalDays = progress.intervalDays
            merged.lapseCount = progress.lapseCount
            merged.intervalBeforeLapse = progress.intervalBeforeLapse
            merged.recommendationStartedAt = progress.recommendationStartedAt ?? merged.recommendationStartedAt
        }
        if duplicates.contains(where: { $0.wordbook == .user }) {
            merged.wordbook = .user
        }
        merged.updatedAt = duplicates.map(\.updatedAt).max() ?? merged.updatedAt
        merged.version = duplicates.map(\.version).max() ?? merged.version
        return merged
    }

    private static func lowerIdentityPriority(_ lhs: VocabularyCard, _ rhs: VocabularyCard) -> Bool {
        if lhs.wordbook != rhs.wordbook {
            return lhs.wordbook != .user && rhs.wordbook == .user
        }
        if lhs.meaningKo.isEmpty != rhs.meaningKo.isEmpty {
            return lhs.meaningKo.isEmpty && rhs.meaningKo.isEmpty == false
        }
        if lhs.version != rhs.version {
            return lhs.version < rhs.version
        }
        return lhs.updatedAt < rhs.updatedAt
    }

    private static func lowerProgressPriority(_ lhs: VocabularyCard, _ rhs: VocabularyCard) -> Bool {
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

    private static func lowerContentPriority(_ lhs: VocabularyCard, _ rhs: VocabularyCard) -> Bool {
        if lhs.meaningKo.isEmpty != rhs.meaningKo.isEmpty {
            return lhs.meaningKo.isEmpty && rhs.meaningKo.isEmpty == false
        }
        if (lhs.definitionSourcePrimary == "user") != (rhs.definitionSourcePrimary == "user") {
            return lhs.definitionSourcePrimary != "user" && rhs.definitionSourcePrimary == "user"
        }
        if lhs.wordbook != rhs.wordbook {
            return lhs.wordbook != .user && rhs.wordbook == .user
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

enum ImportError: LocalizedError {
    case invalidPayload
    case missingDate(String)
    case invalidDate(key: String, value: String)

    var errorDescription: String? {
        switch self {
        case .invalidPayload:
            return "Invalid sync payload."
        case let .missingDate(key):
            return "Missing required sync timestamp: \(key)."
        case let .invalidDate(key, value):
            return "Invalid sync timestamp for \(key): \(value)."
        }
    }
}
