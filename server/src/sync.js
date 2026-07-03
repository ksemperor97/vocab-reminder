const cardStatuses = new Set(["new", "learning", "review", "relearning", "mastered", "suspended"]);
const wordbooks = new Set(["user", "recommended"]);
const captureMethods = new Set([
  "mac_service",
  "mac_clipboard_hotkey",
  "android_process_text",
  "android_share",
  "manual",
  "recommendation"
]);
const reviewResponses = new Set([
  "remembered",
  "forgotten",
  "unavailable",
  "reveal",
  "manual_edit",
  "suspend",
  "restore"
]);
const feedbackActions = new Set(["started", "known", "dismissed"]);
const quietHoursPattern = /^([01][0-9]|2[0-3]):[0-5][0-9]$/;

const emptyApplied = {
  cards: 0,
  contexts: 0,
  definition_candidates: 0,
  review_events: 0,
  recommendation_feedback: 0,
  settings: 0
};

const emptySkipped = {
  cards: 0,
  contexts: 0,
  definition_candidates: 0,
  review_events: 0,
  recommendation_feedback: 0,
  settings: 0,
  reasons: []
};

export class SyncStore {
  constructor(db) {
    this.db = db;
    this.statements = {
      cardCount: db.prepare("SELECT COUNT(*) AS count FROM cards WHERE deleted_at IS NULL"),
      get: new Map(),
      insert: new Map(),
      insertIgnore: new Map(),
      replace: new Map(),
      all: new Map(),
      upsertSettings: db.prepare(`
        INSERT INTO settings (id, body_json, updated_at)
        VALUES ('main', @body_json, @updated_at)
        ON CONFLICT(id) DO UPDATE SET body_json = excluded.body_json, updated_at = excluded.updated_at
      `),
      getSettings: db.prepare("SELECT body_json FROM settings WHERE id = 'main'")
    };

    for (const table of ["cards", "contexts", "definition_candidates", "review_events", "recommendation_feedback"]) {
      this.statements.get.set(table, db.prepare(`SELECT * FROM ${table} WHERE id = ?`));
      this.statements.insert.set(table, db.prepare(`
        INSERT INTO ${table}
          (id, body_json, created_at, updated_at, updated_at_ms, version, deleted_at, deleted_at_ms)
        VALUES
          (@id, @body_json, @created_at, @updated_at, @updated_at_ms, @version, @deleted_at, @deleted_at_ms)
      `));
      this.statements.insertIgnore.set(table, db.prepare(`
        INSERT OR IGNORE INTO ${table}
          (id, body_json, created_at, updated_at, updated_at_ms, version, deleted_at, deleted_at_ms)
        VALUES
          (@id, @body_json, @created_at, @updated_at, @updated_at_ms, @version, @deleted_at, @deleted_at_ms)
      `));
      this.statements.replace.set(table, db.prepare(`
        UPDATE ${table}
        SET body_json = @body_json,
            created_at = @created_at,
            updated_at = @updated_at,
            updated_at_ms = @updated_at_ms,
            version = @version,
            deleted_at = @deleted_at,
            deleted_at_ms = @deleted_at_ms
        WHERE id = @id
      `));
      this.statements.all.set(table, db.prepare(`SELECT body_json FROM ${table} ORDER BY created_at, id`));
    }
  }

  health() {
    return {
      ok: true,
      serverTime: new Date().toISOString(),
      cards: this.statements.cardCount.get().count
    };
  }

  applyBatch(rawPayload) {
    const payload = parseTopLevelPayload(rawPayload);
    const result = {
      applied: { ...emptyApplied },
      skipped: { ...emptySkipped, reasons: [] }
    };

    const tx = this.db.transaction(() => {
      this.applyCards(payload.cards, result);
      this.insertOnly("contexts", payload.contexts, validateContext, result);
      this.insertOnly("definition_candidates", payload.definition_candidates, validateDefinitionCandidate, result);
      this.insertOnly("review_events", payload.review_events, validateReviewEvent, result);
      this.insertOnly("recommendation_feedback", payload.recommendation_feedback, validateRecommendationFeedback, result);
      this.applySettings(payload.settings, result);
    });
    tx();
    return result;
  }

  snapshot() {
    return {
      cards: this.allEntities("cards"),
      contexts: this.allEntities("contexts"),
      definition_candidates: this.allEntities("definition_candidates"),
      review_events: this.allEntities("review_events"),
      recommendation_feedback: this.allEntities("recommendation_feedback"),
      settings: this.settings()
    };
  }

  allEntities(table) {
    return this.statements.all.get(table).all().map((row) => JSON.parse(row.body_json));
  }

  settings() {
    const row = this.statements.getSettings.get();
    return row ? JSON.parse(row.body_json) : null;
  }

