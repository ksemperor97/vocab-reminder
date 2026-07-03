import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const serverRoot = fileURLToPath(new URL("..", import.meta.url));

export function loadEnvFile(envPath = path.join(serverRoot, ".env")) {
  if (!fs.existsSync(envPath)) return;
  const lines = fs.readFileSync(envPath, "utf8").split(/\r?\n/);
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) continue;
    const separator = trimmed.indexOf("=");
    if (separator <= 0) continue;
    const key = trimmed.slice(0, separator).trim();
    if (process.env[key] != null) continue;
    let value = trimmed.slice(separator + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    process.env[key] = value;
  }
}

export function readConfig(overrides = {}) {
  loadEnvFile(overrides.envPath);
  const port = numberValue(overrides.port ?? process.env.VOCAB_SYNC_PORT ?? process.env.PORT, 8787);
  const backupRetention = numberValue(overrides.backupRetention ?? process.env.VOCAB_SYNC_BACKUP_RETENTION, 30);
  const dbPath = resolveServerPath(overrides.dbPath ?? process.env.VOCAB_SYNC_DB_PATH ?? "./data/vocab-sync.sqlite");
  const backupDir = resolveServerPath(overrides.backupDir ?? process.env.VOCAB_SYNC_BACKUP_DIR ?? "./backups");

  return {
    host: String(overrides.host ?? process.env.VOCAB_SYNC_HOST ?? "127.0.0.1"),
    port,
    token: String(overrides.token ?? process.env.VOCAB_SYNC_TOKEN ?? ""),
    dbPath,
    backupDir,
    backupRetention
  };
}

function numberValue(raw, fallback) {
  const parsed = Number(raw);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

function resolveServerPath(raw) {
  const value = String(raw);
  return path.isAbsolute(value) ? value : path.join(serverRoot, value);
}
