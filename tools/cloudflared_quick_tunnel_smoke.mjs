#!/usr/bin/env node
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { openDatabase } from "../server/src/db.js";
import { createSyncServer } from "../server/src/http.js";

const token = "quick-tunnel-smoke-token";
const timeoutMs = Number(process.env.CLOUDFLARED_TUNNEL_SMOKE_TIMEOUT_MS ?? 120000);
const root = fs.mkdtempSync(path.join(os.tmpdir(), "vocab-quick-tunnel-"));
const dbPath = path.join(root, "vocab-sync.sqlite");
const backupDir = path.join(root, "backups");
const db = openDatabase(dbPath);
const { server } = createSyncServer({
  db,
  dbPath,
  backupDir,
  backupRetention: 30,
  token
});

let cloudflared;

try {
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  const localUrl = `http://127.0.0.1:${port}`;

  cloudflared = spawn("cloudflared", [
    "tunnel",
    "--url",
    localUrl,
    "--no-autoupdate"
  ], {
    stdio: ["ignore", "pipe", "pipe"]
  });

  const tunnelUrl = await waitForTunnelUrl(cloudflared, timeoutMs);
  console.log(`OK: cloudflared quick tunnel is ready at ${tunnelUrl}`);

  const health = await retryJson(`${tunnelUrl}/health`, token, timeoutMs);
  assert.equal(health.status, 200);
  assert.equal(health.body.ok, true);
  console.log("OK: HTTPS tunnel /health returns 200");

  const wrongToken = await retryJson(`${tunnelUrl}/health`, "wrong-token", timeoutMs);
  assert.equal(wrongToken.status, 401);
  assert.equal(wrongToken.body.error.message, "Authentication required.");
  console.log("OK: HTTPS tunnel rejects a wrong bearer token");
} finally {
  if (cloudflared && !cloudflared.killed) {
    cloudflared.kill("SIGTERM");
  }
  await new Promise((resolve) => server.close(resolve));
  db.close();
  fs.rmSync(root, { recursive: true, force: true });
}

function waitForTunnelUrl(process, timeout) {
  return new Promise((resolve, reject) => {
    let settled = false;
    let output = "";
    const timer = setTimeout(() => {
      finish(reject, new Error(`Timed out waiting for cloudflared quick tunnel URL after ${timeout}ms.\n${output}`));
    }, timeout);

    const onData = (chunk) => {
      const text = chunk.toString("utf8");
      output += text;
      const match = text.match(/https:\/\/[-a-zA-Z0-9]+\.trycloudflare\.com/);
      if (match) {
        finish(resolve, match[0]);
      }
    };

    const finish = (callback, value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      callback(value);
    };

    process.stdout.on("data", onData);
    process.stderr.on("data", onData);
    process.on("error", (error) => finish(reject, error));
    process.on("exit", (code, signal) => {
      if (!settled) {
        finish(reject, new Error(`cloudflared exited before publishing a tunnel URL: code=${code} signal=${signal}\n${output}`));
      }
    });
  });
}

async function retryJson(url, bearerToken, timeout) {
  const deadline = Date.now() + timeout;
  let lastError;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(url, {
        headers: {
          "Authorization": `Bearer ${bearerToken}`
        }
      });
      return {
        status: response.status,
        body: await response.json()
      };
    } catch (error) {
      lastError = error;
      await sleep(1000);
    }
  }
  const detail = lastError ? ` Last error: ${describeError(lastError)}` : "";
  throw new Error(`Timed out fetching ${url} after ${timeout}ms.${detail}`);
}

function describeError(error) {
  const cause = error.cause;
  if (cause?.code && cause?.hostname) {
    return `${error.message} (${cause.code}: ${cause.hostname})`;
  }
  if (cause?.code) {
    return `${error.message} (${cause.code})`;
  }
  return error.message;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
