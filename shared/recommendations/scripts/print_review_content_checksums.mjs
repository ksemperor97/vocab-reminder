#!/usr/bin/env node
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { contentChecksum, normalizeLemma } from "./review_pipeline_common.mjs";

const path = process.env.RECOMMENDATION_REVIEW_QUEUE;
if (!path) throw new Error("RECOMMENDATION_REVIEW_QUEUE is required.");
const payload = JSON.parse(readFileSync(resolve(path), "utf8"));
if (!Array.isArray(payload.entries)) throw new Error("Review queue entries must be an array.");

for (const entry of [...payload.entries].sort((left, right) =>
  normalizeLemma(left.lemma).localeCompare(normalizeLemma(right.lemma), "en"))) {
  try {
    console.log(`${normalizeLemma(entry.lemma)} ${contentChecksum(entry)}`);
  } catch (error) {
    console.log(`${normalizeLemma(entry.lemma)} INCOMPLETE ${error.message}`);
  }
}
