import Foundation
import Testing
@testable import VocabCore

@Suite(.serialized)
final class CloudSyncClientDictionaryTests {
    deinit {
        DictionaryURLProtocolStub.handler = nil
    }

    @Test func testBatchLookupSendsBearerAndDecodesAllOutcomeKinds() async throws {
        DictionaryURLProtocolStub.handler = { request in
            #expect(request.url?.path == "/dict/batch")
            #expect(request.httpMethod == "POST")
            #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer test-token")
            #expect(request.value(forHTTPHeaderField: "Content-Type") == "application/json; charset=utf-8")
            let body = try #require(DictionaryURLProtocolStub.bodyData(from: request))
            let payload = try #require(JSONSerialization.jsonObject(with: body) as? [String: Any])
            #expect(payload["lemmas"] as? [String] == ["resilient", "missing", "provider"])
            return (
                200,
                ["Content-Type": "application/json"],
                try DictionaryURLProtocolStub.jsonData([
                    "results": [
                        [
                            "status": "resolved",
                            "lemma": "resilient",
                            "meaningKo": "회복력 있는",
                            "definitionEn": "able to recover quickly",
                            "partOfSpeech": "adjective",
                            "exampleSentence": "A resilient system recovers.",
                            "sourceType": "offline_catalog",
                            "confidence": 0.94,
                            "attemptedAt": "2026-07-13T04:00:00.000Z",
                            "errorCode": NSNull()
                        ],
                        [
                            "status": "pending",
                            "lemma": "missing",
                            "meaningKo": "",
                            "errorCode": "not_found"
                        ],
                        [
                            "status": "pending",
                            "lemma": "provider",
                            "meaningKo": "",
                            "errorCode": "provider_failure"
                        ]
                    ]
                ])
            )
        }

        let session = DictionaryURLProtocolStub.session()
        defer { session.invalidateAndCancel() }
        let results = try await CloudSyncClient(
            config: CloudSyncConfig(serverBaseURL: "https://sync.example.test", bearerToken: "test-token"),
            session: session
        ).lookupDictionary(lemmas: [" Resilient ", "missing", "provider"])

        #expect(results.map(\.outcome) == [.resolved, .notFound, .providerFailure])
        #expect(results[0].meaningKo == "회복력 있는")
        #expect(results[0].sourceType == "offline_catalog")
        #expect(results[0].confidence == 0.94)
    }

    @Test func testEnglishOnlyResponseRemainsPendingForKoreanMeaning() async throws {
        DictionaryURLProtocolStub.handler = { _ in
            (
                200,
                ["Content-Type": "application/json"],
                try DictionaryURLProtocolStub.jsonData([
                    "results": [[
                        "status": "resolved",
                        "lemma": "technical",
                        "meaningKo": "",
                        "definitionEn": "relating to a specialized subject",
                        "errorCode": NSNull()
                    ]]
                ])
            )
        }
        let session = DictionaryURLProtocolStub.session()
        defer { session.invalidateAndCancel() }
        let result = try await CloudSyncClient(
            config: CloudSyncConfig(serverBaseURL: "https://sync.example.test", bearerToken: "token"),
            session: session
        ).lookupDictionary(lemmas: ["technical"]).first

        #expect(result?.outcome == .pending)
    }

    @Test func testOldServer404IsReportedWithoutChangingTheSyncErrorContract() async throws {
        DictionaryURLProtocolStub.handler = { _ in
            (404, ["Content-Type": "application/json"], Data(#"{"error":{"message":"Not found."}}"#.utf8))
        }
        let session = DictionaryURLProtocolStub.session()
        defer { session.invalidateAndCancel() }

        do {
            _ = try await CloudSyncClient(
                config: CloudSyncConfig(serverBaseURL: "https://old.example.test", bearerToken: "token"),
                session: session
            ).lookupDictionary(lemmas: ["resilient"])
            Issue.record("Expected HTTP 404")
        } catch CloudSyncError.http(let status, _) {
            #expect(status == 404)
        } catch {
            Issue.record("Unexpected error: \(error)")
        }
    }

    @Test func testDictionaryBatchLimitIsEnforcedBeforeNetworkIO() async {
        let counter = LockedRequestCounter()
        DictionaryURLProtocolStub.handler = { _ in
            counter.increment()
            return (200, [:], try DictionaryURLProtocolStub.jsonData(["results": []]))
        }
        let session = DictionaryURLProtocolStub.session()
        defer { session.invalidateAndCancel() }
        do {
            _ = try await CloudSyncClient(
                config: CloudSyncConfig(serverBaseURL: "https://sync.example.test", bearerToken: "token"),
                session: session
            ).lookupDictionary(
                lemmas: (0...CloudSyncClient.maxDictionaryItemsPerBatch).map { "word\($0)" }
            )
            Issue.record("Expected the bounded batch guard")
        } catch CloudSyncError.invalidConfig {
            #expect(counter.value == 0)
        } catch {
            Issue.record("Unexpected error: \(error)")
        }
    }

    @Test func testLemmaNormalizationMatchesServerPunctuationAndWhitespaceRules() {
        #expect(normalizeCloudDictionaryLemma("  DON’T—STOP\nNOW  ") == "don't-stop now")
    }
}
