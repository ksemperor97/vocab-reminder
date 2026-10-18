#!/usr/bin/env node

import path from "node:path";
import {
  ensureDailyBackup,
  latestBackupPath,
  migrateDatabaseFile,
  restoreBackup,
  verifyBackup,
  verifyDatabaseFile,
  writeJsonAtomic
} from "../src/backup.js";
import { checkpointDatabase, inspectDatabase, openDatabase } from "../src/db.js";
import { runRestoreDrill } from "../src/restore_drill.js";

const [command, ...rawArgs] = process.argv.slice(2);

try {
  const options = parseOptions(rawArgs);
  let result;

  switch (command) {
    case "initialize":
      result = initializeDatabase(required(options, "db"));
      break;
    case "inspect":
      result = verifyDatabaseFile(required(options, "db"));
      break;
    case "checkpoint":
      result = checkpoint(required(options, "db"));
      break;
    case "migrate":
      result = await migrateDatabaseFile(
        required(options, "source"),
        required(options, "destination")
      );
      break;
    case "backup":
      result = await backupNow(
        required(options, "db"),
        required(options, "backup-dir"),
        numberOption(options, "retention", 30)
      );
      break;
    case "verify-backup":
      result = verifyBackup(required(options, "backup"), {
        requireManifest: Boolean(options["require-manifest"])
      });
      break;
    case "restore":
      result = restoreBackup(
        required(options, "backup"),
        required(options, "destination")
      );
      break;
    case "restore-drill":
      if (!options.backup && !options["backup-dir"]) {
        throw new Error("restore-drill requires --backup or --backup-dir.");
      }
      result = await runRestoreDrill({
        backupPath: options.backup,
        backupDir: options["backup-dir"],
        statePath: options.state
      });
      break;
    case "help":
    case "--help":
    case "-h":
    case undefined:
      printHelp();
      process.exit(command ? 0 : 64);
      break;
    default:
      throw new Error(`Unknown command: ${command}`);
  }

  if (options.report) writeJsonAtomic(options.report, result);
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
} catch (error) {
  process.stderr.write(`db_admin: ${String(error?.message ?? error)}\n`);
  process.exitCode = 1;
}

function initializeDatabase(dbPath) {
  const db = openDatabase(dbPath);
  try {
    return {
      path: path.resolve(dbPath),
      created: true,
      summary: inspectDatabase(db)
    };
  } finally {
    db.close();
  }
}

function checkpoint(dbPath) {
  const db = openDatabase(dbPath);
  try {
    return {
      path: path.resolve(dbPath),
      checkpoint: checkpointDatabase(db, "TRUNCATE"),
      summary: inspectDatabase(db)
    };
  } finally {
    db.close();
  }
}

async function backupNow(dbPath, backupDir, retention) {
  const db = openDatabase(dbPath);
  try {
    const created = await ensureDailyBackup(db, dbPath, backupDir, retention);
    const selected = created ?? latestBackupPath(backupDir);
    if (!selected) throw new Error("Backup was not created.");
    return {
      created,
      selected,
      verification: verifyBackup(selected, { requireManifest: true })
    };
  } finally {
    db.close();
  }
}

function parseOptions(args) {
  const options = {};
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    if (!argument.startsWith("--")) throw new Error(`Unexpected argument: ${argument}`);
    const key = argument.slice(2);
    if (key === "require-manifest") {
      options[key] = true;
      continue;
    }
    const value = args[index + 1];
    if (value == null || value.startsWith("--")) throw new Error(`Missing value for --${key}.`);
    options[key] = value;
    index += 1;
  }
  return options;
}

function required(options, key) {
  const value = options[key];
  if (!value) throw new Error(`--${key} is required.`);
  return value;
}

function numberOption(options, key, fallback) {
  if (options[key] == null) return fallback;
  const value = Number(options[key]);
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error(`--${key} must be a positive integer.`);
  }
  return value;
}

function printHelp() {
  const lines = [
    "Usage: node tools/db_admin.mjs <command> [options]",
    "",
    "Commands:",
    "  initialize    --db PATH",
    "  inspect       --db PATH",
    "  checkpoint    --db PATH",
    "  migrate       --source PATH --destination PATH [--report PATH]",
    "  backup        --db PATH --backup-dir PATH [--retention N]",
    "  verify-backup --backup PATH [--require-manifest]",
    "  restore       --backup PATH --destination EMPTY_PATH",
    "  restore-drill (--backup PATH | --backup-dir PATH) [--state PATH]"
  ];
  process.stdout.write(`${lines.join("\n")}\n`);
}
