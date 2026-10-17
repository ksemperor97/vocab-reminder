import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { checkSecretBoundaries } from "./check_secret_boundaries.mjs";
import { activate, prepare, rollback, rollbackReleaseArtifacts } from "./rotate_sync_tokens.mjs";
import {
  acquireRuntimeLock,
  parseEnv,
  repositoryRoot,
  runtimeLockDirectory,
  secretDigest,
} from "./security_helpers.mjs";
import { scanSecretLeaks } from "./scan_secret_leaks.mjs";

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "vocab-security-controls-"));
  fs.chmodSync(root, 0o700);
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const runtimeRoot = path.join(root, "runtime");
  const artifactDir = path.join(root, "artifacts");
  fs.mkdirSync(runtimeRoot, { mode: 0o700 });
  fs.mkdirSync(artifactDir, { mode: 0o700 });
  const serverEnv = path.join(runtimeRoot, ".env");
  const tunnelEnv = path.join(runtimeRoot, "tunnel.env");
  const syncToken = "a".repeat(64);
  const adminToken = "b".repeat(64);
  fs.writeFileSync(
    serverEnv,
    `VOCAB_SYNC_TOKEN=${syncToken}\nVOCAB_SYNC_HOST=127.0.0.1\nVOCAB_SYNC_PORT=8787\n`,
    { mode: 0o600 },
  );
  fs.writeFileSync(
    tunnelEnv,
    `TUNNEL_PROVIDER=cloudflare_quick_worker\nCF_WORKER_PUBLIC_URL=https://stable.example.test\nCF_WORKER_ADMIN_TOKEN=${adminToken}\n`,
    { mode: 0o600 },
  );
  return { root, runtimeRoot, artifactDir, serverEnv, tunnelEnv, syncToken, adminToken };
}

function stageFakeArtifacts(bundle) {
  const stagedServer = parseEnv(fs.readFileSync(path.join(bundle, "staged", "server.env"), "utf8"));
  const artifacts = path.join(bundle, "artifacts");
  const macBinary = path.join(artifacts, "VocabReminder.app", "Contents", "MacOS", "VocabReminderMac");
  const macInfoPlist = path.join(artifacts, "VocabReminder.app", "Contents", "Info.plist");
  const macArchive = path.join(artifacts, "VocabReminder-macOS.zip");
  const releaseManifest = path.join(artifacts, "release-manifest.json");
  const checksums = path.join(artifacts, "SHA256SUMS");
  const apk = path.join(artifacts, "VocabReminderAndroid-release.apk");
  fs.mkdirSync(path.dirname(macBinary), { recursive: true, mode: 0o700 });
  fs.writeFileSync(apk, "fake-signed-apk", { mode: 0o600 });
  fs.writeFileSync(macBinary, "fake-packaged-mac-binary", { mode: 0o700 });
  fs.writeFileSync(macInfoPlist, "fake-info-plist-with-embedded-sync-token", { mode: 0o600 });
  fs.writeFileSync(macArchive, "fake-mac-release-archive", { mode: 0o600 });
  fs.writeFileSync(releaseManifest, "fake-release-manifest", { mode: 0o600 });
  fs.writeFileSync(checksums, "fake-checksum-manifest", { mode: 0o600 });
  const digest = (file) => crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
  fs.writeFileSync(
    path.join(bundle, "artifacts-verified.json"),
    `${JSON.stringify({
      schemaVersion: 1,
      tokenDigest: secretDigest(stagedServer.get("VOCAB_SYNC_TOKEN")),
      artifacts: {
        androidSha256: digest(apk),
        macBinarySha256: digest(macBinary),
        macInfoPlistSha256: digest(macInfoPlist),
        macArchiveSha256: digest(macArchive),
        releaseManifestSha256: digest(releaseManifest),
        checksumFileSha256: digest(checksums),
      },
    })}\n`,
    { mode: 0o600 },
  );
  const manifestFile = path.join(bundle, "manifest.json");
  const manifest = JSON.parse(fs.readFileSync(manifestFile, "utf8"));
  manifest.status = "artifacts_verified";
  manifest.artifactsVerifiedAt = new Date().toISOString();
  fs.writeFileSync(manifestFile, `${JSON.stringify(manifest, null, 2)}\n`, { mode: 0o600 });
}

