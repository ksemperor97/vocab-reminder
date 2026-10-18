import assert from "node:assert/strict";
import test from "node:test";

import { createWorker } from "./worker.js";

class MemoryKV {
  constructor(initial = null) {
    this.value = initial;
  }

  async get() {
    return this.value;
  }

  async put(_key, value) {
    this.value = value;
  }
}

function environment(initial = null) {
  return { ADMIN_TOKEN: "admin-secret", SYNC_UPSTREAM: new MemoryKV(initial) };
}

function adminRequest(method, body) {
  return new Request("https://stable.example.test/__admin/upstream", {
    method,
    headers: {
      "content-type": "application/json",
      "x-vocab-sync-admin": "admin-secret",
    },
    body: body ? JSON.stringify(body) : undefined,
  });
}

test("admin update stores generation and health timestamps while GET exposes metadata", async () => {
  const env = environment();
  const worker = createWorker({ fetchImpl: async () => new Response("ok") });
  const metadata = {
    url: "https://fresh.trycloudflare.com/some/path",
    generation: 42,
    updated_at: "2026-07-13T01:02:03.000Z",
    last_success_at: "2026-07-13T01:02:04.000Z",
  };

  const update = await worker.fetch(adminRequest("POST", metadata), env);
  assert.equal(update.status, 200);
  assert.deepEqual(JSON.parse(env.SYNC_UPSTREAM.value), {
    url: "https://fresh.trycloudflare.com",
    generation: 42,
    updated_at: metadata.updated_at,
    last_success_at: metadata.last_success_at,
  });

  const republish = await worker.fetch(
    adminRequest("POST", {
      ...metadata,
      updated_at: "2026-07-13T01:03:00.000Z",
      last_success_at: null,
    }),
    env,
  );
  assert.equal(republish.status, 200);
  assert.equal(JSON.parse(env.SYNC_UPSTREAM.value).last_success_at, metadata.last_success_at);

  const status = await worker.fetch(adminRequest("GET"), env);
  assert.equal(status.status, 200);
  assert.deepEqual((await status.json()).upstream, JSON.parse(env.SYNC_UPSTREAM.value));
});

test("proxy remains compatible with legacy raw-URL KV values", async () => {
  const env = environment("https://legacy.trycloudflare.com");
  let forwarded;
  const worker = createWorker({
    fetchImpl: async (request) => {
      forwarded = request;
      return Response.json({ ok: true });
    },
  });
  const response = await worker.fetch(
    new Request("https://stable.example.test/health?full=1", {
      headers: { authorization: "Bearer token" },
    }),
    env,
  );

  assert.equal(response.status, 200);
  assert.equal(forwarded.url, "https://legacy.trycloudflare.com/health?full=1");
  assert.equal(forwarded.headers.get("authorization"), "Bearer token");
});

test("proxy streams non-GET request bodies to the selected tunnel", async () => {
  const env = environment("https://legacy.trycloudflare.com");
  let forwarded;
  const worker = createWorker({
    fetchImpl: async (request) => {
      forwarded = request;
      return Response.json({ applied: true });
    },
  });
  const response = await worker.fetch(
    new Request("https://stable.example.test/sync/batch", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ cards: [] }),
    }),
    env,
  );

  assert.equal(response.status, 200);
  assert.equal(forwarded.method, "POST");
  assert.equal(forwarded.url, "https://legacy.trycloudflare.com/sync/batch");
  assert.deepEqual(JSON.parse(await forwarded.text()), { cards: [] });
});

test("network failures and Cloudflare origin failures become JSON 503 with request IDs", async (t) => {
  const record = JSON.stringify({
    url: "https://dead.trycloudflare.com",
    generation: 7,
    updated_at: "2026-07-13T01:00:00.000Z",
    last_success_at: null,
  });

  for (const scenario of [
    { name: "fetch throws", fetchImpl: async () => { throw new Error("offline"); } },
    { name: "upstream returns 530", fetchImpl: async () => new Response("origin error", { status: 530 }) },
  ]) {
    await t.test(scenario.name, async () => {
      const worker = createWorker({ fetchImpl: scenario.fetchImpl });
      const response = await worker.fetch(
        new Request("https://stable.example.test/health", {
          headers: { "x-request-id": "test-request-123" },
        }),
        environment(record),
      );
      assert.equal(response.status, 503);
      assert.equal(response.headers.get("content-type"), "application/json; charset=utf-8");
      assert.equal(response.headers.get("x-request-id"), "test-request-123");
      assert.deepEqual(await response.json(), {
        error: {
          code: "upstream_unavailable",
          message: "Sync upstream is temporarily unavailable.",
          request_id: "test-request-123",
        },
      });
    });
  }
});

test("missing upstream is JSON 503 and admin token is required", async () => {
  const env = environment();
  const worker = createWorker({ fetchImpl: async () => new Response("unexpected") });
  const missing = await worker.fetch(
    new Request("https://stable.example.test/health", { headers: { "x-request-id": "missing-1" } }),
    env,
  );
  assert.equal(missing.status, 503);
  assert.equal((await missing.json()).error.code, "upstream_not_ready");

  const unauthorized = await worker.fetch(
    new Request("https://stable.example.test/__admin/upstream", {
      method: "POST",
      headers: { "x-vocab-sync-admin": "wrong" },
      body: JSON.stringify({ url: "https://new.trycloudflare.com" }),
    }),
    env,
  );
  assert.equal(unauthorized.status, 401);
});