  applyCards(cards, result) {
    for (let index = 0; index < cards.length; index += 1) {
      const validation = validateCard(cards[index]);
      if (!validation.ok) {
        skip(result, "cards", index, validation.reason, cards[index]);
        continue;
      }
      const entity = validation.entity;
      const row = rowFromEntity(entity, "cards");
      const existing = this.statements.get.get("cards").get(entity.id);
      if (!existing) {
        this.statements.insert.get("cards").run(row);
        result.applied.cards += 1;
        continue;
      }
      if (isIncomingCardNewer(row, existing)) {
        this.statements.replace.get("cards").run(row);
        result.applied.cards += 1;
      }
    }
  }

  insertOnly(table, entities, validator, result) {
    for (let index = 0; index < entities.length; index += 1) {
      const validation = validator(entities[index]);
      if (!validation.ok) {
        skip(result, table, index, validation.reason, entities[index]);
        continue;
      }
      const info = this.statements.insertIgnore.get(table).run(rowFromEntity(validation.entity, table));
      if (info.changes > 0) result.applied[table] += 1;
    }
  }

  applySettings(settings, result) {
    if (settings == null) return;
    const validation = validateSettings(settings);
    if (!validation.ok) {
      skip(result, "settings", 0, validation.reason, settings);
      return;
    }
    const existing = this.settings();
    if (!shouldApplySettings(validation.entity, existing)) {
      return;
    }
    this.statements.upsertSettings.run({
      body_json: JSON.stringify(validation.entity),
      updated_at: validation.entity.settings_updated_at ?? new Date().toISOString()
    });
    result.applied.settings = 1;
  }
}

function parseTopLevelPayload(rawPayload) {
  if (!rawPayload || typeof rawPayload !== "object" || Array.isArray(rawPayload)) {
    throw invalidPayload("Sync payload must be an object.");
  }
  return {
    cards: parseEntityArray(rawPayload.cards, "cards"),
    contexts: parseEntityArray(rawPayload.contexts, "contexts"),
    definition_candidates: parseEntityArray(rawPayload.definition_candidates, "definition_candidates"),
    review_events: parseEntityArray(rawPayload.review_events, "review_events"),
    recommendation_feedback: parseEntityArray(rawPayload.recommendation_feedback, "recommendation_feedback"),
    settings: rawPayload.settings == null ? null : rawPayload.settings
  };
}

function parseEntityArray(value, name) {
  if (value == null) return [];
  if (!Array.isArray(value)) throw invalidPayload(`${name} must be an array.`);
  if (value.length > 400) throw invalidPayload(`${name} has too many items for one sync batch.`);
  return value;
}

function validateCard(value) {
  const entity = objectEntity(value);
  if (!entity.ok) return entity;
  const required = [
    "id",
    "lemma",
    "display_word",
    "status",
    "due_at",
    "created_at",
    "updated_at",
    "version"
  ];
  const missing = missingString(entity.entity, required.filter((field) => field !== "version"));
  if (missing) return bad(`${missing} is required.`);
  if (!Number.isInteger(entity.entity.version) || entity.entity.version < 1) return bad("version must be a positive integer.");
  if (entity.entity.wordbook != null && !wordbooks.has(entity.entity.wordbook)) return bad("wordbook is invalid.");
  if (!cardStatuses.has(entity.entity.status)) return bad("status is invalid.");
  for (const field of ["due_at", "created_at", "updated_at"]) {
    if (!validDate(entity.entity[field])) return bad(`${field} must be an ISO timestamp.`);
  }
  if (entity.entity.deleted_at != null && !validDate(entity.entity.deleted_at)) {
    return bad("deleted_at must be an ISO timestamp or null.");
  }
  return entity;
}

function validateContext(value) {
  const entity = objectEntity(value);
  if (!entity.ok) return entity;
  const missing = missingString(entity.entity, ["id", "card_id", "source_sentence", "capture_method", "created_at"]);
  if (missing) return bad(`${missing} is required.`);
  if (!captureMethods.has(entity.entity.capture_method)) return bad("capture_method is invalid.");
  if (!validDate(entity.entity.created_at)) return bad("created_at must be an ISO timestamp.");
  return entity;
}

function validateDefinitionCandidate(value) {
  const entity = objectEntity(value);
  if (!entity.ok) return entity;
  const missing = missingString(entity.entity, ["id", "card_id", "source", "raw_text", "created_at"]);
  if (missing) return bad(`${missing} is required.`);
  if (typeof entity.entity.selected_as_primary !== "boolean") return bad("selected_as_primary must be boolean.");
  if (entity.entity.confidence != null && typeof entity.entity.confidence !== "number") return bad("confidence must be a number or null.");
  if (!validDate(entity.entity.created_at)) return bad("created_at must be an ISO timestamp.");
  return entity;
}

function validateReviewEvent(value) {
  const entity = objectEntity(value);
  if (!entity.ok) return entity;
  const missing = missingString(entity.entity, [
    "id",
    "card_id",
    "device_id",
    "response",
    "previous_status",
    "next_status",
    "client_created_at",
    "server_created_at"
  ]);
  if (missing) return bad(`${missing} is required.`);
  if (!reviewResponses.has(entity.entity.response)) return bad("response is invalid.");
  if (!cardStatuses.has(entity.entity.previous_status)) return bad("previous_status is invalid.");
  if (!cardStatuses.has(entity.entity.next_status)) return bad("next_status is invalid.");
  for (const field of ["client_created_at", "server_created_at"]) {
    if (!validDate(entity.entity[field])) return bad(`${field} must be an ISO timestamp.`);
  }
  for (const field of ["previous_due_at", "next_due_at"]) {
    if (entity.entity[field] != null && !validDate(entity.entity[field])) return bad(`${field} must be an ISO timestamp or null.`);
  }
  return entity;
}

