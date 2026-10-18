#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import {
  isDirectExecution,
  isPathInside,
  privateMode,
  readEnvFile,
  repositoryRoot,
  timingSafeEqualText,
  validateSecret,
} from "./security_helpers.mjs";

function parseArguments(argv) {
  const options = {
    runtimeRoot:
      process.env.VOCAB_SYNC_RUNTIME_ROOT ||
      path.join(os.homedir(), "Library", "Application Support", "VocabReminderServer"),
    allowMissing: false,
    artifactDir: path.join(repositoryRoot, "dist", "sync-release"),
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--allow-missing") options.allowMissing = true;
    else if (argument === "--runtime-root") options.runtimeRoot = path.resolve(argv[++index]);
    else if (argument === "--server-env") options.serverEnv = path.resolve(argv[++index]);
    else if (argument === "--tunnel-env") options.tunnelEnv = path.resolve(argv[++index]);
    else if (argument === "--artifact-dir") options.artifactDir = path.resolve(argv[++index]);
    else throw new Error(`Unknown argument: ${argument}`);
  }
  options.serverEnv ||= path.join(options.runtimeRoot, ".env");
  options.tunnelEnv ||= path.join(options.runtimeRoot, "tunnel.env");
  return options;
}

function listFiles(root) {
  if (!fs.existsSync(root)) return [];
  const files = [];
  const queue = [root];
  while (queue.length > 0) {
    const current = queue.pop();
    const stat = fs.lstatSync(current);
    if (stat.isSymbolicLink()) continue;
    if (stat.isDirectory()) {
      for (const name of fs.readdirSync(current)) queue.push(path.join(current, name));
    } else if (stat.isFile() && stat.size <= 100 * 1024 * 1024) {
      files.push(current);
    }
  }
  return files;
}

function assertPrivateFile(file, label) {
  const mode = privateMode(file);
  if ((mode & 0o077) !== 0) {
    throw new Error(`${label} must not be accessible by group or other users (mode ${mode.toString(8)}).`);
  }
}

function artifactContains(file, needle) {
  try {
    if (fs.readFileSync(file).indexOf(needle) !== -1) return true;
    if ([".apk", ".aab", ".zip"].includes(path.extname(file).toLowerCase())) {
      const expanded = execFileSync("unzip", ["-p", file], {
        maxBuffer: 200 * 1024 * 1024,
        stdio: ["ignore", "pipe", "ignore"],
      });
      return expanded.indexOf(needle) !== -1;
    }
  } catch {
    // A malformed archive is handled by the dedicated artifact verifier.
  }
  return false;
}

export function checkSecretBoundaries(options) {
  const missing = [options.serverEnv, options.tunnelEnv].filter((file) => !fs.existsSync(file));
  if (missing.length > 0) {
    if (options.allowMissing) {
      return { skipped: true, reason: "runtime secret files are not present" };
    }
    throw new Error(`Runtime secret file is missing: ${missing.join(", ")}`);
  }

  for (const [file, label] of [
    [options.serverEnv, "server env"],
    [options.tunnelEnv, "tunnel env"],
  ]) {
    if (isPathInside(fs.realpathSync(file), repositoryRoot)) {
      throw new Error(`${label} must stay outside the repository: ${file}`);
    }
    assertPrivateFile(file, label);
    const parentMode = privateMode(path.dirname(file));
    if ((parentMode & 0o077) !== 0) {
      throw new Error(`${label} directory must not be accessible by group or other users.`);
    }
  }

  const server = readEnvFile(options.serverEnv);
  const tunnel = readEnvFile(options.tunnelEnv);
  const syncToken = validateSecret(server.get("VOCAB_SYNC_TOKEN"), "VOCAB_SYNC_TOKEN");
  // A Funnel-only host has no Cloudflare Worker, so no admin token to guard.
  const funnelOnly = tunnel.get("TUNNEL_PROVIDER") === "tailscale_funnel" && !tunnel.has("CF_WORKER_ADMIN_TOKEN");
  const adminToken = funnelOnly ? null : validateSecret(tunnel.get("CF_WORKER_ADMIN_TOKEN"), "CF_WORKER_ADMIN_TOKEN");

  if (adminToken && timingSafeEqualText(syncToken, adminToken)) {
    throw new Error("VOCAB_SYNC_TOKEN and CF_WORKER_ADMIN_TOKEN must be different secrets.");
  }
  const cloudflareApiToken = String(process.env.CLOUDFLARE_API_TOKEN || "").trim();
  if (
    cloudflareApiToken &&
    (timingSafeEqualText(syncToken, cloudflareApiToken) || (adminToken && timingSafeEqualText(adminToken, cloudflareApiToken)))
  ) {
    throw new Error("Cloudflare deployment credentials must not be reused as runtime tokens.");
  }
  for (const forbidden of ["CF_WORKER_ADMIN_TOKEN", "ADMIN_TOKEN", "CLOUDFLARE_API_TOKEN"]) {
    if (server.has(forbidden)) throw new Error(`${forbidden} must not be stored in the server env.`);
  }
  for (const forbidden of ["VOCAB_SYNC_TOKEN", "VOCAB_SYNC_DEFAULT_TOKEN", "CLOUDFLARE_API_TOKEN", "ANTHROPIC_API_KEY"]) {
    if (tunnel.has(forbidden)) throw new Error(`${forbidden} must not be stored in the tunnel env.`);
  }
  // The Claude key for context meanings is server-only: never in client release artifacts.
  const anthropicKey = String(server.get("ANTHROPIC_API_KEY") || "").trim();
  if (anthropicKey) {
    const keyBytes = Buffer.from(anthropicKey);
    const keyLeaks = listFiles(options.artifactDir).filter((file) => artifactContains(file, keyBytes));
    if (keyLeaks.length > 0) {
      throw new Error(
        `ANTHROPIC_API_KEY was embedded in release artifacts: ${keyLeaks.map((file) => path.relative(repositoryRoot, file)).join(", ")}`,
      );
    }
  }

  const adminBytes = adminToken ? Buffer.from(adminToken) : null;
  const artifactLeaks = adminBytes ? listFiles(options.artifactDir).filter((file) => artifactContains(file, adminBytes)) : [];
  if (artifactLeaks.length > 0) {
    throw new Error(
      `Worker admin token was embedded in release artifacts: ${artifactLeaks.map((file) => path.relative(repositoryRoot, file)).join(", ")}`,
    );
  }

  return {
    skipped: false,
    serverEnv: options.serverEnv,
    tunnelEnv: options.tunnelEnv,
    artifactsChecked: fs.existsSync(options.artifactDir),
  };
}

export function main(argv = process.argv.slice(2)) {
  try {
    const result = checkSecretBoundaries(parseArguments(argv));
    if (result.skipped) {
      console.log(`NOT RUN: secret boundary check (${result.reason})`);
      return 0;
    }
    console.log("OK: sync and Worker admin tokens are present, strong, and distinct");
    console.log("OK: server/tunnel secret files are isolated and privately readable");
    console.log(
      result.artifactsChecked
        ? "OK: Worker admin token is absent from release artifacts"
        : "NOT RUN: release artifact admin-token scan (artifacts missing)",
    );
    return 0;
  } catch (error) {
    console.error(`Secret boundary check failed: ${error.message}`);
    return 1;
  }
}

if (isDirectExecution(import.meta.url)) process.exitCode = main();
