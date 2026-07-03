import fs from "node:fs";
import path from "node:path";

export async function ensureDailyBackup(db, dbPath, backupDir, retention = 30, now = new Date()) {
  if (!dbPath || dbPath === ":memory:" || !fs.existsSync(dbPath)) return null;
  fs.mkdirSync(backupDir, { recursive: true });
  const stamp = yyyymmdd(now);
  const destination = path.join(backupDir, `vocab-sync-${stamp}.sqlite`);
  if (fs.existsSync(destination)) return null;

  await db.backup(destination);
  pruneBackups(backupDir, retention);
  return destination;
}

function pruneBackups(backupDir, retention) {
  const backups = fs.readdirSync(backupDir)
    .filter((name) => /^vocab-sync-\d{8}\.sqlite$/.test(name))
    .sort()
    .reverse();
  for (const stale of backups.slice(Math.max(0, retention))) {
    fs.rmSync(path.join(backupDir, stale), { force: true });
  }
}

function yyyymmdd(date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}${month}${day}`;
}
