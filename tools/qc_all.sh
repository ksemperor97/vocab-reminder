#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
STAMP="$(date '+%Y%m%d_%H%M%S')"
EVIDENCE_DIR="${QC_EVIDENCE_DIR:-/tmp/vocab_product_completion_$(date '+%Y%m%d')/qc_$STAMP}"
LOG_FILE="$EVIDENCE_DIR/qc.log"
MANIFEST_FILE="$EVIDENCE_DIR/manifest.json"
runtime_env="${VOCAB_SYNC_SERVER_ENV_FILE:-${VOCAB_SYNC_RUNTIME_ROOT:-$HOME/Library/Application Support/VocabReminderServer}/.env}"
tunnel_env="${VOCAB_SYNC_TUNNEL_ENV_FILE:-${VOCAB_SYNC_RUNTIME_ROOT:-$HOME/Library/Application Support/VocabReminderServer}/tunnel.env}"

mkdir -p "$EVIDENCE_DIR"
chmod 700 "$EVIDENCE_DIR"
touch "$LOG_FILE"
chmod 600 "$LOG_FILE"
exec > >(tee "$LOG_FILE") 2>&1

status="running"
write_manifest() {
  QC_STATUS="$status" \
  QC_ROOT="$ROOT_DIR" \
  QC_MANIFEST="$MANIFEST_FILE" \
  QC_GIT_SHA="$(git -C "$ROOT_DIR" rev-parse HEAD)" \
  QC_GIT_BRANCH="$(git -C "$ROOT_DIR" branch --show-current)" \
  QC_NODE_VERSION="$(node --version 2>/dev/null || true)" \
  QC_NODE_ABI="$(node -p 'process.versions.modules' 2>/dev/null || true)" \
  QC_JAVA_VERSION="$(java -version 2>&1 | head -n 1 || true)" \
  QC_SWIFT_VERSION="$(swift --version 2>/dev/null | head -n 1 || true)" \
  QC_ANDROID_SDK="${ANDROID_HOME:-not-configured}" \
  node --input-type=module <<'NODE'
import fs from "node:fs";

const manifest = {
  schemaVersion: 1,
  status: process.env.QC_STATUS,
  recordedAt: new Date().toISOString(),
  root: process.env.QC_ROOT,
  gitSha: process.env.QC_GIT_SHA,
  gitBranch: process.env.QC_GIT_BRANCH,
  tools: {
    node: process.env.QC_NODE_VERSION,
    nodeModuleAbi: process.env.QC_NODE_ABI,
    java: process.env.QC_JAVA_VERSION,
    swift: process.env.QC_SWIFT_VERSION,
    androidSdk: process.env.QC_ANDROID_SDK
  }
};
fs.writeFileSync(process.env.QC_MANIFEST, `${JSON.stringify(manifest, null, 2)}\n`);
NODE
}

ensure_evidence_log_is_secret_free() {
  [[ -f "$LOG_FILE" ]] || return 0
  local secret_spec secret_file secret_key secret_value
  for secret_spec in "$runtime_env:VOCAB_SYNC_TOKEN" "$tunnel_env:CF_WORKER_ADMIN_TOKEN"; do
    secret_file="${secret_spec%:*}"
    secret_key="${secret_spec##*:}"
    [[ -f "$secret_file" ]] || continue
    secret_value="$(awk -F= -v key="$secret_key" '
      $1 ~ "^[[:space:]]*" key "[[:space:]]*$" {
        value=$0
        sub(/^[^=]*=/, "", value)
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
        gsub(/^["'\'']|["'\'']$/, "", value)
        print value
        exit
      }
    ' "$secret_file")"
    if [[ -n "$secret_value" ]] && grep -Fq -- "$secret_value" "$LOG_FILE"; then
      rm -f "$LOG_FILE"
      unset secret_value
      return 1
    fi
    unset secret_value
  done
  return 0
}

on_exit() {
  exit_code=$?
  trap - EXIT
  if ! ensure_evidence_log_is_secret_free; then
    exit_code=1
    printf '\nQC log contained a protected token and was removed.\n' >&2
  fi
  if [[ $exit_code -eq 0 ]]; then
    status="passed"
  else
    status="failed"
  fi
  write_manifest
  printf '\nQC status: %s\nEvidence: %s\n' "$status" "$EVIDENCE_DIR"
  exit "$exit_code"
}
trap on_exit EXIT

