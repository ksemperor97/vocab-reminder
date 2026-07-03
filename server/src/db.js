import fs from "node:fs";
import path from "node:path";
import Database from "better-sqlite3";

const entityTables = [
  "cards",
  "contexts",
  "definition_candidates",
  "review_events",
  "recommendation_feedback"
];

export function openDatabase(dbPath) {
  fs.mkdirSync(path.dirname(dbPath), { recursive: true });
  const db = new Database(dbPath);
  db.pragma("journal_mode = WAL");
  db.pragma("foreign_keys = ON");
  migrate(db);
  return db;
}

function migrate(db) {
  for (const table of entityTables) {
    db.exec(`
      CREATE TABLE IF NOT EXISTS ${table} (
        id TEXT PRIMARY KEY,
        body_json TEXT NOT NULL,
        created_at TEXT,
        updated_at TEXT,
        updated_at_ms INTEGER,
        version INTEGER DEFAULT 0,
        deleted_at TEXT,
        deleted_at_ms INTEGER
      );
    `);
  }
  db.exec(`
    CREATE TABLE IF NOT EXISTS settings (
      id TEXT PRIMARY KEY CHECK (id = 'main'),
      body_json TEXT NOT NULL,
      updated_at TEXT NOT NULL
    );
  `);
  db.exec("CREATE INDEX IF NOT EXISTS idx_cards_deleted_at ON cards(deleted_at);");
}
