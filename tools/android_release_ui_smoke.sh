#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-adb}"
DIST_DIR="${SYNC_RELEASE_DIST_DIR:-$ROOT_DIR/dist/sync-release}"
APK="$DIST_DIR/VocabReminderAndroid-release.apk"
PKG="com.vocabreminder.android"
EVIDENCE_DIR="${EVIDENCE_DIR:-/tmp/vocab_sync_release_ui_$(date +%Y%m%d_%H%M%S)}"

if [[ ! -f "$APK" ]]; then
  echo "Missing Android release APK: $APK" >&2
  exit 1
fi

if ! command -v "$ADB" >/dev/null 2>&1; then
  echo "adb was not found. Set ADB or install Android platform-tools." >&2
  exit 1
fi

mkdir -p "$EVIDENCE_DIR"

"$ADB" get-state >/dev/null
"$ADB" install -r "$APK" >/dev/null

if "$ADB" shell run-as "$PKG" id >/dev/null 2>&1; then
  echo "FAIL: installed package is debuggable; expected a release APK." >&2
  exit 1
fi
echo "OK: installed package is not debuggable"

dump_ui() {
  local name="$1"
  "$ADB" shell uiautomator dump "/sdcard/${name}.xml" >/dev/null
  "$ADB" exec-out cat "/sdcard/${name}.xml" > "$EVIDENCE_DIR/${name}.xml"
}

tap_text() {
  local xml_file="$1"
  local text="$2"
  local pick="${3:-last}"
  python3 - "$xml_file" "$text" "$pick" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

xml_file, wanted, pick = sys.argv[1:4]
root = ET.parse(xml_file).getroot()
matches = []
for node in root.iter("node"):
    if node.attrib.get("text") != wanted:
        continue
    bounds = node.attrib.get("bounds", "")
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if not m:
        continue
    x1, y1, x2, y2 = map(int, m.groups())
    matches.append(((x1 + x2) // 2, (y1 + y2) // 2, y1, y2))

if not matches:
    raise SystemExit(f"text not found: {wanted}")

if pick == "first":
    chosen = min(matches, key=lambda item: item[2])
else:
    chosen = max(matches, key=lambda item: item[3])

print(chosen[0], chosen[1])
PY
}

assert_absent() {
  local xml_file="$1"
  shift
  local failed=0
  for needle in "$@"; do
    if grep -Fq "$needle" "$xml_file"; then
      echo "FAIL: forbidden UI text found in $(basename "$xml_file"): $needle" >&2
      failed=1
    fi
  done
  [[ "$failed" -eq 0 ]]
}

assert_present() {
  local xml_file="$1"
  shift
  for needle in "$@"; do
    if ! grep -Fq "$needle" "$xml_file"; then
      echo "FAIL: expected UI text missing in $(basename "$xml_file"): $needle" >&2
      exit 1
    fi
  done
}

forbidden_sync_text=(
  "서버 주소"
  "토큰"
  "VPN"
  "WireGuard"
  "Debug sync fields"
  "sync_server_url_field"
  "sync_auth_token_field"
  "서버:"
  "https://"
  "http://"
)

"$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
"$ADB" shell wm dismiss-keyguard >/dev/null 2>&1 || true
"$ADB" shell am force-stop "$PKG"
"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 2
dump_ui "release_launch"

read -r settings_x settings_y < <(tap_text "$EVIDENCE_DIR/release_launch.xml" "설정" "last")
"$ADB" shell input tap "$settings_x" "$settings_y"
sleep 1
dump_ui "release_settings"

sync_coords=""
if sync_coords="$(tap_text "$EVIDENCE_DIR/release_settings.xml" "동기화" "last" 2>/dev/null)"; then
  :
else
  for attempt in 1 2 3; do
    "$ADB" shell input swipe 540 2100 540 500 500
    sleep 1
    dump_ui "release_settings_scrolled_${attempt}"
    if sync_coords="$(tap_text "$EVIDENCE_DIR/release_settings_scrolled_${attempt}.xml" "동기화" "last" 2>/dev/null)"; then
      break
    fi
  done
fi

if [[ -z "$sync_coords" ]]; then
  echo "FAIL: could not find the 동기화 button in the release settings UI." >&2
  exit 1
fi

read -r sync_x sync_y <<< "$sync_coords"
"$ADB" shell input tap "$sync_x" "$sync_y"
sleep 1
dump_ui "release_sync_panel"

assert_present "$EVIDENCE_DIR/release_sync_panel.xml" \
  "자동 동기화: 준비됨" \
  "연결 테스트" \
  "지금 동기화"

assert_absent "$EVIDENCE_DIR/release_sync_panel.xml" "${forbidden_sync_text[@]}"
echo "OK: release sync UI hides server URL, token, VPN, WireGuard, and endpoint text"

"$ADB" shell dumpsys connectivity > "$EVIDENCE_DIR/connectivity.txt"
if grep -Fq "VPN:com.adguard.android" "$EVIDENCE_DIR/connectivity.txt"; then
  echo "OK: AdGuard VPN is active during release UI smoke"
else
  echo "WARN: AdGuard VPN was not detected during release UI smoke"
fi

"$ADB" shell dumpsys jobscheduler | \
  grep -E 'com\.vocabreminder\.android|START: #u0a[0-9]+/[0-9]+ com\.vocabreminder\.android|STOP: #u0a[0-9]+/[0-9]+ com\.vocabreminder\.android' \
  > "$EVIDENCE_DIR/jobscheduler_vocab.txt" || true

if grep -Fq "com.vocabreminder.android/androidx.work.impl.background.systemjob.SystemJobService" "$EVIDENCE_DIR/jobscheduler_vocab.txt"; then
  echo "OK: WorkManager job evidence captured"
else
  echo "WARN: WorkManager job evidence was not found"
fi

echo "Evidence: $EVIDENCE_DIR"
