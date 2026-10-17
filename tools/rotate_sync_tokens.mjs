#!/usr/bin/env node
import { execFileSync, spawnSync } from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { checkSecretBoundaries } from "./check_secret_boundaries.mjs";
import {
  ensurePrivateDirectory,
  isDirectExecution,
  isPathInside,
  parseEnv,
  privateMode,
  randomToken,
  readEnvFile,
  replaceEnvValue,
  repositoryRoot,
  secretDigest,
  timingSafeEqualText,
  validateSecret,
  writeFileAtomic,
} from "./security_helpers.mjs";

const ACTIVATE_CONFIRMATION = "ROTATE-SINGLE-USER-TOKENS";
const ROLLBACK_CONFIRMATION = "ROLLBACK-SINGLE-USER-TOKENS";

function parseArguments(argv) {
  const options = {
    command: "plan",
    runtimeRoot:
      process.env.VOCAB_SYNC_RUNTIME_ROOT ||
      path.join(os.homedir(), "Library", "Application Support", "VocabReminderServer"),
  };
  if (argv[0] && !argv[0].startsWith("--")) options.command = argv.shift();
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--runtime-root") options.runtimeRoot = path.resolve(argv[++index]);
    else if (argument === "--server-env") options.serverEnv = path.resolve(argv[++index]);
    else if (argument === "--tunnel-env") options.tunnelEnv = path.resolve(argv[++index]);
    else if (argument === "--bundle") options.bundle = path.resolve(argv[++index]);
    else if (argument === "--confirm") options.confirm = argv[++index];
    else throw new Error(`Unknown argument: ${argument}`);
  }
  options.serverEnv ||= path.join(options.runtimeRoot, ".env");
  options.tunnelEnv ||= path.join(options.runtimeRoot, "tunnel.env");
  return options;
}

