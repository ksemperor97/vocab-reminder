import Foundation
import Testing
@testable import VocabCore

@Suite(.serialized)
@MainActor
final class MacDiagnosticsTests {
    @Test func testURLCaptureDistinguishesMechanismAndRemovesSensitiveURLParts() throws {
        var outer = URLComponents()
        outer.scheme = "vocabreminder"
        outer.host = "capture"
        outer.queryItems = [
            URLQueryItem(name: "text", value: "Resilient systems recover."),
            URLQueryItem(
                name: "source_url",
                value: "HTTPS://alice:password@Example.COM:443/docs/page?token=secret#private"
            )
        ]

        let outerURL = try #require(outer.url)
        let payload = try #require(MacCaptureURLParser.parse(outerURL))
        #expect(payload.text == "Resilient systems recover.")
        #expect(payload.sourceURL == "https://example.com/docs/page")
        #expect(payload.sourceApp == MacCaptureSource.urlScheme)

        let context = StoredContext(
            id: "context-1",
            cardId: "card-1",
            sourceSentence: payload.text,
            sourceURL: payload.sourceURL,
            sourceApp: MacCaptureSource.urlScheme,
            captureMethod: .macService,
            createdAt: Date()
        )
        #expect(MacCaptureSource.mechanismLabel(for: context) == "URL scheme")
        #expect(MacCaptureSource.originalApplicationLabel(for: context) == nil)

        outer.queryItems?.append(URLQueryItem(name: "capture_source", value: "mac_service"))
        let serviceURL = try #require(outer.url)
        let servicePayload = try #require(MacCaptureURLParser.parse(serviceURL))
        #expect(servicePayload.sourceApp == MacCaptureSource.service)
        var serviceContext = context
        serviceContext.sourceApp = servicePayload.sourceApp
        #expect(MacCaptureSource.mechanismLabel(for: serviceContext) == "macOS Service")
    }

    @Test func testSourceURLSanitizerRejectsNonWebAndMalformedOrigins() {
        #expect(SourceURLSanitizer.sanitize("file:///Users/private/secret.txt") == nil)
        #expect(SourceURLSanitizer.sanitize("javascript:alert(1)") == nil)
        #expect(SourceURLSanitizer.sanitize("https:///missing-host") == nil)
        #expect(SourceURLSanitizer.sanitize("http://EXAMPLE.com:80/path?q=private#fragment") == "http://example.com/path")
    }

    @Test func testCapturePersistsCanonicalURLMechanismAndOnlySanitizedOrigin() throws {
        let fixture = try makeBlankDirectory(prefix: "MacCaptureOriginTests")
        defer { try? FileManager.default.removeItem(at: fixture) }
        let store = LocalVocabularyStore(appSupportURL: fixture, scheduleNotifications: false)

        store.capture(
            text: "codexoriginword",
            method: .macService,
            sourceApp: MacCaptureSource.urlScheme,
            sourceURL: "https://user:pass@Example.test/source?auth=secret#selection"
        )
        store.savePreview(meaningOverride: "뜻", exampleOverride: "문맥", method: .macService)

        let context = try #require(store.contexts.first { $0.captureMethod == .macService })
        #expect(context.sourceApp == MacCaptureSource.urlScheme)
        #expect(context.sourceURL == "https://example.test/source")
        #expect(MacCaptureSource.mechanismLabel(for: context) == "URL scheme")
        #expect(MacCaptureSource.originalApplicationLabel(for: context) == nil)
    }

    @Test func testServiceInspectionChecksBundleFilesAndPBSState() throws {
        let home = try makeBlankDirectory(prefix: "MacServiceInspectionTests")
        defer { try? FileManager.default.removeItem(at: home) }
        #expect(MacCaptureServiceManager.inspect(homeDirectory: home) == .missing)

        let service = MacCaptureServiceManager.serviceDirectory(homeDirectory: home)
        let resources = service.appendingPathComponent("Contents/Resources", isDirectory: true)
        try FileManager.default.createDirectory(at: resources, withIntermediateDirectories: true)
        let info: [String: Any] = ["CFBundleIdentifier": MacCaptureServiceManager.bundleIdentifier]
        try PropertyListSerialization.data(fromPropertyList: info, format: .xml, options: 0)
            .write(to: service.appendingPathComponent("Contents/Info.plist"))
        try Data("#!/bin/sh\n".utf8).write(to: resources.appendingPathComponent("capture.sh"))
        try Data("workflow".utf8).write(to: resources.appendingPathComponent("document.wflow"))

        let preferencesURL = home.appendingPathComponent("pbs.plist")
        let preferences: [String: Any] = [
            "NSServicesStatus": [
                MacCaptureServiceManager.englishServiceKey: [
                    "enabled_context_menu": "1",
                    "enabled_services_menu": "1"
                ]
            ]
        ]
        try PropertyListSerialization.data(fromPropertyList: preferences, format: .binary, options: 0)
            .write(to: preferencesURL)

        let status = MacCaptureServiceManager.inspect(
            homeDirectory: home,
            preferencesURL: preferencesURL
        )
        #expect(status.installed)
        #expect(status.enabled)
    }

    @Test func testLegacySnapshotWithoutSourceURLOrSyncEventsStillDecodes() throws {
        let now = Date(timeIntervalSince1970: 1_784_000_000)
        let card = VocabularyCard(
            id: "legacy-card",
            lemma: "legacy",
            displayWord: "legacy",
            meaningKo: "레거시",
            definitionEn: "",
            partOfSpeech: "noun",
            exampleSentence: "Legacy context.",
            definitionSourcePrimary: "user",
            status: .new,
            wordbook: .user,
            dueAt: now,
            intervalDays: 0,
            lapseCount: 0,
            intervalBeforeLapse: nil,
            recommendationStartedAt: nil,
            deletedAt: nil,
            createdAt: now,
            updatedAt: now,
            version: 1
        )
        let snapshot = StoreSnapshot(
            cards: [card],
            contexts: [StoredContext(
                id: "legacy-context",
                cardId: card.id,
                sourceSentence: "Legacy context.",
                sourceApp: "macOS Services",
                captureMethod: .macService,
                createdAt: now
            )],
            definitionCandidates: [],
            reviewEvents: [],
            settings: UserSettingsData()
        )
        let encoded = try JSONEncoder.vocab.encode(snapshot)
        var payload = try #require(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        payload.removeValue(forKey: "syncEvents")
        var contexts = try #require(payload["contexts"] as? [[String: Any]])
        contexts[0].removeValue(forKey: "sourceURL")
        contexts[0].removeValue(forKey: "pageTitle")
        payload["contexts"] = contexts

        let decoded = try JSONDecoder.vocab.decode(
            StoreSnapshot.self,
            from: JSONSerialization.data(withJSONObject: payload)
        )
        #expect(decoded.contexts.first?.sourceURL == nil)
        #expect(decoded.contexts.first?.pageTitle == "")
        #expect(decoded.syncEvents.isEmpty)
    }

    private func makeBlankDirectory(prefix: String) throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(prefix)-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }
}