section() {
  printf '\n== %s ==\n' "$1"
}

section "Toolchain and source"
printf 'Git: %s (%s)\n' "$(git -C "$ROOT_DIR" rev-parse HEAD)" "$(git -C "$ROOT_DIR" branch --show-current)"
printf 'Node: %s, ABI %s\n' "$(node --version)" "$(node -p 'process.versions.modules')"
printf 'Java: %s\n' "$(java -version 2>&1 | head -n 1)"
printf 'Swift: %s\n' "$(swift --version | head -n 1)"

section "Server native preflight and tests"
SERVER_NODE_BIN="${QC_SERVER_NODE_BIN:-}"
if [[ -z "$SERVER_NODE_BIN" && -f "$HOME/Library/LaunchAgents/com.vocabreminder.sync.plist" ]]; then
  SERVER_NODE_BIN="$(/usr/libexec/PlistBuddy -c 'Print :ProgramArguments:0' "$HOME/Library/LaunchAgents/com.vocabreminder.sync.plist" 2>/dev/null || true)"
fi
if [[ -z "$SERVER_NODE_BIN" || ! -x "$SERVER_NODE_BIN" ]]; then
  SERVER_NODE_BIN="$(command -v node)"
fi
(
  cd "$ROOT_DIR/server"
  "$SERVER_NODE_BIN" tools/check_node_runtime.mjs
  "$SERVER_NODE_BIN" --test
)

section "Tunnel supervisor and Worker tests"
"$SERVER_NODE_BIN" --test \
  "$ROOT_DIR/server/tunnel/run_cloudflare_quick_worker.test.mjs" \
  "$ROOT_DIR/server/tunnel/cloudflare_worker/worker.test.mjs"

section "Runtime doctor and injected fault detection"
if [[ -d "${VOCAB_SYNC_RUNTIME_ROOT:-$HOME/Library/Application Support/VocabReminderServer}" ]]; then
  bash "$ROOT_DIR/tools/sync_doctor.sh"
  bash "$ROOT_DIR/tools/test_sync_doctor_faults.sh"
else
  echo "NOT RUN: runtime doctor (isolated runtime is not installed on this machine)."
fi

section "Dictionary and recommendation product gates"
node --test "$ROOT_DIR/shared/recommendations/scripts/recommendation_review_pipeline.test.mjs"
node "$ROOT_DIR/tools/run_recommendation_quality_gate.mjs"
if [[ "${QC_REQUIRE_LONG_TERM_RECOMMENDATIONS:-0}" == "1" ]]; then
  RECOMMENDATION_REQUIRE_LONG_TERM=1 node "$ROOT_DIR/tools/run_recommendation_quality_gate.mjs"
fi
if [[ -f "$runtime_env" && -f "$tunnel_env" ]]; then
  (
    set +x
    set -a
    # shellcheck disable=SC1090
    . "$runtime_env"
    # shellcheck disable=SC1090
    . "$tunnel_env"
    set +a
    VOCAB_SYNC_DEFAULT_URL="${VOCAB_SYNC_DEFAULT_URL:-$CF_WORKER_PUBLIC_URL}" \
    VOCAB_SYNC_DEFAULT_TOKEN="${VOCAB_SYNC_DEFAULT_TOKEN:-$VOCAB_SYNC_TOKEN}" \
      node "$ROOT_DIR/tools/run_dictionary_benchmark.mjs"
  )
else
  echo "NOT RUN: live 200-word dictionary benchmark (protected runtime env is unavailable)."
fi

section "Single-user secret boundaries"
node "$ROOT_DIR/tools/check_secret_boundaries.mjs" --allow-missing
node "$ROOT_DIR/tools/scan_secret_leaks.mjs"
node --test "$ROOT_DIR/tools/security_controls.test.mjs"
bash "$ROOT_DIR/tools/test_android_release_signing.sh"

section "Android unit, lint, and build"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "JAVA_HOME is not usable: $JAVA_HOME" >&2
  exit 1
fi
if [[ ! -d "$ANDROID_HOME" ]]; then
  echo "ANDROID_HOME is not usable: $ANDROID_HOME" >&2
  exit 1
