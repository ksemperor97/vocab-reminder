import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const serverRoot = fileURLToPath(new URL("..", import.meta.url));

export function loadEnvFile(envPath = defaultEnvPath()) {
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
  const runtimeRoot = path.resolve(String(
    overrides.runtimeRoot ?? process.env.VOCAB_SYNC_RUNTIME_ROOT ?? serverRoot
  ));
  loadEnvFile(overrides.envPath ?? process.env.VOCAB_SYNC_ENV_PATH ?? path.join(runtimeRoot, ".env"));
  const port = numberValue(overrides.port ?? process.env.VOCAB_SYNC_PORT ?? process.env.PORT, 8787);
  const backupRetention = numberValue(overrides.backupRetention ?? process.env.VOCAB_SYNC_BACKUP_RETENTION, 30);
  const maxBodyBytes = positiveIntegerValue(
    overrides.maxBodyBytes ?? process.env.VOCAB_SYNC_MAX_BODY_BYTES,
    16 * 1024 * 1024
  );
  const maxCollectionItems = positiveIntegerValue(
    overrides.maxCollectionItems ?? process.env.VOCAB_SYNC_MAX_COLLECTION_ITEMS,
    10_000
  );
  const dbPath = resolveRuntimePath(
    overrides.dbPath ?? process.env.VOCAB_SYNC_DB_PATH ?? "./data/vocab-sync.sqlite",
    runtimeRoot
  );
  const backupDir = resolveRuntimePath(
    overrides.backupDir ?? process.env.VOCAB_SYNC_BACKUP_DIR ?? "./backups",
    runtimeRoot
  );
  const appUpdateDir = resolveRuntimePath(
    overrides.appUpdateDir ?? process.env.VOCAB_SYNC_APP_UPDATE_DIR ?? "./app-updates",
    runtimeRoot
  );

  const backupMirrorDirs = String(overrides.backupMirrorDirs ?? process.env.VOCAB_SYNC_BACKUP_MIRROR_DIRS ?? "")
    .split(";")
    .map((entry) => entry.trim())
    .filter(Boolean)
    .map((entry) => resolveRuntimePath(entry.replace(/^~(?=\/)/u, process.env.HOME ?? "~"), runtimeRoot));

  return {
    runtimeRoot,
    host: String(overrides.host ?? process.env.VOCAB_SYNC_HOST ?? "127.0.0.1"),
    port,
    token: String(overrides.token ?? process.env.VOCAB_SYNC_TOKEN ?? ""),
    publicUrl: String(overrides.publicUrl ?? process.env.VOCAB_SYNC_PUBLIC_URL ?? "").trim(),
    dbPath,
    backupDir,
    appUpdateDir,
    backupRetention,
    backupMirrorDirs,
    maxBodyBytes,
    maxCollectionItems
  };
}

function numberValue(raw, fallback) {
  const parsed = Number(raw);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

function positiveIntegerValue(raw, fallback) {
  const parsed = Number(raw);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : fallback;
}

function resolveRuntimePath(raw, runtimeRoot) {
  const value = String(raw);
  return path.isAbsolute(value) ? value : path.resolve(runtimeRoot, value);
}

function defaultEnvPath() {
  const runtimeRoot = process.env.VOCAB_SYNC_RUNTIME_ROOT;
  return process.env.VOCAB_SYNC_ENV_PATH ?? path.join(runtimeRoot || serverRoot, ".env");
}
