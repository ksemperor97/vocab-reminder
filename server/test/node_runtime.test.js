import assert from "node:assert/strict";
import test from "node:test";
import { nodeRuntimeCompatibility, parkIncompatibleRuntime } from "../src/node_runtime.js";

test("accepts the pinned Node major", () => {
  assert.deepEqual(nodeRuntimeCompatibility("22.22.2", "22"), {
    compatible: true,
    currentMajor: 22,
    requiredMajor: 22,
    reason: null
  });
});

test("rejects a different Node major before native modules load", () => {
  const result = nodeRuntimeCompatibility("25.9.0", "22");
  assert.equal(result.compatible, false);
  assert.equal(result.currentMajor, 25);
  assert.equal(result.requiredMajor, 22);
  assert.match(result.reason, /does not match required major 22/);
});

test("parking logs once and keeps a referenced timer alive", async () => {
  const messages = [];
  const timers = [];
  const parked = Symbol("parked");
  const result = await parkIncompatibleRuntime(
    nodeRuntimeCompatibility("25.9.0", "22"),
    {
      log(message) {
        messages.push(message);
      },
      setIntervalImpl(callback, delay) {
        timers.push({ callback, delay });
        return 1;
      },
      async park() {
        return parked;
      }
    }
  );

  assert.equal(result, parked);
  assert.equal(messages.length, 1);
  assert.match(messages[0], /Dropbox log churn/);
  assert.equal(timers.length, 1);
  assert.equal(timers[0].delay, 24 * 60 * 60 * 1000);
});
