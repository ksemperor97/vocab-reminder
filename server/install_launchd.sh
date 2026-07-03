#!/usr/bin/env bash
set -euo pipefail

SERVER_DIR="$(cd "$(dirname "$0")" && pwd)"
PLIST_SRC="$SERVER_DIR/com.vocabreminder.sync.plist"
PLIST_DST="$HOME/Library/LaunchAgents/com.vocabreminder.sync.plist"

if ! command -v npm >/dev/null 2>&1; then
  echo "npm is not installed. Install Node 20+ on the Mac mini, then rerun this script." >&2
  exit 1
fi

NPM_BIN="$(command -v npm)"

mkdir -p "$SERVER_DIR/data" "$SERVER_DIR/backups" "$SERVER_DIR/logs" "$HOME/Library/LaunchAgents"

if [[ ! -f "$SERVER_DIR/.env" ]]; then
  umask 077
  TOKEN="$(openssl rand -hex 32)"
  sed "s/replace-with-openssl-rand-hex-32/$TOKEN/" "$SERVER_DIR/.env.example" > "$SERVER_DIR/.env"
  echo "Created $SERVER_DIR/.env with a new VOCAB_SYNC_TOKEN."
fi

SYNC_TOKEN="$(sed -n 's/^VOCAB_SYNC_TOKEN=//p' "$SERVER_DIR/.env" | head -n 1)"
SYNC_HOST="$(sed -n 's/^VOCAB_SYNC_HOST=//p' "$SERVER_DIR/.env" | head -n 1)"

if [[ -z "$SYNC_TOKEN" || "$SYNC_TOKEN" == "replace-with-openssl-rand-hex-32" ]]; then
  echo "$SERVER_DIR/.env must contain a real VOCAB_SYNC_TOKEN." >&2
  exit 78
fi

if [[ -z "$SYNC_HOST" ]]; then
  printf '\nVOCAB_SYNC_HOST=127.0.0.1\n' >> "$SERVER_DIR/.env"
  SYNC_HOST="127.0.0.1"
fi

if [[ "$SYNC_HOST" != "127.0.0.1" ]]; then
  echo "$SERVER_DIR/.env must keep VOCAB_SYNC_HOST=127.0.0.1 for tunnel-only exposure." >&2
  exit 78
fi

npm --prefix "$SERVER_DIR" install --omit=dev

escape_sed() {
  printf '%s' "$1" | sed 's/[\/&|]/\\&/g'
}

sed \
  -e "s|__SERVER_DIR__|$(escape_sed "$SERVER_DIR")|g" \
  -e "s|__NPM_BIN__|$(escape_sed "$NPM_BIN")|g" \
  "$PLIST_SRC" > "$PLIST_DST"

launchctl bootout "gui/$(id -u)" "$PLIST_DST" >/dev/null 2>&1 || true
launchctl bootstrap "gui/$(id -u)" "$PLIST_DST"
launchctl kickstart -k "gui/$(id -u)/com.vocabreminder.sync"

echo "Installed launchd agent: $PLIST_DST"
echo "Server logs: $SERVER_DIR/logs/sync.out.log and sync.err.log"
