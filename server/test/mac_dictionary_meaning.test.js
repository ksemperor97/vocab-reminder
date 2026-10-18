import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import { cleanMacDictionaryMeaning, macDictionaryPartOfSpeech } from "../src/mac_dictionary_meaning.js";

const vectors = JSON.parse(fs.readFileSync(new URL("../../shared/test-vectors/mac_dictionary_meanings.json", import.meta.url), "utf8"));

test("shared vectors: raw Korean-English dictionary entries become short meanings", () => {
  assert.ok(vectors.cases.length > 0);
  for (const entry of vectors.cases) {
    assert.equal(cleanMacDictionaryMeaning(entry.raw), entry.expected_meaning_ko, entry.lemma);
    assert.equal(macDictionaryPartOfSpeech(entry.raw), entry.expected_part_of_speech, entry.lemma);
  }
});

test("entries without Korean give no meaning", () => {
  assert.equal(cleanMacDictionaryMeaning("pervasive per·va·sive | adjective spreading widely"), "");
  assert.equal(cleanMacDictionaryMeaning(null), "");
});
