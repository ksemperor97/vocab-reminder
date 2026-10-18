#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME_ROOT="${VOCAB_SYNC_RUNTIME_ROOT:-$HOME/Library/Application Support/VocabReminderServer}"
SERVER_ENV="${VOCAB_SYNC_SERVER_ENV_FILE:-$RUNTIME_ROOT/.env}"
TUNNEL_ENV="${VOCAB_SYNC_TUNNEL_ENV_FILE:-$RUNTIME_ROOT/tunnel.env}"
DIST_DIR="${SYNC_RELEASE_DIST_DIR:-$ROOT_DIR/dist/sync-release}"
APK="$DIST_DIR/VocabReminderAndroid-release.apk"
APP="$DIST_DIR/VocabReminder.app"
INFO_PLIST="$APP/Contents/Info.plist"
MAC_BINARY="$APP/Contents/MacOS/VocabReminderMac"
README_FILE="$DIST_DIR/README.md"
MANIFEST_FILE="$DIST_DIR/release-manifest.json"
CHECKSUM_FILE="$DIST_DIR/SHA256SUMS"
CHANGELOG_FILE="$DIST_DIR/CHANGELOG.md"
MAC_ARCHIVE="$DIST_DIR/VocabReminder-macOS.zip"

SYNC_URL="${VOCAB_SYNC_DEFAULT_URL:-}"
SYNC_TOKEN="${VOCAB_SYNC_DEFAULT_TOKEN:-}"

if [[ -z "$SYNC_URL" && -f "$TUNNEL_ENV" ]]; then
  SYNC_URL="$(sed -n 's/^CF_WORKER_PUBLIC_URL=//p' "$TUNNEL_ENV" | head -n 1)"
fi

if [[ -z "$SYNC_URL" && -f "$README_FILE" ]]; then
  SYNC_URL="$(sed -n 's/^Sync endpoint: //p' "$README_FILE" | head -n 1)"
fi

if [[ -z "$SYNC_TOKEN" && -f "$SERVER_ENV" ]]; then
  SYNC_TOKEN="$(sed -n 's/^VOCAB_SYNC_TOKEN=//p' "$SERVER_ENV" | head -n 1)"
fi

if [[ -z "$SYNC_URL" ]]; then
  echo "Set VOCAB_SYNC_DEFAULT_URL or keep dist/sync-release/README.md with Sync endpoint." >&2
  exit 64
fi
if [[ -z "$SYNC_TOKEN" ]]; then
  echo "Set VOCAB_SYNC_DEFAULT_TOKEN or configure $SERVER_ENV with VOCAB_SYNC_TOKEN before artifact verification." >&2
  exit 64
fi
SYNC_TOKEN_SHA256="$(printf '%s' "$SYNC_TOKEN" | shasum -a 256 | awk '{print $1}')"
if [[ ! "$SYNC_TOKEN_SHA256" =~ ^[a-f0-9]{64}$ ]]; then
  echo "Could not compute the expected sync token digest." >&2
  exit 1
fi

is_placeholder_sync_url() {
  local url="$1"
  local host
  host="${url#https://}"
  host="${host%%/*}"
  host="${host%%:*}"
  case "$host" in
    example.com|*.example.com|example.net|*.example.net|example.org|*.example.org|example.invalid|*.example.invalid|*.invalid)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

if is_placeholder_sync_url "$SYNC_URL" && [[ "${ALLOW_SYNC_RELEASE_DRY_RUN:-0}" != "1" ]]; then
  echo "Placeholder sync artifacts require ALLOW_SYNC_RELEASE_DRY_RUN=1: $SYNC_URL" >&2
  exit 64
fi

if [[ ! -f "$APK" ]]; then
  echo "Missing Android release APK: $APK" >&2
  exit 1
fi

if [[ ! -f "$INFO_PLIST" ]]; then
  echo "Missing Mac app Info.plist: $INFO_PLIST" >&2
  exit 1
fi

