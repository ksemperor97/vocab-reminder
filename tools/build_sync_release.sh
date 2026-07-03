#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_ENV="$ROOT_DIR/server/.env"
DIST_DIR="$ROOT_DIR/dist/sync-release"
ANDROID_DIR="$ROOT_DIR/android/VocabReminderAndroid"
MAC_DIR="$ROOT_DIR/mac/VocabReminderMac"

SYNC_URL="${VOCAB_SYNC_DEFAULT_URL:-}"
SYNC_TOKEN="${VOCAB_SYNC_DEFAULT_TOKEN:-}"

if [[ -z "$SYNC_TOKEN" && -f "$SERVER_ENV" ]]; then
  SYNC_TOKEN="$(sed -n 's/^VOCAB_SYNC_TOKEN=//p' "$SERVER_ENV" | head -n 1)"
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
  echo "Set VOCAB_SYNC_DEFAULT_TOKEN or create server/.env with VOCAB_SYNC_TOKEN." >&2
  exit 64
fi

mkdir -p "$DIST_DIR"

echo "Building Android release APK with injected sync endpoint..."
(
  cd "$ANDROID_DIR"
  ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}" ./gradlew :app:assembleRelease \
    -PVOCAB_SYNC_DEFAULT_URL="$SYNC_URL" \
    -PVOCAB_SYNC_DEFAULT_TOKEN="$SYNC_TOKEN"
)

ANDROID_APK="$ANDROID_DIR/app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$ANDROID_APK" ]]; then
  echo "Android release APK not found: $ANDROID_APK" >&2
  exit 1
fi
cp "$ANDROID_APK" "$DIST_DIR/VocabReminderAndroid-release.apk"

echo "Building Mac app with injected sync endpoint..."
(
  cd "$MAC_DIR"
  VOCAB_SYNC_DEFAULT_URL="$SYNC_URL" \
  VOCAB_SYNC_DEFAULT_TOKEN="$SYNC_TOKEN" \
  CONFIGURATION=release \
  ./package_app.sh
)

rm -rf "$DIST_DIR/VocabReminder.app"
cp -R "$MAC_DIR/VocabReminder.app" "$DIST_DIR/VocabReminder.app"

cat > "$DIST_DIR/README.md" <<EOF
# Vocab Reminder Sync Release

Generated: $(date -u +"%Y-%m-%dT%H:%M:%SZ")
Sync endpoint: $SYNC_URL

Artifacts:

- VocabReminderAndroid-release.apk
- VocabReminder.app

The sync token is embedded in the app builds and is intentionally not written here.
Android and MacBook Air users should not enter VPN, router, server URL, or token settings.
EOF

echo "Release artifacts written to: $DIST_DIR"
