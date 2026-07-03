#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_ENV="$ROOT_DIR/server/.env"
SYNC_URL="${VOCAB_SYNC_DEFAULT_URL:-${1:-}}"
SYNC_TOKEN="${VOCAB_SYNC_DEFAULT_TOKEN:-}"
LOCAL_SYNC_URL="${LOCAL_SYNC_URL:-http://127.0.0.1:8787}"

if [[ -z "$SYNC_TOKEN" && -f "$SERVER_ENV" ]]; then
  SYNC_TOKEN="$(sed -n 's/^VOCAB_SYNC_TOKEN=//p' "$SERVER_ENV" | head -n 1)"
fi

if [[ -z "$SYNC_TOKEN" ]]; then
  echo "Set VOCAB_SYNC_DEFAULT_TOKEN or create server/.env with VOCAB_SYNC_TOKEN." >&2
  exit 64
fi

request_status() {
  local url="$1"
  local token="$2"
  local body_file
  body_file="$(mktemp)"
  local status
  status="$(curl -sS -o "$body_file" -w "%{http_code}" \
    --connect-timeout 5 \
    --max-time 10 \
    -H "Authorization: Bearer $token" \
    "$url" 2>/dev/null || true)"
  printf '%s %s\n' "${status:-000}" "$body_file"
}

expect_status() {
  local label="$1"
  local url="$2"
  local token="$3"
  local expected="$4"
  local result status body_file
  result="$(request_status "$url" "$token")"
  status="${result%% *}"
  body_file="${result#* }"
  if [[ "$status" != "$expected" ]]; then
    echo "FAIL: $label expected HTTP $expected, got HTTP ${status:-000}" >&2
    if [[ -s "$body_file" ]]; then
      sed -n '1,20p' "$body_file" >&2
    fi
    rm -f "$body_file"
    exit 1
  fi
  rm -f "$body_file"
  echo "OK: $label -> HTTP $expected"
}

if [[ "${SKIP_LOCAL_SYNC_CHECK:-0}" != "1" ]]; then
  expect_status "local sync health" "$LOCAL_SYNC_URL/health" "$SYNC_TOKEN" "200"
fi

if [[ -n "$SYNC_URL" ]]; then
  if [[ "$SYNC_URL" != https://* ]]; then
    echo "FAIL: stable sync URL must be HTTPS: $SYNC_URL" >&2
    exit 64
  fi
  expect_status "stable sync health" "${SYNC_URL%/}/health" "$SYNC_TOKEN" "200"
  expect_status "stable sync wrong-token rejection" "${SYNC_URL%/}/health" "wrong-token" "401"
else
  echo "SKIP: stable sync health, VOCAB_SYNC_DEFAULT_URL was not set."
fi

if [[ -n "${LAN_SYNC_URL:-}" ]]; then
  result="$(request_status "${LAN_SYNC_URL%/}/health" "$SYNC_TOKEN")"
  status="${result%% *}"
  body_file="${result#* }"
  rm -f "$body_file"
  if [[ "$status" == "200" ]]; then
    echo "FAIL: LAN direct sync URL should not return HTTP 200: $LAN_SYNC_URL" >&2
    exit 1
  fi
  echo "OK: LAN direct sync URL did not return HTTP 200"
fi

if command -v launchctl >/dev/null 2>&1; then
  launchctl print "gui/$(id -u)/com.vocabreminder.sync" >/dev/null 2>&1 && \
    echo "OK: com.vocabreminder.sync launchd agent is loaded" || \
    echo "WARN: com.vocabreminder.sync launchd agent is not loaded"
  launchctl print "gui/$(id -u)/com.vocabreminder.tunnel" >/dev/null 2>&1 && \
    echo "OK: com.vocabreminder.tunnel launchd agent is loaded" || \
    echo "WARN: com.vocabreminder.tunnel launchd agent is not loaded"
fi
