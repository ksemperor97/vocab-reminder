import assert from "node:assert/strict";
import test from "node:test";
import { openDatabase } from "../src/db.js";
import { createSyncServer } from "../src/http.js";

const token = "observability-test-token";

test("echoes a safe client request ID across server errors and redacted request logs", async (t) => {
  const entries = [];
  const app = await startServer(t, { requestLogger: (entry) => entries.push(entry) });
  const response = await fetch(`${app.url}/dict/privateword`, {
    headers: {
      Authorization: "Bearer intentionally-wrong-secret",
      "X-Request-Id": "client-request-123"
    }
  });
  const body = await response.json();

  assert.equal(response.status, 401);
  assert.equal(response.headers.get("x-request-id"), "client-request-123");
  assert.equal(body.error.request_id, "client-request-123");
  assert.equal(entries.length, 1);
  assert.equal(entries[0].request_id, "client-request-123");
  assert.equal(entries[0].route, "/dict/:lemma");
  assert.equal(entries[0].status, 401);
  assert.doesNotMatch(JSON.stringify(entries[0]), /privateword|wrong-secret|observability-test-token/);
});

test("generates a request ID when absent and keeps logging failures non-fatal", async (t) => {
  const app = await startServer(t, {
    requestIdFactory: () => "generated-request-456",
    requestLogger() {
      throw new Error("fixture logger unavailable");
    }
  });
  const response = await fetch(`${app.url}/health`, {
    headers: { Authorization: `Bearer ${token}` }
  });

  assert.equal(response.status, 200);
  assert.equal(response.headers.get("x-request-id"), "generated-request-456");
});

async function startServer(t, options = {}) {
  const db = openDatabase(":memory:");
  const { server } = createSyncServer({ db, token, ...options });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(async () => {
    await new Promise((resolve) => server.close(resolve));
    db.close();
  });
  const { port } = server.address();
  return { url: `http://127.0.0.1:${port}` };
}
