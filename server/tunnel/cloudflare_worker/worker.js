const UPSTREAM_KEY = "current_upstream";
const UNAVAILABLE_STATUSES = new Set([502, 503, 504, 520, 521, 522, 523, 524, 525, 526, 530]);

function requestId(request) {
  return (
    request.headers.get("cf-ray") ||
    request.headers.get("x-request-id") ||
    globalThis.crypto?.randomUUID?.() ||
    `req-${Date.now().toString(36)}`
  );
}

function json(payload, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      ...extraHeaders,
    },
  });
}

function errorResponse(request, message, status, code) {
  const id = requestId(request);
  return json(
    { error: { code, message, request_id: id } },
    status,
    { "x-request-id": id },
  );
}

function unavailable(request, code = "upstream_unavailable") {
  return errorResponse(request, "Sync upstream is temporarily unavailable.", 503, code);
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

function normalizeGeneration(value) {
  if (value === undefined || value === null || value === "") {
    return Date.now();
  }
  if (typeof value === "number") {
    if (!Number.isSafeInteger(value) || value < 0) {
      throw new Error("generation must be a non-negative safe integer.");
    }
    return value;
  }
  const normalized = String(value).trim();
  if (!normalized || normalized.length > 128) {
    throw new Error("generation must be a non-empty value up to 128 characters.");
  }
  return normalized;
}

function normalizeTimestamp(value, name, { nullable = false } = {}) {
  if ((value === null || value === undefined || value === "") && nullable) {
    return null;
  }
  const date = new Date(String(value));
  if (Number.isNaN(date.getTime())) {
    throw new Error(`${name} must be an ISO-8601 timestamp.`);
  }
  return date.toISOString();
}

async function readUpstreamRecord(env) {
  const stored = await env.SYNC_UPSTREAM.get(UPSTREAM_KEY);
  if (!stored) {
    return null;
  }
  try {
    const parsed = JSON.parse(stored);
    if (parsed && typeof parsed === "object" && parsed.url) {
      return {
        url: normalizeQuickTunnelUrl(parsed.url),
        generation: parsed.generation ?? 0,
        updated_at: parsed.updated_at || null,
        last_success_at: parsed.last_success_at || null,
      };
    }
  } catch {
    // Backward compatibility: older deployments stored the raw URL string.
  }
  return {
    url: normalizeQuickTunnelUrl(stored),
    generation: 0,
    updated_at: null,
    last_success_at: null,
  };
}

function isAdminAuthorized(request, env) {
  const expected = env.ADMIN_TOKEN || "";
  const provided = request.headers.get("x-vocab-sync-admin") || "";
  return Boolean(expected) && provided === expected;
}

async function upstreamAdmin(request, env) {
  if (!isAdminAuthorized(request, env)) {
    return errorResponse(request, "Unauthorized.", 401, "unauthorized");
  }
  if (request.method === "GET") {
    const record = await readUpstreamRecord(env);
    return json({ ok: true, upstream: record });
  }
  if (request.method !== "POST") {
    return errorResponse(request, "Use GET or POST.", 405, "method_not_allowed");
  }

  let body;
  try {
    body = await request.json();
  } catch {
    return errorResponse(request, "Request body must be JSON.", 400, "invalid_json");
  }

  let url;
  let generation;
  let updatedAt;
  let lastSuccessAt;
  try {
    url = normalizeQuickTunnelUrl(body.url);
    generation = normalizeGeneration(body.generation);
    updatedAt = body.updated_at
      ? normalizeTimestamp(body.updated_at, "updated_at")
      : new Date().toISOString();
    lastSuccessAt = normalizeTimestamp(body.last_success_at, "last_success_at", { nullable: true });
  } catch (error) {
    return errorResponse(request, error.message, 400, "invalid_upstream_metadata");
  }

  let current = null;
  try {
    current = await readUpstreamRecord(env);
  } catch {
    // An invalid legacy value should not block an authenticated repair update.
  }
  if (
    !lastSuccessAt &&
    current?.url === url &&
    String(current?.generation) === String(generation)
  ) {
    lastSuccessAt = current.last_success_at;
  }

  const record = {
    url,
    generation,
    updated_at: updatedAt,
    last_success_at: lastSuccessAt,
  };
  await env.SYNC_UPSTREAM.put(UPSTREAM_KEY, JSON.stringify(record));
  return json({ ok: true, upstream: record });
}

async function proxyToUpstream(request, env, fetchImpl) {
  let record;
  try {
    record = await readUpstreamRecord(env);
  } catch {
    return unavailable(request, "upstream_configuration_invalid");
  }
  if (!record) {
    return unavailable(request, "upstream_not_ready");
  }

  const incomingUrl = new URL(request.url);
  const targetUrl = new URL(incomingUrl.pathname + incomingUrl.search, record.url);
  const headers = new Headers(request.headers);
  headers.delete("host");

  const init = {
    method: request.method,
    headers,
    redirect: "manual",
  };
  if (!["GET", "HEAD"].includes(request.method.toUpperCase())) {
    init.body = request.body;
    // Required by Node's Fetch for streamed request bodies.
    init.duplex = "half";
  }

  try {
    const response = await fetchImpl(new Request(targetUrl.toString(), init));
    if (UNAVAILABLE_STATUSES.has(response.status)) {
      return unavailable(request);
    }
    return response;
  } catch {
    return unavailable(request);
  }
}

export function createWorker({ fetchImpl = (...args) => fetch(...args) } = {}) {
  return {
    async fetch(request, env) {
      const url = new URL(request.url);
      if (url.pathname === "/__admin/upstream") {
        return upstreamAdmin(request, env);
      }
      return proxyToUpstream(request, env, fetchImpl);
    },
  };
}

export { normalizeQuickTunnelUrl, readUpstreamRecord };

export default createWorker();
