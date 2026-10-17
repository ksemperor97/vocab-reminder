import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { openDatabase } from "../src/db.js";
import { createSyncServer } from "../src/http.js";

const token = "test-token";
const meaningConflictFixture = JSON.parse(
  fs.readFileSync(new URL("../../shared/test-vectors/meaning_conflicts.json", import.meta.url), "utf8")
);

test("roundtrips a sync batch through the HTTP server and writes a daily backup", async (t) => {
  const app = await startTestServer(t);
  const response = await postJson(app.url("/sync/batch"), fullBatch(), token);

  assert.equal(response.status, 200);
  assert.deepEqual(response.body.applied, {
    cards: 1,
    contexts: 1,
    definition_candidates: 1,
    review_events: 1,
    recommendation_feedback: 1,
    settings: 1
  });

  const snapshot = await getJson(app.url("/sync/snapshot"), token);
  assert.equal(snapshot.status, 200);
  assert.equal(snapshot.body.cards[0].lemma, "resilient");
  assert.equal(snapshot.body.contexts[0].source_sentence, "A resilient system recovers quickly.");
  assert.equal(snapshot.body.settings.daily_word_goal, 8);
  assert.equal(fs.readdirSync(app.backupDir).filter((name) => name.endsWith(".sqlite")).length, 1);
});

test("keeps append-only entities immutable and idempotent by id", async (t) => {
  const app = await startTestServer(t);
  const batch = fullBatch();
  await postJson(app.url("/sync/batch"), batch, token);
  const duplicate = await postJson(app.url("/sync/batch"), {
    definition_candidates: [{
      ...batch.definition_candidates[0],
      raw_text: "must not replace the original",
      parsed_meaning_ko: "변조된 뜻"
    }],
    review_events: batch.review_events,
    recommendation_feedback: batch.recommendation_feedback
  }, token);

  assert.equal(duplicate.body.applied.definition_candidates, 0);
  assert.equal(duplicate.body.applied.review_events, 0);
  assert.equal(duplicate.body.applied.recommendation_feedback, 0);

  const snapshot = await getJson(app.url("/sync/snapshot"), token);
  assert.equal(snapshot.body.definition_candidates.length, 1);
  assert.equal(snapshot.body.definition_candidates[0].parsed_meaning_ko, "회복력 있는");
  assert.equal(snapshot.body.review_events.length, 1);
  assert.equal(snapshot.body.recommendation_feedback.length, 1);
});

test("synthesizes one deterministic meaning candidate for legacy card-only retries", async (t) => {
  const app = await startTestServer(t);
  const legacyCard = cardPayload({
    id: "legacy-card-only",
    meaning_ko: "구버전에서 저장한 뜻",
    definition_en: "saved by a legacy client",
    updated_at: "2026-07-03T04:00:00.000Z",
    version: 2,
    created_device_id: "android-legacy"
  });

  const first = await postJson(app.url("/sync/batch"), { cards: [legacyCard] }, token);
  const firstSnapshot = await getJson(app.url("/sync/snapshot"), token);
  const firstCandidate = firstSnapshot.body.definition_candidates[0];

  assert.equal(first.body.applied.cards, 1);
  assert.equal(first.body.applied.definition_candidates, 1);
  assert.equal(firstCandidate.source, "card_snapshot");
  assert.equal(firstCandidate.parsed_meaning_ko, legacyCard.meaning_ko);
  assert.equal(firstCandidate.origin_device_id, "android-legacy");
  assert.equal(firstCandidate.base_card_version, 1);

  const retry = await postJson(app.url("/sync/batch"), { cards: [legacyCard] }, token);
  const retrySnapshot = await getJson(app.url("/sync/snapshot"), token);
  assert.equal(retry.body.applied.cards, 0);
  assert.equal(retry.body.applied.definition_candidates, 0);
  assert.equal(retrySnapshot.body.definition_candidates.length, 1);
  assert.equal(retrySnapshot.body.definition_candidates[0].id, firstCandidate.id);
});

