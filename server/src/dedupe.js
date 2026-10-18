import { withGroupClocks } from "./card_merge.js";

// The server is the only replica that folds active cards sharing a lemma. Two
// devices can each capture the same word before either syncs; folding here (and
// nowhere else) means every device converges on the same survivor, the
// duplicates become ordinary tombstones that point at it, and history rows are
// re-pointed once instead of being re-mapped differently on each client.

export const linkedTables = Object.freeze(["contexts", "definition_candidates", "review_events", "recommendation_feedback"]);

const statusRank = { new: 0, learning: 1, relearning: 2, review: 3, mastered: 4, suspended: 0 };

export const normalizeLemma = (value) => String(value ?? "").normalize("NFKC").trim().toLowerCase();

export function planDuplicateFold(cards, now) {
  const groups = new Map();
  for (const card of cards) {
    if (card.deleted_at != null) continue;
    const lemma = normalizeLemma(card.lemma);
    if (!lemma) continue;
    if (!groups.has(lemma)) groups.set(lemma, []);
    groups.get(lemma).push(withGroupClocks(card));
  }

  const updates = [];
  const redirects = new Map();
  for (const group of groups.values()) {
    if (group.length < 2) continue;
    const [survivor, ...duplicates] = [...group].sort(compareIdentity);
    const progress = [...group].sort(compareProgress)[0];
    const content = hasMeaning(survivor) ? survivor : ([...group].sort(compareContent)[0]);
    const version = Math.max(...group.map((card) => card.version)) + 1;

    const merged = { ...survivor };
    for (const field of ["meaning_ko", "definition_en", "part_of_speech", "phonetic", "definition_source_primary"]) {
      if (content !== survivor && content[field] != null) merged[field] = content[field];
    }
    for (const field of ["source_sentence_primary", "source_app_primary", "source_url_primary"]) {
      if (isBlank(merged[field])) {
        const donor = group.find((card) => !isBlank(card[field]));
        if (donor) merged[field] = donor[field];
      }
    }
    for (const field of ["status", "due_at", "interval_days", "lapse_count", "interval_before_lapse", "ease", "stability", "difficulty"]) {
      if (Object.hasOwn(progress, field)) merged[field] = progress[field];
    }
    merged.wordbook = group.some((card) => card.wordbook === "user") ? "user" : survivor.wordbook;
    merged.recommendation_started_at = group
      .map((card) => card.recommendation_started_at)
      .filter((value) => value != null)
      .sort((a, b) => Date.parse(a) - Date.parse(b))[0] ?? null;
    merged.content_version = Math.max(...group.map((card) => card.content_version)) + 1;
    merged.schedule_version = Math.max(...group.map((card) => card.schedule_version)) + 1;
    merged.content_updated_at = now;
    merged.schedule_updated_at = now;
    merged.version = version;
    merged.updated_at = now;
    updates.push(merged);

    for (const duplicate of duplicates) {
      redirects.set(duplicate.id, survivor.id);
      updates.push({
        ...duplicate,
        deleted_at: now,
        merged_into: survivor.id,
        version: duplicate.version + 1,
        updated_at: now
      });
    }
  }
  return { updates, redirects };
}

// Identity: the card that keeps its id. Prefer the user's own wordbook, then a
// card that already has a meaning, then the oldest capture.
function compareIdentity(a, b) {
  return Number(b.wordbook === "user") - Number(a.wordbook === "user") ||
    Number(hasMeaning(b)) - Number(hasMeaning(a)) ||
    Date.parse(a.created_at) - Date.parse(b.created_at) ||
    (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
}

// Progress: never throw away learning. The furthest-along schedule wins.
function compareProgress(a, b) {
  return Number(b.recommendation_started_at != null) - Number(a.recommendation_started_at != null) ||
    (statusRank[b.status] ?? 0) - (statusRank[a.status] ?? 0) ||
    (b.interval_days ?? 0) - (a.interval_days ?? 0) ||
    (b.lapse_count ?? 0) - (a.lapse_count ?? 0) ||
    b.schedule_version - a.schedule_version ||
    Date.parse(b.schedule_updated_at) - Date.parse(a.schedule_updated_at) ||
    (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
}

// Content donor when the survivor has no meaning yet: prefer user-written text.
function compareContent(a, b) {
  return Number(hasMeaning(b)) - Number(hasMeaning(a)) ||
    Number(b.definition_source_primary === "user") - Number(a.definition_source_primary === "user") ||
    Number(b.wordbook === "user") - Number(a.wordbook === "user") ||
    b.content_version - a.content_version ||
    (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
}

function hasMeaning(card) {
  return !isBlank(card.meaning_ko);
}

function isBlank(value) {
  return typeof value !== "string" || value.trim() === "";
}
