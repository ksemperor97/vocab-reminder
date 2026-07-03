#!/usr/bin/env node
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { openDatabase } from "../server/src/db.js";
import { createSyncServer } from "../server/src/http.js";

const rootDir = path.resolve(new URL("..", import.meta.url).pathname);
const macDir = path.join(rootDir, "mac", "VocabReminderMac");
const token = "mac-smoke-token";
const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), "vocab-mac-sync-smoke-"));
const dbPath = path.join(tempRoot, "vocab-sync.sqlite");
const backupDir = path.join(tempRoot, "backups");
const db = openDatabase(dbPath);
const { server } = createSyncServer({
  db,
  dbPath,
  backupDir,
  backupRetention: 30,
  token
});

try {
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  const serverURL = `http://127.0.0.1:${port}`;

  await runSwiftSmoke(serverURL);
  console.log("OK: Mac CloudSyncClient roundtrips with local Node sync server");
} finally {
  await new Promise((resolve) => server.close(resolve));
  db.close();
  fs.rmSync(tempRoot, { recursive: true, force: true });
}

function runSwiftSmoke(serverURL) {
  return new Promise((resolve, reject) => {
    const child = spawn(
      "swift",
      ["run", "VocabReminderMac", "--check-cloud-sync-smoke"],
      {
        cwd: macDir,
        stdio: "inherit",
        env: {
          ...process.env,
          VOCAB_SYNC_SMOKE_URL: serverURL,
          VOCAB_SYNC_SMOKE_TOKEN: token
        }
      }
    );
    child.on("error", reject);
    child.on("close", (code) => {
      if (code === 0) {
        resolve();
      } else {
        reject(new Error(`swift smoke exited with code ${code}`));
      }
    });
  });
}
