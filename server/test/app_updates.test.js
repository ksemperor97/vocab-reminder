import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { androidUpdateDir, publishAndroidRelease, readAndroidRelease } from "../src/app_updates.js";
import { openDatabase } from "../src/db.js";
import { createSyncServer } from "../src/http.js";

const token = "test-token";
const publishTool = fileURLToPath(new URL("../tools/publish_android_update.mjs", import.meta.url));

test("serves nothing until an APK is published, then its metadata and bytes", async (t) => {
  const app = await startTestServer(t);

  const empty = await fetch(app.url("/app/android/latest"), auth());
  assert.equal(empty.status, 404);
  assert.equal((await fetch(app.url("/app/android/apk"), auth())).status, 404);

  const apk = fakeApk(app.root, "v6", 70_000);
  await publishAndroidRelease({ dir: app.updates, apkPath: apk, versionCode: 6, versionName: "0.4.2" });

  const latest = await fetch(app.url("/app/android/latest"), auth());
  assert.equal(latest.status, 200);
  const body = await latest.json();
  assert.equal(body.versionCode, 6);
  assert.equal(body.versionName, "0.4.2");
  assert.equal(body.sha256, sha256(apk));
  assert.equal(body.size, 70_000);
  assert.equal(body.file, undefined, "the on-disk file name is not part of the API");

  const download = await fetch(app.url("/app/android/apk"), auth());
  assert.equal(download.status, 200);
  assert.equal(download.headers.get("content-type"), "application/vnd.android.package-archive");
  assert.equal(download.headers.get("content-length"), "70000");
  assert.equal(download.headers.get("x-apk-sha256"), body.sha256);
  const bytes = Buffer.from(await download.arrayBuffer());
  assert.equal(crypto.createHash("sha256").update(bytes).digest("hex"), body.sha256);
});

test("requires the bearer token for both update endpoints", async (t) => {
  const app = await startTestServer(t);
  await publishAndroidRelease({ dir: app.updates, apkPath: fakeApk(app.root, "v6"), versionCode: 6, versionName: "0.4.2" });
  for (const route of ["/app/android/latest", "/app/android/apk"]) {
    assert.equal((await fetch(app.url(route))).status, 401, route);
    assert.equal((await fetch(app.url(route), auth("wrong"))).status, 401, route);
  }
});

test("a publish replaces the release atomically and keeps exactly one previous APK", async (t) => {
  const root = tempRoot(t);
  const dir = path.join(root, "android");
  const publish = (version) => publishAndroidRelease({
    dir,
    apkPath: fakeApk(root, `v${version}`),
    versionCode: version,
    versionName: `0.4.${version}`
  });

  const first = await publish(6);
  const second = await publish(7);
  assert.equal(readAndroidRelease(dir).versionCode, 7);
  assert.equal(readAndroidRelease(dir, "previous.json").versionCode, 6);
  assert.deepEqual(apks(dir), [first.file, second.file].sort());

  const third = await publish(8);
  assert.equal(readAndroidRelease(dir, "previous.json").versionCode, 7);
  assert.deepEqual(apks(dir), [second.file, third.file].sort(), "the oldest APK is pruned");
  assert.deepEqual(fs.readdirSync(dir).filter((name) => name.includes("tmp") || name.startsWith(".")), []);
});

test("refuses a lower versionCode unless asked, and leaves the release untouched", async (t) => {
  const root = tempRoot(t);
  const dir = path.join(root, "android");
  await publishAndroidRelease({ dir, apkPath: fakeApk(root, "v7"), versionCode: 7, versionName: "0.4.7" });

  await assert.rejects(
    publishAndroidRelease({ dir, apkPath: fakeApk(root, "v5"), versionCode: 5, versionName: "0.4.5" }),
    /Refusing to publish versionCode 5 over 7/
  );
  assert.equal(readAndroidRelease(dir).versionCode, 7);
  assert.equal(apks(dir).length, 1);

  await publishAndroidRelease({
    dir, apkPath: fakeApk(root, "v5"), versionCode: 5, versionName: "0.4.5", allowDowngrade: true
  });
  assert.equal(readAndroidRelease(dir).versionCode, 5);
});

test("a download that started before a publish finishes with the release it started on", async (t) => {
  const app = await startTestServer(t);
  const oldApk = fakeApk(app.root, "old", 4 * 1024 * 1024);
  await publishAndroidRelease({ dir: app.updates, apkPath: oldApk, versionCode: 6, versionName: "0.4.6" });

  const response = await fetch(app.url("/app/android/apk"), auth());
  const reader = response.body.getReader();
  const chunks = [Buffer.from((await reader.read()).value)];
  for (const version of [7, 8]) {
    await publishAndroidRelease({
      dir: app.updates, apkPath: fakeApk(app.root, `new${version}`, 1024), versionCode: version, versionName: `0.4.${version}`
    });
  }
  for (let next = await reader.read(); !next.done; next = await reader.read()) chunks.push(Buffer.from(next.value));
  assert.equal(crypto.createHash("sha256").update(Buffer.concat(chunks)).digest("hex"), sha256(oldApk));
});

test("the publish tool writes into the runtime root's app-updates folder", (t) => {
  const root = tempRoot(t);
  const apk = fakeApk(root, "cli");
  const result = spawnSync(process.execPath, [
    publishTool, "--apk", apk, "--version-code", "9", "--version-name", "0.5.0", "--runtime-root", root
  ], { encoding: "utf8", env: cleanEnv() });
  assert.equal(result.status, 0, result.stderr);
  const release = readAndroidRelease(androidUpdateDir(path.join(root, "app-updates")));
  assert.equal(release.versionCode, 9);
  assert.equal(release.sha256, sha256(apk));

  const missing = spawnSync(process.execPath, [publishTool, "--apk", apk], { encoding: "utf8" });
  assert.notEqual(missing.status, 0);
  assert.match(missing.stderr, /Usage/);
});

function cleanEnv() {
  const env = { ...process.env };
  for (const key of ["VOCAB_SYNC_APP_UPDATE_DIR", "VOCAB_SYNC_RUNTIME_ROOT", "VOCAB_SYNC_ENV_PATH"]) delete env[key];
  return env;
}

function auth(value = token) {
  return { headers: { Authorization: `Bearer ${value}` } };
}

function tempRoot(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "vocab-app-updates-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  return root;
}

function fakeApk(root, label, size = 2048) {
  const file = path.join(root, `${label}-${crypto.randomUUID()}.apk`);
  const bytes = Buffer.alloc(size);
  crypto.randomFillSync(bytes);
  fs.writeFileSync(file, bytes);
  return file;
}

function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

function apks(dir) {
  return fs.readdirSync(dir).filter((name) => name.endsWith(".apk")).sort();
}

async function startTestServer(t) {
  const root = tempRoot(t);
  const dbPath = path.join(root, "vocab-sync.sqlite");
  const db = openDatabase(dbPath);
  const appUpdateDir = path.join(root, "app-updates");
  const { server } = createSyncServer({ recommendationCatalog: null, db, dbPath, token, appUpdateDir });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(async () => {
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
    db.close();
  });
  const { port } = server.address();
  return {
    root,
    updates: androidUpdateDir(appUpdateDir),
    url: (pathname) => `http://127.0.0.1:${port}${pathname}`
  };
}
