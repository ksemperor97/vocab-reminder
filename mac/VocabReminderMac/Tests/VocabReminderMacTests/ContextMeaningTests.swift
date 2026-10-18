import Foundation
import Testing
@testable import VocabCore

@Suite(.serialized)
@MainActor
final class ContextMeaningTests {
    private let now = Date(timeIntervalSince1970: 1_792_285_205) // 2026-10-18T01:00:05Z

    deinit {
        DictionaryURLProtocolStub.handler = nil
    }

    @Test func testSharedApplyCasesHoldOnMac() throws {
        let vectors = try loadVectors()
        let cases = try #require(vectors["apply_cases"] as? [[String: Any]])
        for vector in cases {
            let name = try #require(vector["name"] as? String)
            let cardSpec = try #require(vector["card"] as? [String: Any])
            let expected = try #require(vector["expected"] as? [String: Any])
            let resultPayload = try #require(vector["result"] as? [String: Any])
            let fixture = try makeStore(cards: [cardSpec])
            defer { try? FileManager.default.removeItem(at: fixture.directory) }
            let store = fixture.store
            let card = try #require(store.cards.first)
            let before = Set(store.definitionCandidates.map(\.id))
            let result = try CloudDictionaryLookupResult(payload: resultPayload)
            let target = DictionaryEnrichmentTarget(
                cardId: card.id,
                lemma: card.lemma,
                sentence: ContextMeaning.sentence(of: card),
                contextKey: ContextMeaning.key(of: card)
            )

            // Same routing as MacAutoSyncController.
            if card.meaningKo.isEmpty {
                #expect(store.pendingDictionaryEnrichmentTargets().map(\.cardId) == [card.id], Comment(rawValue: name))
                store.applyDictionaryEnrichment([result], requestedTargets: [target], now: now)
            } else {
                let queued = store.pendingContextMeaningTargets { _, _ in true }.map(\.cardId)
                #expect(queued == ((cardSpec["manual_edit"] as? Bool ?? false) ? [] : [card.id]), Comment(rawValue: name))
                store.applyContextMeanings([(target: target, result: result)], now: now)
            }

            let after = try #require(store.cards.first)
            let added = store.definitionCandidates.filter { before.contains($0.id) == false }
            #expect((after.version > card.version) == (expected["applied"] as? Bool), Comment(rawValue: name))
            #expect(after.meaningKo == expected["meaning_ko"] as? String, Comment(rawValue: name))
            #expect(after.definitionSourcePrimary == expected["definition_source_primary"] as? String, Comment(rawValue: name))
            #expect(added.map(\.source).sorted() == (expected["new_candidate_sources"] as? [String] ?? []).sorted(), Comment(rawValue: name))
            #expect(added.filter(\.selectedAsPrimary).map(\.source).first == expected["selected_candidate_source"] as? String, Comment(rawValue: name))
            if card.meaningKo.isEmpty == false {
                #expect(after.dueAt == card.dueAt, Comment(rawValue: name))
            }
            let delay = ContextMeaning.retryDelay(contextStatus: result.contextStatus)
            switch expected["context_retry"] as? String {
            case "none": #expect(delay == nil, Comment(rawValue: name))
            case "soon": #expect(delay == ContextMeaning.soonRetryDelay, Comment(rawValue: name))
            default: #expect(delay == ContextMeaning.laterRetryDelay, Comment(rawValue: name))
            }
        }
    }

