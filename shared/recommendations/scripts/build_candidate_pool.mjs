import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(__dirname, "../../..");
const subtlexPath = process.env.SUBTLEX_INDEX_JSON ?? resolve(repoRoot, "node_modules/subtlex-word-frequencies/index.json");
const subtlex = JSON.parse(readFileSync(subtlexPath, "utf8"));

const buckets = [
  ["core", 1, 5_000],
  ["intermediate", 5_001, 15_000],
  ["boundary", 15_001, 30_000],
  ["advanced", 30_001, 55_000],
  ["rare", 55_001, Number.POSITIVE_INFINITY]
];

const stopwords = new Set([
  "about", "after", "again", "also", "because", "before", "being", "between", "could", "every",
  "from", "have", "into", "just", "more", "most", "other", "over", "same", "some",
  "such", "than", "that", "their", "them", "then", "there", "these", "they", "this",
  "those", "through", "under", "very", "were", "what", "when", "where", "which", "while",
  "with", "would", "your"
]);

const entries = subtlex.map((entry, index) => {
  const word = String(entry.word);
  const normalized = word.toLowerCase();
  const rank = index + 1;
  const alphabetic = /^[a-z][a-z'-]*[a-z]$/.test(normalized);
  const candidate = alphabetic &&
    normalized.length >= 4 &&
    rank >= 5_000 &&
    rank <= 70_000 &&
    !stopwords.has(normalized);
  return {
    rank,
    word,
    normalized,
    count: entry.count,
    bucket: bucketForRank(rank),
    candidate
  };
});

if (entries.length !== 74_286) {
  throw new Error(`Unexpected SUBTLEX entry count: ${entries.length}`);
}
if (entries[0]?.rank !== 1 || entries[0]?.word !== "you") {
  throw new Error("Unexpected SUBTLEX ordering; rank assumptions are unsafe.");
}

const meta = {
  source: {
    package: "subtlex-word-frequencies",
    version: "2.0.0",
    license: "ISC",
    repository: "https://github.com/words/subtlex-word-frequencies",
    rankDefinition: "1-based index in index.json sorted by descending SUBTLEXus count"
  },
  generatedAt: "2026-07-02",
  totalEntries: entries.length,
  candidateEntries: entries.filter((entry) => entry.candidate).length,
  buckets: Object.fromEntries(buckets.map(([name, start, end]) => [
    name,
    entries.filter((entry) => entry.rank >= start && entry.rank <= end).length
  ])),
  notes: [
    "This pool is a reproducible source candidate list, not the app-bundled recommendation catalogue.",
    "Definitions, Korean meanings, examples, and domain topics are filled only in recommendation_catalog.json for now."
  ]
};

writeFileSync(
  resolve(repoRoot, "shared/recommendations/recommendation_candidate_pool.meta.json"),
  JSON.stringify(meta, null, 2) + "\n"
);
writeFileSync(
  resolve(repoRoot, "shared/recommendations/recommendation_candidate_pool.jsonl"),
  entries.map((entry) => JSON.stringify(entry)).join("\n") + "\n"
);

console.log(`Wrote ${entries.length} ranked candidates (${meta.candidateEntries} eligible)`);

function bucketForRank(rank) {
  return buckets.find(([, start, end]) => rank >= start && rank <= end)?.[0] ?? "unknown";
}