test("retains both simultaneous offline meanings while the existing card merge picks one primary", async (t) => {
  const app = await startTestServer(t);
  const sharedRevision = cardPayload({
    id: "simultaneous-meaning-edit",
    version: 2,
    updated_at: "2026-07-03T05:00:00.000Z"
  });
  const alphaEdit = {
    ...sharedRevision,
    meaning_ko: "alpha meaning",
    created_device_id: "android-offline"
  };
  const zetaEdit = {
    ...sharedRevision,
    meaning_ko: "zeta meaning",
    created_device_id: "mac-offline"
  };

  await postJson(app.url("/sync/batch"), { cards: [alphaEdit] }, token);
  await postJson(app.url("/sync/batch"), { cards: [zetaEdit] }, token);
  const snapshot = await getJson(app.url("/sync/snapshot"), token);

  assert.equal(snapshot.body.cards[0].meaning_ko, "zeta meaning");
  assert.deepEqual(
    snapshot.body.definition_candidates
      .filter((candidate) => candidate.card_id === sharedRevision.id)
      .map((candidate) => candidate.parsed_meaning_ko)
      .sort(),
    ["alpha meaning", "zeta meaning"]
  );
  assert.deepEqual(
    snapshot.body.definition_candidates
      .filter((candidate) => candidate.card_id === sharedRevision.id)
      .map((candidate) => [candidate.origin_device_id, candidate.base_card_version])
      .sort(),
    [["android-offline", 1], ["mac-offline", 1]]
  );
});

test("accepts legacy candidates without lineage and rejects incomplete lineage", async (t) => {
  const app = await startTestServer(t);
  const legacyCandidate = { ...fullBatch().definition_candidates[0] };
  delete legacyCandidate.origin_device_id;
  delete legacyCandidate.base_card_version;
  const incompleteCandidate = {
    ...legacyCandidate,
    id: "candidate-incomplete-lineage",
    origin_device_id: "android-a"
  };

  const response = await postJson(app.url("/sync/batch"), {
    definition_candidates: [legacyCandidate, incompleteCandidate]
  }, token);

  assert.equal(response.status, 200);
  assert.equal(response.body.applied.definition_candidates, 1);
  assert.equal(response.body.skipped.definition_candidates, 1);
  assert.match(
    response.body.skipped.reasons.map((reason) => reason.reason).join("\n"),
    /provided together/
  );
});

test("matches the shared meaning-conflict merge, export, and restore contract", async (t) => {
  for (const fixtureCase of meaningConflictFixture.cases) {
    const forward = await startTestServer(t);
    const reverse = await startTestServer(t);

    const forwardAndroid = await postJson(forward.url("/sync/batch"), fixtureCase.android_offline, token);
    const forwardMac = await postJson(forward.url("/sync/batch"), fixtureCase.mac_offline, token);
    const reverseMac = await postJson(reverse.url("/sync/batch"), fixtureCase.mac_offline, token);
    const reverseAndroid = await postJson(reverse.url("/sync/batch"), fixtureCase.android_offline, token);

    for (const response of [forwardAndroid, forwardMac, reverseMac, reverseAndroid]) {
      assert.equal(response.status, 200, fixtureCase.name);
    }

    const forwardSnapshot = await getJson(forward.url("/sync/snapshot"), token);
    const reverseSnapshot = await getJson(reverse.url("/sync/snapshot"), token);
    assertMeaningConflictSnapshot(forwardSnapshot.body, fixtureCase.expected, fixtureCase.name);
    assertMeaningConflictSnapshot(reverseSnapshot.body, fixtureCase.expected, fixtureCase.name);

    const currentCard = forwardSnapshot.body.cards.find(
      (card) => card.id === fixtureCase.expected.card_id
    );
    const restoreCandidate = forwardSnapshot.body.definition_candidates.find(
      (candidate) => candidate.id === fixtureCase.expected.restore.candidate_id
    );
    assert.ok(currentCard, `${fixtureCase.name}: merged card must be exported`);
    assert.ok(restoreCandidate, `${fixtureCase.name}: restore candidate must be exported`);

    const restored = await postJson(forward.url("/sync/batch"), {
      cards: [{
        ...currentCard,
        meaning_ko: restoreCandidate.parsed_meaning_ko,
        definition_en: restoreCandidate.parsed_definition_en,
        part_of_speech: restoreCandidate.part_of_speech,
        source_sentence_primary: restoreCandidate.example_sentence,
        definition_source_primary: restoreCandidate.source,
        updated_at: fixtureCase.expected.restore.updated_at,
        content_updated_at: fixtureCase.expected.restore.updated_at,
        content_version: currentCard.content_version + 1,
        version: currentCard.version + 1
      }]
    }, token);
    assert.equal(restored.status, 200, fixtureCase.name);
    assert.equal(restored.body.applied.cards, 1, fixtureCase.name);

    const restoredSnapshot = await getJson(forward.url("/sync/snapshot"), token);
    const restoredCard = restoredSnapshot.body.cards.find(
      (card) => card.id === fixtureCase.expected.card_id
    );
    assert.equal(restoredCard?.meaning_ko, fixtureCase.expected.restore.meaning_ko, fixtureCase.name);
    assert.deepEqual(
      restoredSnapshot.body.definition_candidates
        .filter((candidate) => candidate.card_id === fixtureCase.expected.card_id)
        .map((candidate) => candidate.id)
        .sort(),
      [...fixtureCase.expected.preserved_candidate_ids].sort(),
      fixtureCase.name
    );
  }
});

