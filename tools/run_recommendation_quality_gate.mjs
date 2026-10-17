#!/usr/bin/env node
import crypto from "node:crypto";
import { chooseRecommendations as chooseServerRecommendations } from "../server/src/recommendations.js";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  contentChecksum,
  difficultyBand
} from "../shared/recommendations/scripts/review_pipeline_common.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const catalogPath = path.join(root, "shared/recommendations/recommendation_catalog.json");
const kotlinPath = path.join(root, "android/VocabReminderAndroid/app/src/main/java/com/vocabreminder/android/recommendation/RecommendationCatalog.kt");
const swiftPath = path.join(root, "mac/VocabReminderMac/Sources/VocabReminderMac/RecommendationCatalog.swift");
const minimumTwelveMonthEntries = 365 * 3;
const targetEntries = 2_000;
const strictLongTerm = process.env.RECOMMENDATION_REQUIRE_LONG_TERM === "1";
const outputPath = process.env.RECOMMENDATION_QUALITY_OUTPUT || defaultOutputPath();

const catalogText = await fs.readFile(catalogPath, "utf8");
const catalog = JSON.parse(catalogText);
const entries = Array.isArray(catalog.entries) ? catalog.entries : [];
const failures = [];

if (catalog.schemaVersion !== 2) failures.push("Catalog schemaVersion must be 2.");
if (catalog.source?.package !== "subtlex-word-frequencies" || catalog.source?.license !== "ISC") {
  failures.push("Catalog frequency source must identify subtlex-word-frequencies under ISC.");
}
if (!String(catalog.source?.repository || "").startsWith("https://")) {
  failures.push("Catalog source repository must use HTTPS.");
}
if (entries.length === 0) failures.push("Catalog must contain entries.");
const contentSources = Array.isArray(catalog.contentSources) ? catalog.contentSources : [];
const contentSourceById = new Map(contentSources.map((source) => [source.sourceId, source]));
if (contentSources.length === 0 || contentSourceById.size !== contentSources.length) {
  failures.push("Catalog must contain unique content source metadata.");
}
for (const source of contentSources) {
  if (typeof source.sourceId !== "string" || !source.sourceId ||
      typeof source.attribution !== "string" || !source.attribution ||
      source.redistributionAllowed !== true || typeof source.shareAlikeRequired !== "boolean") {
    failures.push(`Content source ${source.sourceId ?? "<unknown>"} lacks attribution/redistribution metadata.`);
  }
  if (source.kind === "project_authored" && source.rightsBasis !== "project_authored") {
    failures.push(`Project content source ${source.sourceId} lacks project-authored rights basis.`);
  }
  if (source.kind === "licensed_import" &&
      (!source.datasetVersion || !source.dataChecksumSha256 || !source.metadataChecksumSha256 ||
       !Array.isArray(source.licenses) || source.licenses.length === 0)) {
    failures.push(`Licensed content source ${source.sourceId} lacks version/checksum/license metadata.`);
  }
}
const requiredFields = [
  "lemma", "meaningKo", "definitionEn", "partOfSpeech", "exampleSentence", "topic",
  "difficultyBand", "entryId", "contentSourceId", "reviewStatus"
];
for (const [index, entry] of entries.entries()) {
  for (const field of requiredFields) {
    if (typeof entry[field] !== "string" || entry[field].trim() === "") {
      failures.push(`Entry ${index} is missing ${field}.`);
    }
  }
  if (!Number.isSafeInteger(entry.frequencyRank) || entry.frequencyRank <= 0) {
    failures.push(`Entry ${index} has an invalid frequencyRank.`);
  }
  if (!Number.isSafeInteger(entry.frequencyCount) || entry.frequencyCount < 0) {
    failures.push(`Entry ${index} has an invalid frequencyCount.`);
  }
  const normalized = entry.lemma.normalize("NFKC").toLocaleLowerCase("en-US");
  if (entry.entryId !== `recommendation:${normalized}`) failures.push(`Entry ${index} has an unstable entryId.`);
  if (entry.difficultyBand !== difficultyBand(entry.frequencyRank)) failures.push(`Entry ${index} has an invalid difficultyBand.`);
  const source = contentSourceById.get(entry.contentSourceId);
  if (!source) {
    failures.push(`Entry ${index} references unknown content source ${entry.contentSourceId}.`);
  } else if (source.kind === "licensed_import") {
    if (source.redistributionAllowed !== true || typeof source.shareAlikeRequired !== "boolean" ||
        !source.attribution || !Array.isArray(source.licenses) || source.licenses.length === 0) {
      failures.push(`Licensed source ${source.sourceId} lacks release metadata.`);
    }
    if (entry.reviewStatus !== "approved" || entry.review?.status !== "approved") {
      failures.push(`Licensed entry ${entry.lemma} lacks approved human review.`);
    } else {
      try {
        if (entry.review.contentChecksumSha256 !== contentChecksum(entry)) {
          failures.push(`Licensed entry ${entry.lemma} changed after human review.`);
        }
      } catch (error) {
        failures.push(`Licensed entry ${entry.lemma} cannot be checksummed: ${error.message}`);
      }
    }
  } else if (source.kind === "project_authored" && entry.reviewStatus !== "curated_at_authoring") {
    failures.push(`Project-authored entry ${entry.lemma} has an invalid review state.`);
  }
}
const normalizedLemmas = entries.map((entry) => entry.lemma.normalize("NFKC").toLocaleLowerCase("en-US"));
if (new Set(normalizedLemmas).size !== normalizedLemmas.length) failures.push("Catalog lemmas must be unique.");

