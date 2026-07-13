#!/usr/bin/env bash
set -u -o pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME_ROOT="${VOCAB_SYNC_RUNTIME_ROOT:-$HOME/Library/Application Support/VocabReminderServer}"
RUNTIME_STATE_DIR="$RUNTIME_ROOT/state"
SERVER_ENV="${VOCAB_SYNC_SERVER_ENV_FILE:-$RUNTIME_ROOT/.env}"
TUNNEL_ENV="${VOCAB_SYNC_TUNNEL_ENV_FILE:-$RUNTIME_ROOT/tunnel.env}"
RELEASE_README="$ROOT_DIR/dist/sync-release/README.md"

if [[ ! -f "$SERVER_ENV" && -f "$ROOT_DIR/server/.env" ]]; then
  SERVER_ENV="$ROOT_DIR/server/.env"
fi

if [[ ! -f "$TUNNEL_ENV" && -f "$ROOT_DIR/server/tunnel/.env" ]]; then
  TUNNEL_ENV="$ROOT_DIR/server/tunnel/.env"
fi

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

is_cloud_synced_path() {
  local lowered
  lowered="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
  case "$lowered" in
    *"/dropbox/"*|*"/library/mobile documents/"*|*"/icloud drive/"*|*"/onedrive/"*|*"/google drive/"*)
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