test("skips broken records without coercing core memory fields", async (t) => {
  const app = await startTestServer(t);
  const valid = fullBatch();
  const brokenCard = { ...valid.cards[0], id: "card-bad-date", due_at: "not-a-date" };
  const brokenEvent = { ...valid.review_events[0], id: "event-bad-response", response: "maybe" };
  const response = await postJson(app.url("/sync/batch"), {
    cards: [valid.cards[0], brokenCard],
    review_events: [valid.review_events[0], brokenEvent]
  }, token);

  assert.equal(response.status, 200);
  assert.equal(response.body.applied.cards, 1);
  assert.equal(response.body.applied.review_events, 1);
  assert.equal(response.body.skipped.cards, 1);
  assert.equal(response.body.skipped.review_events, 1);
  assert.match(response.body.skipped.reasons.map((reason) => reason.reason).join("\n"), /due_at/);
  assert.match(response.body.skipped.reasons.map((reason) => reason.reason).join("\n"), /response/);

  const snapshot = await getJson(app.url("/sync/snapshot"), token);
  assert.deepEqual(snapshot.body.cards.map((card) => card.id), ["card-1"]);
  assert.deepEqual(snapshot.body.review_events.map((event) => event.id), ["event-1"]);
});

test("resolves card conflicts by version before updated_at", async (t) => {
  const app = await startTestServer(t);
  const baseline = cardPayload({
    meaning_ko: "baseline",
    version: 5,
    updated_at: "2026-07-03T02:00:00.000Z"
  });
  await postJson(app.url("/sync/batch"), { cards: [baseline] }, token);

  const lowerVersionWithLaterTimestamp = await postJson(app.url("/sync/batch"), {
    cards: [{
      ...baseline,
      meaning_ko: "must not win",
      version: 4,
      updated_at: "2026-07-03T03:00:00.000Z"
    }]
  }, token);
  assert.equal(lowerVersionWithLaterTimestamp.body.applied.cards, 0);

  const higherVersionWithEarlierTimestamp = await postJson(app.url("/sync/batch"), {
    cards: [{
      ...baseline,
      meaning_ko: "higher version wins",
      version: 6,
      updated_at: "2026-07-03T01:00:00.000100Z"
    }]
  }, token);
  assert.equal(higherVersionWithEarlierTimestamp.body.applied.cards, 1);

  const equalVersionWithLaterTimestamp = await postJson(app.url("/sync/batch"), {
    cards: [{
      ...baseline,
      meaning_ko: "later timestamp wins",
      version: 6,
      updated_at: "2026-07-03T01:00:00.000200Z"
    }]
  }, token);
  assert.equal(equalVersionWithLaterTimestamp.body.applied.cards, 1);

  const snapshot = await getJson(app.url("/sync/snapshot"), token);
  assert.equal(snapshot.body.cards[0].meaning_ko, "later timestamp wins");
  assert.equal(snapshot.body.cards[0].version, 6);
  assert.equal(snapshot.body.cards[0].updated_at, "2026-07-03T01:00:00.000200Z");
});

