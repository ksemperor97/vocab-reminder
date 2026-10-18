#!/usr/bin/env bash
# Exercises server/tunnel/install_tailscale_funnel.sh against stand-in
# `tailscale` and `curl` commands: env file handling, refusal paths, the
# wrong-token check and Cloudflare retirement. The real Funnel run is
# documented in docs/sync_tunnel_runbook.md.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
INSTALLER="$ROOT_DIR/server/tunnel/install_tailscale_funnel.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

pass() { printf 'PASS: %s\n' "$1"; }
fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }

mkdir -p "$WORK/bin" "$WORK/runtime" "$WORK/home/Library/LaunchAgents"
printf 'VOCAB_SYNC_TOKEN=test-token-0123456789abcdef\nVOCAB_SYNC_PORT=8797\n' > "$WORK/runtime/.env"
printf 'TUNNEL_PROVIDER=cloudflare_quick_worker\nCF_WORKER_PUBLIC_URL=https://vocab-sync.example.workers.dev\nCF_WORKER_ADMIN_TOKEN=keep-me\n' > "$WORK/runtime/tunnel.env"

cat > "$WORK/bin/tailscale" <<'STUB'
#!/usr/bin/env bash
case "$1" in
  status) printf '{"BackendState":"%s","Self":{"DNSName":"mac-mini.tail1234.ts.net."}}' "${STUB_STATE:-Running}" ;;
  funnel) [[ "${STUB_FUNNEL_FAIL:-0}" == "1" ]] && { echo "Funnel is not enabled. https://login.tailscale.com/f/funnel?node=x" >&2; exit 1; }
          echo "Available on the internet: https://mac-mini.tail1234.ts.net/ |-- / proxy http://127.0.0.1:$4" ;;
esac
STUB
cat > "$WORK/bin/curl" <<'STUB'
#!/usr/bin/env bash
# Answers like the sync server: 200 with the right token, 401 otherwise.
for arg in "$@"; do
  case "$arg" in
    "Authorization: Bearer test-token-0123456789abcdef") printf '200'; exit 0 ;;
    "Authorization: Bearer "*) printf '401'; exit 0 ;;
  esac
done
printf '000'
STUB
cat > "$WORK/bin/launchctl" <<'STUB'
#!/usr/bin/env bash
exit 0
STUB
chmod +x "$WORK/bin/"*

run() {
  HOME="$WORK/home" PATH="$WORK/bin:$PATH" TAILSCALE_BIN="$WORK/bin/tailscale" \
    VOCAB_SYNC_RUNTIME_ROOT="$WORK/runtime" "$@"
}

# Disconnected Tailscale is refused with exit 78 and leaves the env untouched.
if STUB_STATE=NeedsLogin run "$INSTALLER" >/dev/null 2>&1; then fail "installer accepted a disconnected Tailscale"; fi
grep -q '^TUNNEL_PROVIDER=cloudflare_quick_worker$' "$WORK/runtime/tunnel.env" || fail "env changed on refusal"
pass "refuses when Tailscale is not connected"

# Funnel not allowed in the tailnet: exit 78 with the CLI's link passed through.
if output="$(STUB_FUNNEL_FAIL=1 run "$INSTALLER" 2>&1)"; then fail "installer ignored a Funnel failure"; fi
[[ "$output" == *"login.tailscale.com/f/funnel"* ]] || fail "Funnel enable link was not shown"
pass "shows the enable-Funnel link when Funnel is not allowed"

# Success: provider and URL recorded, Cloudflare keys kept for the transition.
touch "$WORK/home/Library/LaunchAgents/com.vocabreminder.tunnel.plist"
output="$(run "$INSTALLER" 2>&1)"
grep -q '^TUNNEL_PROVIDER=tailscale_funnel$' "$WORK/runtime/tunnel.env" || fail "provider not recorded"
grep -q '^VOCAB_SYNC_PUBLIC_URL=https://mac-mini.tail1234.ts.net$' "$WORK/runtime/tunnel.env" || fail "public URL not recorded"
grep -q '^CF_WORKER_ADMIN_TOKEN=keep-me$' "$WORK/runtime/tunnel.env" || fail "Cloudflare keys were dropped"
[[ "$(grep -c '^TUNNEL_PROVIDER=' "$WORK/runtime/tunnel.env")" == "1" ]] || fail "provider line duplicated"
[[ "$(stat -f '%Lp' "$WORK/runtime/tunnel.env")" == "600" ]] || fail "tunnel env is not mode 600"
[[ "$output" == *"--retire-cloudflare"* ]] || fail "did not explain how to retire Cloudflare"
[[ -f "$WORK/home/Library/LaunchAgents/com.vocabreminder.tunnel.plist" ]] || fail "retired Cloudflare without being asked"
pass "records the Funnel URL and keeps Cloudflare running for installed apps"

# Explicit retirement removes the old tunnel agent.
run "$INSTALLER" --retire-cloudflare >/dev/null 2>&1
[[ ! -f "$WORK/home/Library/LaunchAgents/com.vocabreminder.tunnel.plist" ]] || fail "Cloudflare agent not retired"
pass "retires the Cloudflare agent only with --retire-cloudflare"

# Release tooling picks the Funnel URL over the Worker URL.
url="$(sed -n 's/^VOCAB_SYNC_PUBLIC_URL=//p' "$WORK/runtime/tunnel.env" | tail -n 1)"
[[ "$url" == "https://mac-mini.tail1234.ts.net" ]] || fail "release URL lookup"
grep -q "VOCAB_SYNC_PUBLIC_URL" "$ROOT_DIR/tools/build_sync_release.sh" || fail "build_sync_release.sh ignores the Funnel URL"
pass "release builds read the Funnel URL first"
