#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_ENV="$ROOT_DIR/server/.env"
DIST_DIR="${SYNC_RELEASE_DIST_DIR:-$ROOT_DIR/dist/sync-release}"
APK="$DIST_DIR/VocabReminderAndroid-release.apk"
APP="$DIST_DIR/VocabReminder.app"
INFO_PLIST="$APP/Contents/Info.plist"
MAC_BINARY="$APP/Contents/MacOS/VocabReminderMac"
README_FILE="$DIST_DIR/README.md"

SYNC_URL="${VOCAB_SYNC_DEFAULT_URL:-}"
SYNC_TOKEN="${VOCAB_SYNC_DEFAULT_TOKEN:-}"

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

APKSIGNER="${APKSIGNER:-}"
if [[ -z "$APKSIGNER" && -n "${ANDROID_HOME:-}" ]]; then
  APKSIGNER="$(find "$ANDROID_HOME/build-tools" -name apksigner -type f 2>/dev/null | sort | tail -n 1 || true)"
fi
if [[ -z "$APKSIGNER" && -d "$HOME/Library/Android/sdk/build-tools" ]]; then
  APKSIGNER="$(find "$HOME/Library/Android/sdk/build-tools" -name apksigner -type f 2>/dev/null | sort | tail -n 1 || true)"
fi

if [[ -z "$APKSIGNER" ]]; then
  echo "Missing apksigner. Set APKSIGNER or ANDROID_HOME." >&2
  exit 1
fi

"$APKSIGNER" verify --verbose "$APK" >/dev/null
echo "OK: Android APK signature verifies"

APK_STRINGS="$(mktemp)"
APK_MANIFEST_STRINGS="$(mktemp)"
trap 'rm -f "$APK_STRINGS" "$APK_MANIFEST_STRINGS"' EXIT
DEX_FILES="$(unzip -Z1 "$APK" | grep -E '^classes.*\.dex$')"
unzip -p "$APK" $DEX_FILES | strings > "$APK_STRINGS"
unzip -p "$APK" AndroidManifest.xml | strings > "$APK_MANIFEST_STRINGS"

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

if [[ -n "$SYNC_TOKEN" ]]; then
  if ! grep -Fq "$SYNC_TOKEN" "$APK_STRINGS"; then
    echo "Android APK does not contain expected sync token." >&2
    exit 1
  fi
  echo "OK: Android APK contains expected sync token"
else
  echo "SKIP: Android token check, no token was provided."
fi

MAC_URL="$(/usr/libexec/PlistBuddy -c 'Print :VocabSyncDefaultURL' "$INFO_PLIST")"
if [[ "$MAC_URL" != "$SYNC_URL" ]]; then
  echo "Mac app sync URL mismatch. Expected $SYNC_URL, got $MAC_URL" >&2
  exit 1
fi
echo "OK: Mac app contains expected sync URL"

if [[ -n "$SYNC_TOKEN" ]]; then
  MAC_TOKEN="$(/usr/libexec/PlistBuddy -c 'Print :VocabSyncDefaultToken' "$INFO_PLIST")"
  if [[ "$MAC_TOKEN" != "$SYNC_TOKEN" ]]; then
    echo "Mac app sync token mismatch." >&2
    exit 1
  fi
  echo "OK: Mac app contains expected sync token"
else
  echo "SKIP: Mac token check, no token was provided."
fi

if strings "$MAC_BINARY" | grep -Eq 'Debug sync fields|서버 주소|토큰|WireGuard|VPN|macmini\.local|10\.66\.0\.1'; then
  echo "Mac release binary contains debug sync or legacy VPN/direct-server strings." >&2
  exit 1
fi
echo "OK: Mac release binary has no debug sync or legacy VPN/direct-server strings"

echo "OK: sync release artifacts are ready: $DIST_DIR"
