import Foundation
import SQLite3

/// Row-per-entity storage for the Mac store, same layout as the Android
/// VocabularyDatabase: each row keeps its sync wire JSON in `body`, plus the
/// columns we filter on, and `outbox` lists rows changed locally that the
/// server has not acknowledged yet.
package final class VocabularyDatabase {
    package static let entityTables = ["cards", "contexts", "definition_candidates", "review_events", "recommendation_feedback"]

    private var handle: OpaquePointer?

    package init(url: URL) throws {
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        guard sqlite3_open_v2(url.path, &handle, flags, nil) == SQLITE_OK else {
            let message = handle.map { String(cString: sqlite3_errmsg($0)) } ?? "unknown error"
            sqlite3_close(handle)
            throw DatabaseError.open(message)
        }
        try execute("PRAGMA journal_mode = WAL")
        try execute("PRAGMA busy_timeout = 5000")
        try migrate()
    }

    deinit {
        sqlite3_close(handle)
    }

    private func migrate() throws {
        try execute("""
            CREATE TABLE IF NOT EXISTS cards (id TEXT PRIMARY KEY, lemma TEXT NOT NULL, deleted_at TEXT, created_at TEXT NOT NULL, body TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS idx_cards_lemma ON cards(lemma);
            CREATE TABLE IF NOT EXISTS contexts (id TEXT PRIMARY KEY, card_id TEXT NOT NULL, created_at TEXT NOT NULL, body TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS definition_candidates (id TEXT PRIMARY KEY, card_id TEXT NOT NULL, created_at TEXT NOT NULL, body TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS review_events (id TEXT PRIMARY KEY, card_id TEXT NOT NULL, response TEXT NOT NULL, client_created_at TEXT NOT NULL, body TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS recommendation_feedback (id TEXT PRIMARY KEY, card_id TEXT NOT NULL, lemma TEXT NOT NULL, action TEXT NOT NULL, created_at TEXT NOT NULL, body TEXT NOT NULL);
            CREATE INDEX IF NOT EXISTS idx_contexts_card_id ON contexts(card_id);
            CREATE INDEX IF NOT EXISTS idx_definition_candidates_card_id ON definition_candidates(card_id);
            CREATE INDEX IF NOT EXISTS idx_review_events_card_id ON review_events(card_id);
            CREATE INDEX IF NOT EXISTS idx_recommendation_feedback_card_id ON recommendation_feedback(card_id);
            CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS outbox (collection TEXT NOT NULL, id TEXT NOT NULL, PRIMARY KEY (collection, id));
            """)
    }

    package func execute(_ sql: String) throws {
        var error: UnsafeMutablePointer<CChar>?
        guard sqlite3_exec(handle, sql, nil, nil, &error) == SQLITE_OK else {
            let message = error.map { String(cString: $0) } ?? "unknown error"
            sqlite3_free(error)
            throw DatabaseError.statement(message)
        }
    }

    /// Runs `body` in one IMMEDIATE transaction; any thrown error rolls it back.
    package func transaction<T>(_ body: () throws -> T) throws -> T {
        try execute("BEGIN IMMEDIATE")
        do {
            let result = try body()
            try execute("COMMIT")
            return result
        } catch {
            try? execute("ROLLBACK")
            throw error
        }
    }

    package func run(_ sql: String, _ arguments: [String?] = []) throws {
        let statement = try prepare(sql, arguments)
        defer { sqlite3_finalize(statement) }
        guard sqlite3_step(statement) == SQLITE_DONE else { throw DatabaseError.statement(lastError) }
    }

    package func rows(_ sql: String, _ arguments: [String?] = []) throws -> [[String?]] {
        let statement = try prepare(sql, arguments)
        defer { sqlite3_finalize(statement) }
        var result: [[String?]] = []
        while true {
            let code = sqlite3_step(statement)
            if code == SQLITE_DONE { break }
            guard code == SQLITE_ROW else { throw DatabaseError.statement(lastError) }
            let columns = Int(sqlite3_column_count(statement))
            result.append((0..<columns).map { index in
                sqlite3_column_text(statement, Int32(index)).map { String(cString: $0) }
            })
        }
        return result
    }

    package func strings(_ sql: String, _ arguments: [String?] = []) throws -> [String] {
        try rows(sql, arguments).compactMap { $0.first ?? nil }
    }

    package func bodies(_ table: String) throws -> [[String: Any]] {
        try strings("SELECT body FROM \(table)").compactMap { body in
            try JSONSerialization.jsonObject(with: Data(body.utf8)) as? [String: Any]
        }
    }

    package func value(forKey key: String) throws -> String? {
        try strings("SELECT value FROM kv WHERE key = ?", [key]).first
    }

    package func setValue(_ value: String?, forKey key: String) throws {
        if let value {
            try run("INSERT OR REPLACE INTO kv (key, value) VALUES (?, ?)", [key, value])
        } else {
            try run("DELETE FROM kv WHERE key = ?", [key])
        }
    }

    package func markOutbox(_ collection: String, id: String) throws {
        try run("INSERT OR IGNORE INTO outbox (collection, id) VALUES (?, ?)", [collection, id])
    }

    private func prepare(_ sql: String, _ arguments: [String?]) throws -> OpaquePointer? {
        var statement: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &statement, nil) == SQLITE_OK else {
            throw DatabaseError.statement(lastError)
        }
        for (index, argument) in arguments.enumerated() {
            let position = Int32(index + 1)
            if let argument {
                sqlite3_bind_text(statement, position, argument, -1, Self.transient)
            } else {
                sqlite3_bind_null(statement, position)
            }
        }
        return statement
    }

    private var lastError: String { String(cString: sqlite3_errmsg(handle)) }

    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    package enum DatabaseError: LocalizedError {
        case open(String)
        case statement(String)

        package var errorDescription: String? {
            switch self {
            case .open(let message): return "데이터베이스를 열지 못했습니다: \(message)"
            case .statement(let message): return "데이터베이스 오류: \(message)"
            }
        }
    }
}
