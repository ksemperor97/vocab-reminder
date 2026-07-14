#!/usr/bin/env node
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import {
  canonicalJson,
  difficultyBand,
  normalizeLemma,
  sha256,
  validateSourceManifest
} from "./review_pipeline_common.mjs";

const __dirname = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(__dirname, "../../..");
const sourcePath = requiredPath("RECOMMENDATION_LEXICAL_JSONL");
const manifestPath = requiredPath("RECOMMENDATION_LEXICAL_SOURCE_MANIFEST");
const queuePath = resolve(process.env.RECOMMENDATION_IMPORT_QUEUE ??
  resolve(repoRoot, "shared/recommendations/recommendation_fill_queue.json"));
const outputPath = requiredPath("RECOMMENDATION_REVIEW_QUEUE_OUTPUT");
const limit = parseLimit(process.env.RECOMMENDATION_IMPORT_LIMIT);

const sourceBytes = readFileSync(sourcePath);
const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
const sourceSnapshot = validateSourceManifest(manifest, sourceBytes);
if (sourceSnapshot.format !== "wiktextract-jsonl-v1") {
  throw new Error(`Unsupported lexical JSONL format: ${sourceSnapshot.format}`);
}

const queueText = readFileSync(queuePath, "utf8");
const queuePayload = JSON.parse(queueText);
const queue = Array.isArray(queuePayload.queue) ? queuePayload.queue : [];
const queueByLemma = new Map();
for (const item of queue) {
  const lemma = normalizeLemma(item.lemma);
  if (!lemma || queueByLemma.has(lemma)) continue;
  queueByLemma.set(lemma, item);
}

const recordsByLemma = new Map();
const lines = sourceBytes.toString("utf8").split(/\r?\n/);
for (let index = 0; index < lines.length; index += 1) {
  const line = lines[index].trim();
  if (!line) continue;
  let record;
  try {
    record = JSON.parse(line);
  } catch (error) {
    throw new Error(`Invalid JSONL at line ${index + 1}: ${error.message}`);
  }
  if (record.lang_code !== "en") continue;
  const lemma = normalizeLemma(record.word);
  if (!queueByLemma.has(lemma)) continue;
  const candidates = lexicalCandidates(record, index + 1);
  if (candidates.length === 0) continue;
  const existing = recordsByLemma.get(lemma) ?? [];
  existing.push(...candidates);
  recordsByLemma.set(lemma, existing);
}

const imported = [];
for (const [lemma, item] of [...queueByLemma.entries()].sort((left, right) => queueOrder(left[1], right[1]))) {
  const choices = (recordsByLemma.get(lemma) ?? []).sort(compareLexicalCandidate);
  if (choices.length === 0) continue;
  const selected = choices[0];
  const sourceCandidates = choices.map(({ sourceLine, ...candidate }) => ({ ...candidate, sourceLine }));
  const missing = [];
  if (!selected.definitionEn) missing.push("definition");
  if (!selected.meaningKo) missing.push("translation");
  if (!selected.exampleSentence) missing.push("example");
  missing.push("topic", "human_review");
  imported.push({
    queueRank: item.queueRank,
    lemma,
    meaningKo: selected.meaningKo,
    definitionEn: selected.definitionEn,
    partOfSpeech: selected.partOfSpeech,
    exampleSentence: selected.exampleSentence,
    frequencyRank: item.frequencyRank,
    frequencyCount: item.frequencyCount,
    difficultyBand: item.bucket || difficultyBand(item.frequencyRank),
    topic: "",
    fillStatus: `needs_${missing.join("_")}`,
    releaseEligible: false,
    provenance: {
      sourceId: sourceSnapshot.sourceId,
      datasetVersion: sourceSnapshot.datasetVersion,
      dataChecksumSha256: sourceSnapshot.dataChecksumSha256,
      metadataChecksumSha256: sourceSnapshot.metadataChecksumSha256,
      recordChecksumSha256: selected.recordChecksumSha256,
      sourceLine: selected.sourceLine
    },
    sourceCandidates,
    review: {
      status: "pending",
      reviewerId: "",
      reviewedAt: "",
      contentChecksumSha256: "",
      checks: {
        definitionAccuracy: false,
        translationAccuracy: false,
        exampleQuality: false,
        topicFit: false,
        licenseAndAttribution: false
      },
      notes: ""
    }
  });
  if (imported.length >= limit) break;
}

