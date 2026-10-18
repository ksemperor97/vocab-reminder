import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {
  ensureDailyBackup,
  migrateDatabaseFile,
  restoreBackup,
  verifyBackup,
  verifyDatabaseFile
} from "../src/backup.js";
import { mirrorLatestBackup } from "../src/backup_mirror.js";
import { readConfig } from "../src/config.js";
import { inspectDatabase, openDatabase } from "../src/db.js";
import { runRestoreDrill } from "../src/restore_drill.js";

test("resolves deployed database and backup paths under the runtime root", () => {
  const runtimeRoot = path.join(os.tmpdir(), "VocabReminderServer runtime");
  const config = readConfig({
    runtimeRoot,
    envPath: path.join(runtimeRoot, "missing.env"),
    dbPath: "data/custom.sqlite",
    backupDir: "backups"
  });

  assert.equal(config.runtimeRoot, runtimeRoot);
  assert.equal(config.dbPath, path.join(runtimeRoot, "data", "custom.sqlite"));
  assert.equal(config.backupDir, path.join(runtimeRoot, "backups"));
});

test("daily backups are integrity checked, checksummed, and manifested", async (t) => {
  const fixture = createFixture(t);
  seedDatabase(fixture.db);
  const now = new Date("2026-07-13T01:02:03.000Z");
  const backupPath = await ensureDailyBackup(fixture.db, fixture.dbPath, fixture.backupDir, 30, now);

  assert.ok(backupPath);
  assert.ok(fs.existsSync(`${backupPath}.manifest.json`));
  const sourceSummary = inspectDatabase(fixture.db);
  const verified = verifyBackup(backupPath, { expectedSummary: sourceSummary, requireManifest: true });
  assert.equal(verified.summary.counts.cards, 1);
  assert.equal(verified.summary.counts.settings, 1);
});

test("a corrupt same-day backup is quarantined before a verified replacement is created", async (t) => {
  const fixture = createFixture(t);
  seedDatabase(fixture.db);
  const now = new Date("2026-07-13T01:02:03.000Z");
  const backupPath = await ensureDailyBackup(fixture.db, fixture.dbPath, fixture.backupDir, 30, now);
  fs.writeFileSync(backupPath, "not a sqlite database");

  const replacement = await ensureDailyBackup(fixture.db, fixture.dbPath, fixture.backupDir, 30, now);
  assert.equal(replacement, backupPath);
  verifyBackup(backupPath, { requireManifest: true });
  assert.ok(fs.readdirSync(fixture.backupDir).some((name) => name.includes(".invalid-")));
});

test("today's backup is replaced when the database changed after it was taken", async (t) => {
  const fixture = createFixture(t);
  const morning = new Date("2026-07-13T01:00:00.000Z");
  const backupPath = await ensureDailyBackup(fixture.db, fixture.dbPath, fixture.backupDir, 30, morning);
  assert.equal(verifyBackup(backupPath).summary.counts.cards, 0);

  seedDatabase(fixture.db);
  const evening = new Date("2026-07-13T10:00:00.000Z");
  assert.equal(await ensureDailyBackup(fixture.db, fixture.dbPath, fixture.backupDir, 30, evening), backupPath);
  assert.equal(verifyBackup(backupPath, { requireManifest: true }).summary.counts.cards, 1);
  assert.equal(await ensureDailyBackup(fixture.db, fixture.dbPath, fixture.backupDir, 30, evening), null, "unchanged data keeps the backup");
});

test("the newest backup is copied to every mirror folder, checked, and pruned there", async (t) => {
  const fixture = createFixture(t);
  seedDatabase(fixture.db);
  const backupPath = await ensureDailyBackup(fixture.db, fixture.dbPath, fixture.backupDir, 30, new Date("2026-07-13T01:00:00.000Z"));
  const drive = path.join(fixture.root, "Google Drive", "VocabReminder Backups");
  const icloud = path.join(fixture.root, "iCloud", "VocabReminder Backups");
  fs.mkdirSync(icloud, { recursive: true });
  for (const day of ["20260710", "20260711", "20260712"]) fs.writeFileSync(path.join(icloud, `vocab-sync-${day}.sqlite`), "old");
  const blocked = path.join(fixture.root, "not-a-folder");
  fs.writeFileSync(blocked, "");

  const results = mirrorLatestBackup(fixture.backupDir, [drive, icloud, blocked], 2);
  assert.deepEqual(results.map((result) => result.status), ["copied", "copied", "failed"]);
  for (const dir of [drive, icloud]) {
    const copy = path.join(dir, path.basename(backupPath));
    assert.deepEqual(fs.readFileSync(copy), fs.readFileSync(backupPath));
    verifyBackup(copy, { requireManifest: true });
  }
  assert.deepEqual(fs.readdirSync(icloud).filter((name) => name.endsWith(".sqlite")).sort(),
    ["vocab-sync-20260712.sqlite", "vocab-sync-20260713.sqlite"]);
  assert.deepEqual(mirrorLatestBackup(fixture.backupDir, [drive], 2).map((result) => result.status), ["current"]);
});

