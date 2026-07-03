import { ensureDailyBackup } from "./backup.js";
import { readConfig } from "./config.js";
import { openDatabase } from "./db.js";
import { createSyncServer } from "./http.js";

const config = readConfig();
const db = openDatabase(config.dbPath);
await ensureDailyBackup(db, config.dbPath, config.backupDir, config.backupRetention);

const { server } = createSyncServer({
  db,
  dbPath: config.dbPath,
  backupDir: config.backupDir,
  backupRetention: config.backupRetention,
  token: config.token
});

server.listen(config.port, config.host, () => {
  console.log(`vocab-sync listening on http://${config.host}:${config.port}`);
});

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
