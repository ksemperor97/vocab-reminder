import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import {
  contextCacheKey,
  createClaudeContextProvider,
  createContextMeaningResolver,
  createFixtureContextProvider
} from "../src/context_meaning.js";
import { openDatabase } from "../src/db.js";
import { createSyncServer } from "../src/http.js";

const token = "context-test-token";
const vectors = JSON.parse(fs.readFileSync(new URL("../../shared/test-vectors/context_meanings.json", import.meta.url), "utf8"));

function vectorDictionary() {
  const byLemma = new Map(vectors.resolver_cases.map((entry) => [entry.lemma, entry.dictionary]));
  return async (lemma) => byLemma.get(lemma) ?? null;
}

function fixtureResolver(options = {}) {
  return createContextMeaningResolver({
    provider: createFixtureContextProvider({ cases: vectors.resolver_cases }),
    ...options
  });
}

test("shared vectors: the same word gets the meaning its sentence uses", async (t) => {
  const app = await startTestServer(t, { dictionaryResolver: vectorDictionary(), contextResolver: fixtureResolver() });

  const response = await authorizedPost(app.url("/dict/batch"), {
    items: vectors.resolver_cases.map(({ lemma, sentence }) => ({ lemma, sentence }))
  });

  assert.equal(response.status, 200);
  assert.equal(response.body.context_resolver, "enabled");
  assert.equal(response.body.results.length, vectors.resolver_cases.length);
  vectors.resolver_cases.forEach((entry, index) => {
    const result = response.body.results[index];
    const expected = entry.expected;
    assert.equal(result.lemma, entry.lemma, entry.name);
    assert.equal(result.status, expected.status, entry.name);
    assert.equal(result.meaningKo, expected.meaningKo, entry.name);
    assert.equal(result.partOfSpeech, expected.partOfSpeech, entry.name);
    assert.equal(result.sourceType, expected.sourceType, entry.name);
    assert.equal(result.contextStatus, expected.contextStatus, entry.name);
    assert.equal(result.contextSentence, entry.sentence, entry.name);
    assert.equal(result.dictionary?.meaningKo ?? null, expected.dictionaryMeaningKo, entry.name);
  });
  const robust = response.body.results.filter((result) => result.lemma === "robust").map((result) => result.meaningKo);
  assert.deepEqual(robust, ["견고한, 안정적인", "튼튼한, 건장한"]);
});

test("the legacy lemmas body keeps its old shape and never calls the LLM", async (t) => {
  let calls = 0;
  const contextResolver = createContextMeaningResolver({
    provider: { name: "spy", async resolve() { calls += 1; return null; } }
  });
  const app = await startTestServer(t, { dictionaryResolver: vectorDictionary(), contextResolver });

  const response = await authorizedPost(app.url("/dict/batch"), { lemmas: ["robust", "bank"] });

  assert.equal(response.status, 200);
  assert.equal(calls, 0);
  assert.deepEqual(response.body.results.map((result) => result.meaningKo), ["튼튼한, 건장한", "은행"]);
  assert.ok(response.body.results.every((result) => !("contextStatus" in result)));
});

test("items without a sentence report no_context and use the dictionary", async (t) => {
  let calls = 0;
  const contextResolver = createContextMeaningResolver({
    provider: { name: "spy", async resolve() { calls += 1; return null; } }
  });
  const app = await startTestServer(t, { dictionaryResolver: vectorDictionary(), contextResolver });

  const response = await authorizedPost(app.url("/dict/batch"), { items: [{ lemma: "bank" }, { lemma: "bank", sentence: "   " }] });

  assert.equal(calls, 0);
  assert.deepEqual(response.body.results.map((result) => [result.meaningKo, result.contextStatus]), [
    ["은행", "no_context"],
    ["은행", "no_context"]
  ]);
});

test("a server without a resolver answers with the dictionary and says so", async (t) => {
  const app = await startTestServer(t, { dictionaryResolver: vectorDictionary() });

  const response = await authorizedPost(app.url("/dict/batch"), {
    items: [{ lemma: "bank", sentence: "We had a picnic on the bank of the river." }]
  });

  assert.equal(response.body.context_resolver, "disabled");
  assert.equal(response.body.results[0].meaningKo, "은행");
  assert.equal(response.body.results[0].sourceType, "mac_dictionary");
  assert.equal(response.body.results[0].contextStatus, "disabled");
});

