import { randomUUID } from "node:crypto";

const tables = ["cards", "contexts", "definition_candidates", "review_events", "recommendation_feedback", "settings"];

// The log and entity writes share the SQLite transaction. Legacy clients also
// produce changes because tracking lives in triggers, not the HTTP handler.
export function migrateChangeLog(db) {
  db.transaction(() => {
    db.exec(`CREATE TABLE IF NOT EXISTS sync_metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS sync_changes (
        sequence INTEGER PRIMARY KEY AUTOINCREMENT,
        entity_table TEXT NOT NULL, entity_id TEXT NOT NULL
      );`);
    if (!db.prepare("SELECT value FROM sync_metadata WHERE key = 'epoch'").get()) {
      db.prepare("INSERT INTO sync_metadata VALUES ('epoch', ?)").run(randomUUID());
      for (const table of tables) {
        db.exec(`INSERT INTO sync_changes(entity_table, entity_id) SELECT '${table}', id FROM ${table} ORDER BY id;`);
      }
    }
    for (const table of tables) {
      db.exec(`CREATE TRIGGER IF NOT EXISTS ${table}_sync_insert AFTER INSERT ON ${table}
        BEGIN INSERT INTO sync_changes(entity_table, entity_id) VALUES ('${table}', NEW.id); END;
        CREATE TRIGGER IF NOT EXISTS ${table}_sync_update AFTER UPDATE OF body_json ON ${table}
        WHEN OLD.body_json != NEW.body_json
        BEGIN INSERT INTO sync_changes(entity_table, entity_id) VALUES ('${table}', NEW.id); END;`);
    }
  })();
}

export function readChanges(db, cursor = "", limit = 400) {
  if (!Number.isSafeInteger(limit) || limit < 1 || limit > 1000) {
    throw Object.assign(new Error("limit must be between 1 and 1000."), { status: 400 });
  }
  if (typeof cursor !== "string" || cursor.length > 100 || (cursor && !/^[a-f0-9-]{36}:\d+$/.test(cursor))) {
    throw Object.assign(new Error("Invalid sync cursor."), { status: 400 });
  }
  return db.transaction(() => {
    const epoch = db.prepare("SELECT value FROM sync_metadata WHERE key = 'epoch'").get().value;
    const max = db.prepare("SELECT COALESCE(MAX(sequence), 0) AS value FROM sync_changes").get().value;
    const [previousEpoch, rawSequence] = cursor.split(":");
    const requested = Number(rawSequence ?? 0);
    const reset = !!cursor && (previousEpoch !== epoch || !Number.isSafeInteger(requested) || requested > max);
    const sequence = reset ? 0 : requested;
    const rows = db.prepare("SELECT * FROM sync_changes WHERE sequence > ? ORDER BY sequence LIMIT ?").all(sequence, limit);
    const result = Object.fromEntries(tables.map(table => [table, table === "settings" ? null : []]));
    const seen = new Set();
    for (const row of rows) {
      const key = `${row.entity_table}:${row.entity_id}`;
      if (seen.has(key)) continue;
      seen.add(key);
      if (!tables.includes(row.entity_table)) throw new Error("Invalid change log table.");
      const entity = db.prepare(`SELECT body_json FROM ${row.entity_table} WHERE id = ?`).get(row.entity_id);
      if (!entity) continue;
      if (row.entity_table === "settings") result.settings = JSON.parse(entity.body_json);
      else result[row.entity_table].push(JSON.parse(entity.body_json));
    }
    const next = rows.at(-1)?.sequence ?? sequence;
    return { ...result, sync_cursor: `${epoch}:${next}`, has_more: next < max, reset };
  })();
}
