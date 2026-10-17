#!/usr/bin/env node
// Publishes a signed Android APK so phones can update without adb:
//
//   node server/tools/publish_android_update.mjs --apk app-release.apk \
//     --version-code 6 --version-name 0.4.2 [--signer-sha256 <hex>] [--runtime-root DIR]
//
// The APK lands in <runtime root>/app-updates/android (or VOCAB_SYNC_APP_UPDATE_DIR),
// where GET /app/android/latest and /app/android/apk serve it. The previous
// release is kept beside it. tools/build_sync_release.sh calls this after a
// release build.
import { androidUpdateDir, publishAndroidRelease } from "../src/app_updates.js";
import { readConfig } from "../src/config.js";

function parseArguments(argv) {
  const options = { allowDowngrade: false };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    const value = () => {
      const next = argv[++index];
      if (next === undefined) throw new Error(`${argument} needs a value.`);
      return next;
    };
    if (argument === "--apk") options.apk = value();
    else if (argument === "--version-code") options.versionCode = Number(value());
    else if (argument === "--version-name") options.versionName = value();
    else if (argument === "--signer-sha256") options.signerSha256 = value().toLowerCase();
    else if (argument === "--runtime-root") options.runtimeRoot = value();
    else if (argument === "--allow-downgrade") options.allowDowngrade = true;
    else throw new Error(`Unknown argument: ${argument}`);
  }
  if (!options.apk || options.versionCode === undefined || !options.versionName) {
    throw new Error("Usage: publish_android_update.mjs --apk PATH --version-code N --version-name NAME");
  }
  return options;
}

try {
  const options = parseArguments(process.argv.slice(2));
  const config = readConfig(options.runtimeRoot ? { runtimeRoot: options.runtimeRoot } : {});
  const dir = androidUpdateDir(config.appUpdateDir);
  const release = await publishAndroidRelease({
    dir,
    apkPath: options.apk,
    versionCode: options.versionCode,
    versionName: options.versionName,
    signerSha256: options.signerSha256 ?? null,
    allowDowngrade: options.allowDowngrade
  });
  console.log(
    `Published Android ${release.versionName} (${release.versionCode}) to ${dir}: ` +
      `${release.size} bytes, sha256 ${release.sha256}`
  );
} catch (error) {
  console.error(error.message);
  process.exit(1);
}