test("boundary check requires distinct tokens and excludes admin credentials from artifacts", (t) => {
  const f = fixture(t);
  const options = { ...f, allowMissing: false };
  const passed = checkSecretBoundaries(options);
  assert.equal(passed.skipped, false);

  fs.writeFileSync(f.tunnelEnv, `CF_WORKER_ADMIN_TOKEN=${f.syncToken}\n`, { mode: 0o600 });
  assert.throws(() => checkSecretBoundaries(options), /must be different secrets/);

  fs.writeFileSync(f.tunnelEnv, `CF_WORKER_ADMIN_TOKEN=${f.adminToken}\n`, { mode: 0o600 });
  fs.writeFileSync(path.join(f.artifactDir, "release.bin"), Buffer.from(`prefix-${f.adminToken}-suffix`));
  assert.throws(() => checkSecretBoundaries(options), /embedded in release artifacts/);
});

test("leak scanner reports paths and categories without requiring source-line output", (t) => {
  const f = fixture(t);
  const generated = path.join(f.root, "generated");
  fs.mkdirSync(generated, { mode: 0o700 });
  const options = {
    runtimeRoot: f.runtimeRoot,
    serverEnv: f.serverEnv,
    tunnelEnv: f.tunnelEnv,
    paths: [generated],
    defaultPaths: false,
    allowNoSecrets: false,
  };
  assert.equal(scanSecretLeaks(options).findings.size, 0);
  const leakedLog = path.join(generated, "build.log");
  fs.writeFileSync(leakedLog, `redacted-command-value=${f.syncToken}\n`, { mode: 0o600 });
  const result = scanSecretLeaks(options);
  assert.deepEqual(result.findings.get(fs.realpathSync(leakedLog)), ["known-sync-secret"]);
});

test("prepare creates private atomic backups and staged tokens without changing active env", (t) => {
  const f = fixture(t);
  const serverBefore = fs.readFileSync(f.serverEnv, "utf8");
  const tunnelBefore = fs.readFileSync(f.tunnelEnv, "utf8");
  const bundle = path.join(f.root, "rotation-bundle");
  const prepared = prepare({ ...f, bundle });
  assert.equal(prepared, bundle);
  assert.equal(fs.readFileSync(f.serverEnv, "utf8"), serverBefore);
  assert.equal(fs.readFileSync(f.tunnelEnv, "utf8"), tunnelBefore);
  assert.equal(fs.statSync(bundle).mode & 0o777, 0o700);

  const stagedServerFile = path.join(bundle, "staged", "server.env");
  const stagedTunnelFile = path.join(bundle, "staged", "tunnel.env");
  assert.equal(fs.statSync(stagedServerFile).mode & 0o777, 0o600);
  assert.equal(fs.statSync(stagedTunnelFile).mode & 0o777, 0o600);
  const stagedServer = parseEnv(fs.readFileSync(stagedServerFile, "utf8"));
  const stagedTunnel = parseEnv(fs.readFileSync(stagedTunnelFile, "utf8"));
  assert.notEqual(stagedServer.get("VOCAB_SYNC_TOKEN"), f.syncToken);
  assert.notEqual(stagedTunnel.get("CF_WORKER_ADMIN_TOKEN"), f.adminToken);
  assert.notEqual(stagedServer.get("VOCAB_SYNC_TOKEN"), stagedTunnel.get("CF_WORKER_ADMIN_TOKEN"));

  const manifestText = fs.readFileSync(path.join(bundle, "manifest.json"), "utf8");
  const readmeText = fs.readFileSync(path.join(bundle, "README.md"), "utf8");
  assert.equal(manifestText.includes(f.syncToken), false);
  assert.equal(manifestText.includes(f.adminToken), false);
  assert.equal(readmeText.includes(f.syncToken), false);
  assert.equal(readmeText.includes(f.adminToken), false);
  const manifest = JSON.parse(manifestText);
  assert.equal(manifest.tokenDigests.oldSync, secretDigest(f.syncToken));
  assert.equal(manifest.status, "prepared");
});

