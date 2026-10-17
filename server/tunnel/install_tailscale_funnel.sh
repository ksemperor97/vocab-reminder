#!/usr/bin/env bash
# Publishes the local sync server through Tailscale Funnel.
#
# Funnel gives this Mac a stable public HTTPS address
# (https://<machine>.<tailnet>.ts.net) that forwards to 127.0.0.1:<port>.
# Phones and Macs reach it without installing Tailscale, so it does not take
# the Android VPN slot. TLS terminates on this Mac, and tailscaled keeps the
# Funnel configuration across restarts, so no supervisor process is needed.
#
# One-time account steps (done once by the owner, not by this script):
#   1. Install Tailscale on this Mac and sign in.
#   2. In the Tailscale admin console, enable MagicDNS + HTTPS certificates and
#      allow Funnel for this machine (the CLI prints the exact link if missing).
#
# Usage: server/tunnel/install_tailscale_funnel.sh [--retire-cloudflare]
#   --retire-cloudflare  unload the old Cloudflare quick-tunnel agent. Only do
#                        this after every installed app uses the Funnel URL.
set -euo pipefail

RUNTIME_ROOT="${VOCAB_SYNC_RUNTIME_ROOT:-$HOME/Library/Application Support/VocabReminderServer}"
SERVER_ENV="$RUNTIME_ROOT/.env"
ENV_FILE="${VOCAB_SYNC_TUNNEL_ENV_FILE:-$RUNTIME_ROOT/tunnel.env}"
LEGACY_PLIST="$HOME/Library/LaunchAgents/com.vocabreminder.tunnel.plist"
RETIRE_CLOUDFLARE=0
for arg in "$@"; do
  case "$arg" in
    --retire-cloudflare) RETIRE_CLOUDFLARE=1 ;;
    *) echo "Unknown option: $arg" >&2; exit 64 ;;
  esac
done

die() {
  echo "$1" >&2
  exit "${2:-1}"
}

env_value() {
  [[ -f "$1" ]] || return 0
  sed -n "s/^$2=//p" "$1" | tail -n 1
}

# Replace or append KEY=VALUE in an env file without touching other lines.
set_env_value() {
  local file="$1" key="$2" value="$3" tmp
  tmp="$(mktemp "${file}.XXXXXX")"
  if [[ -f "$file" ]]; then
    grep -v "^$key=" "$file" > "$tmp" || true
  fi
  printf '%s=%s\n' "$key" "$value" >> "$tmp"
  chmod 600 "$tmp"
  mv "$tmp" "$file"
}

find_tailscale() {
  # The macOS app's own binary works as the CLI when called by its full path.
  if [[ -n "${TAILSCALE_BIN:-}" && -x "$TAILSCALE_BIN" ]]; then
    printf '%s' "$TAILSCALE_BIN"
  elif [[ -x /Applications/Tailscale.app/Contents/MacOS/Tailscale ]]; then
    printf '%s' /Applications/Tailscale.app/Contents/MacOS/Tailscale
  elif command -v tailscale >/dev/null 2>&1; then
    command -v tailscale
  fi
}

[[ -f "$SERVER_ENV" ]] || die "Runtime server environment is missing at $SERVER_ENV. Run server/install_launchd.sh first." 78
PORT="$(env_value "$SERVER_ENV" VOCAB_SYNC_PORT)"
PORT="${PORT:-8787}"
TOKEN="$(env_value "$SERVER_ENV" VOCAB_SYNC_TOKEN)"
[[ -n "$TOKEN" ]] || die "VOCAB_SYNC_TOKEN is missing from $SERVER_ENV." 78

TAILSCALE="$(find_tailscale)"
if [[ -z "$TAILSCALE" ]]; then
  cat >&2 <<EOF
Tailscale is not installed on this Mac.

Install it (either works), sign in once, then rerun this script:
  brew install --cask tailscale        # menu bar app + CLI
  open -a Tailscale                    # sign in from the menu bar
EOF
  exit 78
fi

