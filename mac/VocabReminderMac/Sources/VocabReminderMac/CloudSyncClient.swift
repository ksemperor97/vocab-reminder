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
        case .http(let status, let requestID):
            return "Sync HTTP \(status) · 요청 \(Self.shortRequestID(requestID))"
        case .server(let message):
            let displayMessage = message.components(separatedBy: "[request_id:").first?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? message
            guard let requestID else { return displayMessage }
            return "\(displayMessage) · 요청 \(Self.shortRequestID(requestID))"
        }
    }

    var requestID: String? {
        switch self {
        case .http(_, let requestID):
            return requestID.isEmpty ? nil : requestID
        case .server(let message):
            guard let range = message.range(of: "[request_id:") else { return nil }
            let suffix = message[range.upperBound...]
            guard let end = suffix.firstIndex(of: "]") else { return nil }
            return String(suffix[..<end])
        default:
            return nil
        }
    }

    private static func shortRequestID(_ value: String) -> String {
        value.isEmpty ? "확인 불가" : String(value.prefix(12))
    }
}

enum CloudDictionaryOutcome: String, Codable, Equatable, Sendable {
    case resolved
    case pending
    case notFound = "not_found"
    case providerFailure = "provider_failure"
}

func normalizeCloudDictionaryLemma(_ value: String) -> String {
    value
        .precomposedStringWithCompatibilityMapping
        .replacingOccurrences(of: "\u{2018}", with: "'")
        .replacingOccurrences(of: "\u{2019}", with: "'")
        .replacingOccurrences(of: "\u{2010}", with: "-")
        .replacingOccurrences(of: "\u{2011}", with: "-")
        .replacingOccurrences(of: "\u{2012}", with: "-")
        .replacingOccurrences(of: "\u{2013}", with: "-")
        .replacingOccurrences(of: "\u{2014}", with: "-")
        .replacingOccurrences(of: "\u{2015}", with: "-")
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .split(whereSeparator: \.isWhitespace)
        .joined(separator: " ")
        .lowercased(with: Locale(identifier: "en_US_POSIX"))
}

enum CloudRequestOperation: String, Codable, Equatable, Sendable {
    case health
    case syncPush = "sync_push"
    case syncPull = "sync_pull"
    case dictionaryBatch = "dictionary_batch"
    case dictionaryLookup = "dictionary_lookup"
    case other
}

struct CloudRequestDiagnostic: Codable, Equatable, Sendable {
    var requestID: String
    var operation: CloudRequestOperation
    var succeeded: Bool
    var statusCode: Int?
    var category: String
    var occurredAt: Date

    var shortRequestID: String { String(requestID.prefix(12)) }
}

enum CloudRequestDiagnosticsStore {
    static let defaultsKey = "lastCloudRequestDiagnosticV1"

    static func load(defaults: UserDefaults = .standard) -> CloudRequestDiagnostic? {
        guard let data = defaults.data(forKey: defaultsKey) else { return nil }
        return try? JSONDecoder.vocab.decode(CloudRequestDiagnostic.self, from: data)
    }

    static func save(_ diagnostic: CloudRequestDiagnostic, defaults: UserDefaults = .standard) {
        guard let data = try? JSONEncoder.vocab.encode(diagnostic) else { return }
        defaults.set(data, forKey: defaultsKey)
    }
}

struct CloudDictionarySense: Equatable, Sendable {
    var meaningKo: String
    var definitionEn: String = ""
    var partOfSpeech: String = ""
    var exampleSentence: String = ""
}

struct CloudDictionaryLookupResult: Equatable, Sendable {
    var lemma: String
    var meaningKo: String
    var definitionEn: String
    var partOfSpeech: String
    var exampleSentence: String
    var sourceType: String
    var confidence: Double
    var attemptedAt: String
    var errorCode: String?
    var senses: [CloudDictionarySense]

