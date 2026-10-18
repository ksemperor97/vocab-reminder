import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
export const RUNTIME_LOCK_TOKEN_ENV = "VOCAB_RUNTIME_LOCK_TOKEN";
export const RUNTIME_MUTATION_LOCK_NAME = "release-credential-mutation";

export function parseEnv(text) {
  const values = new Map();
  for (const rawLine of String(text).split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith("#")) continue;
    const separator = line.indexOf("=");
    if (separator <= 0) continue;
    const name = line.slice(0, separator).trim();
    let value = line.slice(separator + 1).trim();
    if (
      value.length >= 2 &&
      ((value.startsWith('"') && value.endsWith('"')) ||
        (value.startsWith("'") && value.endsWith("'")))
    ) {
      value = value.slice(1, -1);
    }
    values.set(name, value);
  }
  return values;
}

export function readEnvFile(file) {
  return parseEnv(fs.readFileSync(file, "utf8"));
}

export function replaceEnvValue(text, name, value) {
  if (!/^[A-Z][A-Z0-9_]*$/.test(name)) {
    throw new Error(`Unsupported environment key: ${name}`);
  }
  if (!value || /[\r\n]/.test(value)) {
    throw new Error(`${name} must be a non-empty single-line value.`);
  }
  const lines = String(text).split(/\r?\n/);
  let replaced = false;
  const rendered = lines.map((line) => {
    if (new RegExp(`^\\s*${name}\\s*=`).test(line)) {
      replaced = true;
      return `${name}=${value}`;
    }
    return line;
  });
  if (!replaced) rendered.push(`${name}=${value}`);
  while (rendered.length > 1 && rendered.at(-1) === "" && rendered.at(-2) === "") {
    rendered.pop();
  }
  return `${rendered.join("\n").replace(/\n+$/, "")}\n`;
}

export function validateSecret(value, name) {
  if (!value || value.length < 32 || /[\r\n]/.test(value) || /^replace-with-/i.test(value)) {
    throw new Error(`${name} must be a real single-line secret of at least 32 characters.`);
  }
  return value;
}

export function secretDigest(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

export function randomToken() {
  return crypto.randomBytes(32).toString("hex");
}

export function timingSafeEqualText(left, right) {
  const leftBuffer = Buffer.from(String(left));
  const rightBuffer = Buffer.from(String(right));
  return leftBuffer.length === rightBuffer.length && crypto.timingSafeEqual(leftBuffer, rightBuffer);
}

export function ensurePrivateDirectory(directory) {
  fs.mkdirSync(directory, { recursive: true, mode: 0o700 });
  fs.chmodSync(directory, 0o700);
}

export function writeFileAtomic(file, contents, mode = 0o600) {
  const directory = path.dirname(file);
  ensurePrivateDirectory(directory);
  const temporary = path.join(
    directory,
    `.${path.basename(file)}.tmp-${process.pid}-${crypto.randomBytes(6).toString("hex")}`,
  );
  let descriptor;
  try {
    descriptor = fs.openSync(temporary, "wx", mode);
    fs.writeFileSync(descriptor, contents);
    fs.fsyncSync(descriptor);
    fs.closeSync(descriptor);
    descriptor = undefined;
    fs.chmodSync(temporary, mode);
    fs.renameSync(temporary, file);
    fs.chmodSync(file, mode);
    const directoryDescriptor = fs.openSync(directory, "r");
    try {
      fs.fsyncSync(directoryDescriptor);
    } finally {
      fs.closeSync(directoryDescriptor);
    }
  } finally {
    if (descriptor !== undefined) fs.closeSync(descriptor);
    fs.rmSync(temporary, { force: true });
  }
}

export function isPathInside(candidate, parent) {
  const relative = path.relative(path.resolve(parent), path.resolve(candidate));
  return relative === "" || (!relative.startsWith("..") && !path.isAbsolute(relative));
}

export function privateMode(file) {
  return fs.statSync(file).mode & 0o777;
}

function validateLockName(name) {
  if (!/^[a-z0-9][a-z0-9._-]*$/.test(name)) {
    throw new Error(`Unsupported runtime lock name: ${name}`);
  }
  return name;
}

export function runtimeLockDirectory(runtimeRoot, name = RUNTIME_MUTATION_LOCK_NAME) {
  if (!runtimeRoot) throw new Error("Runtime lock requires a runtime root.");
  return path.join(path.resolve(runtimeRoot), "state", "locks", `${validateLockName(name)}.lock`);
}

function lockOwnerFile(lockDirectory) {
  return path.join(lockDirectory, "owner.json");
}

function readLockOwner(lockDirectory) {
  const ownerFile = lockOwnerFile(lockDirectory);
  if (!fs.existsSync(ownerFile)) return null;
  try {
    const owner = JSON.parse(fs.readFileSync(ownerFile, "utf8"));
    if (
      owner.schemaVersion !== 1 ||
      !Number.isSafeInteger(owner.pid) ||
      owner.pid <= 0 ||
      !/^[a-f0-9]{64}$/.test(owner.tokenSha256 || "")
    ) {
      return null;
    }
    return owner;
  } catch {
    return null;
  }
}

function processIsAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return error?.code === "EPERM";
  }
}