absolute_path() {
  local value="$1"
  local base="$2"
  case "$value" in
    /*) printf '%s\n' "$value" ;;
    *) printf '%s/%s\n' "${base%/}" "$value" ;;
  esac
}

plist_value() {
  local file="$1"
  local key="$2"
  [[ -f "$file" ]] || return 0
  /usr/libexec/PlistBuddy -c "Print :$key" "$file" 2>/dev/null || true
}

launchd_snapshot() {
  local label="$1"
  launchctl print "gui/$(id -u)/$label" 2>/dev/null || true
}

launchd_field() {
  local snapshot="$1"
  local key="$2"
  printf '%s\n' "$snapshot" | awk -F= -v key="$key" '
    $1 ~ "^[[:space:]]*" key "[[:space:]]*$" {
      value=$2
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
      print value
      exit
    }
  '
}

json_value() {
  local file="$1"
  local dotted_key="$2"
  local node_bin="${3:-node}"
  [[ -f "$file" ]] || return 0
  "$node_bin" -e '
    const fs = require("node:fs");
    const [file, dotted] = process.argv.slice(1);
    try {
      let value = JSON.parse(fs.readFileSync(file, "utf8"));
      for (const key of dotted.split(".")) value = value?.[key];
      if (value == null) process.exit(0);
      console.log(typeof value === "object" ? JSON.stringify(value) : String(value));
    } catch {
      process.exit(2);
    }
  ' "$file" "$dotted_key" 2>/dev/null || true
}

file_age_seconds() {
  local file="$1"
  local modified
  modified="$(stat -f %m "$file" 2>/dev/null || printf '0')"
  if [[ "$modified" =~ ^[0-9]+$ ]] && (( modified > 0 )); then
    printf '%s\n' "$(( $(date +%s) - modified ))"
  else
    printf '%s\n' "-1"
  fi
}

sqlite_integrity() {
  sqlite3 -readonly "$1" "PRAGMA quick_check;" 2>/dev/null | head -n 1
}

print_section() {
  printf '\n== %s ==\n' "$1"
}

print_section "Runtime deployment"

active_release=""
if [[ -e "$RUNTIME_ROOT/current" ]]; then
  active_release="$(cd "$RUNTIME_ROOT/current" 2>/dev/null && pwd -P || true)"
fi

server_plist="$HOME/Library/LaunchAgents/com.vocabreminder.sync.plist"
tunnel_plist="$HOME/Library/LaunchAgents/com.vocabreminder.tunnel.plist"
node_bin="$(plist_value "$server_plist" "ProgramArguments:0")"
if [[ -z "$node_bin" || ! -x "$node_bin" ]]; then
  node_bin="$(command -v node 2>/dev/null || true)"
fi

if is_cloud_synced_path "$RUNTIME_ROOT"; then
  fail "runtime root is inside a cloud-synced folder: $RUNTIME_ROOT"
elif [[ -d "$RUNTIME_ROOT" ]]; then
  pass "runtime root is outside Dropbox/iCloud: $RUNTIME_ROOT"
else
  fail "runtime root is missing: $RUNTIME_ROOT"
fi

if [[ -L "$RUNTIME_ROOT/current" && -n "$active_release" && -d "$active_release" ]]; then
  pass "versioned current release resolves to $active_release"
elif [[ -e "$RUNTIME_ROOT/current" ]]; then
  fail "runtime current exists but is not a valid release symlink"
else
  fail "runtime current release symlink is missing"
fi

if [[ -f "$SERVER_ENV" ]]; then
  if is_cloud_synced_path "$SERVER_ENV"; then
    fail "active server env is inside a cloud-synced checkout: $SERVER_ENV"
  else
    pass "active server env exists outside the checkout"
  fi
fi

if [[ -n "$node_bin" && -x "$node_bin" ]]; then
  node_version="$("$node_bin" --version 2>/dev/null || true)"
  node_abi="$("$node_bin" -p 'process.versions.modules' 2>/dev/null || true)"
  node_major="${node_version#v}"
  node_major="${node_major%%.*}"
  if [[ "$node_major" =~ ^[0-9]+$ ]] && (( node_major >= 20 )); then
    pass "pinned Node is executable: $node_version (ABI $node_abi)"
  else
    fail "server requires Node 20+, got ${node_version:-unknown}"
  fi
else
  fail "pinned Node binary is missing or not executable"
fi

if [[ -n "$active_release" && -n "$node_bin" && -x "$node_bin" ]]; then
  if (
    cd "$active_release" &&
    "$node_bin" --input-type=module -e "await import('better-sqlite3')"
  ) >/dev/null 2>&1; then
    pass "better-sqlite3 loads with the pinned Node ABI"
  else
    fail "better-sqlite3 cannot load with the pinned Node ABI"
  fi
else
  skip "native module preflight requires an active runtime release"
fi

dictionary_catalog="$(plist_value "$server_plist" "EnvironmentVariables:VOCAB_DICTIONARY_CATALOG_PATH")"
dictionary_helper="$(plist_value "$server_plist" "EnvironmentVariables:VOCAB_DICTIONARY_HELPER_PATH")"
if [[ -f "$dictionary_catalog" ]] && ! is_cloud_synced_path "$dictionary_catalog"; then
  pass "offline dictionary catalog exists in the isolated runtime"
else
  fail "offline dictionary catalog is missing or cloud-synced"
fi
if [[ -x "$dictionary_helper" ]]; then
  if "$dictionary_helper" resilient >/dev/null 2>&1; then
    pass "Mac DictionaryServices helper resolves a real word"
  else
    fail "Mac DictionaryServices helper could not resolve the probe word"
  fi
else
  fail "Mac DictionaryServices helper is missing or not executable"
fi

deployment_state="$RUNTIME_STATE_DIR/deployment.json"
if [[ -f "$deployment_state" ]]; then
  deployment_status="$(json_value "$deployment_state" "status" "${node_bin:-node}")"
  deployment_release="$(json_value "$deployment_state" "releaseId" "${node_bin:-node}")"
  if [[ "$deployment_status" == "active" ]]; then
    pass "last deployment preflight succeeded${deployment_release:+ for $deployment_release}"
  else
    fail "deployment state does not record a successful preflight"
  fi
else
  warn "deployment state is missing"
fi

print_section "Mac mini server"

sync_token=""
sync_host="127.0.0.1"
sync_port="8787"
db_path=""
backup_dir=""

if [[ -f "$SERVER_ENV" ]]; then
  pass "active server env exists"
  sync_token="$(env_value "$SERVER_ENV" VOCAB_SYNC_TOKEN)"
  sync_host="$(env_value "$SERVER_ENV" VOCAB_SYNC_HOST)"
  sync_port="$(env_value "$SERVER_ENV" VOCAB_SYNC_PORT)"
  db_path="$(env_value "$SERVER_ENV" VOCAB_SYNC_DB_PATH)"
  backup_dir="$(env_value "$SERVER_ENV" VOCAB_SYNC_BACKUP_DIR)"
  sync_host="${sync_host:-127.0.0.1}"
  sync_port="${sync_port:-8787}"
  db_path="${db_path:-$RUNTIME_ROOT/data/vocab-sync.sqlite}"
  backup_dir="${backup_dir:-$RUNTIME_ROOT/backups}"
  db_path="$(absolute_path "$db_path" "${active_release:-$RUNTIME_ROOT}")"
  backup_dir="$(absolute_path "$backup_dir" "${active_release:-$RUNTIME_ROOT}")"

  if is_placeholder_value "$sync_token"; then
    fail "VOCAB_SYNC_TOKEN is missing or still a placeholder"
  else
    pass "VOCAB_SYNC_TOKEN is set"
  fi

  if [[ "$sync_host" == "127.0.0.1" ]]; then
    pass "server binds to 127.0.0.1"
  else
    fail "server should bind to 127.0.0.1, got VOCAB_SYNC_HOST=$sync_host"
  fi
else
  fail "active server env is missing: $SERVER_ENV"
fi

if command -v launchctl >/dev/null 2>&1; then
  server_snapshot="$(launchd_snapshot com.vocabreminder.sync)"
  server_state="$(launchd_field "$server_snapshot" state)"
  server_pid="$(launchd_field "$server_snapshot" pid)"
  if [[ "$server_state" == "running" && "$server_pid" =~ ^[0-9]+$ ]]; then
    pass "com.vocabreminder.sync is running with pid $server_pid"
  elif [[ -n "$server_snapshot" ]]; then
    fail "com.vocabreminder.sync is loaded but not running"
  else
    fail "com.vocabreminder.sync launchd agent is not loaded"
  fi
else
  warn "launchctl is unavailable; skipping launchd checks"
fi

if [[ -f "$server_plist" ]]; then
  if grep -qi 'Dropbox\|Mobile Documents\|iCloud Drive' "$server_plist"; then
    fail "server launchd plist still executes or logs inside a cloud-synced checkout"
  else
    pass "server launchd paths are outside cloud-synced folders"
  fi
else
  fail "server launchd plist is missing"
fi

if [[ -n "$sync_token" ]] && ! is_placeholder_value "$sync_token"; then
  local_status="$(request_status "http://127.0.0.1:${sync_port}/health" "$sync_token")"
  wrong_local_status="$(request_status "http://127.0.0.1:${sync_port}/health" "doctor-intentionally-wrong-token")"
  if [[ "$local_status" == "200" ]]; then
    pass "local health check http://127.0.0.1:${sync_port}/health returned 200"
  else
    fail "local health check expected 200, got HTTP $local_status"
  fi
  if [[ "$wrong_local_status" == "401" ]]; then
    pass "local wrong-token check returned 401"
  else
    fail "local wrong-token check expected 401, got HTTP $wrong_local_status"
  fi
else
  skip "local health check requires a real server token"
fi

print_section "Database and backups"

if [[ -n "$db_path" ]]; then
  if is_cloud_synced_path "$db_path"; then
    fail "database is inside a cloud-synced folder: $db_path"
  elif [[ ! -f "$db_path" ]]; then
    fail "database is missing: $db_path"
  else
    pass "database exists outside cloud-synced folders"
    if command -v sqlite3 >/dev/null 2>&1; then
      db_integrity="$(sqlite_integrity "$db_path")"
      if [[ "$db_integrity" == "ok" ]]; then
        pass "database PRAGMA quick_check returned ok"
      else
        fail "database quick_check failed: ${db_integrity:-no result}"
      fi
    else
      warn "sqlite3 CLI is unavailable; database integrity was not checked"
    fi
  fi
fi

if [[ -n "$backup_dir" ]]; then
  if is_cloud_synced_path "$backup_dir"; then
    fail "backup directory is inside a cloud-synced folder: $backup_dir"
  elif [[ ! -d "$backup_dir" ]]; then
    fail "backup directory is missing: $backup_dir"
  else
    latest_backup="$(find "$backup_dir" -maxdepth 1 -type f -name 'vocab-sync-*.sqlite' -print 2>/dev/null | sort | tail -n 1)"
    if [[ -z "$latest_backup" ]]; then
      fail "no daily SQLite backup exists"
    else
      backup_age="$(file_age_seconds "$latest_backup")"
      if (( backup_age >= 0 && backup_age <= 129600 )); then
        pass "latest backup is recent: $(basename "$latest_backup")"
      else
        fail "latest backup is older than 36 hours: $(basename "$latest_backup")"
      fi
      if command -v sqlite3 >/dev/null 2>&1; then
        backup_integrity="$(sqlite_integrity "$latest_backup")"
        if [[ "$backup_integrity" == "ok" ]]; then
          pass "latest backup PRAGMA quick_check returned ok"
        else
          fail "latest backup quick_check failed: ${backup_integrity:-no result}"
        fi
      fi
    fi
  fi
fi

restore_state="$RUNTIME_STATE_DIR/last_restore_drill.json"
if [[ -f "$restore_state" ]]; then
  restore_status="$(json_value "$restore_state" "status" "${node_bin:-node}")"
  restore_at="$(json_value "$restore_state" "completedAt" "${node_bin:-node}")"
  if [[ "$restore_status" == "passed" ]]; then
    pass "last restore drill succeeded${restore_at:+ at $restore_at}"
  else
    fail "last restore drill did not succeed"
  fi
else
  warn "restore drill has not been recorded yet"
fi

print_section "Outbound tunnel"

if [[ -f "$TUNNEL_ENV" ]]; then
  if is_cloud_synced_path "$TUNNEL_ENV"; then
    fail "active tunnel env is inside a cloud-synced checkout: $TUNNEL_ENV"
  else
    pass "active tunnel env exists outside the checkout"
  fi
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
  fail "active tunnel env is missing: $TUNNEL_ENV"
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
  tunnel_snapshot="$(launchd_snapshot com.vocabreminder.tunnel)"
  tunnel_state="$(launchd_field "$tunnel_snapshot" state)"
  tunnel_pid="$(launchd_field "$tunnel_snapshot" pid)"
  if [[ "$tunnel_state" == "running" && "$tunnel_pid" =~ ^[0-9]+$ ]]; then
    pass "com.vocabreminder.tunnel is running with pid $tunnel_pid"
  elif [[ -n "$tunnel_snapshot" ]]; then
    fail "com.vocabreminder.tunnel is loaded but not running"
  else
    fail "com.vocabreminder.tunnel launchd agent is not loaded"
  fi
fi

if [[ -f "$tunnel_plist" ]]; then
  if grep -qi 'Dropbox\|Mobile Documents\|iCloud Drive' "$tunnel_plist"; then
    fail "tunnel launchd plist still executes or logs inside a cloud-synced checkout"
  else
    pass "tunnel launchd paths are outside cloud-synced folders"
  fi
else
  fail "tunnel launchd plist is missing"
fi

tunnel_state_file="${TUNNEL_STATE_FILE:-$RUNTIME_STATE_DIR/tunnel-supervisor.json}"
if [[ -f "$tunnel_state_file" ]]; then
  supervisor_status="$(json_value "$tunnel_state_file" supervisor_status "${node_bin:-node}")"
  child_status="$(json_value "$tunnel_state_file" child_status "${node_bin:-node}")"
  local_health_at="$(json_value "$tunnel_state_file" last_local_health_at "${node_bin:-node}")"
  stable_health_at="$(json_value "$tunnel_state_file" last_stable_health_at "${node_bin:-node}")"
  stable_failures="$(json_value "$tunnel_state_file" consecutive_failures.stable "${node_bin:-node}")"
  tunnel_state_age="$(file_age_seconds "$tunnel_state_file")"
  if [[ "$supervisor_status" == "healthy" && "$child_status" == "running" ]]; then
    pass "tunnel supervisor reports a healthy connection"
  else
    fail "tunnel supervisor is not healthy (supervisor=${supervisor_status:-unknown}, child=${child_status:-unknown})"
  fi
  if (( tunnel_state_age >= 0 && tunnel_state_age <= 180 )); then
    pass "tunnel supervisor state is fresh"
  else
    fail "tunnel supervisor state is stale"
  fi
  if [[ -n "$local_health_at" ]]; then
    pass "tunnel supervisor recorded local health at $local_health_at"
  else
    fail "tunnel supervisor has no local health success"
  fi
  if [[ -n "$stable_health_at" && "${stable_failures:-0}" == "0" ]]; then
    pass "tunnel supervisor recorded stable health at $stable_health_at"
  else
    fail "tunnel supervisor stable health is missing or failing"
  fi
else
  fail "tunnel supervisor state is missing: $tunnel_state_file"
fi

print_section "Stable HTTPS endpoint"

stable_url="${VOCAB_SYNC_DEFAULT_URL:-${worker_url:-}}"
if [[ -n "$stable_url" ]]; then
  if [[ "$stable_url" != https://* ]]; then
    fail "VOCAB_SYNC_DEFAULT_URL must be HTTPS: $stable_url"
  elif is_placeholder_sync_url "$stable_url"; then
    fail "VOCAB_SYNC_DEFAULT_URL is still a placeholder: $stable_url"
  elif [[ -n "$sync_token" ]] && ! is_placeholder_value "$sync_token"; then
    stable_status="$(request_status "${stable_url%/}/health" "$sync_token")"
    wrong_stable_status="$(request_status "${stable_url%/}/health" "doctor-intentionally-wrong-token")"
    if [[ "$stable_status" == "200" ]]; then
      pass "stable endpoint health returned 200"
    else
      fail "stable endpoint health expected 200, got HTTP $stable_status"
    fi
    if [[ "$wrong_stable_status" == "401" ]]; then
      pass "stable endpoint wrong-token check returned 401"
    else
      fail "stable endpoint wrong-token check expected 401, got HTTP $wrong_stable_status"
    fi
  else
    skip "stable endpoint check requires a real server token"
  fi
else
  fail "stable public HTTPS sync URL is not configured"
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
  elif [[ -n "$stable_url" && "${release_endpoint%/}" != "${stable_url%/}" ]]; then
    fail "release artifact endpoint differs from the active stable endpoint"
  else
    pass "release artifacts record the active stable endpoint"
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
