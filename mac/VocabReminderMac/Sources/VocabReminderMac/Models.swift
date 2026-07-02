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
    var sourceApp: String
    var captureMethod: CaptureMethod
    var createdAt: Date
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

struct UserSettingsData: Codable, Equatable {
    var dailyWordGoal: Int = 10
    var maxQueueSize: Int = 20
    var snoozeDurationMinutes: Int = 60
    var quietHoursStart: String = "23:00"
    var quietHoursEnd: String = "09:00"
    var timezone: String = "Asia/Seoul"
    var enableWordRecommendations: Bool = true
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

    var needsWordSelection: Bool { wordCount >= 4 }
}
