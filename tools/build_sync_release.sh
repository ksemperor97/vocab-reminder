#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME_ROOT="${VOCAB_SYNC_RUNTIME_ROOT:-$HOME/Library/Application Support/VocabReminderServer}"
if [[ "${VOCAB_RUNTIME_LOCK_WRAPPED:-0}" != "1" ]]; then
  exec node "$ROOT_DIR/tools/with_runtime_lock.mjs" \
    --runtime-root "$RUNTIME_ROOT" \
    --label "sync release build" \
    -- bash "$0" "$@"
fi
SERVER_ENV="${VOCAB_SYNC_SERVER_ENV_FILE:-$RUNTIME_ROOT/.env}"
TUNNEL_ENV="${VOCAB_SYNC_TUNNEL_ENV_FILE:-$RUNTIME_ROOT/tunnel.env}"
DIST_DIR="${SYNC_RELEASE_DIST_DIR:-$ROOT_DIR/dist/sync-release}"
ANDROID_DIR="$ROOT_DIR/android/VocabReminderAndroid"
MAC_DIR="$ROOT_DIR/mac/VocabReminderMac"
CHANGELOG_FILE="${VOCAB_RELEASE_CHANGELOG_FILE:-$ROOT_DIR/CHANGELOG.md}"

SYNC_URL="${VOCAB_SYNC_DEFAULT_URL:-}"
SYNC_TOKEN="${VOCAB_SYNC_DEFAULT_TOKEN:-}"

if [[ -z "$SYNC_TOKEN" && -f "$SERVER_ENV" ]]; then
  SYNC_TOKEN="$(sed -n 's/^VOCAB_SYNC_TOKEN=//p' "$SERVER_ENV" | head -n 1)"
fi
if [[ -z "$SYNC_URL" && -f "$TUNNEL_ENV" ]]; then
  # Tailscale Funnel address first; the Cloudflare Worker address only during the transition.
  SYNC_URL="$(sed -n 's/^VOCAB_SYNC_PUBLIC_URL=//p' "$TUNNEL_ENV" | tail -n 1)"
  [[ -n "$SYNC_URL" ]] || SYNC_URL="$(sed -n 's/^CF_WORKER_PUBLIC_URL=//p' "$TUNNEL_ENV" | head -n 1)"
fi

if [[ -z "$SYNC_URL" ]]; then
  echo "Set VOCAB_SYNC_DEFAULT_URL to the stable HTTPS sync endpoint." >&2
  exit 64
fi

if [[ "$SYNC_URL" != https://* ]]; then
  echo "VOCAB_SYNC_DEFAULT_URL must be HTTPS for release clients: $SYNC_URL" >&2
  exit 64
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
  echo "Placeholder sync URLs require ALLOW_SYNC_RELEASE_DRY_RUN=1: $SYNC_URL" >&2
  exit 64
fi

if [[ -z "$SYNC_TOKEN" ]]; then
  echo "Set VOCAB_SYNC_DEFAULT_TOKEN or configure $SERVER_ENV with VOCAB_SYNC_TOKEN." >&2
  exit 64
fi
SYNC_TOKEN_SHA256="$(printf '%s' "$SYNC_TOKEN" | shasum -a 256 | awk '{print $1}')"
if [[ ! "$SYNC_TOKEN_SHA256" =~ ^[a-f0-9]{64}$ ]]; then
  echo "Could not compute the embedded sync token digest." >&2
  exit 1
fi

if [[ ! -f "$CHANGELOG_FILE" ]]; then
  echo "Release changelog is missing: $CHANGELOG_FILE" >&2
  exit 64
fi

mkdir -p "$(dirname "$DIST_DIR")"
PREVIOUS_DIR="${DIST_DIR}.previous"
if [[ ! -e "$DIST_DIR" && -e "$PREVIOUS_DIR" ]]; then
  mv "$PREVIOUS_DIR" "$DIST_DIR"
  echo "Recovered the previous release after an interrupted artifact swap."
elif [[ -e "$DIST_DIR" && -e "$PREVIOUS_DIR" ]]; then
  rm -rf "$PREVIOUS_DIR"
  echo "Finalized cleanup from a previously completed artifact swap."
fi
STAGING_DIR="$(mktemp -d "$(dirname "$DIST_DIR")/.sync-release.tmp.XXXXXX")"
trap 'rm -rf "$STAGING_DIR"' EXIT

fsync_release_parent() {
  RELEASE_PARENT="$(dirname "$DIST_DIR")" node --input-type=module <<'NODE'
import fs from "node:fs";
const descriptor = fs.openSync(process.env.RELEASE_PARENT, "r");
try { fs.fsyncSync(descriptor); } finally { fs.closeSync(descriptor); }
NODE
}

echo "Building Android release APK with injected sync endpoint..."
(
  cd "$ANDROID_DIR"
  ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}" \
  VOCAB_SYNC_DEFAULT_URL="$SYNC_URL" \
  VOCAB_SYNC_DEFAULT_TOKEN="$SYNC_TOKEN" \
  ./gradlew :app:assembleRelease
)

ANDROID_APK="$ANDROID_DIR/app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$ANDROID_APK" ]]; then
  echo "Android release APK not found: $ANDROID_APK" >&2
  exit 1