    @Test func testControllerSendsCapturedSentencesAndUpgradesDictionaryMeaningOnce() async throws {
        let fixture = try makeStore(cards: [
            ["lemma": "acute", "meaning_ko": "", "definition_source_primary": "pending_capture",
             "source_sentence_primary": "The patient was admitted with acute appendicitis."],
            ["lemma": "bank", "meaning_ko": "은행", "definition_source_primary": "mac_dictionary",
             "source_sentence_primary": "We had a picnic on the bank of the river."],
            ["lemma": "salient", "meaning_ko": "두드러진", "definition_source_primary": "mac_dictionary",
             "source_sentence_primary": ""]
        ])
        defer { try? FileManager.default.removeItem(at: fixture.directory) }
        let bodies = LockedBodies()
        DictionaryURLProtocolStub.handler = { request in try Self.contextResponse(to: request, recordingInto: bodies) }
        let session = DictionaryURLProtocolStub.session()
        defer { session.invalidateAndCancel() }
        let suiteName = "ContextMeaningTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let controller = MacAutoSyncController(defaults: defaults, session: session, nowProvider: { [now] in now })
        let config = CloudSyncConfig(serverBaseURL: "https://sync.example.test", bearerToken: "token")

        let first = await controller.performDictionaryEnrichment(store: fixture.store, config: config)
        let second = await controller.performDictionaryEnrichment(store: fixture.store, config: config)

        #expect(first.appliedCount == 2)
        #expect(second.appliedCount == 0)
        #expect(bodies.all == [
            [["lemma": "acute", "sentence": "The patient was admitted with acute appendicitis."]],
            [["lemma": "bank", "sentence": "We had a picnic on the bank of the river."]]
        ])
        let acute = try #require(fixture.store.cards.first { $0.lemma == "acute" })
        let bank = try #require(fixture.store.cards.first { $0.lemma == "bank" })
        let salient = try #require(fixture.store.cards.first { $0.lemma == "salient" })
        #expect(acute.meaningKo == "급성의")
        #expect(acute.definitionSourcePrimary == "llm_context")
        #expect(bank.meaningKo == "강둑, 둑")
        #expect(bank.definitionSourcePrimary == "llm_context")
        #expect(salient.meaningKo == "두드러진")
        #expect(fixture.store.definitionCandidates.contains { $0.cardId == bank.id && $0.parsedMeaningKo == "은행" })
        #expect(controller.contextRetryAt(cardId: bank.id) == .some(nil))
    }

    @Test func testFailedContextLookupIsRetriedAfterTheShortDelay() async throws {
        let fixture = try makeStore(cards: [
            ["lemma": "bank", "meaning_ko": "은행", "definition_source_primary": "mac_dictionary",
             "source_sentence_primary": "We had a picnic on the bank of the river."]
        ])
        defer { try? FileManager.default.removeItem(at: fixture.directory) }
        let requests = LockedRequestCounter()
        DictionaryURLProtocolStub.handler = { _ in
            requests.increment()
            return (503, ["Content-Type": "application/json"], try DictionaryURLProtocolStub.jsonData(["error": ["message": "down"]]))
        }
        let session = DictionaryURLProtocolStub.session()
        defer { session.invalidateAndCancel() }
        let suiteName = "ContextMeaningTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let clock = LockedClock(now)
        let controller = MacAutoSyncController(defaults: defaults, session: session, nowProvider: { clock.value })
        let config = CloudSyncConfig(serverBaseURL: "https://sync.example.test", bearerToken: "token")

        _ = await controller.performDictionaryEnrichment(store: fixture.store, config: config)
        _ = await controller.performDictionaryEnrichment(store: fixture.store, config: config)
        clock.value = now.addingTimeInterval(ContextMeaning.soonRetryDelay + 1)
        _ = await controller.performDictionaryEnrichment(store: fixture.store, config: config)

        #expect(requests.value == 2)
        #expect(fixture.store.cards.first?.meaningKo == "은행")
    }

    @Test func testClientFallsBackToTheLemmasBodyForAServerWithoutContextMeanings() async throws {
        let bodies = LockedBodies()
        DictionaryURLProtocolStub.handler = { request in try Self.legacyServerResponse(to: request, recordingInto: bodies) }
        let session = DictionaryURLProtocolStub.session()
        defer { session.invalidateAndCancel() }
        let client = CloudSyncClient(
            config: CloudSyncConfig(serverBaseURL: "https://sync.example.test", bearerToken: "token"),
            session: session
        )

        let results = try await client.lookupDictionary(items: [CloudDictionaryRequest(lemma: "bank", sentence: "We sat on the bank.")])

        #expect(bodies.all.count == 2)
        #expect(results.map(\.meaningKo) == ["은행"])
        #expect(results.first?.contextStatus == nil)
    }

    // MARK: - Fixtures

    /// Answers like a server that only understands {lemmas}.
    nonisolated private static func legacyServerResponse(
        to request: URLRequest,
        recordingInto bodies: LockedBodies
    ) throws -> (status: Int, headers: [String: String], data: Data) {
        guard let body = DictionaryURLProtocolStub.bodyData(from: request),
              let json = try JSONSerialization.jsonObject(with: body) as? [String: Any]
        else { throw URLError(.cannotParseResponse) }
        bodies.append([["body": json["items"] == nil ? "lemmas" : "items"]])
        if json["items"] != nil {
            return (400, ["Content-Type": "application/json"], try DictionaryURLProtocolStub.jsonData(["error": ["message": "Dictionary batch body must contain a lemmas array."]]))
        }
        let result: [String: Any] = ["status": "resolved", "lemma": "bank", "meaningKo": "은행", "sourceType": "mac_dictionary"]
        return (200, ["Content-Type": "application/json"], try DictionaryURLProtocolStub.jsonData(["results": [result]]))
    }

    /// Answers like a context-enabled server. Runs on URLSession's thread, not the main actor.
    nonisolated private static func contextResponse(
        to request: URLRequest,
        recordingInto bodies: LockedBodies
    ) throws -> (status: Int, headers: [String: String], data: Data) {
        guard let body = DictionaryURLProtocolStub.bodyData(from: request),
              let json = try JSONSerialization.jsonObject(with: body) as? [String: Any],
              let items = json["items"] as? [[String: String]]
        else { throw URLError(.cannotParseResponse) }
        bodies.append(items)
        let results: [[String: Any]] = items.map { item in
            [
                "status": "resolved", "lemma": item["lemma"] ?? "",
                "meaningKo": item["lemma"] == "acute" ? "급성의" : "강둑, 둑", "partOfSpeech": "noun",
                "sourceType": "llm_context", "confidence": 0.9, "contextStatus": "resolved",
                "contextSentence": item["sentence"] ?? "",
                "dictionary": ["meaningKo": "은행", "sourceType": "mac_dictionary", "confidence": 0.8]
            ]
        }
        return (200, ["Content-Type": "application/json"], try DictionaryURLProtocolStub.jsonData(["results": results]))
    }

    private func makeStore(cards specs: [[String: Any]]) throws -> (store: LocalVocabularyStore, directory: URL) {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("ContextMeaningTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var cards: [[String: Any]] = []
        var candidates: [[String: Any]] = []
        var events: [[String: Any]] = []
        for (index, spec) in specs.enumerated() {
            let id = "context-card-\(index)"
            let lemma = spec["lemma"] as? String ?? ""
            let meaning = spec["meaning_ko"] as? String ?? ""
            let source = spec["definition_source_primary"] as? String ?? ""
            let sentence = spec["source_sentence_primary"] as? String ?? ""
            cards.append([
                "id": id, "lemma": lemma, "display_word": lemma, "language": "en", "wordbook": "user",
                "meaning_ko": meaning, "definition_en": "", "part_of_speech": "", "phonetic": NSNull(), "audio_url": NSNull(),
                "source_sentence_primary": sentence, "source_url_primary": NSNull(), "source_app_primary": "XCTest",
                "definition_source_primary": source, "status": "new", "due_at": "2026-10-18T03:00:00.000Z",
                "interval_days": 0, "ease": 2.5, "stability": NSNull(), "difficulty": NSNull(), "lapse_count": 0,
                "interval_before_lapse": NSNull(), "recommendation_started_at": NSNull(), "deleted_at": NSNull(),
                "created_device_id": "mac-fixture", "created_at": "2026-10-18T00:00:0\(index).000Z",
                "updated_at": "2026-10-18T00:30:00.000Z", "version": 2
            ])
            if meaning.isEmpty == false {
                candidates.append([
                    "id": "\(id)-candidate", "card_id": id, "source": source, "raw_text": meaning,
                    "parsed_meaning_ko": meaning, "parsed_definition_en": "", "part_of_speech": "", "phonetic": NSNull(),
                    "example_sentence": sentence, "confidence": 1.0, "selected_as_primary": true,
                    "origin_device_id": "mac-fixture", "base_card_version": 1, "created_at": "2026-10-18T00:30:00.000Z"
                ])
            }
            if spec["manual_edit"] as? Bool == true {
                events.append([
                    "id": "\(id)-edit", "card_id": id, "response": "manual_edit", "previous_status": "new", "next_status": "new",
                    "previous_due_at": "2026-10-18T03:00:00.000Z", "next_due_at": "2026-10-18T03:00:00.000Z",
                    "previous_interval_days": 0, "next_interval_days": 0, "client_created_at": "2026-10-18T00:40:00.000Z",
                    "metadata": ["action": source == "user" ? "card_edit" : "definition_candidate_restored"]
                ])
            }
        }
        let payload: [String: Any] = [
            "cards": cards, "contexts": [], "definition_candidates": candidates,
            "review_events": events, "recommendation_feedback": []
        ]
        try JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys])
            .write(to: directory.appendingPathComponent("vocab-reminder-sync.json"), options: .atomic)
        let store = LocalVocabularyStore(appSupportURL: directory, scheduleNotifications: false)
        store.settings.enableWordRecommendations = false
        store.importSyncSnapshot()
        return (store, directory)
    }

    private func loadVectors() throws -> [String: Any] {
        var directory = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while directory.path != "/" {
            let candidate = directory.appendingPathComponent("shared/test-vectors/context_meanings.json")
            if FileManager.default.fileExists(atPath: candidate.path) {
                return try #require(JSONSerialization.jsonObject(with: Data(contentsOf: candidate)) as? [String: Any])
            }
            directory.deleteLastPathComponent()
        }
        throw CocoaError(.fileNoSuchFile)
    }
}

private final class LockedBodies: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: [[[String: String]]] = []

    var all: [[[String: String]]] {
        lock.lock()
        defer { lock.unlock() }
        return storage
    }

    func append(_ items: [[String: String]]) {
        lock.lock()
        storage.append(items)
        lock.unlock()
    }
}

private final class LockedClock: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: Date

    init(_ date: Date) { storage = date }

    var value: Date {
        get { lock.lock(); defer { lock.unlock() }; return storage }
        set { lock.lock(); storage = newValue; lock.unlock() }
    }
}
