import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { openDatabase } from "../src/db.js";
import { SyncStore } from "../src/sync.js";
import { createSyncServer } from "../src/http.js";
import { readChanges } from "../src/changes.js";
import { createRecommendationCatalogLoader, maintainRecommendations, chooseRecommendations } from "../src/recommendations.js";

const now = new Date("2026-09-23T01:00:00Z");
const settings = {
  daily_word_goal: 3, max_queue_size: 20, snooze_duration_minutes: 10,
  quiet_hours_start: "22:00", quiet_hours_end: "08:00", timezone: "Asia/Seoul",
  enable_android_notifications: true, enable_mac_notifications: true,
  show_meaning_on_lockscreen: false, show_meaning_after_remembered: true,
  auto_generate_korean_meaning: true, save_source_url: true, save_surrounding_text: true,
  enable_word_recommendations: true, settings_updated_at: now.toISOString()
};
const seed = (lemma, topic = "everyday") => ({ lemma, topic, meaningKo: "테스트 뜻", definitionEn: "a test definition", partOfSpeech: "noun", exampleSentence: "This is a test example.", frequencyRank: 18000 });
const catalog = [seed("errand"), seed("itinerary", "travel"), seed("rapport", "relationships"), seed("surplus", "society"), seed("canopy", "nature"), seed("candid", "relationships")];
const card = (id, extra = {}) => ({ id, lemma: id, display_word: id, meaning_ko: "뜻", status: "new", wordbook: "user", due_at: "2027-01-01T00:00:00Z", created_at: now.toISOString(), updated_at: now.toISOString(), version: 1, ...extra });
function fixture(t, overrides = {}) {
  const db = openDatabase(":memory:");
  t.after(() => db.close());
  const store = new SyncStore(db);
  const result = store.applyBatch({ settings: { ...settings, ...overrides } });
  assert.equal(result.skipped.settings, 0);
  return store;
}
const waiting = store => store.snapshot().cards.filter(card => !card.deleted_at && card.wordbook === "recommended" && !card.recommendation_started_at);

test("one server queue is idempotent across repeated device pulls and restart", t => {
  const store = fixture(t);
  assert.equal(maintainRecommendations(store, catalog, now).generated, 3);
  const before = store.snapshot();
  const cursor = readChanges(store.db).sync_cursor;
  const restartedStore = new SyncStore(store.db);
  assert.equal(maintainRecommendations(restartedStore, catalog, now).generated, 0);
  assert.deepEqual(store.snapshot(), before);
  assert.equal(readChanges(store.db, cursor).cards.length, 0);
  assert.equal(new Set(waiting(store).map(card => card.id)).size, 3);
});

test("known feedback alone removes cards, defeats stale resurrection, and excludes later days", t => {
  const store = fixture(t);
  maintainRecommendations(store, catalog, now);
  const original = waiting(store)[0];
  const feedback = { id: "known", card_id: original.id, lemma: `  ${original.lemma.toUpperCase()}  `, action: "known", created_at: now.toISOString() };
  store.applyBatch({ recommendation_feedback: [feedback] });
  assert.deepEqual(maintainRecommendations(store, catalog, now), { generated: 0, retired: 1 });
  assert.equal(waiting(store).length, 2);
  // A stale active copy cannot resurrect the tombstone: deletion is permanent.
  store.applyBatch({ cards: [{ ...original, version: 100 }] });
  assert.ok(store.snapshot().cards.find(card => card.id === original.id).deleted_at);
  assert.equal(maintainRecommendations(store, catalog, now).retired, 0);
  const tomorrow = new Date(now.getTime() + 86400000);
  assert.equal(maintainRecommendations(store, catalog, tomorrow).generated, 1);
  assert.ok(waiting(store).every(card => card.lemma !== original.lemma));
});

test("migration replaces only untouched local suggestions, preserving learning and saved cards", t => {
  const store = fixture(t, { daily_word_goal: 5 });
  store.applyBatch({ cards: [card("legacy", { wordbook: "recommended" }),
    card("learning", { wordbook: "recommended", recommendation_started_at: now.toISOString(), status: "learning" }),
    card("reviewed", { wordbook: "recommended", status: "review" }), card("saved")] });
  assert.equal(maintainRecommendations(store, catalog, now).retired, 1);
  const snapshot = store.snapshot();
  assert.ok(snapshot.cards.find(card => card.id === "legacy").deleted_at);
  for (const id of ["learning", "reviewed", "saved"]) assert.ok(!snapshot.cards.find(card => card.id === id).deleted_at);
});

test("due reviews, timezone day budget, disabled recommendations and queue cap are respected", t => {
  const store = fixture(t, { max_queue_size: 2 });
  store.applyBatch({ cards: [card("due", { due_at: now.toISOString() })] });
  assert.equal(maintainRecommendations(store, catalog, now).generated, 1);
  const disabled = fixture(t, { enable_word_recommendations: false });
  assert.equal(maintainRecommendations(disabled, catalog, now).generated, 0);
  const completed = fixture(t, { daily_word_goal: 1 });
  completed.applyBatch({ recommendation_feedback: [{ id: "f", card_id: "old", lemma: "known", action: "known", created_at: "2026-09-22T15:01:00Z" }] });
  assert.equal(maintainRecommendations(completed, catalog, now).generated, 0);
  assert.equal(maintainRecommendations(completed, catalog, new Date("2026-09-23T15:01:00Z")).generated, 1);
});

