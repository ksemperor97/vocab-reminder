#!/usr/bin/env node
import { spawnSync } from "node:child_process";
import os from "node:os";
import path from "node:path";

import {
  acquireRuntimeLock,
  RUNTIME_LOCK_TOKEN_ENV,
  RUNTIME_MUTATION_LOCK_NAME,
} from "./security_helpers.mjs";

function parseArguments(argv) {
  const options = {
    runtimeRoot:
      process.env.VOCAB_SYNC_RUNTIME_ROOT ||
      path.join(os.homedir(), "Library", "Application Support", "VocabReminderServer"),
    name: RUNTIME_MUTATION_LOCK_NAME,
    label: "release/credential mutation",
  };
  let index = 0;
  for (; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--") {
      index += 1;
      break;
    }
    if (argument === "--runtime-root") options.runtimeRoot = path.resolve(argv[++index]);
    else if (argument === "--name") options.name = argv[++index];
    else if (argument === "--label") options.label = argv[++index];
    else throw new Error(`Unknown lock wrapper argument: ${argument}`);
  }
  options.command = argv[index];
  options.args = argv.slice(index + 1);
  if (!options.command) throw new Error("Runtime lock wrapper requires `-- <command> [args...]`.");
  return options;
}

function main(argv = process.argv.slice(2)) {
  let lease;
  let exitStatus = 1;
  try {
    const options = parseArguments(argv);
    lease = acquireRuntimeLock({
      runtimeRoot: options.runtimeRoot,
      name: options.name,
      label: options.label,
    });
    const result = spawnSync(options.command, options.args, {
      stdio: "inherit",
      env: {
        ...process.env,
        [RUNTIME_LOCK_TOKEN_ENV]: lease.token,
        VOCAB_RUNTIME_LOCK_WRAPPED: "1",
      },
    });
    if (result.error) throw result.error;
    if (result.signal) throw new Error(`Wrapped command terminated by ${result.signal}.`);
    exitStatus = result.status ?? 1;
  } catch (error) {
    console.error(`Runtime mutation lock failed: ${error.message}`);
    exitStatus = 1;
  } finally {
    if (lease) {
      try {
        lease.release();
      } catch (error) {
        console.error(`Runtime mutation lock cleanup failed: ${error.message}`);
        exitStatus = 1;
      }
    }
  }
  return exitStatus;
}

process.exitCode = main();
