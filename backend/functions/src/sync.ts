import {
  FieldValue,
  getFirestore,
  type DocumentReference,
  type SetOptions,
  type WriteBatch
} from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";

type Entity = Record<string, unknown> & { id?: unknown };

export type SyncBatchPayload = {
  cards?: Entity[];
  contexts?: Entity[];
  definition_candidates?: Entity[];
  review_events?: Entity[];
  recommendation_feedback?: Entity[];
  settings?: Entity | null;
};

export type SyncBatchResult = {
  cards: number;
  contexts: number;
  definition_candidates: number;
  review_events: number;
  recommendation_feedback: number;
  settings: number;
};

const writableCollections = [
  "cards",
  "contexts",
  "definition_candidates",
  "recommendation_feedback"
] as const;

export function parseSyncBatchPayload(data: unknown): Required<SyncBatchPayload> {
  if (!data || typeof data !== "object" || Array.isArray(data)) {
    throw new HttpsError("invalid-argument", "Sync payload must be an object.");
  }

  const input = data as SyncBatchPayload;
  return {
    cards: parseEntityArray(input.cards, "cards"),
    contexts: parseEntityArray(input.contexts, "contexts"),
    definition_candidates: parseEntityArray(input.definition_candidates, "definition_candidates"),
    review_events: parseEntityArray(input.review_events, "review_events"),
    recommendation_feedback: parseEntityArray(input.recommendation_feedback, "recommendation_feedback"),
    settings: input.settings == null ? null : parseSettings(input.settings)
  };
}

export async function applySyncBatch(uid: string, data: unknown): Promise<SyncBatchResult> {
  const payload = parseSyncBatchPayload(data);
  const db = getFirestore();
  const userRef = db.collection("users").doc(uid);
  const result: SyncBatchResult = {
    cards: 0,
    contexts: 0,
    definition_candidates: 0,
    review_events: 0,
    recommendation_feedback: 0,
    settings: 0
  };

  let batch: WriteBatch = db.batch();
  let pendingWrites = 0;
  const commitPending = async () => {
    if (pendingWrites === 0) return;
    await batch.commit();
    batch = db.batch();
    pendingWrites = 0;
  };
  const enqueueSet = async (ref: DocumentReference, entity: Entity, options?: SetOptions) => {
    const payloadWithServerFields = withServerFields(entity, uid);
    if (options) {
      batch.set(ref, payloadWithServerFields, options);
    } else {
      batch.set(ref, payloadWithServerFields);
    }
    pendingWrites += 1;
    if (pendingWrites >= 400) {
      await commitPending();
    }
  };

  for (const collection of writableCollections) {
    for (const entity of payload[collection]) {
      const id = entity.id as string;
      await enqueueSet(userRef.collection(collection).doc(id), entity);
      result[collection] += 1;
    }
  }
  if (payload.settings) {
    await enqueueSet(userRef.collection("settings").doc("main"), payload.settings, { merge: true });
    result.settings = 1;
  }
  await commitPending();

  // guide 12.2/12.3: review_events are append-only and idempotent by client UUID.
  for (const event of payload.review_events) {
    const id = event.id as string;
    const eventRef = userRef.collection("review_events").doc(id);
    const snapshot = await eventRef.get();
    if (!snapshot.exists) {
      await eventRef.create(withServerFields(event, uid));
      result.review_events += 1;
    }
  }

  return result;
}

function parseEntityArray(value: unknown, name: string): Entity[] {
  if (value == null) return [];
  if (!Array.isArray(value)) {
    throw new HttpsError("invalid-argument", `${name} must be an array.`);
  }
  if (value.length > 400) {
    throw new HttpsError("invalid-argument", `${name} has too many items for one sync batch.`);
  }
  return value.map((item, index) => parseEntity(item, `${name}[${index}]`));
}

function parseSettings(value: unknown): Entity {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new HttpsError("invalid-argument", "settings must be an object.");
  }
  return value as Entity;
}

function parseEntity(value: unknown, label: string): Entity {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new HttpsError("invalid-argument", `${label} must be an object.`);
  }
  const entity = value as Entity;
  if (typeof entity.id !== "string" || entity.id.trim() === "") {
    throw new HttpsError("invalid-argument", `${label}.id is required.`);
  }
  return entity;
}

function withServerFields(entity: Entity, uid: string): Entity {
  return {
    ...entity,
    user_id: uid,
    server_created_at: entity.server_created_at ?? FieldValue.serverTimestamp(),
    server_updated_at: FieldValue.serverTimestamp()
  };
}
