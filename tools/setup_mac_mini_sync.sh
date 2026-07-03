#!/usr/bin/env bash
set -u -o pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SERVER_ENV="$ROOT_DIR/server/.env"
TUNNEL_ENV="$ROOT_DIR/server/tunnel/.env"

section() {
  printf '\n== %s ==\n' "$1"
}

die() {
  echo "$1" >&2
  exit "${2:-1}"
}

write_tunnel_env_from_process_env() {
  [[ ! -f "$TUNNEL_ENV" ]] || return 0
  [[ -n "${CF_WORKER_PUBLIC_URL:-}" || -n "${CF_WORKER_ADMIN_TOKEN:-}" || -n "${CLOUDFLARED_TUNNEL_TOKEN:-}" || -n "${CLOUDFLARED_CONFIG:-}" ]] || return 0

  umask 077
  {
    if [[ -n "${CF_WORKER_PUBLIC_URL:-}" || -n "${CF_WORKER_ADMIN_TOKEN:-}" ]]; then
      printf 'TUNNEL_PROVIDER=cloudflare_quick_worker\n'
      printf 'CF_WORKER_PUBLIC_URL=%s\n' "${CF_WORKER_PUBLIC_URL:-}"
      printf 'CF_WORKER_ADMIN_TOKEN=%s\n' "${CF_WORKER_ADMIN_TOKEN:-}"
    elif [[ -n "${CLOUDFLARED_TUNNEL_TOKEN:-}" ]]; then
      printf 'TUNNEL_PROVIDER=cloudflare_named\n'
      printf 'CLOUDFLARED_TUNNEL_TOKEN=%s\n' "$CLOUDFLARED_TUNNEL_TOKEN"
    fi
    if [[ -n "${CLOUDFLARED_CONFIG:-}" ]]; then
      printf 'TUNNEL_PROVIDER=cloudflare_named\n'
      printf 'CLOUDFLARED_CONFIG=%s\n' "$CLOUDFLARED_CONFIG"
    fi
  } > "$TUNNEL_ENV"
  echo "Created server/tunnel/.env from tunnel environment variables."
}

if ! command -v launchctl >/dev/null 2>&1; then
  die "This setup script is for macOS launchd on the always-on Mac mini."
fi

section "Install vocab-sync server"
"$ROOT_DIR/server/install_launchd.sh" || exit $?

if [[ ! -f "$SERVER_ENV" ]]; then
  die "server/.env was not created by server/install_launchd.sh." 78
fi

section "Install outbound tunnel"
write_tunnel_env_from_process_env

"$ROOT_DIR/server/tunnel/install_tunnel.sh"
tunnel_status=$?
if [[ "$tunnel_status" -eq 78 ]]; then
  cat <<EOF

Tunnel setup paused.

Cloudflare free path, no purchased domain.

Edit server/tunnel/.env with:

  TUNNEL_PROVIDER=cloudflare_quick_worker
  CF_WORKER_PUBLIC_URL=https://vocab-sync.<your-workers-subdomain>.workers.dev
  CF_WORKER_ADMIN_TOKEN=<worker admin token>

To create that Worker URL/admin token, follow:

  server/tunnel/cloudflare_worker/README.md

Then rerun:

  npm run sync:setup-mac-mini
EOF
  exit 78
elif [[ "$tunnel_status" -ne 0 ]]; then
  exit "$tunnel_status"
fi

section "Readiness check"

if [[ -z "${VOCAB_SYNC_DEFAULT_URL:-}" ]]; then
  cat <<EOF
Set VOCAB_SYNC_DEFAULT_URL to the stable HTTPS tunnel URL, then run:

  VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.<your-workers-subdomain>.workers.dev npm run sync:doctor
  VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.<your-workers-subdomain>.workers.dev npm run sync:preflight
EOF
  exit 78
fi

VOCAB_SYNC_DEFAULT_URL="$VOCAB_SYNC_DEFAULT_URL" "$ROOT_DIR/tools/sync_doctor.sh"