function sha256File(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function timestamp() {
  return new Date().toISOString().replace(/[-:]/g, "").replace(/\.\d{3}Z$/, "Z");
}

function shellQuote(value) {
  return `'${String(value).replaceAll("'", `'"'"'`)}'`;
}

function assertPrivateBundle(bundle) {
  if (!path.isAbsolute(bundle)) throw new Error("Rotation bundle path must be absolute.");
  if (isPathInside(bundle, repositoryRoot)) throw new Error("Rotation bundle must stay outside the repository.");
  if (fs.existsSync(bundle) && (privateMode(bundle) & 0o077) !== 0) {
    throw new Error(`Rotation bundle must have mode 700: ${bundle}`);
  }
}

function environmentFiles(options) {
  if (!fs.existsSync(options.serverEnv)) throw new Error(`Server env is missing: ${options.serverEnv}`);
  if (!fs.existsSync(options.tunnelEnv)) throw new Error(`Tunnel env is missing: ${options.tunnelEnv}`);
  checkSecretBoundaries({
    serverEnv: options.serverEnv,
    tunnelEnv: options.tunnelEnv,
    artifactDir: path.join(repositoryRoot, "dist", "sync-release"),
    allowMissing: false,
  });
  return {
    serverText: fs.readFileSync(options.serverEnv, "utf8"),
    tunnelText: fs.readFileSync(options.tunnelEnv, "utf8"),
    server: readEnvFile(options.serverEnv),
    tunnel: readEnvFile(options.tunnelEnv),
  };
}

function defaultBundle(options) {
  return path.join(options.runtimeRoot, "state", "token-rotation", timestamp());
}

function manifestPath(bundle) {
  return path.join(bundle, "manifest.json");
}

function loadBundle(bundle) {
  assertPrivateBundle(bundle);
  const file = manifestPath(bundle);
  if (!fs.existsSync(file)) throw new Error(`Rotation manifest is missing: ${file}`);
  const manifest = JSON.parse(fs.readFileSync(file, "utf8"));
  if (manifest.schemaVersion !== 1) throw new Error("Unsupported rotation bundle schema.");
  if ((privateMode(file) & 0o077) !== 0) throw new Error("Rotation manifest must have mode 600.");
  for (const [label, envPath] of [["server", manifest.serverEnv], ["tunnel", manifest.tunnelEnv]]) {
    if (!path.isAbsolute(envPath || "") || isPathInside(envPath, repositoryRoot)) {
      throw new Error(`Rotation manifest ${label} env path is not an external absolute path.`);
    }
  }
  const files = {
    backupServer: path.join(bundle, "backup", "server.env"),
    backupTunnel: path.join(bundle, "backup", "tunnel.env"),
    stagedServer: path.join(bundle, "staged", "server.env"),
    stagedTunnel: path.join(bundle, "staged", "tunnel.env"),
    artifacts: path.join(bundle, "artifacts"),
    artifactMarker: path.join(bundle, "artifacts-verified.json"),
  };
  for (const filePath of [files.backupServer, files.backupTunnel, files.stagedServer, files.stagedTunnel]) {
    if (!fs.existsSync(filePath) || (privateMode(filePath) & 0o077) !== 0) {
      throw new Error(`Rotation secret file is missing or not private: ${filePath}`);
    }
  }
  const backupServer = parseEnv(fs.readFileSync(files.backupServer, "utf8"));
  const backupTunnel = parseEnv(fs.readFileSync(files.backupTunnel, "utf8"));
  const stagedServer = parseEnv(fs.readFileSync(files.stagedServer, "utf8"));
  const stagedTunnel = parseEnv(fs.readFileSync(files.stagedTunnel, "utf8"));
  const digests = {
    oldSync: secretDigest(validateSecret(backupServer.get("VOCAB_SYNC_TOKEN"), "backup sync token")),
    oldAdmin: secretDigest(validateSecret(backupTunnel.get("CF_WORKER_ADMIN_TOKEN"), "backup admin token")),
    newSync: secretDigest(validateSecret(stagedServer.get("VOCAB_SYNC_TOKEN"), "staged sync token")),
    newAdmin: secretDigest(validateSecret(stagedTunnel.get("CF_WORKER_ADMIN_TOKEN"), "staged admin token")),
  };
  for (const [name, digest] of Object.entries(digests)) {
    if (manifest.tokenDigests?.[name] !== digest) throw new Error(`Rotation bundle ${name} digest does not match.`);
  }
  if (
    sha256File(files.backupServer) !== manifest.activeFileDigests?.server ||
    sha256File(files.backupTunnel) !== manifest.activeFileDigests?.tunnel
  ) {
    throw new Error("Rotation backups do not match the prepared active env digests.");
  }
  return { bundle, manifest, files };
}

function verifyActiveState(bundleState) {
  const { manifest } = bundleState;
  if (!fs.existsSync(manifest.serverEnv) || !fs.existsSync(manifest.tunnelEnv)) {
    throw new Error("Active runtime env files moved after rotation preparation.");
  }
  if (
    sha256File(manifest.serverEnv) !== manifest.activeFileDigests.server ||
    sha256File(manifest.tunnelEnv) !== manifest.activeFileDigests.tunnel
  ) {
    throw new Error("Active runtime env changed after preparation; create a fresh rotation bundle.");
  }
}

function writeManifest(bundle, manifest) {
  writeFileAtomic(manifestPath(bundle), `${JSON.stringify(manifest, null, 2)}\n`, 0o600);
}

export function plan(options) {
  environmentFiles(options);
  console.log("DRY RUN: no tokens, env files, services, Cloudflare secrets, or artifacts were changed.");
  console.log("Rotation phases:");
  console.log("1. prepare a private bundle containing atomic env backups and newly generated secrets");
  console.log("2. build and verify Android/Mac artifacts from the staged sync token");
  console.log("3. during a maintenance window, revoke both old tokens and atomically activate staged env files");
  console.log("4. verify new-token 200, old-token 401, promote artifacts, then reinstall retained devices");
  console.log("5. rollback restores both env backups and the old Worker secret if activation fails");
  console.log("Run with `prepare` only when you are ready to create a private staged bundle.");
}

export function prepare(options) {
  const active = environmentFiles(options);
  const bundle = options.bundle || defaultBundle(options);
  assertPrivateBundle(bundle);
  if (fs.existsSync(bundle)) throw new Error(`Rotation bundle already exists: ${bundle}`);
  ensurePrivateDirectory(bundle);
  ensurePrivateDirectory(path.join(bundle, "backup"));
  ensurePrivateDirectory(path.join(bundle, "staged"));

  const oldSync = validateSecret(active.server.get("VOCAB_SYNC_TOKEN"), "VOCAB_SYNC_TOKEN");
  const oldAdmin = validateSecret(active.tunnel.get("CF_WORKER_ADMIN_TOKEN"), "CF_WORKER_ADMIN_TOKEN");
  let newSync = randomToken();
  let newAdmin = randomToken();
  while (timingSafeEqualText(newSync, oldSync)) newSync = randomToken();
  while (
    timingSafeEqualText(newAdmin, oldAdmin) ||
    timingSafeEqualText(newAdmin, newSync) ||
    timingSafeEqualText(newAdmin, oldSync)
  ) newAdmin = randomToken();

  const backupServer = path.join(bundle, "backup", "server.env");
  const backupTunnel = path.join(bundle, "backup", "tunnel.env");
  const stagedServer = path.join(bundle, "staged", "server.env");
  const stagedTunnel = path.join(bundle, "staged", "tunnel.env");
  writeFileAtomic(backupServer, active.serverText, 0o600);
  writeFileAtomic(backupTunnel, active.tunnelText, 0o600);
  writeFileAtomic(stagedServer, replaceEnvValue(active.serverText, "VOCAB_SYNC_TOKEN", newSync), 0o600);
  writeFileAtomic(stagedTunnel, replaceEnvValue(active.tunnelText, "CF_WORKER_ADMIN_TOKEN", newAdmin), 0o600);

  const manifest = {
    schemaVersion: 1,
    status: "prepared",
    createdAt: new Date().toISOString(),
    repositoryRoot,
    runtimeRoot: path.resolve(options.runtimeRoot),
    serverEnv: path.resolve(options.serverEnv),
    tunnelEnv: path.resolve(options.tunnelEnv),
    workerUrl: active.tunnel.get("CF_WORKER_PUBLIC_URL") || "",
    activeFileDigests: {
      server: sha256File(options.serverEnv),
      tunnel: sha256File(options.tunnelEnv),
    },
    tokenDigests: {
      oldSync: secretDigest(oldSync),
      oldAdmin: secretDigest(oldAdmin),
      newSync: secretDigest(newSync),
      newAdmin: secretDigest(newAdmin),
    },
  };
  writeManifest(bundle, manifest);
  const command = shellQuote(path.join(repositoryRoot, "tools", "rotate_sync_tokens.mjs"));
  const quotedBundle = shellQuote(bundle);
  writeFileAtomic(
    path.join(bundle, "README.md"),
    [
      "# VocabReminder token rotation bundle",
      "",
      "This directory contains both old and proposed secrets. Keep it mode 0700 and never copy it to the repository or cloud storage.",
      "No active token was changed by `prepare`.",
      "",
      "Build and verify staged release artifacts:",
      `node ${command} build --bundle ${quotedBundle}`,
      "",
      "Activate only in a maintenance window after reviewing docs/single_user_security_model.md:",
      `node ${command} activate --bundle ${quotedBundle} --confirm ${ACTIVATE_CONFIRMATION}`,
      "",
      "Emergency rollback before distributing the new artifacts:",
      `node ${command} rollback --bundle ${quotedBundle} --confirm ${ROLLBACK_CONFIRMATION}`,
      "",
      "This file intentionally contains no token values.",
      "",
    ].join("\n"),
    0o600,
  );
  console.log("Prepared a private token-rotation bundle; active tokens remain unchanged.");
  console.log(`Bundle: ${bundle}`);
  console.log("Next: run the bundle's documented build command before any activation.");
  return bundle;
}

function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd || repositoryRoot,
    env: options.env || process.env,
    input: options.input,
    stdio: options.input === undefined ? "inherit" : ["pipe", "inherit", "inherit"],
    encoding: "utf8",
  });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${path.basename(command)} failed with exit code ${result.status}.`);
}

function buildEnvironment(overrides) {
  const environment = Object.fromEntries(
    Object.entries(process.env).filter(
      ([name]) => ![
        "CF_WORKER_ADMIN_TOKEN",
        "CLOUDFLARE_API_TOKEN",
        "VOCAB_SYNC_TOKEN",
        "VOCAB_SYNC_DEFAULT_TOKEN",
      ].includes(name),
    ),
  );
  return { ...environment, ...overrides };
}

export function buildArtifacts(options) {
  const state = loadBundle(options.bundle);
  verifyActiveState(state);
  const stagedServer = parseEnv(fs.readFileSync(state.files.stagedServer, "utf8"));
  const stagedTunnel = parseEnv(fs.readFileSync(state.files.stagedTunnel, "utf8"));
  const syncToken = validateSecret(stagedServer.get("VOCAB_SYNC_TOKEN"), "staged VOCAB_SYNC_TOKEN");
  const workerUrl = stagedTunnel.get("CF_WORKER_PUBLIC_URL") || state.manifest.workerUrl;
  if (!workerUrl.startsWith("https://")) throw new Error("Rotation bundle has no HTTPS Worker URL.");
  fs.rmSync(state.files.artifacts, { recursive: true, force: true });
  ensurePrivateDirectory(state.files.artifacts);
  const childEnvironment = buildEnvironment({
    VOCAB_SYNC_DEFAULT_URL: workerUrl,
    VOCAB_SYNC_DEFAULT_TOKEN: syncToken,
    SYNC_RELEASE_DIST_DIR: state.files.artifacts,
    // The staged token is not live yet; phones must not be offered this APK.
    SYNC_RELEASE_SKIP_ANDROID_PUBLISH: "1",
  });
  run("bash", [path.join(repositoryRoot, "tools", "build_sync_release.sh")], { env: childEnvironment });
  run("bash", [path.join(repositoryRoot, "tools", "check_sync_release_artifacts.sh")], { env: childEnvironment });
  checkSecretBoundaries({
    serverEnv: state.files.stagedServer,
    tunnelEnv: state.files.stagedTunnel,
    artifactDir: state.files.artifacts,
    allowMissing: false,
  });
  const apk = path.join(state.files.artifacts, "VocabReminderAndroid-release.apk");
  const macBinary = path.join(state.files.artifacts, "VocabReminder.app", "Contents", "MacOS", "VocabReminderMac");
  const macInfoPlist = path.join(state.files.artifacts, "VocabReminder.app", "Contents", "Info.plist");
  const macArchive = path.join(state.files.artifacts, "VocabReminder-macOS.zip");
  const releaseManifest = path.join(state.files.artifacts, "release-manifest.json");
  const checksums = path.join(state.files.artifacts, "SHA256SUMS");
  const marker = {
    schemaVersion: 1,
    verifiedAt: new Date().toISOString(),
    tokenDigest: secretDigest(syncToken),
    artifacts: {
      androidSha256: sha256File(apk),
      macBinarySha256: sha256File(macBinary),
      macInfoPlistSha256: sha256File(macInfoPlist),
      macArchiveSha256: sha256File(macArchive),
      releaseManifestSha256: sha256File(releaseManifest),
      checksumFileSha256: sha256File(checksums),
    },
  };
  writeFileAtomic(state.files.artifactMarker, `${JSON.stringify(marker, null, 2)}\n`, 0o600);
  state.manifest.status = "artifacts_verified";
  state.manifest.artifactsVerifiedAt = marker.verifiedAt;
  writeManifest(state.bundle, state.manifest);
  console.log("Staged Android and Mac artifacts were built and verified without changing active tokens.");
  console.log(`Artifacts: ${state.files.artifacts}`);
}

function putWorkerSecret(token) {
  const environment = Object.fromEntries(
    Object.entries(process.env).filter(
      ([name]) => !["CF_WORKER_ADMIN_TOKEN", "VOCAB_SYNC_TOKEN", "VOCAB_SYNC_DEFAULT_TOKEN"].includes(name),
    ),
  );
  const wrangler = path.join(repositoryRoot, "node_modules", ".bin", "wrangler");
  if (!fs.existsSync(wrangler)) {
    throw new Error(`Pinned Wrangler is missing: ${wrangler}. Run npm ci before rotating tokens.`);
  }
  run(wrangler, ["secret", "put", "ADMIN_TOKEN"], {
    cwd: path.join(repositoryRoot, "server", "tunnel", "cloudflare_worker"),
    env: environment,
    input: token,
  });
}

function restartServices() {
  if (process.platform !== "darwin") throw new Error("Token activation requires macOS launchd.");
  const domain = `gui/${process.getuid()}`;
  execFileSync("launchctl", ["kickstart", "-k", `${domain}/com.vocabreminder.sync`], { stdio: "ignore" });
  execFileSync("launchctl", ["kickstart", "-k", `${domain}/com.vocabreminder.tunnel`], { stdio: "ignore" });
}

async function status(url, headers) {
  try {
    const response = await fetch(url, { headers, signal: AbortSignal.timeout(5_000) });
    await response.body?.cancel();
    return response.status;
  } catch {
    return 0;
  }
}

async function waitForCutover({ workerUrl, port, currentSync, previousSync, currentAdmin, previousAdmin }) {
  for (let attempt = 0; attempt < 30; attempt += 1) {
    const results = await Promise.all([
      status(`http://127.0.0.1:${port}/health`, { authorization: `Bearer ${currentSync}` }),
      status(`http://127.0.0.1:${port}/health`, { authorization: `Bearer ${previousSync}` }),
      status(`${workerUrl}/health`, { authorization: `Bearer ${currentSync}` }),
      status(`${workerUrl}/health`, { authorization: `Bearer ${previousSync}` }),
      status(`${workerUrl}/__admin/upstream`, { "x-vocab-sync-admin": currentAdmin }),
      status(`${workerUrl}/__admin/upstream`, { "x-vocab-sync-admin": previousAdmin }),
    ]);
    if (results.join(",") === "200,401,200,401,200,401") return;
    await new Promise((resolve) => setTimeout(resolve, 1_000));
  }
  throw new Error("Token cutover did not reach the required new=200/old=401 state within 30 seconds.");
}

