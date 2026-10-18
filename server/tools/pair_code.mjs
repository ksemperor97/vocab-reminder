#!/usr/bin/env node
// Prints a pairing code for a new device, made on the server Mac itself.
//
//   node server/tools/pair_code.mjs            # code + the address to open on the new device
//   node server/tools/pair_code.mjs --devices  # list paired devices
//   node server/tools/pair_code.mjs --revoke <device-id>
//
// Reads the runtime .env (VOCAB_SYNC_DB_PATH) and tunnel.env (VOCAB_SYNC_PUBLIC_URL).
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { openDatabase } from "../src/db.js";
import { createDeviceRegistry } from "../src/devices.js";

const runtimeRoot = process.env.VOCAB_SYNC_RUNTIME_ROOT ?? path.join(os.homedir(), "Library/Application Support/VocabReminderServer");
const env = { ...readEnv(path.join(runtimeRoot, "tunnel.env")), ...readEnv(path.join(runtimeRoot, ".env")) };
const dbPath = path.resolve(runtimeRoot, env.VOCAB_SYNC_DB_PATH ?? "data/vocab-sync.sqlite");
const db = openDatabase(dbPath);
const devices = createDeviceRegistry(db, { legacyToken: env.VOCAB_SYNC_TOKEN });
const args = process.argv.slice(2);
try {
  if (args[0] === "--devices") {
    for (const device of devices.list()) {
      console.log(`${device.id}  ${device.platform.padEnd(8)} ${device.name}  last seen ${device.last_seen_at ?? "-"}${device.revoked_at ? "  (disconnected)" : ""}`);
    }
  } else if (args[0] === "--revoke") {
    devices.revoke(args[1]);
    console.log(`Disconnected ${args[1]}.`);
  } else {
    const { code, expiresAt } = devices.createCode("server-owner");
    const base = (env.VOCAB_SYNC_PUBLIC_URL ?? "").replace(/\/+$/u, "");
    console.log(`Code: ${code}  (valid until ${expiresAt}, once)`);
    if (base) console.log(`Open on the new device: ${base}/pair?code=${code}`);
  }
} finally {
  db.close();
}

function readEnv(file) {
  if (!fs.existsSync(file)) return {};
  return Object.fromEntries(fs.readFileSync(file, "utf8").split(/\r?\n/u)
    .map((line) => line.match(/^([A-Z0-9_]+)=(.*)$/u)).filter(Boolean).map((m) => [m[1], m[2].trim()]));
}