if [[ ! -f "$MAC_BINARY" ]]; then
  echo "Missing Mac app binary: $MAC_BINARY" >&2
  exit 1
fi

for required_file in "$MANIFEST_FILE" "$CHECKSUM_FILE" "$CHANGELOG_FILE" "$MAC_ARCHIVE"; do
  if [[ ! -f "$required_file" ]]; then
    echo "Missing release provenance artifact: $required_file" >&2
    exit 1
  fi
done

(
  cd "$DIST_DIR"
  shasum -a 256 -c SHA256SUMS >/dev/null
)
echo "OK: release artifact checksums verify"

EXPECTED_GIT_SHA="$(git -C "$ROOT_DIR" rev-parse HEAD)" \
EXPECTED_SYNC_URL="$SYNC_URL" \
EXPECTED_SYNC_TOKEN_SHA256="$SYNC_TOKEN_SHA256" \
RELEASE_DIST_DIR="$DIST_DIR" \
ALLOW_DIRTY_RELEASE_ARTIFACTS="${ALLOW_DIRTY_RELEASE_ARTIFACTS:-0}" \
node --input-type=module - "$MANIFEST_FILE" <<'NODE'
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const manifest = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
const fail = (message) => {
  console.error(message);
  process.exit(1);
};
if (manifest?.schemaVersion !== 1) fail("Unsupported or missing release manifest schemaVersion.");
if (manifest?.source?.gitSha !== process.env.EXPECTED_GIT_SHA) {
  fail(`Release manifest commit mismatch: expected ${process.env.EXPECTED_GIT_SHA}.`);
}
if (manifest?.source?.dirty && process.env.ALLOW_DIRTY_RELEASE_ARTIFACTS !== "1") {
  fail("Release manifest was generated from a dirty worktree.");
}
if (manifest?.configuration?.syncEndpoint !== process.env.EXPECTED_SYNC_URL) {
  fail("Release manifest sync endpoint does not match the expected endpoint.");
}
if (manifest?.configuration?.tokenRecorded !== false || manifest?.configuration?.tokenEmbedded !== true) {
  fail("Release manifest secret-boundary flags are invalid.");
}
if (manifest?.configuration?.tokenDigestAlgorithm !== "sha256" ||
    manifest?.configuration?.tokenSha256 !== process.env.EXPECTED_SYNC_TOKEN_SHA256) {
  fail("Release manifest sync token digest does not match the expected token.");
}
if (manifest?.android?.debuggable !== false) fail("Release manifest marks Android as debuggable.");
const artifacts = [
  ["android.sha256", "VocabReminderAndroid-release.apk"],
  ["macOS.sha256", "VocabReminder-macOS.zip"],
  ["changelog.sha256", "CHANGELOG.md"],
];
for (const [manifestPath, artifact] of artifacts) {
  const value = manifestPath.split(".").reduce((current, key) => current?.[key], manifest);
  if (!/^[a-f0-9]{64}$/u.test(value ?? "")) fail(`Missing SHA-256 in release manifest: ${manifestPath}.`);
  const actual = crypto.createHash("sha256")
    .update(fs.readFileSync(path.join(process.env.RELEASE_DIST_DIR, artifact)))
    .digest("hex");
  if (value !== actual) fail(`Release manifest SHA-256 does not match ${artifact}.`);
}
NODE
echo "OK: release manifest matches current commit and endpoint"

ANDROID_SIGNER_SHA256="$(
  JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}" \
  ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}" \
    bash "$ROOT_DIR/tools/verify_android_release_signing.sh" --print-sha256 "$APK"
)"
if [[ ! "$ANDROID_SIGNER_SHA256" =~ ^[a-f0-9]{64}$ ]]; then
  echo "Could not verify Android signer continuity." >&2
  exit 1
fi
echo "OK: Android APK signer matches the protected continuity record"

