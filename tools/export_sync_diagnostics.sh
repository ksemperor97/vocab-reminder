#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd -P)"
RUNTIME_ROOT="${VOCAB_SYNC_RUNTIME_ROOT:-$HOME/Library/Application Support/VocabReminderServer}"
STAMP="$(date '+%Y%m%d_%H%M%S')"
OUTPUT_DIR="${1:-/tmp/vocab-sync-diagnostics-$STAMP}"
NODE_BIN="${VOCAB_SYNC_DIAGNOSTICS_NODE_BIN:-$(command -v node)}"

if [[ -e "$OUTPUT_DIR" ]]; then
  echo "Diagnostics destination already exists: $OUTPUT_DIR" >&2
  exit 1
fi
mkdir -p "$OUTPUT_DIR"
chmod 700 "$OUTPUT_DIR"

redact_stream() {
  sed -E \
    -e 's/(Authorization:[[:space:]]*Bearer[[:space:]]+)[^[:space:]]+/\1[REDACTED]/Ig' \
    -e 's/(VOCAB_SYNC_TOKEN|CF_WORKER_ADMIN_TOKEN|CLOUDFLARED_TUNNEL_TOKEN|storePassword|keyPassword)=.*/\1=[REDACTED]/g' \
    -e 's/[A-Fa-f0-9]{48,}/[REDACTED_LONG_VALUE]/g'
}

write_manifest() {
  DIAGNOSTICS_OUTPUT="$OUTPUT_DIR/manifest.json" \
  DIAGNOSTICS_ROOT="$ROOT_DIR" \
  DIAGNOSTICS_RUNTIME="$RUNTIME_ROOT" \
  DIAGNOSTICS_GIT_SHA="$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null || true)" \
  DIAGNOSTICS_GIT_BRANCH="$(git -C "$ROOT_DIR" branch --show-current 2>/dev/null || true)" \
  DIAGNOSTICS_NODE_VERSION="$($NODE_BIN --version 2>/dev/null || true)" \
  DIAGNOSTICS_SWIFT_VERSION="$(swift --version 2>/dev/null | head -n 1 || true)" \
  DIAGNOSTICS_JAVA_VERSION="$(java -version 2>&1 | head -n 1 || true)" \
  "$NODE_BIN" --input-type=module <<'NODE'
import fs from "node:fs";

const manifest = {
  schemaVersion: 1,
  redacted: true,
  recordedAt: new Date().toISOString(),
  gitSha: process.env.DIAGNOSTICS_GIT_SHA,
  gitBranch: process.env.DIAGNOSTICS_GIT_BRANCH,
  runtimeRoot: process.env.DIAGNOSTICS_RUNTIME,
  tools: {
    node: process.env.DIAGNOSTICS_NODE_VERSION,
    swift: process.env.DIAGNOSTICS_SWIFT_VERSION,
    java: process.env.DIAGNOSTICS_JAVA_VERSION
  },
  notice: "No environment-file values, database rows, capture text, or bearer tokens are included."
};
fs.writeFileSync(process.env.DIAGNOSTICS_OUTPUT, `${JSON.stringify(manifest, null, 2)}\n`);
NODE
}

write_state_summary() {
  local input="$1"
  local output="$2"
  local kind="$3"
  [[ -f "$input" ]] || return 0
  "$NODE_BIN" --input-type=module - "$input" "$output" "$kind" <<'NODE'
import fs from "node:fs";

const [input, output, kind] = process.argv.slice(2);
const value = JSON.parse(fs.readFileSync(input, "utf8"));
const keys = kind === "tunnel"
  ? [
      "schema_version", "supervisor_status", "child_status", "child_pid",
      "generation", "started_at", "updated_at", "last_local_health_at",
      "last_quick_health_at", "last_stable_health_at", "last_error",
      "next_retry_at", "restart_count", "consecutive_failures"
    ]
  : ["status", "releaseId", "releasePath", "previousRelease", "startedAt", "completedAt", "error"];
const summary = {};
for (const key of keys) {
  if (value[key] !== undefined) summary[key] = value[key];
}
fs.writeFileSync(output, `${JSON.stringify(summary, null, 2)}\n`);
NODE
}

write_env_shape() {
  local input="$1"
  local output="$2"
  [[ -f "$input" ]] || return 0
  awk -F= '
    /^[[:space:]]*#/ || /^[[:space:]]*$/ { next }
    /^[[:space:]]*[A-Za-z_][A-Za-z0-9_]*[[:space:]]*=/ {
      key=$1
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", key)
      value=$0
      sub(/^[^=]*=/, "", value)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
      printf "%s=%s\n", key, (value == "" ? "unset" : "set")
    }
  ' "$input" > "$output"
}

write_manifest

set +e
bash "$ROOT_DIR/tools/sync_doctor.sh" 2>&1 | redact_stream > "$OUTPUT_DIR/sync-doctor.txt"
doctor_status=${PIPESTATUS[0]}
set -e
printf 'exit_code=%s\n' "$doctor_status" > "$OUTPUT_DIR/sync-doctor-exit.txt"

write_env_shape "$RUNTIME_ROOT/.env" "$OUTPUT_DIR/server-env-shape.txt"
write_env_shape "$RUNTIME_ROOT/tunnel.env" "$OUTPUT_DIR/tunnel-env-shape.txt"
write_state_summary "$RUNTIME_ROOT/state/deployment.json" "$OUTPUT_DIR/deployment-state.json" deployment
write_state_summary "$RUNTIME_ROOT/state/tunnel-supervisor.json" "$OUTPUT_DIR/tunnel-state.json" tunnel
write_state_summary "$RUNTIME_ROOT/state/last_restore_drill.json" "$OUTPUT_DIR/restore-drill-state.json" restore

if command -v launchctl >/dev/null 2>&1; then
  for label in com.vocabreminder.sync com.vocabreminder.tunnel; do
    launchctl print "gui/$(id -u)/$label" 2>/dev/null \
      | awk '
          /^[[:space:]]*(state|pid|runs|last exit code|last terminating signal|program|working directory|stdout path|stderr path)[[:space:]]*=/ { print }
        ' \
      | redact_stream > "$OUTPUT_DIR/$label.txt" || true
  done
fi

if [[ -d "$RUNTIME_ROOT/logs" ]]; then
  : > "$OUTPUT_DIR/log-files.txt"
  while IFS= read -r log_file; do
    printf '%s bytes=%s modified_epoch=%s\n' \
      "$(basename "$log_file")" \
      "$(stat -f %z "$log_file" 2>/dev/null || printf unknown)" \
      "$(stat -f %m "$log_file" 2>/dev/null || printf unknown)" \
      >> "$OUTPUT_DIR/log-files.txt"
    tail -n 80 "$log_file" 2>/dev/null \
      | redact_stream > "$OUTPUT_DIR/$(basename "$log_file").redacted-tail.txt" || true
  done < <(find "$RUNTIME_ROOT/logs" -maxdepth 1 -type f -name '*.log' -print | sort)
fi

find "$OUTPUT_DIR" -type f -exec chmod 600 {} +
echo "Redacted diagnostics are ready for review: $OUTPUT_DIR"
echo "sync:doctor exit code: $doctor_status"