fi
(
  cd "$ROOT_DIR/android/VocabReminderAndroid"
  ./gradlew --no-daemon \
    :app:testDebugUnitTest \
    :app:lintDebug \
    :app:assembleDebug \
    :app:assembleDeviceQa \
    :app:assembleDeviceQaAndroidTest
)

section "Android signed release"
signing_properties="${VOCAB_ANDROID_SIGNING_PROPERTIES:-$HOME/Library/Application Support/VocabReminderAndroid/release-signing.properties}"
if [[ -f "$signing_properties" && -f "$runtime_env" && -f "$tunnel_env" ]]; then
  (
    set +x
    set -a
    # shellcheck disable=SC1090
    . "$runtime_env"
    # shellcheck disable=SC1090
    . "$tunnel_env"
    set +a
    cd "$ROOT_DIR/android/VocabReminderAndroid"
    VOCAB_SYNC_DEFAULT_URL="${VOCAB_SYNC_DEFAULT_URL:-$CF_WORKER_PUBLIC_URL}" \
    VOCAB_SYNC_DEFAULT_TOKEN="${VOCAB_SYNC_DEFAULT_TOKEN:-$VOCAB_SYNC_TOKEN}" \
      ./gradlew --no-daemon :app:assembleRelease
    JAVA_HOME="$JAVA_HOME" ANDROID_HOME="$ANDROID_HOME" \
      bash "$ROOT_DIR/tools/verify_android_release_signing.sh"
  )
else
  echo "NOT RUN: signed Android release (protected signing/runtime configuration is unavailable)."
fi

section "Mac XCTest, build, and scheduler vectors"
if [[ -z "${DEVELOPER_DIR:-}" && -d "/Applications/Xcode.app/Contents/Developer" ]]; then
  export DEVELOPER_DIR="/Applications/Xcode.app/Contents/Developer"
fi
if ! xcrun --find xctest >/dev/null 2>&1; then
  echo "A complete Xcode toolchain with XCTest is required for Mac QC." >&2
  exit 1
fi
(
  cd "$ROOT_DIR/mac/VocabReminderMac"
  swift test
  swift build
  swift build -c release
  swift run VocabReminderMac --check-scheduler-vectors
)

section "Release artifact scan"
if [[ -f "$ROOT_DIR/dist/sync-release/VocabReminderAndroid-release.apk" && -d "$ROOT_DIR/dist/sync-release/VocabReminder.app" ]]; then
  (
    cd "$ROOT_DIR"
    npm run sync:check-artifacts
  )
elif [[ "${QC_REQUIRE_RELEASE_ARTIFACTS:-0}" == "1" ]]; then
  echo "Release artifacts are required but missing." >&2
  exit 1
else
  echo "NOT RUN: release artifact scan (artifacts missing; set QC_REQUIRE_RELEASE_ARTIFACTS=1 to make this fatal)."
fi

section "Physical Android device"
ADB_BIN="$(command -v adb 2>/dev/null || true)"
if [[ -z "$ADB_BIN" && -x "$ANDROID_HOME/platform-tools/adb" ]]; then
  ADB_BIN="$ANDROID_HOME/platform-tools/adb"
fi
if [[ -n "$ADB_BIN" ]] && "$ADB_BIN" devices 2>/dev/null | awk 'NR > 1 && $2 == "device" { found=1 } END { exit found ? 0 : 1 }'; then
  ADB_BIN="$ADB_BIN" \
  ANDROID_QA_SKIP_BUILD=1 \
  ANDROID_QA_EVIDENCE_DIR="$EVIDENCE_DIR/android-device" \
    bash "$ROOT_DIR/tools/run_android_instrumentation.sh"
else
  if [[ "${QC_REQUIRE_ANDROID_DEVICE:-0}" == "1" ]]; then
    echo "An authorized Android device is required for release QC." >&2
    exit 1
  fi
  echo "NOT RUN: Android instrumentation/device scenarios (no authorized device connected; set QC_REQUIRE_ANDROID_DEVICE=1 to make this fatal)."
fi

section "Evidence secret scan"
if ! ensure_evidence_log_is_secret_free; then
  echo "QC log contained a protected token and was removed." >&2
  exit 1
fi
echo "PASS: QC evidence does not contain active protected token values."

section "Post-build secret leak scan"
QC_EVIDENCE_DIR="$EVIDENCE_DIR" node "$ROOT_DIR/tools/scan_secret_leaks.mjs"

status="passed"