APK_STRINGS="$(mktemp)"
APK_MANIFEST_STRINGS="$(mktemp)"
TOKEN_PATTERN="$(mktemp)"
MAC_ARCHIVE_EXTRACT="$(mktemp -d)"
trap 'rm -f "$APK_STRINGS" "$APK_MANIFEST_STRINGS" "$TOKEN_PATTERN"; rm -rf "$MAC_ARCHIVE_EXTRACT"' EXIT
DEX_FILES="$(unzip -Z1 "$APK" | grep -E '^classes.*\.dex$')"
unzip -p "$APK" $DEX_FILES | strings > "$APK_STRINGS"
unzip -p "$APK" AndroidManifest.xml | strings > "$APK_MANIFEST_STRINGS"

AAPT_BIN="${AAPT:-}"
if [[ -z "$AAPT_BIN" && -n "${ANDROID_HOME:-}" ]]; then
  AAPT_BIN="$(find "$ANDROID_HOME/build-tools" -name aapt -type f 2>/dev/null | sort | tail -n 1 || true)"
fi
if [[ -z "$AAPT_BIN" || ! -x "$AAPT_BIN" ]]; then
  echo "Missing aapt. Set AAPT or ANDROID_HOME." >&2
  exit 1
fi
APK_BADGING="$($AAPT_BIN dump badging "$APK")"
if printf '%s\n' "$APK_BADGING" | grep -q '^application-debuggable'; then
  echo "Android release APK is debuggable." >&2
  exit 1
fi
ACTUAL_ANDROID_VERSION_CODE="$(printf '%s\n' "$APK_BADGING" | sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p" | head -n 1)"
ACTUAL_ANDROID_VERSION_NAME="$(printf '%s\n' "$APK_BADGING" | sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" | head -n 1)"
ACTUAL_ANDROID_TARGET_SDK="$(printf '%s\n' "$APK_BADGING" | sed -n "s/^targetSdkVersion:'\([^']*\)'.*/\1/p" | head -n 1)"
ACTUAL_ANDROID_VERSION_CODE="$ACTUAL_ANDROID_VERSION_CODE" \
ACTUAL_ANDROID_VERSION_NAME="$ACTUAL_ANDROID_VERSION_NAME" \
ACTUAL_ANDROID_TARGET_SDK="$ACTUAL_ANDROID_TARGET_SDK" \
ACTUAL_ANDROID_SIGNER_SHA256="$ANDROID_SIGNER_SHA256" \
node --input-type=module - "$MANIFEST_FILE" <<'NODE'
import fs from "node:fs";
const manifest = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
if (String(manifest.android.versionCode) !== process.env.ACTUAL_ANDROID_VERSION_CODE ||
    manifest.android.versionName !== process.env.ACTUAL_ANDROID_VERSION_NAME ||
    String(manifest.android.targetSdk) !== process.env.ACTUAL_ANDROID_TARGET_SDK ||
    manifest.android.signerCertificateSha256 !== process.env.ACTUAL_ANDROID_SIGNER_SHA256) {
  console.error("Android APK version or signer metadata does not match release-manifest.json.");
  process.exit(1);
}
NODE
echo "OK: Android release is non-debuggable and version metadata matches"

if grep -Eq 'networkSecurityConfig|network_security_config|usesCleartextTraffic|10\.66\.0\.1|macmini\.local' "$APK_MANIFEST_STRINGS"; then
  echo "Android release manifest contains legacy cleartext/VPN network configuration." >&2
  exit 1
fi
echo "OK: Android release manifest has no legacy cleartext/VPN network configuration"

if grep -Eq '10\.66\.0\.1|macmini\.local|WireGuard|VPN|networkSecurityConfig|network_security_config' "$APK_STRINGS"; then
  echo "Android release code contains legacy VPN/direct-server strings." >&2
  exit 1
fi
echo "OK: Android release code has no legacy VPN/direct-server strings"

if grep -Eq 'Debug sync fields|sync_server_url_field|sync_auth_token_field|서버 주소|토큰|서버:' "$APK_STRINGS"; then
  echo "Android release code contains debug sync setup strings." >&2
  exit 1
