import { createRecommendationCatalogLoader, maintainRecommendations } from "./recommendations.js";
import { readChanges } from "./changes.js";
import crypto from "node:crypto";
import http from "node:http";
import { ensureDailyBackup } from "./backup.js";
import {
  createDictionaryResolver,
  normalizeDictionaryLemma,
  normalizeDictionaryResult,
  pendingDictionaryResult
} from "./dictionary.js";
import { SyncStore } from "./sync.js";

const defaultMaxBodyBytes = 16 * 1024 * 1024;
const defaultMaxDictionaryBatchItems = 50;
const hardMaxDictionaryBatchItems = 100;
const dictionaryBatchConcurrency = 4;
const defaultDictionaryRequestsPerMinute = 120;
const hardMaxDictionaryRequestsPerMinute = 600;

export function createSyncServer({
  db,
  dbPath,
  backupDir,
  backupRetention,
  token,
  maxBodyBytes = defaultMaxBodyBytes,
  maxCollectionItems,
  dictionaryResolver = createDictionaryResolver(),
  maxDictionaryBatchItems = defaultMaxDictionaryBatchItems,
  dictionaryRequestsPerMinute = defaultDictionaryRequestsPerMinute,
  recommendationCatalog = createRecommendationCatalogLoader(),
  requestLogger = null,
  requestIdFactory = () => crypto.randomUUID(),
  now = () => Date.now()
}) {
  if (!token || token.trim() === "") {
    throw new Error("VOCAB_SYNC_TOKEN is required.");
  }
  const bodyByteLimit = positiveIntegerOrDefault(maxBodyBytes, defaultMaxBodyBytes);
  const dictionaryBatchLimit = Math.min(
    positiveIntegerOrDefault(maxDictionaryBatchItems, defaultMaxDictionaryBatchItems),
    hardMaxDictionaryBatchItems
  );
  const dictionaryRateLimiter = createFixedWindowRateLimiter(
    Math.min(
      positiveIntegerOrDefault(dictionaryRequestsPerMinute, defaultDictionaryRequestsPerMinute),
      hardMaxDictionaryRequestsPerMinute
    ),
    now
  );
  db.prepare("UPDATE sync_metadata SET value = ? WHERE key = 'epoch'").run(crypto.randomUUID());
  const store = new SyncStore(db, { maxCollectionItems });

  const server = http.createServer(async (request, response) => {
    const startedAt = Date.now();
    const requestId = resolveRequestId(request.headers["x-request-id"], requestIdFactory);
    response.setHeader("X-Request-Id", requestId);
    try {
      await route({
        request,
        response,
        store,
        db,
        dbPath,
        backupDir,
        backupRetention,
        token,
        maxBodyBytes: bodyByteLimit,
        dictionaryResolver,
        maxDictionaryBatchItems: dictionaryBatchLimit,
        dictionaryRateLimiter,
        requestId, recommendationCatalog, now
      });
    } catch (error) {
      sendError(response, error, requestId);
    } finally {
      logRequest(requestLogger, {
        requestId,
        method: request.method ?? "UNKNOWN",
        route: safeRouteLabel(request.url),
        status: response.statusCode,
        durationMs: Date.now() - startedAt
      });
    }
  });

  // Reads never write. Recommendations are topped up after each upload and by
  // this hook, which the process runs on a timer so a new day's budget appears
  // even when no device has uploaded yet.
  function maintain() {
    if (!recommendationCatalog) return { generated: 0, retired: 0 };
    return maintainRecommendations(store, recommendationCatalog(), new Date(now()));
  }

  return { server, store, maintain };
}