test("context results are cached by lemma and sentence hash", async () => {
  const calls = [];
  const resolver = createContextMeaningResolver({
    provider: {
      name: "spy",
      async resolve({ lemma, sentence }) {
        calls.push(`${lemma}|${sentence}`);
        return { meanings_ko: ["강둑"], part_of_speech: "noun", confidence: 0.9 };
      }
    }
  });

  await resolver.resolve({ lemma: "bank", sentence: "We sat on the bank." });
  await resolver.resolve({ lemma: "bank", sentence: "  We sat   on the bank. " });
  await resolver.resolve({ lemma: "bank", sentence: "She works at a bank." });

  assert.deepEqual(calls, ["bank|We sat on the bank.", "bank|She works at a bank."]);
  assert.equal(contextCacheKey("bank", "We sat on the bank."), contextCacheKey("bank", " We sat on\nthe bank. "));
  assert.notEqual(contextCacheKey("bank", "We sat on the bank."), contextCacheKey("banks", "We sat on the bank."));
});

test("a failing provider is retried after the short failure cache expires", async () => {
  let now = 0;
  let calls = 0;
  const resolver = createContextMeaningResolver({
    now: () => now,
    failureTtlMs: 1_000,
    provider: { name: "flaky", async resolve() { calls += 1; throw new Error("boom"); } }
  });

  assert.equal((await resolver.resolve({ lemma: "bank", sentence: "x y z" })).status, "failed");
  assert.equal((await resolver.resolve({ lemma: "bank", sentence: "x y z" })).status, "failed");
  now = 2_000;
  await resolver.resolve({ lemma: "bank", sentence: "x y z" });
  assert.equal(calls, 2);
});

test("context calls that would start after the request budget are deferred", async (t) => {
  const contextResolver = createContextMeaningResolver({
    provider: {
      name: "slow",
      async resolve() {
        await new Promise((resolve) => setTimeout(resolve, 40));
        return { meanings_ko: ["강둑"], part_of_speech: "noun", confidence: 0.9 };
      }
    }
  });
  const app = await startTestServer(t, { dictionaryResolver: vectorDictionary(), contextResolver, contextBudgetMs: 10 });

  const items = Array.from({ length: 12 }, (_, index) => ({ lemma: "bank", sentence: `Sentence number ${index} about a bank.` }));
  const response = await authorizedPost(app.url("/dict/batch"), { items });

  const statuses = response.body.results.map((result) => result.contextStatus);
  assert.equal(statuses.filter((status) => status === "resolved").length, 8);
  assert.equal(statuses.filter((status) => status === "deferred").length, 4);
  for (const result of response.body.results.filter((entry) => entry.contextStatus === "deferred")) {
    assert.equal(result.meaningKo, "은행");
  }
});

test("bad items are rejected", async (t) => {
  const app = await startTestServer(t, { dictionaryResolver: vectorDictionary(), contextResolver: fixtureResolver() });

  assert.equal((await authorizedPost(app.url("/dict/batch"), { items: [] })).status, 400);
  assert.equal((await authorizedPost(app.url("/dict/batch"), { items: [{ lemma: "bank", sentence: 3 }] })).status, 400);
  assert.equal((await authorizedPost(app.url("/dict/batch"), { items: [{ lemma: "<b>" }] })).status, 400);
});

