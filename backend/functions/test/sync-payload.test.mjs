import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { HttpsError } from "firebase-functions/v2/https";
import { parseSyncBatchPayload, stripServerFields } from "../lib/sync.js";

describe("sync payload parser", () => {
  it("accepts Firestore-shaped collection arrays and settings", () => {
    const parsed = parseSyncBatchPayload({
      cards: [{ id: "card-1", lemma: "tentative" }],
      contexts: [{ id: "context-1", card_id: "card-1" }],
      definition_candidates: [{ id: "candidate-1", card_id: "card-1" }],
      review_events: [{ id: "event-1", card_id: "card-1" }],
      recommendation_feedback: [{ id: "feedback-1", card_id: "card-1", lemma: "ubiquitous", action: "known", created_at: "2026-07-02T00:00:00Z" }],
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

  it("rejects invalid recommendation feedback actions", () => {
    assert.throws(
      () => parseSyncBatchPayload({
        recommendation_feedback: [
          { id: "feedback-1", card_id: "card-1", lemma: "ubiquitous", action: "maybe", created_at: "2026-07-02T00:00:00Z" }
        ]
      }),
      HttpsError
    );
  });

  it("rejects recommendation feedback without required card fields", () => {
    const baseFeedback = {
      id: "feedback-1",
      card_id: "card-1",
      lemma: "ubiquitous",
      action: "known",
      created_at: "2026-07-02T00:00:00Z"
    };

    for (const field of ["card_id", "lemma", "created_at"]) {
      const missingField = { ...baseFeedback };
      delete missingField[field];
      assert.throws(
        () => parseSyncBatchPayload({ recommendation_feedback: [missingField] }),
        HttpsError
      );

      assert.throws(
        () => parseSyncBatchPayload({
          recommendation_feedback: [{ ...baseFeedback, [field]: "  " }]
        }),
        HttpsError
      );
    }
  });

  it("rejects recommendation feedback with invalid created_at timestamps", () => {
    assert.throws(
      () => parseSyncBatchPayload({
        recommendation_feedback: [
          { id: "feedback-1", card_id: "card-1", lemma: "ubiquitous", action: "known", created_at: "not a date" }
        ]
      }),
      HttpsError
    );
  });

  it("defaults missing collections to empty arrays", () => {
    const parsed = parseSyncBatchPayload({});
    assert.deepEqual(parsed.cards, []);
    assert.deepEqual(parsed.review_events, []);
    assert.equal(parsed.settings, null);
  });

  it("strips server-only fields from snapshot payloads", () => {
    assert.deepEqual(
      stripServerFields({
        id: "card-1",
        lemma: "tentative",
        user_id: "user-a",
        server_created_at: { seconds: 1 },
        server_updated_at: { seconds: 2 }
      }),
      { id: "card-1", lemma: "tentative" }
    );
  });
});
