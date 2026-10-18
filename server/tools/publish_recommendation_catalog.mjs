#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import os from "node:os";
import { fileURLToPath } from "node:url";
import { createRecommendationCatalogLoader } from "../src/recommendations.js";

const source = path.resolve(process.argv[2] ?? fileURLToPath(new URL("../../shared/recommendations/recommendation_catalog.json", import.meta.url)));
const target = path.resolve(process.argv[3] ?? process.env.VOCAB_RECOMMENDATION_CATALOG_PATH ??
  path.join(os.homedir(), "Library/Application Support/VocabReminderServer/current/catalog/recommendation_catalog.json"));
const temporary = `${target}.${process.pid}.tmp`;
try {
  // Validate the exact bytes being published before touching the active catalogue.
  fs.writeFileSync(temporary, fs.readFileSync(source), { mode: 0o600, flag: "wx" });
  const entries = createRecommendationCatalogLoader(temporary)();
  if (fs.existsSync(target)) fs.copyFileSync(target, `${target}.previous`);
  fs.renameSync(temporary, target);
  console.log(`Published ${entries.length} server recommendation entries. The next sync uses this catalogue; no app update or server restart is required.`);
} catch (error) {
  console.error(`Recommendation publication failed: ${error.message}`);
  process.exitCode = 1;
} finally {
  fs.rmSync(temporary, { force: true });
}
