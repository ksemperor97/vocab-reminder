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
    private let config: CloudSyncConfig
    private let session: URLSession

    init(config: CloudSyncConfig, session: URLSession = .shared) {
        self.config = config
        self.session = session
    }

    func pushAndPull(localBatch: [String: Any]) async throws -> Data {
        _ = try await post("sync/batch", body: localBatch)
        let snapshot = try await get("sync/snapshot")
        return try JSONSerialization.data(withJSONObject: snapshot, options: [.prettyPrinted, .sortedKeys])
    }

    func push(localBatch: [String: Any]) async throws -> [String: Any] {
        try await post("sync/batch", body: localBatch)
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
}