    var outcome: CloudDictionaryOutcome {
        guard meaningKo.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return .resolved
        }
        switch errorCode {
        case CloudDictionaryOutcome.notFound.rawValue:
            return .notFound
        case CloudDictionaryOutcome.providerFailure.rawValue:
            return .providerFailure
        default:
            // A response can be "resolved" with only an English definition.
            // Keep waiting for a Korean meaning instead of scheduling an
            // unusable review notification.
            return .pending
        }
    }

    init(
        lemma: String,
        meaningKo: String,
        definitionEn: String = "",
        partOfSpeech: String = "",
        exampleSentence: String = "",
        sourceType: String = "server_dictionary",
        confidence: Double = 0,
        attemptedAt: String = "",
        errorCode: String? = nil,
        senses: [CloudDictionarySense] = []
    ) {
        self.lemma = lemma
        self.meaningKo = meaningKo
        self.definitionEn = definitionEn
        self.partOfSpeech = partOfSpeech
        self.exampleSentence = exampleSentence
        self.sourceType = sourceType
        self.confidence = confidence
        self.attemptedAt = attemptedAt
        self.errorCode = errorCode
        self.senses = Array(senses.prefix(20))
    }

    init(payload: [String: Any]) throws {
        let lemma = normalizeCloudDictionaryLemma(payload["lemma"] as? String ?? "")
        guard lemma.isEmpty == false else { throw CloudSyncError.invalidResponse }

        self.init(
            lemma: lemma,
            meaningKo: payload["meaningKo"] as? String ?? payload["meaning_ko"] as? String ?? "",
            definitionEn: payload["definitionEn"] as? String ?? payload["definition_en"] as? String ?? "",
            partOfSpeech: payload["partOfSpeech"] as? String ?? payload["part_of_speech"] as? String ?? "",
            exampleSentence: payload["exampleSentence"] as? String ?? payload["example_sentence"] as? String ?? "",
            sourceType: payload["sourceType"] as? String ?? payload["source_type"] as? String ?? "server_dictionary",
            confidence: (payload["confidence"] as? NSNumber)?.doubleValue ?? 0,
            attemptedAt: payload["attemptedAt"] as? String ?? payload["attempted_at"] as? String ?? "",
            errorCode: payload["errorCode"] as? String ?? payload["error_code"] as? String,
            senses: (payload["senses"] as? [[String: Any]] ?? []).prefix(20).map {
                CloudDictionarySense(
                    meaningKo: $0["meaningKo"] as? String ?? $0["meaning_ko"] as? String ?? "",
                    definitionEn: $0["definitionEn"] as? String ?? $0["definition_en"] as? String ?? "",
                    partOfSpeech: $0["partOfSpeech"] as? String ?? $0["part_of_speech"] as? String ?? "",
                    exampleSentence: $0["exampleSentence"] as? String ?? $0["example_sentence"] as? String ?? ""
                )
            }
        )
    }
}

final class CloudSyncClient {
    static let maxItemsPerBatch = 400
    static let maxDictionaryItemsPerBatch = 25
    private static let collectionKeys = [
        "cards",
        "contexts",
        "definition_candidates",
        "review_events",
        "recommendation_feedback"
    ]

    private var supportsChanges = false
    private let config: CloudSyncConfig
    private let session: URLSession
    private let diagnosticsDefaults: UserDefaults

