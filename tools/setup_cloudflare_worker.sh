#!/usr/bin/env bash
set -euo pipefail
set +x
umask 077

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORKER_DIR="$ROOT_DIR/server/tunnel/cloudflare_worker"
WRANGLER_TOML="$WORKER_DIR/wrangler.toml"
RUNTIME_ROOT="${VOCAB_SYNC_RUNTIME_ROOT:-$HOME/Library/Application Support/VocabReminderServer}"
if [[ "${VOCAB_RUNTIME_LOCK_WRAPPED:-0}" != "1" ]]; then
  exec node "$ROOT_DIR/tools/with_runtime_lock.mjs" \
    --runtime-root "$RUNTIME_ROOT" \
    --label "Cloudflare Worker setup" \
    -- bash "$0" "$@"
fi
TUNNEL_ENV="${VOCAB_SYNC_TUNNEL_ENV_FILE:-$RUNTIME_ROOT/tunnel.env}"
WORKER_NAME="${CF_WORKER_NAME:-vocab-sync}"
KV_BINDING="SYNC_UPSTREAM"
WRANGLER_BIN="${WRANGLER_BIN:-$ROOT_DIR/node_modules/.bin/wrangler}"
SETUP_STATE_DIR="$RUNTIME_ROOT/state/cloudflare-worker-setup"
STAGED_ADMIN_TOKEN_FILE="$SETUP_STATE_DIR/admin-token.staged"

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

run_wrangler() {
  [[ -x "$WRANGLER_BIN" ]] || die "Pinned Wrangler is missing: $WRANGLER_BIN. Run npm ci in $ROOT_DIR."
  env \
    -u CF_WORKER_ADMIN_TOKEN \
    -u VOCAB_SYNC_TOKEN \
    -u VOCAB_SYNC_DEFAULT_TOKEN \
    "$WRANGLER_BIN" "$@"
}

env_value() {
  local file="$1"
  local key="$2"
  [[ -f "$file" ]] || return 0
  awk -F= -v key="$key" '
    $1 == key {
      value=$0
      sub(/^[^=]*=/, "", value)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
      print value
      exit
    }
  ' "$file"
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
  if run_wrangler whoami >/dev/null 2>&1; then
    return 0
  fi
  echo "Cloudflare login is required. A browser window may open; approve Wrangler access there."
  run_wrangler login
}

create_kv_namespace() {
  local existing_id
  existing_id="$(extract_kv_id)"
  if [[ -n "$existing_id" ]]; then
    echo "$existing_id"
    return 0
  fi

  local output kv_id
  if ! output="$(run_wrangler kv namespace create "$KV_BINDING" 2>&1)"; then
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
  if ! output="$(printf '%s' "$token" | run_wrangler secret put ADMIN_TOKEN 2>&1)"; then
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
  if ! output="$(run_wrangler deploy 2>&1)"; then
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
  VOCAB_TUNNEL_ENV="$TUNNEL_ENV" \
  VOCAB_WORKER_URL="$worker_url" \
  VOCAB_WORKER_ADMIN_TOKEN="$admin_token" \
  VOCAB_SECURITY_HELPERS="$ROOT_DIR/tools/security_helpers.mjs" \
    node --input-type=module <<'NODE'
import fs from "node:fs";
import { pathToFileURL } from "node:url";
const { replaceEnvValue, writeFileAtomic } = await import(pathToFileURL(process.env.VOCAB_SECURITY_HELPERS).href);
const file = process.env.VOCAB_TUNNEL_ENV;
let text = fs.existsSync(file)
  ? fs.readFileSync(file, "utf8")
  : "TUNNEL_PROVIDER=cloudflare_quick_worker\n";
text = replaceEnvValue(text, "TUNNEL_PROVIDER", "cloudflare_quick_worker");
text = replaceEnvValue(text, "CF_WORKER_PUBLIC_URL", process.env.VOCAB_WORKER_URL);
text = replaceEnvValue(text, "CF_WORKER_ADMIN_TOKEN", process.env.VOCAB_WORKER_ADMIN_TOKEN);
writeFileAtomic(file, text, 0o600);
NODE
}

stage_initial_admin_token() {
  local token="$1"
  mkdir -p "$SETUP_STATE_DIR"
  chmod 700 "$SETUP_STATE_DIR"
  local temporary="$STAGED_ADMIN_TOKEN_FILE.tmp.$$"
  printf '%s\n' "$token" > "$temporary"
  chmod 600 "$temporary"
  mv -f "$temporary" "$STAGED_ADMIN_TOKEN_FILE"
}

require_command node
require_command openssl

cd "$WORKER_DIR"

section "Cloudflare login"
ensure_login

section "Create or reuse Worker KV"
kv_id="$(create_kv_namespace)"
write_wrangler_toml "$kv_id"
echo "Using KV namespace id: $kv_id"

section "Set Worker admin secret"
existing_admin_token="$(env_value "$TUNNEL_ENV" CF_WORKER_ADMIN_TOKEN)"
if [[ -n "$existing_admin_token" && -n "${CF_WORKER_ADMIN_TOKEN:-}" && "$CF_WORKER_ADMIN_TOKEN" != "$existing_admin_token" ]]; then
  die "Refusing to rotate an existing Worker admin token in setup. Use tools/rotate_sync_tokens.mjs."
fi
if [[ -n "$existing_admin_token" ]]; then
  admin_token="$existing_admin_token"
elif [[ -f "$STAGED_ADMIN_TOKEN_FILE" ]]; then
  admin_token="$(tr -d '\r\n' < "$STAGED_ADMIN_TOKEN_FILE")"
else
  admin_token="${CF_WORKER_ADMIN_TOKEN:-$(openssl rand -hex 32)}"
  stage_initial_admin_token "$admin_token"
fi
sync_token="$(env_value "$RUNTIME_ROOT/.env" VOCAB_SYNC_TOKEN)"
if [[ ${#admin_token} -lt 32 || "$admin_token" == replace-with-* ]]; then
  die "CF_WORKER_ADMIN_TOKEN must be a real secret of at least 32 characters."
fi
if [[ -n "$sync_token" && "$admin_token" == "$sync_token" ]]; then
  die "CF_WORKER_ADMIN_TOKEN must be different from VOCAB_SYNC_TOKEN."
fi
if [[ -n "${CLOUDFLARE_API_TOKEN:-}" && "$admin_token" == "$CLOUDFLARE_API_TOKEN" ]]; then
  die "CF_WORKER_ADMIN_TOKEN must be different from CLOUDFLARE_API_TOKEN."
fi
put_admin_secret "$admin_token" >/dev/null
echo "ADMIN_TOKEN secret set in Cloudflare Worker."

section "Deploy Worker"
worker_url="$(deploy_worker)"
echo "Worker URL: $worker_url"

section "Write Mac mini tunnel env"
write_tunnel_env "$worker_url" "$admin_token"
rm -f "$STAGED_ADMIN_TOKEN_FILE"
echo "Wrote isolated runtime tunnel.env for cloudflare_quick_worker."

cat <<EOF

Next on the Mac mini:

  VOCAB_SYNC_DEFAULT_URL=$worker_url npm run sync:setup-mac-mini
  VOCAB_SYNC_DEFAULT_URL=$worker_url npm run sync:doctor

Use this URL for release builds:

  VOCAB_SYNC_DEFAULT_URL=$worker_url npm run sync:preflight
EOF