test("activation uses staged tokens and records success only after cutover verification", async (t) => {
  const f = fixture(t);
  const bundle = path.join(f.root, "successful-rotation");
  prepare({ ...f, bundle });
  stageFakeArtifacts(bundle);
  const workerUpdates = [];
  let restartCount = 0;
  let cutoverChecked = false;
  await activate(
    { bundle, confirm: "ROTATE-SINGLE-USER-TOKENS" },
    {
      putWorkerSecretImpl: (token) => {
        const journal = JSON.parse(fs.readFileSync(path.join(bundle, "manifest.json"), "utf8"));
        assert.equal(journal.status, "activating");
        assert.equal(journal.activationPhase, "prepared");
        workerUpdates.push(token);
      },
      restartServicesImpl: () => { restartCount += 1; },
      waitForCutoverImpl: async (values) => {
        cutoverChecked = true;
        assert.notEqual(values.currentSync, values.previousSync);
        assert.notEqual(values.currentAdmin, values.previousAdmin);
      },
      promoteArtifactsImpl: () => null,
      logger: { log() {}, error() {} },
    },
  );
  assert.equal(workerUpdates.length, 1);
  assert.equal(restartCount, 1);
  assert.equal(cutoverChecked, true);
  assert.notEqual(parseEnv(fs.readFileSync(f.serverEnv, "utf8")).get("VOCAB_SYNC_TOKEN"), f.syncToken);
  const manifest = JSON.parse(fs.readFileSync(path.join(bundle, "manifest.json"), "utf8"));
  assert.equal(manifest.status, "activated");
  assert.equal(manifest.activationPhase, "completed");
});

test("failed activation restores both env backups and the previous Worker secret", async (t) => {
  const f = fixture(t);
  const bundle = path.join(f.root, "failed-rotation");
  prepare({ ...f, bundle });
  stageFakeArtifacts(bundle);
  const workerUpdates = [];
  let restartCount = 0;
  await assert.rejects(
    activate(
      { bundle, confirm: "ROTATE-SINGLE-USER-TOKENS" },
      {
        putWorkerSecretImpl: (token) => workerUpdates.push(token),
        restartServicesImpl: () => { restartCount += 1; },
        waitForCutoverImpl: async () => { throw new Error("simulated cutover failure"); },
        promoteArtifactsImpl: () => null,
        logger: { log() {}, error() {} },
      },
    ),
    /simulated cutover failure/,
  );
  assert.equal(workerUpdates.length, 2);
  assert.notEqual(workerUpdates[0], workerUpdates[1]);
  assert.equal(workerUpdates[1], f.adminToken);
  assert.equal(restartCount, 2);
  assert.equal(parseEnv(fs.readFileSync(f.serverEnv, "utf8")).get("VOCAB_SYNC_TOKEN"), f.syncToken);
  assert.equal(parseEnv(fs.readFileSync(f.tunnelEnv, "utf8")).get("CF_WORKER_ADMIN_TOKEN"), f.adminToken);
  const manifest = JSON.parse(fs.readFileSync(path.join(bundle, "manifest.json"), "utf8"));
  assert.equal(manifest.status, "artifacts_verified");
  assert.equal(manifest.activationPhase, "rolled_back");
});

