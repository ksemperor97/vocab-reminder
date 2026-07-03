#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
APP_PATH="${VOCAB_REMINDER_APP_PATH:-$ROOT_DIR/dist/sync-release/VocabReminder.app}"
PLIST_SRC="$ROOT_DIR/mac/com.vocabreminder.app.plist"
PLIST_DST="$HOME/Library/LaunchAgents/com.vocabreminder.app.plist"
LOG_DIR="$ROOT_DIR/server/logs"

if [[ ! -d "$APP_PATH" ]]; then
  echo "VocabReminder.app was not found: $APP_PATH" >&2
  echo "Build release artifacts first, or set VOCAB_REMINDER_APP_PATH." >&2
  exit 1
fi

mkdir -p "$HOME/Library/LaunchAgents" "$LOG_DIR"

escape_sed() {
  printf '%s' "$1" | sed 's/[\/&|]/\\&/g'
}

sed \
  -e "s|__APP_PATH__|$(escape_sed "$APP_PATH")|g" \
  -e "s|__LOG_DIR__|$(escape_sed "$LOG_DIR")|g" \
  "$PLIST_SRC" > "$PLIST_DST"

plutil -lint "$PLIST_DST" >/dev/null

launchctl bootout "gui/$(id -u)" "$PLIST_DST" >/dev/null 2>&1 || true
launchctl bootstrap "gui/$(id -u)" "$PLIST_DST"
launchctl kickstart -k "gui/$(id -u)/com.vocabreminder.app"

echo "Installed launchd agent: $PLIST_DST"
echo "App path: $APP_PATH"
echo "App logs: $LOG_DIR/app.out.log and app.err.log"
