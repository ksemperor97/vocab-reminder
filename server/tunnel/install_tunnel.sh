#!/usr/bin/env bash
set -euo pipefail

SOURCE_SERVER_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME_ROOT="${VOCAB_SYNC_RUNTIME_ROOT:-$HOME/Library/Application Support/VocabReminderServer}"
CURRENT_DIR="$RUNTIME_ROOT/current"
TUNNEL_DIR="$CURRENT_DIR/tunnel"
ENV_FILE="${VOCAB_SYNC_TUNNEL_ENV_FILE:-$RUNTIME_ROOT/tunnel.env}"
SERVER_ENV="$RUNTIME_ROOT/.env"
LEGACY_ENV_FILE="$SOURCE_SERVER_DIR/tunnel/.env"
ENV_EXAMPLE="$SOURCE_SERVER_DIR/tunnel/.env.example"
PLIST_SRC="$SOURCE_SERVER_DIR/com.vocabreminder.tunnel.plist"
PLIST_DST="$HOME/Library/LaunchAgents/com.vocabreminder.tunnel.plist"
LOG_DIR="$RUNTIME_ROOT/logs"
STATE_FILE="$RUNTIME_ROOT/state/tunnel-supervisor.json"

if [[ ! -d "$CURRENT_DIR/tunnel" ]]; then
  echo "Runtime release is missing at $CURRENT_DIR. Run server/install_launchd.sh first." >&2
  exit 78
fi

if [[ ! -f "$SERVER_ENV" ]]; then
  echo "Runtime server environment is missing at $SERVER_ENV. Run server/install_launchd.sh first." >&2
  exit 78
fi

mkdir -p "$LOG_DIR" "$RUNTIME_ROOT/state" "$HOME/Library/LaunchAgents"

if [[ ! -f "$ENV_FILE" ]]; then
  umask 077
  if [[ -f "$LEGACY_ENV_FILE" ]]; then
    cp "$LEGACY_ENV_FILE" "$ENV_FILE"
    echo "Migrated the existing tunnel environment to $ENV_FILE."
  else
    cp "$ENV_EXAMPLE" "$ENV_FILE"
    echo "Created $ENV_FILE."
    echo "Edit it for cloudflare_quick_worker or cloudflare_named, then rerun this script." >&2
    exit 78
  fi
fi
chmod 600 "$ENV_FILE"

set -a
# shellcheck disable=SC1090
. "$ENV_FILE"
set +a

provider="${TUNNEL_PROVIDER:-}"
if [[ -z "$provider" ]]; then
  provider="cloudflare_quick_worker"
fi

if ! command -v cloudflared >/dev/null 2>&1; then
  if command -v brew >/dev/null 2>&1; then
    brew install cloudflared
  else
    echo "cloudflared is not installed and Homebrew was not found." >&2
    echo "Install cloudflared first, then rerun this script." >&2
    exit 1
  fi
fi

CLOUDFLARED_BIN="$(command -v cloudflared)"
SERVER_PLIST="$HOME/Library/LaunchAgents/com.vocabreminder.sync.plist"
NODE_BIN="$(/usr/libexec/PlistBuddy -c 'Print :ProgramArguments:0' "$SERVER_PLIST" 2>/dev/null || true)"
if [[ -z "$NODE_BIN" || ! -x "$NODE_BIN" ]]; then
  NODE_BIN="$(command -v node || true)"
fi
if [[ -n "$NODE_BIN" ]]; then
  NODE_BIN="$("$NODE_BIN" -p 'process.execPath')"
fi

case "$provider" in
  cloudflare_quick_worker|cloudflare-worker|quick-worker)
    if [[ -z "$NODE_BIN" ]]; then
      echo "node is required for cloudflare_quick_worker tunnel management." >&2
      exit 1
    fi
    if [[ -z "${CF_WORKER_PUBLIC_URL:-}" || "$CF_WORKER_PUBLIC_URL" == "https://vocab-sync.your-workers-subdomain.workers.dev" ]]; then
      echo "$ENV_FILE must define CF_WORKER_PUBLIC_URL for cloudflare_quick_worker." >&2
      exit 78
    fi
    if [[ "$CF_WORKER_PUBLIC_URL" != https://* ]]; then
      echo "CF_WORKER_PUBLIC_URL must use https: $CF_WORKER_PUBLIC_URL" >&2
      exit 78
    fi
    if [[ -z "${CF_WORKER_ADMIN_TOKEN:-}" || "$CF_WORKER_ADMIN_TOKEN" == "replace-with-worker-admin-token" ]]; then
      echo "$ENV_FILE must define CF_WORKER_ADMIN_TOKEN for cloudflare_quick_worker." >&2
      exit 78
    fi
    ;;
  cloudflare_named|cloudflared|cloudflare)
    if [[ -n "${CLOUDFLARED_CONFIG:-}" ]]; then
      if [[ ! -f "$CLOUDFLARED_CONFIG" ]]; then
        echo "CLOUDFLARED_CONFIG does not exist: $CLOUDFLARED_CONFIG" >&2
        exit 78
      fi
    elif [[ -z "${CLOUDFLARED_TUNNEL_TOKEN:-}" ]]; then
      echo "$ENV_FILE must define CLOUDFLARED_TUNNEL_TOKEN or CLOUDFLARED_CONFIG." >&2
      exit 78
    elif [[ "$CLOUDFLARED_TUNNEL_TOKEN" == "replace-with-cloudflare-tunnel-token" ]]; then
      echo "Replace CLOUDFLARED_TUNNEL_TOKEN in $ENV_FILE before installing the tunnel agent." >&2
      exit 78
    fi
    ;;
  *)
    echo "Unsupported TUNNEL_PROVIDER=$provider" >&2
    exit 78
    ;;
esac

escape_sed() {
  printf '%s' "$1" | sed 's/[\/&|]/\\&/g'
}

sed \
  -e "s|__CURRENT_DIR__|$(escape_sed "$CURRENT_DIR")|g" \
  -e "s|__RUNTIME_ROOT__|$(escape_sed "$RUNTIME_ROOT")|g" \
  -e "s|__SERVER_ENV__|$(escape_sed "$SERVER_ENV")|g" \
  -e "s|__TUNNEL_ENV__|$(escape_sed "$ENV_FILE")|g" \
  -e "s|__LOG_DIR__|$(escape_sed "$LOG_DIR")|g" \
  -e "s|__STATE_FILE__|$(escape_sed "$STATE_FILE")|g" \
  -e "s|__CLOUDFLARED_BIN__|$(escape_sed "$CLOUDFLARED_BIN")|g" \
  -e "s|__NODE_BIN__|$(escape_sed "$NODE_BIN")|g" \
  "$PLIST_SRC" > "$PLIST_DST"

launchctl bootout "gui/$(id -u)" "$PLIST_DST" >/dev/null 2>&1 || true
launchctl bootstrap "gui/$(id -u)" "$PLIST_DST"
launchctl kickstart -k "gui/$(id -u)/com.vocabreminder.tunnel"

echo "Installed launchd agent: $PLIST_DST"
echo "Tunnel provider: $provider"
echo "Tunnel logs: $LOG_DIR/tunnel.out.log and tunnel.err.log"
echo "Tunnel state: $STATE_FILE"
