// Devices and pairing.
//
// Each app gets its own token by pairing instead of carrying one shared secret,
// so a public build contains no credentials. An authorized device (or the
// server owner with server/tools/pair_code.mjs) creates a 6-digit code that
// lasts 10 minutes and works once; a new app exchanges it for a long random
// token. Only the token's SHA-256 is stored. VOCAB_SYNC_TOKEN keeps working as
// the "legacy" device so apps built with it keep syncing until it is rotated.
//
// Guessing: a code has 10^6 values and lives 10 minutes. After 10 failed claims
// within 10 minutes every open code is cancelled and claims are refused for the
// rest of that window, so a guesser gets at most 10 tries per code generation.
import crypto from "node:crypto";

export const LEGACY_DEVICE_ID = "legacy";
const codeLifetimeMs = 10 * 60 * 1000;
const failureWindowMs = 10 * 60 * 1000;
const maxFailuresPerWindow = 10;
const lastSeenWriteIntervalMs = 5 * 60 * 1000;

export function migrateDeviceTables(db) {
  db.exec(`
    CREATE TABLE IF NOT EXISTS devices (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      platform TEXT NOT NULL,
      token_hash TEXT NOT NULL UNIQUE,
      created_at TEXT NOT NULL,
      last_seen_at TEXT,
      revoked_at TEXT
    );
    CREATE TABLE IF NOT EXISTS pairing_codes (
      code_hash TEXT PRIMARY KEY,
      created_by TEXT NOT NULL,
      created_at TEXT NOT NULL,
      expires_at_ms INTEGER NOT NULL,
      used_at TEXT
    );
  `);
}

export function createDeviceRegistry(db, { legacyToken, now = Date.now } = {}) {
  migrateDeviceTables(db);
  const statements = {
    byTokenHash: db.prepare("SELECT id, last_seen_at FROM devices WHERE token_hash = ? AND revoked_at IS NULL"),
    touch: db.prepare("UPDATE devices SET last_seen_at = ? WHERE id = ?"),
    insertCode: db.prepare("INSERT OR REPLACE INTO pairing_codes (code_hash, created_by, created_at, expires_at_ms) VALUES (?, ?, ?, ?)"),
    openCode: db.prepare("SELECT code_hash FROM pairing_codes WHERE code_hash = ? AND used_at IS NULL AND expires_at_ms > ?"),
    useCode: db.prepare("UPDATE pairing_codes SET used_at = ? WHERE code_hash = ?"),
    cancelOpenCodes: db.prepare("UPDATE pairing_codes SET used_at = ? WHERE used_at IS NULL"),
    pruneCodes: db.prepare("DELETE FROM pairing_codes WHERE expires_at_ms < ?"),
    insertDevice: db.prepare("INSERT INTO devices (id, name, platform, token_hash, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?)"),
    list: db.prepare("SELECT id, name, platform, created_at, last_seen_at, revoked_at FROM devices ORDER BY created_at"),
    revoke: db.prepare("UPDATE devices SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL")
  };
  let failures = [];
  let lockedUntil = 0;

  /** The device id a bearer token belongs to, or null. */
  function authenticate(token) {
    if (typeof token !== "string" || token === "") return null;
    if (legacyToken && constantTimeEquals(token, legacyToken)) return LEGACY_DEVICE_ID;
    const row = statements.byTokenHash.get(sha256(token));
    if (!row) return null;
    const time = now();
    if (!row.last_seen_at || time - Date.parse(row.last_seen_at) > lastSeenWriteIntervalMs) {
      statements.touch.run(new Date(time).toISOString(), row.id);
    }
    return row.id;
  }

  function createCode(createdBy) {
    const time = now();
    statements.pruneCodes.run(time - codeLifetimeMs);
    const code = String(crypto.randomInt(0, 1_000_000)).padStart(6, "0");
    statements.insertCode.run(sha256(code), createdBy, new Date(time).toISOString(), time + codeLifetimeMs);
    return { code, expiresAt: new Date(time + codeLifetimeMs).toISOString(), expiresInSeconds: codeLifetimeMs / 1000 };
  }

  function claim({ code, deviceName, platform }) {
    const time = now();
    failures = failures.filter((at) => time - at < failureWindowMs);
    if (time < lockedUntil) throw httpError(429, "Too many wrong codes. Create a new code in a few minutes.");
    const normalized = String(code ?? "").replace(/\D/gu, "");
    const codeHash = sha256(normalized);
    const found = normalized.length === 6 ? statements.openCode.get(codeHash, time) : undefined;
    if (!found) {
      failures.push(time);
      if (failures.length >= maxFailuresPerWindow) {
        statements.cancelOpenCodes.run(new Date(time).toISOString());
        lockedUntil = time + failureWindowMs;
        failures = [];
      }
      throw httpError(400, "That code is wrong or has expired. Create a new one.");
    }
    const deviceId = crypto.randomUUID();
    const token = `vrd_${crypto.randomBytes(32).toString("base64url")}`;
    const stamp = new Date(time).toISOString();
    db.transaction(() => {
      statements.useCode.run(stamp, codeHash);
      statements.insertDevice.run(
        deviceId,
        cleanLabel(deviceName, "Unnamed device"),
        cleanLabel(platform, "unknown").toLowerCase(),
        sha256(token),
        stamp,
        stamp
      );
    })();
    return { deviceId, token };
  }

  function list() {
    return statements.list.all().map((row) => ({
      id: row.id,
      name: row.name,
      platform: row.platform,
      created_at: row.created_at,
      last_seen_at: row.last_seen_at,
      revoked_at: row.revoked_at
    }));
  }

  function revoke(deviceId) {
    if (deviceId === LEGACY_DEVICE_ID) throw httpError(400, "The legacy token is revoked by rotating VOCAB_SYNC_TOKEN.");
    const changes = statements.revoke.run(new Date(now()).toISOString(), String(deviceId ?? "")).changes;
    if (changes === 0) throw httpError(404, "No such device, or it is already disconnected.");
    return { revoked: deviceId };
  }

  return { authenticate, createCode, claim, list, revoke };
}

function cleanLabel(value, fallback) {
  const text = String(value ?? "").replace(/[\u0000-\u001f]/gu, "").trim().slice(0, 80);
  return text || fallback;
}

function sha256(value) {
  return crypto.createHash("sha256").update(String(value)).digest("hex");
}

function constantTimeEquals(actual, expected) {
  const a = crypto.createHash("sha256").update(actual).digest();
  const b = crypto.createHash("sha256").update(expected).digest();
  return crypto.timingSafeEqual(a, b);
}

function httpError(status, message) {
  const error = new Error(message);
  error.status = status;
  return error;
}
