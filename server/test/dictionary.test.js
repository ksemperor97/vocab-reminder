import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {
  createDictionaryResolver,
  normalizeDictionaryLemma
} from "../src/dictionary.js";
import { openDatabase } from "../src/db.js";
import { createSyncServer } from "../src/http.js";

const token = "dictionary-test-token";

test("dictionary HTTP endpoints require the sync bearer token", async (t) => {
  const app = await startTestServer(t, {
    dictionaryResolver: async () => ({ meaningKo: "뜻" })
  });

  const missing = await fetchJson(app.url("/dict/resilient"));
  const wrong = await fetchJson(app.url("/dict/resilient"), {
    headers: { Authorization: "Bearer wrong-token" }
  });

  assert.equal(missing.status, 401);
  assert.equal(wrong.status, 401);
  assert.equal(missing.body.error.message, "Authentication required.");
});

test("GET /dict/:lemma normalizes a provider hit into the stable result shape", async (t) => {
  const attemptedAt = "2026-07-13T01:02:03.000Z";
  const app = await startTestServer(t, {
    dictionaryResolver: async (lemma) => ({
      lemma,
      meaningKo: "회복력 있는",
      definitionEn: "able to recover quickly",
      partOfSpeech: "adjective",
      exampleSentence: "A resilient system recovers quickly.",
      sourceType: "offline_catalog",
      confidence: 0.91,
      attemptedAt
    })
  });

  const response = await authorizedGet(app.url("/dict/Resilient"));

  assert.equal(response.status, 200);
  assert.deepEqual(response.body, {
    status: "resolved",
    lemma: "resilient",
    meaningKo: "회복력 있는",
    definitionEn: "able to recover quickly",
    partOfSpeech: "adjective",
    exampleSentence: "A resilient system recovers quickly.",
    sourceType: "offline_catalog",
    confidence: 0.91,
    attemptedAt,
    errorCode: null
  });
});

test("dictionary misses remain a non-blocking 200 pending result", async (t) => {
  const app = await startTestServer(t, { dictionaryResolver: async () => null });

  const response = await authorizedGet(app.url("/dict/perovskite"));

  assert.equal(response.status, 200);
  assert.equal(response.body.status, "pending");
  assert.equal(response.body.lemma, "perovskite");
  assert.equal(response.body.meaningKo, "");
  assert.equal(response.body.definitionEn, "");
  assert.equal(response.body.sourceType, "none");
  assert.equal(response.body.confidence, 0);
  assert.equal(response.body.errorCode, "not_found");
  assert.match(response.body.attemptedAt, /^\d{4}-\d{2}-\d{2}T/);
});

test("dictionary endpoints reject malformed lemmas and cap batch requests", async (t) => {
  let lookups = 0;
  const app = await startTestServer(t, {
    maxDictionaryBatchItems: 2,
    dictionaryResolver: async (lemma) => {
      lookups += 1;
      return { meaningKo: `뜻:${lemma}`, sourceType: "fixture", confidence: 1 };
    }
  });

  const malformedGet = await authorizedGet(app.url("/dict/bad%2Flemma"));
  const missingArray = await authorizedPost(app.url("/dict/batch"), { words: ["one"] });
  const overLimit = await authorizedPost(app.url("/dict/batch"), { lemmas: ["one", "two", "three"] });
  const malformedBatch = await authorizedPost(app.url("/dict/batch"), { lemmas: ["valid", "bad123"] });

  assert.equal(malformedGet.status, 400);
  assert.equal(missingArray.status, 400);
  assert.equal(overLimit.status, 400);
  assert.equal(malformedBatch.status, 400);
  assert.equal(lookups, 0);
});

test("POST /dict/batch preserves order while deduplicating provider work", async (t) => {
  const calls = [];
  const app = await startTestServer(t, {
    maxDictionaryBatchItems: 4,
    dictionaryResolver: async (lemma) => {
      calls.push(lemma);
      return { meaningKo: `뜻:${lemma}`, sourceType: "fixture", confidence: 0.8 };
    }
  });

  const response = await authorizedPost(app.url("/dict/batch"), {
    lemmas: ["Resilient", "robust", "resilient"]
  });

  assert.equal(response.status, 200);
  assert.deepEqual(response.body.results.map((result) => result.lemma), ["resilient", "robust", "resilient"]);
  assert.deepEqual(calls.sort(), ["resilient", "robust"]);
});

