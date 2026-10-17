import Foundation

package enum CardStatus: String, Codable, CaseIterable {
    case new
    case learning
    case review
    case relearning
    case mastered
    case suspended
}

package enum ReviewResponse: String, Codable {
    case remembered
    case forgotten
    case unavailable
    case reveal
    case manualEdit = "manual_edit"
    case suspend
    case restore
}

package enum Wordbook: String, Codable {
    case user
    case recommended
}

package enum CaptureMethod: String, Codable {
    case macService = "mac_service"
    case macClipboardHotkey = "mac_clipboard_hotkey"
    case androidProcessText = "android_process_text"
    case androidShare = "android_share"
    case manual
    case recommendation
}

package struct VocabularyCard: Identifiable, Codable, Equatable {
    package var id: String
    package var lemma: String
    package var displayWord: String
    package var meaningKo: String
    package var definitionEn: String
    package var partOfSpeech: String
    package var phonetic: String
    package var exampleSentence: String
    package var sourceAppPrimary: String
    package var sourceUrlPrimary: String
    package var definitionSourcePrimary: String
    package var primaryDefinitionCandidateId: String?
    package var status: CardStatus
    package var wordbook: Wordbook
    package var dueAt: Date
    package var intervalDays: Int
    package var lapseCount: Int
    package var intervalBeforeLapse: Int?
    package var recommendationStartedAt: Date?
    package var deletedAt: Date?
    package var mergedInto: String?
    package var createdDeviceId: String
    package var createdAt: Date
    package var updatedAt: Date
    package var version: Int
    // Field-group clocks (sync protocol 3); nil means "same as the whole-card clock".
    // See CardMerge and shared/test-vectors/card_merge.json.
    package var contentVersionValue: Int?
    package var contentUpdatedAtValue: Date?
    package var scheduleVersionValue: Int?
    package var scheduleUpdatedAtValue: Date?

    package init(
        id: String,
        lemma: String,
        displayWord: String,
        meaningKo: String,
        definitionEn: String,
        partOfSpeech: String,
        phonetic: String = "",
        exampleSentence: String,
        sourceAppPrimary: String = "",
        sourceUrlPrimary: String = "",
        definitionSourcePrimary: String,
        primaryDefinitionCandidateId: String? = nil,
        status: CardStatus,
        wordbook: Wordbook,
        dueAt: Date,
        intervalDays: Int,
        lapseCount: Int,
        intervalBeforeLapse: Int? = nil,
        recommendationStartedAt: Date? = nil,
        deletedAt: Date? = nil,
        mergedInto: String? = nil,
        createdDeviceId: String = "",
        createdAt: Date,
        updatedAt: Date,
        version: Int,
        contentVersion: Int? = nil,
        contentUpdatedAt: Date? = nil,
        scheduleVersion: Int? = nil,
        scheduleUpdatedAt: Date? = nil
    ) {
        self.id = id
        self.lemma = lemma
        self.displayWord = displayWord
        self.meaningKo = meaningKo
        self.definitionEn = definitionEn
        self.partOfSpeech = partOfSpeech
        self.phonetic = phonetic
        self.exampleSentence = exampleSentence
        self.sourceAppPrimary = sourceAppPrimary
        self.sourceUrlPrimary = sourceUrlPrimary
        self.definitionSourcePrimary = definitionSourcePrimary
        self.primaryDefinitionCandidateId = primaryDefinitionCandidateId
        self.status = status
        self.wordbook = wordbook
        self.dueAt = dueAt
        self.intervalDays = intervalDays
        self.lapseCount = lapseCount
        self.intervalBeforeLapse = intervalBeforeLapse
        self.recommendationStartedAt = recommendationStartedAt
        self.deletedAt = deletedAt
        self.mergedInto = mergedInto
        self.createdDeviceId = createdDeviceId
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.version = version
        self.contentVersionValue = contentVersion
        self.contentUpdatedAtValue = contentUpdatedAt
        self.scheduleVersionValue = scheduleVersion
        self.scheduleUpdatedAtValue = scheduleUpdatedAt
    }

    package var contentVersion: Int { contentVersionValue ?? version }
    package var contentUpdatedAt: Date { contentUpdatedAtValue ?? updatedAt }
    package var scheduleVersion: Int { scheduleVersionValue ?? version }
    package var scheduleUpdatedAt: Date { scheduleUpdatedAtValue ?? updatedAt }

    private enum CodingKeys: String, CodingKey {
        case id, lemma, displayWord, meaningKo, definitionEn, partOfSpeech, phonetic, exampleSentence
        case sourceAppPrimary, sourceUrlPrimary, definitionSourcePrimary, primaryDefinitionCandidateId
        case status, wordbook, dueAt, intervalDays, lapseCount, intervalBeforeLapse, recommendationStartedAt
        case deletedAt, mergedInto, createdDeviceId, createdAt, updatedAt, version
        case contentVersionValue, contentUpdatedAtValue, scheduleVersionValue, scheduleUpdatedAtValue
    }

    // Decodes the pre-SQLite vocab-data.json, which lacks the newer fields.
    package init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            id: try c.decode(String.self, forKey: .id),
            lemma: try c.decode(String.self, forKey: .lemma),
            displayWord: try c.decode(String.self, forKey: .displayWord),
            meaningKo: try c.decode(String.self, forKey: .meaningKo),
            definitionEn: try c.decode(String.self, forKey: .definitionEn),
            partOfSpeech: try c.decode(String.self, forKey: .partOfSpeech),
            phonetic: try c.decodeIfPresent(String.self, forKey: .phonetic) ?? "",
            exampleSentence: try c.decode(String.self, forKey: .exampleSentence),
            sourceAppPrimary: try c.decodeIfPresent(String.self, forKey: .sourceAppPrimary) ?? "",
            sourceUrlPrimary: try c.decodeIfPresent(String.self, forKey: .sourceUrlPrimary) ?? "",
            definitionSourcePrimary: try c.decode(String.self, forKey: .definitionSourcePrimary),
            primaryDefinitionCandidateId: try c.decodeIfPresent(String.self, forKey: .primaryDefinitionCandidateId),
            status: try c.decode(CardStatus.self, forKey: .status),
            wordbook: try c.decode(Wordbook.self, forKey: .wordbook),
            dueAt: try c.decode(Date.self, forKey: .dueAt),
            intervalDays: try c.decode(Int.self, forKey: .intervalDays),
            lapseCount: try c.decode(Int.self, forKey: .lapseCount),
            intervalBeforeLapse: try c.decodeIfPresent(Int.self, forKey: .intervalBeforeLapse),
            recommendationStartedAt: try c.decodeIfPresent(Date.self, forKey: .recommendationStartedAt),
            deletedAt: try c.decodeIfPresent(Date.self, forKey: .deletedAt),
            mergedInto: try c.decodeIfPresent(String.self, forKey: .mergedInto),
            createdDeviceId: try c.decodeIfPresent(String.self, forKey: .createdDeviceId) ?? "",
            createdAt: try c.decode(Date.self, forKey: .createdAt),
            updatedAt: try c.decode(Date.self, forKey: .updatedAt),
            version: try c.decode(Int.self, forKey: .version),
            contentVersion: try c.decodeIfPresent(Int.self, forKey: .contentVersionValue),
            contentUpdatedAt: try c.decodeIfPresent(Date.self, forKey: .contentUpdatedAtValue),
            scheduleVersion: try c.decodeIfPresent(Int.self, forKey: .scheduleVersionValue),
            scheduleUpdatedAt: try c.decodeIfPresent(Date.self, forKey: .scheduleUpdatedAtValue)
        )
    }
}

