import fs from "node:fs";
import { createHash } from "node:crypto";

export const normalizeLemma = (value) => String(value ?? "").normalize("NFKC").trim().toLowerCase();
const serverDevice = "recommendation-server";
const inactive = new Set(["mastered", "suspended"]);
const countedResponses = new Set(["remembered", "forgotten", "unavailable", "reveal"]);

// Read on sync so updating the computer's catalogue never requires a client build.
// Keep the last valid catalogue if an interrupted edit leaves invalid JSON behind.
export function createRecommendationCatalogLoader(catalogPath = process.env.VOCAB_RECOMMENDATION_CATALOG_PATH ||
  process.env.VOCAB_DICTIONARY_CATALOG_PATH ||
  (fs.existsSync(new URL("../catalog/recommendation_catalog.json", import.meta.url))
    ? new URL("../catalog/recommendation_catalog.json", import.meta.url)
    : new URL("../../shared/recommendations/recommendation_catalog.json", import.meta.url))) {
  let previousText;
  let entries;
  return () => {
    try {
      const text = fs.readFileSync(catalogPath, "utf8");
      if (text === previousText) return entries;
      const parsed = JSON.parse(text).entries;
      if (!Array.isArray(parsed)) throw new Error("Recommendation catalogue must contain entries.");
      const seen = new Set();
      for (const item of parsed) {
        if (!["lemma", "meaningKo", "definitionEn", "partOfSpeech", "exampleSentence", "topic"].every(key =>
          typeof item[key] === "string" && item[key].trim()) ||
          !Number.isSafeInteger(item.frequencyRank) || item.frequencyRank < 1 ||
          seen.has(normalizeLemma(item.lemma))) throw new Error("Invalid recommendation catalogue entry.");
        seen.add(normalizeLemma(item.lemma));
      }
      entries = parsed;
      previousText = text;
    } catch (error) {
      if (!entries) throw error;
    }
    return entries;
  };
}

export function chooseRecommendations(catalog, snapshot, limit) {
  const byLemma = new Map(catalog.map(seed => [normalizeLemma(seed.lemma), seed]));
  const feedback = snapshot.recommendation_feedback;
  const excluded = new Set(snapshot.cards.map(card => normalizeLemma(card.lemma)));
  for (const item of feedback) if (["known", "dismissed"].includes(item.action)) excluded.add(normalizeLemma(item.lemma));
  const users = snapshot.cards.filter(card => !card.deleted_at && card.wordbook !== "recommended");
  const ranks = users.map(card => byLemma.get(normalizeLemma(card.lemma))?.frequencyRank).filter(Boolean).sort((a,b) => a-b);
  const knownCount = new Set(feedback.filter(item => item.action === "known").map(item => normalizeLemma(item.lemma))).size;
  const weakCount = users.filter(card => (card.lapse_count ?? 0) >= 3).length;
  const center = Math.min(55000, Math.max(8000, (ranks[Math.min(Math.floor(ranks.length * .7), ranks.length-1)] ?? 16000) + knownCount * 1500 - weakCount * 700));
  const lower = Math.max(5000, center-4000), upper = Math.min(70000, center+10000);
  const dismissedTopics = new Set(feedback.filter(item => item.action === "dismissed")
    .map(item => byLemma.get(normalizeLemma(item.lemma))?.topic).filter(Boolean));
  const topicCounts = new Map();
  const seen = new Set([...snapshot.cards.map(card => normalizeLemma(card.lemma)), ...feedback.map(item => normalizeLemma(item.lemma))]);
  for (const lemma of seen) {
    const topic = byLemma.get(lemma)?.topic;
    if (topic) topicCounts.set(topic, (topicCounts.get(topic) ?? 0) + 1);
  }
  const inBand = seed => seed.frequencyRank >= lower && seed.frequencyRank <= upper;
  const distance = seed => seed.frequencyRank < lower ? lower-seed.frequencyRank : seed.frequencyRank > upper ? seed.frequencyRank-upper : Math.abs(seed.frequencyRank-(lower+upper)/2);
  let candidates = catalog.filter(seed => !excluded.has(normalizeLemma(seed.lemma)));
  const selected = [];
  while (selected.length < limit && candidates.length) {
    candidates.sort((a,b) => Number(inBand(b))-Number(inBand(a)) ||
      Number(dismissedTopics.has(a.topic))-Number(dismissedTopics.has(b.topic)) ||
      (topicCounts.get(a.topic) ?? 0)-(topicCounts.get(b.topic) ?? 0) ||
      distance(a)-distance(b) || a.frequencyRank-b.frequencyRank || a.lemma.localeCompare(b.lemma, "en"));
    const next = candidates.shift();
    selected.push(next);
    topicCounts.set(next.topic, (topicCounts.get(next.topic) ?? 0)+1);
  }
  return selected;
}

