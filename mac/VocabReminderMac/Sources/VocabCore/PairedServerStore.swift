import Foundation
import Security

/// The server this Mac paired with and the token it was given for it.
package struct PairedServer: Equatable {
    package init(serverBaseURL: String, token: String, deviceID: String) {
        self.serverBaseURL = serverBaseURL
        self.token = token
        self.deviceID = deviceID
    }

    package var serverBaseURL: String
    package var token: String
    package var deviceID: String
}

/// Keeps the paired server's token in the login Keychain. Runs with their own
/// data directory (VOCAB_REMINDER_DATA_DIR: end-to-end runs, vocab-check) use a
/// separate Keychain account so they never touch the real app's pairing.
package enum PairedServerStore {
    private static let service = "com.vocabreminder.paired-server"

    private static var account: String {
        if let dataDir = ProcessInfo.processInfo.environment["VOCAB_REMINDER_DATA_DIR"], dataDir.isEmpty == false {
            return "data-dir:\(dataDir)"
        }
        return "default"
    }

    package static func load() -> PairedServer? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: String],
              let server = json["server"], let token = json["token"], server.isEmpty == false, token.isEmpty == false
        else { return nil }
        return PairedServer(serverBaseURL: server, token: token, deviceID: json["device_id"] ?? "")
    }

    @discardableResult
    package static func save(_ paired: PairedServer) -> Bool {
        let data = (try? JSONSerialization.data(withJSONObject: [
            "server": paired.serverBaseURL, "token": paired.token, "device_id": paired.deviceID
        ])) ?? Data()
        SecItemDelete(baseQuery as CFDictionary)
        var attributes = baseQuery
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        attributes[kSecAttrLabel as String] = "Vocabulary Reminder sync server"
        return SecItemAdd(attributes as CFDictionary, nil) == errSecSuccess
    }

    package static func clear() {
        SecItemDelete(baseQuery as CFDictionary)
    }

    private static var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
    }
}
