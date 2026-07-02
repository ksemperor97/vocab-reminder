import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(__dirname, "../../..");
const limit = Number.parseInt(process.env.RECOMMENDATION_ENRICH_LIMIT ?? "25", 10);
const outputPath = process.env.RECOMMENDATION_ENRICH_OUTPUT ??
  resolve(repoRoot, "shared/recommendations/recommendation_enriched_candidates.json");
const delayMs = Number.parseInt(process.env.RECOMMENDATION_ENRICH_DELAY_MS ?? "250", 10);
const requireComplete = process.env.RECOMMENDATION_ENRICH_REQUIRE_COMPLETE === "true";
const dictionaryFixture = readOptionalJson(process.env.RECOMMENDATION_DICTIONARY_FIXTURE_JSON);
const translationFixture = readOptionalJson(process.env.RECOMMENDATION_TRANSLATION_FIXTURE_JSON);

const queuePath = resolve(repoRoot, "shared/recommendations/recommendation_fill_queue.json");
const queue = JSON.parse(readFileSync(queuePath, "utf8")).queue.slice(0, limit);

const enriched = [];
for (const item of queue) {
  const dictionaryEntries = await lookupDictionary(item.lemma);
  const lexical = parseDictionaryEntries(dictionaryEntries);
  const meaningKo = translationFixture?.[item.lemma] ?? "";
  const topic = classifyTopic(item.lemma, lexical.definitionEn, lexical.exampleSentence);
  const fillStatus = statusFor({ ...lexical, meaningKo, topic });
  if (requireComplete && fillStatus !== "complete") {
    throw new Error(`${item.lemma} is not complete: ${fillStatus}`);
  }
  enriched.push({
    ...item,
    meaningKo,
    definitionEn: lexical.definitionEn,
    partOfSpeech: lexical.partOfSpeech,
    exampleSentence: lexical.exampleSentence,
    topic,
    fillStatus,
    sources: {
      frequency: "subtlex-word-frequencies@2.0.0",
      definition: lexical.definitionEn ? "dictionaryapi.dev" : null,
      translation: meaningKo ? "fixture" : null,
      topic: "heuristic"
    }
  });
  if (!dictionaryFixture && delayMs > 0) {
    await sleep(delayMs);
  }
}

const payload = {
  generatedAt: "2026-07-02",
  sourceQueue: "shared/recommendations/recommendation_fill_queue.json",
  limit,
  totalEnriched: enriched.length,
  complete: enriched.filter((entry) => entry.fillStatus === "complete").length,
  entries: enriched
};

writeFileSync(outputPath, JSON.stringify(payload, null, 2) + "\n");
console.log(`Wrote ${enriched.length} enriched candidates to ${outputPath}`);

async function lookupDictionary(lemma) {
  if (dictionaryFixture) {
    return dictionaryFixture[lemma] ?? [];
  }
  const url = `https://api.dictionaryapi.dev/api/v2/entries/en/${encodeURIComponent(lemma)}`;
  const response = await fetch(url);
  if (!response.ok) {
    return [];
  }
  return await response.json();
}

function parseDictionaryEntries(entries) {
  for (const entry of Array.isArray(entries) ? entries : []) {
    for (const meaning of entry.meanings ?? []) {
      for (const definition of meaning.definitions ?? []) {
        if (definition.definition) {
          return {
            definitionEn: definition.definition,
            partOfSpeech: meaning.partOfSpeech ?? "",
            exampleSentence: definition.example ?? ""
          };
        }
      }
    }
  }
  return {
    definitionEn: "",
    partOfSpeech: "",
    exampleSentence: ""
  };
}

function classifyTopic(lemma, definitionEn, exampleSentence) {
  const text = `${lemma} ${definitionEn} ${exampleSentence}`.toLowerCase();
  if (/(neural|brain|memory|sensory|perception|stimulus|attention|cortical|synaptic)/.test(text)) {
    return "neuroscience";
  }
  if (/(data|model|estimate|probability|distribution|variable|sample|statistical|variance)/.test(text)) {
    return "statistics";
  }
  if (/(experiment|method|procedure|protocol|measure|analysis|test|apparatus)/.test(text)) {
    return "methods";
  }
  if (/(study|evidence|theory|concept|argument|explain|interpret)/.test(text)) {
    return "academic";
  }
  return "general";
}

function statusFor(entry) {
  const missing = [];
  if (!entry.definitionEn) missing.push("definition");
  if (!entry.meaningKo) missing.push("translation");
  if (!entry.exampleSentence) missing.push("example");
  if (!entry.topic) missing.push("topic");
  return missing.length === 0 ? "complete" : `needs_${missing.join("_")}`;
}

function readOptionalJson(path) {
  if (!path) return null;
  return JSON.parse(readFileSync(path, "utf8"));
}