test("interrupted activation is durably detected and token-only recovery preserves runtime edits", async (t) => {
  const f = fixture(t);
  const bundle = path.join(f.root, "interrupted-rotation");
  prepare({ ...f, bundle });
  stageFakeArtifacts(bundle);
  const stagedServer = parseEnv(fs.readFileSync(path.join(bundle, "staged", "server.env"), "utf8"));
  const stagedTunnel = parseEnv(fs.readFileSync(path.join(bundle, "staged", "tunnel.env"), "utf8"));
  fs.writeFileSync(
    f.serverEnv,
    `VOCAB_SYNC_TOKEN=${stagedServer.get("VOCAB_SYNC_TOKEN")}\nVOCAB_SYNC_HOST=127.0.0.1\nVOCAB_SYNC_PORT=9999\n`,
    { mode: 0o600 },
  );
  fs.writeFileSync(
    f.tunnelEnv,
    `TUNNEL_PROVIDER=cloudflare_quick_worker\nCF_WORKER_PUBLIC_URL=https://stable.example.test\nCF_WORKER_ADMIN_TOKEN=${stagedTunnel.get("CF_WORKER_ADMIN_TOKEN")}\nTUNNEL_LOG_LEVEL=debug\n`,
    { mode: 0o600 },
  );
  const manifestFile = path.join(bundle, "manifest.json");
  const manifest = JSON.parse(fs.readFileSync(manifestFile, "utf8"));
  manifest.status = "activating";
  manifest.activationPhase = "artifacts_promoted";
  manifest.artifactPromotion = {
    destination: "/not-used-in-injected-test/destination",
    temporary: "/not-used-in-injected-test/temporary",
    backup: "/not-used-in-injected-test/backup",
    hadPrevious: true,
  };
  fs.writeFileSync(manifestFile, `${JSON.stringify(manifest, null, 2)}\n`, { mode: 0o600 });

  const workerUpdates = [];
  let restartCount = 0;
  let artifactRecoveryCount = 0;
  await assert.rejects(
    activate(
      { bundle, confirm: "ROTATE-SINGLE-USER-TOKENS" },
      {
        putWorkerSecretImpl: (token) => workerUpdates.push(token),
        restartServicesImpl: () => { restartCount += 1; },
        recoverInterruptedArtifactPromotionImpl: () => { artifactRecoveryCount += 1; },
        logger: { log() {}, error() {} },
      },
    ),
    /interrupted activation was recovered/i,
  );
  assert.deepEqual(workerUpdates, [f.adminToken]);
  assert.equal(restartCount, 1);
  assert.equal(artifactRecoveryCount, 1);
  const recoveredServer = parseEnv(fs.readFileSync(f.serverEnv, "utf8"));
  const recoveredTunnel = parseEnv(fs.readFileSync(f.tunnelEnv, "utf8"));
  assert.equal(recoveredServer.get("VOCAB_SYNC_TOKEN"), f.syncToken);
  assert.equal(recoveredServer.get("VOCAB_SYNC_PORT"), "9999");
  assert.equal(recoveredTunnel.get("CF_WORKER_ADMIN_TOKEN"), f.adminToken);
  assert.equal(recoveredTunnel.get("TUNNEL_LOG_LEVEL"), "debug");
  const recoveredManifest = JSON.parse(fs.readFileSync(manifestFile, "utf8"));
  assert.equal(recoveredManifest.status, "artifacts_verified");
  assert.equal(recoveredManifest.activationPhase, "interrupted_activation_recovered");
});

test("explicit rollback restores only token keys and preserves later runtime tuning", async (t) => {
  const f = fixture(t);
  const bundle = path.join(f.root, "rollback-preserves-settings");
  prepare({ ...f, bundle });
  stageFakeArtifacts(bundle);
  await activate(
    { bundle, confirm: "ROTATE-SINGLE-USER-TOKENS" },
    {
      putWorkerSecretImpl: () => {},
      restartServicesImpl: () => {},
      waitForCutoverImpl: async () => {},
      promoteArtifactsImpl: () => null,
      logger: { log() {}, error() {} },
    },
  );
  fs.writeFileSync(f.serverEnv, fs.readFileSync(f.serverEnv, "utf8").replace("VOCAB_SYNC_PORT=8787", "VOCAB_SYNC_PORT=9999"));
  fs.appendFileSync(f.tunnelEnv, "TUNNEL_LOG_LEVEL=debug\n");
  let artifactRollbackRequest;
  await rollback(
    { bundle, confirm: "ROLLBACK-SINGLE-USER-TOKENS" },
    {
      putWorkerSecretImpl: () => {},
      restartServicesImpl: () => {},
      waitForCutoverImpl: async () => {},
      rollbackArtifactsImpl: (request) => {
        artifactRollbackRequest = request;
        return { restoredPrevious: false, quarantined: true };
      },
      logger: { log() {}, error() {} },
    },
  );
  const restoredServer = parseEnv(fs.readFileSync(f.serverEnv, "utf8"));
  const restoredTunnel = parseEnv(fs.readFileSync(f.tunnelEnv, "utf8"));
  assert.equal(restoredServer.get("VOCAB_SYNC_TOKEN"), f.syncToken);
  assert.equal(restoredServer.get("VOCAB_SYNC_PORT"), "9999");
  assert.equal(restoredTunnel.get("CF_WORKER_ADMIN_TOKEN"), f.adminToken);
  assert.equal(restoredTunnel.get("TUNNEL_LOG_LEVEL"), "debug");
  assert.equal(artifactRollbackRequest.previousArtifactDirectory, null);
  assert.match(artifactRollbackRequest.destination, /dist[/\\]sync-release$/);
});

