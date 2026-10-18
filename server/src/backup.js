import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import {
  assertMatchingDatabaseSummaries,
  checkpointDatabase,
  inspectDatabase,
  openDatabase
} from "./db.js";

const backupNamePattern = /^vocab-sync-(\d{8})\.sqlite$/;
const inFlightBackups = new Map();

export function ensureDailyBackup(db, dbPath, backupDir, retention = 30, now = new Date()) {
  if (!dbPath || dbPath === ":memory:" || !fs.existsSync(dbPath)) return Promise.resolve(null);
  const key = path.resolve(backupDir, `vocab-sync-${yyyymmdd(now)}.sqlite`);
  const active = inFlightBackups.get(key);
  if (active) return active;

  const task = ensureDailyBackupOnce(db, dbPath, backupDir, retention, now)
    .finally(() => inFlightBackups.delete(key));
  inFlightBackups.set(key, task);
  return task;
}

async function ensureDailyBackupOnce(db, dbPath, backupDir, retention, now) {
  fs.mkdirSync(backupDir, { recursive: true });
  const stamp = yyyymmdd(now);
  const destination = path.join(backupDir, `vocab-sync-${stamp}.sqlite`);

  if (fs.existsSync(destination)) {
    try {
      const verification = verifyBackup(destination);
      if (!fs.existsSync(manifestPath(destination))) {
        const createdAt = fs.statSync(destination).mtime;
        writeJsonAtomic(manifestPath(destination), backupManifest(destination, verification, createdAt));
      }
      const { summary } = verifyBackup(destination, { requireManifest: true });
      // Today's backup follows the database until the day ends: a backup taken
      // in the morning must not stand for the whole day's changes.
      if (sameContents(summary, inspectDatabase(db))) {
        pruneBackups(backupDir, retention);
        return null;
      }
    } catch (error) {
      quarantineInvalidBackup(destination, now, error);
    }
  }

  const partial = partialPath(destination);
  try {
    await db.backup(partial);
    const verification = verifyDatabaseFile(partial);
    fs.renameSync(partial, destination);
    const manifest = backupManifest(destination, verification, now);
    writeJsonAtomic(manifestPath(destination), manifest);
    verifyBackup(destination, { requireManifest: true });
  } catch (error) {
    removeDatabaseFiles(partial);
    throw error;
  }

  pruneBackups(backupDir, retention);
  return destination;
}

function sameContents(a, b) {
  return JSON.stringify(a?.counts) === JSON.stringify(b?.counts) &&
    a?.idChecksum === b?.idChecksum &&
    a?.contentChecksum === b?.contentChecksum;
}

export function verifyBackup(backupPath, options = {}) {
  const { expectedSummary, requireManifest = false } = options;
  const verification = verifyDatabaseFile(backupPath);
  const sidecarPath = manifestPath(backupPath);

  if (fs.existsSync(sidecarPath)) {
    const manifest = JSON.parse(fs.readFileSync(sidecarPath, "utf8"));
    if (manifest.schemaVersion !== 1) {
      throw new Error(`Unsupported backup manifest schema: ${manifest.schemaVersion}`);
    }
    if (manifest.databaseSha256 !== verification.databaseSha256) {
      throw new Error("Backup file checksum does not match its manifest.");
    }
    assertMatchingDatabaseSummaries(manifest.summary, verification.summary, "backup manifest");
  } else if (requireManifest) {
    throw new Error(`Backup manifest is missing: ${sidecarPath}`);
  }

  if (expectedSummary) {
    assertMatchingDatabaseSummaries(expectedSummary, verification.summary, "backup");
  }
  return verification;
}

export async function migrateDatabaseFile(sourcePath, destinationPath) {
  const source = path.resolve(sourcePath);
  const destination = path.resolve(destinationPath);
  if (source === destination) throw new Error("Source and destination database paths must differ.");
  if (!fs.existsSync(source)) throw new Error(`Source database does not exist: ${source}`);
  refuseExistingDestination(destination);

  fs.mkdirSync(path.dirname(destination), { recursive: true });
  const partial = partialPath(destination);
  const sourceDb = openDatabase(source);
  try {
    const checkpoint = checkpointDatabase(sourceDb, "TRUNCATE");
    const sourceSummary = inspectDatabase(sourceDb);
    await sourceDb.backup(partial);
    const restored = verifyDatabaseFile(partial);
    assertMatchingDatabaseSummaries(sourceSummary, restored.summary, "migrated database");
    fs.renameSync(partial, destination);
    const finalVerification = verifyDatabaseFile(destination);
    assertMatchingDatabaseSummaries(sourceSummary, finalVerification.summary, "migrated database");
    return {
      source,
      destination,
      checkpoint,
      summary: finalVerification.summary,
      databaseSha256: finalVerification.databaseSha256
    };
  } catch (error) {
    removeDatabaseFiles(partial);
    throw error;
  } finally {
    sourceDb.close();
  }
}