test("prefers a tombstone on an exact card conflict and prevents equal-tuple resurrection", async (t) => {
  const app = await startTestServer(t);
  const active = cardPayload({
    version: 3,
    updated_at: "2026-07-03T03:00:00.000Z",
    deleted_at: null
  });
  await postJson(app.url("/sync/batch"), { cards: [active] }, token);

  const tombstone = {
    ...active,
    deleted_at: "2026-07-03T03:01:00.000Z"
  };
  const deleted = await postJson(app.url("/sync/batch"), { cards: [tombstone] }, token);
  assert.equal(deleted.body.applied.cards, 1);

  const resurrection = await postJson(app.url("/sync/batch"), { cards: [active] }, token);
  assert.equal(resurrection.body.applied.cards, 0);

  const snapshot = await getJson(app.url("/sync/snapshot"), token);
  assert.equal(snapshot.body.cards[0].deleted_at, tombstone.deleted_at);
});

test("uses a stable payload tie-breaker for exact active card conflicts", async (t) => {
  const first = await startTestServer(t);
  const second = await startTestServer(t);
  const alpha = cardPayload({
    meaning_ko: "alpha",
    version: 9,
    updated_at: "2026-07-03T03:00:00.000123Z",
    deleted_at: null
  });
  const zeta = { ...alpha, meaning_ko: "zeta" };

  await postJson(first.url("/sync/batch"), { cards: [alpha] }, token);
  await postJson(first.url("/sync/batch"), { cards: [zeta] }, token);
  await postJson(second.url("/sync/batch"), { cards: [zeta] }, token);
  await postJson(second.url("/sync/batch"), { cards: [alpha] }, token);

  const firstSnapshot = await getJson(first.url("/sync/snapshot"), token);
  const secondSnapshot = await getJson(second.url("/sync/snapshot"), token);
  assert.equal(firstSnapshot.body.cards[0].meaning_ko, "zeta");
  assert.equal(secondSnapshot.body.cards[0].meaning_ko, "zeta");
});

test("roundtrips recommendation_started_at and validates its timestamp", async (t) => {
  const app = await startTestServer(t);
  const startedAt = "2026-07-03T03:04:05.123456Z";
  const accepted = await postJson(app.url("/sync/batch"), {
    cards: [cardPayload({ recommendation_started_at: startedAt })]
  }, token);
  assert.equal(accepted.status, 200);
  assert.equal(accepted.body.applied.cards, 1);

  const snapshot = await getJson(app.url("/sync/snapshot"), token);
  assert.equal(snapshot.body.cards[0].recommendation_started_at, startedAt);

  const rejected = await postJson(app.url("/sync/batch"), {
    cards: [cardPayload({ id: "invalid-started-at", recommendation_started_at: "not-a-date" })]
  }, token);
  assert.equal(rejected.body.skipped.cards, 1);
  assert.match(rejected.body.skipped.reasons[0].reason, /recommendation_started_at/);
});

test("assigns review event server_created_at at receipt and stores it in the row", async (t) => {
  const app = await startTestServer(t);
  const base = fullBatch().review_events[0];
  const spoofed = {
    ...base,
    id: "event-spoofed-time",
    server_created_at: "2099-01-01T00:00:00.000Z"
  };
  const withoutServerTime = { ...base, id: "event-without-server-time" };
  delete withoutServerTime.server_created_at;

  const before = Date.now();
  const response = await postJson(app.url("/sync/batch"), {
    review_events: [spoofed, withoutServerTime]
  }, token);
  const after = Date.now();

  assert.equal(response.status, 200);
  assert.equal(response.body.applied.review_events, 2);

  const snapshot = await getJson(app.url("/sync/snapshot"), token);
  for (const event of snapshot.body.review_events) {
    const receivedAt = Date.parse(event.server_created_at);
    assert.ok(receivedAt >= before && receivedAt <= after);
    assert.notEqual(event.server_created_at, spoofed.server_created_at);

    const row = app.db.prepare("SELECT created_at, body_json FROM review_events WHERE id = ?").get(event.id);
    assert.equal(row.created_at, event.server_created_at);
    assert.equal(JSON.parse(row.body_json).server_created_at, event.server_created_at);
  }
});