async function route(context) {
  const { request, response, store } = context;
  const url = new URL(request.url ?? "/", "http://localhost");

  if (url.pathname === "/health" && request.method === "GET") {
    requireBearer(request, context.token);
    sendJson(response, 200, store.health());
    return;
  }

  if (url.pathname.startsWith("/dict/") && request.method === "GET") {
    requireBearer(request, context.token);
    context.dictionaryRateLimiter.consume();
    const lemma = dictionaryLemmaFromPath(url.pathname);
    sendJson(response, 200, await lookupDictionary(context.dictionaryResolver, lemma));
    return;
  }

  if (url.pathname === "/dict/batch" && request.method === "POST") {
    requireBearer(request, context.token);
    context.dictionaryRateLimiter.consume();
    const payload = await readJsonBody(request, context.maxBodyBytes);
    const lemmas = dictionaryBatchLemmas(payload, context.maxDictionaryBatchItems);
    const uniqueLemmas = [...new Set(lemmas)];
    const uniqueResults = await mapWithConcurrency(
      uniqueLemmas,
      dictionaryBatchConcurrency,
      (lemma) => lookupDictionary(context.dictionaryResolver, lemma)
    );
    const resultByLemma = new Map(uniqueResults.map((result) => [result.lemma, result]));
    sendJson(response, 200, {
      results: lemmas.map((lemma) => ({ ...resultByLemma.get(lemma) }))
    });
    return;
  }

  if (url.pathname === "/sync/batch" && request.method === "POST") {
    requireBearer(request, context.token);
    const payload = await readJsonBody(request, context.maxBodyBytes);
    const result = context.db.transaction(() => {
      const applied = store.applyBatch(payload);
      if (context.recommendationCatalog) maintainRecommendations(store, context.recommendationCatalog(), new Date(context.now()));
      return applied;
    })();
    await maybeBackup(context);
    sendJson(response, 200, { ...result, sync_protocol: 3 });
    return;
  }

  if (url.pathname === "/sync/changes" && request.method === "GET") {
    requireBearer(request, context.token);
    sendJson(response, 200, readChanges(context.db, url.searchParams.get("cursor") ?? "", Number(url.searchParams.get("limit") ?? 400)));
    return;
  }

  if (url.pathname === "/sync/snapshot" && request.method === "GET") {
    requireBearer(request, context.token);
    sendJson(response, 200, store.snapshot());
    return;
  }

  sendJson(response, 404, { error: { message: "Not found." } });
}

async function lookupDictionary(resolver, lemma) {
  try {
    const raw = typeof resolver === "function"
      ? await resolver(lemma)
      : resolver && typeof resolver.lookup === "function"
        ? await resolver.lookup(lemma)
        : null;
    return normalizeDictionaryResult(lemma, raw);
  } catch {
    return pendingDictionaryResult(lemma, "provider_failure");
  }
}

function dictionaryLemmaFromPath(pathname) {
  const encoded = pathname.slice("/dict/".length);
  let decoded;
  try {
    decoded = decodeURIComponent(encoded);
  } catch {
    throw badRequest("Dictionary lemma must use valid URL encoding.");
  }
  return normalizeDictionaryLemma(decoded);
}

function dictionaryBatchLemmas(payload, limit) {
  if (!payload || typeof payload !== "object" || !Array.isArray(payload.lemmas)) {
    throw badRequest("Dictionary batch body must contain a lemmas array.");
  }
  if (payload.lemmas.length === 0 || payload.lemmas.length > limit) {
    throw badRequest(`Dictionary batch must contain between 1 and ${limit} lemmas.`);
  }
  return payload.lemmas.map(normalizeDictionaryLemma);
}

async function mapWithConcurrency(items, concurrency, transform) {
  const results = new Array(items.length);
  let nextIndex = 0;
  async function worker() {
    while (nextIndex < items.length) {
      const index = nextIndex;
      nextIndex += 1;
      results[index] = await transform(items[index]);
    }
  }
  const workerCount = Math.min(concurrency, items.length);
  await Promise.all(Array.from({ length: workerCount }, worker));
  return results;
}

async function maybeBackup({ db, dbPath, backupDir, backupRetention }) {
  if (!backupDir) return;
  await ensureDailyBackup(db, dbPath, backupDir, backupRetention);
}

