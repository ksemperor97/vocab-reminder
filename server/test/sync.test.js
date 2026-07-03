import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { openDatabase } from "../src/db.js";
import { createSyncServer } from "../src/http.js";

const token = "test-token";

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

test("ignores duplicate append-only events by id", async (t) => {
  const app = await startTestServer(t);
  const batch = fullBatch();
  await postJson(app.url("/sync/batch"), batch, token);
  const duplicate = await postJson(app.url("/sync/batch"), {
    review_events: batch.review_events,
    recommendation_feedback: batch.recommendation_feedback
  }, token);

  assert.equal(duplicate.body.applied.review_events, 0);
  assert.equal(duplicate.body.applied.recommendation_feedback, 0);

  const snapshot = await getJson(app.url("/sync/snapshot"), token);
  assert.equal(snapshot.body.review_events.length, 1);
  assert.equal(snapshot.body.recommendation_feedback.length, 1);
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

async function startTestServer(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "vocab-sync-test-"));
  const dbPath = path.join(root, "vocab-sync.sqlite");
  const backupDir = path.join(root, "backups");
  const db = openDatabase(dbPath);
  const { server } = createSyncServer({
    db,
    dbPath,
    backupDir,
    backupRetention: 30,
    token
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
    url: (pathname) => `http://127.0.0.1:${port}${pathname}`
  };
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
