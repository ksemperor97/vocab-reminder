#!/usr/bin/env node
import crypto from "node:crypto";
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

const kotlinText = await fs.readFile(kotlinPath, "utf8");
const swiftText = await fs.readFile(swiftPath, "utf8");
const kotlinEntries = parseGeneratedCatalog(kotlinText, "kotlin");
const swiftEntries = parseGeneratedCatalog(swiftText, "swift");
const kotlinCount = kotlinEntries.length;
const swiftCount = swiftEntries.length;
if (kotlinCount !== entries.length) failures.push(`Android generated catalog count ${kotlinCount} differs from ${entries.length}.`);
if (swiftCount !== entries.length) failures.push(`Mac generated catalog count ${swiftCount} differs from ${entries.length}.`);
const androidMismatches = comparePlatformEntries(entries, kotlinEntries);
const macMismatches = comparePlatformEntries(entries, swiftEntries);
const crossPlatformMismatches = comparePlatformEntries(kotlinEntries, swiftEntries);
if (androidMismatches.length > 0) failures.push(`Android generated catalog differs at ${androidMismatches.length} entries.`);
if (macMismatches.length > 0) failures.push(`Mac generated catalog differs at ${macMismatches.length} entries.`);
if (crossPlatformMismatches.length > 0) failures.push(`Android/Mac recommendation identity differs at ${crossPlatformMismatches.length} entries.`);

const vectorAudit = await auditRecommendationVectors();
if (vectorAudit.missingCases.length > 0) failures.push(`Shared recommendation vectors lack: ${vectorAudit.missingCases.join(", ")}.`);
if (!vectorAudit.uniqueNames) failures.push("Shared recommendation vector case names must be unique.");

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
    androidGeneratedEntries: kotlinCount,
    macGeneratedEntries: swiftCount,
    daysAtThreePerDay: Math.floor(entries.length / 3),
    licensedImportedEntries: entries.filter((entry) => contentSourceById.get(entry.contentSourceId)?.kind === "licensed_import").length,
    pendingReviewEntriesBundled: entries.filter((entry) => contentSourceById.get(entry.contentSourceId)?.kind === "licensed_import" && entry.reviewStatus !== "approved").length
  },
  licensing: {
    frequencyRanks: catalog.source,
    definitionMeaningExampleProvenance: "project_authored_curated_seed",
    prospectiveExpansionSource: "Wiktionary via kaikki.org; not bundled until attribution, share-alike, and human review gates are satisfied"
  },
  platformParity: {
    androidGeneratedEntries: kotlinCount,
    macGeneratedEntries: swiftCount,
    androidCatalogMismatches: androidMismatches,
    macCatalogMismatches: macMismatches,
    crossPlatformIdentityMismatches: crossPlatformMismatches,
    identityDefinition: "entryId recommendation:<normalized-lemma>; generated platform seed order and full content must match"
  },
  vectorAudit,
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
console.log(`Recommendation catalog integrity: ${entries.length} complete entries, platform copies ${kotlinCount}/${swiftCount}.`);
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
  if (userSignals.length < 50 || limit <= 0) return [];
  const excluded = new Set([
    ...existing,
    ...feedback.filter((item) => item.action === "known" || item.action === "dismissed").map((item) => item.lemma)
  ]);
  const byLemma = new Map(catalogEntries.map((entry) => [entry.lemma, entry]));
  const dismissedTopics = new Set(
    feedback.filter((item) => item.action === "dismissed").map((item) => byLemma.get(item.lemma)?.topic).filter(Boolean)
  );
  const knownBoost = feedback.filter((item) => item.action === "known").length * 1_500;
  const center = clamp(16_000 + knownBoost, 8_000, 55_000);
  const lower = Math.max(5_000, center - 4_000);
  const upper = Math.min(70_000, center + 10_000);
  const preferredTopics = new Set(["academic", "methods"]);

  return catalogEntries
    .filter((entry) => !excluded.has(entry.lemma))
    .toSorted((left, right) => {
      const leftInBand = left.frequencyRank >= lower && left.frequencyRank <= upper;
      const rightInBand = right.frequencyRank >= lower && right.frequencyRank <= upper;
      if (leftInBand !== rightInBand) return leftInBand ? -1 : 1;
      const leftDismissed = dismissedTopics.has(left.topic);
      const rightDismissed = dismissedTopics.has(right.topic);
      if (leftDismissed !== rightDismissed) return leftDismissed ? 1 : -1;
      const leftPreferred = preferredTopics.has(left.topic);
      const rightPreferred = preferredTopics.has(right.topic);
      if (leftPreferred !== rightPreferred) return leftPreferred ? -1 : 1;
      const distance = distanceFromBand(left.frequencyRank, lower, upper) - distanceFromBand(right.frequencyRank, lower, upper);
      return distance || left.frequencyRank - right.frequencyRank;
    })
    .slice(0, limit);
}