// These cards use the existing sync schema, including tombstones and change-log triggers.
// The transaction makes repeated/concurrent device pulls share one recommendation queue.
export function maintainRecommendations(store, catalog, now = new Date()) {
  return store.db.transaction(() => {
    let cards = store.allEntities("cards");
    const feedback = store.allEntities("recommendation_feedback");
    const settings = store.settings();
    const blocked = new Set(feedback.filter(item => ["known", "dismissed"].includes(item.action))
      .map(item => normalizeLemma(item.lemma)));
    const started = new Set(feedback.filter(item => item.action === "started").map(item => item.card_id));
    const reviewed = store.reviewedCardIds([...countedResponses]);
    const stamp = now.toISOString();
    const retired = cards.filter(card => !card.deleted_at && card.wordbook === "recommended" &&
      (blocked.has(normalizeLemma(card.lemma)) ||
        (settings?.enable_word_recommendations && catalog.length && !card.id.startsWith("server-recommendation-") && !card.recommendation_started_at &&
          card.status === "new" && !started.has(card.id) && !reviewed.has(card.id))));
    if (retired.length) {
      store.applyBatch({ cards: retired.map(card => ({ ...card, deleted_at: stamp, updated_at: stamp, version: card.version+1 })) });
      cards = store.allEntities("cards");
    }
    if (!settings?.enable_word_recommendations) return { generated: 0, retired: retired.length };
    let formatter;
    try { formatter = new Intl.DateTimeFormat("en-CA", { timeZone: settings.timezone, year: "numeric", month: "2-digit", day: "2-digit" }); }
    catch { formatter = new Intl.DateTimeFormat("en-CA", { timeZone: "UTC", year: "numeric", month: "2-digit", day: "2-digit" }); }
    const today = formatter.format(now);
    const onToday = date => Number.isFinite(Date.parse(date)) && formatter.format(new Date(date)) === today;
    // Anything reviewed "today" in any timezone reached the server within the last two days.
    const recentEvents = store.reviewEventsReceivedSince(now.getTime() - 2 * 86400000);
    const processed = new Set([
      ...recentEvents.filter(event => countedResponses.has(event.response) && onToday(event.client_created_at)).map(event => event.card_id),
      ...feedback.filter(item => onToday(item.created_at)).map(item => item.card_id)
    ]);
    const active = cards.filter(card => !card.deleted_at && !inactive.has(card.status) && card.meaning_ko?.trim());
    const due = active.filter(card => !processed.has(card.id) && (card.wordbook !== "recommended" || card.recommendation_started_at) && Date.parse(card.due_at) <= now.getTime()).length;
    const waiting = active.filter(card => card.wordbook === "recommended" && !card.recommendation_started_at).length;
    const limit = Math.max(0, Math.min(settings.daily_word_goal-processed.size, settings.max_queue_size)-due-waiting);
    const selected = chooseRecommendations(catalog, { cards, recommendation_feedback: feedback }, limit);
    if (selected.length) {
      const generated = selected.map(seed => ({
        id: `server-recommendation-${createHash("sha256").update(normalizeLemma(seed.lemma)).digest("hex")}`,
        lemma: normalizeLemma(seed.lemma), display_word: seed.lemma, meaning_ko: seed.meaningKo,
        definition_en: seed.definitionEn, part_of_speech: seed.partOfSpeech, phonetic: "",
        source_sentence_primary: seed.exampleSentence, definition_source_primary: "server_catalog",
        wordbook: "recommended", recommendation_started_at: null, status: "new", due_at: stamp,
        interval_days: 0, interval_before_lapse: null, lapse_count: 0, created_at: stamp, updated_at: stamp,
        version: 1, deleted_at: null, created_device_id: serverDevice
      }));
      const contexts = generated.map(card => ({ id: `${card.id}-context`, card_id: card.id,
        source_sentence: card.source_sentence_primary, source_app: "Vocabulary Recommendation",
        source_url: null, capture_method: "recommendation", created_at: stamp }));
      const result = store.applyBatch({ cards: generated, contexts });
      if (result.skipped.cards || result.skipped.contexts) throw new Error("Server recommendation validation failed.");
    }
    return { generated: selected.length, retired: retired.length };
  })();
}