export function restoreBackup(backupPath, destinationPath) {
  const source = path.resolve(backupPath);
  const destination = path.resolve(destinationPath);
  refuseExistingDestination(destination);
  const sourceVerification = verifyBackup(source, { requireManifest: true });
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  const partial = partialPath(destination);

  try {
    fs.copyFileSync(source, partial, fs.constants.COPYFILE_EXCL);
    const restored = verifyDatabaseFile(partial);
    assertMatchingDatabaseSummaries(sourceVerification.summary, restored.summary, "restored database");
    fs.renameSync(partial, destination);
    return {
      source,
      destination,
      summary: restored.summary,
      databaseSha256: restored.databaseSha256
    };
  } catch (error) {
    removeDatabaseFiles(partial);
    throw error;
  }
}

export function latestBackupPath(backupDir) {
  if (!fs.existsSync(backupDir)) return null;
  const names = fs.readdirSync(backupDir)
    .filter((name) => backupNamePattern.test(name))
    .sort()
    .reverse();
  return names.length > 0 ? path.join(backupDir, names[0]) : null;
}

export function verifyDatabaseFile(dbPath) {
  if (!fs.existsSync(dbPath)) throw new Error(`Database does not exist: ${dbPath}`);
  const db = openDatabase(dbPath, {
    readonly: true,
    fileMustExist: true,
    migrateSchema: false
  });
  try {
    return {
      path: path.resolve(dbPath),
      sizeBytes: fs.statSync(dbPath).size,
      databaseSha256: sha256File(dbPath),
      summary: inspectDatabase(db)
    };
  } finally {
    db.close();
  }
}

export function writeJsonAtomic(destination, value) {
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  const temporary = path.join(
    path.dirname(destination),
    `.${path.basename(destination)}.${process.pid}.${crypto.randomUUID()}.tmp`
  );
  const serialized = `${JSON.stringify(value, null, 2)}\n`;
  fs.writeFileSync(temporary, serialized, { mode: 0o600, flag: "wx" });
  fs.renameSync(temporary, destination);
}

export function createRestoreDrillDirectory(prefix = "vocab-sync-restore-drill-") {
  return fs.mkdtempSync(path.join(os.tmpdir(), prefix));
}

function backupManifest(backupPath, verification, now) {
  return {
    schemaVersion: 1,
    createdAt: now.toISOString(),
    backupFile: path.basename(backupPath),
    sizeBytes: verification.sizeBytes,
    databaseSha256: verification.databaseSha256,
    summary: verification.summary
  };
}

function quarantineInvalidBackup(backupPath, now, error) {
  const suffix = `.invalid-${compactTimestamp(now)}`;
  const quarantined = `${backupPath}${suffix}`;
  fs.renameSync(backupPath, quarantined);
  const sidecar = manifestPath(backupPath);
  if (fs.existsSync(sidecar)) fs.renameSync(sidecar, `${sidecar}${suffix}`);
  writeJsonAtomic(`${quarantined}.error.json`, {
    quarantinedAt: now.toISOString(),
    reason: String(error?.message ?? error)
  });
}

function refuseExistingDestination(destination) {
  const conflicts = [destination, `${destination}-wal`, `${destination}-shm`].filter(fs.existsSync);
  if (conflicts.length > 0) {
    throw new Error(`Restore destination must be empty: ${conflicts.join(", ")}`);
  }
}

function pruneBackups(backupDir, retention) {
  const normalizedRetention = Number.isSafeInteger(Number(retention)) && Number(retention) > 0
    ? Number(retention)
    : 30;
  const backups = fs.readdirSync(backupDir)
    .filter((name) => backupNamePattern.test(name))
    .sort()
    .reverse();
  for (const stale of backups.slice(normalizedRetention)) {
    const backupPath = path.join(backupDir, stale);
    fs.rmSync(backupPath, { force: true });
    fs.rmSync(manifestPath(backupPath), { force: true });
  }
}

function removeDatabaseFiles(dbPath) {
  for (const suffix of ["", "-wal", "-shm"]) {
    fs.rmSync(`${dbPath}${suffix}`, { force: true });
  }
}

function sha256File(filePath) {
  const hash = crypto.createHash("sha256");
  hash.update(fs.readFileSync(filePath));
  return hash.digest("hex");
}

function manifestPath(backupPath) {
  return `${backupPath}.manifest.json`;
}

function partialPath(destination) {
  return path.join(
    path.dirname(destination),
    `.${path.basename(destination)}.${process.pid}.${crypto.randomUUID()}.partial`
  );
}

function yyyymmdd(date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}${month}${day}`;
}

function compactTimestamp(date) {
  return date.toISOString().replace(/[-:.TZ]/g, "");
}
