#!/usr/bin/env bash
set -euo pipefail
set +x

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd -P)"
RUNTIME_ROOT="${VOCAB_SYNC_RUNTIME_ROOT:-$HOME/Library/Application Support/VocabReminderServer}"
DURATION_SECONDS="${SYNC_SOAK_DURATION_SECONDS:-86400}"
INTERVAL_SECONDS="${SYNC_SOAK_INTERVAL_SECONDS:-30}"
STAMP="$(date '+%Y%m%d_%H%M%S')"
OUTPUT_DIR="${SYNC_SOAK_OUTPUT_DIR:-/tmp/vocab_product_completion_$(date '+%Y%m%d')/sync_soak_$STAMP}"
SERVER_ENV="${VOCAB_SYNC_SERVER_ENV_FILE:-$RUNTIME_ROOT/.env}"
TUNNEL_ENV="${VOCAB_SYNC_TUNNEL_ENV_FILE:-$RUNTIME_ROOT/tunnel.env}"
STATE_FILE="$RUNTIME_ROOT/state/tunnel-supervisor.json"

if ! [[ "$DURATION_SECONDS" =~ ^[0-9]+$ ]] || (( DURATION_SECONDS < 1 )); then
  echo "SYNC_SOAK_DURATION_SECONDS must be a positive integer." >&2
  exit 64
fi
if ! [[ "$INTERVAL_SECONDS" =~ ^[0-9]+$ ]] || (( INTERVAL_SECONDS < 1 )); then
  echo "SYNC_SOAK_INTERVAL_SECONDS must be a positive integer." >&2
  exit 64
fi
if [[ ! -f "$SERVER_ENV" || ! -f "$TUNNEL_ENV" ]]; then
  echo "Protected runtime server/tunnel env files are required." >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR"
chmod 700 "$OUTPUT_DIR"
SAMPLES_FILE="$OUTPUT_DIR/samples.tsv"
SUMMARY_FILE="$OUTPUT_DIR/summary.json"
printf 'sample\trecorded_at\tserver_pid\ttunnel_pid\tlocal_http\tlocal_wrong_http\tstable_http\tstable_wrong_http\tdb_integrity\ttunnel_status\tresult\n' > "$SAMPLES_FILE"
chmod 600 "$SAMPLES_FILE"

set -a
# shellcheck disable=SC1090
. "$SERVER_ENV"
# shellcheck disable=SC1090
. "$TUNNEL_ENV"
set +a

stable_url="${VOCAB_SYNC_DEFAULT_URL:-${CF_WORKER_PUBLIC_URL:-}}"
if [[ -z "${VOCAB_SYNC_TOKEN:-}" || -z "$stable_url" ]]; then
  echo "Runtime sync token and stable URL are required." >&2
  exit 1
fi

plist_env() {
  /usr/libexec/PlistBuddy -c "Print :EnvironmentVariables:$1" \
    "$HOME/Library/LaunchAgents/com.vocabreminder.sync.plist" 2>/dev/null || true
}

launchd_pid() {
  launchctl print "gui/$(id -u)/$1" 2>/dev/null \
    | awk -F= '$1 ~ /^[[:space:]]*pid[[:space:]]*$/ {gsub(/[[:space:]]/, "", $2); print $2; exit}'
}

http_status() {
  local url="$1"
  local token="$2"
  curl -sS -o /dev/null -w '%{http_code}' \
    --connect-timeout 3 --max-time 8 \
    -H "Authorization: Bearer $token" "$url" 2>/dev/null || printf '000'
}

tunnel_status() {
  [[ -f "$STATE_FILE" ]] || { printf 'missing'; return; }
  node --input-type=module - "$STATE_FILE" <<'NODE' 2>/dev/null || printf 'invalid'
import fs from "node:fs";
const state = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));
const fresh = Date.now() - fs.statSync(process.argv[2]).mtimeMs <= 180_000;
process.stdout.write(
  state.supervisor_status === "healthy" && state.child_status === "running" && fresh
    ? "healthy"
    : "unhealthy"
);
NODE
}

