import Foundation

enum CardStatus: String, Codable, CaseIterable {
    case new
    case learning
    case review
    case relearning
    case mastered
    case suspended
}

enum ReviewResponse: String, Codable {
    case remembered
    case forgotten
    case unavailable
    case reveal
    case manualEdit = "manual_edit"
    case suspend
    case restore
}

enum Wordbook: String, Codable {
    case user
    case recommended
}

enum CaptureMethod: String, Codable {
    case macService = "mac_service"
    case macClipboardHotkey = "mac_clipboard_hotkey"
    case androidProcessText = "android_process_text"
    case androidShare = "android_share"
    case manual
    case recommendation
}

struct VocabularyCard: Identifiable, Codable, Equatable {
    var id: String
    var lemma: String
    var displayWord: String
    var meaningKo: String
    var definitionEn: String
    var partOfSpeech: String
    var exampleSentence: String
    var definitionSourcePrimary: String
    var status: CardStatus
    var wordbook: Wordbook
    var dueAt: Date
    var intervalDays: Int
    var lapseCount: Int
    var intervalBeforeLapse: Int?
    var recommendationStartedAt: Date?
    var deletedAt: Date?
    var createdAt: Date
    var updatedAt: Date
    var version: Int
}

struct StoredContext: Identifiable, Codable, Equatable {
    var id: String
    var cardId: String
    var sourceSentence: String
    var sourceURL: String? = nil
    var pageTitle: String = ""
    var sourceApp: String
    var captureMethod: CaptureMethod
    var createdAt: Date
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

    init(from decoder: Decoder) throws {
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

struct StoredDefinitionCandidate: Identifiable, Codable, Equatable {
    var id: String
    var cardId: String
    var source: String
    var rawText: String
    var parsedMeaningKo: String
    var parsedDefinitionEn: String
    var partOfSpeech: String
    var exampleSentence: String
    var confidence: Double
    var selectedAsPrimary: Bool
    var createdAt: Date
    var originDeviceId: String? = nil
    var baseCardVersion: Int? = nil
}

struct StoredReviewEvent: Identifiable, Codable, Equatable {
    var id: String
    var cardId: String
    var response: ReviewResponse
    var previousStatus: CardStatus
    var nextStatus: CardStatus
    var previousDueAt: Date
    var nextDueAt: Date
    var previousIntervalDays: Int
    var nextIntervalDays: Int
    var clientCreatedAt: Date
    var metadata: [String: String]
}

struct StoredSyncEvent: Identifiable, Codable, Equatable {
    var id: String
    var succeeded: Bool
    var summary: String
    var createdAt: Date
}

struct RecommendationFeedback: Identifiable, Codable, Equatable {
    var id: String
    var cardId: String
    var lemma: String
    var action: String
    var createdAt: Date
}

struct UserSettingsData: Codable, Equatable {
    var dailyWordGoal: Int = 10
    var maxQueueSize: Int = 20
    var snoozeDurationMinutes: Int = 60
    var quietHoursStart: String = "23:00"
    var quietHoursEnd: String = "09:00"
    var timezone: String = "Asia/Seoul"
    var enableWordRecommendations: Bool = true
    var recommendationsIntroducedAt: Date?
    var updatedAt: Date = Date(timeIntervalSince1970: 0)

    init(
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

    init(from decoder: Decoder) throws {
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

struct DictionaryResult: Equatable {
    var lemma: String
    var displayWord: String
    var meaningKo: String
    var definitionEn: String
    var partOfSpeech: String
    var exampleSentence: String
    var rawSource: String
    var sourceType: String
    var confidence: Double
    var sourceSentence: String
    var sourceApp: String
}

struct CaptureAnalysis: Equatable {
    var rawText: String
    var normalizedText: String
    var displayWord: String
    var lemma: String
    var wordCount: Int
    var sourceSentence: String
    var candidates: [String]

    var needsWordSelection: Bool { wordCount >= 4 && !candidates.isEmpty }
}
