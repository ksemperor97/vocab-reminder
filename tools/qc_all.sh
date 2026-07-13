#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
STAMP="$(date '+%Y%m%d_%H%M%S')"
EVIDENCE_DIR="${QC_EVIDENCE_DIR:-/tmp/vocab_product_completion_$(date '+%Y%m%d')/qc_$STAMP}"
LOG_FILE="$EVIDENCE_DIR/qc.log"
MANIFEST_FILE="$EVIDENCE_DIR/manifest.json"

mkdir -p "$EVIDENCE_DIR"
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
    swift: process.env.QC_SWIFT_VERSION
  }
};
fs.writeFileSync(process.env.QC_MANIFEST, `${JSON.stringify(manifest, null, 2)}\n`);
NODE
}

on_exit() {
  exit_code=$?
  if [[ $exit_code -eq 0 ]]; then
    status="passed"
  else
    status="failed"
  fi
  write_manifest
  printf '\nQC status: %s\nEvidence: %s\n' "$status" "$EVIDENCE_DIR"
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
(
  cd "$ROOT_DIR/server"
  node --input-type=module -e "await import('better-sqlite3'); console.log('better-sqlite3 ABI preflight passed')"
  npm test
)

section "Tunnel supervisor and Worker tests"
node --test \
  "$ROOT_DIR/server/tunnel/run_cloudflare_quick_worker.test.mjs" \
  "$ROOT_DIR/server/tunnel/cloudflare_worker/worker.test.mjs"

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
    :app:assembleDebugAndroidTest
)

section "Mac XCTest, build, and scheduler vectors"
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

status="passed"