test("rollback quarantines a first release when no previous artifacts exist", (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "vocab-first-release-rollback-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const destination = path.join(root, "sync-release");
  const quarantineDirectory = path.join(root, "sync-release.rolled-back-test");
  fs.mkdirSync(destination);
  fs.writeFileSync(path.join(destination, "revoked-token-artifact"), "new");

  const result = rollbackReleaseArtifacts({
    destination,
    previousArtifactDirectory: null,
    quarantineDirectory,
  });

  assert.deepEqual(result, { restoredPrevious: false, quarantined: true });
  assert.equal(fs.existsSync(destination), false);
  assert.equal(fs.readFileSync(path.join(quarantineDirectory, "revoked-token-artifact"), "utf8"), "new");
});

test("runtime mutation lock rejects overlap, supports authenticated nesting, and hides its capability", (t) => {
  const runtimeRoot = fs.mkdtempSync(path.join(os.tmpdir(), "vocab-runtime-lock-"));
  t.after(() => fs.rmSync(runtimeRoot, { recursive: true, force: true }));
  const lease = acquireRuntimeLock({ runtimeRoot, label: "first", inheritedToken: "" });
  const ownerText = fs.readFileSync(path.join(lease.directory, "owner.json"), "utf8");
  assert.equal(ownerText.includes(lease.token), false);
  assert.throws(
    () => acquireRuntimeLock({ runtimeRoot, label: "overlap", inheritedToken: "" }),
    /held by live process/i,
  );
  assert.throws(
    () => acquireRuntimeLock({ runtimeRoot, inheritedToken: "incorrect-capability" }),
    /capability does not match/i,
  );
  const nested = acquireRuntimeLock({ runtimeRoot, inheritedToken: lease.token });
  assert.equal(nested.owned, false);
  nested.release();
  assert.equal(fs.existsSync(lease.directory), true);
  lease.release();
  const next = acquireRuntimeLock({ runtimeRoot, label: "next", inheritedToken: "" });
  next.release();
});

test("runtime mutation lock reclaims a dead recorded owner", (t) => {
  const runtimeRoot = fs.mkdtempSync(path.join(os.tmpdir(), "vocab-stale-runtime-lock-"));
  t.after(() => fs.rmSync(runtimeRoot, { recursive: true, force: true }));
  const lockDirectory = runtimeLockDirectory(runtimeRoot);
  fs.mkdirSync(lockDirectory, { recursive: true, mode: 0o700 });
  fs.writeFileSync(
    path.join(lockDirectory, "owner.json"),
    `${JSON.stringify({
      schemaVersion: 1,
      pid: 2_147_483_647,
      label: "dead owner",
      createdAt: new Date(0).toISOString(),
      tokenSha256: secretDigest("dead-capability"),
    })}\n`,
    { mode: 0o600 },
  );
  const lease = acquireRuntimeLock({ runtimeRoot, inheritedToken: "" });
  assert.equal(lease.owned, true);
  lease.release();
});

test("activation rejects release metadata or archive tampering after verification", async (t) => {
  const f = fixture(t);
  const bundle = path.join(f.root, "tampered-artifacts");
  prepare({ ...f, bundle });
  stageFakeArtifacts(bundle);
  fs.appendFileSync(
    path.join(bundle, "artifacts", "VocabReminder.app", "Contents", "Info.plist"),
    "tampered-after-verification",
  );
  let workerUpdates = 0;
  await assert.rejects(
    activate(
      { bundle, confirm: "ROTATE-SINGLE-USER-TOKENS" },
      {
        putWorkerSecretImpl: () => { workerUpdates += 1; },
        restartServicesImpl: () => {},
        waitForCutoverImpl: async () => {},
        promoteArtifactsImpl: () => null,
        logger: { log() {}, error() {} },
      },
    ),
    /artifact verification marker does not match/i,
  );
  assert.equal(workerUpdates, 0);
});