fi
cp "$ANDROID_APK" "$STAGING_DIR/VocabReminderAndroid-release.apk"
ANDROID_SIGNER_SHA256="$(
  JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}" \
  ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}" \
    bash "$ROOT_DIR/tools/verify_android_release_signing.sh" \
      --print-sha256 "$STAGING_DIR/VocabReminderAndroid-release.apk"
)"
if [[ ! "$ANDROID_SIGNER_SHA256" =~ ^[a-f0-9]{64}$ ]]; then
  echo "Could not verify the Android release signing certificate." >&2
  exit 1
fi
echo "Android release signer matches the protected continuity record."

echo "Building Mac app with injected sync endpoint..."
(
  cd "$MAC_DIR"
  VOCAB_SYNC_DEFAULT_URL="$SYNC_URL" \
  VOCAB_SYNC_DEFAULT_TOKEN="$SYNC_TOKEN" \
  CONFIGURATION=release \
  ./package_app.sh
)

cp -R "$MAC_DIR/VocabReminder.app" "$STAGING_DIR/VocabReminder.app"
ditto -c -k --sequesterRsrc --keepParent \
  "$STAGING_DIR/VocabReminder.app" \
  "$STAGING_DIR/VocabReminder-macOS.zip"
cp "$CHANGELOG_FILE" "$STAGING_DIR/CHANGELOG.md"

AAPT_BIN="${AAPT:-}"
if [[ -z "$AAPT_BIN" ]]; then
  AAPT_BIN="$(find "${ANDROID_HOME:-$HOME/Library/Android/sdk}/build-tools" -name aapt -type f 2>/dev/null | sort | tail -n 1 || true)"
fi
if [[ -z "$AAPT_BIN" || ! -x "$AAPT_BIN" ]]; then
  echo "Missing aapt. Set AAPT or ANDROID_HOME." >&2
  exit 1
fi

APK_BADGING="$($AAPT_BIN dump badging "$STAGING_DIR/VocabReminderAndroid-release.apk")"
ANDROID_VERSION_CODE="$(printf '%s\n' "$APK_BADGING" | sed -n "s/^package:.*versionCode='\([^']*\)'.*/\1/p" | head -n 1)"
ANDROID_VERSION_NAME="$(printf '%s\n' "$APK_BADGING" | sed -n "s/^package:.*versionName='\([^']*\)'.*/\1/p" | head -n 1)"
ANDROID_MIN_SDK="$(printf '%s\n' "$APK_BADGING" | sed -n "s/^sdkVersion:'\([^']*\)'.*/\1/p" | head -n 1)"
ANDROID_TARGET_SDK="$(printf '%s\n' "$APK_BADGING" | sed -n "s/^targetSdkVersion:'\([^']*\)'.*/\1/p" | head -n 1)"
if [[ -z "$ANDROID_VERSION_CODE" || -z "$ANDROID_VERSION_NAME" || -z "$ANDROID_TARGET_SDK" ]]; then
  echo "Could not read Android release version metadata." >&2
  exit 1