for (const clientCatalogue of [kotlinPath, swiftPath]) {
  if (await fs.access(clientCatalogue).then(() => true, () => false)) {
    failures.push("Client bundles must not contain recommendation catalogues.");
  }
}

const simulation = simulateThirtyDays(entries);
if (simulation.duplicateLemmas.length > 0) failures.push("30-day simulation produced duplicate recommendations.");
if (simulation.feedbackReappearance.length > 0) failures.push("Known/dismissed feedback lemmas reappeared.");
if (simulation.savedReappearance.length > 0) failures.push("Already-saved lemmas reappeared.");
if (simulation.reviewStarvationDays.length > 0) failures.push("Recommendations displaced due reviews.");

const longTermSupplyReady = entries.length >= minimumTwelveMonthEntries;
if (strictLongTerm && !longTermSupplyReady) {
  failures.push(`Long-term catalog requires at least ${minimumTwelveMonthEntries} complete entries; found ${entries.length}.`);
}

const report = {
  schemaVersion: 1,
  recordedAt: new Date().toISOString(),
  gitSha: await gitSha(),
  result: failures.length === 0 ? "passed" : "failed",
  catalog: {
    entries: entries.length,
    checksumSha256: crypto.createHash("sha256").update(catalogText).digest("hex"),
    requiredFieldsComplete: failures.every((failure) => !failure.includes("missing")),
    uniqueLemmas: new Set(normalizedLemmas).size === normalizedLemmas.length,
    daysAtThreePerDay: Math.floor(entries.length / 3),
    licensedImportedEntries: entries.filter((entry) => contentSourceById.get(entry.contentSourceId)?.kind === "licensed_import").length,
    pendingReviewEntriesBundled: entries.filter((entry) => contentSourceById.get(entry.contentSourceId)?.kind === "licensed_import" && entry.reviewStatus !== "approved").length
  },
  licensing: {
    frequencyRanks: catalog.source,
    definitionMeaningExampleProvenance: "project_authored_curated_seed",
    prospectiveExpansionSource: "Wiktionary via kaikki.org; not bundled until attribution, share-alike, and human review gates are satisfied"
  },
  recommendationAuthority: "computer_server",
  simulation,
  releaseReadiness: {
    minimumTwelveMonthEntries,
    targetEntries,
    longTermSupplyReady,
    strictLongTermGateEnabled: strictLongTerm
  },
  failures
};