const payload = {
  schemaVersion: 1,
  reviewPolicyVersion: 1,
  sourceSnapshot,
  queueSnapshot: {
    checksumSha256: sha256(queueText),
    queuedEntries: queue.length
  },
  selectionPolicy: {
    name: "queue-order-completeness-lexical-v1",
    description: "Exact normalized queue lemma; prefer complete definition, Korean translation, and example; deterministic lexical tie-break."
  },
  totalImported: imported.length,
  humanReviewStatus: {
    pending: imported.length,
    approved: 0,
    rejected: 0
  },
  entries: imported
};

mkdirSync(dirname(outputPath), { recursive: true });
writeFileSync(outputPath, `${JSON.stringify(payload, null, 2)}\n`);
console.log(`Imported ${imported.length} licensed lexical candidates into a pending review queue.`);
console.log(`Source: ${sourceSnapshot.sourceId}@${sourceSnapshot.datasetVersion} sha256:${sourceSnapshot.dataChecksumSha256}`);
console.log(`Review queue: ${outputPath}`);

function lexicalCandidates(record, sourceLine) {
  const topLevelTranslations = koreanTranslations(record.translations);
  const senses = Array.isArray(record.senses) && record.senses.length > 0 ? record.senses : [{}];
  return senses.map((sense) => {
    const definitions = uniqueStrings([...(sense.glosses ?? []), ...(sense.raw_glosses ?? [])]);
    const examples = uniqueStrings((sense.examples ?? []).map((example) => typeof example === "string" ? example : example?.text));
    const meanings = uniqueStrings([...koreanTranslations(sense.translations), ...topLevelTranslations]);
    const candidate = {
      definitionEn: definitions[0] ?? "",
      meaningKo: meanings.join(", "),
      partOfSpeech: normalizePartOfSpeech(record.pos),
      exampleSentence: examples[0] ?? "",
      sourceLine
    };
    return {
      ...candidate,
      recordChecksumSha256: sha256(canonicalJson({
        word: record.word,
        lang_code: record.lang_code,
        pos: record.pos ?? "",
        sense: {
          glosses: sense.glosses ?? [],
          raw_glosses: sense.raw_glosses ?? [],
          examples: sense.examples ?? [],
          translations: sense.translations ?? []
        },
        topLevelTranslations: record.translations ?? []
      }))
    };
  }).filter((candidate) => candidate.definitionEn || candidate.meaningKo || candidate.exampleSentence);
}

function koreanTranslations(translations) {
  return uniqueStrings((Array.isArray(translations) ? translations : [])
    .filter((translation) => translation?.lang_code === "ko" || translation?.lang === "Korean")
    .map((translation) => translation.word));
}

function uniqueStrings(values) {
  return [...new Set(values
    .filter((value) => typeof value === "string")
    .map((value) => value.normalize("NFKC").replace(/\s+/g, " ").trim())
    .filter(Boolean))].sort((left, right) => left.localeCompare(right, "en"));
}

function normalizePartOfSpeech(value) {
  return ({ adj: "adjective", adv: "adverb" })[value] ?? String(value ?? "").trim();
}

function compareLexicalCandidate(left, right) {
  return completeness(right) - completeness(left) ||
    left.partOfSpeech.localeCompare(right.partOfSpeech, "en") ||
    left.definitionEn.localeCompare(right.definitionEn, "en") ||
    left.meaningKo.localeCompare(right.meaningKo, "ko") ||
    left.exampleSentence.localeCompare(right.exampleSentence, "en") ||
    left.recordChecksumSha256.localeCompare(right.recordChecksumSha256);
}

function completeness(candidate) {
  return [candidate.definitionEn, candidate.meaningKo, candidate.exampleSentence, candidate.partOfSpeech]
    .filter(Boolean).length;
}

function queueOrder(left, right) {
  return numberOrMax(left.queueRank) - numberOrMax(right.queueRank) ||
    numberOrMax(left.frequencyRank) - numberOrMax(right.frequencyRank) ||
    normalizeLemma(left.lemma).localeCompare(normalizeLemma(right.lemma), "en");
}

function numberOrMax(value) {
  return Number.isSafeInteger(value) ? value : Number.MAX_SAFE_INTEGER;
}

function requiredPath(name) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required.`);
  return resolve(value);
}

function parseLimit(value) {
  if (value == null || value === "") return Number.MAX_SAFE_INTEGER;
  const parsed = Number.parseInt(value, 10);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) throw new Error("RECOMMENDATION_IMPORT_LIMIT must be a positive integer.");
  return parsed;
}
