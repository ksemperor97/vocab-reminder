import fs from "node:fs";
import { nodeRuntimeCompatibility, parkIncompatibleRuntime } from "./node_runtime.js";

const expectedNodeMajor = fs.readFileSync(new URL("../.node-version", import.meta.url), "utf8").trim();
const compatibility = nodeRuntimeCompatibility(process.versions.node, expectedNodeMajor);
if (!compatibility.compatible) {
  await parkIncompatibleRuntime(compatibility);
}

const [
  { ensureDailyBackup },
  { readConfig },
  { openDatabase },
  { createSyncServer }
] = await Promise.all([
  import("./backup.js"),
  import("./config.js"),
  import("./db.js"),
  import("./http.js")
]);

const config = readConfig();
const db = openDatabase(config.dbPath);
await ensureDailyBackup(db, config.dbPath, config.backupDir, config.backupRetention);

const { server, maintain } = createSyncServer({
  db,
  dbPath: config.dbPath,
  backupDir: config.backupDir,
  backupRetention: config.backupRetention,
  token: config.token,
  appUpdateDir: config.appUpdateDir,
  maxBodyBytes: config.maxBodyBytes,
  maxCollectionItems: config.maxCollectionItems,
  requestLogger(entry) {
    console.log(JSON.stringify({ event: "sync_request", ...entry }));
  }
});

server.listen(config.port, config.host, () => {
  console.log(`vocab-sync listening on http://${config.host}:${config.port}`);
});

function runMaintenance() {
  try {
    const result = maintain();
    if (result.generated || result.retired) console.log(JSON.stringify({ event: "recommendation_maintenance", ...result }));
  } catch (error) {
    console.error("Recommendation maintenance failed", error);
  }
}
runMaintenance();
setInterval(runMaintenance, 10 * 60 * 1000).unref();

setInterval(() => {
  ensureDailyBackup(db, config.dbPath, config.backupDir, config.backupRetention)
    .catch((error) => console.error("Daily backup failed", error));
}, 60 * 60 * 1000).unref();

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => {
    server.close(() => {
      db.close();
      process.exit(0);
    });
  });
}
