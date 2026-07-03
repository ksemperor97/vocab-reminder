#!/usr/bin/env bash
set -euo pipefail

SERVER_DIR="$(cd "$(dirname "$0")/.." && pwd)"
TUNNEL_DIR="$SERVER_DIR/tunnel"
ENV_FILE="$TUNNEL_DIR/.env"
ENV_EXAMPLE="$TUNNEL_DIR/.env.example"
PLIST_SRC="$SERVER_DIR/com.vocabreminder.tunnel.plist"
PLIST_DST="$HOME/Library/LaunchAgents/com.vocabreminder.tunnel.plist"

mkdir -p "$SERVER_DIR/logs" "$HOME/Library/LaunchAgents"

if [[ ! -f "$ENV_FILE" ]]; then
  umask 077
  cp "$ENV_EXAMPLE" "$ENV_FILE"
  echo "Created $ENV_FILE."
  echo "Edit it for cloudflare_quick_worker or cloudflare_named, then rerun this script." >&2
  exit 78
fi

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
NODE_BIN="$(command -v node || true)"

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
  -e "s|__SERVER_DIR__|$(escape_sed "$SERVER_DIR")|g" \
  -e "s|__CLOUDFLARED_BIN__|$(escape_sed "$CLOUDFLARED_BIN")|g" \
  -e "s|__NODE_BIN__|$(escape_sed "$NODE_BIN")|g" \
  "$PLIST_SRC" > "$PLIST_DST"

launchctl bootout "gui/$(id -u)" "$PLIST_DST" >/dev/null 2>&1 || true
launchctl bootstrap "gui/$(id -u)" "$PLIST_DST"
launchctl kickstart -k "gui/$(id -u)/com.vocabreminder.tunnel"

echo "Installed launchd agent: $PLIST_DST"
echo "Tunnel provider: $provider"
echo "Tunnel logs: $SERVER_DIR/logs/tunnel.out.log and tunnel.err.log"
