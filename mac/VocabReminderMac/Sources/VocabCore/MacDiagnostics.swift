import Foundation

package enum MacCaptureSource {
    package static let service = "macOS Service"
    package static let clipboard = "macOS clipboard capture"
    package static let urlScheme = "vocabreminder URL scheme"
    package static let manual = "Manual entry"

    package static func canonicalSourceApp(method: CaptureMethod, declaredSourceApp: String) -> String {
        let lowered = declaredSourceApp
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        switch method {
        case .macClipboardHotkey:
            return clipboard
        case .macService:
            return lowered.contains("url") || lowered.contains("quick action")
                ? urlScheme
                : service
        case .manual:
            return manual
        case .recommendation:
            return "Vocabulary Recommendation"
        case .androidProcessText:
            return "Android text selection"
        case .androidShare:
            return "Android share"
        }
    }

    package static func mechanismLabel(for context: StoredContext) -> String {
        let source = context.sourceApp.lowercased()
        switch context.captureMethod {
        case .macClipboardHotkey:
            return "클립보드 단축키/버튼"
        case .macService:
            return source.contains("url") || source.contains("quick action")
                ? "URL scheme"
                : "macOS Service"
        case .manual:
            return "앱에서 직접 입력"
        case .recommendation:
            return "추천 단어"
        case .androidProcessText:
            return "Android 텍스트 선택"
        case .androidShare:
            return "Android 공유"
        }
    }

    package static func originalApplicationLabel(for context: StoredContext) -> String? {
        // The current capture paths reliably identify the mechanism, not the
        // application that originally produced clipboard/Services text.
        // Do not turn mechanism labels from legacy data into fake provenance.
        let generic = [
            "", "mac", "clipboard", "macos services", "macos service",
            "macos quick action", "vocabreminder url scheme", "manual entry",
            "mac manual", "self-check", "xctest"
        ]
        let normalized = context.sourceApp
            .folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
            .lowercased()
        guard generic.contains(normalized) == false,
              normalized.contains("android share") == false,
              normalized.contains("android selection") == false,
              normalized.contains("clipboard") == false,
              normalized.contains("service") == false,
              normalized.contains("quick action") == false,
              normalized.contains("url scheme") == false
        else {
            return nil
        }
        return context.sourceApp.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

package struct MacURLCapturePayload: Equatable, Sendable {
    package init(
        text: String,
        sourceURL: String? = nil,
        sourceApp: String
    ) {
        self.text = text
        self.sourceURL = sourceURL
        self.sourceApp = sourceApp
    }

    package var text: String
    package var sourceURL: String?
    package var sourceApp: String
}

package enum MacCaptureURLParser {
    package static func parse(_ url: URL) -> MacURLCapturePayload? {
        guard url.scheme?.lowercased() == "vocabreminder",
              url.host?.lowercased() == "capture",
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let text = components.queryItems?
                .first(where: { $0.name == "text" })?
                .value?
                .trimmingCharacters(in: .whitespacesAndNewlines),
              text.isEmpty == false
        else {
            return nil
        }
        let rawSourceURL = components.queryItems?
            .first(where: { $0.name == "source_url" })?
            .value
        // `capture_source=mac_service` is a mechanism marker emitted by the
        // app-installed workflow. It is not treated as the name of the
        // application where the text originally appeared.
        let declaredMechanism = components.queryItems?
            .first(where: { $0.name == "capture_source" })?
            .value
        return MacURLCapturePayload(
            text: text,
            sourceURL: SourceURLSanitizer.sanitize(rawSourceURL),
            sourceApp: declaredMechanism == "mac_service"
                ? MacCaptureSource.service
                : MacCaptureSource.urlScheme
        )
    }
}

package enum SourceURLSanitizer {
    package static func sanitize(_ rawValue: String?) -> String? {
        guard let rawValue else { return nil }
        let trimmed = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.isEmpty == false,
              trimmed.utf8.count <= 8_192,
              var components = URLComponents(string: trimmed),
              let rawScheme = components.scheme?.lowercased(),
              rawScheme == "https" || rawScheme == "http",
              let rawHost = components.host?.trimmingCharacters(in: .whitespacesAndNewlines),
              rawHost.isEmpty == false
        else {
            return nil
        }

        components.scheme = rawScheme
        components.host = rawHost.lowercased()
        components.user = nil
        components.password = nil
        components.query = nil
        components.fragment = nil
        if (rawScheme == "https" && components.port == 443) ||
            (rawScheme == "http" && components.port == 80) {
            components.port = nil
        }
        guard let sanitized = components.string, sanitized.utf8.count <= 2_048 else { return nil }
        return sanitized
    }
}

package enum CaptureDiagnosticOutcome: String, Codable, Sendable {
    case succeeded
    case failed
}

package struct CaptureDiagnosticRecord: Codable, Equatable, Sendable {
    package init(
        outcome: CaptureDiagnosticOutcome,
        mechanism: String,
        detailCode: String,
        occurredAt: Date
    ) {
        self.outcome = outcome
        self.mechanism = mechanism
        self.detailCode = detailCode
        self.occurredAt = occurredAt
    }

    package var outcome: CaptureDiagnosticOutcome
    package var mechanism: String
    package var detailCode: String
    package var occurredAt: Date
}

package enum CaptureDiagnosticStore {
    package static let defaultsKey = "lastMacCaptureDiagnosticV1"

    package static func load(defaults: UserDefaults = .standard) -> CaptureDiagnosticRecord? {
        guard let data = defaults.data(forKey: defaultsKey) else { return nil }
        return try? JSONDecoder.vocab.decode(CaptureDiagnosticRecord.self, from: data)
    }

    package static func save(_ record: CaptureDiagnosticRecord, defaults: UserDefaults = .standard) {
        guard let data = try? JSONEncoder.vocab.encode(record) else { return }
        defaults.set(data, forKey: defaultsKey)
    }
}

package struct MacCaptureServiceStatus: Equatable, Sendable {
    package init(
        installed: Bool,
        enabled: Bool,
        detail: String
    ) {
        self.installed = installed
        self.enabled = enabled
        self.detail = detail
    }

    package var installed: Bool
    package var enabled: Bool
    package var detail: String

    package static let missing = MacCaptureServiceStatus(
        installed: false,
        enabled: false,
        detail: "Service가 설치되지 않았습니다."
    )
}

package enum MacCaptureServiceManager {
    package static let workflowName = "Add to Vocabulary Reminder.workflow"
    package static let bundleIdentifier = "com.vocabreminder.capture.workflow"
    package static let englishServiceKey =
        "com.vocabreminder.capture.workflow - Add to Vocabulary Reminder - runWorkflowAsService"
    package static let koreanServiceKey =
        "com.vocabreminder.capture.workflow - Vocabulary Reminder에 추가 - runWorkflowAsService"

    package static func serviceDirectory(homeDirectory: URL = FileManager.default.homeDirectoryForCurrentUser) -> URL {
        homeDirectory
            .appendingPathComponent("Library/Services", isDirectory: true)
            .appendingPathComponent(workflowName, isDirectory: true)
    }

    package static func inspect(
        homeDirectory: URL = FileManager.default.homeDirectoryForCurrentUser,
        preferencesURL: URL? = nil
    ) -> MacCaptureServiceStatus {
        let serviceDirectory = serviceDirectory(homeDirectory: homeDirectory)
        let infoURL = serviceDirectory.appendingPathComponent("Contents/Info.plist")
        let scriptURL = serviceDirectory.appendingPathComponent("Contents/Resources/capture.sh")
        let workflowURL = serviceDirectory.appendingPathComponent("Contents/Resources/document.wflow")
        let fileManager = FileManager.default

        guard fileManager.fileExists(atPath: infoURL.path),
              fileManager.fileExists(atPath: scriptURL.path),
              fileManager.fileExists(atPath: workflowURL.path),
              let infoData = try? Data(contentsOf: infoURL),
              let info = try? PropertyListSerialization.propertyList(from: infoData, format: nil) as? [String: Any],
              info["CFBundleIdentifier"] as? String == bundleIdentifier
        else {
            return .missing
        }

        let preferencesURL = preferencesURL ?? homeDirectory
            .appendingPathComponent("Library/Preferences/pbs.plist")
        let enabled = serviceIsEnabled(preferencesURL: preferencesURL)
        return MacCaptureServiceStatus(
            installed: true,
            enabled: enabled,
            detail: enabled
                ? "Service가 설치되어 우클릭 메뉴에서 사용할 수 있습니다."
                : "Service 파일은 있지만 macOS 서비스 메뉴에서 꺼져 있습니다."
        )
    }

    package static func install(homeDirectory: URL = FileManager.default.homeDirectoryForCurrentUser) throws {
        let directory = serviceDirectory(homeDirectory: homeDirectory)
        let contents = directory.appendingPathComponent("Contents", isDirectory: true)
        let resources = contents.appendingPathComponent("Resources", isDirectory: true)
        let fileManager = FileManager.default
        try fileManager.createDirectory(at: resources, withIntermediateDirectories: true)

        let info: [String: Any] = [
            "CFBundleDevelopmentRegion": "en_US",
            "CFBundleIdentifier": bundleIdentifier,
            "CFBundleName": "Add to Vocabulary Reminder",
            "CFBundleShortVersionString": "1.1",
            "NSServices": [[
                "NSMenuItem": [
                    "default": "Add to Vocabulary Reminder",
                    "en": "Add to Vocabulary Reminder",
                    "English": "Add to Vocabulary Reminder",
                    "Korean": "Vocabulary Reminder에 추가"
                ],
                "NSMessage": "runWorkflowAsService",
                "NSSendTypes": ["public.utf8-plain-text", "NSStringPboardType"]
            ]]
        ]
        try propertyListData(info).write(
            to: contents.appendingPathComponent("Info.plist"),
            options: .atomic
        )

        let scriptURL = resources.appendingPathComponent("capture.sh")
        try Data(captureScript.utf8).write(to: scriptURL, options: .atomic)
        try fileManager.setAttributes([.posixPermissions: 0o755], ofItemAtPath: scriptURL.path)

        let command = "/bin/sh \(shellQuote(scriptURL.path)) \"$@\""
        let workflow = workflowPropertyList(command: command)
        try propertyListData(workflow).write(
            to: resources.appendingPathComponent("document.wflow"),
            options: .atomic
        )

        try run("/usr/bin/defaults", arguments: [
            "write", "pbs", "NSServicesStatus", "-dict-add", englishServiceKey,
            "{ \"enabled_context_menu\" = 1; \"enabled_services_menu\" = 1; }"
        ])
        try run("/usr/bin/defaults", arguments: [
            "write", "pbs", "NSServicesStatus", "-dict-add", koreanServiceKey,
            "{ \"enabled_context_menu\" = 1; \"enabled_services_menu\" = 1; }"
        ])
        try run("/System/Library/CoreServices/pbs", arguments: ["-flush"])
        try run("/System/Library/CoreServices/pbs", arguments: ["en", "ko"])
    }

    private static func serviceIsEnabled(preferencesURL: URL) -> Bool {
        guard let data = try? Data(contentsOf: preferencesURL),
              let root = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any],
              let statuses = root["NSServicesStatus"] as? [String: Any]
        else {
            return false
        }
        return [englishServiceKey, koreanServiceKey].contains { key in
            guard let status = statuses[key] as? [String: Any] else { return false }
            return enabledValue(status["enabled_context_menu"]) ||
                enabledValue(status["enabled_services_menu"])
        }
    }

    private static func enabledValue(_ value: Any?) -> Bool {
        if let value = value as? Bool { return value }
        if let value = value as? NSNumber { return value.boolValue }
        if let value = value as? String { return value == "1" || value.lowercased() == "true" }
        return false
    }

    private static func propertyListData(_ object: Any) throws -> Data {
        try PropertyListSerialization.data(
            fromPropertyList: object,
            format: .xml,
            options: 0
        )
    }

    private static func workflowPropertyList(command: String) -> [String: Any] {
        [
            "AMApplicationBuild": "522",
            "AMApplicationVersion": "2.10",
            "AMDocumentVersion": "2",
            "actions": [[
                "action": [
                    "ActionBundlePath": "/System/Library/Automator/Run Shell Script.action",
                    "ActionName": "Run Shell Script",
                    "ActionParameters": [
                        "CheckedForUserDefaultShell": false,
                        "COMMAND_STRING": command,
                        "inputMethod": 0,
                        "shell": "/bin/sh",
                        "source": command
                    ],
                    "AMAccepts": [
                        "Container": "List",
                        "Optional": false,
                        "Types": ["com.apple.cocoa.string"]
                    ],
                    "AMActionVersion": "2.0.3",
                    "AMApplication": "Automator",
                    "AMProvides": [
                        "Container": "List",
                        "Types": ["com.apple.cocoa.string"]
                    ],
                    "BundleIdentifier": "com.apple.RunShellScript",
                    "CFBundleVersion": "2.0.3",
                    "Class Name": "RunShellScriptAction",
                    "InputUUID": UUID().uuidString,
                    "OutputUUID": UUID().uuidString,
                    "UUID": UUID().uuidString,
                    "isViewVisible": true
                ],
                "isViewVisible": true
            ]],
            "workflowMetaData": [
                "serviceApplicationBundleID": "",
                "serviceInputTypeIdentifier": "com.apple.Automator.text",
                "serviceOutputTypeIdentifier": "com.apple.Automator.nothing",
                "serviceProcessesInput": 0,
                "workflowTypeIdentifier": "com.apple.Automator.servicesMenu"
            ]
        ]
    }

    private static func shellQuote(_ value: String) -> String {
        "'" + value.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }

    private static func run(_ executable: String, arguments: [String]) throws {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: executable)
        process.arguments = arguments
        let errorPipe = Pipe()
        process.standardOutput = FileHandle.nullDevice
        process.standardError = errorPipe
        try process.run()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else {
            let errorData = errorPipe.fileHandleForReading.readDataToEndOfFile()
            let error = String(data: errorData, encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            throw NSError(
                domain: "MacCaptureServiceManager",
                code: Int(process.terminationStatus),
                userInfo: [NSLocalizedDescriptionKey: error.isEmpty ? "Service 등록 명령이 실패했습니다." : error]
            )
        }
    }

    private static let captureScript = #"""
#!/bin/sh
LOG="$HOME/Library/Logs/VocabReminderService.log"
{
  printf '%s start argc=%s\n' "$(/bin/date '+%Y-%m-%d %H:%M:%S')" "$#"
  text="$(cat 2>/dev/null || true)"
  append_text() {
    if [ -z "$1" ]; then return; fi
    if [ -z "$text" ]; then text="$1"; else text="${text}
$1"; fi
  }
  for arg in "$@"; do
    if [ -f "$arg" ]; then
      file_text="$(cat "$arg" 2>/dev/null || true)"
      if [ -n "$file_text" ]; then append_text "$file_text"; else append_text "$arg"; fi
    else
      append_text "$arg"
    fi
  done
  text="$(printf '%s' "$text" | /usr/bin/python3 -c 'import sys; print(sys.stdin.read().strip())')"
  if [ -z "$text" ]; then
    clip_backup="$(/usr/bin/mktemp -t vocabreminder-clip.XXXXXX)"
    /usr/bin/pbpaste > "$clip_backup" 2>/dev/null || :
    /usr/bin/osascript -e 'tell application "System Events" to keystroke "c" using command down' 2>/dev/null || :
    /bin/sleep 0.2
    text="$(/usr/bin/pbpaste 2>/dev/null | /usr/bin/python3 -c 'import sys; print(sys.stdin.read().strip())')"
    /usr/bin/pbcopy < "$clip_backup" 2>/dev/null || :
    /bin/rm -f "$clip_backup"
  fi
  printf '%s text_length=%s\n' "$(/bin/date '+%Y-%m-%d %H:%M:%S')" "$(printf '%s' "$text" | /usr/bin/wc -c | /usr/bin/tr -d ' ')"
  if [ -n "$text" ]; then
    encoded="$(printf '%s' "$text" | /usr/bin/python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.stdin.read().strip()))')"
    /usr/bin/open "vocabreminder://capture?text=${encoded}&capture_source=mac_service" || true
  fi
} >> "$LOG" 2>&1
exit 0
"""#
}
