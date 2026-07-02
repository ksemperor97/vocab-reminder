import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(__dirname, "../../..");
const limit = Number.parseInt(process.env.RECOMMENDATION_QUEUE_LIMIT ?? "1000", 10);

const poolPath = resolve(repoRoot, "shared/recommendations/recommendation_candidate_pool.jsonl");
const catalogPath = resolve(repoRoot, "shared/recommendations/recommendation_catalog.json");
const catalog = JSON.parse(readFileSync(catalogPath, "utf8"));
const catalogLemmas = new Set(catalog.entries.map((entry) => entry.lemma.toLowerCase()));

const pool = readFileSync(poolPath, "utf8")
  .trim()
  .split("\n")
  .map((line) => JSON.parse(line));

const queue = pool
  .filter((entry) => entry.candidate && !catalogLemmas.has(entry.normalized))
  .sort(compareQueuePriority)
  .slice(0, limit)
  .map((entry, index) => ({
    queueRank: index + 1,
    lemma: entry.normalized,
    sourceWord: entry.word,
    frequencyRank: entry.rank,
    frequencyCount: entry.count,
    bucket: entry.bucket,
    fillStatus: "needs_definition_translation_topic"
  }));

const payload = {
  generatedAt: "2026-07-02",
  sourcePool: "shared/recommendations/recommendation_candidate_pool.jsonl",
  excludedCatalogue: "shared/recommendations/recommendation_catalog.json",
  limit,
  totalQueued: queue.length,
  queue
};

writeFileSync(
  resolve(repoRoot, "shared/recommendations/recommendation_fill_queue.json"),
  JSON.stringify(payload, null, 2) + "\n"
);

console.log(`Wrote ${queue.length} recommendation fill queue entries`);

function compareQueuePriority(a, b) {
  return bucketPriority(a.bucket) - bucketPriority(b.bucket) ||
    distanceFromTarget(a.rank) - distanceFromTarget(b.rank) ||
    b.count - a.count ||
    a.normalized.localeCompare(b.normalized);
}

function bucketPriority(bucket) {
  return {
    boundary: 0,
    intermediate: 1,
    advanced: 2,
    rare: 3,
    core: 4
  }[bucket] ?? 5;
}

function distanceFromTarget(rank) {
  return Math.abs(rank - 18_000);
}
