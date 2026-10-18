import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

// The signed Android APK the phone updates itself from. Layout under the
// runtime's app-updates/android directory:
//
//   latest.json      what GET /app/android/latest serves; names the APK below
//   previous.json    the release latest.json pointed at before the last publish
//   <name>.apk       one file per kept release (latest and previous only)
//
// Publishing writes the new APK under a temporary name, renames it into place,
// then swaps latest.json with a rename, so a reader sees either the old release
// or the new one, never a half-written file.

const latestFile = "latest.json";
const previousFile = "previous.json";
const apkNamePattern = /^vocab-reminder-[0-9]+-[a-f0-9]{12}\.apk$/;

export function androidUpdateDir(appUpdateDir) {
  return path.join(appUpdateDir, "android");
}

export function readAndroidRelease(dir, fileName = latestFile) {
  let raw;
  try {
    raw = fs.readFileSync(path.join(dir, fileName), "utf8");
  } catch (error) {
    if (error.code === "ENOENT") return null;
    throw error;
  }
  return validateRelease(JSON.parse(raw));
}

/** Opens the published APK; the caller owns the returned descriptor. */
export function openAndroidApk(dir) {
  const release = readAndroidRelease(dir);
  if (!release) return null;
  const fd = fs.openSync(path.join(dir, release.file), "r");
  const { size } = fs.fstatSync(fd);
  if (size !== release.size) {
    fs.closeSync(fd);
    throw new Error(`Published APK ${release.file} is ${size} bytes; latest.json says ${release.size}.`);
  }
  return { release, fd };
}

export function publicRelease(release) {
  return {
    versionCode: release.versionCode,
    versionName: release.versionName,
    sha256: release.sha256,
    size: release.size,
    publishedAt: release.publishedAt
  };
}

export async function publishAndroidRelease({
  dir,
  apkPath,
  versionCode,
  versionName,
  signerSha256 = null,
  allowDowngrade = false,
  now = () => new Date()
}) {
  if (!Number.isSafeInteger(versionCode) || versionCode <= 0) {
    throw new Error("versionCode must be a positive integer.");
  }
  if (typeof versionName !== "string" || versionName.trim() === "") {
    throw new Error("versionName is required.");
  }
  if (signerSha256 !== null && !/^[a-f0-9]{64}$/.test(signerSha256)) {
    throw new Error("signerSha256 must be 64 lowercase hex characters.");
  }
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  const current = readAndroidRelease(dir);
  if (current && versionCode < current.versionCode && !allowDowngrade) {
    throw new Error(
      `Refusing to publish versionCode ${versionCode} over ${current.versionCode}; phones only install higher versions.`
    );
  }

  const tempApk = path.join(dir, `.incoming-${process.pid}-${crypto.randomUUID()}.apk`);
  let sha256;
  let size;
  try {
    ({ sha256, size } = await copyAndHash(apkPath, tempApk));
    if (size === 0) throw new Error(`APK is empty: ${apkPath}`);
  } catch (error) {
    fs.rmSync(tempApk, { force: true });
    throw error;
  }
  const file = `vocab-reminder-${versionCode}-${sha256.slice(0, 12)}.apk`;
  fs.renameSync(tempApk, path.join(dir, file));

  const release = {
    platform: "android",
    versionCode,
    versionName: versionName.trim(),
    sha256,
    size,
    file,
    signerSha256,
    publishedAt: now().toISOString()
  };
  if (current && current.file !== file) {
    writeJsonAtomically(path.join(dir, previousFile), current);
  }
  writeJsonAtomically(path.join(dir, latestFile), release);
  fsyncDirectory(dir);

  // Keep the published APK and the one before it; anything older goes.
  const keep = new Set([file, readAndroidRelease(dir, previousFile)?.file].filter(Boolean));
  for (const name of fs.readdirSync(dir)) {
    if ((apkNamePattern.test(name) && !keep.has(name)) || name.startsWith(".incoming-")) {
      fs.rmSync(path.join(dir, name), { force: true });
    }
  }
  return release;
}

function validateRelease(value) {
  const valid = value && typeof value === "object" &&
    Number.isSafeInteger(value.versionCode) && value.versionCode > 0 &&
    typeof value.versionName === "string" && value.versionName !== "" &&
    typeof value.sha256 === "string" && /^[a-f0-9]{64}$/.test(value.sha256) &&
    Number.isSafeInteger(value.size) && value.size > 0 &&
    typeof value.file === "string" && apkNamePattern.test(value.file);
  if (!valid) throw new Error("Published Android release metadata is invalid.");
  return value;
}

async function copyAndHash(source, target) {
  const hash = crypto.createHash("sha256");
  let size = 0;
  const output = fs.openSync(target, "wx", 0o600);
  try {
    for await (const chunk of fs.createReadStream(source)) {
      hash.update(chunk);
      size += chunk.byteLength;
      fs.writeSync(output, chunk);
    }
    fs.fsyncSync(output);
  } finally {
    fs.closeSync(output);
  }
  return { sha256: hash.digest("hex"), size };
}

function writeJsonAtomically(target, value) {
  const temp = `${target}.${process.pid}.tmp`;
  const fd = fs.openSync(temp, "w", 0o600);
  try {
    fs.writeSync(fd, `${JSON.stringify(value, null, 2)}\n`);
    fs.fsyncSync(fd);
  } finally {
    fs.closeSync(fd);
  }
  fs.renameSync(temp, target);
}

function fsyncDirectory(dir) {
  const fd = fs.openSync(dir, "r");
  try {
    fs.fsyncSync(fd);
  } finally {
    fs.closeSync(fd);
  }
}
