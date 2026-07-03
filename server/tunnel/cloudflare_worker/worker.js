const UPSTREAM_KEY = "current_upstream";

function json(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

function normalizeQuickTunnelUrl(value) {
  const url = new URL(String(value || "").trim());
  if (url.protocol !== "https:") {
    throw new Error("Upstream URL must use https.");
  }
  if (!url.hostname.endsWith(".trycloudflare.com")) {
    throw new Error("Upstream URL must be a Cloudflare quick tunnel URL.");
  }
  url.pathname = "";
  url.search = "";
  url.hash = "";
  return url.origin;
}

async function updateUpstream(request, env) {
  if (request.method !== "POST") {
    return json({ error: { message: "Use POST." } }, 405);
  }
  const expected = env.ADMIN_TOKEN || "";
  const provided = request.headers.get("x-vocab-sync-admin") || "";
  if (!expected || provided !== expected) {
    return json({ error: { message: "Unauthorized." } }, 401);
  }

  let body;
  try {
    body = await request.json();
  } catch {
    return json({ error: { message: "Request body must be JSON." } }, 400);
  }

  let upstream;
  try {
    upstream = normalizeQuickTunnelUrl(body.url);
  } catch (error) {
    return json({ error: { message: error.message } }, 400);
  }

  await env.SYNC_UPSTREAM.put(UPSTREAM_KEY, upstream);
  return json({ ok: true, upstream });
}

async function proxyToUpstream(request, env) {
  const upstream = await env.SYNC_UPSTREAM.get(UPSTREAM_KEY);
  if (!upstream) {
    return json({ error: { message: "Sync upstream is not ready." } }, 503);
  }

  const incomingUrl = new URL(request.url);
  const targetUrl = new URL(incomingUrl.pathname + incomingUrl.search, upstream);
  const headers = new Headers(request.headers);
  headers.delete("host");

  const init = {
    method: request.method,
    headers,
    redirect: "manual",
  };
  if (!["GET", "HEAD"].includes(request.method.toUpperCase())) {
    init.body = request.body;
  }

  try {
    return await fetch(new Request(targetUrl.toString(), init));
  } catch {
    return json({ error: { message: "Sync upstream fetch failed." } }, 502);
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/__admin/upstream") {
      return updateUpstream(request, env);
    }
    return proxyToUpstream(request, env);
  },
};