function artifactPromotionPlan() {
  const destination = path.join(repositoryRoot, "dist", "sync-release");
  return {
    destination,
    temporary: path.join(repositoryRoot, "dist", `.sync-release.rotation-${process.pid}-${timestamp()}`),
    backup: path.join(repositoryRoot, "dist", `sync-release.before-token-rotation-${timestamp()}`),
    hadPrevious: fs.existsSync(destination),
  };
}

function promoteArtifacts(state, plan = artifactPromotionPlan()) {
  const { destination, temporary, backup } = plan;
  fs.rmSync(temporary, { recursive: true, force: true });
  fs.cpSync(state.files.artifacts, temporary, { recursive: true });
  let oldMoved = false;
  try {
    if (fs.existsSync(destination)) {
      fs.renameSync(destination, backup);
      oldMoved = true;
    }
    fs.renameSync(temporary, destination);
    return oldMoved ? backup : null;
  } catch (error) {
    fs.rmSync(temporary, { recursive: true, force: true });
    if (oldMoved && !fs.existsSync(destination)) fs.renameSync(backup, destination);
    throw error;
  }
}

function verifyArtifactMarker(state, stagedSyncToken) {
  if (!fs.existsSync(state.files.artifactMarker)) {
    throw new Error("Staged artifacts are not verified. Run the bundle build step first.");
  }
  const marker = JSON.parse(fs.readFileSync(state.files.artifactMarker, "utf8"));
  const apk = path.join(state.files.artifacts, "VocabReminderAndroid-release.apk");
  const macBinary = path.join(state.files.artifacts, "VocabReminder.app", "Contents", "MacOS", "VocabReminderMac");
  const macInfoPlist = path.join(state.files.artifacts, "VocabReminder.app", "Contents", "Info.plist");
  const macArchive = path.join(state.files.artifacts, "VocabReminder-macOS.zip");
  const releaseManifest = path.join(state.files.artifacts, "release-manifest.json");
  const checksums = path.join(state.files.artifacts, "SHA256SUMS");
  if (
    marker.tokenDigest !== secretDigest(stagedSyncToken) ||
    marker.artifacts?.androidSha256 !== sha256File(apk) ||
    marker.artifacts?.macBinarySha256 !== sha256File(macBinary) ||
    marker.artifacts?.macInfoPlistSha256 !== sha256File(macInfoPlist) ||
    marker.artifacts?.macArchiveSha256 !== sha256File(macArchive) ||
    marker.artifacts?.releaseManifestSha256 !== sha256File(releaseManifest) ||
    marker.artifacts?.checksumFileSha256 !== sha256File(checksums)
  ) {
    throw new Error("Staged artifact verification marker does not match the token or artifact checksums.");
  }
}