test("release build passes the sync token through the child environment, never a Gradle argument", () => {
  const script = fs.readFileSync(path.join(repositoryRoot, "tools", "build_sync_release.sh"), "utf8");
  assert.equal(script.includes("-PVOCAB_SYNC_DEFAULT_TOKEN"), false);
  assert.match(script, /VOCAB_SYNC_DEFAULT_TOKEN="\$SYNC_TOKEN"/);
  assert.match(script, /set \+x/);
  assert.match(script, /verify_android_release_signing\.sh/);
  assert.match(script, /signerCertificateSha256/);
  assert.match(script, /tokenDigestAlgorithm: "sha256"/);
  assert.match(script, /tokenSha256/);
  assert.match(script, /minimumSystemVersion/);
  const checker = fs.readFileSync(path.join(repositoryRoot, "tools", "check_sync_release_artifacts.sh"), "utf8");
  assert.match(checker, /verify_android_release_signing\.sh/);
  assert.match(checker, /signerCertificateSha256/);
  assert.match(checker, /SYNC_TOKEN_SHA256/);
  assert.match(checker, /MAC_BINARY_MINIMUM_SYSTEM_VERSION/);
  assert.equal(checker.includes("SKIP: Android token check"), false);
  assert.equal(checker.includes("SKIP: Mac token check"), false);

  const signerPin = fs.readFileSync(
    path.join(repositoryRoot, "android", "VocabReminderAndroid", "release-signer.sha256"),
    "utf8",
  ).trim();
  assert.match(signerPin, /^[a-f0-9]{64}$/u);
  for (const relative of ["tools/setup_android_release_signing.sh", "tools/verify_android_release_signing.sh"]) {
    const signingScript = fs.readFileSync(path.join(repositoryRoot, relative), "utf8");
    assert.match(signingScript, /release-signer\.sha256/);
    assert.match(signingScript, /PINNED_SIGNER_SHA256/);
  }
});