fi
echo "OK: Android release code has no debug sync setup strings"

if ! grep -Fq "$SYNC_URL" "$APK_STRINGS"; then
  echo "Android APK does not contain expected sync URL: $SYNC_URL" >&2
  exit 1
fi
echo "OK: Android APK contains expected sync URL"

printf '%s\n' "$SYNC_TOKEN" > "$TOKEN_PATTERN"
chmod 600 "$TOKEN_PATTERN"
if ! grep -Fq -f "$TOKEN_PATTERN" "$APK_STRINGS"; then
  echo "Android APK does not contain expected sync token." >&2
  exit 1
fi
echo "OK: Android APK contains expected sync token"

MAC_URL="$(/usr/libexec/PlistBuddy -c 'Print :VocabSyncDefaultURL' "$INFO_PLIST")"
if [[ "$MAC_URL" != "$SYNC_URL" ]]; then
  echo "Mac app sync URL mismatch. Expected $SYNC_URL, got $MAC_URL" >&2
  exit 1
fi
echo "OK: Mac app contains expected sync URL"

MAC_VERSION_CODE="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$INFO_PLIST")"
MAC_VERSION_NAME="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$INFO_PLIST")"
MAC_MINIMUM_SYSTEM_VERSION="$(/usr/libexec/PlistBuddy -c 'Print :LSMinimumSystemVersion' "$INFO_PLIST")"
MAC_BINARY_MINIMUM_SYSTEM_VERSION="$(xcrun vtool -show-build "$MAC_BINARY" | awk '$1 == "minos" { print $2; exit }')"
if [[ -z "$MAC_BINARY_MINIMUM_SYSTEM_VERSION" ||
      "$MAC_MINIMUM_SYSTEM_VERSION" != "$MAC_BINARY_MINIMUM_SYSTEM_VERSION" ]]; then
  echo "Mac Info.plist minimum system version does not match the built binary." >&2
  exit 1
fi
MAC_VERSION_CODE="$MAC_VERSION_CODE" \
MAC_VERSION_NAME="$MAC_VERSION_NAME" \
MAC_MINIMUM_SYSTEM_VERSION="$MAC_MINIMUM_SYSTEM_VERSION" \
node --input-type=module - "$MANIFEST_FILE" <<'NODE'
import fs from "node:fs";
const manifest = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
if (manifest.macOS.versionCode !== process.env.MAC_VERSION_CODE ||
    manifest.macOS.versionName !== process.env.MAC_VERSION_NAME ||
    manifest.macOS.minimumSystemVersion !== process.env.MAC_MINIMUM_SYSTEM_VERSION ||
    manifest.macOS.signing !== "ad-hoc") {
  console.error("Mac app version/minimum-system/signing metadata does not match release-manifest.json.");
  process.exit(1);
}
NODE
codesign --verify --deep --strict --verbose=2 "$APP" >/dev/null 2>&1
echo "OK: Mac app ad-hoc signature and version metadata verify"

MAC_TOKEN="$(/usr/libexec/PlistBuddy -c 'Print :VocabSyncDefaultToken' "$INFO_PLIST")"
if [[ "$MAC_TOKEN" != "$SYNC_TOKEN" ]]; then
  echo "Mac app sync token mismatch." >&2
  exit 1
fi
echo "OK: Mac app contains expected sync token"

MAC_SERVICE_MESSAGE="$(/usr/libexec/PlistBuddy -c 'Print :NSServices:0:NSMessage' "$INFO_PLIST" 2>/dev/null || true)"
MAC_SERVICE_PORT="$(/usr/libexec/PlistBuddy -c 'Print :NSServices:0:NSPortName' "$INFO_PLIST" 2>/dev/null || true)"
MAC_SERVICE_MENU="$(/usr/libexec/PlistBuddy -c 'Print :NSServices:0:NSMenuItem:default' "$INFO_PLIST" 2>/dev/null || true)"
MAC_SERVICE_TYPES="$(/usr/libexec/PlistBuddy -c 'Print :NSServices:0:NSSendTypes' "$INFO_PLIST" 2>/dev/null || true)"
MAC_CAPTURE_SCHEME="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleURLTypes:0:CFBundleURLSchemes:0' "$INFO_PLIST" 2>/dev/null || true)"