function restorePromotedArtifacts(previousArtifacts) {
  const destination = path.join(repositoryRoot, "dist", "sync-release");
  fs.rmSync(destination, { recursive: true, force: true });
  if (previousArtifacts && fs.existsSync(previousArtifacts)) fs.renameSync(previousArtifacts, destination);
}

export function rollbackReleaseArtifacts({ destination, previousArtifactDirectory, quarantineDirectory }) {
  if (!destination) throw new Error("Artifact rollback requires a destination.");
  if (!quarantineDirectory || fs.existsSync(quarantineDirectory)) {
    throw new Error("Artifact rollback requires a new, unused quarantine directory.");
  }
  if (previousArtifactDirectory && fs.existsSync(previousArtifactDirectory)) {
    if (fs.existsSync(destination)) {
      fs.renameSync(destination, quarantineDirectory);
    }
    fs.renameSync(previousArtifactDirectory, destination);
    return { restoredPrevious: true, quarantined: fs.existsSync(quarantineDirectory) };
  }
  if (fs.existsSync(destination)) {
    fs.renameSync(destination, quarantineDirectory);
    return { restoredPrevious: false, quarantined: true };
  }
  return { restoredPrevious: false, quarantined: false };
}

function recoverInterruptedArtifactPromotion(state) {
  const plan = state.manifest.artifactPromotion;
  if (!plan) return;
  const distRoot = path.join(repositoryRoot, "dist");
  for (const key of ["destination", "temporary", "backup"]) {
    if (!isPathInside(plan[key] || "", distRoot)) {
      throw new Error(`Artifact promotion journal has an unsafe ${key} path.`);
    }
  }
  fs.rmSync(plan.temporary, { recursive: true, force: true });
  if (plan.hadPrevious) {
    if (fs.existsSync(plan.backup)) {
      fs.rmSync(plan.destination, { recursive: true, force: true });
      fs.renameSync(plan.backup, plan.destination);
    }
  } else {
    fs.rmSync(plan.destination, { recursive: true, force: true });
  }
}

