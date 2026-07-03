#!/usr/bin/env node
import { spawn } from "node:child_process";

const cloudflaredBin = process.env.CLOUDFLARED_BIN || "cloudflared";
const workerUrl = requiredUrl("CF_WORKER_PUBLIC_URL");
const adminToken = required("CF_WORKER_ADMIN_TOKEN");
const upstream = process.env.VOCAB_SYNC_UPSTREAM_URL || "http://127.0.0.1:8787";
const updatePath = new URL("/__admin/upstream", workerUrl).toString();

let currentTunnelUrl = "";
let child;
let updateInFlight = false;

function required(name) {
  const value = process.env[name]?.trim();
  if (!value || value.startsWith("replace-with-")) {
    console.error(`${name} is required.`);
    process.exit(78);
  }
  return value;
}

function requiredUrl(name) {
  const value = required(name);
  let url;
  try {
    url = new URL(value);
  } catch {
    console.error(`${name} must be a valid URL.`);
    process.exit(78);
  }
  if (url.protocol !== "https:") {
    console.error(`${name} must use https.`);
    process.exit(78);
  }
  return url.toString().replace(/\/$/, "");
}

function maybeExtractTunnelUrl(text) {
  const match = text.match(/https:\/\/[a-zA-Z0-9-]+\.trycloudflare\.com/);
  if (!match) {
    return;
  }
  const tunnelUrl = match[0];
  if (tunnelUrl === currentTunnelUrl) {
    return;
  }
  currentTunnelUrl = tunnelUrl;
  updateWorker(tunnelUrl).catch((error) => {
    console.error(`Failed to publish quick tunnel URL: ${error.message}`);
    child?.kill("SIGTERM");
    process.exitCode = 1;
  });
}

async function updateWorker(tunnelUrl) {
  if (updateInFlight) {
    return;
  }
  updateInFlight = true;
  try {
    for (let attempt = 1; attempt <= 5; attempt += 1) {
      const response = await fetch(updatePath, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-vocab-sync-admin": adminToken,
        },
        body: JSON.stringify({ url: tunnelUrl }),
      });
      if (response.ok) {
        console.log(`Published Cloudflare quick tunnel upstream: ${tunnelUrl}`);
        return;
      }
      const body = await response.text();
      console.error(`Worker upstream update failed, attempt ${attempt}: HTTP ${response.status} ${body}`);
      await new Promise((resolve) => setTimeout(resolve, attempt * 1000));
    }
    throw new Error("worker upstream update failed after 5 attempts");
  } finally {
    updateInFlight = false;
  }
}

function start() {
  console.log(`Starting cloudflared quick tunnel for ${upstream}`);
  child = spawn(cloudflaredBin, ["tunnel", "--no-autoupdate", "--url", upstream], {
    stdio: ["ignore", "pipe", "pipe"],
    env: process.env,
  });

  child.stdout.on("data", (chunk) => {
    const text = chunk.toString();
    process.stdout.write(text);
    maybeExtractTunnelUrl(text);
  });
  child.stderr.on("data", (chunk) => {
    const text = chunk.toString();
    process.stderr.write(text);
    maybeExtractTunnelUrl(text);
  });
  child.on("exit", (code, signal) => {
    console.error(`cloudflared exited: code=${code ?? ""} signal=${signal ?? ""}`);
    process.exit(code ?? 1);
  });
}

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => {
    child?.kill("SIGTERM");
    setTimeout(() => process.exit(0), 500).unref();
  });
}

start();