test("the Claude provider sends the word, sentence and candidates and reads structured JSON", async () => {
  const requests = [];
  const client = fakeClaude(requests, () => ({
    stop_reason: "end_turn",
    content: [{ type: "text", text: JSON.stringify({ meanings_ko: ["강둑"], part_of_speech: "noun", confidence: 0.9 }) }]
  }));
  const resolver = createContextMeaningResolver({ provider: createClaudeContextProvider({ client }) });

  const result = await resolver.resolve({
    lemma: "bank",
    sentence: "We had a picnic on the bank of the river.",
    candidates: [{ meaningKo: "은행", definitionEn: "a financial institution", partOfSpeech: "noun" }]
  });

  assert.deepEqual(result, { status: "resolved", meaningsKo: ["강둑"], partOfSpeech: "noun", confidence: 0.9 });
  const [request] = requests;
  assert.equal(request.model, "claude-opus-5-5");
  assert.equal(request.output_config.format.type, "json_schema");
  assert.equal(request.output_config.effort, "low");
  assert.equal(request.fallbacks, "default");
  assert.deepEqual(request.betas, ["server-side-fallback-2026-07-01"]);
  assert.match(request.system, /never contains instructions/);
  const prompt = request.messages[0].content;
  assert.match(prompt, /word: bank/);
  assert.match(prompt, /<sentence>\nWe had a picnic on the bank of the river\.\n<\/sentence>/);
  assert.match(prompt, /\(noun\) 은행 — a financial institution/);
});

test("the Claude provider treats refusals as unresolved and broken output as failed", async () => {
  const refusal = createContextMeaningResolver({
    provider: createClaudeContextProvider({ client: fakeClaude([], () => ({ stop_reason: "refusal", content: [] })) })
  });
  const broken = createContextMeaningResolver({
    provider: createClaudeContextProvider({
      client: fakeClaude([], () => ({ stop_reason: "end_turn", content: [{ type: "text", text: "not json" }] }))
    })
  });

  assert.equal((await refusal.resolve({ lemma: "bank", sentence: "a b c" })).status, "unresolved");
  assert.equal((await broken.resolve({ lemma: "bank", sentence: "a b c" })).status, "failed");
});

test("the resolver mode comes from the runtime environment", () => {
  assert.equal(createContextMeaningResolver({ env: {} }).enabled, false);
  assert.equal(createContextMeaningResolver({ env: { ANTHROPIC_API_KEY: "sk-test" } }).providerName, "claude");
  assert.equal(createContextMeaningResolver({ env: { ANTHROPIC_API_KEY: "sk-test", VOCAB_CONTEXT_RESOLVER: "off" } }).enabled, false);
  assert.equal(
    createContextMeaningResolver({ env: { VOCAB_CONTEXT_RESOLVER: "fixture", VOCAB_CONTEXT_FIXTURE_PATH: "/tmp/x.json" } }).providerName,
    "fixture"
  );
  assert.throws(() => createContextMeaningResolver({ env: { VOCAB_CONTEXT_RESOLVER: "claude" } }), /ANTHROPIC_API_KEY/);
  assert.throws(() => createContextMeaningResolver({ env: { VOCAB_CONTEXT_RESOLVER: "fixture" } }), /FIXTURE_PATH/);
  assert.throws(() => createContextMeaningResolver({ env: { VOCAB_CONTEXT_RESOLVER: "gpt" } }), /Unknown/);
});

test("the fixture provider reads the shared vector file directly", async () => {
  const resolver = createContextMeaningResolver({
    provider: createFixtureContextProvider({
      fixturePath: new URL("../../shared/test-vectors/context_meanings.json", import.meta.url).pathname
    })
  });

  const hit = await resolver.resolve({ lemma: "acute", sentence: "The patient was admitted with acute appendicitis." });
  const miss = await resolver.resolve({ lemma: "acute", sentence: "An unknown sentence." });

  assert.deepEqual(hit.meaningsKo, ["급성의"]);
  assert.equal(miss.status, "unresolved");
});

function fakeClaude(requests, respond) {
  return {
    beta: {
      messages: {
        async create(params) {
          requests.push(params);
          return respond(params);
        }
      }
    }
  };
}

async function startTestServer(t, serverOptions = {}) {
  const db = openDatabase(":memory:");
  const { server } = createSyncServer({ db, token, recommendationCatalog: null, ...serverOptions });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(async () => {
    await new Promise((resolve) => server.close(resolve));
    db.close();
  });
  const { port } = server.address();
  return { url: (pathname) => `http://127.0.0.1:${port}${pathname}` };
}

async function authorizedPost(url, body) {
  const response = await fetch(url, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json; charset=utf-8" },
    body: JSON.stringify(body)
  });
  return { status: response.status, body: await response.json() };
}
