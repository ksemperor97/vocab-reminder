// Copies of the daily backups outside this Mac.
//
// The server writes one verified backup a day into its runtime backups folder
// (backup.js). That folder is on the same disk as the database, so the newest
// backup and its manifest are also copied into each folder listed in
// VOCAB_SYNC_BACKUP_MIRROR_DIRS (separated by ";"), for example the Google
// Drive and iCloud Drive folders, whose own apps upload them. A copy is written
// under a temporary name, checked against the manifest's SHA-256 and renamed
// into place; each folder keeps the same number of days as the backups folder.
// A folder that cannot be written is reported and skipped; sync never waits on it.
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const backupNamePattern = /^vocab-sync-(\d{8})\.sqlite$/;

export function parseMirrorDirs(value) {
  return String(value ?? "")
    .split(";")
    .map((entry) => entry.trim())
    .filter(Boolean);
}

export function mirrorLatestBackup(backupDir, mirrorDirs, retention = 30) {
  const results = [];
  if (!mirrorDirs?.length || !fs.existsSync(backupDir)) return results;
  const latest = fs.readdirSync(backupDir).filter((name) => backupNamePattern.test(name)).sort().pop();
  if (!latest) return results;
  const source = path.join(backupDir, latest);
  const manifestSource = `${source}.manifest.json`;
  if (!fs.existsSync(manifestSource)) return results;
  const expectedSha = JSON.parse(fs.readFileSync(manifestSource, "utf8")).databaseSha256;

  for (const dir of mirrorDirs) {
    try {
      fs.mkdirSync(dir, { recursive: true });
      const destination = path.join(dir, latest);
      if (fs.existsSync(destination) && fs.existsSync(`${destination}.manifest.json`) && sha256(destination) === expectedSha) {
        results.push({ dir, file: latest, status: "current" });
      } else {
        copyAtomically(source, destination);
        if (sha256(destination) !== expectedSha) throw new Error("copied backup does not match its manifest checksum");
        copyAtomically(manifestSource, `${destination}.manifest.json`);
        results.push({ dir, file: latest, status: "copied" });
      }
      prune(dir, retention);
    } catch (error) {
      results.push({ dir, file: latest, status: "failed", reason: error.message });
    }
  }
  return results;
}

function copyAtomically(source, destination) {
  const temporary = path.join(path.dirname(destination), `.${path.basename(destination)}.${process.pid}.partial`);
  try {
    fs.copyFileSync(source, temporary);
    fs.renameSync(temporary, destination);
  } finally {
    fs.rmSync(temporary, { force: true });
  }
}

function prune(dir, retention) {
  const keep = Number.isSafeInteger(Number(retention)) && Number(retention) > 0 ? Number(retention) : 30;
  const backups = fs.readdirSync(dir).filter((name) => backupNamePattern.test(name)).sort().reverse();
  for (const stale of backups.slice(keep)) {
    fs.rmSync(path.join(dir, stale), { force: true });
    fs.rmSync(path.join(dir, `${stale}.manifest.json`), { force: true });
  }
}

function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}
