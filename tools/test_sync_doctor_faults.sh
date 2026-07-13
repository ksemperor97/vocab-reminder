#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME_ROOT="${VOCAB_SYNC_RUNTIME_ROOT:-$HOME/Library/Application Support/VocabReminderServer}"
DOCTOR="$ROOT_DIR/tools/sync_doctor.sh"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/vocab-sync-doctor-faults.XXXXXX")"

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

expect_failure() {
  local name="$1"
  local pattern="$2"
  shift 2
  local output_file="$TMP_DIR/$name.log"

  if "$@" >"$output_file" 2>&1; then
    echo "Expected $name to fail, but sync doctor returned success." >&2
    return 1
  fi
  if ! grep -Fq "$pattern" "$output_file"; then
    echo "Expected $name output to contain: $pattern" >&2
    sed -n '1,220p' "$output_file" >&2
    return 1
  fi
  printf 'PASS: %s detected (%s)\n' "$name" "$pattern"
}

expect_failure \
  "node-abi" \
  "better-sqlite3 cannot load with the pinned Node ABI" \
  env VOCAB_SYNC_DOCTOR_NODE_BIN=/usr/bin/false bash "$DOCTOR"

expect_failure \
  "cloud-synced-db" \
  "database is inside a cloud-synced folder" \
  env VOCAB_SYNC_DOCTOR_DB_PATH="$ROOT_DIR/.doctor-fixture.sqlite" bash "$DOCTOR"

if [[ ! -f "$RUNTIME_ROOT/state/tunnel-supervisor.json" ]]; then
  echo "Tunnel supervisor state is required for the stale-state fault test." >&2
  exit 1
fi
cp "$RUNTIME_ROOT/state/tunnel-supervisor.json" "$TMP_DIR/stale-tunnel-state.json"
touch -t 200001010000 "$TMP_DIR/stale-tunnel-state.json"
expect_failure \
  "stale-tunnel" \
  "tunnel supervisor state is stale" \
  env TUNNEL_STATE_FILE="$TMP_DIR/stale-tunnel-state.json" bash "$DOCTOR"

echo "All sync doctor fault-injection checks passed."
