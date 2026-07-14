import Foundation
import XCTest
@testable import VocabReminderMac

final class CloudRequestDiagnosticsTests: XCTestCase {
    override func tearDown() {
        DictionaryURLProtocolStub.handler = nil
        super.tearDown()
    }

    func testEveryHealthSyncAndDictionaryRequestCarriesAUUIDRequestID() async throws {
        let counter = LockedRequestCounter()
        DictionaryURLProtocolStub.handler = { request in
            let rawRequestID = try XCTUnwrap(request.value(forHTTPHeaderField: "X-Request-Id"))
            XCTAssertNotNil(UUID(uuidString: rawRequestID))
            counter.increment()

            switch request.url?.path {
            case "/health":
                return (200, ["Content-Type": "application/json"], try DictionaryURLProtocolStub.jsonData(["cards": 0]))
            case "/sync/batch":
                return (
                    200,
                    ["Content-Type": "application/json"],
                    try DictionaryURLProtocolStub.jsonData([
                        "applied": [String: Int](),
                        "skipped": ["reasons": [Any]()] as [String: Any]
                    ])
                )
            case "/sync/snapshot":
                return (200, ["Content-Type": "application/json"], try DictionaryURLProtocolStub.jsonData(["cards": []]))
            case "/dict/batch":
                return (
                    200,
                    ["Content-Type": "application/json"],
                    try DictionaryURLProtocolStub.jsonData([
                        "results": [["lemma": "privatelemma", "meaningKo": "뜻"]]
                    ])
                )
            default:
                XCTFail("Unexpected request path: \(request.url?.path ?? "nil")")
                return (404, [:], Data())
            }
        }

        let session = DictionaryURLProtocolStub.session()
        defer { session.invalidateAndCancel() }
        let client = CloudSyncClient(
            config: CloudSyncConfig(serverBaseURL: "https://sync.example.test", bearerToken: "top-secret-token"),
            session: session,
            diagnosticsDefaults: try makeDefaults()
        )

        _ = try await client.health()
        _ = try await client.pushAndPull(localBatch: emptySyncBatch())
        _ = try await client.lookupDictionary(lemmas: ["privatelemma"])

        XCTAssertEqual(counter.value, 4)
    }

    func testResponseHeaderRequestIDWinsAndIsPreservedInErrorAndStatus() async throws {
        let defaults = try makeDefaults()
        DictionaryURLProtocolStub.handler = { _ in
            (
                503,
                [
                    "Content-Type": "application/json",
                    "X-Request-Id": "server-header-request-123"
                ],
                Data(#"{"request_id":"json-request-ignored","error":{"message":"Unavailable"}}"#.utf8)
            )
        }
        let session = DictionaryURLProtocolStub.session()
        defer { session.invalidateAndCancel() }

        do {
            _ = try await CloudSyncClient(
                config: CloudSyncConfig(serverBaseURL: "https://sync.example.test", bearerToken: "token"),
                session: session,
                diagnosticsDefaults: defaults
            ).health()
            XCTFail("Expected HTTP failure")
        } catch let error as CloudSyncError {
            XCTAssertEqual(error.requestID, "server-header-request-123")
            XCTAssertTrue(error.localizedDescription.contains("server-heade"))
            XCTAssertFalse(error.localizedDescription.contains("json-request-ignored"))
        }

        let diagnostic = try XCTUnwrap(CloudRequestDiagnosticsStore.load(defaults: defaults))
        XCTAssertEqual(diagnostic.requestID, "server-header-request-123")
        XCTAssertEqual(diagnostic.operation, .health)
        XCTAssertEqual(diagnostic.statusCode, 503)
        XCTAssertFalse(diagnostic.succeeded)
    }

    func testJSONErrorRequestIDFallbackIsPreservedWithoutSecrets() async throws {
        let defaults = try makeDefaults()
        let token = "token-must-not-persist"
        let capturedSentence = "captured sentence must not persist"
        let lemma = "privatelemma"
        DictionaryURLProtocolStub.handler = { _ in
            (
                422,
                ["Content-Type": "application/json"],
                try DictionaryURLProtocolStub.jsonData([
                    "error": [
                        "request_id": "json-error-request-456",
                        "message": "\(token) \(capturedSentence) \(lemma)"
                    ]
                ])
            )
        }
        let session = DictionaryURLProtocolStub.session()
        defer { session.invalidateAndCancel() }

        var errorDescription = ""
        do {
            _ = try await CloudSyncClient(
                config: CloudSyncConfig(serverBaseURL: "https://sync.example.test", bearerToken: token),
                session: session,
                diagnosticsDefaults: defaults
            ).lookupDictionary(lemmas: [lemma])
            XCTFail("Expected HTTP failure")
        } catch let error as CloudSyncError {
            errorDescription = error.localizedDescription
            XCTAssertEqual(error.requestID, "json-error-request-456")
        }

        let diagnosticData = try XCTUnwrap(defaults.data(forKey: CloudRequestDiagnosticsStore.defaultsKey))
        let persisted = String(decoding: diagnosticData, as: UTF8.self)
        for secret in [token, capturedSentence, lemma] {
            XCTAssertFalse(errorDescription.contains(secret))
            XCTAssertFalse(persisted.contains(secret))
        }
        XCTAssertTrue(persisted.contains("json-error-request-456"))
    }

    func testServerErrorDescriptionDisplaysOnlyShortRequestID() {
        let requestID = "12345678-1234-1234-1234-123456789abc"
        let error = CloudSyncError.server("네트워크 요청 실패 [request_id:\(requestID)]")

        XCTAssertEqual(error.requestID, requestID)
        XCTAssertTrue(error.localizedDescription.contains(String(requestID.prefix(12))))
        XCTAssertFalse(error.localizedDescription.contains(requestID))
        XCTAssertFalse(error.localizedDescription.contains("request_id"))
    }

    private func emptySyncBatch() -> [String: Any] {
        [
            "cards": [],
            "contexts": [],
            "definition_candidates": [],
            "review_events": [],
            "recommendation_feedback": [],
            "settings": NSNull()
        ]
    }

    private func makeDefaults() throws -> UserDefaults {
        let suiteName = "CloudRequestDiagnosticsTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        return defaults
    }
}