function lockAgeMilliseconds(lockDirectory) {
  try {
    return Math.max(0, Date.now() - fs.statSync(lockDirectory).mtimeMs);
  } catch {
    return 0;
  }
}

function reclaimStaleLock(lockDirectory) {
  const staleDirectory = `${lockDirectory}.stale-${process.pid}-${crypto.randomBytes(6).toString("hex")}`;
  try {
    fs.renameSync(lockDirectory, staleDirectory);
  } catch (error) {
    if (error?.code === "ENOENT") return false;
    throw error;
  }
  fs.rmSync(staleDirectory, { recursive: true, force: true });
  return true;
}

/**
 * Atomically owns a shared release/credential mutation lock below runtime state.
 *
 * The raw capability token exists only in process memory/environment. The owner
 * record persists its SHA-256 digest so a child can prove that it inherited the
 * live owner's capability without placing that capability on disk.
 */
export function acquireRuntimeLock({
  runtimeRoot,
  name = RUNTIME_MUTATION_LOCK_NAME,
  label = "runtime mutation",
  inheritedToken = process.env[RUNTIME_LOCK_TOKEN_ENV] || "",
  ownerlessStaleAfterMs = 30_000,
} = {}) {
  const lockDirectory = runtimeLockDirectory(runtimeRoot, name);
  const lockRoot = path.dirname(lockDirectory);
  ensurePrivateDirectory(path.dirname(lockRoot));
  ensurePrivateDirectory(lockRoot);

  if (inheritedToken) {
    const owner = readLockOwner(lockDirectory);
    if (!owner) throw new Error(`Inherited runtime lock is missing or invalid: ${lockDirectory}`);
    if (!processIsAlive(owner.pid)) {
      throw new Error(`Inherited runtime lock owner is no longer running: pid ${owner.pid}`);
    }
    if (!timingSafeEqualText(owner.tokenSha256, secretDigest(inheritedToken))) {
      throw new Error("Inherited runtime lock capability does not match the live owner.");
    }
    return {
      directory: lockDirectory,
      owned: false,
      token: inheritedToken,
      release() {},
    };
  }

  for (let attempt = 0; attempt < 4; attempt += 1) {
    try {
      fs.mkdirSync(lockDirectory, { mode: 0o700 });
      fs.chmodSync(lockDirectory, 0o700);
    } catch (error) {
      if (error?.code !== "EEXIST") throw error;
      const owner = readLockOwner(lockDirectory);
      if (owner && processIsAlive(owner.pid)) {
        throw new Error(
          `Runtime mutation lock is held by live process ${owner.pid} (${owner.label || "unknown operation"}).`,
        );
      }
      if (!owner && lockAgeMilliseconds(lockDirectory) < ownerlessStaleAfterMs) {
        throw new Error("Runtime mutation lock is being initialized by another process.");
      }
      reclaimStaleLock(lockDirectory);
      continue;
    }

    const token = randomToken();
    try {
      writeFileAtomic(
        lockOwnerFile(lockDirectory),
        `${JSON.stringify({
          schemaVersion: 1,
          pid: process.pid,
          label: String(label).slice(0, 120),
          createdAt: new Date().toISOString(),
          tokenSha256: secretDigest(token),
        }, null, 2)}\n`,
        0o600,
      );
    } catch (error) {
      fs.rmSync(lockDirectory, { recursive: true, force: true });
      throw error;
    }

    let released = false;
    return {
      directory: lockDirectory,
      owned: true,
      token,
      release() {
        if (released) return;
        const owner = readLockOwner(lockDirectory);
        if (!owner || !timingSafeEqualText(owner.tokenSha256, secretDigest(token))) {
          throw new Error("Refusing to release a runtime lock that is no longer owned by this process.");
        }
        fs.rmSync(lockDirectory, { recursive: true });
        released = true;
      },
    };
  }
  throw new Error(`Could not acquire runtime mutation lock after stale-owner recovery: ${lockDirectory}`);
}

export function isDirectExecution(metaUrl, argv = process.argv) {
  if (!argv[1]) return false;
  try {
    return fs.realpathSync(argv[1]) === fs.realpathSync(fileURLToPath(metaUrl));
  } catch {
    return false;
  }
}