function validateRecommendationFeedback(value) {
  const entity = objectEntity(value);
  if (!entity.ok) return entity;
  const missing = missingString(entity.entity, ["id", "card_id", "lemma", "action", "created_at"]);
  if (missing) return bad(`${missing} is required.`);
  if (!feedbackActions.has(entity.entity.action)) return bad("action is invalid.");
  if (!validDate(entity.entity.created_at)) return bad("created_at must be an ISO timestamp.");
  return entity;
}

function validateSettings(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return bad("settings must be an object.");
  const entity = { ok: true, entity: value };
  const requiredNumbers = ["max_queue_size", "snooze_duration_minutes", "daily_word_goal"];
  for (const field of requiredNumbers) {
    if (!Number.isInteger(entity.entity[field]) || entity.entity[field] < 1) return bad(`${field} must be a positive integer.`);
  }
  const missing = missingString(entity.entity, ["quiet_hours_start", "quiet_hours_end", "timezone"]);
  if (missing) return bad(`${missing} is required.`);
  if (!quietHoursPattern.test(entity.entity.quiet_hours_start)) return bad("quiet_hours_start is invalid.");
  if (!quietHoursPattern.test(entity.entity.quiet_hours_end)) return bad("quiet_hours_end is invalid.");
  const requiredBooleans = [
    "enable_android_notifications",
    "enable_mac_notifications",
    "show_meaning_on_lockscreen",
    "show_meaning_after_remembered",
    "auto_generate_korean_meaning",
    "save_source_url",
    "save_surrounding_text",
    "enable_word_recommendations"
  ];
  for (const field of requiredBooleans) {
    if (typeof entity.entity[field] !== "boolean") return bad(`${field} must be boolean.`);
  }
  if (entity.entity.recommendations_introduced_at != null && !validDate(entity.entity.recommendations_introduced_at)) {
    return bad("recommendations_introduced_at must be an ISO timestamp or null.");
  }
  if (entity.entity.settings_updated_at != null && !validDate(entity.entity.settings_updated_at)) {
    return bad("settings_updated_at must be an ISO timestamp.");
  }
  return entity;
}

function shouldApplySettings(incoming, existing) {
  if (existing == null) return true;
  const incomingTimestamp = settingsTimestamp(incoming);
  const existingTimestamp = settingsTimestamp(existing);
  if (incomingTimestamp != null && existingTimestamp != null) {
    return incomingTimestamp > existingTimestamp;
  }
  if (incomingTimestamp != null) return true;
  if (existingTimestamp != null) return false;
  return true;
}

function settingsTimestamp(settings) {
  const value = settings?.settings_updated_at;
  return validDate(value) ? Date.parse(value) : null;
}

function objectEntity(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return bad("record must be an object.");
  if (typeof value.id !== "string" || value.id.trim() === "") return bad("id is required.");
  return { ok: true, entity: value };
}

function missingString(entity, fields) {
  return fields.find((field) => typeof entity[field] !== "string" || entity[field].trim() === "");
}

function validDate(value) {
  return typeof value === "string" && value.trim() !== "" && !Number.isNaN(Date.parse(value));
}

function rowFromEntity(entity, table) {
  const createdAt = entity.created_at ?? entity.client_created_at ?? null;
  const updatedAt = table === "cards" ? entity.updated_at : createdAt;
  const deletedAt = table === "cards" ? entity.deleted_at ?? null : null;
  return {
    id: entity.id,
    body_json: JSON.stringify(entity),
    created_at: createdAt,
    updated_at: updatedAt,
    updated_at_ms: validDate(updatedAt) ? Date.parse(updatedAt) : 0,
    version: Number.isInteger(entity.version) ? entity.version : 0,
    deleted_at: deletedAt,
    deleted_at_ms: validDate(deletedAt) ? Date.parse(deletedAt) : null
  };
}

function isIncomingCardNewer(incoming, existing) {
  const existingVersion = Number.isInteger(existing.version) ? existing.version : 0;
  const existingUpdatedAtMs = Number.isInteger(existing.updated_at_ms) ? existing.updated_at_ms : 0;
  return incoming.version > existingVersion || incoming.updated_at_ms > existingUpdatedAtMs;
}

function skip(result, collection, index, reason, entity) {
  result.skipped[collection] += 1;
  if (result.skipped.reasons.length >= 50) return;
  const id = entity && typeof entity === "object" && typeof entity.id === "string" ? entity.id : null;
  result.skipped.reasons.push({ collection, index, id, reason });
}

function bad(reason) {
  return { ok: false, reason };
}

function invalidPayload(message) {
  const error = new Error(message);
  error.status = 400;
  return error;
}
