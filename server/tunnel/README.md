# Vocab Reminder Sync Tunnel

This folder installs the Mac mini outbound tunnel agent for the automatic sync path.

The target route is:

```text
Android/MacBook -> free workers.dev URL
               -> Cloudflare Worker
               -> current Cloudflare quick tunnel URL
               -> http://127.0.0.1:8787
```

Android and MacBook Air clients must not need VPN, router, DDNS, purchased domain,
server URL, or token setup.

## Default: Cloudflare, No Purchased Domain

Use the free `workers.dev` URL as the stable app URL. The Mac mini starts a
Cloudflare quick tunnel, receives an auto-generated `trycloudflare.com` URL,
and publishes that current URL to the Worker.

1. Deploy the Worker in `server/tunnel/cloudflare_worker`.
2. Put the Worker URL and admin token in `server/tunnel/.env`:

```env
TUNNEL_PROVIDER=cloudflare_quick_worker
CF_WORKER_PUBLIC_URL=https://vocab-sync.<your-workers-subdomain>.workers.dev
CF_WORKER_ADMIN_TOKEN=<worker admin token>
```

3. Install the launch agent:

```sh
cd server/tunnel
./install_tunnel.sh
```

## Checks

```sh
launchctl print "gui/$(id -u)/com.vocabreminder.tunnel"
tail -n 100 ../logs/tunnel.err.log
```

With the sync server already running:

```sh
TOKEN="$(sed -n 's/^VOCAB_SYNC_TOKEN=//p' ../.env)"
curl -i -H "Authorization: Bearer $TOKEN" "$CF_WORKER_PUBLIC_URL/health"
curl -i -H "Authorization: Bearer wrong-token" "$CF_WORKER_PUBLIC_URL/health"
```

Expected:

```text
valid token   -> HTTP 200
wrong token   -> HTTP 401
Android setup -> nothing beyond installing/running the app
```
