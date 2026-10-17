import { migrateChangeLog } from "./changes.js";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import Database from "better-sqlite3";

export const entityTables = Object.freeze([
  "cards",
  "contexts",
  "definition_candidates",
  "review_events",
  "recommendation_feedback"
]);

export const databaseTables = Object.freeze([...entityTables, "settings"]);

export function openDatabase(dbPath, options = {}) {
  const {
    readonly = false,
    fileMustExist = false,
    migrateSchema = !readonly,
    verifyIntegrity = true
  } = options;

  if (dbPath !== ":memory:" && !readonly) {
    fs.mkdirSync(path.dirname(dbPath), { recursive: true });
  }

  const db = new Database(dbPath, { readonly, fileMustExist });
  try {
    db.pragma("foreign_keys = ON");
    db.pragma("busy_timeout = 5000");
    if (!readonly) db.pragma("journal_mode = WAL");
    if (migrateSchema) migrateDatabaseSchema(db);
    if (verifyIntegrity) assertDatabaseIntegrity(db);
    return db;
  } catch (error) {
    db.close();
    throw error;
  }
}

export function migrateDatabaseSchema(db) {
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
  migrateChangeLog(db);
}

export function checkpointDatabase(db, mode = "TRUNCATE") {
  const normalizedMode = String(mode).toUpperCase();
  if (!new Set(["PASSIVE", "FULL", "RESTART", "TRUNCATE"]).has(normalizedMode)) {
    throw new Error(`Unsupported SQLite checkpoint mode: ${mode}`);
  }
  const rows = db.pragma(`wal_checkpoint(${normalizedMode})`);
  const result = rows[0] ?? {};
  if (Number(result.busy ?? 0) !== 0) {
    throw new Error(`SQLite WAL checkpoint is busy (${JSON.stringify(result)}).`);
  }
  return result;
}

export function assertDatabaseIntegrity(db) {
  const rows = db.pragma("integrity_check");
  const messages = rows.flatMap((row) => Object.values(row)).map(String);
  if (messages.length !== 1 || messages[0].toLowerCase() !== "ok") {
    throw new Error(`SQLite integrity check failed: ${messages.join("; ") || "no result"}`);
  }
  return "ok";
}

export function inspectDatabase(db) {
  assertDatabaseIntegrity(db);

  const counts = {};
  const idHash = crypto.createHash("sha256");
  const contentHash = crypto.createHash("sha256");

  for (const table of databaseTables) {
    const rows = db.prepare(`SELECT id, body_json FROM ${table} ORDER BY id`).all();
    counts[table] = rows.length;
    for (const row of rows) {
      updateLengthPrefixed(idHash, table);
      updateLengthPrefixed(idHash, row.id);
      updateLengthPrefixed(contentHash, table);
      updateLengthPrefixed(contentHash, row.id);
      updateLengthPrefixed(contentHash, row.body_json);
    }
  }

  return {
    integrity: "ok",
    counts,
    idChecksum: idHash.digest("hex"),
    contentChecksum: contentHash.digest("hex")
  };
}

export function assertMatchingDatabaseSummaries(expected, actual, label = "database") {
  if (JSON.stringify(expected.counts) !== JSON.stringify(actual.counts)) {
    throw new Error(`${label} entity counts do not match.`);
  }
  if (expected.idChecksum !== actual.idChecksum) {
    throw new Error(`${label} ID checksum does not match.`);
  }
  if (expected.contentChecksum !== actual.contentChecksum) {
    throw new Error(`${label} content checksum does not match.`);
  }
}

function updateLengthPrefixed(hash, value) {
  const bytes = Buffer.from(String(value ?? ""), "utf8");
  const length = Buffer.allocUnsafe(8);
  length.writeBigUInt64BE(BigInt(bytes.length));
  hash.update(length);
  hash.update(bytes);
}
