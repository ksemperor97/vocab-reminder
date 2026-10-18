import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { mkdtemp, readFile, rm, symlink } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { PassThrough } from "node:stream";
import test from "node:test";
import { fileURLToPath } from "node:url";

import {
  isDirectExecution,
  loadConfiguration,
  QuickTunnelSupervisor,
  resolveStateFile,
  writeJsonAtomically,
} from "./run_cloudflare_quick_worker.mjs";

test("default health timing leaves enough budget for connection recovery within 120 seconds", () => {
  const config = loadConfiguration({
    CF_WORKER_PUBLIC_URL: "https://stable.example.test",
    CF_WORKER_ADMIN_TOKEN: "admin-secret",
    VOCAB_SYNC_TOKEN: "sync-secret",
  });
  const worstCaseBudget =
    config.healthIntervalMs * config.healthFailureThreshold +
    config.healthTimeoutMs * config.healthFailureThreshold +
    config.childStopGraceMs +
    config.restartBaseMs +
    config.tunnelReadyTimeoutMs;

  assert.equal(config.healthIntervalMs, 10_000);
  assert.equal(config.healthFailureThreshold, 2);
  assert.ok(worstCaseBudget < 120_000, `recovery budget was ${worstCaseBudget} ms`);
});

test("direct execution detection follows a versioned current symlink", async (t) => {
  const root = await mkdtemp(path.join(os.tmpdir(), "vocab-tunnel-symlink-test-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const targetUrl = new URL("./run_cloudflare_quick_worker.mjs", import.meta.url);
  const targetPath = fileURLToPath(targetUrl);
  const currentPath = path.join(root, "current-runner.mjs");
  await symlink(targetPath, currentPath);

  assert.equal(isDirectExecution(currentPath, targetUrl.href), true);
});

class FakeChild extends EventEmitter {
  constructor(pid) {
    super();
    this.pid = pid;
    this.stdout = new PassThrough();
    this.stderr = new PassThrough();
    this.kills = [];
  }

  kill(signal) {
    this.kills.push(signal);
    this.emit("exit", 0, signal);
    return true;
  }
}

function configuration(overrides = {}) {
  return {
    cloudflaredBin: "fake-cloudflared",
    workerUrl: "https://stable.example.test",
    adminToken: "admin-secret",
    syncToken: "sync-secret",
    upstreamUrl: "http://127.0.0.1:8787",
    updateUrl: "https://stable.example.test/__admin/upstream",
    stateFile: "/tmp/unused-tunnel-state.json",
    healthIntervalMs: 1_000,
    healthTimeoutMs: 500,
    healthFailureThreshold: 2,
    tunnelReadyTimeoutMs: 1_000,
    restartBaseMs: 100,
    restartMaxMs: 400,
    publishAttempts: 1,
    publishRetryMs: 1,
    metadataRefreshMs: 1,
    childStopGraceMs: 100,
    ...overrides,
  };
}

function harness({ config = configuration(), now = 1_700_000_000_000 } = {}) {
  const children = [];
  const publications = [];
  const states = [];
  const health = { local: true, public: true, published: undefined, stable: undefined };
  let clock = now;
  const fetchImpl = async (input, init = {}) => {
    const url = new URL(typeof input === "string" ? input : input.url);
    const authorization = new Headers(init.headers || input.headers).get("authorization") || "";
    if (url.hostname === "127.0.0.1") {
      return new Response("", { status: health.local ? 200 : 503 });
    }
    if (url.pathname === "/__admin/upstream") {
      assert.equal(new Headers(init.headers).get("x-vocab-sync-admin"), "admin-secret");
      publications.push(JSON.parse(init.body));
      return Response.json({ ok: true });
    }
    const endpointHealthy = url.hostname.endsWith(".trycloudflare.com")
      ? (health.published ?? health.public)
      : (health.stable ?? health.public);
    if (!endpointHealthy) {
      return Response.json({ error: "down" }, { status: 503 });
    }
    return new Response("", {
      status: authorization === "Bearer sync-secret" ? 200 : 401,
    });
  };
  const supervisor = new QuickTunnelSupervisor(config, {
    spawnImpl: () => {
      const child = new FakeChild(10_000 + children.length);
      children.push(child);
      return child;
    },
    fetchImpl,
    now: () => clock,
    writeStateImpl: async (_file, state) => states.push(state),
    logger: { log() {}, error() {} },
  });
  return {
    supervisor,
    children,
    publications,
    states,
    health,
    setNow(value) {
      clock = value;
    },
  };
}

test("supervisor publishes metadata and requires 200/401 auth health on public endpoints", async () => {
  const h = harness();
  await h.supervisor.start({ schedule: false });
  assert.equal(h.children.length, 1);

  await h.supervisor.acceptTunnelUrl("https://fresh-one.trycloudflare.com");
  await h.supervisor.runHealthCycle();
  await h.supervisor.waitForIdle();

  assert.equal(h.publications.length, 2);
  assert.equal(h.publications[0].url, "https://fresh-one.trycloudflare.com");
  assert.equal(h.publications[0].generation, 1_700_000_000_000);
  assert.equal(h.publications[0].last_success_at, null);
  assert.equal(h.publications[1].last_success_at, "2023-11-14T22:13:20.000Z");

  const state = h.supervisor.snapshot();
  assert.equal(state.supervisor_status, "healthy");
  assert.equal(state.child_status, "running");
  assert.equal(state.last_local_health_at, "2023-11-14T22:13:20.000Z");
  assert.equal(state.last_stable_health_at, "2023-11-14T22:13:20.000Z");
  assert.deepEqual(state.consecutive_failures, { local: 0, stable: 0, child: 0 });
  await h.supervisor.stop();
});

test("supervisor restarts stale public connections with capped exponential backoff", async () => {
  const h = harness({
    config: configuration({
      healthFailureThreshold: 1,
      restartBaseMs: 100,
      restartMaxMs: 150,
    }),
    now: 1_000,
  });
  await h.supervisor.start({ schedule: false });
  await h.supervisor.acceptTunnelUrl("https://stale-one.trycloudflare.com");
  h.health.public = false;

  await h.supervisor.runHealthCycle();
  assert.equal(h.children[0].kills[0], "SIGTERM");
  assert.equal(h.supervisor.snapshot().restart_attempt, 1);
  assert.equal(h.supervisor.snapshot().next_restart_at, "1970-01-01T00:00:01.100Z");

  h.setNow(1_099);
  await h.supervisor.runHealthCycle();
  assert.equal(h.children.length, 1);
  h.setNow(1_100);
  await h.supervisor.runHealthCycle();
  assert.equal(h.children.length, 2);

  await h.supervisor.acceptTunnelUrl("https://stale-two.trycloudflare.com");
  await h.supervisor.runHealthCycle();
  assert.equal(h.children[1].kills[0], "SIGTERM");
  assert.equal(h.supervisor.snapshot().restart_attempt, 2);
  assert.equal(h.supervisor.snapshot().next_restart_at, "1970-01-01T00:00:01.250Z");
});

test("local outage is tracked separately and does not churn cloudflared", async () => {
  const h = harness({ config: configuration({ healthFailureThreshold: 1 }) });
  await h.supervisor.start({ schedule: false });
  await h.supervisor.acceptTunnelUrl("https://still-running.trycloudflare.com");
  h.health.local = false;
  h.health.public = false;

  await h.supervisor.runHealthCycle();
  await h.supervisor.runHealthCycle();

  assert.equal(h.children.length, 1);
  assert.deepEqual(h.children[0].kills, []);
  assert.equal(h.supervisor.snapshot().supervisor_status, "waiting_for_local");
  assert.equal(h.supervisor.snapshot().consecutive_failures.local, 2);
  assert.equal(h.supervisor.snapshot().consecutive_failures.stable, 0);
  await h.supervisor.stop();
});

test("stable Worker failure republishes metadata without recycling a healthy quick tunnel", async () => {
  const h = harness({ config: configuration({ healthFailureThreshold: 1 }) });
  await h.supervisor.start({ schedule: false });
  await h.supervisor.acceptTunnelUrl("https://direct-is-healthy.trycloudflare.com");
  h.health.published = true;
  h.health.stable = false;

  await h.supervisor.runHealthCycle();
  await h.supervisor.runHealthCycle();

  assert.equal(h.children.length, 1);
  assert.deepEqual(h.children[0].kills, []);
  assert.equal(h.publications.length, 3);
  assert.equal(h.supervisor.snapshot().supervisor_status, "degraded");
  assert.equal(h.supervisor.snapshot().consecutive_failures.stable, 2);
  assert.equal(h.supervisor.snapshot().last_error.category, "stable_health");
  await h.supervisor.stop();
});

test("state path selection and atomic JSON writer use the runtime root when it exists", async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), "vocab-tunnel-state-"));
  try {
    const selected = resolveStateFile({ VOCAB_SYNC_RUNTIME_ROOT: root });
    assert.equal(selected, path.join(root, "state", "tunnel-supervisor.json"));
    await writeJsonAtomically(selected, { schema_version: 1, supervisor_status: "healthy" });
    assert.deepEqual(JSON.parse(await readFile(selected, "utf8")), {
      schema_version: 1,
      supervisor_status: "healthy",
    });
    assert.equal(resolveStateFile({ TUNNEL_STATE_FILE: path.join(root, "custom.json") }), path.join(root, "custom.json"));
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
