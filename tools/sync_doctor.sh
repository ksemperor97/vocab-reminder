#!/usr/bin/env bash
set -u -o pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_ENV="$ROOT_DIR/server/.env"
TUNNEL_ENV="$ROOT_DIR/server/tunnel/.env"
RELEASE_README="$ROOT_DIR/dist/sync-release/README.md"

fail_count=0
warn_count=0

pass() {
  printf 'PASS: %s\n' "$1"
}

warn() {
  warn_count=$((warn_count + 1))
  printf 'WARN: %s\n' "$1"
}

fail() {
  fail_count=$((fail_count + 1))
  printf 'FAIL: %s\n' "$1"
}

skip() {
  printf 'SKIP: %s\n' "$1"
}

env_value() {
  local file="$1"
  local key="$2"
  [[ -f "$file" ]] || return 0
  awk -F= -v key="$key" '
    $0 ~ "^[[:space:]]*" key "[[:space:]]*=" {
      value=$0
      sub(/^[^=]*=/, "", value)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
      gsub(/^["'\'']|["'\'']$/, "", value)
      print value
      exit
    }
  ' "$file"
}

is_placeholder_value() {
  local value="$1"
  [[ -z "$value" ]] && return 0
  case "$value" in
    replace-*|temporary-*|changeme|CHANGE_ME|dummy|test-token|token|TODO*)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

is_placeholder_sync_url() {
  local url="$1"
  local host
  host="${url#https://}"
  host="${host#http://}"
  host="${host%%/*}"
  host="${host%%:*}"
  case "$host" in
    ""|example.com|*.example.com|example.net|*.example.net|example.org|*.example.org|example.invalid|*.example.invalid|*.invalid)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

request_status() {
  local url="$1"
  local token="$2"
  curl -sS -o /dev/null -w "%{http_code}" \
    --connect-timeout 3 \
    --max-time 6 \
    -H "Authorization: Bearer $token" \
    "$url" 2>/dev/null || printf '000'
}

print_section() {
  printf '\n== %s ==\n' "$1"
}

print_section "Mac mini server"

sync_token=""
sync_host="127.0.0.1"
sync_port="8787"

if [[ -f "$SERVER_ENV" ]]; then
  pass "server/.env exists"
  sync_token="$(env_value "$SERVER_ENV" VOCAB_SYNC_TOKEN)"
  sync_host="$(env_value "$SERVER_ENV" VOCAB_SYNC_HOST)"
  sync_port="$(env_value "$SERVER_ENV" VOCAB_SYNC_PORT)"
  sync_host="${sync_host:-127.0.0.1}"
  sync_port="${sync_port:-8787}"

  if is_placeholder_value "$sync_token"; then
    fail "server/.env VOCAB_SYNC_TOKEN is missing or still a placeholder"
  else
    pass "server/.env VOCAB_SYNC_TOKEN is set"
  fi

  if [[ "$sync_host" == "127.0.0.1" ]]; then
    pass "server binds to 127.0.0.1"
  else
    fail "server should bind to 127.0.0.1, got VOCAB_SYNC_HOST=$sync_host"
  fi
else
  fail "server/.env is missing; run server/install_launchd.sh on the Mac mini"
fi

if command -v launchctl >/dev/null 2>&1; then
  if launchctl print "gui/$(id -u)/com.vocabreminder.sync" >/dev/null 2>&1; then
    pass "com.vocabreminder.sync launchd agent is loaded"
  else
    fail "com.vocabreminder.sync launchd agent is not loaded"
  fi
else
  warn "launchctl is unavailable; skipping launchd checks"
fi

if [[ -n "$sync_token" ]] && ! is_placeholder_value "$sync_token"; then
  local_status="$(request_status "http://127.0.0.1:${sync_port}/health" "$sync_token")"
  if [[ "$local_status" == "200" ]]; then
    pass "local health check http://127.0.0.1:${sync_port}/health returned 200"
  else
    fail "local health check expected 200, got HTTP $local_status"
  fi
else
  skip "local health check requires a real server token"
fi

print_section "Outbound tunnel"