package struct StoredContext: Identifiable, Codable, Equatable {
    package init(
        id: String,
        cardId: String,
        sourceSentence: String,
        sourceURL: String? = nil,
        pageTitle: String = "",
        sourceApp: String,
        captureMethod: CaptureMethod,
        createdAt: Date
    ) {
        self.id = id
        self.cardId = cardId
        self.sourceSentence = sourceSentence
        self.sourceURL = sourceURL
        self.pageTitle = pageTitle
        self.sourceApp = sourceApp
        self.captureMethod = captureMethod
        self.createdAt = createdAt
    }

    package var id: String
    package var cardId: String
    package var sourceSentence: String
    package var sourceURL: String? = nil
    package var pageTitle: String = ""
    package var sourceApp: String
    package var captureMethod: CaptureMethod
    package var createdAt: Date
}

extension StoredContext {
    private enum CodingKeys: String, CodingKey {
        case id
        case cardId
        case sourceSentence
        case sourceURL
        case pageTitle
        case sourceApp
        case captureMethod
        case createdAt
    }

    package init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        cardId = try container.decode(String.self, forKey: .cardId)
        sourceSentence = try container.decode(String.self, forKey: .sourceSentence)
        sourceURL = try container.decodeIfPresent(String.self, forKey: .sourceURL)
        pageTitle = try container.decodeIfPresent(String.self, forKey: .pageTitle) ?? ""
        sourceApp = try container.decode(String.self, forKey: .sourceApp)
        captureMethod = try container.decode(CaptureMethod.self, forKey: .captureMethod)
        createdAt = try container.decode(Date.self, forKey: .createdAt)
    }
}

