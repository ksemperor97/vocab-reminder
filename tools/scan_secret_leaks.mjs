#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import { isDirectExecution, parseEnv, repositoryRoot } from "./security_helpers.mjs";

const MAX_FILE_BYTES = 50 * 1024 * 1024;
const GENERATED_TEXT_EXTENSIONS = new Set([
  ".htm", ".html", ".json", ".log", ".md", ".out", ".plist", ".properties",
  ".report", ".text", ".txt", ".xml", ".yaml", ".yml",
]);

function parseArguments(argv) {
  const options = {
    runtimeRoot:
      process.env.VOCAB_SYNC_RUNTIME_ROOT ||
      path.join(os.homedir(), "Library", "Application Support", "VocabReminderServer"),
    paths: [],
    defaultPaths: true,
    allowNoSecrets: true,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--runtime-root") options.runtimeRoot = path.resolve(argv[++index]);
    else if (argument === "--server-env") options.serverEnv = path.resolve(argv[++index]);
    else if (argument === "--tunnel-env") options.tunnelEnv = path.resolve(argv[++index]);
    else if (argument === "--path") options.paths.push(path.resolve(argv[++index]));
    else if (argument === "--no-default-paths") options.defaultPaths = false;
    else if (argument === "--require-known-secrets") options.allowNoSecrets = false;
    else throw new Error(`Unknown argument: ${argument}`);
  }
  options.serverEnv ||= path.join(options.runtimeRoot, ".env");
  options.tunnelEnv ||= path.join(options.runtimeRoot, "tunnel.env");
  return options;
}

function addSecret(secrets, kind, value) {
  const normalized = String(value || "").trim();
  if (normalized.length < 16 || /[\r\n]/.test(normalized) || /^replace-with-/i.test(normalized)) return;
  const existing = secrets.find((item) => item.value === normalized);
  if (existing) existing.kinds.add(kind);
  else secrets.push({ value: normalized, kinds: new Set([kind]) });
}

function loadSecrets(options) {
  const secrets = [];
  const sourceFiles = new Set();
  const candidates = [
    [options.serverEnv, [["sync", "VOCAB_SYNC_TOKEN"], ["anthropic", "ANTHROPIC_API_KEY"]]],
    [options.tunnelEnv, [["admin", "CF_WORKER_ADMIN_TOKEN"]]],
    [path.join(repositoryRoot, "server", ".env"), [["sync", "VOCAB_SYNC_TOKEN"], ["anthropic", "ANTHROPIC_API_KEY"]]],
    [path.join(repositoryRoot, "server", "tunnel", ".env"), [["admin", "CF_WORKER_ADMIN_TOKEN"]]],
  ];
  for (const [file, mappings] of candidates) {
    if (!fs.existsSync(file) || !fs.statSync(file).isFile()) continue;
    sourceFiles.add(fs.realpathSync(file));
    const env = parseEnv(fs.readFileSync(file, "utf8"));
    for (const [kind, key] of mappings) addSecret(secrets, kind, env.get(key));
  }
  addSecret(secrets, "sync", process.env.VOCAB_SYNC_TOKEN);
  addSecret(secrets, "sync", process.env.VOCAB_SYNC_DEFAULT_TOKEN);
  addSecret(secrets, "admin", process.env.CF_WORKER_ADMIN_TOKEN);
  addSecret(secrets, "cloudflare-api", process.env.CLOUDFLARE_API_TOKEN);
  addSecret(secrets, "anthropic", process.env.ANTHROPIC_API_KEY);
  return { secrets, sourceFiles };
}

function addFile(targets, file, classification, { generatedOnly = false } = {}) {
  try {
    const stat = fs.lstatSync(file);
    if (stat.isSymbolicLink() || !stat.isFile() || stat.size > MAX_FILE_BYTES) return;
    if (generatedOnly && !GENERATED_TEXT_EXTENSIONS.has(path.extname(file).toLowerCase())) return;
    const resolved = fs.realpathSync(file);
    const previous = targets.get(resolved);
    if (previous === "sensitive" || classification === previous) return;
    targets.set(resolved, classification === "sensitive" ? "sensitive" : previous || classification);
  } catch {
    // A disappearing build log should not abort the rest of the scan.
  }
}

function walk(targets, root, classification, { generatedOnly = false } = {}) {
  if (!fs.existsSync(root)) return;
  const queue = [root];
  while (queue.length > 0) {
    const current = queue.pop();
    let stat;
    try {
      stat = fs.lstatSync(current);
    } catch {
      continue;
    }
    if (stat.isSymbolicLink()) continue;
    if (stat.isDirectory()) {
      for (const name of fs.readdirSync(current)) queue.push(path.join(current, name));
    } else {
      addFile(targets, current, classification, { generatedOnly });
    }
  }
}