await fs.mkdir(path.dirname(outputPath), { recursive: true, mode: 0o700 });
await fs.writeFile(outputPath, `${JSON.stringify(report, null, 2)}\n`, { mode: 0o600 });
console.log(`Recommendation catalog integrity: ${entries.length} complete entries, computer-server authority.`);
console.log(`30-day simulation: ${simulation.recommendedCount} recommendations, duplicates ${simulation.duplicateLemmas.length}, feedback/saved reappearances ${simulation.feedbackReappearance.length}/${simulation.savedReappearance.length}, review starvation ${simulation.reviewStarvationDays.length}.`);
console.log(`Long-term supply: ${longTermSupplyReady ? "READY" : "NOT READY"} (${entries.length}/${minimumTwelveMonthEntries} minimum, target ${targetEntries}).`);
console.log(`Evidence: ${outputPath}`);
if (failures.length > 0) {
  for (const failure of failures) console.error(`FAIL: ${failure}`);
  process.exitCode = 1;
}

function simulateThirtyDays(catalogEntries) {
  const dailyGoal = 3;
  const userSignals = Array.from({ length: 50 }, (_, index) => ({ lemma: `private-user-word-${index}` }));
  const feedback = [
    { lemma: catalogEntries[0]?.lemma, action: "known" },
    { lemma: catalogEntries[1]?.lemma, action: "dismissed" }
  ].filter((item) => item.lemma);
  const feedbackLemmas = new Set(feedback.map((item) => item.lemma));
  const savedLemmas = new Set([catalogEntries[2]?.lemma].filter(Boolean));
  const existing = new Set(savedLemmas);
  const selectedAcrossDays = [];
  const days = [];
  const reviewStarvationDays = [];

  for (let day = 1; day <= 30; day += 1) {
    const dueReviewCount = day % 7 === 0 ? 3 : day % 3;
    const openSlots = Math.max(0, dailyGoal - dueReviewCount);
    const selected = chooseRecommendations(catalogEntries, userSignals, feedback, existing, openSlots);
    if (selected.length > openSlots) reviewStarvationDays.push(day);
    for (const entry of selected) {
      selectedAcrossDays.push(entry.lemma);
      existing.add(entry.lemma);
    }
    days.push({ day, dueReviewCount, recommendationSlots: openSlots, selected: selected.map((entry) => entry.lemma) });
  }

  const seen = new Set();
  const duplicateLemmas = [];
  for (const lemma of selectedAcrossDays) {
    if (seen.has(lemma)) duplicateLemmas.push(lemma);
    seen.add(lemma);
  }
  return {
    days: 30,
    dailyGoal,
    recommendedCount: selectedAcrossDays.length,
    duplicateLemmas: [...new Set(duplicateLemmas)],
    feedbackReappearance: selectedAcrossDays.filter((lemma) => feedbackLemmas.has(lemma)),
    savedReappearance: selectedAcrossDays.filter((lemma) => savedLemmas.has(lemma)),
    reviewStarvationDays,
    schedule: days
  };
}

function chooseRecommendations(catalogEntries, userSignals, feedback, existing, limit) {
  return chooseServerRecommendations(catalogEntries, {
    cards: [...existing].map(lemma => ({ lemma, wordbook: "recommended" })),
    recommendation_feedback: feedback
  }, limit);
}

function defaultOutputPath() {
  const date = new Date().toISOString().slice(0, 10).replaceAll("-", "");
  const stamp = new Date().toISOString().replaceAll(/[-:.TZ]/g, "").slice(0, 14);
  return `/tmp/vocab_product_completion_${date}/recommendation_quality_${stamp}.json`;
}

async function gitSha() {
  const { execFile } = await import("node:child_process");
  return new Promise((resolve) => {
    execFile("git", ["-C", root, "rev-parse", "HEAD"], { encoding: "utf8" }, (error, stdout) => {
      resolve(error ? "unknown" : stdout.trim());
    });
  });
}