if [[ -f "$TUNNEL_ENV" ]]; then
  pass "server/tunnel/.env exists"
  tunnel_provider="$(env_value "$TUNNEL_ENV" TUNNEL_PROVIDER)"
  worker_url="$(env_value "$TUNNEL_ENV" CF_WORKER_PUBLIC_URL)"
  worker_admin_token="$(env_value "$TUNNEL_ENV" CF_WORKER_ADMIN_TOKEN)"
  tunnel_token="$(env_value "$TUNNEL_ENV" CLOUDFLARED_TUNNEL_TOKEN)"
  tunnel_config="$(env_value "$TUNNEL_ENV" CLOUDFLARED_CONFIG)"

  tunnel_provider="${tunnel_provider:-}"
  if [[ -z "$tunnel_provider" ]]; then
    tunnel_provider="cloudflare_quick_worker"
  fi

  case "$tunnel_provider" in
    cloudflare_quick_worker|cloudflare-worker|quick-worker)
      pass "tunnel provider is cloudflare_quick_worker"
      if [[ -z "$worker_url" || "$worker_url" == "https://vocab-sync.your-workers-subdomain.workers.dev" ]]; then
        fail "server/tunnel/.env must define CF_WORKER_PUBLIC_URL"
      elif [[ "$worker_url" != https://* ]]; then
        fail "CF_WORKER_PUBLIC_URL must be HTTPS: $worker_url"
      elif [[ "$worker_url" != *".workers.dev" ]]; then
        warn "CF_WORKER_PUBLIC_URL is not a workers.dev URL: $worker_url"
      else
        pass "CF_WORKER_PUBLIC_URL is set"
      fi
      if is_placeholder_value "$worker_admin_token" || [[ "$worker_admin_token" == "replace-with-worker-admin-token" ]]; then
        fail "server/tunnel/.env must define a real CF_WORKER_ADMIN_TOKEN"
      else
        pass "CF_WORKER_ADMIN_TOKEN is set"
      fi
      ;;
    cloudflare_named|cloudflared|cloudflare)
      warn "tunnel provider is legacy cloudflare_named; this requires a Cloudflare domain/zone"
      if [[ -n "$tunnel_config" ]]; then
        if [[ -f "$tunnel_config" ]]; then
          pass "CLOUDFLARED_CONFIG exists"
        else
          fail "CLOUDFLARED_CONFIG does not exist: $tunnel_config"
        fi
      elif is_placeholder_value "$tunnel_token" || [[ "$tunnel_token" == "replace-with-cloudflare-tunnel-token" ]]; then
        fail "server/tunnel/.env must define a real CLOUDFLARED_TUNNEL_TOKEN or CLOUDFLARED_CONFIG"
      else
        pass "CLOUDFLARED_TUNNEL_TOKEN is set"
      fi
      ;;
    *)
      fail "Unsupported TUNNEL_PROVIDER=$tunnel_provider"
      ;;
  esac
else
  fail "server/tunnel/.env is missing; run server/tunnel/install_tunnel.sh after creating the Worker"
fi

if command -v cloudflared >/dev/null 2>&1; then
  pass "cloudflared is installed"
else
  fail "cloudflared is not installed"
fi

if [[ -n "${tunnel_provider:-}" && "$tunnel_provider" == cloudflare_quick_worker* ]]; then
  if command -v node >/dev/null 2>&1; then
    pass "node is available for quick tunnel updater"
  else
    fail "node is required for cloudflare_quick_worker"
  fi
fi

if command -v launchctl >/dev/null 2>&1; then
  if launchctl print "gui/$(id -u)/com.vocabreminder.tunnel" >/dev/null 2>&1; then
    pass "com.vocabreminder.tunnel launchd agent is loaded"
  else
    fail "com.vocabreminder.tunnel launchd agent is not loaded"
  fi
fi

print_section "Stable HTTPS endpoint"

stable_url="${VOCAB_SYNC_DEFAULT_URL:-}"
if [[ -n "$stable_url" ]]; then
  if [[ "$stable_url" != https://* ]]; then
    fail "VOCAB_SYNC_DEFAULT_URL must be HTTPS: $stable_url"
  elif is_placeholder_sync_url "$stable_url"; then
    fail "VOCAB_SYNC_DEFAULT_URL is still a placeholder: $stable_url"
  elif [[ -n "$sync_token" ]] && ! is_placeholder_value "$sync_token"; then
    if VOCAB_SYNC_DEFAULT_URL="$stable_url" VOCAB_SYNC_DEFAULT_TOKEN="$sync_token" "$ROOT_DIR/tools/check_sync_endpoint.sh"; then
      pass "stable endpoint health and wrong-token checks passed"
    else
      fail "stable endpoint check failed for $stable_url"
    fi
  else
    skip "stable endpoint check requires a real server token"
  fi
else
  fail "VOCAB_SYNC_DEFAULT_URL is not set to the stable public HTTPS sync URL"
fi

print_section "Release artifacts"

if [[ -f "$RELEASE_README" ]]; then
  release_endpoint="$(sed -n 's/^Sync endpoint: //p' "$RELEASE_README" | head -n 1)"
  if [[ -z "$release_endpoint" ]]; then
    fail "dist/sync-release/README.md does not record a sync endpoint"
  elif is_placeholder_sync_url "$release_endpoint"; then
    if [[ "${ALLOW_SYNC_RELEASE_DRY_RUN:-0}" == "1" ]]; then
      warn "release artifacts use dry-run placeholder endpoint: $release_endpoint"
    else
      fail "release artifacts use placeholder endpoint: $release_endpoint"
    fi
  else
    pass "release artifacts record stable endpoint: $release_endpoint"
  fi
else
  warn "dist/sync-release/README.md is missing; run npm run sync:preflight after the real endpoint is ready"
fi

if [[ -f "$ROOT_DIR/dist/sync-release/VocabReminderAndroid-release.apk" ]]; then
  pass "Android release APK exists"
else
  warn "Android release APK is missing"
fi

if [[ -d "$ROOT_DIR/dist/sync-release/VocabReminder.app" ]]; then
  pass "Mac release app exists"
else
  warn "Mac release app is missing"
fi

print_section "Device QC readiness"

if command -v adb >/dev/null 2>&1; then
  adb_lines="$(adb devices -l 2>/dev/null | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }')"
  if [[ "$adb_lines" -gt 0 ]]; then
    pass "Android device is connected via adb"
  else
    warn "no Android device is connected via adb"
  fi
else
  warn "adb is unavailable; Android device QC cannot be checked here"
fi

print_section "Summary"

printf 'Failures: %d, warnings: %d\n' "$fail_count" "$warn_count"

if [[ "$fail_count" -gt 0 ]]; then
  exit 1
fi