test("Android self-update is on for release builds only", () => {
  const gradle = fs.readFileSync(
    path.join(repositoryRoot, "android", "VocabReminderAndroid", "app", "build.gradle.kts"),
    "utf8",
  );
  const block = (name) => {
    const start = gradle.indexOf(name);
    assert.notEqual(start, -1, name);
    return gradle.slice(start, gradle.indexOf("\n        }", start));
  };
  assert.match(gradle, /defaultConfig \{[^}]*SELF_UPDATE_ENABLED", "false"/s);
  assert.match(block('getByName("release")'), /SELF_UPDATE_ENABLED", "true"/);
  assert.match(block('create("deviceQa")'), /SELF_UPDATE_ENABLED", "false"/);
  assert.match(block('getByName("debug")'), /SELF_UPDATE_ENABLED", e2eSelfUpdate\.toString\(\)/);
  assert.match(gradle, /onVariants\(selector\(\)\.withBuildType\("debug"\)\)/);

  const release = fs.readFileSync(path.join(repositoryRoot, "tools", "build_sync_release.sh"), "utf8");
  assert.match(release, /publish_android_update\.mjs/);
  assert.match(release, /--signer-sha256 "\$ANDROID_SIGNER_SHA256"/);
});

test("Cloudflare mutations require the repository-pinned Wrangler binary", () => {
  const packageJson = JSON.parse(fs.readFileSync(path.join(repositoryRoot, "package.json"), "utf8"));
  assert.equal(packageJson.devDependencies.wrangler, "4.110.0");
  for (const relative of ["tools/setup_cloudflare_worker.sh", "tools/rotate_sync_tokens.mjs"]) {
    const source = fs.readFileSync(path.join(repositoryRoot, relative), "utf8");
    assert.equal(source.includes("npx --yes wrangler"), false);
    assert.match(source, /node_modules["/),.\s]+\.bin["/),.\s]+wrangler/);
  }
});

test("runtime and release shell scripts retain crash-safe state boundaries", () => {
  const setup = fs.readFileSync(path.join(repositoryRoot, "tools", "setup_cloudflare_worker.sh"), "utf8");
  assert.match(setup, /writeFileAtomic\(file, text, 0o600\)/);
  assert.match(setup, /Refusing to rotate an existing Worker admin token in setup/);
  assert.equal(setup.includes('cat > "$TUNNEL_ENV"'), false);

  const release = fs.readFileSync(path.join(repositoryRoot, "tools", "build_sync_release.sh"), "utf8");
  assert.match(release, /Recovered the previous release after an interrupted artifact swap/);
  assert.match(release, /fsync_release_parent/);
  assert.match(release, /with_runtime_lock\.mjs/);

  const macSetup = fs.readFileSync(path.join(repositoryRoot, "tools", "setup_mac_mini_sync.sh"), "utf8");
  const rotation = fs.readFileSync(path.join(repositoryRoot, "tools", "rotate_sync_tokens.mjs"), "utf8");
  assert.match(setup, /with_runtime_lock\.mjs/);
  assert.match(macSetup, /with_runtime_lock\.mjs/);
  assert.match(rotation, /with_runtime_lock\.mjs/);
  assert.match(rotation, /mutatingCommands/);

  const checker = fs.readFileSync(path.join(repositoryRoot, "tools", "check_sync_release_artifacts.sh"), "utf8");
  assert.match(checker, /ditto -x -k "\$MAC_ARCHIVE"/);
  assert.match(checker, /diff -qr "\$APP" "\$ARCHIVE_APP"/);
  assert.match(checker, /codesign --verify --deep --strict --verbose=2 "\$ARCHIVE_APP"/);
});

test("Android instrumentation is structurally isolated from the production application", () => {
  const gradle = fs.readFileSync(
    path.join(repositoryRoot, "android", "VocabReminderAndroid", "app", "build.gradle.kts"),
    "utf8",
  );
  const instrumentation = fs.readFileSync(
    path.join(
      repositoryRoot,
      "android",
      "VocabReminderAndroid",
      "app",
      "src",
      "androidTest",
      "java",
      "com",
      "vocabreminder",
      "android",
      "M3VerticalSliceTest.kt",
    ),
    "utf8",
  );
  const runner = fs.readFileSync(path.join(repositoryRoot, "tools", "run_android_instrumentation.sh"), "utf8");
  const qc = fs.readFileSync(path.join(repositoryRoot, "tools", "qc_all.sh"), "utf8");

  assert.match(gradle, /testBuildType = "deviceQa"/);
  assert.match(gradle, /applicationIdSuffix = "\.qa"/);
  assert.match(gradle, /DEFAULT_SYNC_SERVER_URL", "\\"\\""/);
  assert.match(gradle, /DEFAULT_SYNC_BEARER_TOKEN", "\\"\\""/);
  assert.match(instrumentation, /targetContext\.packageName == "com\.vocabreminder\.android\.qa"/);
  assert.ok(instrumentation.indexOf("targetContext.packageName") < instrumentation.indexOf("clearAll()"));
  assert.equal(instrumentation.includes("cmd notification cancel-all"), false);
  assert.equal(instrumentation.includes("dumpsys notification"), false);
  assert.match(instrumentation, /activeNotifications/);
  assert.match(runner, /instrumentation:\$RUNNER \(target=\$QA_PACKAGE\)/);
  assert.match(runner, /pm clear "\$QA_PACKAGE"/);
  assert.match(runner, /pm grant "\$QA_PACKAGE" android\.permission\.POST_NOTIFICATIONS/);
  assert.equal(runner.includes("pm clear com.vocabreminder.android\n"), false);
  assert.match(qc, /run_android_instrumentation\.sh/);
  assert.match(qc, /QC_REQUIRE_ANDROID_DEVICE/);
  assert.equal(qc.includes("connectedDebugAndroidTest"), false);
});