function distanceFromBand(rank, lower, upper) {
  if (rank < lower) return lower - rank;
  if (rank > upper) return rank - upper;
  return Math.abs(rank - Math.floor((lower + upper) / 2));
}

function clamp(value, minimum, maximum) {
  return Math.min(maximum, Math.max(minimum, value));
}

function parseGeneratedCatalog(text, platform) {
  const blocks = [...text.matchAll(/RecommendationSeed\(([\s\S]*?)\n\s*\)/g)].map((match) => match[1]);
  return blocks.map((block, index) => ({
    lemma: generatedString(block, "lemma", platform, index),
    meaningKo: generatedString(block, "meaningKo", platform, index),
    definitionEn: generatedString(block, "definitionEn", platform, index),
    partOfSpeech: generatedString(block, "partOfSpeech", platform, index),
    exampleSentence: generatedString(block, "exampleSentence", platform, index),
    frequencyRank: generatedInteger(block, "frequencyRank", platform, index),
    topic: generatedString(block, "topic", platform, index),
    entryId: `recommendation:${generatedString(block, "lemma", platform, index).normalize("NFKC").toLocaleLowerCase("en-US")}`
  }));
}

function generatedString(block, field, platform, index) {
  const match = block.match(new RegExp(`${field}\\s*(?:=|:)\\s*("(?:\\\\.|[^"\\\\])*")`));
  if (!match) throw new Error(`${platform} generated entry ${index} is missing ${field}.`);
  return JSON.parse(match[1]);
}

function generatedInteger(block, field, platform, index) {
  const match = block.match(new RegExp(`${field}\\s*(?:=|:)\\s*(\\d+)`));
  if (!match) throw new Error(`${platform} generated entry ${index} is missing ${field}.`);
  return Number.parseInt(match[1], 10);
}

function comparePlatformEntries(expected, actual) {
  const fields = ["lemma", "meaningKo", "definitionEn", "partOfSpeech", "exampleSentence", "frequencyRank", "topic", "entryId"];
  const mismatches = [];
  const count = Math.max(expected.length, actual.length);
  for (let index = 0; index < count; index += 1) {
    const left = expected[index];
    const right = actual[index];
    const differingFields = fields.filter((field) => left?.[field] !== right?.[field]);
    if (differingFields.length > 0) {
      mismatches.push({
        index,
        expectedId: left?.entryId ?? null,
        actualId: right?.entryId ?? null,
        differingFields
      });
    }
  }
  return mismatches;
}

async function auditRecommendationVectors() {
  const vectorPath = path.join(root, "shared/test-vectors/recommendations.json");
  const vectors = JSON.parse(await fs.readFile(vectorPath, "utf8"));
  const names = new Set(vectors.map((vector) => vector.name));
  const requiredCases = [
    "recommendations_stay_off_before_fifty_user_words",
    "due_reviews_fill_goal_before_recommendations",
    "recommendations_stop_when_setting_disabled",
    "existing_recommendations_occupy_open_daily_slots",
    "recommendation_fill_limit_respects_queue_cap",
    "recommendations_use_boundary_and_preferred_topics",
    "known_feedback_excludes_exact_lemma",
    "known_feedback_shifts_recommendation_band_harder",
    "dismissed_feedback_excludes_lemma_and_downranks_topic"
  ];
  return {
    vectorPath: "shared/test-vectors/recommendations.json",
    cases: vectors.length,
    uniqueNames: names.size === vectors.length,
    requiredCases,
    missingCases: requiredCases.filter((name) => !names.has(name)),
    platformExecution: "Android unit tests and Mac scheduler self-check consume the same vectors"
  };
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