test("provider failures return a retryable pending result instead of HTTP 500", async (t) => {
  const app = await startTestServer(t, {
    dictionaryResolver: {
      async lookup() {
        throw new Error("fixture provider unavailable");
      }
    }
  });

  const response = await authorizedGet(app.url("/dict/resilient"));

  assert.equal(response.status, 200);
  assert.equal(response.body.status, "pending");
  assert.equal(response.body.errorCode, "provider_failure");
});

test("authenticated dictionary requests have a bounded per-process rate limit", async (t) => {
  let currentTime = Date.parse("2026-07-14T00:00:00Z");
  const app = await startTestServer(t, {
    dictionaryRequestsPerMinute: 2,
    now: () => currentTime,
    dictionaryResolver: async (lemma) => ({ meaningKo: `뜻:${lemma}` })
  });

  assert.equal((await authorizedGet(app.url("/dict/alpha"))).status, 200);
  assert.equal((await authorizedGet(app.url("/dict/beta"))).status, 200);
  const limited = await authorizedGet(app.url("/dict/gamma"));
  assert.equal(limited.status, 429);
  assert.equal(limited.body.error.message, "Dictionary request rate limit exceeded.");
  assert.equal(limited.headers.get("retry-after"), "60");

  currentTime += 60_000;
  assert.equal((await authorizedGet(app.url("/dict/gamma"))).status, 200);
});

test("configured offline JSON catalog is local-only and skips malformed rows", async (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "vocab-dictionary-catalog-test-"));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const catalogPath = path.join(root, "catalog.json");
  fs.writeFileSync(catalogPath, JSON.stringify({
    entries: [
      {
        lemma: "resilient",
        meaningKo: "회복력 있는",
        definitionEn: "able to recover quickly",
        partOfSpeech: "adjective",
        exampleSentence: "A resilient system recovers quickly."
      },
      { lemma: "bad123", meaningKo: "무시할 행" }
    ]
  }));
  const resolver = createDictionaryResolver({
    catalogPath,
    now: () => new Date("2026-07-13T02:03:04.000Z")
  });

  const hit = await resolver.lookup("Resilient");
  const miss = await resolver.lookup("perovskite");

  assert.equal(hit.status, "resolved");
  assert.equal(hit.meaningKo, "회복력 있는");
  assert.equal(hit.sourceType, "offline_catalog");
  assert.equal(hit.confidence, 0.9);
  assert.equal(hit.attemptedAt, "2026-07-13T02:03:04.000Z");
  assert.equal(miss.status, "pending");
  assert.equal(miss.errorCode, "not_found");
});

test("English-only informal variants derive a canonical negative form from the definition", async () => {
  const calls = [];
  const resolver = createDictionaryResolver({
    providers: [{
      async lookup(lemma) {
        calls.push(lemma);
        if (lemma === "uncomfy") {
          return {
            definitionEn: "informal: not comfortable",
            partOfSpeech: "adjective",
            sourceType: "mac_dictionary",
            confidence: 0.68
          };
        }
        if (lemma === "uncomfortable") {
          return {
            meaningKo: "불편한, 편하지 않은",
            partOfSpeech: "adjective",
            sourceType: "mac_dictionary",
            confidence: 0.78
          };
        }
        return null;
      }
    }],
    now: () => new Date("2026-07-28T14:00:00.000Z")
  });

  const result = await resolver.lookup("uncomfy");

  assert.deepEqual(calls, ["uncomfy", "uncomfortable"]);
  assert.equal(result.status, "resolved");
  assert.equal(result.lemma, "uncomfy");
  assert.equal(result.meaningKo, "불편한, 편하지 않은");
  assert.equal(result.definitionEn, "informal: not comfortable");
  assert.equal(result.partOfSpeech, "adjective");
  assert.equal(result.sourceType, "definition_variant");
  assert.equal(result.confidence, 0.72);
});

