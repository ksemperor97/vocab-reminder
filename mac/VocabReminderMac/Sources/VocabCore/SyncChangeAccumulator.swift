import Foundation

package struct SyncChangeAccumulator {
    private static let keys = ["cards", "contexts", "definition_candidates", "review_events", "recommendation_feedback"]
    private var collections: [String: [String: [String: Any]]] = [:]
    private var settings: [String: Any]?
    package private(set) var cursor = ""
    private var reset = false

    package mutating func append(_ page: [String: Any], previousCursor: String) throws {
        guard let next = page["sync_cursor"] as? String, !next.isEmpty,
              let more = page["has_more"] as? Bool,
              !more || next != previousCursor else {
            throw CloudSyncError.server("동기화 변경 응답 형식 오류")
        }
        reset = reset || page["reset"] as? Bool == true
        for key in Self.keys {
            guard let rows = page[key] as? [[String: Any]] else { throw CloudSyncError.server("동기화 목록 형식 오류") }
            for row in rows {
                guard let id = row["id"] as? String, !id.isEmpty else { throw CloudSyncError.server("동기화 항목 ID 오류") }
                collections[key, default: [:]][id] = row
            }
        }
        if let value = page["settings"] as? [String: Any] { settings = value }
        cursor = next
    }

    package func data() throws -> Data {
        var payload: [String: Any] = ["sync_cursor": cursor, "sync_reset": reset, "settings": settings as Any? ?? NSNull()]
        for key in Self.keys { payload[key] = Array(collections[key, default: [:]].values) }
        return try JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys])
    }
}