fi
if printf '%s\n' "$APK_BADGING" | grep -q '^application-debuggable'; then
  echo "Android release artifact is debuggable." >&2
  exit 1
fi

MAC_INFO="$STAGING_DIR/VocabReminder.app/Contents/Info.plist"
MAC_BINARY="$STAGING_DIR/VocabReminder.app/Contents/MacOS/VocabReminderMac"
MAC_VERSION_NAME="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$MAC_INFO")"
MAC_VERSION_CODE="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleVersion' "$MAC_INFO")"
MAC_MINIMUM_SYSTEM_VERSION="$(/usr/libexec/PlistBuddy -c 'Print :LSMinimumSystemVersion' "$MAC_INFO")"
MAC_BINARY_MINIMUM_SYSTEM_VERSION="$(xcrun vtool -show-build "$MAC_BINARY" | awk '$1 == "minos" { print $2; exit }')"
if [[ -z "$MAC_BINARY_MINIMUM_SYSTEM_VERSION" ||
      "$MAC_MINIMUM_SYSTEM_VERSION" != "$MAC_BINARY_MINIMUM_SYSTEM_VERSION" ]]; then
  echo "Mac Info.plist minimum system version does not match the built binary." >&2
  exit 1
fi
GIT_SHA="$(git -C "$ROOT_DIR" rev-parse HEAD)"
GIT_BRANCH="$(git -C "$ROOT_DIR" branch --show-current)"
if git -C "$ROOT_DIR" diff --quiet && git -C "$ROOT_DIR" diff --cached --quiet && \
   [[ -z "$(git -C "$ROOT_DIR" ls-files --others --exclude-standard)" ]]; then
  GIT_DIRTY=false
else
  GIT_DIRTY=true
fi
GENERATED_AT="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

(
  cd "$STAGING_DIR"
  shasum -a 256 \
    VocabReminderAndroid-release.apk \
    VocabReminder-macOS.zip \
    CHANGELOG.md > SHA256SUMS
)

MANIFEST_ANDROID_SHA="$(awk '$2 == "VocabReminderAndroid-release.apk" { print $1 }' "$STAGING_DIR/SHA256SUMS")"
MANIFEST_MAC_SHA="$(awk '$2 == "VocabReminder-macOS.zip" { print $1 }' "$STAGING_DIR/SHA256SUMS")"
MANIFEST_CHANGELOG_SHA="$(awk '$2 == "CHANGELOG.md" { print $1 }' "$STAGING_DIR/SHA256SUMS")"