test("definition relations resolve variants without a per-word alias list", async () => {
  const calls = [];
  const resolver = createDictionaryResolver({
    providers: [{
      async lookup(lemma) {
        calls.push(lemma);
        if (lemma === "awks") {
          return {
            definitionEn: "informal. Origin: abbreviation of awkward.",
            partOfSpeech: "adjective",
            sourceType: "mac_dictionary",
            confidence: 0.68
          };
        }
        if (lemma === "awkward") {
          return {
            meaningKo: "어색한, 곤란한",
            partOfSpeech: "adjective",
            sourceType: "mac_dictionary",
            confidence: 0.78
          };
        }
        return null;
      }
    }]
  });

  const result = await resolver.lookup("awks");

  assert.deepEqual(calls, ["awks", "awkward"]);
  assert.equal(result.lemma, "awks");
  assert.equal(result.meaningKo, "어색한, 곤란한");
  assert.equal(result.sourceType, "definition_variant");
});

test("descriptive definitions do not guess an unrelated canonical word", async () => {
  const calls = [];
  const resolver = createDictionaryResolver({
    providers: [{
      async lookup(lemma) {
        calls.push(lemma);
        return {
          definitionEn: "informal causing feelings of acute embarrassment or awkwardness",
          partOfSpeech: "adjective",
          sourceType: "mac_dictionary",
          confidence: 0.68
        };
      }
    }]
  });

  const result = await resolver.lookup("cringey");

  assert.deepEqual(calls, ["cringey"]);
  assert.equal(result.meaningKo, "");
  assert.equal(result.sourceType, "mac_dictionary");
});

test("resolver cache coalesces concurrent work and does not expose mutable entries", async () => {
  let calls = 0;
  const resolver = createDictionaryResolver({
    providers: [{
      async lookup() {
        calls += 1;
        await new Promise((resolve) => setTimeout(resolve, 5));
        return { meaningKo: "원본", sourceType: "fixture", confidence: 0.7 };
      }
    }]
  });

  const [first, concurrent] = await Promise.all([
    resolver.lookup("robust"),
    resolver.lookup("robust")
  ]);
  first.meaningKo = "변조";
  const cached = await resolver.lookup("robust");

  assert.equal(calls, 1);
  assert.equal(concurrent.meaningKo, "원본");
  assert.equal(cached.meaningKo, "원본");
});

test("lemma validation accepts phrases and apostrophes but rejects control-like input", () => {
  assert.equal(normalizeDictionaryLemma("  State-of-the-Art  "), "state-of-the-art");
  assert.equal(normalizeDictionaryLemma("Learner’s Dictionary"), "learner's dictionary");
  assert.throws(() => normalizeDictionaryLemma("word?token"), /unsupported characters/);
  assert.throws(() => normalizeDictionaryLemma("a".repeat(81)), /between 1 and 80/);
});

async function startTestServer(t, serverOptions = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "vocab-dictionary-http-test-"));
  const db = openDatabase(":memory:");
  const { server } = createSyncServer({ db, token, ...serverOptions });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(async () => {
    await new Promise((resolve) => server.close(resolve));
    db.close();
    fs.rmSync(root, { recursive: true, force: true });
  });
  const { port } = server.address();
  return { url: (pathname) => `http://127.0.0.1:${port}${pathname}` };
}

async function authorizedGet(url) {
  return fetchJson(url, { headers: { Authorization: `Bearer ${token}` } });
}

async function authorizedPost(url, body) {
  return fetchJson(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json; charset=utf-8"
    },
    body: JSON.stringify(body)
  });
}

async function fetchJson(url, options) {
  const response = await fetch(url, options);
  return { status: response.status, headers: response.headers, body: await response.json() };
}

test("structured alternative senses survive normalization without replacing the primary", async () => {
  const { normalizeDictionaryResult } = await import("../src/dictionary.js");
  const result = normalizeDictionaryResult("bank", {
    meaningKo: "은행", definitionEn: "a financial institution", sourceType: "offline_catalog",
    senses: [{ meaning_ko: "강둑", definition_en: "land beside a river", part_of_speech: "noun", example_sentence: "We stood on the bank." }]
  });
  assert.equal(result.meaningKo, "은행");
  assert.deepEqual(result.senses, [{ meaningKo: "강둑", definitionEn: "land beside a river", partOfSpeech: "noun", exampleSentence: "We stood on the bank." }]);
});