function requireBearer(request, expectedToken) {
  const authorization = request.headers.authorization ?? "";
  const match = /^Bearer\s+(.+)$/i.exec(String(authorization));
  if (!match || !constantTimeTokenEquals(match[1], expectedToken)) {
    const error = new Error("Authentication required.");
    error.status = 401;
    throw error;
  }
}

function constantTimeTokenEquals(actual, expected) {
  const actualHash = crypto.createHash("sha256").update(actual).digest();
  const expectedHash = crypto.createHash("sha256").update(expected).digest();
  return crypto.timingSafeEqual(actualHash, expectedHash);
}

async function readJsonBody(request, maxBodyBytes) {
  const declaredLength = Number(request.headers["content-length"]);
  if (Number.isFinite(declaredLength) && declaredLength > maxBodyBytes) {
    throw payloadTooLarge();
  }
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.byteLength;
    if (size > maxBodyBytes) {
      throw payloadTooLarge();
    }
    chunks.push(chunk);
  }
  const body = Buffer.concat(chunks).toString("utf8");
  if (body.trim() === "") return {};
  try {
    return JSON.parse(body);
  } catch {
    const error = new Error("Request body must be valid JSON.");
    error.status = 400;
    throw error;
  }
}

function payloadTooLarge() {
  const error = new Error("Request body is too large.");
  error.status = 413;
  return error;
}

function badRequest(message) {
  const error = new Error(message);
  error.status = 400;
  return error;
}

function positiveIntegerOrDefault(value, fallback) {
  return Number.isSafeInteger(value) && value > 0 ? value : fallback;
}

function createFixedWindowRateLimiter(limit, now) {
  const windowMilliseconds = 60_000;
  let windowStartedAt = Number(now());
  let count = 0;
  return {
    consume() {
      const current = Number(now());
      if (!Number.isFinite(current)) throw new Error("Rate-limit clock returned an invalid timestamp.");
      if (current - windowStartedAt >= windowMilliseconds || current < windowStartedAt) {
        windowStartedAt = current;
        count = 0;
      }
      if (count >= limit) {
        const error = new Error("Dictionary request rate limit exceeded.");
        error.status = 429;
        error.retryAfterSeconds = Math.max(1, Math.ceil((windowStartedAt + windowMilliseconds - current) / 1_000));
        throw error;
      }
      count += 1;
    }
  };
}

function sendError(response, error, requestId) {
  const status = Number.isInteger(error.status) ? error.status : 500;
  if (status === 429 && Number.isInteger(error.retryAfterSeconds)) {
    response.setHeader("Retry-After", String(error.retryAfterSeconds));
  }
  sendJson(response, status, {
    error: {
      message: status === 500 ? "Internal server error." : error.message,
      request_id: requestId
    }
  });
}

function resolveRequestId(headerValue, requestIdFactory) {
  const candidate = Array.isArray(headerValue) ? headerValue[0] : headerValue;
  if (typeof candidate === "string" && /^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$/.test(candidate)) {
    return candidate;
  }
  const generated = String(requestIdFactory());
  if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$/.test(generated)) {
    throw new Error("requestIdFactory returned an invalid request ID.");
  }
  return generated;
}

function safeRouteLabel(rawUrl) {
  let pathname = "/";
  try {
    pathname = new URL(rawUrl ?? "/", "http://localhost").pathname;
  } catch {
    return "invalid-url";
  }
  if (pathname.startsWith("/dict/") && pathname !== "/dict/batch") return "/dict/:lemma";
  if (["/health", "/dict/batch", "/sync/batch", "/sync/changes", "/sync/snapshot"].includes(pathname)) {
    return pathname;
  }
  return "unmatched";
}

function logRequest(logger, entry) {
  if (typeof logger !== "function") return;
  try {
    logger({
      at: new Date().toISOString(),
      request_id: entry.requestId,
      method: String(entry.method).toUpperCase(),
      route: entry.route,
      status: entry.status,
      duration_ms: entry.durationMs
    });
  } catch {
    // Observability must never make a sync request fail.
  }
}

function sendJson(response, status, payload) {
  const body = JSON.stringify(payload);
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(body)
  });
  response.end(body);
}