package struct StoredDefinitionCandidate: Identifiable, Codable, Equatable {
    package init(
        id: String,
        cardId: String,
        source: String,
        rawText: String,
        parsedMeaningKo: String,
        parsedDefinitionEn: String,
        partOfSpeech: String,
        exampleSentence: String,
        confidence: Double,
        selectedAsPrimary: Bool,
        createdAt: Date,
        originDeviceId: String? = nil,
        baseCardVersion: Int? = nil
    ) {
        self.id = id
        self.cardId = cardId
        self.source = source
        self.rawText = rawText
        self.parsedMeaningKo = parsedMeaningKo
        self.parsedDefinitionEn = parsedDefinitionEn
        self.partOfSpeech = partOfSpeech
        self.exampleSentence = exampleSentence
        self.confidence = confidence
        self.selectedAsPrimary = selectedAsPrimary
        self.createdAt = createdAt
        self.originDeviceId = originDeviceId
        self.baseCardVersion = baseCardVersion
    }

    package var id: String
    package var cardId: String
    package var source: String
    package var rawText: String
    package var parsedMeaningKo: String
    package var parsedDefinitionEn: String
    package var partOfSpeech: String
    package var exampleSentence: String
    package var confidence: Double
    package var selectedAsPrimary: Bool
    package var createdAt: Date
    package var originDeviceId: String? = nil
    package var baseCardVersion: Int? = nil
}

package struct StoredReviewEvent: Identifiable, Codable, Equatable {
    package init(
        id: String,
        cardId: String,
        response: ReviewResponse,
        previousStatus: CardStatus,
        nextStatus: CardStatus,
        previousDueAt: Date,
        nextDueAt: Date,
        previousIntervalDays: Int,
        nextIntervalDays: Int,
        clientCreatedAt: Date,
        metadata: [String: String]
    ) {
        self.id = id
        self.cardId = cardId
        self.response = response
        self.previousStatus = previousStatus
        self.nextStatus = nextStatus
        self.previousDueAt = previousDueAt
        self.nextDueAt = nextDueAt
        self.previousIntervalDays = previousIntervalDays
        self.nextIntervalDays = nextIntervalDays
        self.clientCreatedAt = clientCreatedAt
        self.metadata = metadata
    }

    package var id: String
    package var cardId: String
    package var response: ReviewResponse
    package var previousStatus: CardStatus
    package var nextStatus: CardStatus
    package var previousDueAt: Date
    package var nextDueAt: Date
    package var previousIntervalDays: Int
    package var nextIntervalDays: Int
    package var clientCreatedAt: Date
    package var metadata: [String: String]
}

package struct StoredSyncEvent: Identifiable, Codable, Equatable {
    package init(
        id: String,
        succeeded: Bool,
        summary: String,
        createdAt: Date
    ) {
        self.id = id
        self.succeeded = succeeded
        self.summary = summary
        self.createdAt = createdAt
    }

    package var id: String
    package var succeeded: Bool
    package var summary: String
    package var createdAt: Date
}

package struct RecommendationFeedback: Identifiable, Codable, Equatable {
    package init(
        id: String,
        cardId: String,
        lemma: String,
        action: String,
        createdAt: Date
    ) {
        self.id = id
        self.cardId = cardId
        self.lemma = lemma
        self.action = action
        self.createdAt = createdAt
    }

    package var id: String
    package var cardId: String
    package var lemma: String
    package var action: String
    package var createdAt: Date
}

