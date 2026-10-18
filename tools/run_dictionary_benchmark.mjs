#!/usr/bin/env node
import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const fixturePath = process.env.DICTIONARY_BENCHMARK_FIXTURE ||
  path.join(root, "shared/test-vectors/dictionary_benchmark.json");
const endpoint = requiredUrl(process.env.VOCAB_SYNC_DEFAULT_URL);
const token = requiredSecret(process.env.VOCAB_SYNC_DEFAULT_TOKEN || process.env.VOCAB_SYNC_TOKEN);
const expectedCounts = { general: 120, academic: 40, inflected: 20, phrases: 10, polysemous: 10 };
const minimumCoverage = numberBetween(process.env.DICTIONARY_BENCHMARK_MIN_COVERAGE, 0.95, 0, 1);
const maximumBatchMs = positiveNumber(process.env.DICTIONARY_BENCHMARK_MAX_BATCH_MS, 10_000);
const outputPath = process.env.DICTIONARY_BENCHMARK_OUTPUT || defaultOutputPath();

const fixture = JSON.parse(await fs.readFile(fixturePath, "utf8"));
validateFixture(fixture);
const cases = flattenFixture(fixture);
const batches = chunk(cases, 50);
const observations = [];
const requestSummaries = [];

for (const [index, batch] of batches.entries()) {
  const requestId = `dict-bench-${crypto.randomUUID()}`;
  const started = performance.now();
  const response = await fetch(new URL("/dict/batch", endpoint), {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json; charset=utf-8",
      "X-Request-Id": requestId
    },
    body: JSON.stringify({ lemmas: batch.map((item) => item.lemma) }),
    signal: AbortSignal.timeout(maximumBatchMs + 2_000)
  });
  const durationMs = Math.round((performance.now() - started) * 10) / 10;
  const responseRequestId = response.headers.get("x-request-id") || requestId;
  if (!response.ok) {
    throw new Error(`Dictionary batch ${index + 1} failed with HTTP ${response.status} (request ${responseRequestId}).`);
  }
  const payload = await response.json();
  if (!Array.isArray(payload.results) || payload.results.length !== batch.length) {
    throw new Error(`Dictionary batch ${index + 1} returned an invalid result count (request ${responseRequestId}).`);
  }
  requestSummaries.push({
    batch: index + 1,
    itemCount: batch.length,
    durationMs,
    underMaximum: durationMs <= maximumBatchMs,
    requestId: responseRequestId
  });
  for (let itemIndex = 0; itemIndex < batch.length; itemIndex += 1) {
    const testCase = batch[itemIndex];
    const result = payload.results[itemIndex] || {};
    const hasKorean = nonEmpty(result.meaningKo);
    const hasEnglish = nonEmpty(result.definitionEn);
    observations.push({
      category: testCase.category,
      lemma: testCase.lemma,
      resolved: result.status === "resolved" && (hasKorean || hasEnglish),
      meaningKind: hasKorean && hasEnglish ? "korean_and_english" : hasKorean ? "korean" : hasEnglish ? "english" : "none",
      sourceType: nonEmpty(result.sourceType) ? result.sourceType : "none",
      confidence: finiteNumber(result.confidence),
      errorCode: nonEmpty(result.errorCode) ? result.errorCode : null,
      attemptedAt: nonEmpty(result.attemptedAt) ? result.attemptedAt : null,
      batchDurationMs: durationMs,
      requestId: responseRequestId
    });
  }
}

const groups = Object.fromEntries(
  Object.keys(expectedCounts).map((category) => [category, summarize(observations.filter((item) => item.category === category))])
);
const overall = summarize(observations);
const allBatchesUnderMaximum = requestSummaries.every((item) => item.underMaximum);
const passed = overall.coverage >= minimumCoverage && allBatchesUnderMaximum;
const polysemousReview = fixture.polysemous.map((item) => {
  const observation = observations.find((candidate) => candidate.category === "polysemous" && candidate.lemma === item.lemma);
  return {
    lemma: item.lemma,
    context: item.context,
    resolverStatus: observation?.resolved ? "resolved" : "unresolved",
    sourceType: observation?.sourceType || "none",
    humanContextAccuracy: null,
    reviewerNote: ""
  };
});

