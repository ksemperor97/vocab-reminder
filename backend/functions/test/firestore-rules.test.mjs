import assert from "node:assert/strict";
import { after, before, beforeEach, describe, it } from "node:test";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment
} from "@firebase/rules-unit-testing";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import {
  deleteDoc,
  doc,
  getDoc,
  setDoc,
  updateDoc
} from "firebase/firestore";

let testEnv;

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: "vocab-reminder-rules-test",
    firestore: {
      rules: readFileSync(resolve("../firestore.rules"), "utf8")
    }
  });
});

beforeEach(async () => {
  await testEnv.clearFirestore();
});

after(async () => {
  await testEnv.cleanup();
});

describe("Firestore rules", () => {
  it("allows an authenticated user to write their own card", async () => {
    const db = testEnv.authenticatedContext("user-a").firestore();
    await assertSucceeds(
      setDoc(doc(db, "users/user-a/cards/card-1"), {
        lemma: "tentative",
        status: "new"
      })
    );
  });

  it("denies unauthenticated writes", async () => {
    const db = testEnv.unauthenticatedContext().firestore();
    await assertFails(
      setDoc(doc(db, "users/user-a/cards/card-1"), {
        lemma: "tentative",
        status: "new"
      })
    );
  });

  it("denies access to another user's data", async () => {
    const ownerDb = testEnv.authenticatedContext("user-a").firestore();
    const otherDb = testEnv.authenticatedContext("user-b").firestore();

    await assertSucceeds(
      setDoc(doc(ownerDb, "users/user-a/cards/card-1"), {
        lemma: "tentative",
        status: "new"
      })
    );

    await assertFails(getDoc(doc(otherDb, "users/user-a/cards/card-1")));
    await assertFails(
      setDoc(doc(otherDb, "users/user-a/cards/card-2"), {
        lemma: "robust",
        status: "new"
      })
    );
  });

  it("allows review_events create but denies update and delete", async () => {
    const db = testEnv.authenticatedContext("user-a").firestore();
    const eventRef = doc(db, "users/user-a/review_events/event-1");

    await assertSucceeds(
      setDoc(eventRef, {
        card_id: "card-1",
        response: "remembered",
        previous_status: "new",
        next_status: "learning"
      })
    );

    await assertFails(updateDoc(eventRef, { response: "forgotten" }));
    await assertFails(deleteDoc(eventRef));
  });

  it("allows recommendation_feedback create but denies update and delete", async () => {
    const db = testEnv.authenticatedContext("user-a").firestore();
    const feedbackRef = doc(db, "users/user-a/recommendation_feedback/feedback-1");

    await assertSucceeds(
      setDoc(feedbackRef, {
        card_id: "card-1",
        lemma: "ubiquitous",
        action: "known"
      })
    );

    await assertFails(updateDoc(feedbackRef, { action: "dismissed" }));
    await assertFails(deleteDoc(feedbackRef));
  });

  it("denies recommendation_feedback with invalid action", async () => {
    const db = testEnv.authenticatedContext("user-a").firestore();

    await assertFails(
      setDoc(doc(db, "users/user-a/recommendation_feedback/feedback-1"), {
        card_id: "card-1",
        lemma: "ubiquitous",
        action: "maybe"
      })
    );
  });

  it("allows settings/main and denies unknown subcollections", async () => {
    const db = testEnv.authenticatedContext("user-a").firestore();

    await assertSucceeds(
      setDoc(doc(db, "users/user-a/settings/main"), {
        daily_word_goal: 10
      })
    );

    await assertFails(
      setDoc(doc(db, "users/user-a/random/doc-1"), {
        should_not_write: true
      })
    );

    assert.ok(testEnv);
  });
});
