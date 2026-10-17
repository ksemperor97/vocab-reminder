import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import {
  createRestoreDrillDirectory,
  latestBackupPath,
  restoreBackup,
  verifyBackup,
  writeJsonAtomic
} from "./backup.js";
import { databaseTables, inspectDatabase, openDatabase } from "./db.js";
import { createSyncServer } from "./http.js";

export async function runRestoreDrill({ backupPath, backupDir, statePath } = {}) {
  const startedAt = new Date();
  const selectedBackup = backupPath ?? latestBackupPath(backupDir);
  if (!selectedBackup) throw new Error(`No daily backup exists in ${backupDir}.`);

  const temporaryRoot = createRestoreDrillDirectory();
  const restoredPath = path.join(temporaryRoot, "restored", "vocab-sync.sqlite");
  let db;
  let server;

  try {
    const backupVerification = verifyBackup(selectedBackup, { requireManifest: true });
    const restoreResult = restoreBackup(selectedBackup, restoredPath);
    db = openDatabase(restoredPath);
    const restoredSummary = inspectDatabase(db);
    const token = crypto.randomBytes(32).toString("hex");
    ({ server } = createSyncServer({
      recommendationCatalog: null, // Verify restored data without generating or migrating cards.
      db, token
    }));
    await new Promise((resolve, reject) => {
      server.once("error", reject);
      server.listen(0, "127.0.0.1", resolve);
    });

    const { port } = server.address();
    const response = await fetch(`http://127.0.0.1:${port}/sync/snapshot`, {
      headers: { Authorization: `Bearer ${token}` }
    });
    if (response.status !== 200) {
      throw new Error(`Restored snapshot returned HTTP ${response.status}.`);
    }
    const snapshot = await response.json();
    assertSnapshotCounts(snapshot, restoredSummary.counts);

    const result = {
      schemaVersion: 1,
      status: "passed",
      startedAt: startedAt.toISOString(),
      completedAt: new Date().toISOString(),
      backupPath: path.resolve(selectedBackup),
      backupSha256: backupVerification.databaseSha256,
      restoredSha256: restoreResult.databaseSha256,
      summary: restoredSummary
    };
    if (statePath) writeJsonAtomic(statePath, result);
    return result;
  } catch (error) {
    if (statePath) {
      writeJsonAtomic(statePath, {
        schemaVersion: 1,
        status: "failed",
        startedAt: startedAt.toISOString(),
        completedAt: new Date().toISOString(),
        backupPath: selectedBackup ? path.resolve(selectedBackup) : null,
        error: String(error?.message ?? error)
      });
    }
    throw error;
  } finally {
    if (server?.listening) {
      await new Promise((resolve) => server.close(resolve));
    }
    if (db?.open) db.close();
    fs.rmSync(temporaryRoot, { recursive: true, force: true });
  }
}

function assertSnapshotCounts(snapshot, expectedCounts) {
  for (const table of databaseTables) {
    const actual = table === "settings"
      ? (snapshot.settings == null ? 0 : 1)
      : (Array.isArray(snapshot[table]) ? snapshot[table].length : -1);
    if (actual !== expectedCounts[table]) {
      throw new Error(
        `Restored snapshot count mismatch for ${table}: expected ${expectedCounts[table]}, received ${actual}.`
      );
    }
  }
}
