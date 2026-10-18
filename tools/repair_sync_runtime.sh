#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME_ROOT="${VOCAB_SYNC_RUNTIME_ROOT:-$HOME/Library/Application Support/VocabReminderServer}"
STATE_FILE="$RUNTIME_ROOT/state/tunnel-supervisor.json"

case "$(printf '%s' "$RUNTIME_ROOT" | tr '[:upper:]' '[:lower:]')" in
  *"/dropbox/"*|*"/library/mobile documents/"*|*"/icloud drive/"*|*"/onedrive/"*|*"/google drive/"*)
    echo "repair_sync_runtime: runtime root must not be cloud-synced: $RUNTIME_ROOT" >&2
    exit 78
    ;;
esac

export VOCAB_SYNC_RUNTIME_ROOT="$RUNTIME_ROOT"

echo "== Deploy and verify isolated server runtime =="
"$ROOT_DIR/server/install_launchd.sh"

echo "== Install the outbound tunnel (Tailscale Funnel, or the legacy Cloudflare supervisor) =="
"$ROOT_DIR/server/tunnel/install_tunnel.sh"

NODE_BIN="$(/usr/libexec/PlistBuddy -c 'Print :ProgramArguments:0' "$HOME/Library/LaunchAgents/com.vocabreminder.sync.plist" 2>/dev/null || command -v node)"

if grep -q '^TUNNEL_PROVIDER=tailscale_funnel' "$RUNTIME_ROOT/tunnel.env" 2>/dev/null; then
  # install_tailscale_funnel.sh already waited for the public /health check.
  echo "== Tailscale Funnel verified by the installer =="
else
  echo "== Wait for connection-aware tunnel health =="
  deadline=$(( $(date +%s) + 120 ))
  while (( $(date +%s) < deadline )); do
    if [[ -f "$STATE_FILE" ]] && "$NODE_BIN" -e '
      const fs = require("node:fs");
      const state = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
      process.exit(state.supervisor_status === "healthy" && state.child_status === "running" ? 0 : 1);
    ' "$STATE_FILE" >/dev/null 2>&1; then
      break
    fi
    sleep 2
  done

  if [[ ! -f "$STATE_FILE" ]] || ! "$NODE_BIN" -e '
    const fs = require("node:fs");
    const state = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
    process.exit(state.supervisor_status === "healthy" && state.child_status === "running" ? 0 : 1);
  ' "$STATE_FILE" >/dev/null 2>&1; then
    echo "repair_sync_runtime: tunnel did not become healthy within 120 seconds." >&2
    exit 1
  fi
fi

echo "== Final operational diagnosis =="
"$ROOT_DIR/tools/sync_doctor.sh"
