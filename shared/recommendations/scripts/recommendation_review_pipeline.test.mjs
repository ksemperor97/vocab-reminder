import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import test from "node:test";
import { contentChecksum } from "./review_pipeline_common.mjs";

const __dirname = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(__dirname, "../../..");
const importer = resolve(__dirname, "import_licensed_lexical_jsonl.mjs");
const promoter = resolve(__dirname, "promote_reviewed_candidates.mjs");
const builder = resolve(__dirname, "build_catalog.mjs");
const sourcePath = resolve(repoRoot, "shared/recommendations/fixtures/licensed_wiktextract_fixture.jsonl");
const manifestPath = resolve(repoRoot, "shared/recommendations/fixtures/licensed_wiktextract_fixture.source.json");
const queuePath = resolve(repoRoot, "shared/recommendations/fixtures/licensed_import_queue_fixture.json");

test("licensed JSONL import is deterministic and always starts pending", () => {
  withTempDir((directory) => {
    const first = resolve(directory, "review-first.json");
    const second = resolve(directory, "review-second.json");
    run(importer, importEnv(first));
    run(importer, importEnv(second));
    assert.equal(readFileSync(first, "utf8"), readFileSync(second, "utf8"));

    const payload = JSON.parse(readFileSync(first, "utf8"));
    assert.deepEqual(payload.entries.map((entry) => entry.lemma), ["hitchhike", "clout"]);
    assert.equal(payload.entries[0].definitionEn, "to travel by asking passing vehicles for a free ride");
    assert.equal(payload.entries[0].meaningKo, "히치하이크하다");
    assert.ok(payload.entries.every((entry) => entry.review.status === "pending"));
    assert.ok(payload.entries.every((entry) => entry.releaseEligible === false));
    assert.equal(payload.sourceSnapshot.shareAlikeRequired, true);
    assert.equal(payload.sourceSnapshot.licenses.length, 2);
  });
});

test("source checksum mismatch fails closed", () => {
  withTempDir((directory) => {
    const tamperedSource = resolve(directory, "tampered.jsonl");
    writeFileSync(tamperedSource, `${readFileSync(sourcePath, "utf8")} `);
    const result = run(importer, {
      ...importEnv(resolve(directory, "review.json")),
      RECOMMENDATION_LEXICAL_JSONL: tamperedSource
    }, false);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /checksum mismatch/i);
  });
});

