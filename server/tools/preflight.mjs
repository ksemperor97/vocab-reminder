#!/usr/bin/env node

import fs from "node:fs";
import net from "node:net";
import path from "node:path";
import { inspectDatabase, openDatabase } from "../src/db.js";

let options = {};
let dbPath;

try {
  options = parseOptions(process.argv.slice(2));
  dbPath = required(options, "db");
  const host = options.host ?? "127.0.0.1";
  const port = integerOption(options, "port", 0);
  const db = openDatabase(dbPath);
  let summary;
  try {
    summary = inspectDatabase(db);
  } finally {
    db.close();
  }

  const boundAddress = await testBind(host, port);
  const result = {
    status: "passed",
    nodeBinary: process.execPath,
    nodeVersion: process.version,
    nodeModuleAbi: process.versions.modules,
    databasePath: path.resolve(dbPath),
    database: summary,
    bind: boundAddress
  };
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
} catch (error) {
  process.stderr.write(`preflight: ${String(error?.message ?? error)}\n`);
  process.exitCode = 1;
} finally {
  if (options["remove-db"] && dbPath) removeDatabaseFiles(dbPath);
}

function testBind(hostname, requestedPort) {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once("error", reject);
    server.listen(requestedPort, hostname, () => {
      const address = server.address();
      server.close((error) => {
        if (error) reject(error);
        else resolve(address);
      });
    });
  });
}

function parseOptions(args) {
  const parsed = {};
  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    if (!argument.startsWith("--")) throw new Error(`Unexpected argument: ${argument}`);
    const key = argument.slice(2);
    if (key === "remove-db") {
      parsed[key] = true;
      continue;
    }
    const value = args[index + 1];
    if (value == null || value.startsWith("--")) throw new Error(`Missing value for --${key}.`);
    parsed[key] = value;
    index += 1;
  }
  return parsed;
}

function required(parsed, key) {
  if (!parsed[key]) throw new Error(`--${key} is required.`);
  return parsed[key];
}

function integerOption(parsed, key, fallback) {
  if (parsed[key] == null) return fallback;
  const value = Number(parsed[key]);
  if (!Number.isSafeInteger(value) || value < 0 || value > 65535) {
    throw new Error(`--${key} must be an integer from 0 through 65535.`);
  }
  return value;
}

function removeDatabaseFiles(databasePath) {
  for (const suffix of ["", "-wal", "-shm"]) {
    fs.rmSync(`${databasePath}${suffix}`, { force: true });
  }
}
