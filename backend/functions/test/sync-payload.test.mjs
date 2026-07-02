import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { HttpsError } from "firebase-functions/v2/https";
import { parseSyncBatchPayload } from "../lib/sync.js";

describe("sync payload parser", () => {
  it("accepts Firestore-shaped collection arrays and settings", () => {
    const parsed = parseSyncBatchPayload({
      cards: [{ id: "card-1", lemma: "tentative" }],
      contexts: [{ id: "context-1", card_id: "card-1" }],
      definition_candidates: [{ id: "candidate-1", card_id: "card-1" }],
      review_events: [{ id: "event-1", card_id: "card-1" }],
      recommendation_feedback: [{ id: "feedback-1", lemma: "ubiquitous" }],
      settings: { daily_word_goal: 10 }
    });

    assert.equal(parsed.cards.length, 1);
    assert.equal(parsed.review_events[0].id, "event-1");
    assert.equal(parsed.settings.daily_word_goal, 10);
  });

  it("rejects entities without client-generated ids", () => {
    assert.throws(
      () => parseSyncBatchPayload({ cards: [{ lemma: "tentative" }] }),
      HttpsError
    );
  });

  it("defaults missing collections to empty arrays", () => {
    const parsed = parseSyncBatchPayload({});
    assert.deepEqual(parsed.cards, []);
    assert.deepEqual(parsed.review_events, []);
    assert.equal(parsed.settings, null);
  });
});