package struct UserSettingsData: Codable, Equatable {
    package var dailyWordGoal: Int = 10
    package var maxQueueSize: Int = 20
    package var snoozeDurationMinutes: Int = 60
    package var quietHoursStart: String = "23:00"
    package var quietHoursEnd: String = "09:00"
    package var timezone: String = "Asia/Seoul"
    package var enableWordRecommendations: Bool = true
    package var recommendationsIntroducedAt: Date?
    package var updatedAt: Date = Date(timeIntervalSince1970: 0)

    package init(
        dailyWordGoal: Int = 10,
        maxQueueSize: Int = 20,
        snoozeDurationMinutes: Int = 60,
        quietHoursStart: String = "23:00",
        quietHoursEnd: String = "09:00",
        timezone: String = "Asia/Seoul",
        enableWordRecommendations: Bool = true,
        recommendationsIntroducedAt: Date? = nil,
        updatedAt: Date = Date(timeIntervalSince1970: 0)
    ) {
        self.dailyWordGoal = dailyWordGoal
        self.maxQueueSize = maxQueueSize
        self.snoozeDurationMinutes = snoozeDurationMinutes
        self.quietHoursStart = quietHoursStart
        self.quietHoursEnd = quietHoursEnd
        self.timezone = timezone
        self.enableWordRecommendations = enableWordRecommendations
        self.recommendationsIntroducedAt = recommendationsIntroducedAt
        self.updatedAt = updatedAt
    }

    private enum CodingKeys: String, CodingKey {
        case dailyWordGoal
        case maxQueueSize
        case snoozeDurationMinutes
        case quietHoursStart
        case quietHoursEnd
        case timezone
        case enableWordRecommendations
        case recommendationsIntroducedAt
        case updatedAt
    }

    package init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        dailyWordGoal = try container.decodeIfPresent(Int.self, forKey: .dailyWordGoal) ?? 10
        maxQueueSize = try container.decodeIfPresent(Int.self, forKey: .maxQueueSize) ?? 20
        snoozeDurationMinutes = try container.decodeIfPresent(Int.self, forKey: .snoozeDurationMinutes) ?? 60
        quietHoursStart = try container.decodeIfPresent(String.self, forKey: .quietHoursStart) ?? "23:00"
        quietHoursEnd = try container.decodeIfPresent(String.self, forKey: .quietHoursEnd) ?? "09:00"
        timezone = try container.decodeIfPresent(String.self, forKey: .timezone) ?? "Asia/Seoul"
        enableWordRecommendations = try container.decodeIfPresent(Bool.self, forKey: .enableWordRecommendations) ?? true
        recommendationsIntroducedAt = try container.decodeIfPresent(Date.self, forKey: .recommendationsIntroducedAt)
        updatedAt = try container.decodeIfPresent(Date.self, forKey: .updatedAt) ?? Date(timeIntervalSince1970: 0)
    }
}

package struct DictionaryResult: Equatable {
    package init(
        lemma: String,
        displayWord: String,
        meaningKo: String,
        definitionEn: String,
        partOfSpeech: String,
        exampleSentence: String,
        rawSource: String,
        sourceType: String,
        confidence: Double,
        sourceSentence: String,
        sourceApp: String
    ) {
        self.lemma = lemma
        self.displayWord = displayWord
        self.meaningKo = meaningKo
        self.definitionEn = definitionEn
        self.partOfSpeech = partOfSpeech
        self.exampleSentence = exampleSentence
        self.rawSource = rawSource
        self.sourceType = sourceType
        self.confidence = confidence
        self.sourceSentence = sourceSentence
        self.sourceApp = sourceApp
    }

    package var lemma: String
    package var displayWord: String
    package var meaningKo: String
    package var definitionEn: String
    package var partOfSpeech: String
    package var exampleSentence: String
    package var rawSource: String
    package var sourceType: String
    package var confidence: Double
    package var sourceSentence: String
    package var sourceApp: String
}

package struct CaptureAnalysis: Equatable {
    package init(
        rawText: String,
        normalizedText: String,
        displayWord: String,
        lemma: String,
        wordCount: Int,
        sourceSentence: String,
        candidates: [String]
    ) {
        self.rawText = rawText
        self.normalizedText = normalizedText
        self.displayWord = displayWord
        self.lemma = lemma
        self.wordCount = wordCount
        self.sourceSentence = sourceSentence
        self.candidates = candidates
    }

    package var rawText: String
    package var normalizedText: String
    package var displayWord: String
    package var lemma: String
    package var wordCount: Int
    package var sourceSentence: String
    package var candidates: [String]

    package var needsWordSelection: Bool { wordCount >= 4 && !candidates.isEmpty }
}