test("only checksum-bound approved human review reaches a deterministic promotion and catalog", () => {
  withTempDir((directory) => {
    const pendingPath = resolve(directory, "pending.json");
    run(importer, importEnv(pendingPath));
    const reviewed = JSON.parse(readFileSync(pendingPath, "utf8"));
    const approved = reviewed.entries.find((entry) => entry.lemma === "hitchhike");
    approved.topic = "general";
    approved.fillStatus = "complete";
    approved.review = {
      status: "approved",
      reviewerId: "fixture-human-reviewer",
      reviewedAt: "2026-07-13T00:00:00Z",
      contentChecksumSha256: contentChecksum(approved),
      checks: {
        definitionAccuracy: true,
        translationAccuracy: true,
        exampleQuality: true,
        topicFit: true,
        licenseAndAttribution: true
      },
      notes: "Synthetic fixture approval for pipeline testing only."
    };
    const reviewedPath = resolve(directory, "reviewed.json");
    writeFileSync(reviewedPath, `${JSON.stringify(reviewed, null, 2)}\n`);

    const promotionOne = resolve(directory, "promotions-one.json");
    const promotionTwo = resolve(directory, "promotions-two.json");
    run(promoter, promotionEnv(reviewedPath, promotionOne));
    run(promoter, promotionEnv(reviewedPath, promotionTwo));
    assert.equal(readFileSync(promotionOne, "utf8"), readFileSync(promotionTwo, "utf8"));
    const promotions = JSON.parse(readFileSync(promotionOne, "utf8"));
    assert.deepEqual(promotions.entries.map((entry) => entry.lemma), ["hitchhike"]);
    assert.deepEqual(promotions.skipped, { pending: 1, rejected: 0 });

    const first = buildPaths(directory, "first");
    const second = buildPaths(directory, "second");
    run(builder, buildEnv(promotionOne, first));
    run(builder, buildEnv(promotionOne, second));
    assert.equal(readFileSync(first.catalog, "utf8"), readFileSync(second.catalog, "utf8"));

    const baselineCount = JSON.parse(readFileSync(resolve(repoRoot, "shared/recommendations/recommendation_catalog.json"), "utf8")).entries.length;
    const built = JSON.parse(readFileSync(first.catalog, "utf8"));
    assert.equal(built.entries.length, baselineCount + 1);
    const imported = built.entries.find((entry) => entry.lemma === "hitchhike");
    assert.equal(imported.reviewStatus, "approved");
    assert.equal(imported.entryId, "recommendation:hitchhike");
    assert.equal(built.contentSources.find((source) => source.sourceId === imported.contentSourceId).shareAlikeRequired, true);

    const pendingBuild = run(builder, buildEnv(pendingPath, buildPaths(directory, "pending")), false);
    assert.notEqual(pendingBuild.status, 0);
    assert.match(pendingBuild.stderr, /promotion artifact/i);

    const metadataTampered = structuredClone(reviewed);
    metadataTampered.sourceSnapshot.attribution = "tampered attribution after import";
    const metadataTamperedPath = resolve(directory, "metadata-tampered.json");
    writeFileSync(metadataTamperedPath, `${JSON.stringify(metadataTampered, null, 2)}\n`);
    const metadataTamperedPromotion = run(
      promoter,
      promotionEnv(metadataTamperedPath, resolve(directory, "metadata-tampered-promotion.json")),
      false
    );
    assert.notEqual(metadataTamperedPromotion.status, 0);
    assert.match(metadataTamperedPromotion.stderr, /metadata changed after import/i);

    approved.definitionEn = "tampered after review";
    writeFileSync(reviewedPath, `${JSON.stringify(reviewed, null, 2)}\n`);
    const tamperedPromotion = run(promoter, promotionEnv(reviewedPath, resolve(directory, "tampered-promotion.json")), false);
    assert.notEqual(tamperedPromotion.status, 0);
    assert.match(tamperedPromotion.stderr, /changed after review/i);
  });
});

function importEnv(output) {
  return {
    RECOMMENDATION_LEXICAL_JSONL: sourcePath,
    RECOMMENDATION_LEXICAL_SOURCE_MANIFEST: manifestPath,
    RECOMMENDATION_IMPORT_QUEUE: queuePath,
    RECOMMENDATION_REVIEW_QUEUE_OUTPUT: output
  };
}

function promotionEnv(reviewQueue, output) {
  return {
    RECOMMENDATION_REVIEW_QUEUE: reviewQueue,
    RECOMMENDATION_REVIEWED_PROMOTIONS_OUTPUT: output
  };
}

function buildPaths(directory, prefix) {
  return {
    catalog: resolve(directory, `${prefix}-catalog.json`)
  };
}

function buildEnv(promotions, paths) {
  return {
    RECOMMENDATION_REVIEWED_PROMOTIONS: promotions,
    RECOMMENDATION_CATALOG_OUTPUT: paths.catalog
  };
}

function run(script, extraEnv, expectSuccess = true) {
  const result = spawnSync(process.execPath, [script], {
    cwd: repoRoot,
    env: { ...process.env, ...extraEnv },
    encoding: "utf8"
  });
  if (expectSuccess && result.status !== 0) {
    assert.fail(`${script} failed\nstdout:\n${result.stdout}\nstderr:\n${result.stderr}`);
  }
  return result;
}

function withTempDir(block) {
  const directory = mkdtempSync(resolve(tmpdir(), "vocab-recommendation-review-"));
  try {
    block(directory);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}
