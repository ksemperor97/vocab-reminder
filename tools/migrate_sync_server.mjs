#!/usr/bin/env node
// Copies everything from one sync server to another through the public API and
// checks the copy. Safe to run again: uploads are idempotent and cards merge,
// so a second run just brings over whatever changed on the old server since.
//
//   node tools/migrate_sync_server.mjs \
//     --from https://old.example --from-env /path/to/old/.env \
//     --to   https://new.example --to-env   "~/Library/Application Support/VocabReminderServer/.env"
//
// Tokens are read from VOCAB_SYNC_TOKEN in the given env files and never printed.
import fs from "node:fs";
import os from "node:os";

const collections = ["cards", "contexts", "definition_candidates", "review_events", "recommendation_feedback"];
const chunkSize = 1000;

function parseArgs(argv) {
  const options = {};
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index].replace(/^--/, "");
    options[key] = argv[index + 1];
  }
  for (const key of ["from", "from-env", "to", "to-env"]) {
    if (!options[key]) throw new Error(`--${key} is required`);
  }
  return options;
}

function tokenFrom(envPath) {
  const resolved = envPath.replace(/^~(?=\/)/, os.homedir());
  const line = fs.readFileSync(resolved, "utf8").split(/\r?\n/).find((entry) => entry.startsWith("VOCAB_SYNC_TOKEN="));
  const token = line?.slice("VOCAB_SYNC_TOKEN=".length).trim();
  if (!token) throw new Error(`VOCAB_SYNC_TOKEN is missing in ${resolved}`);
  return token;
}

async function request(base, token, path, body) {
  const response = await fetch(new URL(path, base.endsWith("/") ? base : `${base}/`), {
    method: body ? "POST" : "GET",
    headers: { Authorization: `Bearer ${token}`, ...(body ? { "Content-Type": "application/json" } : {}) },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(60_000)
  });
  if (!response.ok) throw new Error(`${body ? "POST" : "GET"} ${path} on ${base} returned ${response.status}`);
  return response.json();
}

function chunks(snapshot) {
  const count = Math.max(1, ...collections.map((name) => Math.ceil((snapshot[name]?.length ?? 0) / chunkSize)));
  return Array.from({ length: count }, (_, index) => ({
    ...Object.fromEntries(collections.map((name) => [name, (snapshot[name] ?? []).slice(index * chunkSize, (index + 1) * chunkSize)])),
    // Settings go with the last chunk, after every entity is in place.
    settings: index === count - 1 ? snapshot.settings ?? null : null
  }));
}

// What a person would notice if it were lost or changed.
function cardEssentials(card) {
  return JSON.stringify([
    card.lemma, card.meaning_ko, card.definition_en, card.source_sentence_primary, card.wordbook,
    card.status, Date.parse(card.due_at), card.interval_days, card.lapse_count,
    card.deleted_at ? "deleted" : "active", card.recommendation_started_at ? "started" : ""
  ]);
}

function compare(source, target) {
  const problems = [];
  for (const name of collections) {
    const targetIds = new Set((target[name] ?? []).map((row) => row.id));
    const missing = (source[name] ?? []).filter((row) => !targetIds.has(row.id));
    if (missing.length) problems.push(`${name}: ${missing.length} missing (e.g. ${missing[0].id})`);
  }
  const targetCards = new Map((target.cards ?? []).map((card) => [card.id, card]));
  const survivors = new Set((target.cards ?? []).map((card) => card.merged_into).filter(Boolean));
  const folded = [];
  for (const card of source.cards ?? []) {
    const copy = targetCards.get(card.id);
    if (!copy || cardEssentials(copy) === cardEssentials(card)) continue;
    // The new server folds active duplicates of one word: the survivor takes the
    // furthest learning progress and the other copy becomes a tombstone.
    if (copy.merged_into || survivors.has(copy.id)) folded.push(card.lemma);
    else problems.push(`card ${card.lemma} (${card.id}) differs`);
  }
  if (folded.length) console.log(`Folded duplicate cards (kept the furthest progress): ${[...new Set(folded)].join(", ")}`);
  return problems;
}

const options = parseArgs(process.argv.slice(2));
const fromToken = tokenFrom(options["from-env"]);
const toToken = tokenFrom(options["to-env"]);

const source = await request(options.from, fromToken, "sync/snapshot");
const counts = Object.fromEntries(collections.map((name) => [name, source[name]?.length ?? 0]));
console.log(`Read from ${options.from}: ${JSON.stringify(counts)}`);

let applied = 0;
let skipped = 0;
for (const batch of chunks(source)) {
  const result = await request(options.to, toToken, "sync/batch", batch);
  applied += Object.values(result.applied ?? {}).reduce((sum, value) => sum + (Number(value) || 0), 0);
  skipped += collections.reduce((sum, name) => sum + (Number(result.skipped?.[name]) || 0), 0);
  for (const reason of result.skipped?.reasons ?? []) console.log(`  skipped ${reason.collection} ${reason.id}: ${reason.reason}`);
}
console.log(`Wrote to ${options.to}: ${applied} rows applied, ${skipped} skipped`);

const target = await request(options.to, toToken, "sync/snapshot");
const problems = compare(source, target);
if (problems.length) {
  console.error(`Copy check found ${problems.length} problem(s):`);
  for (const problem of problems.slice(0, 20)) console.error(`  ${problem}`);
  process.exit(1);
}
const active = (target.cards ?? []).filter((card) => !card.deleted_at).length;
console.log(`Copy check passed: every row is on the new server; ${active} active cards there.`);