db_path="$(plist_env VOCAB_SYNC_DB_PATH)"
db_path="${db_path:-$RUNTIME_ROOT/data/vocab-sync.sqlite}"
started_epoch="$(date +%s)"
deadline_epoch="$((started_epoch + DURATION_SECONDS))"
samples=0
failures=0

while (( $(date +%s) < deadline_epoch )); do
  samples=$((samples + 1))
  recorded_at="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  server_pid="$(launchd_pid com.vocabreminder.sync)"
  tunnel_pid="$(launchd_pid com.vocabreminder.tunnel)"
  local_http="$(http_status "http://127.0.0.1:${VOCAB_SYNC_PORT:-8787}/health" "$VOCAB_SYNC_TOKEN")"
  local_wrong_http="$(http_status "http://127.0.0.1:${VOCAB_SYNC_PORT:-8787}/health" "soak-intentionally-wrong-token")"
  stable_http="$(http_status "${stable_url%/}/health" "$VOCAB_SYNC_TOKEN")"
  stable_wrong_http="$(http_status "${stable_url%/}/health" "soak-intentionally-wrong-token")"
  db_integrity="$(sqlite3 -readonly "$db_path" 'PRAGMA quick_check;' 2>/dev/null | head -n 1 || true)"
  supervisor="$(tunnel_status)"
  result="passed"
  if [[ ! "$server_pid" =~ ^[0-9]+$ || ! "$tunnel_pid" =~ ^[0-9]+$ ||
        "$local_http" != "200" || "$local_wrong_http" != "401" ||
        "$stable_http" != "200" || "$stable_wrong_http" != "401" ||
        "$db_integrity" != "ok" || "$supervisor" != "healthy" ]]; then
    result="failed"
    failures=$((failures + 1))
  fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$samples" "$recorded_at" "${server_pid:-none}" "${tunnel_pid:-none}" \
    "$local_http" "$local_wrong_http" "$stable_http" "$stable_wrong_http" \
    "${db_integrity:-unavailable}" "$supervisor" "$result" >> "$SAMPLES_FILE"

  remaining="$((deadline_epoch - $(date +%s)))"
  (( remaining > 0 )) || break
  sleep_for="$INTERVAL_SECONDS"
  (( sleep_for > remaining )) && sleep_for="$remaining"
  sleep "$sleep_for"
done

SOAK_OUTPUT="$SUMMARY_FILE" \
SOAK_STARTED="$started_epoch" \
SOAK_COMPLETED="$(date +%s)" \
SOAK_DURATION="$DURATION_SECONDS" \
SOAK_INTERVAL="$INTERVAL_SECONDS" \
SOAK_SAMPLES="$samples" \
SOAK_FAILURES="$failures" \
SOAK_GIT_SHA="$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null || true)" \
node --input-type=module <<'NODE'
import fs from "node:fs";
const failures = Number(process.env.SOAK_FAILURES);
const summary = {
  schemaVersion: 1,
  result: failures === 0 ? "passed" : "failed",
  startedAt: new Date(Number(process.env.SOAK_STARTED) * 1000).toISOString(),
  completedAt: new Date(Number(process.env.SOAK_COMPLETED) * 1000).toISOString(),
  requestedDurationSeconds: Number(process.env.SOAK_DURATION),
  intervalSeconds: Number(process.env.SOAK_INTERVAL),
  samples: Number(process.env.SOAK_SAMPLES),
  failures,
  gitSha: process.env.SOAK_GIT_SHA,
  secretsRecorded: false
};
fs.writeFileSync(process.env.SOAK_OUTPUT, `${JSON.stringify(summary, null, 2)}\n`, { mode: 0o600 });
NODE

echo "Sync soak result: $([[ $failures -eq 0 ]] && echo passed || echo failed) ($samples samples, $failures failures)."
echo "Evidence: $OUTPUT_DIR"
(( failures == 0 ))