RELEASE_GENERATED_AT="$GENERATED_AT" \
RELEASE_SYNC_URL="$SYNC_URL" \
RELEASE_SYNC_TOKEN_SHA256="$SYNC_TOKEN_SHA256" \
RELEASE_GIT_SHA="$GIT_SHA" \
RELEASE_GIT_BRANCH="$GIT_BRANCH" \
RELEASE_GIT_DIRTY="$GIT_DIRTY" \
RELEASE_ANDROID_VERSION_CODE="$ANDROID_VERSION_CODE" \
RELEASE_ANDROID_VERSION_NAME="$ANDROID_VERSION_NAME" \
RELEASE_ANDROID_MIN_SDK="$ANDROID_MIN_SDK" \
RELEASE_ANDROID_TARGET_SDK="$ANDROID_TARGET_SDK" \
RELEASE_ANDROID_SHA="$MANIFEST_ANDROID_SHA" \
RELEASE_ANDROID_SIGNER_SHA256="$ANDROID_SIGNER_SHA256" \
RELEASE_MAC_VERSION_CODE="$MAC_VERSION_CODE" \
RELEASE_MAC_VERSION_NAME="$MAC_VERSION_NAME" \
RELEASE_MAC_MINIMUM_SYSTEM_VERSION="$MAC_MINIMUM_SYSTEM_VERSION" \
RELEASE_MAC_SHA="$MANIFEST_MAC_SHA" \
RELEASE_CHANGELOG_SHA="$MANIFEST_CHANGELOG_SHA" \
node --input-type=module > "$STAGING_DIR/release-manifest.json" <<'NODE'
const manifest = {
  schemaVersion: 1,
  generatedAt: process.env.RELEASE_GENERATED_AT,
  source: {
    gitSha: process.env.RELEASE_GIT_SHA,
    gitBranch: process.env.RELEASE_GIT_BRANCH,
    dirty: process.env.RELEASE_GIT_DIRTY === "true"
  },
  configuration: {
    syncEndpoint: process.env.RELEASE_SYNC_URL,
    tokenEmbedded: true,
    tokenRecorded: false,
    tokenDigestAlgorithm: "sha256",
    tokenSha256: process.env.RELEASE_SYNC_TOKEN_SHA256
  },
  android: {
    artifact: "VocabReminderAndroid-release.apk",
    versionCode: Number(process.env.RELEASE_ANDROID_VERSION_CODE),
    versionName: process.env.RELEASE_ANDROID_VERSION_NAME,
    minSdk: Number(process.env.RELEASE_ANDROID_MIN_SDK),
    targetSdk: Number(process.env.RELEASE_ANDROID_TARGET_SDK),
    debuggable: false,
    signerCertificateSha256: process.env.RELEASE_ANDROID_SIGNER_SHA256,
    sha256: process.env.RELEASE_ANDROID_SHA
  },
  macOS: {
    artifact: "VocabReminder-macOS.zip",
    appBundle: "VocabReminder.app",
    versionCode: process.env.RELEASE_MAC_VERSION_CODE,
    versionName: process.env.RELEASE_MAC_VERSION_NAME,
    minimumSystemVersion: process.env.RELEASE_MAC_MINIMUM_SYSTEM_VERSION,
    signing: "ad-hoc",
    sha256: process.env.RELEASE_MAC_SHA
  },
  changelog: {
    artifact: "CHANGELOG.md",
    sha256: process.env.RELEASE_CHANGELOG_SHA
  }
};
process.stdout.write(`${JSON.stringify(manifest, null, 2)}\n`);
NODE

cat > "$STAGING_DIR/README.md" <<EOF
# Vocab Reminder Sync Release

Generated: $GENERATED_AT
Sync endpoint: $SYNC_URL
Git commit: $GIT_SHA
Android: $ANDROID_VERSION_NAME ($ANDROID_VERSION_CODE), target SDK $ANDROID_TARGET_SDK
macOS: $MAC_VERSION_NAME ($MAC_VERSION_CODE), ad-hoc signed

Artifacts:

- VocabReminderAndroid-release.apk
- VocabReminder.app
- VocabReminder-macOS.zip
- release-manifest.json
- SHA256SUMS
- CHANGELOG.md

The sync token is embedded in the app builds and is intentionally not written here.
Android and MacBook Air users should not enter VPN, router, server URL, or token settings.
EOF

if [[ -e "$DIST_DIR" ]]; then
  mv "$DIST_DIR" "$PREVIOUS_DIR"
  fsync_release_parent
fi
if ! mv "$STAGING_DIR" "$DIST_DIR"; then
  if [[ -e "$PREVIOUS_DIR" ]]; then
    mv "$PREVIOUS_DIR" "$DIST_DIR"
    fsync_release_parent
  fi
  exit 1
fi
fsync_release_parent
rm -rf "$PREVIOUS_DIR"
fsync_release_parent
trap - EXIT
echo "Release artifacts written to: $DIST_DIR"

# Phones update from the sync server (Settings > 앱 업데이트), so publish the
# APK into the server runtime. The publish swaps the release atomically and
# keeps the previous APK. SYNC_RELEASE_SKIP_ANDROID_PUBLISH=1 builds only.
if [[ "${SYNC_RELEASE_SKIP_ANDROID_PUBLISH:-0}" == "1" ]]; then
  echo "Skipped publishing the Android update (SYNC_RELEASE_SKIP_ANDROID_PUBLISH=1)."
else
  node "$ROOT_DIR/server/tools/publish_android_update.mjs" \
    --runtime-root "$RUNTIME_ROOT" \
    --apk "$DIST_DIR/VocabReminderAndroid-release.apk" \
    --version-code "$ANDROID_VERSION_CODE" \
    --version-name "$ANDROID_VERSION_NAME" \
    --signer-sha256 "$ANDROID_SIGNER_SHA256"
fi
