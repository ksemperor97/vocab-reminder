import CryptoKit
import Foundation

struct SyncUploadState {
    private static let collections = ["cards", "contexts", "definition_candidates", "review_events", "recommendation_feedback"]

    static func fingerprints(_ payload: [String: Any]) throws -> [String: String] {
        var result: [String: String] = [:]
        for key in collections {
            for row in payload[key] as? [[String: Any]] ?? [] {
                guard let id = row["id"] as? String else { continue }
                result["\(key):\(id)"] = try digest(row)
            }
        }
        if let settings = payload["settings"] as? [String: Any] { result["settings:main"] = try digest(settings) }
        return result
    }

    static func pending(_ payload: [String: Any], acknowledged: [String: String]) throws -> [String: Any] {
        let current = try fingerprints(payload)
        var result = payload
        for key in collections {
            result[key] = (payload[key] as? [[String: Any]] ?? []).filter { row in
                guard let id = row["id"] as? String else { return true }
                return acknowledged["\(key):\(id)"] != current["\(key):\(id)"]
            }
        }
        if acknowledged["settings:main"] == current["settings:main"] { result["settings"] = NSNull() }
        return result
    }

    private static func digest(_ object: [String: Any]) throws -> String {
        let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        return SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}