if [[ "$MAC_SERVICE_MESSAGE" != "addToVocabularyReminder" ||
      "$MAC_SERVICE_PORT" != "VocabReminder" ||
      "$MAC_SERVICE_MENU" != "Add to Vocabulary Reminder" ||
      "$MAC_SERVICE_TYPES" != *"public.utf8-plain-text"* ||
      "$MAC_SERVICE_TYPES" != *"NSStringPboardType"* ]]; then
  echo "Mac app is missing the text capture service declaration." >&2
  exit 1
fi
echo "OK: Mac app declares text capture service"

if [[ "$MAC_CAPTURE_SCHEME" != "vocabreminder" ]]; then
  echo "Mac app is missing the vocabreminder capture URL scheme." >&2
  exit 1
fi
echo "OK: Mac app declares capture URL scheme"

if strings "$MAC_BINARY" | grep -Eq 'Debug sync fields|서버 주소|토큰|WireGuard|VPN|macmini\.local|10\.66\.0\.1'; then
  echo "Mac release binary contains debug sync or legacy VPN/direct-server strings." >&2
  exit 1
fi
echo "OK: Mac release binary has no debug sync or legacy VPN/direct-server strings"

ditto -x -k "$MAC_ARCHIVE" "$MAC_ARCHIVE_EXTRACT"
ARCHIVE_APP="$MAC_ARCHIVE_EXTRACT/VocabReminder.app"
ARCHIVE_INFO="$ARCHIVE_APP/Contents/Info.plist"
ARCHIVE_BINARY="$ARCHIVE_APP/Contents/MacOS/VocabReminderMac"
if [[ ! -f "$ARCHIVE_INFO" || ! -f "$ARCHIVE_BINARY" ]]; then
  echo "Distributed Mac archive does not contain the expected VocabReminder.app bundle." >&2
  exit 1
fi
if find "$MAC_ARCHIVE_EXTRACT" -mindepth 1 -maxdepth 1 \
    ! -name 'VocabReminder.app' ! -name '__MACOSX' -print -quit | grep -q .; then
  echo "Distributed Mac archive contains an unexpected top-level payload." >&2
  exit 1
fi
if ! diff -qr "$APP" "$ARCHIVE_APP" >/dev/null; then
  echo "Distributed Mac archive does not match the verified loose app bundle." >&2
  exit 1
fi
codesign --verify --deep --strict --verbose=2 "$ARCHIVE_APP" >/dev/null 2>&1
ARCHIVE_URL="$(/usr/libexec/PlistBuddy -c 'Print :VocabSyncDefaultURL' "$ARCHIVE_INFO")"
ARCHIVE_VERSION_CODE="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$ARCHIVE_INFO")"
ARCHIVE_VERSION_NAME="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$ARCHIVE_INFO")"
if [[ "$ARCHIVE_URL" != "$SYNC_URL" ||
      "$ARCHIVE_VERSION_CODE" != "$MAC_VERSION_CODE" ||
      "$ARCHIVE_VERSION_NAME" != "$MAC_VERSION_NAME" ]]; then
  echo "Distributed Mac archive endpoint/version metadata does not match the verified app." >&2
  exit 1
fi
ARCHIVE_TOKEN="$(/usr/libexec/PlistBuddy -c 'Print :VocabSyncDefaultToken' "$ARCHIVE_INFO")"
if [[ "$ARCHIVE_TOKEN" != "$SYNC_TOKEN" ]]; then
  echo "Distributed Mac archive sync token mismatch." >&2
  exit 1
fi
echo "OK: distributed Mac zip matches the verified app, metadata, token, and signature"

echo "OK: sync release artifacts are ready: $DIST_DIR"
