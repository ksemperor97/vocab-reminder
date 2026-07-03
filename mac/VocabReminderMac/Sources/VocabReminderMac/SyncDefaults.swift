import Foundation

enum SyncDefaults {
    static var serverURL: String {
        configuredValue(infoKey: "VocabSyncDefaultURL", environmentKey: "VOCAB_SYNC_DEFAULT_URL")
    }

    static var bearerToken: String {
        configuredValue(infoKey: "VocabSyncDefaultToken", environmentKey: "VOCAB_SYNC_DEFAULT_TOKEN")
    }

    static func effectiveServerURL(stored: String) -> String {
#if DEBUG
        let trimmed = stored.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? serverURL : trimmed
#else
        return serverURL
#endif
    }

    static func effectiveBearerToken(stored: String) -> String {
#if DEBUG
        let trimmed = stored.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? bearerToken : trimmed
#else
        return bearerToken
#endif
    }

    static func effectiveCredentials() -> (serverURL: String, bearerToken: String)? {
        let defaults = UserDefaults.standard
        let serverURL = effectiveServerURL(stored: defaults.string(forKey: "syncServerURL") ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let bearerToken = effectiveBearerToken(stored: defaults.string(forKey: "syncBearerToken") ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard serverURL.isEmpty == false, bearerToken.isEmpty == false else { return nil }
        return (serverURL, bearerToken)
    }

    private static func configuredValue(infoKey: String, environmentKey: String) -> String {
        if let bundleValue = Bundle.main.object(forInfoDictionaryKey: infoKey) as? String,
           bundleValue.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false {
            return bundleValue
        }
        return ProcessInfo.processInfo.environment[environmentKey] ?? ""
    }
}
