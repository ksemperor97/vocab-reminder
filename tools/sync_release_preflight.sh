#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_ENV="$ROOT_DIR/server/.env"

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
  echo "VOCAB_SYNC_DEFAULT_URL must be HTTPS: $SYNC_URL" >&2
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

export VOCAB_SYNC_DEFAULT_URL="$SYNC_URL"
export VOCAB_SYNC_DEFAULT_TOKEN="$SYNC_TOKEN"

if [[ "${SKIP_ENDPOINT_CHECK:-0}" != "1" ]]; then
  echo "== Checking sync endpoint =="
  "$ROOT_DIR/tools/check_sync_endpoint.sh"
else
  echo "== Skipping endpoint check =="
fi

if [[ "${SKIP_RELEASE_BUILD:-0}" != "1" ]]; then
  echo "== Building release artifacts =="
  "$ROOT_DIR/tools/build_sync_release.sh"
else
  echo "== Skipping release build =="
fi

echo "== Checking release artifacts =="
"$ROOT_DIR/tools/check_sync_release_artifacts.sh"

cat <<EOF
== Ready for device QC ==
Artifacts:
- $ROOT_DIR/dist/sync-release/VocabReminderAndroid-release.apk
- $ROOT_DIR/dist/sync-release/VocabReminder.app

Next checks:
- Android release UI: no server URL/token/VPN setup fields
- Android with AdGuard left on: launch app and confirm automatic sync status
- MacBook Air: launch app and confirm automatic sync status
EOF
