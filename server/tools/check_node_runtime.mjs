import fs from "node:fs";
import Database from "better-sqlite3";

const expected = fs.readFileSync(new URL("../.node-version", import.meta.url), "utf8").trim();
try {
  if (process.versions.node.split(".")[0] !== expected) throw new Error(`Expected Node ${expected}, found ${process.version}.`);
  const db = new Database(":memory:");
  db.prepare("SELECT 1").get();
  db.close();
} catch (error) {
  console.error(`Server runtime preflight failed (Node ${process.version}, ABI ${process.versions.modules}).`);
  console.error("Select the Node version in server/.node-version, then run npm --prefix server rebuild better-sqlite3 --build-from-source.");
  console.error(error.code ?? error.message);
  process.exitCode = 1;
}