function recordJournal(state, status, phase, additional = {}) {
  state.manifest.status = status;
  state.manifest.activationPhase = phase;
  Object.assign(state.manifest, additional);
  writeManifest(state.bundle, state.manifest);
}

function compareAndSwapEnvSecret(file, key, expectedValue, replacementValue) {
  if (!fs.existsSync(file)) throw new Error(`Active runtime env file is missing: ${file}`);
  const currentText = fs.readFileSync(file, "utf8");
  const currentValue = validateSecret(parseEnv(currentText).get(key), `active ${key}`);
  if (timingSafeEqualText(currentValue, replacementValue)) return false;
  if (!timingSafeEqualText(currentValue, expectedValue)) {
    throw new Error(`Active ${key} is neither the expected old nor staged value; refusing to overwrite it.`);
  }
  writeFileAtomic(file, replaceEnvValue(currentText, key, replacementValue), 0o600);
  return true;
}

async function restoreOldState(
  state,
  {
    restoreWorker = true,
    putWorkerSecretImpl = putWorkerSecret,
    restartServicesImpl = restartServices,
    onPhase = () => {},
  } = {},
) {
  const oldServer = parseEnv(fs.readFileSync(state.files.backupServer, "utf8"));
  const oldTunnel = parseEnv(fs.readFileSync(state.files.backupTunnel, "utf8"));
  const newServer = parseEnv(fs.readFileSync(state.files.stagedServer, "utf8"));
  const newTunnel = parseEnv(fs.readFileSync(state.files.stagedTunnel, "utf8"));
  const oldSync = validateSecret(oldServer.get("VOCAB_SYNC_TOKEN"), "backup sync token");
  const oldAdmin = validateSecret(oldTunnel.get("CF_WORKER_ADMIN_TOKEN"), "backup admin token");
  const newSync = validateSecret(newServer.get("VOCAB_SYNC_TOKEN"), "staged sync token");
  const newAdmin = validateSecret(newTunnel.get("CF_WORKER_ADMIN_TOKEN"), "staged admin token");
  if (restoreWorker) {
    putWorkerSecretImpl(oldAdmin);
    onPhase("recovery_worker_secret_restored");
  }
  compareAndSwapEnvSecret(state.manifest.serverEnv, "VOCAB_SYNC_TOKEN", newSync, oldSync);
  onPhase("recovery_server_env_restored");
  compareAndSwapEnvSecret(state.manifest.tunnelEnv, "CF_WORKER_ADMIN_TOKEN", newAdmin, oldAdmin);
  onPhase("recovery_tunnel_env_restored");
  restartServicesImpl();
  onPhase("recovery_services_restarted");
}

