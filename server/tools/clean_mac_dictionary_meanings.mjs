#!/usr/bin/env node
// Replaces raw macOS dictionary text in card meanings with the short meaning
// (server/src/mac_dictionary_meaning.js), through the sync API so every device
// picks it up. Only cards whose meaning still comes from the Mac dictionary
// (definition_source_primary = mac_dictionary) are touched; a meaning the user
// typed or chose has source "user" and is left alone. When the rule finds no
// Korean gloss, the local model named in the runtime .env is asked instead.
//
//   node server/tools/clean_mac_dictionary_meanings.mjs            # show what would change
//   node server/tools/clean_mac_dictionary_meanings.mjs --apply    # write it
//
// Options: --url (default VOCAB_SYNC_PUBLIC_URL or http://127.0.0.1:<port>),
// --env (default the server runtime .env). The token is never printed.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { cleanMacDictionaryMeaning, macDictionaryPartOfSpeech } from "../src/mac_dictionary_meaning.js";

const runtimeRoot = process.env.VOCAB_SYNC_RUNTIME_ROOT ?? path.join(os.homedir(), "Library/Application Support/VocabReminderServer");
const args = process.argv.slice(2);
const option = (name, fallback) => {
  const index = args.indexOf(`--${name}`);
  return index >= 0 ? args[index + 1] : fallback;
};
const apply = args.includes("--apply");
const env = readEnv(option("env", path.join(runtimeRoot, ".env")));
const token = env.VOCAB_SYNC_TOKEN;
if (!token) throw new Error("VOCAB_SYNC_TOKEN is missing from the server env file.");
const base = option("url", `http://127.0.0.1:${env.VOCAB_SYNC_PORT ?? 8787}`);

const snapshot = await request("sync/snapshot");
const now = new Date().toISOString();
const changed = [];
for (const card of snapshot.cards ?? []) {
  if (card.deleted_at || card.definition_source_primary !== "mac_dictionary") continue;
  const candidate = (snapshot.definition_candidates ?? [])
    .filter((entry) => entry.card_id === card.id && entry.source === "mac_dictionary" && entry.raw_text)
    .sort((a, b) => String(b.created_at).localeCompare(String(a.created_at)))[0];
  if (!candidate) continue;
  let meaning = cleanMacDictionaryMeaning(candidate.raw_text);
  let how = "rule";
  if (!meaning) {
    meaning = await askLocalModel(card.lemma, candidate.raw_text);
    how = "local model";
  }
  if (!meaning) {
    console.log(`  skip ${card.lemma}: no Korean gloss found`);
    continue;
  }
  const headwordJunk = /·|\s\d+$/u.test(card.definition_en ?? "") || (card.definition_en ?? "").replace(/[·\s\d]/gu, "") === card.lemma;
  const partOfSpeech = card.part_of_speech || macDictionaryPartOfSpeech(candidate.raw_text);
  if (meaning === card.meaning_ko && !headwordJunk && partOfSpeech === card.part_of_speech) continue;
  console.log(`  ${card.lemma}: ${JSON.stringify(card.meaning_ko)} -> ${JSON.stringify(meaning)} (${how})`);
  changed.push({
    ...card,
    meaning_ko: meaning,
    definition_en: headwordJunk ? "" : card.definition_en,
    part_of_speech: partOfSpeech,
    content_version: (Number(card.content_version) || Number(card.version) || 1) + 1,
    content_updated_at: now,
    version: (Number(card.version) || 1) + 1,
    updated_at: now
  });
}

if (!apply) {
  console.log(`${changed.length} card(s) would change. Run again with --apply to write them.`);
} else if (changed.length > 0) {
  const result = await request("sync/batch", { cards: changed });
  console.log(`Updated ${result.applied?.cards ?? 0} of ${changed.length} card(s).`);
} else {
  console.log("Nothing to change.");
}

async function request(route, body) {
  const response = await fetch(new URL(route, base.endsWith("/") ? base : `${base}/`), {
    method: body ? "POST" : "GET",
    headers: { Authorization: `Bearer ${token}`, ...(body ? { "Content-Type": "application/json" } : {}) },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(60_000)
  });
  if (!response.ok) throw new Error(`${route} returned ${response.status}`);
  return response.json();
}

async function askLocalModel(lemma, raw) {
  if ((env.VOCAB_CONTEXT_RESOLVER ?? "").toLowerCase() !== "local" || !env.VOCAB_CONTEXT_MODEL) return "";
  const baseUrl = (env.VOCAB_CONTEXT_BASE_URL ?? "http://127.0.0.1:1234/v1").replace(/\/+$/u, "");
  try {
    const response = await fetch(`${baseUrl}/chat/completions`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        model: env.VOCAB_CONTEXT_MODEL,
        temperature: 0,
        max_tokens: 512,
        messages: [
          { role: "system", content: "From a Korean-English dictionary entry, reply with only the first sense's Korean meaning as one to three short glosses separated by \", \". No labels, no English, no explanation." },
          { role: "user", content: `word: ${lemma}\nentry:\n${raw.slice(0, 1500)}` }
        ]
      }),
      signal: AbortSignal.timeout(60_000)
    });
    const message = (await response.json())?.choices?.[0]?.message ?? {};
    const text = String(message.content || message.reasoning_content || "").replace(/<think>[\s\S]*?<\/think>/gu, "").trim();
    return /[가-힣]/u.test(text) && text.length <= 40 ? text : "";
  } catch {
    return "";
  }
}

function readEnv(file) {
  const values = {};
  for (const line of fs.readFileSync(file, "utf8").split(/\r?\n/u)) {
    const match = line.match(/^([A-Z0-9_]+)=(.*)$/u);
    if (match) values[match[1]] = match[2].trim().replace(/^['"]|['"]$/gu, "");
  }
  return values;
}
