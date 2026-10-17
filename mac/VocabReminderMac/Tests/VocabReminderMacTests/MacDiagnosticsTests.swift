import Foundation
import XCTest
@testable import VocabReminderMac

@MainActor
final class MacDiagnosticsTests: XCTestCase {
    func testURLCaptureDistinguishesMechanismAndRemovesSensitiveURLParts() throws {
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

        let payload = try XCTUnwrap(MacCaptureURLParser.parse(try XCTUnwrap(outer.url)))
        XCTAssertEqual(payload.text, "Resilient systems recover.")
        XCTAssertEqual(payload.sourceURL, "https://example.com/docs/page")
        XCTAssertEqual(payload.sourceApp, MacCaptureSource.urlScheme)

        let context = StoredContext(
            id: "context-1",
            cardId: "card-1",
            sourceSentence: payload.text,
            sourceURL: payload.sourceURL,
            sourceApp: MacCaptureSource.urlScheme,
            captureMethod: .macService,
            createdAt: Date()
        )
        XCTAssertEqual(MacCaptureSource.mechanismLabel(for: context), "URL scheme")
        XCTAssertNil(MacCaptureSource.originalApplicationLabel(for: context))

        outer.queryItems?.append(URLQueryItem(name: "capture_source", value: "mac_service"))
        let servicePayload = try XCTUnwrap(MacCaptureURLParser.parse(try XCTUnwrap(outer.url)))
        XCTAssertEqual(servicePayload.sourceApp, MacCaptureSource.service)
        var serviceContext = context
        serviceContext.sourceApp = servicePayload.sourceApp
        XCTAssertEqual(MacCaptureSource.mechanismLabel(for: serviceContext), "macOS Service")
    }

    func testSourceURLSanitizerRejectsNonWebAndMalformedOrigins() {
        XCTAssertNil(SourceURLSanitizer.sanitize("file:///Users/private/secret.txt"))
        XCTAssertNil(SourceURLSanitizer.sanitize("javascript:alert(1)"))
        XCTAssertNil(SourceURLSanitizer.sanitize("https:///missing-host"))
        XCTAssertEqual(
            SourceURLSanitizer.sanitize("http://EXAMPLE.com:80/path?q=private#fragment"),
            "http://example.com/path"
        )
    }

    func testCapturePersistsCanonicalURLMechanismAndOnlySanitizedOrigin() throws {
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

        let context = try XCTUnwrap(store.contexts.first { $0.captureMethod == .macService })
        XCTAssertEqual(context.sourceApp, MacCaptureSource.urlScheme)
        XCTAssertEqual(context.sourceURL, "https://example.test/source")
        XCTAssertEqual(MacCaptureSource.mechanismLabel(for: context), "URL scheme")
        XCTAssertNil(MacCaptureSource.originalApplicationLabel(for: context))
    }

    func testServiceInspectionChecksBundleFilesAndPBSState() throws {
        let home = try makeBlankDirectory(prefix: "MacServiceInspectionTests")
        defer { try? FileManager.default.removeItem(at: home) }
        XCTAssertEqual(MacCaptureServiceManager.inspect(homeDirectory: home), .missing)

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
        XCTAssertTrue(status.installed)
        XCTAssertTrue(status.enabled)
    }

    func testLegacySnapshotWithoutSourceURLOrSyncEventsStillDecodes() throws {
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
        var payload = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
        payload.removeValue(forKey: "syncEvents")
        var contexts = try XCTUnwrap(payload["contexts"] as? [[String: Any]])
        contexts[0].removeValue(forKey: "sourceURL")
        contexts[0].removeValue(forKey: "pageTitle")
        payload["contexts"] = contexts

        let decoded = try JSONDecoder.vocab.decode(
            StoreSnapshot.self,
            from: JSONSerialization.data(withJSONObject: payload)
        )
        XCTAssertNil(decoded.contexts.first?.sourceURL)
        XCTAssertEqual(decoded.contexts.first?.pageTitle, "")
        XCTAssertTrue(decoded.syncEvents.isEmpty)
    }

    private func makeBlankDirectory(prefix: String) throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(prefix)-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }
}
