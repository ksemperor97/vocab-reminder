#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORKER_DIR="$ROOT_DIR/server/tunnel/cloudflare_worker"
WRANGLER_TOML="$WORKER_DIR/wrangler.toml"
TUNNEL_ENV="$ROOT_DIR/server/tunnel/.env"
WORKER_NAME="${CF_WORKER_NAME:-vocab-sync}"
KV_BINDING="SYNC_UPSTREAM"

section() {
  printf '\n== %s ==\n' "$1"
}

die() {
  echo "$1" >&2
  exit "${2:-1}"
}

auth_die() {
  cat >&2 <<'EOF'
Cloudflare Wrangler is running in a non-interactive environment and needs CLOUDFLARE_API_TOKEN.

Create one Cloudflare user API token with permissions:

  Account > Account Settings > Read
  Account > Workers Scripts > Edit
  Account > Workers KV Storage > Edit
  User > User Details > Read
  User > Memberships > Read

Then rerun:

  CLOUDFLARE_API_TOKEN=<paste-token-here> npm run sync:setup-cloudflare-worker

This token is only for creating/deploying the Worker/KV. Android users will not see or enter it.
EOF
  exit 78
}

workers_dev_die() {
  cat >&2 <<'EOF'
Cloudflare Worker deploy needs a workers.dev account subdomain.

Open Cloudflare Workers & Pages and set the account workers.dev subdomain,
then rerun:

  CLOUDFLARE_API_TOKEN=<paste-token-here> npm run sync:setup-cloudflare-worker
EOF
  exit 79
}

is_auth_error() {
  grep -qiE 'CLOUDFLARE_API_TOKEN|necessary to set'
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "$1 is required."
}

extract_kv_id() {
  [[ -f "$WRANGLER_TOML" ]] || return 0
  awk -F= '/^[[:space:]]*id[[:space:]]*=/ {
    value=$0
    sub(/^[^=]*=/, "", value)
    gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
    gsub(/^"|"$/, "", value)
    if (value != "replace-with-kv-namespace-id") print value
    exit
  }' "$WRANGLER_TOML"
}

write_wrangler_toml() {
  local kv_id="$1"
  cat > "$WRANGLER_TOML" <<EOF
name = "$WORKER_NAME"
main = "worker.js"
compatibility_date = "2026-07-03"
workers_dev = true

[[kv_namespaces]]
binding = "$KV_BINDING"
id = "$kv_id"
EOF
}

ensure_login() {
  if [[ -n "${CLOUDFLARE_API_TOKEN:-}" ]]; then
    return 0
  fi
  if npx --yes wrangler whoami >/dev/null 2>&1; then
    return 0
  fi
  echo "Cloudflare login is required. A browser window may open; approve Wrangler access there."
  npx --yes wrangler login
}

create_kv_namespace() {
  local existing_id
  existing_id="$(extract_kv_id)"
  if [[ -n "$existing_id" ]]; then
    echo "$existing_id"
    return 0
  fi

  local output kv_id
  if ! output="$(npx --yes wrangler kv namespace create "$KV_BINDING" 2>&1)"; then
    printf '%s\n' "$output" >&2
    if printf '%s\n' "$output" | is_auth_error; then
      auth_die
    fi
    exit 1
  fi
  printf '%s\n' "$output" >&2
  kv_id="$(printf '%s\n' "$output" | grep -Eo 'id[[:space:]]*=[[:space:]]*"[^"]+"' | head -n 1 | sed -E 's/.*"([^"]+)".*/\1/')"
  if [[ -z "$kv_id" ]]; then
    kv_id="$(printf '%s\n' "$output" | grep -Eo '[0-9a-f]{32}' | head -n 1 || true)"
  fi
  [[ -n "$kv_id" ]] || die "Could not read KV namespace id from Wrangler output."
  echo "$kv_id"
}

put_admin_secret() {
  local token="$1"
  local output
  if ! output="$(printf '%s' "$token" | npx --yes wrangler secret put ADMIN_TOKEN 2>&1)"; then
    printf '%s\n' "$output" >&2
    if printf '%s\n' "$output" | is_auth_error; then
      auth_die
    fi
    exit 1
  fi
  printf '%s\n' "$output" >&2
}

deploy_worker() {
  local output url
  if ! output="$(npx --yes wrangler deploy 2>&1)"; then
    printf '%s\n' "$output" >&2
    if printf '%s\n' "$output" | grep -qi 'register a workers.dev subdomain'; then
      workers_dev_die
    fi
    if printf '%s\n' "$output" | is_auth_error; then
      auth_die
    fi
    exit 1
  fi
  printf '%s\n' "$output" >&2
  url="$(printf '%s\n' "$output" | grep -Eo 'https://[^[:space:]]+\.workers\.dev' | head -n 1 || true)"
  [[ -n "$url" ]] || die "Could not read workers.dev URL from Wrangler deploy output."
  echo "$url"
}

write_tunnel_env() {
  local worker_url="$1"
  local admin_token="$2"
  umask 077
  cat > "$TUNNEL_ENV" <<EOF
TUNNEL_PROVIDER=cloudflare_quick_worker
CF_WORKER_PUBLIC_URL=$worker_url
CF_WORKER_ADMIN_TOKEN=$admin_token
EOF
  chmod 600 "$TUNNEL_ENV"
}

require_command node
require_command npx
require_command openssl

cd "$WORKER_DIR"

section "Cloudflare login"
ensure_login

section "Create or reuse Worker KV"
kv_id="$(create_kv_namespace)"
write_wrangler_toml "$kv_id"
echo "Using KV namespace id: $kv_id"

section "Set Worker admin secret"
admin_token="${CF_WORKER_ADMIN_TOKEN:-$(openssl rand -hex 32)}"
put_admin_secret "$admin_token" >/dev/null
echo "ADMIN_TOKEN secret set in Cloudflare Worker."

section "Deploy Worker"
worker_url="$(deploy_worker)"
echo "Worker URL: $worker_url"

section "Write Mac mini tunnel env"
write_tunnel_env "$worker_url" "$admin_token"
echo "Wrote server/tunnel/.env for cloudflare_quick_worker."

cat <<EOF

Next on the Mac mini:

  VOCAB_SYNC_DEFAULT_URL=$worker_url npm run sync:setup-mac-mini
  VOCAB_SYNC_DEFAULT_URL=$worker_url npm run sync:doctor

Use this URL for release builds:

  VOCAB_SYNC_DEFAULT_URL=$worker_url npm run sync:preflight
EOF