    init(
        config: CloudSyncConfig,
        session: URLSession = .shared,
        diagnosticsDefaults: UserDefaults = .standard
    ) {
        self.config = config
        self.session = session
        self.diagnosticsDefaults = diagnosticsDefaults
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
            supportsChanges = supportsChanges || (result["sync_protocol"] as? Int == 2)
            aggregate = Self.mergingPushResult(aggregate, result)
        }
        return aggregate
    }

    func pullSnapshot(cursor: String? = nil) async throws -> Data {
        guard supportsChanges else {
            let snapshot = try await get("sync/snapshot")
            return try JSONSerialization.data(withJSONObject: snapshot, options: [.sortedKeys])
        }
        var next = cursor ?? ""
        var collected = SyncChangeAccumulator()
        for _ in 0..<10_000 {
            let escaped = next.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
            let page = try await get("sync/changes?limit=400&cursor=\(escaped)")
            try collected.append(page, previousCursor: next)
            next = collected.cursor
            if page["has_more"] as? Bool == false { return try collected.data() }
        }
        throw CloudSyncError.server("동기화 변경 페이지가 너무 많습니다.")
    }

    func health() async throws -> [String: Any] {
        try await get("health")
    }

    func lookupDictionary(lemmas: [String]) async throws -> [CloudDictionaryLookupResult] {
        let normalized = lemmas.map(normalizeCloudDictionaryLemma)
        guard normalized.isEmpty == false,
              normalized.count <= Self.maxDictionaryItemsPerBatch,
              normalized.allSatisfy({ $0.isEmpty == false })
        else {
            throw CloudSyncError.invalidConfig(
                "Dictionary lookup requires 1...\(Self.maxDictionaryItemsPerBatch) non-empty lemmas."
            )
        }

        let payload = try await post("dict/batch", body: ["lemmas": normalized])
        guard let rawResults = payload["results"] as? [[String: Any]],
              rawResults.count == normalized.count
        else {
            throw CloudSyncError.invalidResponse
        }
        return try rawResults.map(CloudDictionaryLookupResult.init(payload:))
    }

    private func post(_ path: String, body: [String: Any]) async throws -> [String: Any] {
        var request = URLRequest(url: try config.endpoint(path))
        request.httpMethod = "POST"
        request.timeoutInterval = 5
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(config.bearerToken)", forHTTPHeaderField: "Authorization")
        request.setValue(UUID().uuidString.lowercased(), forHTTPHeaderField: "X-Request-Id")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return try await send(request)
    }

    private func get(_ path: String) async throws -> [String: Any] {
        var request = URLRequest(url: try config.endpoint(path))
        request.httpMethod = "GET"
        request.timeoutInterval = 5
        request.setValue("Bearer \(config.bearerToken)", forHTTPHeaderField: "Authorization")
        request.setValue(UUID().uuidString.lowercased(), forHTTPHeaderField: "X-Request-Id")
        return try await send(request)
    }

    private func send(_ request: URLRequest) async throws -> [String: Any] {
        let outboundRequestID = sanitizedRequestID(
            request.value(forHTTPHeaderField: "X-Request-Id")
        ) ?? UUID().uuidString.lowercased()
        let operation = operation(for: request.url)
        let responseData: Data
        let response: URLResponse
        do {
            (responseData, response) = try await session.data(for: request)
        } catch {
            recordRequest(
                requestID: outboundRequestID,
                operation: operation,
                succeeded: false,
                statusCode: nil,
                category: "transport"
            )
            throw CloudSyncError.server(
                "네트워크 요청 실패 [request_id:\(outboundRequestID)]"
            )
        }
        guard let http = response as? HTTPURLResponse else {
            recordRequest(
                requestID: outboundRequestID,
                operation: operation,
                succeeded: false,
                statusCode: nil,
                category: "invalid_response"
            )
            throw CloudSyncError.server(
                "응답 형식 오류 [request_id:\(outboundRequestID)]"
            )
        }
        let parsedPayload = try? JSONSerialization.jsonObject(with: responseData) as? [String: Any]
        let requestID = responseRequestID(http: http, payload: parsedPayload) ?? outboundRequestID
        guard (200...299).contains(http.statusCode) else {
            recordRequest(
                requestID: requestID,
                operation: operation,
                succeeded: false,
                statusCode: http.statusCode,
                category: "http_\(http.statusCode)"
            )
            // Do not preserve a raw response body here. Dictionary errors can
            // otherwise leak the requested lemma, and sync validation bodies
            // can contain captured text.
            throw CloudSyncError.http(http.statusCode, requestID)
        }
        guard let payload = parsedPayload else {
            recordRequest(
                requestID: requestID,
                operation: operation,
                succeeded: false,
                statusCode: http.statusCode,
                category: "invalid_json"
            )
            throw CloudSyncError.server(
                "응답 JSON 오류 [request_id:\(requestID)]"
            )
        }
        if payload["error"] as? [String: Any] != nil {
            recordRequest(
                requestID: requestID,
                operation: operation,
                succeeded: false,
                statusCode: http.statusCode,
                category: "server_error"
            )
            throw CloudSyncError.server(
                "서버 요청 거절 [request_id:\(requestID)]"
            )
        }
        recordRequest(
            requestID: requestID,
            operation: operation,
            succeeded: true,
            statusCode: http.statusCode,
            category: "success"
        )
        return payload
    }

    private func operation(for url: URL?) -> CloudRequestOperation {
        guard let path = url?.path else { return .other }
        switch path {
        case "/health":
            return .health
        case "/sync/batch":
            return .syncPush
        case "/sync/snapshot", "/sync/changes":
            return .syncPull
        case "/dict/batch":
            return .dictionaryBatch
        default:
            return path.hasPrefix("/dict/") ? .dictionaryLookup : .other
        }
    }

    private func responseRequestID(http: HTTPURLResponse, payload: [String: Any]?) -> String? {
        let header = http.value(forHTTPHeaderField: "X-Request-Id") ??
            http.value(forHTTPHeaderField: "X-Correlation-Id")
        if let value = sanitizedRequestID(header) { return value }
        if let value = sanitizedRequestID(payload?["request_id"] as? String) { return value }
        if let error = payload?["error"] as? [String: Any],
           let value = sanitizedRequestID(error["request_id"] as? String) {
            return value
        }
        return nil
    }

    private func sanitizedRequestID(_ value: String?) -> String? {
        guard let value else { return nil }
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.isEmpty == false,
              trimmed.utf8.count <= 128,
              trimmed.range(of: #"^[A-Za-z0-9._:-]+$"#, options: .regularExpression) != nil
        else {
            return nil
        }
        return trimmed
    }

    private func recordRequest(
        requestID: String,
        operation: CloudRequestOperation,
        succeeded: Bool,
        statusCode: Int?,
        category: String
    ) {
        CloudRequestDiagnosticsStore.save(
            CloudRequestDiagnostic(
                requestID: requestID,
                operation: operation,
                succeeded: succeeded,
                statusCode: statusCode,
                category: category,
                occurredAt: Date()
            ),
            defaults: diagnosticsDefaults
        )
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
