#!/usr/bin/env node
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { openDatabase } from "../server/src/db.js";
import { createSyncServer } from "../server/src/http.js";

const token = "local-smoke-token";
const root = fs.mkdtempSync(path.join(os.tmpdir(), "vocab-sync-smoke-"));
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

try {
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address();
  const baseUrl = `http://127.0.0.1:${port}`;

  const health = await getJson(`${baseUrl}/health`, token);
  assert.equal(health.status, 200);
  assert.equal(health.body.ok, true);
  assert.equal(health.body.cards, 0);
  console.log("OK: local /health returns 200");

  const wrongToken = await getJson(`${baseUrl}/health`, "wrong-token");
  assert.equal(wrongToken.status, 401);
  assert.equal(wrongToken.body.error.message, "Authentication required.");
  console.log("OK: wrong token returns 401");

  const firstBatch = await postJson(`${baseUrl}/sync/batch`, fullBatch(), token);
  assert.equal(firstBatch.status, 200);
  assert.equal(firstBatch.body.applied.cards, 1);
  assert.equal(firstBatch.body.applied.review_events, 1);
  assert.equal(firstBatch.body.applied.settings, 1);
  console.log("OK: sync batch applies card/event/settings");

  const snapshot = await getJson(`${baseUrl}/sync/snapshot`, token);
  assert.equal(snapshot.status, 200);
  assert.equal(snapshot.body.cards.length, 1);
  assert.equal(snapshot.body.cards[0].lemma, "resilient");
  assert.equal(snapshot.body.settings.daily_word_goal, 8);
  console.log("OK: snapshot returns applied state");

  const phoneSettings = await postJson(`${baseUrl}/sync/batch`, {
    settings: settingsPayload({ daily_word_goal: 12, settings_updated_at: "2026-07-03T02:00:00.000Z" })
  }, token);
  assert.equal(phoneSettings.body.applied.settings, 1);
  const staleMacSettings = await postJson(`${baseUrl}/sync/batch`, {
    settings: settingsPayload({ daily_word_goal: 4, settings_updated_at: "2026-07-03T01:00:00.000Z" })
  }, token);
  assert.equal(staleMacSettings.body.applied.settings, 0);
  const settingsSnapshot = await getJson(`${baseUrl}/sync/snapshot`, token);
  assert.equal(settingsSnapshot.body.settings.daily_word_goal, 12);
  assert.equal(settingsSnapshot.body.settings.settings_updated_at, "2026-07-03T02:00:00.000Z");
  console.log("OK: newer client settings survive stale device push");

  const duplicate = await postJson(`${baseUrl}/sync/batch`, {
    review_events: fullBatch().review_events,
    recommendation_feedback: fullBatch().recommendation_feedback
  }, token);
  assert.equal(duplicate.status, 200);
  assert.equal(duplicate.body.applied.review_events, 0);
  assert.equal(duplicate.body.applied.recommendation_feedback, 0);
  console.log("OK: duplicate append-only entities are idempotent");

  const broken = await postJson(`${baseUrl}/sync/batch`, {
    cards: [{ ...fullBatch().cards[0], id: "bad-card", due_at: "not-a-date" }]
  }, token);
  assert.equal(broken.status, 200);
  assert.equal(broken.body.skipped.cards, 1);
  assert.match(broken.body.skipped.reasons[0].reason, /due_at/);
  console.log("OK: broken records are skipped and reported");

  const backups = fs.readdirSync(backupDir).filter((name) => /^vocab-sync-\d{8}\.sqlite$/.test(name));
  assert.equal(backups.length, 1);
  console.log("OK: daily backup is created");
} finally {
  await new Promise((resolve) => server.close(resolve));
  db.close();
  fs.rmSync(root, { recursive: true, force: true });
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
    daily_word_goal: 8,
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