for (const collectionSize of [401, 5_000]) {
  test(`accepts a ${collectionSize}-item append-only collection`, async (t) => {
    const app = await startTestServer(t);
    const response = await postJson(app.url("/sync/batch"), {
      review_events: reviewEventPayloads(collectionSize)
    }, token);

    assert.equal(response.status, 200);
    assert.equal(response.body.applied.review_events, collectionSize);
    assert.equal(response.body.skipped.review_events, 0);
  });
}

test("rejects collections above the default 10000-item safety limit", async (t) => {
  const app = await startTestServer(t);
  const response = await postJson(app.url("/sync/batch"), {
    contexts: Array.from({ length: 10_001 }, () => ({}))
  }, token);

  assert.equal(response.status, 400);
  assert.match(response.body.error.message, /10000-item sync batch limit/);
});

test("enforces the configured HTTP request body limit", async (t) => {
  const app = await startTestServer(t, { maxBodyBytes: 1_024 });
  const response = await postJson(app.url("/sync/batch"), {
    padding: "x".repeat(2_048)
  }, token);

  assert.equal(response.status, 413);
  assert.equal(response.body.error.message, "Request body is too large.");
});

test("rejects requests with a wrong bearer token", async (t) => {
  const app = await startTestServer(t);
  const response = await getJson(app.url("/sync/snapshot"), "wrong-token");

  assert.equal(response.status, 401);
  assert.equal(response.body.error.message, "Authentication required.");
});

test("keeps settings unchanged when the next batch sends settings null", async (t) => {
  const app = await startTestServer(t);
  await postJson(app.url("/sync/batch"), { settings: settingsPayload({ daily_word_goal: 8 }) }, token);
  await postJson(app.url("/sync/batch"), { settings: null }, token);

  const snapshot = await getJson(app.url("/sync/snapshot"), token);
  assert.equal(snapshot.body.settings.daily_word_goal, 8);
});

test("applies settings only when the incoming settings timestamp is newer", async (t) => {
  const app = await startTestServer(t);
  const older = "2026-07-03T00:00:00.000Z";
  const newer = "2026-07-03T01:00:00.000Z";

  const first = await postJson(app.url("/sync/batch"), {
    settings: settingsPayload({ daily_word_goal: 8, settings_updated_at: older })
  }, token);
  const second = await postJson(app.url("/sync/batch"), {
    settings: settingsPayload({ daily_word_goal: 9, settings_updated_at: newer })
  }, token);

  assert.equal(first.body.applied.settings, 1);
  assert.equal(second.body.applied.settings, 1);

  const snapshot = await getJson(app.url("/sync/snapshot"), token);
  assert.equal(snapshot.body.settings.daily_word_goal, 9);
  assert.equal(snapshot.body.settings.settings_updated_at, newer);
});

test("ignores older or equal settings timestamps", async (t) => {
  const app = await startTestServer(t);
  const savedAt = "2026-07-03T01:00:00.000Z";
  await postJson(app.url("/sync/batch"), {
    settings: settingsPayload({ daily_word_goal: 9, settings_updated_at: savedAt })
  }, token);

  const older = await postJson(app.url("/sync/batch"), {
    settings: settingsPayload({ daily_word_goal: 5, settings_updated_at: "2026-07-03T00:59:59.000Z" })
  }, token);
  const equal = await postJson(app.url("/sync/batch"), {
    settings: settingsPayload({ daily_word_goal: 6, settings_updated_at: savedAt })
  }, token);

  assert.equal(older.body.applied.settings, 0);
  assert.equal(equal.body.applied.settings, 0);

  const snapshot = await getJson(app.url("/sync/snapshot"), token);
  assert.equal(snapshot.body.settings.daily_word_goal, 9);
  assert.equal(snapshot.body.settings.settings_updated_at, savedAt);
});

