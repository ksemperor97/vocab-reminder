#!/usr/bin/env node
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import {
  REVIEW_CHECKS,
  RELEASE_TOPICS,
  contentChecksum,
  normalizeLemma,
  releaseContent,
  sha256,
  validatePromotionArtifact
} from "./review_pipeline_common.mjs";

const reviewQueuePath = requiredPath("RECOMMENDATION_REVIEW_QUEUE");
const outputPath = requiredPath("RECOMMENDATION_REVIEWED_PROMOTIONS_OUTPUT");
const payload = JSON.parse(readFileSync(reviewQueuePath, "utf8"));
if (payload?.schemaVersion !== 1 || payload?.reviewPolicyVersion !== 1) {
  throw new Error("Review queue schemaVersion and reviewPolicyVersion must both be 1.");
}
if (!payload.sourceSnapshot) throw new Error("Review queue must preserve sourceSnapshot.");
if (!Array.isArray(payload.entries)) throw new Error("Review queue entries must be an array.");

const seen = new Set();
const approved = [];
const skipped = { pending: 0, rejected: 0 };
for (const entry of payload.entries) {
  const lemma = normalizeLemma(entry.lemma);
  if (!lemma) throw new Error("Review queue contains an empty lemma.");
  if (seen.has(lemma)) throw new Error(`Review queue contains duplicate lemma: ${lemma}`);
  seen.add(lemma);
  const status = entry.review?.status;
  if (status === "pending" || status === "rejected") {
    skipped[status] += 1;
    continue;
  }
  if (status !== "approved") throw new Error(`${lemma} has unsupported review status ${String(status)}.`);

  const content = releaseContent(entry);
  if (!RELEASE_TOPICS.has(content.topic)) throw new Error(`${lemma} has unsupported topic ${content.topic}.`);
  if (!entry.review.reviewerId?.trim()) throw new Error(`${lemma} approved review requires reviewerId.`);
  if (!isIsoDate(entry.review.reviewedAt)) throw new Error(`${lemma} approved review requires an ISO-8601 reviewedAt value.`);
  for (const check of REVIEW_CHECKS) {
    if (entry.review.checks?.[check] !== true) throw new Error(`${lemma} approved review check ${check} is not true.`);
  }
  const expectedContentChecksum = contentChecksum(content);
  if (entry.review.contentChecksumSha256 !== expectedContentChecksum) {
    throw new Error(`${lemma} changed after review; expected content checksum ${expectedContentChecksum}.`);
  }
  if (entry.provenance?.sourceId !== payload.sourceSnapshot.sourceId ||
      entry.provenance?.datasetVersion !== payload.sourceSnapshot.datasetVersion ||
      entry.provenance?.dataChecksumSha256 !== payload.sourceSnapshot.dataChecksumSha256 ||
      entry.provenance?.metadataChecksumSha256 !== payload.sourceSnapshot.metadataChecksumSha256) {
    throw new Error(`${lemma} source provenance does not match the review queue snapshot.`);
  }

  approved.push({
    ...content,
    releaseEligible: true,
    provenance: entry.provenance,
    review: {
      status: "approved",
      reviewerId: entry.review.reviewerId.trim(),
      reviewedAt: entry.review.reviewedAt,
      contentChecksumSha256: expectedContentChecksum,
      checks: Object.fromEntries(REVIEW_CHECKS.map((check) => [check, true])),
      notes: typeof entry.review.notes === "string" ? entry.review.notes.trim() : ""
    }
  });
}

approved.sort((left, right) => left.frequencyRank - right.frequencyRank || left.lemma.localeCompare(right.lemma, "en"));
const artifact = {
  schemaVersion: 1,
  promotionPolicyVersion: 1,
  sourceSnapshots: [payload.sourceSnapshot],
  sourceReviewQueueChecksumSha256: fileChecksum(reviewQueuePath),
  totalReviewedEntries: payload.entries.length,
  approvedEntries: approved.length,
  skipped,
  entries: approved
};

// Revalidate the exact artifact consumed by the release-catalogue builder.
validatePromotionArtifact(artifact);
mkdirSync(dirname(outputPath), { recursive: true });
writeFileSync(outputPath, `${JSON.stringify(artifact, null, 2)}\n`);
console.log(`Promoted ${approved.length} approved candidates; pending ${skipped.pending}, rejected ${skipped.rejected}.`);
console.log(`Reviewed promotions: ${outputPath}`);

function requiredPath(name) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required.`);
  return resolve(value);
}

function fileChecksum(path) {
  return sha256(readFileSync(path));
}

function isIsoDate(value) {
  return typeof value === "string" &&
    /^\d{4}-\d{2}-\d{2}(?:T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z)?$/.test(value) &&
    !Number.isNaN(Date.parse(value));
}