function collectTargets(options) {
  const targets = new Map();
  if (options.defaultPaths) {
    try {
      const tracked = execFileSync("git", ["-C", repositoryRoot, "ls-files", "-co", "--exclude-standard", "-z"]);
      for (const relative of tracked.toString("utf8").split("\0").filter(Boolean)) {
        addFile(targets, path.join(repositoryRoot, relative), "sensitive");
      }
    } catch {
      walk(targets, path.join(repositoryRoot, "docs"), "sensitive");
      addFile(targets, path.join(repositoryRoot, "README.md"), "sensitive");
    }

    walk(targets, path.join(options.runtimeRoot, "logs"), "sensitive", { generatedOnly: true });
    walk(targets, path.join(repositoryRoot, "server", "logs"), "sensitive", { generatedOnly: true });
    walk(targets, path.join(os.homedir(), ".gradle", "daemon"), "sensitive", { generatedOnly: true });
    walk(targets, path.join(repositoryRoot, "android", "VocabReminderAndroid", "app", "build", "reports"), "sensitive", { generatedOnly: true });
    const releaseDirectory = path.join(repositoryRoot, "dist", "sync-release");
    walk(targets, releaseDirectory, "artifact");
    addFile(targets, path.join(releaseDirectory, "README.md"), "sensitive");

    const temporaryRoot = fs.realpathSync(os.tmpdir());
    for (const name of fs.readdirSync(temporaryRoot)) {
      if (/^vocab(?:[-_])/.test(name)) {
        walk(targets, path.join(temporaryRoot, name), "sensitive", { generatedOnly: true });
      }
    }
    if (process.env.QC_EVIDENCE_DIR) {
      walk(targets, path.resolve(process.env.QC_EVIDENCE_DIR), "sensitive", { generatedOnly: true });
    }
    for (const extra of String(process.env.SECRET_SCAN_PATHS || "").split(path.delimiter).filter(Boolean)) {
      walk(targets, path.resolve(extra), "sensitive", { generatedOnly: true });
    }
  }
  for (const extra of options.paths) walk(targets, extra, "sensitive");
  return targets;
}

function looksLikeText(buffer) {
  const sample = buffer.subarray(0, Math.min(buffer.length, 8_192));
  let nul = 0;
  for (const byte of sample) if (byte === 0) nul += 1;
  return sample.length === 0 || nul / sample.length < 0.01;
}

function searchableContents(file, initial) {
  if (![".apk", ".aab", ".zip"].includes(path.extname(file).toLowerCase())) return [initial];
  try {
    return [
      initial,
      execFileSync("unzip", ["-p", file], {
        maxBuffer: 200 * 1024 * 1024,
        stdio: ["ignore", "pipe", "ignore"],
      }),
    ];
  } catch {
    return [initial];
  }
}

function suspiciousLiteral(text) {
  if (/-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/.test(text)) return "private-key-material";
  const patterns = [
    /(?:VOCAB_SYNC_TOKEN|VOCAB_SYNC_DEFAULT_TOKEN|CF_WORKER_ADMIN_TOKEN|CLOUDFLARE_API_TOKEN|ADMIN_TOKEN)\s*[:=]\s*["']?([A-Za-z0-9+/_=-]{24,})/g,
    /Bearer\s+([A-Za-z0-9+/_=-]{24,})/gi,
    /\b(sk-ant-[A-Za-z0-9_-]{20,})/g,
  ];
  for (const pattern of patterns) {
    for (const match of text.matchAll(pattern)) {
      const value = match[1].toLowerCase();
      if (
        value.startsWith("replace-with-") ||
        value.startsWith("temporary-") ||
        value.startsWith("example-") ||
        value.startsWith("placeholder-") ||
        value.startsWith("intentionally-") ||
        value.startsWith("fixture-") ||
        value.startsWith("test-") ||
        value.includes("your-token")
      ) continue;
      return "literal-secret-pattern";
    }
  }
  return null;
}

export function scanSecretLeaks(options) {
  const { secrets, sourceFiles } = loadSecrets(options);
  if (secrets.length === 0 && !options.allowNoSecrets) {
    throw new Error("No live secret sources were available for an exact-value scan.");
  }
  const findings = new Map();
  for (const [file, classification] of collectTargets(options)) {
    if (sourceFiles.has(file)) continue;
    let buffer;
    try {
      buffer = fs.readFileSync(file);
    } catch {
      continue;
    }
    const categories = new Set();
    const contents = searchableContents(file, buffer);
    for (const content of contents) {
      for (const secret of secrets) {
        const onlySync = secret.kinds.size === 1 && secret.kinds.has("sync");
        if (classification === "artifact" && onlySync) continue;
        if (content.indexOf(Buffer.from(secret.value)) !== -1) {
          for (const kind of secret.kinds) categories.add(`known-${kind}-secret`);
        }
      }
    }
    if (looksLikeText(buffer)) {
      const heuristic = suspiciousLiteral(buffer.toString("utf8"));
      if (heuristic) categories.add(heuristic);
    }
    if (categories.size > 0) findings.set(file, [...categories].sort());
  }
  return { findings, knownSecretCount: secrets.length };
}

export function main(argv = process.argv.slice(2)) {
  try {
    const result = scanSecretLeaks(parseArguments(argv));
    if (result.findings.size > 0) {
      console.error("Secret leak scan failed. Values are redacted; only paths and categories follow:");
      for (const [file, categories] of [...result.findings].sort(([left], [right]) => left.localeCompare(right))) {
        const display = file.startsWith(`${repositoryRoot}${path.sep}`) ? path.relative(repositoryRoot, file) : file;
        console.error(`- ${display} [${categories.join(", ")}]`);
      }
      return 1;
    }
    console.log(
      result.knownSecretCount > 0
        ? "OK: repository, docs, tests, generated reports, and logs contain no known secret values"
        : "OK: literal-secret scan passed (no live secrets available for exact matching)",
    );
    console.log("OK: Worker admin credentials are absent from release artifacts");
    return 0;
  } catch (error) {
    console.error(`Secret leak scan failed: ${error.message}`);
    return 1;
  }
}

if (isDirectExecution(import.meta.url)) process.exitCode = main();