export async function activate(options, dependencies = {}) {
  if (options.confirm !== ACTIVATE_CONFIRMATION) {
    throw new Error(`Activation requires --confirm ${ACTIVATE_CONFIRMATION}.`);
  }
  const state = loadBundle(options.bundle);
  const backupServer = parseEnv(fs.readFileSync(state.files.backupServer, "utf8"));
  const backupTunnel = parseEnv(fs.readFileSync(state.files.backupTunnel, "utf8"));
  const stagedServerText = fs.readFileSync(state.files.stagedServer, "utf8");
  const stagedTunnelText = fs.readFileSync(state.files.stagedTunnel, "utf8");
  const stagedServer = parseEnv(stagedServerText);
  const stagedTunnel = parseEnv(stagedTunnelText);
  const oldSync = validateSecret(backupServer.get("VOCAB_SYNC_TOKEN"), "backup sync token");
  const oldAdmin = validateSecret(backupTunnel.get("CF_WORKER_ADMIN_TOKEN"), "backup admin token");
  const newSync = validateSecret(stagedServer.get("VOCAB_SYNC_TOKEN"), "staged sync token");
  const newAdmin = validateSecret(stagedTunnel.get("CF_WORKER_ADMIN_TOKEN"), "staged admin token");
  const workerUrl = stagedTunnel.get("CF_WORKER_PUBLIC_URL") || state.manifest.workerUrl;
  const port = stagedServer.get("VOCAB_SYNC_PORT") || "8787";
  const putWorkerSecretImpl = dependencies.putWorkerSecretImpl || putWorkerSecret;
  const restartServicesImpl = dependencies.restartServicesImpl || restartServices;
  const waitForCutoverImpl = dependencies.waitForCutoverImpl || waitForCutover;
  const promoteArtifactsImpl = dependencies.promoteArtifactsImpl || promoteArtifacts;
  const restorePromotedArtifactsImpl = dependencies.restorePromotedArtifactsImpl || restorePromotedArtifacts;
  const recoverInterruptedArtifactPromotionImpl =
    dependencies.recoverInterruptedArtifactPromotionImpl || recoverInterruptedArtifactPromotion;
  const logger = dependencies.logger || console;

  if (state.manifest.status === "activating") {
    logger.error("An interrupted activation was detected; restoring the previous credentials before any retry.");
    try {
      await restoreOldState(state, {
        putWorkerSecretImpl,
        restartServicesImpl,
        onPhase: (phase) => recordJournal(state, "activating", phase),
      });
      recoverInterruptedArtifactPromotionImpl(state);
      recordJournal(state, "activating", "recovery_artifacts_restored");
      recordJournal(state, "artifacts_verified", "interrupted_activation_recovered", {
        activationRecoveredAt: new Date().toISOString(),
      });
    } catch (recoveryError) {
      recordJournal(state, "activating", "recovery_failed", {
        activationRecoveryFailedAt: new Date().toISOString(),
      });
      throw new Error(`Interrupted activation recovery failed: ${recoveryError.message}`);
    }
    throw new Error("Interrupted activation was recovered. Review runtime settings, then run activate again.");
  }
  if (state.manifest.status !== "artifacts_verified") {
    throw new Error("Activation requires a bundle whose artifacts were built and verified.");
  }
  verifyActiveState(state);
  verifyArtifactMarker(state, newSync);

  let promotedArtifactBackup;
  let artifactsPromoted = false;
  recordJournal(state, "activating", "prepared", {
    activationStartedAt: new Date().toISOString(),
    activationAttempt: Number(state.manifest.activationAttempt || 0) + 1,
  });
  try {
    putWorkerSecretImpl(newAdmin);
    recordJournal(state, "activating", "worker_secret_updated");
    compareAndSwapEnvSecret(state.manifest.serverEnv, "VOCAB_SYNC_TOKEN", oldSync, newSync);
    recordJournal(state, "activating", "server_env_updated");
    compareAndSwapEnvSecret(state.manifest.tunnelEnv, "CF_WORKER_ADMIN_TOKEN", oldAdmin, newAdmin);
    recordJournal(state, "activating", "tunnel_env_updated");
    restartServicesImpl();
    recordJournal(state, "activating", "services_restarted");
    await waitForCutoverImpl({
      workerUrl,
      port,
      currentSync: newSync,
      previousSync: oldSync,
      currentAdmin: newAdmin,
      previousAdmin: oldAdmin,
    });
    recordJournal(state, "activating", "cutover_verified");
    const promotionPlan = artifactPromotionPlan();
    recordJournal(state, "activating", "artifact_promotion_prepared", {
      artifactPromotion: promotionPlan,
    });
    promotedArtifactBackup = promoteArtifactsImpl(state, promotionPlan);
    artifactsPromoted = true;
    recordJournal(state, "activating", "artifacts_promoted", {
      previousArtifactDirectory: promotedArtifactBackup,
    });
    recordJournal(state, "activated", "completed", {
      activatedAt: new Date().toISOString(),
    });
    logger.log("Token rotation activated and verified: new credentials return 200; old credentials return 401.");
    logger.log("New artifacts were promoted to dist/sync-release. Install them on every retained device now.");
    logger.log("Keep the private bundle until all devices are verified, then move it to encrypted offline storage.");
  } catch (error) {
    logger.error("Activation failed; restoring the previous token values and Worker admin secret.");
    try {
      await restoreOldState(state, {
        putWorkerSecretImpl,
        restartServicesImpl,
        onPhase: (phase) => recordJournal(state, "activating", phase),
      });
      if (state.manifest.artifactPromotion) recoverInterruptedArtifactPromotionImpl(state);
      else if (artifactsPromoted) restorePromotedArtifactsImpl(promotedArtifactBackup);
      recordJournal(state, "artifacts_verified", "rolled_back", {
        activationRolledBackAt: new Date().toISOString(),
      });
    } catch (rollbackError) {
      recordJournal(state, "activating", "rollback_failed", {
        activationRollbackFailedAt: new Date().toISOString(),
      });
      throw new Error(`Activation failed (${error.message}); automatic rollback also failed (${rollbackError.message}).`);
    }
    throw error;
  }
}

