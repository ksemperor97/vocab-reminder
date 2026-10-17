import AppKit
import XCTest

/// Drives the packaged menu bar app (VocabReminder-E2E.app) the way a person
/// would: open it from the menu bar, type a word, check the preview, save,
/// press "지금 동기화", edit a meaning in the card editor.
///
/// tools/e2e/run_real_e2e.py runs one test per step and passes parameters as
/// TEST_RUNNER_VOCAB_E2E_* variables (xcodebuild strips the prefix):
///   VOCAB_E2E_APP        path to VocabReminder-E2E.app
///   VOCAB_E2E_DATA_DIR   scratch data directory for the app
///   VOCAB_E2E_SERVER     sync server URL
///   VOCAB_E2E_TOKEN      sync bearer token
///   VOCAB_E2E_WORD       word to act on
///   VOCAB_E2E_MEANING    meaning to save, expect, or write
final class MenuBarAppUITests: XCTestCase {
    private var environment: [String: String] { ProcessInfo.processInfo.environment }
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
    }

    override func tearDown() {
        app?.terminate()
    }

    // MARK: Steps

    /// Capture a new word from the main window and sync it to the server.
    @MainActor
    func testCaptureWordAndSync() throws {
        let word = try value("VOCAB_E2E_WORD")
        let meaning = try value("VOCAB_E2E_MEANING")
        try launchAndOpenMainWindow()

        let field = app.textFields["capture_text_field"]
        XCTAssertTrue(field.waitForExistence(timeout: 10), "capture field missing")
        focus(field)
        app.typeText(word)
        press(app.buttons["capture_preview_button"])

        let meaningField = app.textFields["preview_meaning_field"]
        XCTAssertTrue(meaningField.waitForExistence(timeout: 10), "preview did not appear")
        replaceText(in: meaningField, with: meaning)
        press(app.buttons["preview_save_button"])

        XCTAssertTrue(element("card_word_\(lemma(word))").waitForExistence(timeout: 10), "saved card is not listed")
        try syncNow()
        try screenshot()
    }

    /// Sync and expect a word (saved on another device) with a given meaning.
    @MainActor
    func testWordArrivesAfterSync() throws {
        let word = try value("VOCAB_E2E_WORD")
        let meaning = try value("VOCAB_E2E_MEANING")
        try launchAndOpenMainWindow()
        try syncNow()

        let row = element("sidebar_word_\(lemma(word))")
        XCTAssertTrue(row.waitForExistence(timeout: 15), "\(word) did not arrive")
        let meaningLabel = element("card_meaning_\(lemma(word))")
        XCTAssertTrue(meaningLabel.waitForExistence(timeout: 5), "\(word) is not in the recent list")
        XCTAssertTrue(text(of: meaningLabel).contains(meaning), "meaning shown: \(text(of: meaningLabel))")
        try screenshot()
    }

    /// Edit a card's meaning in the card editor and sync the change.
    @MainActor
    func testEditMeaningAndSync() throws {
        let word = try value("VOCAB_E2E_WORD")
        let meaning = try value("VOCAB_E2E_MEANING")
        try launchAndOpenMainWindow()
        try syncNow()

        let row = element("sidebar_word_\(lemma(word))")
        XCTAssertTrue(row.waitForExistence(timeout: 15), "\(word) is not in the sidebar")
        press(row)
        let editorField = app.textFields["editor_meaning_field"]
        XCTAssertTrue(editorField.waitForExistence(timeout: 10), "card editor did not open")
        replaceText(in: editorField, with: meaning)
        press(app.buttons["editor_save_button"])
        try screenshot()
        press(app.buttons["editor_close_button"])

        let meaningLabel = element("card_meaning_\(lemma(word))")
        XCTAssertTrue(meaningLabel.waitForExistence(timeout: 10))
        XCTAssertTrue(text(of: meaningLabel).contains(meaning), "edit not saved: \(text(of: meaningLabel))")
        try syncNow()
    }

    // MARK: Helpers

    @MainActor
    private func launchAndOpenMainWindow() throws {
        let appPath = try value("VOCAB_E2E_APP")
        let server = try value("VOCAB_E2E_SERVER")
        let token = try value("VOCAB_E2E_TOKEN")
        app = XCUIApplication(url: URL(fileURLWithPath: appPath))
        app.launchEnvironment = [
            "VOCAB_REMINDER_DATA_DIR": try value("VOCAB_E2E_DATA_DIR"),
            "VOCAB_SYNC_DEFAULT_URL": server,
            "VOCAB_SYNC_DEFAULT_TOKEN": token
        ]
        // Argument-domain defaults win over anything stored and are never persisted.
        app.launchArguments = ["-syncServerURL", server, "-syncBearerToken", token]
        app.launch()

        if !app.textFields["capture_text_field"].waitForExistence(timeout: 4) {
            // Not opened at launch: use the menu bar item like a person would.
            let statusItem = app.statusItems.firstMatch
            XCTAssertTrue(statusItem.waitForExistence(timeout: 10), "menu bar item missing")
            statusItem.click()
            let open = app.menuItems["최근 저장 단어"]
            XCTAssertTrue(open.waitForExistence(timeout: 5), "menu bar menu did not open")
            open.click()
        }
        XCTAssertTrue(app.textFields["capture_text_field"].waitForExistence(timeout: 10), "main window did not open")
        app.activate()
        waitUntilSettled(app.textFields["capture_text_field"])
    }

    /// Waits until an element stops moving (window placement, Stage Manager
    /// and scroll animations), so a coordinate click lands where we aim.
    @MainActor
    private func waitUntilSettled(_ element: XCUIElement, timeout: TimeInterval = 8) {
        let deadline = Date().addingTimeInterval(timeout)
        var last = element.frame
        var stableSince = Date()
        while Date() < deadline {
            Thread.sleep(forTimeInterval: 0.25)
            let now = element.frame
            if now != last {
                last = now
                stableSince = Date()
            } else if Date().timeIntervalSince(stableSince) >= 0.75 {
                return
            }
        }
    }

    @MainActor
    private func syncNow() throws {
        let button = app.buttons["sync_now_button"]
        XCTAssertTrue(button.waitForExistence(timeout: 10), "sync button missing")
        press(button)
        let done = NSPredicate(format: "label CONTAINS %@ OR value CONTAINS %@", "동기화 완료", "동기화 완료")
        let message = app.staticTexts.matching(identifier: "status_message").matching(done).firstMatch
        XCTAssertTrue(message.waitForExistence(timeout: 30), "sync did not finish: \(statusText())")
    }

    @MainActor
    private func replaceText(in field: XCUIElement, with text: String) {
        focus(field)
        app.typeKey("a", modifierFlags: .command)
        app.typeKey(.delete, modifierFlags: [])
        app.typeText(text)
    }

    /// Keyboard input goes to the frontmost app, so take focus right before typing.
    @MainActor
    private func focus(_ field: XCUIElement) {
        app.activate()
        press(field)
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 5), "app did not come to the front")
    }

    /// Clicks at the element's centre on screen. XCTest's own click() refuses
    /// when any other app's window overlaps the element's frame, even though
    /// the E2E window floats above them; a coordinate click goes to whatever is
    /// really on top, which is this window.
    @MainActor
    private func press(_ element: XCUIElement) {
        XCTAssertTrue(element.waitForExistence(timeout: 10), "\(element) missing")
        reveal(element)
        waitUntilSettled(element)
        element.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).click()
    }

    /// Scrolls the main window until the element sits inside the screen's usable
    /// area. The E2E window fills that area's height at the left edge (see
    /// IsolatedRunWindowPlacement in the app), so screen geometry is enough.
    @MainActor
    private func reveal(_ element: XCUIElement) {
        guard let screen = NSScreen.main else { return }
        let bottom = screen.frame.height - screen.visibleFrame.minY - 12
        let top = screen.frame.height - screen.visibleFrame.maxY + 90
        let scrollPoint = app.coordinate(withNormalizedOffset: .zero)
            .withOffset(CGVector(dx: -app.frame.minX + 560, dy: -app.frame.minY + screen.frame.height / 2))
        var attempts = 0
        while element.frame.maxY > bottom && attempts < 15 {
            scrollPoint.scroll(byDeltaX: 0, deltaY: -120)
            attempts += 1
        }
        attempts = 0
        while element.frame.minY < top && attempts < 15 {
            scrollPoint.scroll(byDeltaX: 0, deltaY: 120)
            attempts += 1
        }
    }

    @MainActor
    private func element(_ identifier: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    @MainActor
    private func text(of element: XCUIElement) -> String {
        [element.label, element.value as? String ?? ""].joined(separator: " ")
    }

    @MainActor
    private func statusText() -> String {
        app.staticTexts.matching(identifier: "status_message").allElementsBoundByIndex.map(\.label).joined(separator: " | ")
    }

    /// Kept in the result bundle; the harness exports it as evidence (the test
    /// runner is sandboxed and cannot write to arbitrary paths).
    @MainActor
    private func screenshot() throws {
        let attachment = XCTAttachment(screenshot: app.windows.firstMatch.screenshot())
        attachment.name = "window"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func value(_ key: String) throws -> String {
        try XCTUnwrap(environment[key].flatMap { $0.isEmpty ? nil : $0 }, "\(key) is required")
    }

    private func lemma(_ word: String) -> String {
        word.trimmingCharacters(in: .whitespaces).lowercased()
    }
}
