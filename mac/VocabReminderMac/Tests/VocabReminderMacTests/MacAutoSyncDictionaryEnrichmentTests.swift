import Foundation
import Testing
@testable import VocabCore

@Suite(.serialized)
@MainActor
final class MacAutoSyncDictionaryEnrichmentTests {
    deinit {
        DictionaryURLProtocolStub.handler = nil
    }

    @Test func testControllerAppliesResolvedMeaningAndReportsCompletion() async throws {
        let fixture = try makeBlankMeaningStore(words: ["codexcontrollerresolved"])
        defer { try? FileManager.default.removeItem(at: fixture.directory) }
        let target = try #require(fixture.store.pendingDictionaryEnrichmentTargets().first)
        DictionaryURLProtocolStub.handler = { _ in
            (
                200,
                ["Content-Type": "application/json"],
                try DictionaryURLProtocolStub.jsonData([
                    "results": [[
                        "status": "resolved",
                        "lemma": target.lemma,
                        "meaningKo": "컨트롤러가 채운 뜻",
                        "sourceType": "fixture",
                        "confidence": 1.0,
                        "errorCode": NSNull()
                    ]]
                ])
            )
        }
        let session = DictionaryURLProtocolStub.session()
        defer { session.invalidateAndCancel() }
        let defaults = try makeDefaults()
        let controller = MacAutoSyncController(
            defaults: defaults,
            retryDefaultsKey: "ledger",
            session: session,
            nowProvider: { Date(timeIntervalSince1970: 1_784_000_000) }
        )

        let summary = await controller.performDictionaryEnrichment(
            store: fixture.store,
            config: CloudSyncConfig(serverBaseURL: "https://sync.example.test", bearerToken: "token")
        )

        #expect(summary.appliedCount == 1)
        #expect(fixture.store.cards.first?.meaningKo == "컨트롤러가 채운 뜻")
        #expect(fixture.store.dictionaryEnrichmentStatus.resolvedCount == 1)
        #expect(fixture.store.dictionaryEnrichmentStatus.providerFailureCount == 0)
    }

    @Test func testNotFoundAndProviderFailureRemainDistinctWithProviderRetryFirst() async throws {
        let fixture = try makeBlankMeaningStore(words: ["codexnotfound", "codexproviderfailure"])
        defer { try? FileManager.default.removeItem(at: fixture.directory) }
        let targets = fixture.store.pendingDictionaryEnrichmentTargets()
        #expect(targets.count == 2)
        DictionaryURLProtocolStub.handler = { _ in
            return (
                200,
                ["Content-Type": "application/json"],
                try DictionaryURLProtocolStub.jsonData([
                    "results": [
                        [
                            "status": "pending",
                            "lemma": targets[0].lemma,
                            "meaningKo": "",
                            "errorCode": "not_found"
                        ],
                        [
                            "status": "pending",
                            "lemma": targets[1].lemma,
                            "meaningKo": "",
                            "errorCode": "provider_failure"
                        ]
                    ]
                ])
            )
        }
        let session = DictionaryURLProtocolStub.session()
        defer { session.invalidateAndCancel() }
        let now = Date(timeIntervalSince1970: 1_784_000_000)
        let controller = MacAutoSyncController(
            defaults: try makeDefaults(),
            retryDefaultsKey: "ledger",
            session: session,
            nowProvider: { now }
        )

        let summary = await controller.performDictionaryEnrichment(
            store: fixture.store,
            config: CloudSyncConfig(serverBaseURL: "https://sync.example.test", bearerToken: "token")
        )

        #expect(summary.appliedCount == 0)
        #expect(fixture.store.dictionaryEnrichmentStatus.notFoundCount == 1)
        #expect(fixture.store.dictionaryEnrichmentStatus.providerFailureCount == 1)
        #expect(summary.nextRetryAt == now.addingTimeInterval(60))
        #expect(fixture.store.pendingDictionaryEnrichmentTargets().count == 2)
    }

    @Test func testOldServerEndpointIsNonFatalAndBackoffPreventsImmediateRepeat() async throws {
        let fixture = try makeBlankMeaningStore(words: ["codexoldserver"])
        defer { try? FileManager.default.removeItem(at: fixture.directory) }
        let counter = LockedRequestCounter()
        DictionaryURLProtocolStub.handler = { _ in
            counter.increment()
            return (404, ["Content-Type": "application/json"], Data(#"{"error":{"message":"Not found."}}"#.utf8))
        }
        let session = DictionaryURLProtocolStub.session()
        defer { session.invalidateAndCancel() }
        let now = Date(timeIntervalSince1970: 1_784_000_000)
        let controller = MacAutoSyncController(
            defaults: try makeDefaults(),
            retryDefaultsKey: "ledger",
            session: session,
            nowProvider: { now }
        )
        let config = CloudSyncConfig(serverBaseURL: "https://old.example.test", bearerToken: "token")

        let first = await controller.performDictionaryEnrichment(store: fixture.store, config: config)
        let second = await controller.performDictionaryEnrichment(store: fixture.store, config: config)

        #expect(first.appliedCount == 0)
        #expect(second.appliedCount == 0)
        #expect(counter.value == 1)
        #expect(fixture.store.dictionaryEnrichmentStatus.endpointUnavailable)
        #expect(first.nextRetryAt == now.addingTimeInterval(MacAutoSyncController.endpointUnavailableRetryDelay))
        #expect(fixture.store.cards.first?.meaningKo == "")
    }

    @Test func testRetryPolicyHasFiniteCapsForEveryPendingOutcome() {
        #expect(DictionaryEnrichmentRetryLedger.retryDelay(outcome: .pending, attemptCount: 99) == 6 * 60 * 60)
        #expect(DictionaryEnrichmentRetryLedger.retryDelay(outcome: .notFound, attemptCount: 99) == 7 * 24 * 60 * 60)
        #expect(DictionaryEnrichmentRetryLedger.retryDelay(outcome: .providerFailure, attemptCount: 99) == 60 * 60)
    }

    @Test func testRetryLedgerSurvivesControllerRecreation() throws {
        let defaults = try makeDefaults()
        let now = Date(timeIntervalSince1970: 1_784_000_000)
        let first = DictionaryEnrichmentRetryLedger(defaults: defaults, key: "persisted-ledger")
        first.record(cardId: "card-1", outcome: .providerFailure, at: now)

        let reloaded = DictionaryEnrichmentRetryLedger(defaults: defaults, key: "persisted-ledger")
        #expect(!(reloaded.isEligible(cardId: "card-1", at: now.addingTimeInterval(59))))
        #expect(reloaded.isEligible(cardId: "card-1", at: now.addingTimeInterval(60)))
    }

    private func makeDefaults() throws -> UserDefaults {
        let suiteName = "MacAutoSyncDictionaryEnrichmentTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        return defaults
    }
}
