# Cloudflare workers.dev Sync Front Door

This is the no-purchased-domain Cloudflare path.

```text
Android/MacBook -> https://<worker>.<subdomain>.workers.dev
               -> Worker KV current upstream
               -> https://<auto>.trycloudflare.com
               -> Mac mini cloudflared quick tunnel
               -> http://127.0.0.1:8787
```

Cloudflare still provides the public HTTPS edge. The stable app URL is the free
`workers.dev` URL, not a bought domain. No VPN, router setting, DDNS, or domain
purchase is part of this path.

## One-command deploy

From the repo root:

```sh
npm run sync:setup-cloudflare-worker
```

If Wrangler opens a browser, approve Cloudflare access. The script creates or
reuses the KV namespace, deploys the Worker, sets the admin secret, and writes
`server/tunnel/.env` for the Mac mini.

If Wrangler reports that a non-interactive environment needs
`CLOUDFLARE_API_TOKEN`, create a Cloudflare user API token with:

```text
Account > Account Settings > Read
Account > Workers Scripts > Edit
Account > Workers KV Storage > Edit
User > User Details > Read
User > Memberships > Read
```

Then rerun:

```sh
CLOUDFLARE_API_TOKEN=<paste-token-here> npm run sync:setup-cloudflare-worker
```

## Manual deploy

```sh
cd server/tunnel/cloudflare_worker
cp wrangler.toml.example wrangler.toml
npx wrangler login
npx wrangler kv namespace create SYNC_UPSTREAM
```

Paste the returned namespace `id` into `wrangler.toml`.

Create the Worker admin secret:

```sh
ADMIN_TOKEN="$(openssl rand -hex 32)"
printf '%s' "$ADMIN_TOKEN" | npx wrangler secret put ADMIN_TOKEN
npx wrangler deploy
```

The deploy output gives a URL like:

```text
https://vocab-sync.<your-workers-subdomain>.workers.dev
```

Put that URL and the same `ADMIN_TOKEN` in `server/tunnel/.env` on the Mac mini:

```sh
TUNNEL_PROVIDER=cloudflare_quick_worker
CF_WORKER_PUBLIC_URL=https://vocab-sync.<your-workers-subdomain>.workers.dev
CF_WORKER_ADMIN_TOKEN=<ADMIN_TOKEN>
```

Then run:

```sh
VOCAB_SYNC_DEFAULT_URL=https://vocab-sync.<your-workers-subdomain>.workers.dev npm run sync:setup-mac-mini
```

After launchd starts the tunnel agent, `server/logs/tunnel.out.log` should show
`Published Cloudflare quick tunnel upstream: https://...trycloudflare.com`.
