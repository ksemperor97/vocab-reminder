#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_PROJECT="$ROOT_DIR/android/VocabReminderAndroid"
QA_PACKAGE="com.vocabreminder.android.qa"
TEST_PACKAGE="com.vocabreminder.android.qa.test"
RUNNER="$TEST_PACKAGE/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="com.vocabreminder.android.M3VerticalSliceTest"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"

if [[ -n "${ADB_BIN:-}" ]]; then
  ADB="$ADB_BIN"
elif command -v adb >/dev/null 2>&1; then
  ADB="$(command -v adb)"
else
  ADB="$ANDROID_HOME/platform-tools/adb"
fi

if [[ ! -x "$ADB" ]]; then
  echo "adb is unavailable: $ADB" >&2
  exit 1
fi
if ! "$ADB" devices | awk 'NR > 1 && $2 == "device" { found=1 } END { exit found ? 0 : 1 }'; then
  echo "No authorized Android device is connected." >&2
  exit 1
fi
device_sdk="$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ ! "$device_sdk" =~ ^[0-9]+$ ]]; then
  echo "Unable to determine the connected Android SDK level." >&2
  exit 1
fi

EVIDENCE_DIR="${ANDROID_QA_EVIDENCE_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/vocab-android-qa.XXXXXX")}"
mkdir -p "$EVIDENCE_DIR"
chmod 700 "$EVIDENCE_DIR"

cleanup() {
  "$ADB" shell am force-stop "$QA_PACKAGE" >/dev/null 2>&1 || true
  "$ADB" shell am force-stop "$TEST_PACKAGE" >/dev/null 2>&1 || true
  if [[ "${KEEP_ANDROID_QA_PACKAGES:-0}" != "1" ]]; then
    "$ADB" uninstall "$TEST_PACKAGE" >/dev/null 2>&1 || true
    "$ADB" uninstall "$QA_PACKAGE" >/dev/null 2>&1 || true
  fi
  find "$EVIDENCE_DIR" -type f -exec chmod 600 {} + 2>/dev/null || true
}
trap cleanup EXIT INT TERM

if [[ "${ANDROID_QA_SKIP_BUILD:-0}" != "1" ]]; then
  echo "== Build isolated device-QA app and tests =="
  (
    cd "$ANDROID_PROJECT"
    VOCAB_SYNC_DEFAULT_URL= VOCAB_SYNC_DEFAULT_TOKEN= \
      ./gradlew --no-daemon :app:assembleDeviceQa :app:assembleDeviceQaAndroidTest
  ) >"$EVIDENCE_DIR/build.txt" 2>&1 || {
    tail -n 120 "$EVIDENCE_DIR/build.txt" >&2
    exit 1
  }
  tail -n 8 "$EVIDENCE_DIR/build.txt"
fi

QA_APK="$ANDROID_PROJECT/app/build/outputs/apk/deviceQa/app-deviceQa.apk"
TEST_APK="$ANDROID_PROJECT/app/build/outputs/apk/androidTest/deviceQa/app-deviceQa-androidTest.apk"
if [[ ! -f "$QA_APK" || ! -f "$TEST_APK" ]]; then
  echo "Device-QA APKs are missing; build them before using ANDROID_QA_SKIP_BUILD=1." >&2
  exit 1
fi

echo "== Install side-by-side QA packages =="
{
  "$ADB" uninstall "$TEST_PACKAGE" >/dev/null 2>&1 || true
  "$ADB" uninstall "$QA_PACKAGE" >/dev/null 2>&1 || true
  "$ADB" install -r -t "$QA_APK"
  "$ADB" install -r -t "$TEST_APK"
  "$ADB" shell pm list instrumentation
} >"$EVIDENCE_DIR/install.txt" 2>&1

if ! grep -Fq "instrumentation:$RUNNER (target=$QA_PACKAGE)" "$EVIDENCE_DIR/install.txt"; then
  echo "Instrumentation is not bound to the isolated QA package." >&2
  sed -n '1,120p' "$EVIDENCE_DIR/install.txt" >&2
  exit 1
fi
echo "PASS: instrumentation targets $QA_PACKAGE"

tests=(
  manualAddCreatesCardDueInTenMinutes
  sentenceCaptureShowsCandidateSelectionAndSavesPreview
  capturedRecommendedWordPromotesToUserWordbookAndKeepsProgress
  syncImportMergesDuplicateLemmaAndKeepsBothContexts
  localSyncButtonsExportAndImportSnapshot
  deleteButtonSoftDeletesUserCard
  recommendationStartKeepsCardAndRecordsFeedback
  recommendationNotificationShowsIntroMeaningBeforeStarted
  recommendationKnownAndDismissedSoftDeleteCardsAndRecordFeedback
  dailyPlannerGeneratesRecommendationSlotsAfterFiftyUserWords
  notificationActionsPersistEventsAndUpdateDueState
  reviewNotificationHidesMeaningUntilForgotten
  alarmReceiverShowsDueReviewNotificationWithoutMeaning
)

settle_seconds="${ANDROID_QA_SETTLE_SECONDS:-5}"
passed=0
failed=0
: >"$EVIDENCE_DIR/summary.txt"

echo "== Run ${#tests[@]} device scenarios sequentially =="
for test_name in "${tests[@]}"; do
  "$ADB" shell am force-stop "$QA_PACKAGE" >/dev/null 2>&1 || true
  "$ADB" shell am force-stop "$TEST_PACKAGE" >/dev/null 2>&1 || true
  "$ADB" shell pm clear "$QA_PACKAGE" >/dev/null
  if (( device_sdk >= 33 )); then
    "$ADB" shell pm grant "$QA_PACKAGE" android.permission.POST_NOTIFICATIONS >/dev/null
  fi
  sleep "$settle_seconds"

  test_log="$EVIDENCE_DIR/test-$test_name.txt"
  "$ADB" shell am instrument -w -r \
    -e class "$TEST_CLASS#$test_name" \
    "$RUNNER" >"$test_log" 2>&1 || true

  if grep -Fqx 'OK (1 test)' "$test_log" && \
      ! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$test_log"; then
    echo "PASS $test_name"
    echo "PASS $test_name" >>"$EVIDENCE_DIR/summary.txt"
    passed=$((passed + 1))
  else
    echo "FAIL $test_name" >&2
    echo "FAIL $test_name" >>"$EVIDENCE_DIR/summary.txt"
    grep -En 'FAILURES!!!|AssertionError|Error in |INSTRUMENTATION_FAILED|Process crashed|stack=' "$test_log" \
      | head -n 16 >&2 || true
    failed=$((failed + 1))
  fi
done

printf 'RESULT passed=%d failed=%d total=%d\n' "$passed" "$failed" "${#tests[@]}" \
  | tee -a "$EVIDENCE_DIR/summary.txt"
echo "Evidence: $EVIDENCE_DIR"

if (( failed > 0 )); then
  exit 1
fi