test("in-progress recommendations count as due reviews and no recommendation displaces them", t => {
  const store = fixture(t, { daily_word_goal: 1 });
  store.applyBatch({ cards: [card("started", { wordbook: "recommended", recommendation_started_at: now.toISOString(), due_at: now.toISOString() })] });
  assert.equal(maintainRecommendations(store, catalog, now).generated, 0);
});

test("catalogue editing takes effect without a server restart and invalid edits keep the last valid copy", t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "vocab-catalog-"));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const file = path.join(directory, "catalog.json");
  fs.writeFileSync(file, JSON.stringify({ entries: [seed("first")] }));
  const load = createRecommendationCatalogLoader(file);
  assert.equal(load()[0].lemma, "first");
  fs.writeFileSync(file, "{");
  assert.equal(load()[0].lemma, "first");
  fs.writeFileSync(file, JSON.stringify({ entries: [seed("new-server-only-word")] }));
  assert.equal(load()[0].lemma, "new-server-only-word");
  const store = fixture(t);
  maintainRecommendations(store, load(), now);
  assert.equal(waiting(store)[0].lemma, "new-server-only-word");
});

test("production catalogue diversifies single-slot refills and permanently excludes normalized feedback", () => {
  const entries = createRecommendationCatalogLoader()();
  const snapshot = { cards: [], recommendation_feedback: [{ lemma: "  ＳＵＢＴＬＥ  ", action: "known" }] };
  const topics = new Set();
  for (let i = 0; i < 6; i++) {
    const [selected] = chooseRecommendations(entries, snapshot, 1);
    topics.add(selected.topic);
    snapshot.cards.push(card(selected.lemma, { wordbook: "recommended" }));
  }
  assert.equal(topics.size, 6);
  assert.ok(chooseRecommendations(entries, snapshot, 1000).every(seed => seed.lemma !== "subtle"));
});

test("legacy snapshot and incremental clients receive the same server-only word without a new endpoint", async t => {
  const store = fixture(t, { daily_word_goal: 1 });
  let entries = [seed("word-absent-from-bundled-clients")];
  const { server, maintain } = createSyncServer({ db: store.db, token: "test-token", recommendationCatalog: () => entries, now: () => now.getTime() });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); }));
  const url = `http://127.0.0.1:${server.address().port}`;
  const headers = { Authorization: "Bearer test-token", "Content-Type": "application/json" };
  const unauthorized = await fetch(`${url}/sync/snapshot`);
  assert.equal(unauthorized.status, 401);
  assert.equal(waiting(store).length, 0);
  // Reads are side-effect free; the maintenance hook (timer or upload) adds the word.
  await fetch(`${url}/sync/snapshot`, { headers });
  assert.equal(waiting(store).length, 0);
  assert.equal(maintain().generated, 1);
  const [legacy, incremental] = await Promise.all([
    fetch(`${url}/sync/snapshot`, { headers }).then(r => r.json()),
    fetch(`${url}/sync/changes`, { headers }).then(r => r.json())
  ]);
  assert.equal(legacy.cards[0].lemma, "word-absent-from-bundled-clients");
  assert.equal(incremental.cards[0].id, legacy.cards[0].id);
  const item = legacy.cards[0];
  const response = await fetch(`${url}/sync/batch`, { method: "POST", headers, body: JSON.stringify({ recommendation_feedback: [{ id: "feedback-only", card_id: item.id, lemma: item.lemma, action: "known", created_at: now.toISOString() }] }) });
  assert.equal(response.status, 200);
  const delta = await fetch(`${url}/sync/changes?cursor=${encodeURIComponent(incremental.sync_cursor)}`, { headers }).then(r => r.json());
  assert.ok(delta.cards.find(card => card.id === item.id).deleted_at);
  entries = [seed("another-server-only-word")];
  // The daily decision budget prevents new cards until the following day.
  assert.equal(waiting(store).length, 0);
});

test("client device attribution on roundtrip does not turn server cards into legacy suggestions", t => {
  const store = fixture(t);
  maintainRecommendations(store, catalog, now);
  const cards = waiting(store).map(card => ({ ...card, created_device_id: "android-existing", version: card.version+1 }));
  store.applyBatch({ cards });
  const cursor = readChanges(store.db).sync_cursor;
  assert.deepEqual(maintainRecommendations(store, catalog, now), { generated: 0, retired: 0 });
  assert.equal(readChanges(store.db, cursor).cards.length, 0);
});

test("failed recommendation creation rolls back migration tombstones too", t => {
  const store = fixture(t);
  store.applyBatch({ cards: [card("legacy", { wordbook: "recommended" })] });
  const before = store.snapshot();
  assert.throws(() => maintainRecommendations(store, [seed("")], now), /validation/);
  assert.deepEqual(store.snapshot(), before);
});
