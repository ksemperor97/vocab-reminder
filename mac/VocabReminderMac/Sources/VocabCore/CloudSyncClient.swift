import Foundation

package struct CloudSyncConfig {
    package init(
        serverBaseURL: String,
        bearerToken: String
    ) {
        self.serverBaseURL = serverBaseURL
        self.bearerToken = bearerToken
    }

    package var serverBaseURL: String
    package var bearerToken: String

    package func endpoint(_ path: String) throws -> URL {
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

package enum CloudSyncError: Error, LocalizedError {
    case invalidConfig(String)
    case invalidResponse
    case http(Int, String)
    case server(String)

    package var errorDescription: String? {
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

    package var requestID: String? {
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

package enum CloudDictionaryOutcome: String, Codable, Equatable, Sendable {
    case resolved
    case pending
    case notFound = "not_found"
    case providerFailure = "provider_failure"
}

package func normalizeCloudDictionaryLemma(_ value: String) -> String {
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

package enum CloudRequestOperation: String, Codable, Equatable, Sendable {
    case health
    case syncPush = "sync_push"
    case syncPull = "sync_pull"
    case dictionaryBatch = "dictionary_batch"
    case dictionaryLookup = "dictionary_lookup"
    case other
}

package struct CloudRequestDiagnostic: Codable, Equatable, Sendable {
    package init(
        requestID: String,
        operation: CloudRequestOperation,
        succeeded: Bool,
        statusCode: Int? = nil,
        category: String,
        occurredAt: Date
    ) {
        self.requestID = requestID
        self.operation = operation
        self.succeeded = succeeded
        self.statusCode = statusCode
        self.category = category
        self.occurredAt = occurredAt
    }

    package var requestID: String
    package var operation: CloudRequestOperation
    package var succeeded: Bool
    package var statusCode: Int?
    package var category: String
    package var occurredAt: Date

    package var shortRequestID: String { String(requestID.prefix(12)) }
}

package enum CloudRequestDiagnosticsStore {
    package static let defaultsKey = "lastCloudRequestDiagnosticV1"

    package static func load(defaults: UserDefaults = .standard) -> CloudRequestDiagnostic? {
        guard let data = defaults.data(forKey: defaultsKey) else { return nil }
        return try? JSONDecoder.vocab.decode(CloudRequestDiagnostic.self, from: data)
    }

    package static func save(_ diagnostic: CloudRequestDiagnostic, defaults: UserDefaults = .standard) {
        guard let data = try? JSONEncoder.vocab.encode(diagnostic) else { return }
        defaults.set(data, forKey: defaultsKey)
    }
}

package struct CloudDictionarySense: Equatable, Sendable {
    package init(
        meaningKo: String,
        definitionEn: String = "",
        partOfSpeech: String = "",
        exampleSentence: String = ""
    ) {
        self.meaningKo = meaningKo
        self.definitionEn = definitionEn
        self.partOfSpeech = partOfSpeech
        self.exampleSentence = exampleSentence
    }

    package var meaningKo: String
    package var definitionEn: String = ""
    package var partOfSpeech: String = ""
    package var exampleSentence: String = ""
}

/// The dictionary result the server set aside when it picked a meaning from the sentence.
package struct CloudDictionaryFallback: Equatable, Sendable {
    package init(
        meaningKo: String,
        definitionEn: String = "",
        partOfSpeech: String = "",
        exampleSentence: String = "",
        sourceType: String = "server_dictionary",
        confidence: Double = 0
    ) {
        self.meaningKo = meaningKo
        self.definitionEn = definitionEn
        self.partOfSpeech = partOfSpeech
        self.exampleSentence = exampleSentence
        self.sourceType = sourceType
        self.confidence = confidence
    }

    package var meaningKo: String
    package var definitionEn: String
    package var partOfSpeech: String
    package var exampleSentence: String
    package var sourceType: String
    package var confidence: Double
}

/// One dictionary lookup: the lemma and, when known, the sentence it was captured in.
package struct CloudDictionaryRequest: Equatable, Sendable {
    package init(lemma: String, sentence: String = "") {
        self.lemma = lemma
        self.sentence = sentence
    }

    package var lemma: String
    package var sentence: String
}

package struct CloudDictionaryLookupResult: Equatable, Sendable {
    package var lemma: String
    package var meaningKo: String
    package var definitionEn: String
    package var partOfSpeech: String
    package var exampleSentence: String
    package var sourceType: String
    package var confidence: Double
    package var attemptedAt: String
    package var errorCode: String?
    package var senses: [CloudDictionarySense]
    /// How the server used the sentence: resolved, unresolved, failed, deferred,
    /// disabled or no_context; nil from servers that predate context meanings.
    package var contextStatus: String?
    package var contextSentence: String
    package var dictionary: CloudDictionaryFallback?

    package var outcome: CloudDictionaryOutcome {
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

    package init(
        lemma: String,
        meaningKo: String,
        definitionEn: String = "",
        partOfSpeech: String = "",
        exampleSentence: String = "",
        sourceType: String = "server_dictionary",
        confidence: Double = 0,
        attemptedAt: String = "",
        errorCode: String? = nil,
        senses: [CloudDictionarySense] = [],
        contextStatus: String? = nil,
        contextSentence: String = "",
        dictionary: CloudDictionaryFallback? = nil
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
        self.contextStatus = contextStatus
        self.contextSentence = contextSentence
        self.dictionary = dictionary
    }

    package init(payload: [String: Any]) throws {
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
            },
            contextStatus: (payload["contextStatus"] as? String ?? payload["context_status"] as? String).flatMap { $0.isEmpty ? nil : $0 },
            contextSentence: payload["contextSentence"] as? String ?? payload["context_sentence"] as? String ?? "",
            dictionary: (payload["dictionary"] as? [String: Any]).flatMap { fallback in
                let parsed = CloudDictionaryFallback(
                    meaningKo: fallback["meaningKo"] as? String ?? fallback["meaning_ko"] as? String ?? "",
                    definitionEn: fallback["definitionEn"] as? String ?? fallback["definition_en"] as? String ?? "",
                    partOfSpeech: fallback["partOfSpeech"] as? String ?? fallback["part_of_speech"] as? String ?? "",
                    exampleSentence: fallback["exampleSentence"] as? String ?? fallback["example_sentence"] as? String ?? "",
                    sourceType: fallback["sourceType"] as? String ?? fallback["source_type"] as? String ?? "server_dictionary",
                    confidence: min(max((fallback["confidence"] as? NSNumber)?.doubleValue ?? 0, 0), 1)
                )
                return parsed.meaningKo.isEmpty && parsed.definitionEn.isEmpty ? nil : parsed
            }
        )
    }
}

package final class CloudSyncClient {
    package static let maxItemsPerBatch = 400
    package static let maxDictionaryItemsPerBatch = 25
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

    package init(
        config: CloudSyncConfig,
        session: URLSession = .shared,
        diagnosticsDefaults: UserDefaults = .standard
    ) {
        self.config = config
        self.session = session
        self.diagnosticsDefaults = diagnosticsDefaults
    }

    package func pushAndPull(localBatch: [String: Any]) async throws -> Data {
        _ = try await push(localBatch: localBatch)
        let snapshot = try await get("sync/snapshot")
        return try JSONSerialization.data(withJSONObject: snapshot, options: [.prettyPrinted, .sortedKeys])
    }

    package func push(localBatch: [String: Any]) async throws -> [String: Any] {
        var aggregate: [String: Any] = [
            "applied": [String: Int](),
            "skipped": ["reasons": [Any]()] as [String: Any]
        ]
        for batch in Self.splitIntoBatches(localBatch) {
            let result = try await post("sync/batch", body: batch)
            supportsChanges = supportsChanges || (result["sync_protocol"] as? Int ?? 0) >= 2
            aggregate = Self.mergingPushResult(aggregate, result)
        }
        return aggregate
    }

    package func pullSnapshot(cursor: String? = nil) async throws -> Data {
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

    package func health() async throws -> [String: Any] {
        try await get("health")
    }

    /// A 6-digit code another device can use to pair with this server (10 minutes, once).
    package func createPairingCode() async throws -> String {
        let payload = try await post("pair/code", body: [:])
        guard let code = payload["code"] as? String else { throw CloudSyncError.invalidResponse }
        return code
    }

    /// Exchanges a pairing code for this Mac's own token. The request carries
    /// no credentials; the code is the proof.
    package static func claimPairing(
        serverBaseURL: String,
        code: String,
        deviceName: String,
        session: URLSession = .shared
    ) async throws -> PairedServer {
        let base = serverBaseURL.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard base.hasPrefix("https://") || base.hasPrefix("http://"), let url = URL(string: "\(base)/pair/claim") else {
            throw CloudSyncError.invalidConfig("서버 주소는 https://로 시작해야 해요.")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 15
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: [
            "code": code.filter(\.isNumber), "device_name": deviceName, "platform": "mac"
        ])
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw CloudSyncError.server("서버에 닿지 않아요. 주소와 인터넷 연결을 확인하세요.")
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        switch status {
        case 200...299: break
        case 400: throw CloudSyncError.server("코드가 틀렸거나 만료됐어요. 새 코드를 만들어 다시 입력하세요.")
        case 429: throw CloudSyncError.server("틀린 코드가 여러 번 입력돼 잠시 막혔어요. 몇 분 뒤 새 코드로 다시 하세요.")
        default: throw CloudSyncError.server("연결하지 못했어요 (HTTP \(status)).")
        }
        guard let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let token = payload["token"] as? String, token.isEmpty == false
        else { throw CloudSyncError.invalidResponse }
        return PairedServer(serverBaseURL: base, token: token, deviceID: payload["device_id"] as? String ?? "")
    }

    package func lookupDictionary(lemmas: [String]) async throws -> [CloudDictionaryLookupResult] {
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

    /// Sends each lemma with the sentence it was captured in, so the server can
    /// pick the meaning that sentence uses (an LLM call, hence the longer wait).
    package func lookupDictionary(items: [CloudDictionaryRequest]) async throws -> [CloudDictionaryLookupResult] {
        let normalized = items.map {
            CloudDictionaryRequest(
                lemma: normalizeCloudDictionaryLemma($0.lemma),
                sentence: String($0.sentence.trimmingCharacters(in: .whitespacesAndNewlines).prefix(1_000))
            )
        }
        guard normalized.isEmpty == false,
              normalized.count <= Self.maxDictionaryItemsPerBatch,
              normalized.allSatisfy({ $0.lemma.isEmpty == false })
        else {
            throw CloudSyncError.invalidConfig(
                "Dictionary lookup requires 1...\(Self.maxDictionaryItemsPerBatch) non-empty lemmas."
            )
        }
        let body: [[String: Any]] = normalized.map { item in
            item.sentence.isEmpty ? ["lemma": item.lemma] : ["lemma": item.lemma, "sentence": item.sentence]
        }
        let hasSentence = normalized.contains { $0.sentence.isEmpty == false }
        let payload: [String: Any]
        do {
            payload = try await post(
                "dict/batch",
                body: ["items": body],
                timeout: hasSentence ? Self.contextLookupTimeout : 5
            )
        } catch CloudSyncError.http(400, _) {
            // A server older than context meanings only reads {lemmas}; answers then carry no contextStatus.
            return try await lookupDictionary(lemmas: normalized.map(\.lemma))
        }
        guard let rawResults = payload["results"] as? [[String: Any]],
              rawResults.count == normalized.count
        else {
            throw CloudSyncError.invalidResponse
        }
        let results = try rawResults.map(CloudDictionaryLookupResult.init(payload:))
        guard zip(results, normalized).allSatisfy({ $0.lemma == $1.lemma }) else {
            throw CloudSyncError.invalidResponse
        }
        return results
    }

    // The server spends at most ~20 s starting LLM calls plus 15 s per call.
    package static let contextLookupTimeout: TimeInterval = 45

    private func post(_ path: String, body: [String: Any], timeout: TimeInterval = 5) async throws -> [String: Any] {
        var request = URLRequest(url: try config.endpoint(path))
        request.httpMethod = "POST"
        request.timeoutInterval = timeout
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

    package static func splitIntoBatches(_ localBatch: [String: Any]) -> [[String: Any]] {
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