const report = {
  schemaVersion: 1,
  recordedAt: new Date().toISOString(),
  gitSha: await gitSha(),
  endpointOrigin: endpoint.origin,
  fixture: path.relative(root, fixturePath),
  thresholds: { minimumCoverage, maximumBatchMs },
  result: passed ? "passed" : "failed",
  note: "Definition text and bearer credentials are intentionally excluded. Polysemous accuracy remains a human-review gate.",
  overall,
  groups,
  requests: requestSummaries,
  observations,
  polysemousReview
};

await fs.mkdir(path.dirname(outputPath), { recursive: true, mode: 0o700 });
await fs.writeFile(outputPath, `${JSON.stringify(report, null, 2)}\n`, { mode: 0o600 });
console.log(`Dictionary benchmark: ${overall.resolved}/${overall.total} resolved (${(overall.coverage * 100).toFixed(1)}%).`);
for (const [category, summary] of Object.entries(groups)) {
  console.log(`- ${category}: ${summary.resolved}/${summary.total} (${(summary.coverage * 100).toFixed(1)}%)`);
}
console.log(`- p95 batch latency: ${overall.p95BatchDurationMs} ms`);
console.log("- polysemous context accuracy: NOT RUN (human review required)");
console.log(`Evidence: ${outputPath}`);
if (!passed) process.exitCode = 1;

function validateFixture(value) {
  for (const [category, expected] of Object.entries(expectedCounts)) {
    if (!Array.isArray(value[category]) || value[category].length !== expected) {
      throw new Error(`Benchmark category ${category} must contain exactly ${expected} cases.`);
    }
  }
  const lemmas = flattenFixture(value).map((item) => item.lemma.toLocaleLowerCase("en-US"));
  if (new Set(lemmas).size !== lemmas.length) throw new Error("Benchmark lemmas must be unique across categories.");
}

function flattenFixture(value) {
  return ["general", "academic", "inflected", "phrases"].flatMap((category) =>
    value[category].map((lemma) => ({ category, lemma }))
  ).concat(value.polysemous.map((item) => ({ category: "polysemous", lemma: item.lemma })));
}

function summarize(items) {
  const resolved = items.filter((item) => item.resolved).length;
  const batchDurations = items.map((item) => item.batchDurationMs).sort((a, b) => a - b);
  return {
    total: items.length,
    resolved,
    unresolved: items.length - resolved,
    coverage: items.length ? resolved / items.length : 0,
    p95BatchDurationMs: percentile(batchDurations, 0.95),
    koreanMeaningCount: items.filter((item) => item.meaningKind.includes("korean")).length,
    englishOnlyCount: items.filter((item) => item.meaningKind === "english").length
  };
}

function percentile(values, ratio) {
  if (values.length === 0) return 0;
  return values[Math.max(0, Math.ceil(values.length * ratio) - 1)];
}

function chunk(items, size) {
  return Array.from({ length: Math.ceil(items.length / size) }, (_, index) => items.slice(index * size, (index + 1) * size));
}

function nonEmpty(value) {
  return typeof value === "string" && value.trim() !== "";
}

function finiteNumber(value) {
  return Number.isFinite(Number(value)) ? Number(value) : 0;
}

function requiredUrl(value) {
  if (!value) throw new Error("VOCAB_SYNC_DEFAULT_URL is required.");
  const parsed = new URL(value);
  if (parsed.protocol !== "https:" && parsed.hostname !== "127.0.0.1" && parsed.hostname !== "localhost") {
    throw new Error("Dictionary benchmark endpoint must use HTTPS, except for localhost.");
  }
  return parsed;
}

function requiredSecret(value) {
  if (!value || value.trim() === "") throw new Error("VOCAB_SYNC_DEFAULT_TOKEN or VOCAB_SYNC_TOKEN is required.");
  return value;
}

function positiveNumber(value, fallback) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? number : fallback;
}

function numberBetween(value, fallback, minimum, maximum) {
  const number = Number(value);
  return Number.isFinite(number) && number >= minimum && number <= maximum ? number : fallback;
}

function defaultOutputPath() {
  const date = new Date().toISOString().slice(0, 10).replaceAll("-", "");
  const stamp = new Date().toISOString().replaceAll(/[-:.TZ]/g, "").slice(0, 14);
  return `/tmp/vocab_product_completion_${date}/dictionary_benchmark_${stamp}.json`;
}

async function gitSha() {
  const { execFile } = await import("node:child_process");
  return new Promise((resolve) => {
    execFile("git", ["-C", root, "rev-parse", "HEAD"], { encoding: "utf8" }, (error, stdout) => {
      resolve(error ? "unknown" : stdout.trim());
    });
  });
}
