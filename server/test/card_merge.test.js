import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import { mergeCards } from "../src/card_merge.js";
import { openDatabase } from "../src/db.js";
import { SyncStore } from "../src/sync.js";

const vectors = JSON.parse(fs.readFileSync(new URL("../../shared/test-vectors/card_merge.json", import.meta.url), "utf8"));

for (const vector of vectors.cases) {
  test(`card merge vector: ${vector.name}`, () => {
    for (const [label, merged] of [
      ["existing<-incoming", mergeCards(vector.existing, vector.incoming)],
      ["incoming<-existing", mergeCards(vector.incoming, vector.existing)]
    ]) {
      for (const [field, value] of Object.entries(vector.expected)) {
        assert.deepEqual(merged[field] ?? null, value, `${label}: ${field}`);
      }
    }
  });
}

function card(id, overrides = {}) {
  return {
    id, lemma: "resilient", display_word: "resilient", wordbook: "user", meaning_ko: "",
    status: "new", due_at: "2026-10-01T00:00:00.000Z", interval_days: 0, lapse_count: 0,
    created_at: "2026-09-01T00:00:00.000Z", updated_at: "2026-09-01T00:00:00.000Z", version: 1,
    ...overrides
  };
}

function storeFixture(t) {
  const db = openDatabase(":memory:");
  t.after(() => db.close());
  return new SyncStore(db);
}

test("server folds same-lemma cards captured on two devices into one survivor", (t) => {
  const store = storeFixture(t);
  store.applyBatch({
    cards: [card("android-card", { meaning_ko: "회복력 있는", created_at: "2026-09-01T00:00:00.000Z" })],
    contexts: [{ id: "ctx-a", card_id: "android-card", source_sentence: "A", capture_method: "android_share", created_at: "2026-09-01T00:00:00.000Z" }]
  });
  const result = store.applyBatch({
    cards: [card("mac-card", { lemma: " Resilient ", status: "review", interval_days: 6, created_at: "2026-09-02T00:00:00.000Z", version: 4 })],
    contexts: [{ id: "ctx-m", card_id: "mac-card", source_sentence: "M", capture_method: "mac_service", created_at: "2026-09-02T00:00:00.000Z" }],
    review_events: [{ id: "ev-m", card_id: "mac-card", device_id: "mac-1", response: "remembered", previous_status: "learning", next_status: "review", client_created_at: "2026-09-02T00:00:00.000Z" }]
  });
  assert.equal(result.folded, 1);

  const snapshot = store.snapshot();
  const active = snapshot.cards.filter((entry) => !entry.deleted_at);
  assert.equal(active.length, 1);
  const survivor = active[0];
  assert.equal(survivor.id, "android-card", "the card with a meaning keeps its id");
  assert.equal(survivor.meaning_ko, "회복력 있는");
  assert.equal(survivor.status, "review", "learning progress from the duplicate is kept");
  assert.equal(survivor.interval_days, 6);
  assert.equal(survivor.version, 5);

  const tombstone = snapshot.cards.find((entry) => entry.id === "mac-card");
  assert.ok(tombstone.deleted_at);
  assert.equal(tombstone.merged_into, "android-card");
  for (const row of [...snapshot.contexts, ...snapshot.review_events]) assert.equal(row.card_id, "android-card");
  assert.equal(snapshot.review_events[0].merged_from_card_id, "mac-card");

  // History captured offline for the folded duplicate still lands on the survivor.
  store.applyBatch({ review_events: [{ id: "ev-late", card_id: "mac-card", device_id: "mac-1", response: "forgotten", previous_status: "review", next_status: "relearning", client_created_at: "2026-09-03T00:00:00.000Z" }] });
  assert.equal(store.snapshot().review_events.find((event) => event.id === "ev-late").card_id, "android-card");

  // Folding is idempotent: a stale active copy of the duplicate cannot reopen it.
  assert.equal(store.applyBatch({ cards: [card("mac-card", { lemma: "resilient", version: 4 })] }).folded, 0);
  assert.equal(store.snapshot().cards.filter((entry) => !entry.deleted_at).length, 1);
});

test("snapshot and changes reads do not write", async (t) => {
  const { createSyncServer } = await import("../src/http.js");
  const store = storeFixture(t);
  const { server } = createSyncServer({ db: store.db, token: "t", recommendationCatalog: () => [] });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise((resolve) => { server.close(resolve); server.closeAllConnections(); }));
  const url = `http://127.0.0.1:${server.address().port}`;
  const before = store.db.prepare("SELECT COUNT(*) AS n FROM sync_changes").get().n;
  for (const path of ["/sync/snapshot", "/sync/changes"]) {
    const response = await fetch(url + path, { headers: { Authorization: "Bearer t" } });
    assert.equal(response.status, 200);
  }
  assert.equal(store.db.prepare("SELECT COUNT(*) AS n FROM sync_changes").get().n, before);
});
