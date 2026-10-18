// Field-group merge for cards (sync protocol 3).
//
// A card is not one last-writer-wins blob. Edits to what the word means and
// reviews that move it through the schedule happen on different devices at the
// same time, so each group carries its own clock and wins independently:
//   content  -> content_version, content_updated_at
//   schedule -> schedule_version, schedule_updated_at
// Lifecycle fields only ever move one way and merge without a clock:
//   wordbook                  "user" wins over "recommended" (promotion is permanent)
//   recommendation_started_at earliest non-null
//   deleted_at                earliest non-null (there is no undelete)
//   merged_into               kept from whichever side recorded it (tombstones only)
//
// The same rules are implemented in Kotlin and Swift and pinned by
// shared/test-vectors/card_merge.json.

export const contentFields = Object.freeze([
  "display_word",
  "meaning_ko",
  "definition_en",
  "part_of_speech",
  "phonetic",
  "source_sentence_primary",
  "source_app_primary",
  "source_url_primary",
  "definition_source_primary",
  "primary_definition_candidate_id"
]);

export const scheduleFields = Object.freeze([
  "status",
  "due_at",
  "interval_days",
  "lapse_count",
  "interval_before_lapse",
  "ease",
  "stability",
  "difficulty"
]);

// Older payloads have only the whole-card clock; treat it as both group clocks.
export function withGroupClocks(card) {
  return {
    ...card,
    content_version: positiveInteger(card.content_version) ?? card.version,
    content_updated_at: validDate(card.content_updated_at) ? card.content_updated_at : card.updated_at,
    schedule_version: positiveInteger(card.schedule_version) ?? card.version,
    schedule_updated_at: validDate(card.schedule_updated_at) ? card.schedule_updated_at : card.updated_at
  };
}

export function mergeCards(existing, incoming) {
  const left = withGroupClocks(existing);
  const right = withGroupClocks(incoming);
  const content = compareGroup(right, left, "content", contentFields) > 0 ? right : left;
  const schedule = compareGroup(right, left, "schedule", scheduleFields) > 0 ? right : left;
  const deletedAt = earliest(left.deleted_at, right.deleted_at);

  // Non-group fields (created_device_id, language, ...) come from the side with
  // the newer whole-card revision.
  const newer = compareRevision(right, left) > 0 ? right : left;
  const older = newer === right ? left : right;
  const merged = { ...older, ...newer };
  for (const field of contentFields) assignOrDelete(merged, field, content);
  for (const field of scheduleFields) assignOrDelete(merged, field, schedule);
  merged.content_version = content.content_version;
  merged.content_updated_at = content.content_updated_at;
  merged.schedule_version = schedule.schedule_version;
  merged.schedule_updated_at = schedule.schedule_updated_at;
  merged.id = left.id;
  merged.lemma = left.lemma;
  merged.created_at = earliest(left.created_at, right.created_at);
  setPresent(merged, "wordbook",
    left.wordbook === "user" || right.wordbook === "user" ? "user" : (left.wordbook ?? right.wordbook ?? null), left, right);
  setPresent(merged, "recommendation_started_at",
    earliest(left.recommendation_started_at, right.recommendation_started_at), left, right);
  setPresent(merged, "deleted_at", deletedAt, left, right);
  const mergedInto = left.merged_into ?? right.merged_into ?? null;
  if (deletedAt != null && mergedInto != null) merged.merged_into = mergedInto;
  else delete merged.merged_into;
  merged.version = newer.version;
  merged.updated_at = newer.updated_at;
  return merged;
}

// Keep a lifecycle key out of the payload when neither side ever had it, so an
// identical retry produces a byte-identical body and no change-log entry.
function setPresent(target, field, value, left, right) {
  if (value != null || Object.hasOwn(left, field) || Object.hasOwn(right, field)) target[field] = value;
  else delete target[field];
}

function compareRevision(a, b) {
  if (a.version !== b.version) return a.version > b.version ? 1 : -1;
  return compareTimestamps(a.updated_at, b.updated_at);
}

function assignOrDelete(target, field, source) {
  if (Object.hasOwn(source, field)) target[field] = source[field];
  else delete target[field];
}

function compareGroup(a, b, group, fields) {
  const versionA = a[`${group}_version`];
  const versionB = b[`${group}_version`];
  if (versionA !== versionB) return versionA > versionB ? 1 : -1;
  const time = compareTimestamps(a[`${group}_updated_at`], b[`${group}_updated_at`]);
  if (time !== 0) return time;
  // Same logical revision with different contents (clock skew, imported legacy
  // data): pick a stable winner so every replica converges on the same values.
  const keyA = groupKey(a, fields);
  const keyB = groupKey(b, fields);
  if (keyA === keyB) return 0;
  return keyA > keyB ? 1 : -1;
}

export function groupKey(card, fields) {
  return fields.map((field) => {
    const value = card[field];
    if (value == null) return "";
    if (field === "due_at") return String(Date.parse(value));
    return String(value);
  }).join("\u001f");
}

export function compareTimestamps(left, right) {
  const leftMs = validDate(left) ? Date.parse(left) : 0;
  const rightMs = validDate(right) ? Date.parse(right) : 0;
  if (leftMs !== rightMs) return leftMs > rightMs ? 1 : -1;
  const leftFraction = fractionDigits(left);
  const rightFraction = fractionDigits(right);
  const width = Math.max(leftFraction.length, rightFraction.length);
  const a = leftFraction.padEnd(width, "0");
  const b = rightFraction.padEnd(width, "0");
  if (a === b) return 0;
  return a > b ? 1 : -1;
}

function earliest(a, b) {
  if (!validDate(a)) return validDate(b) ? b : null;
  if (!validDate(b)) return a;
  return compareTimestamps(a, b) <= 0 ? a : b;
}

function fractionDigits(value) {
  if (typeof value !== "string") return "";
  return /\.(\d+)(?:Z|[+-]\d{2}:?\d{2})$/i.exec(value)?.[1] ?? "";
}

function positiveInteger(value) {
  return Number.isInteger(value) && value >= 1 ? value : null;
}

function validDate(value) {
  return typeof value === "string" && value.trim() !== "" && !Number.isNaN(Date.parse(value));
}