test("legacy settings payloads only win while the saved settings are legacy too", async (t) => {
  const app = await startTestServer(t);

  const legacyFirst = await postJson(app.url("/sync/batch"), {
    settings: settingsPayload({ daily_word_goal: 8 })
  }, token);
  const legacySecond = await postJson(app.url("/sync/batch"), {
    settings: settingsPayload({ daily_word_goal: 9 })
  }, token);
  assert.equal(legacyFirst.body.applied.settings, 1);
  assert.equal(legacySecond.body.applied.settings, 1);

  const timestamped = await postJson(app.url("/sync/batch"), {
    settings: settingsPayload({ daily_word_goal: 10, settings_updated_at: "2026-07-03T02:00:00.000Z" })
  }, token);
  const staleLegacy = await postJson(app.url("/sync/batch"), {
    settings: settingsPayload({ daily_word_goal: 3 })
  }, token);

  assert.equal(timestamped.body.applied.settings, 1);
  assert.equal(staleLegacy.body.applied.settings, 0);

  const snapshot = await getJson(app.url("/sync/snapshot"), token);
  assert.equal(snapshot.body.settings.daily_word_goal, 10);
  assert.equal(snapshot.body.settings.settings_updated_at, "2026-07-03T02:00:00.000Z");
});

async function startTestServer(t, serverOptions = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "vocab-sync-test-"));
  const dbPath = path.join(root, "vocab-sync.sqlite");
  const backupDir = path.join(root, "backups");
  const db = openDatabase(dbPath);
  const { server } = createSyncServer({
    recommendationCatalog: null,
    db,
    dbPath,
    backupDir,
    backupRetention: 30,
    token,
    ...serverOptions
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(async () => {
    await new Promise((resolve) => server.close(resolve));
    db.close();
    fs.rmSync(root, { recursive: true, force: true });
  });
  const { port } = server.address();
  return {
    backupDir,
    db,
    url: (pathname) => `http://127.0.0.1:${port}${pathname}`
  };
}

function assertMeaningConflictSnapshot(snapshot, expected, name) {
  const card = snapshot.cards.find((item) => item.id === expected.card_id);
  const candidates = snapshot.definition_candidates.filter(
    (candidate) => candidate.card_id === expected.card_id
  );
  assert.equal(card?.meaning_ko, expected.primary.meaning_ko, name);
  assert.deepEqual(
    candidates.map((candidate) => candidate.id).sort(),
    [...expected.preserved_candidate_ids].sort(),
    name
  );
  assert.equal(
    candidates.find((candidate) => candidate.id === expected.primary.candidate_id)?.parsed_meaning_ko,
    expected.primary.meaning_ko,
    name
  );
  assert.deepEqual(
    candidates.map((candidate) => candidate.id).filter((id) =>
      candidates.some((candidate) =>
        candidate.id === id && candidate.origin_device_id && candidate.base_card_version
      )
    ).sort(),
    [...expected.conflict_candidate_ids].sort(),
    name
  );
}

async function postJson(url, body, bearerToken) {
  const response = await fetch(url, {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${bearerToken}`,
      "Content-Type": "application/json; charset=utf-8"
    },
    body: JSON.stringify(body)
  });
  return {
    status: response.status,
    body: await response.json()
  };
}

async function getJson(url, bearerToken) {
  const response = await fetch(url, {
    headers: {
      "Authorization": `Bearer ${bearerToken}`
    }
  });
  return {
    status: response.status,
    body: await response.json()
  };
}

function fullBatch() {
  return {
    cards: [
      {
        id: "card-1",
        lemma: "resilient",
        display_word: "resilient",
        language: "en",
        wordbook: "user",
        meaning_ko: "회복력 있는",
        definition_en: "Able to recover quickly.",
        part_of_speech: "adjective",
        phonetic: null,
        audio_url: null,
        source_sentence_primary: "A resilient system recovers quickly.",
        source_url_primary: null,
        source_app_primary: "test",
        definition_source_primary: "user",
        status: "new",
        due_at: "2026-07-03T00:00:00.000Z",
        interval_days: 0,
        ease: 2.5,
        stability: null,
        difficulty: null,
        lapse_count: 0,
        interval_before_lapse: null,
        deleted_at: null,
        created_device_id: "test-device",
        created_at: "2026-07-03T00:00:00.000Z",
        updated_at: "2026-07-03T00:00:00.000Z",
        version: 1
      }
    ],
    contexts: [
      {
        id: "context-1",
        card_id: "card-1",
        source_sentence: "A resilient system recovers quickly.",
        surrounding_text: null,
        source_url: null,
        page_title: null,
        source_app: "test",
        capture_method: "manual",
        created_device_id: "test-device",
        created_at: "2026-07-03T00:00:00.000Z"
      }
    ],
    definition_candidates: [
      {
        id: "candidate-1",
        card_id: "card-1",
        source: "user",
        raw_text: "Able to recover quickly.",
        parsed_meaning_ko: "회복력 있는",
        parsed_definition_en: "Able to recover quickly.",
        part_of_speech: "adjective",
        phonetic: null,
        example_sentence: null,
        confidence: 1,
        selected_as_primary: true,
        origin_device_id: "test-device",
        base_card_version: 1,
        created_at: "2026-07-03T00:00:00.000Z"
      }
    ],
    review_events: [
      {
        id: "event-1",
        card_id: "card-1",
        device_id: "test-device",
        response: "remembered",
        previous_status: "new",
        next_status: "review",
        previous_due_at: "2026-07-03T00:00:00.000Z",
        next_due_at: "2026-07-04T00:00:00.000Z",
        previous_interval_days: 0,
        next_interval_days: 1,
        client_created_at: "2026-07-03T00:00:00.000Z",
        server_created_at: "2026-07-03T00:00:00.000Z",
        metadata: {}
      }
    ],
    recommendation_feedback: [
      {
        id: "feedback-1",
        card_id: "card-1",
        lemma: "resilient",
        action: "started",
        created_at: "2026-07-03T00:00:00.000Z"
      }
    ],
    settings: settingsPayload({ daily_word_goal: 8 })
  };
}

function cardPayload(overrides = {}) {
  return {
    ...fullBatch().cards[0],
    ...overrides
  };
}

function reviewEventPayloads(count) {
  const base = fullBatch().review_events[0];
  return Array.from({ length: count }, (_, index) => ({
    ...base,
    id: `event-bulk-${index}`
  }));
}

function settingsPayload(overrides = {}) {
  return {
    max_queue_size: 20,
    snooze_duration_minutes: 60,
    daily_word_goal: 10,
    quiet_hours_start: "23:00",
    quiet_hours_end: "09:00",
    timezone: "Asia/Seoul",
    enable_android_notifications: true,
    enable_mac_notifications: true,
    show_meaning_on_lockscreen: false,
    show_meaning_after_remembered: true,
    auto_generate_korean_meaning: true,
    save_source_url: true,
    save_surrounding_text: false,
    enable_word_recommendations: true,
    recommendations_introduced_at: null,
    ...overrides
  };
}

test("changes endpoint authenticates, advertises protocol, and exposes incremental edits", async t => {
  const app = await startTestServer(t);
  assert.equal((await getJson(app.url("/sync/changes"), "wrong")).status, 401);
  const push = await postJson(app.url("/sync/batch"), fullBatch(), token);
  assert.equal(push.body.sync_protocol, 3);
  const first = await getJson(app.url("/sync/changes?limit=400"), token);
  assert.equal(first.status, 200);
  assert.equal(first.body.cards.length, 1);
  assert.equal(first.body.has_more, false);
  const next = await getJson(app.url(`/sync/changes?cursor=${encodeURIComponent(first.body.sync_cursor)}`), token);
  assert.equal(next.body.cards.length, 0);
  assert.equal(next.body.review_events.length, 0);
  assert.equal((await getJson(app.url("/sync/changes?limit=0"), token)).status, 400);
});
