import Foundation

struct CloudSyncConfig {
    var serverBaseURL: String
    var bearerToken: String

    func endpoint(_ path: String) throws -> URL {
        let trimmed = serverBaseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.isEmpty == false else { throw CloudSyncError.invalidConfig("Server URL is required.") }
        guard bearerToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false else {
            throw CloudSyncError.invalidConfig("Sync token is required.")
        }
        guard let url = URL(string: trimmed.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/" + path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))) else {
            throw CloudSyncError.invalidConfig("Server URL is invalid.")
        }
        return url
    }
}

enum CloudSyncError: Error, LocalizedError {
    case invalidConfig(String)
    case invalidResponse
    case http(Int, String)
    case server(String)

    var errorDescription: String? {
        switch self {
        case .invalidConfig(let message):
            return message
        case .invalidResponse:
            return "Sync response was not valid JSON."
        case .http(let status, let body):
            return "Sync HTTP \(status): \(body)"
        case .server(let message):
            return message
        }
    }
}

final class CloudSyncClient {
    static let maxItemsPerBatch = 400
    private static let collectionKeys = [
        "cards",
        "contexts",
        "definition_candidates",
        "review_events",
        "recommendation_feedback"
    ]

    private let config: CloudSyncConfig
    private let session: URLSession

    init(config: CloudSyncConfig, session: URLSession = .shared) {
        self.config = config
        self.session = session
    }

    func pushAndPull(localBatch: [String: Any]) async throws -> Data {
        _ = try await push(localBatch: localBatch)
        let snapshot = try await get("sync/snapshot")
        return try JSONSerialization.data(withJSONObject: snapshot, options: [.prettyPrinted, .sortedKeys])
    }

    func push(localBatch: [String: Any]) async throws -> [String: Any] {
        var aggregate: [String: Any] = [
            "applied": [String: Int](),
            "skipped": ["reasons": [Any]()] as [String: Any]
        ]
        for batch in Self.splitIntoBatches(localBatch) {
            let result = try await post("sync/batch", body: batch)
            aggregate = Self.mergingPushResult(aggregate, result)
        }
        return aggregate
    }

    func pullSnapshot() async throws -> Data {
        let snapshot = try await get("sync/snapshot")
        return try JSONSerialization.data(withJSONObject: snapshot, options: [.prettyPrinted, .sortedKeys])
    }

    func health() async throws -> [String: Any] {
        try await get("health")
    }

    private func post(_ path: String, body: [String: Any]) async throws -> [String: Any] {
        var request = URLRequest(url: try config.endpoint(path))
        request.httpMethod = "POST"
        request.timeoutInterval = 5
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(config.bearerToken)", forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return try await send(request)
    }

    private func get(_ path: String) async throws -> [String: Any] {
        var request = URLRequest(url: try config.endpoint(path))
        request.httpMethod = "GET"
        request.timeoutInterval = 5
        request.setValue("Bearer \(config.bearerToken)", forHTTPHeaderField: "Authorization")
        return try await send(request)
    }

    private func send(_ request: URLRequest) async throws -> [String: Any] {
        let (responseData, response) = try await session.data(for: request)
        let responseText = String(data: responseData, encoding: .utf8) ?? ""
        guard let http = response as? HTTPURLResponse else { throw CloudSyncError.invalidResponse }
        guard (200...299).contains(http.statusCode) else {
            throw CloudSyncError.http(http.statusCode, responseText)
        }
        guard let payload = try JSONSerialization.jsonObject(with: responseData) as? [String: Any] else {
            throw CloudSyncError.invalidResponse
        }
        if let error = payload["error"] as? [String: Any] {
            throw CloudSyncError.server(error["message"] as? String ?? "Sync failed.")
        }
        return payload
    }

    static func splitIntoBatches(_ localBatch: [String: Any]) -> [[String: Any]] {
        let arrays = Dictionary(uniqueKeysWithValues: collectionKeys.map { key in
            (key, localBatch[key] as? [Any] ?? [])
        })
        let batchCount = max(
            1,
            arrays.values.map { ($0.count + maxItemsPerBatch - 1) / maxItemsPerBatch }.max() ?? 1
        )
        let settings = localBatch["settings"] ?? NSNull()

        return (0..<batchCount).map { batchIndex in
            var batch: [String: Any] = [:]
            for key in collectionKeys {
                let source = arrays[key] ?? []
                let start = batchIndex * maxItemsPerBatch
                let end = min(source.count, start + maxItemsPerBatch)
                batch[key] = start < end ? Array(source[start..<end]) : []
            }
            // Settings are applied only after all entity chunks have succeeded. A retry
            // after a partial upload is safe because server writes are idempotent.
            batch["settings"] = batchIndex == batchCount - 1 ? settings : NSNull()
            return batch
        }
    }

    private static func mergingPushResult(_ aggregate: [String: Any], _ incoming: [String: Any]) -> [String: Any] {
        var result = aggregate
        result["applied"] = mergeCounts(
            aggregate["applied"] as? [String: Any] ?? [:],
            incoming["applied"] as? [String: Any] ?? [:]
        )

        let aggregateSkipped = aggregate["skipped"] as? [String: Any] ?? [:]
        let incomingSkipped = incoming["skipped"] as? [String: Any] ?? [:]
        var skipped = mergeCounts(aggregateSkipped, incomingSkipped, ignoredKeys: ["reasons"])
        skipped["reasons"] = (aggregateSkipped["reasons"] as? [Any] ?? []) +
            (incomingSkipped["reasons"] as? [Any] ?? [])
        result["skipped"] = skipped
        return result
    }

    private static func mergeCounts(
        _ aggregate: [String: Any],
        _ incoming: [String: Any],
        ignoredKeys: Set<String> = []
    ) -> [String: Any] {
        var result = aggregate
        for (key, value) in incoming where !ignoredKeys.contains(key) {
            let oldCount = (result[key] as? NSNumber)?.intValue ?? result[key] as? Int ?? 0
            let newCount = (value as? NSNumber)?.intValue ?? value as? Int ?? 0
            result[key] = oldCount + newCount
        }
        return result
    }
}
