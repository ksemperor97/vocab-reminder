import Foundation
import XCTest
@testable import VocabReminderMac

final class CloudSyncClientDictionaryTests: XCTestCase {
    override func tearDown() {
        DictionaryURLProtocolStub.handler = nil
        super.tearDown()
    }

    func testBatchLookupSendsBearerAndDecodesAllOutcomeKinds() async throws {
        DictionaryURLProtocolStub.handler = { request in
            XCTAssertEqual(request.url?.path, "/dict/batch")
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer test-token")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json; charset=utf-8")
            let body = try XCTUnwrap(DictionaryURLProtocolStub.bodyData(from: request))
            let payload = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
            XCTAssertEqual(payload["lemmas"] as? [String], ["resilient", "missing", "provider"])
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

        XCTAssertEqual(results.map(\.outcome), [.resolved, .notFound, .providerFailure])
        XCTAssertEqual(results[0].meaningKo, "회복력 있는")
        XCTAssertEqual(results[0].sourceType, "offline_catalog")
        XCTAssertEqual(results[0].confidence, 0.94, accuracy: 0.0001)
    }

    func testEnglishOnlyResponseRemainsPendingForKoreanMeaning() async throws {
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

        XCTAssertEqual(result?.outcome, .pending)
    }

    func testOldServer404IsReportedWithoutChangingTheSyncErrorContract() async throws {
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
            XCTFail("Expected HTTP 404")
        } catch CloudSyncError.http(let status, _) {
            XCTAssertEqual(status, 404)
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testDictionaryBatchLimitIsEnforcedBeforeNetworkIO() async {
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
            XCTFail("Expected the bounded batch guard")
        } catch CloudSyncError.invalidConfig {
            XCTAssertEqual(counter.value, 0)
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testLemmaNormalizationMatchesServerPunctuationAndWhitespaceRules() {
        XCTAssertEqual(normalizeCloudDictionaryLemma("  DON’T—STOP\nNOW  "), "don't-stop now")
    }
}