test("mirror folders come from VOCAB_SYNC_BACKUP_MIRROR_DIRS separated by semicolons", () => {
  const config = readConfig({ runtimeRoot: "/tmp/runtime", envPath: "/tmp/runtime/missing.env", backupMirrorDirs: "/a/Google Drive/x; /b/iCloud/y ;" });
  assert.deepEqual(config.backupMirrorDirs, ["/a/Google Drive/x", "/b/iCloud/y"]);
});

test("migration checkpoints the source and preserves counts plus ID and content checksums", async (t) => {
  const root = temporaryRoot(t);
  const sourcePath = path.join(root, "legacy", "vocab-sync.sqlite");
  const destinationPath = path.join(root, "runtime", "data", "vocab-sync.sqlite");
  const sourceDb = openDatabase(sourcePath);
  seedDatabase(sourceDb);
  const expected = inspectDatabase(sourceDb);
  sourceDb.close();

  const result = await migrateDatabaseFile(sourcePath, destinationPath);
  const migrated = verifyDatabaseFile(destinationPath);
  assert.deepEqual(result.summary.counts, expected.counts);
  assert.equal(migrated.summary.idChecksum, expected.idChecksum);
  assert.equal(migrated.summary.contentChecksum, expected.contentChecksum);
  assert.equal(result.checkpoint.busy, 0);
});

test("restore only writes to an empty path and preserves the verified backup snapshot", async (t) => {
  const fixture = createFixture(t);
  seedDatabase(fixture.db);
  const backupPath = await ensureDailyBackup(
    fixture.db,
    fixture.dbPath,
    fixture.backupDir,
    30,
    new Date("2026-07-13T01:02:03.000Z")
  );
  const restoredPath = path.join(fixture.root, "restored", "vocab-sync.sqlite");

  const result = restoreBackup(backupPath, restoredPath);
  const restored = verifyDatabaseFile(restoredPath);
  assert.deepEqual(restored.summary, result.summary);
  assert.throws(() => restoreBackup(backupPath, restoredPath), /must be empty/);
});

test("restore drill boots an isolated server and reads the restored snapshot", async (t) => {
  const fixture = createFixture(t);
  seedDatabase(fixture.db);
  const backupPath = await ensureDailyBackup(
    fixture.db,
    fixture.dbPath,
    fixture.backupDir,
    30,
    new Date("2026-07-13T01:02:03.000Z")
  );
  const statePath = path.join(fixture.root, "state", "last_restore_drill.json");

  const result = await runRestoreDrill({ backupPath, statePath });
  assert.equal(result.status, "passed");
  assert.equal(result.summary.counts.cards, 1);
  assert.equal(JSON.parse(fs.readFileSync(statePath, "utf8")).status, "passed");
});

function createFixture(t) {
  const root = temporaryRoot(t);
  const dbPath = path.join(root, "data", "vocab-sync.sqlite");
  const backupDir = path.join(root, "backups");
  const db = openDatabase(dbPath);
  t.after(() => {
    if (db.open) db.close();
  });
  return { root, dbPath, backupDir, db };
}

function temporaryRoot(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "vocab-runtime-safety-test-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  return root;
}

function seedDatabase(db) {
  const card = {
    id: "card-runtime-safety",
    lemma: "durable",
    meaning_ko: "오래가는",
    version: 1,
    updated_at: "2026-07-13T00:00:00.000Z"
  };
  db.prepare(`
    INSERT INTO cards (id, body_json, created_at, updated_at, updated_at_ms, version)
    VALUES (?, ?, ?, ?, ?, ?)
  `).run(
    card.id,
    JSON.stringify(card),
    card.updated_at,
    card.updated_at,
    Date.parse(card.updated_at),
    card.version
  );
  const settings = {
    daily_word_goal: 8,
    settings_updated_at: "2026-07-13T00:00:00.000Z"
  };
  db.prepare("INSERT INTO settings (id, body_json, updated_at) VALUES ('main', ?, ?)")
    .run(JSON.stringify(settings), settings.settings_updated_at);
}
