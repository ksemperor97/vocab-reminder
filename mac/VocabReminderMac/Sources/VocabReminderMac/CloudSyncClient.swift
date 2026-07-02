import Foundation

struct CloudSyncConfig {
    var functionsBaseURL: String
    var bearerToken: String

    func endpoint(_ functionName: String) throws -> URL {
        let trimmed = functionsBaseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.isEmpty == false else { throw CloudSyncError.invalidConfig("Functions URL is required.") }
        guard bearerToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false else {
            throw CloudSyncError.invalidConfig("Firebase Auth ID token is required.")
        }
        guard let url = URL(string: trimmed.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/" + functionName) else {
            throw CloudSyncError.invalidConfig("Functions URL is invalid.")
        }
        return url
    }
}

enum CloudSyncError: Error, LocalizedError {
    case invalidConfig(String)
    case invalidResponse
    case http(Int, String)
    case callable(String)
    case missingResult(String)

    var errorDescription: String? {
        switch self {
        case .invalidConfig(let message):
            return message
        case .invalidResponse:
            return "Cloud sync response was not valid JSON."
        case .http(let status, let body):
            return "Cloud sync HTTP \(status): \(body)"
        case .callable(let message):
            return message
        case .missingResult(let functionName):
            return "Callable \(functionName) response missing result."
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
        _ = try await call("syncBatch", data: localBatch)
        let snapshot = try await call("syncSnapshot", data: [:])
        return try JSONSerialization.data(withJSONObject: snapshot, options: [.prettyPrinted, .sortedKeys])
    }

    private func call(_ functionName: String, data: [String: Any]) async throws -> [String: Any] {
        var request = URLRequest(url: try config.endpoint(functionName))
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(config.bearerToken)", forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["data": data])

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
            throw CloudSyncError.callable(error["message"] as? String ?? "Callable \(functionName) failed.")
        }
        guard let result = payload["result"] as? [String: Any] else {
            throw CloudSyncError.missingResult(functionName)
        }
        return result
    }
}
