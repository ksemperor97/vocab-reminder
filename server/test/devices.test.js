import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { openDatabase } from "../src/db.js";
import { createDeviceRegistry, LEGACY_DEVICE_ID } from "../src/devices.js";
import { createSyncServer } from "../src/http.js";

const legacyToken = "legacy-token-for-device-tests-0123456789";

test("a paired device gets its own token; codes work once and expire", (t) => {
  let clock = Date.parse("2026-10-18T00:00:00Z");
  const registry = createDeviceRegistry(database(t), { legacyToken, now: () => clock });
  assert.equal(registry.authenticate(legacyToken), LEGACY_DEVICE_ID);
  assert.equal(registry.authenticate("nope"), null);

  const { code } = registry.createCode(LEGACY_DEVICE_ID);
  assert.match(code, /^\d{6}$/u);
  const { deviceId, token } = registry.claim({ code, deviceName: "Galaxy Z Fold7", platform: "Android" });
  assert.equal(registry.authenticate(token), deviceId);
  assert.throws(() => registry.claim({ code, deviceName: "again", platform: "android" }), /wrong or has expired/u);

  const late = registry.createCode(deviceId);
  clock += 11 * 60 * 1000;
  assert.throws(() => registry.claim({ code: late.code, deviceName: "late", platform: "mac" }), /wrong or has expired/u);

  const [device] = registry.list();
  assert.equal(device.name, "Galaxy Z Fold7");
  assert.equal(device.platform, "android");
  registry.revoke(deviceId);
  assert.equal(registry.authenticate(token), null);
  assert.throws(() => registry.revoke(LEGACY_DEVICE_ID), /rotating/u);
});

test("ten wrong codes cancel every open code and lock claims for a while", (t) => {
  let clock = Date.parse("2026-10-18T00:00:00Z");
  const registry = createDeviceRegistry(database(t), { legacyToken, now: () => clock });
  const { code } = registry.createCode(LEGACY_DEVICE_ID);
  const wrong = code === "000000" ? "111111" : "000000";
  for (let attempt = 0; attempt < 10; attempt += 1) {
    assert.throws(() => registry.claim({ code: wrong, deviceName: "x", platform: "x" }));
  }
  assert.throws(() => registry.claim({ code, deviceName: "x", platform: "x" }), (error) => error.status === 429);
  clock += 11 * 60 * 1000;
  assert.throws(() => registry.claim({ code, deviceName: "x", platform: "x" }), /wrong or has expired/u, "the open code was cancelled");
  const fresh = registry.createCode(LEGACY_DEVICE_ID);
  assert.ok(registry.claim({ code: fresh.code, deviceName: "x", platform: "x" }).token);
});

test("pairing over HTTP: code needs a device, claim needs only the code, the new token syncs", async (t) => {
  const app = await startServer(t);
  assert.equal((await post(app, "/pair/code", {})).status, 401);
  const codeResponse = await post(app, "/pair/code", {}, legacyToken);
  assert.equal(codeResponse.status, 200);
  const { code, expires_in_seconds: lifetime } = await codeResponse.json();
  assert.equal(lifetime, 600);

  const claim = await post(app, "/pair/claim", { code, device_name: "Pixel", platform: "android" });
  assert.equal(claim.status, 200);
  const { token, device_id: deviceId } = await claim.json();
  assert.match(token, /^vrd_/u);

  const health = await fetch(app.url("/health"), { headers: { Authorization: `Bearer ${token}` } });
  assert.equal(health.status, 200);
  const list = await (await fetch(app.url("/devices"), { headers: { Authorization: `Bearer ${token}` } })).json();
  assert.equal(list.current_device_id, deviceId);
  assert.equal((await post(app, "/devices/revoke", { device_id: deviceId }, legacyToken)).status, 200);
  assert.equal((await fetch(app.url("/health"), { headers: { Authorization: `Bearer ${token}` } })).status, 401);

  const page = await fetch(app.url(`/pair?code=${code}`), { headers: { "X-Forwarded-Host": "host.tailnet.ts.net" } });
  const html = await page.text();
  assert.match(page.headers.get("content-type"), /text\/html; charset=utf-8/u);
  assert.match(html, new RegExp(`scheme=vocabreminder;package=com.vocabreminder.android`, "u"));
  assert.match(html, /server=https%3A%2F%2Fhost.tailnet.ts.net&amp;code=\d{6}/u);
});

function database(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "vocab-devices-"));
  const db = openDatabase(path.join(root, "db.sqlite"));
  t.after(() => {
    db.close();
    fs.rmSync(root, { recursive: true, force: true });
  });
  return db;
}

async function startServer(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "vocab-devices-http-"));
  const dbPath = path.join(root, "db.sqlite");
  const db = openDatabase(dbPath);
  const { server } = createSyncServer({ recommendationCatalog: null, db, dbPath, token: legacyToken });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(async () => {
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
    db.close();
    fs.rmSync(root, { recursive: true, force: true });
  });
  const { port } = server.address();
  return { url: (pathname) => `http://127.0.0.1:${port}${pathname}` };
}

function post(app, pathname, body, bearer) {
  return fetch(app.url(pathname), {
    method: "POST",
    headers: { "Content-Type": "application/json", ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}) },
    body: JSON.stringify(body)
  });
}