status_json="$("$TAILSCALE" status --json 2>/dev/null || true)"
# plutil reads JSON and ships with every macOS.
read_status() {
  printf '%s' "$status_json" | plutil -extract "$1" raw -o - - 2>/dev/null || true
}
backend_state="$(read_status BackendState)"
dns_name="$(read_status Self.DNSName)"
dns_name="${dns_name%.}"
if [[ "$backend_state" != "Running" ]]; then
  die "Tailscale is not connected (state: ${backend_state:-unknown}). Sign in from the Tailscale menu bar app, then rerun." 78
fi
if [[ "$dns_name" != *.ts.net ]]; then
  die "This machine has no MagicDNS name yet (got '${dns_name:-none}'). Enable MagicDNS in the Tailscale admin console, then rerun." 78
fi
PUBLIC_URL="https://$dns_name"

echo "Publishing http://127.0.0.1:$PORT at $PUBLIC_URL through Tailscale Funnel..."
echo "If Funnel is not yet allowed for this machine, Tailscale prints a login.tailscale.com"
echo "link below and waits; open it, approve, and this continues by itself."
funnel_log="$(mktemp)"
set +e
"$TAILSCALE" funnel --bg --yes "$PORT" 2>&1 | tee "$funnel_log"
funnel_status=${PIPESTATUS[0]}
set -e
if [[ "$funnel_status" -ne 0 ]]; then
  rm -f "$funnel_log"
  cat >&2 <<EOF

Funnel could not be turned on. If a login.tailscale.com link was shown above,
open it to allow Funnel (and HTTPS certificates) for this machine, then rerun.
EOF
  exit 78
fi
rm -f "$funnel_log"

umask 077
mkdir -p "$RUNTIME_ROOT"
touch "$ENV_FILE"
set_env_value "$ENV_FILE" TUNNEL_PROVIDER tailscale_funnel
set_env_value "$ENV_FILE" VOCAB_SYNC_PUBLIC_URL "$PUBLIC_URL"
echo "Recorded TUNNEL_PROVIDER=tailscale_funnel and VOCAB_SYNC_PUBLIC_URL=$PUBLIC_URL in $ENV_FILE."

# A fresh Funnel certificate and public DNS record can take a minute to appear.
echo "Checking $PUBLIC_URL/health with the server token..."
status=""
for _ in $(seq 1 30); do
  status="$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 \
    -H "Authorization: Bearer $TOKEN" "$PUBLIC_URL/health" || true)"
  [[ "$status" == "200" ]] && break
  sleep 4
done
if [[ "$status" != "200" ]]; then
  die "The Funnel URL did not answer /health with 200 (last status: ${status:-none}). Check 'tailscale funnel status' and that the server is running." 1
fi
wrong="$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 \
  -H "Authorization: Bearer funnel-check-wrong-token" "$PUBLIC_URL/health" || true)"
[[ "$wrong" == "401" ]] || die "The Funnel URL accepted a wrong token (status $wrong); refusing to continue." 1
echo "OK: $PUBLIC_URL/health returns 200 with the token and 401 without it."

if [[ "$RETIRE_CLOUDFLARE" == "1" ]]; then
  if [[ -f "$LEGACY_PLIST" ]]; then
    launchctl bootout "gui/$(id -u)" "$LEGACY_PLIST" 2>/dev/null || true
    rm -f "$LEGACY_PLIST"
    echo "Retired the Cloudflare quick-tunnel agent."
  else
    echo "No Cloudflare quick-tunnel agent to retire."
  fi
elif [[ -f "$LEGACY_PLIST" ]]; then
  cat <<EOF

The old Cloudflare quick tunnel is still running, so apps built with the
workers.dev address keep syncing. Once the phone and Mac apps are rebuilt with
$PUBLIC_URL and updated, retire it with:

  server/tunnel/install_tailscale_funnel.sh --retire-cloudflare
EOF
fi

cat <<EOF

Build the apps against the Funnel address:

  VOCAB_SYNC_DEFAULT_URL=$PUBLIC_URL npm run sync:release
EOF