export async function rollback(options, dependencies = {}) {
  if (options.confirm !== ROLLBACK_CONFIRMATION) {
    throw new Error(`Rollback requires --confirm ${ROLLBACK_CONFIRMATION}.`);
  }
  const state = loadBundle(options.bundle);
  if (!["activated", "rolling_back"].includes(state.manifest.status)) {
    throw new Error("This bundle does not record an activated rotation.");
  }
  const backupServer = parseEnv(fs.readFileSync(state.files.backupServer, "utf8"));
  const backupTunnel = parseEnv(fs.readFileSync(state.files.backupTunnel, "utf8"));
  const stagedServer = parseEnv(fs.readFileSync(state.files.stagedServer, "utf8"));
  const stagedTunnel = parseEnv(fs.readFileSync(state.files.stagedTunnel, "utf8"));
  const putWorkerSecretImpl = dependencies.putWorkerSecretImpl || putWorkerSecret;
  const restartServicesImpl = dependencies.restartServicesImpl || restartServices;
  const waitForCutoverImpl = dependencies.waitForCutoverImpl || waitForCutover;
  const rollbackArtifactsImpl = dependencies.rollbackArtifactsImpl || rollbackReleaseArtifacts;
  const logger = dependencies.logger || console;
  recordJournal(state, "rolling_back", "prepared", {
    rollbackStartedAt: state.manifest.rollbackStartedAt || new Date().toISOString(),
  });
  await restoreOldState(state, {
    putWorkerSecretImpl,
    restartServicesImpl,
    onPhase: (phase) => recordJournal(state, "rolling_back", phase),
  });
  await waitForCutoverImpl({
    workerUrl: backupTunnel.get("CF_WORKER_PUBLIC_URL") || state.manifest.workerUrl,
    port: backupServer.get("VOCAB_SYNC_PORT") || "8787",
    currentSync: validateSecret(backupServer.get("VOCAB_SYNC_TOKEN"), "backup sync token"),
    previousSync: validateSecret(stagedServer.get("VOCAB_SYNC_TOKEN"), "staged sync token"),
    currentAdmin: validateSecret(backupTunnel.get("CF_WORKER_ADMIN_TOKEN"), "backup admin token"),
    previousAdmin: validateSecret(stagedTunnel.get("CF_WORKER_ADMIN_TOKEN"), "staged admin token"),
  });
  recordJournal(state, "rolling_back", "cutover_verified");
  const oldArtifacts = state.manifest.previousArtifactDirectory;
  const promotion = state.manifest.artifactPromotion;
  const distRoot = path.join(repositoryRoot, "dist");
  const destination = promotion?.destination || path.join(distRoot, "sync-release");
  if (!isPathInside(destination, distRoot) ||
      (oldArtifacts && !isPathInside(oldArtifacts, distRoot))) {
    throw new Error("Artifact rollback journal contains a path outside the release directory.");
  }
  const failedNew = path.join(distRoot, `sync-release.rolled-back-${timestamp()}-${process.pid}`);
  const artifactResult = rollbackArtifactsImpl({
    destination,
    previousArtifactDirectory: oldArtifacts || null,
    quarantineDirectory: failedNew,
  });
  recordJournal(state, "rolled_back", "completed", {
    rolledBackAt: new Date().toISOString(),
    rolledBackArtifactDirectory: artifactResult?.quarantined ? failedNew : null,
  });
  if (artifactResult?.restoredPrevious) {
    logger.log("Previous server/tunnel credentials and release artifacts were restored.");
  } else {
    logger.log("Previous credentials were restored; the revoked-token release was removed from the deployable path.");
  }
  logger.log("Do not use rollback after distributing the new artifacts without reinstalling the previous artifacts.");
}

export async function main(argv = process.argv.slice(2)) {
  try {
    const options = parseArguments([...argv]);
    if (options.command === "plan" || options.command === "dry-run") plan(options);
    else if (options.command === "prepare") prepare(options);
    else if (options.command === "build") {
      if (!options.bundle) throw new Error("build requires --bundle <path>.");
      buildArtifacts(options);
    } else if (options.command === "activate") {
      if (!options.bundle) throw new Error("activate requires --bundle <path>.");
      await activate(options);
    } else if (options.command === "rollback") {
      if (!options.bundle) throw new Error("rollback requires --bundle <path>.");
      await rollback(options);
    } else {
      throw new Error(`Unknown command: ${options.command}`);
    }
    return 0;
  } catch (error) {
    console.error(`Token rotation failed: ${error.message}`);
    return 1;
  }
}

async function runDirect(argv = process.argv.slice(2)) {
  let options;
  try {
    options = parseArguments([...argv]);
  } catch {
    return main(argv);
  }
  const mutatingCommands = new Set(["prepare", "build", "activate", "rollback"]);
  if (!mutatingCommands.has(options.command) || process.env.VOCAB_RUNTIME_LOCK_WRAPPED === "1") {
    return main(argv);
  }
  const wrapper = path.join(repositoryRoot, "tools", "with_runtime_lock.mjs");
  const result = spawnSync(
    process.execPath,
    [
      wrapper,
      "--runtime-root", options.runtimeRoot,
      "--label", `token rotation ${options.command}`,
      "--",
      process.execPath,
      fileURLToPath(import.meta.url),
      ...argv,
    ],
    { stdio: "inherit", env: process.env },
  );
  if (result.error) {
    console.error(`Token rotation failed: ${result.error.message}`);
    return 1;
  }
  return result.status ?? 1;
}

if (isDirectExecution(import.meta.url)) process.exitCode = await runDirect();
