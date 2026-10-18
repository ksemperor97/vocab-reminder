import { createRecommendationCatalogLoader, maintainRecommendations } from "./recommendations.js";
import { readChanges } from "./changes.js";
import crypto from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import { pipeline } from "node:stream/promises";
import { androidUpdateDir, openAndroidApk, publicRelease, readAndroidRelease } from "./app_updates.js";
import { ensureDailyBackup } from "./backup.js";
import {
  createDictionaryResolver,
  normalizeDictionaryLemma,
  normalizeDictionaryResult,
  pendingDictionaryResult
} from "./dictionary.js";
import { contextSourceType, normalizeContextSentence } from "./context_meaning.js";
import { SyncStore } from "./sync.js";
import { createDeviceRegistry } from "./devices.js";

const defaultMaxBodyBytes = 16 * 1024 * 1024;
const defaultMaxDictionaryBatchItems = 50;
const hardMaxDictionaryBatchItems = 100;
const dictionaryBatchConcurrency = 4;
const contextBatchConcurrency = 8;
// Context calls that have not started by this point in a request answer with
// the dictionary result and contextStatus "deferred"; the client asks again.
const defaultContextBudgetMs = 20_000;
const defaultDictionaryRequestsPerMinute = 120;
const hardMaxDictionaryRequestsPerMinute = 600;

export function createSyncServer({
  db,
  dbPath,
  backupDir,
  backupRetention,
  token,
  publicUrl = "",
  appUpdateDir = null,
  maxBodyBytes = defaultMaxBodyBytes,
  maxCollectionItems,
  dictionaryResolver = createDictionaryResolver(),
  contextResolver = null,
  contextBudgetMs = defaultContextBudgetMs,
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
  const devices = createDeviceRegistry(db, { legacyToken: token, now });

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
        devices,
        publicUrl,
        androidUpdates: appUpdateDir ? androidUpdateDir(appUpdateDir) : null,
        maxBodyBytes: bodyByteLimit,
        dictionaryResolver,
        contextResolver,
        contextBudgetMs,
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

  // ---- Pairing: an authorized device makes a code, a new app exchanges it for its own token.
  if (url.pathname === "/pair/code" && request.method === "POST") {
    const deviceId = requireDevice(request, context);
    sendJson(response, 200, snakeCase(context.devices.createCode(deviceId)));
    return;
  }

  if (url.pathname === "/pair/claim" && request.method === "POST") {
    const payload = await readJsonBody(request, 16 * 1024);
    const claimed = context.devices.claim({ code: payload.code, deviceName: payload.device_name, platform: payload.platform });
    sendJson(response, 200, { device_id: claimed.deviceId, token: claimed.token });
    return;
  }

  // The QR code a device shows points here: a phone camera opens it in the
  // browser, and the button hands server and code to the installed app.
  if (url.pathname === "/pair" && request.method === "GET") {
    sendPairingPage(response, pairingOrigin(request, context.publicUrl), url.searchParams.get("code") ?? "");
    return;
  }

  if (url.pathname === "/devices" && request.method === "GET") {
    const deviceId = requireDevice(request, context);
    sendJson(response, 200, { current_device_id: deviceId, devices: context.devices.list() });
    return;
  }

  if (url.pathname === "/devices/revoke" && request.method === "POST") {
    requireDevice(request, context);
    const payload = await readJsonBody(request, 16 * 1024);
    sendJson(response, 200, context.devices.revoke(payload.device_id));
    return;
  }

  if (url.pathname === "/health" && request.method === "GET") {
    requireDevice(request, context);
    sendJson(response, 200, store.health());
    return;
  }

  if (url.pathname.startsWith("/dict/") && request.method === "GET") {
    requireDevice(request, context);
    context.dictionaryRateLimiter.consume();
    const lemma = dictionaryLemmaFromPath(url.pathname);
    sendJson(response, 200, await lookupDictionary(context.dictionaryResolver, lemma));
    return;
  }

  if (url.pathname === "/dict/batch" && request.method === "POST") {
    requireDevice(request, context);
    context.dictionaryRateLimiter.consume();
    const payload = await readJsonBody(request, context.maxBodyBytes);
    const items = dictionaryBatchItems(payload, context.maxDictionaryBatchItems);
    const uniqueLemmas = [...new Set(items.map((item) => item.lemma))];
    const uniqueResults = await mapWithConcurrency(
      uniqueLemmas,
      dictionaryBatchConcurrency,
      (lemma) => lookupDictionary(context.dictionaryResolver, lemma)
    );
    const resultByLemma = new Map(uniqueResults.map((result) => [result.lemma, result]));
    const contextEnabled = Boolean(context.contextResolver?.enabled);
    const contextItems = [...new Map(
      items.filter((item) => item.sentence !== "").map((item) => [itemKey(item), item])
    ).values()];
    const deadline = Date.now() + context.contextBudgetMs;
    const contextResults = new Map(await mapWithConcurrency(
      contextItems,
      contextBatchConcurrency,
      async (item) => [itemKey(item), await resolveInContext(
        context.contextResolver, item, resultByLemma.get(item.lemma), deadline
      )]
    ));
    sendJson(response, 200, {
      context_resolver: contextEnabled ? "enabled" : "disabled",
      results: items.map((item) => item.sentence === ""
        ? { ...resultByLemma.get(item.lemma), ...(item.withContext ? { contextStatus: "no_context" } : {}) }
        : { ...contextResults.get(itemKey(item)) })
    });
    return;
  }

  if (url.pathname === "/sync/batch" && request.method === "POST") {
    requireDevice(request, context);
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
    requireDevice(request, context);
    sendJson(response, 200, readChanges(context.db, url.searchParams.get("cursor") ?? "", Number(url.searchParams.get("limit") ?? 400)));
    return;
  }

  if (url.pathname === "/sync/snapshot" && request.method === "GET") {
    requireDevice(request, context);
    sendJson(response, 200, store.snapshot());
    return;
  }

  if (url.pathname === "/app/android/latest" && request.method === "GET") {
    requireDevice(request, context);
    const release = context.androidUpdates ? readAndroidRelease(context.androidUpdates) : null;
    if (!release) {
      sendJson(response, 404, { error: { message: "No Android update has been published." } });
      return;
    }
    sendJson(response, 200, publicRelease(release));
    return;
  }

  if (url.pathname === "/app/android/apk" && request.method === "GET") {
    requireDevice(request, context);
    const opened = context.androidUpdates ? openAndroidApk(context.androidUpdates) : null;
    if (!opened) {
      sendJson(response, 404, { error: { message: "No Android update has been published." } });
      return;
    }
    await sendApk(response, opened);
    return;
  }

  sendJson(response, 404, { error: { message: "Not found." } });
}

async function sendApk(response, { release, fd }) {
  // The descriptor was opened before the headers, so a publish that swaps
  // latest.json mid-download cannot mix two releases in one response.
  const stream = fs.createReadStream(null, { fd, start: 0 });
  response.writeHead(200, {
    "Content-Type": "application/vnd.android.package-archive",
    "Content-Length": release.size,
    "Content-Disposition": `attachment; filename="${release.file}"`,
    "Cache-Control": "no-store",
    "X-Apk-Sha256": release.sha256,
    "X-Apk-Version-Code": String(release.versionCode)
  });
  try {
    await pipeline(stream, response);
  } catch {
    // The phone went away mid-download; it retries and checks the hash anyway.
    response.destroy();
  }
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

// Accepts the original {lemmas: [...]} body and the context-aware
// {items: [{lemma, sentence}]} body. Results keep request order either way.
function dictionaryBatchItems(payload, limit) {
  if (!payload || typeof payload !== "object") {
    throw badRequest("Dictionary batch body must contain a lemmas or items array.");
  }
  if (Array.isArray(payload.items)) {
    if (payload.items.length === 0 || payload.items.length > limit) {
      throw badRequest(`Dictionary batch must contain between 1 and ${limit} items.`);
    }
    return payload.items.map((item) => {
      if (!item || typeof item !== "object") throw badRequest("Dictionary batch items must be objects.");
      if (item.sentence != null && typeof item.sentence !== "string") {
        throw badRequest("Dictionary batch item sentence must be a string.");
      }
      return {
        lemma: normalizeDictionaryLemma(item.lemma),
        sentence: normalizeContextSentence(item.sentence ?? ""),
        withContext: true
      };
    });
  }
  if (!Array.isArray(payload.lemmas)) {
    throw badRequest("Dictionary batch body must contain a lemmas or items array.");
  }
  if (payload.lemmas.length === 0 || payload.lemmas.length > limit) {
    throw badRequest(`Dictionary batch must contain between 1 and ${limit} lemmas.`);
  }
  return payload.lemmas.map((lemma) => ({ lemma: normalizeDictionaryLemma(lemma), sentence: "", withContext: false }));
}

function itemKey(item) {
  return `${item.lemma}\u0000${item.sentence}`;
}

// Guide 10.8: an LLM pick from the captured sentence outranks the dictionary
// gloss. The dictionary result stays attached so the client can keep it as a
// candidate, and is the answer whenever the LLM is off, unsure, or failing.
async function resolveInContext(resolver, item, dictionaryResult, deadline) {
  const fallback = (contextStatus) => ({ ...dictionaryResult, contextStatus, contextSentence: item.sentence });
  if (!resolver?.enabled) return fallback("disabled");
  if (Date.now() >= deadline) return fallback("deferred");
  let picked;
  try {
    picked = await resolver.resolve({
      lemma: item.lemma,
      sentence: item.sentence,
      candidates: dictionaryCandidates(dictionaryResult)
    });
  } catch {
    picked = { status: "failed" };
  }
  if (picked?.status !== "resolved") return fallback(picked?.status ?? "failed");
  const resolved = normalizeDictionaryResult(item.lemma, {
    meaningKo: picked.meaningsKo.join(", "),
    definitionEn: dictionaryResult.definitionEn,
    partOfSpeech: picked.partOfSpeech || dictionaryResult.partOfSpeech,
    exampleSentence: item.sentence,
    sourceType: contextSourceType,
    confidence: picked.confidence,
    ...(dictionaryResult.senses ? { senses: dictionaryResult.senses } : {})
  });
  return {
    ...resolved,
    contextStatus: "resolved",
    contextSentence: item.sentence,
    ...(dictionaryResult.status === "resolved" ? { dictionary: dictionaryFallbackSummary(dictionaryResult) } : {})
  };
}

function dictionaryCandidates(result) {
  if (!result || result.status !== "resolved") return [];
  const primary = { meaningKo: result.meaningKo, definitionEn: result.definitionEn, partOfSpeech: result.partOfSpeech };
  return [primary, ...(result.senses ?? [])];
}

function dictionaryFallbackSummary(result) {
  return {
    meaningKo: result.meaningKo,
    definitionEn: result.definitionEn,
    partOfSpeech: result.partOfSpeech,
    exampleSentence: result.exampleSentence,
    sourceType: result.sourceType,
    confidence: result.confidence
  };
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

/** The id of the device whose bearer token this request carries; 401 otherwise. */
function requireDevice(request, context) {
  const authorization = request.headers.authorization ?? "";
  const match = /^Bearer\s+(.+)$/i.exec(String(authorization));
  const deviceId = match ? context.devices.authenticate(match[1].trim()) : null;
  if (!deviceId) {
    const error = new Error("Authentication required.");
    error.status = 401;
    throw error;
  }
  return deviceId;
}

function snakeCase(value) {
  return Object.fromEntries(Object.entries(value).map(([key, entry]) => [key.replace(/[A-Z]/gu, (c) => `_${c.toLowerCase()}`), entry]));
}

function pairingOrigin(request, publicUrl) {
  if (publicUrl) return publicUrl.replace(/\/+$/u, "");
  const host = String(request.headers["x-forwarded-host"] ?? request.headers.host ?? "localhost");
  const proto = String(request.headers["x-forwarded-proto"] ?? (host.endsWith(".ts.net") ? "https" : "http"));
  return `${proto}://${host}`;
}

function sendPairingPage(response, origin, rawCode) {
  const code = String(rawCode).replace(/\D/gu, "").slice(0, 6);
  const appLink = `vocabreminder://pair?server=${encodeURIComponent(origin)}&code=${code}`;
  const androidLink = `intent://pair?server=${encodeURIComponent(origin)}&code=${code}#Intent;scheme=vocabreminder;package=com.vocabreminder.android;end`;
  const escape = (text) => text.replace(/[&<>"]/gu, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
  const body = `<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Vocabulary Reminder 연결</title>
<style>body{font:17px/1.6 system-ui,sans-serif;background:#faf8f4;color:#1c1b19;margin:0;padding:32px 20px}main{max-width:420px;margin:0 auto;display:grid;gap:18px}
.code{font:600 40px/1 ui-monospace,Menlo,monospace;letter-spacing:.2em;background:#f0ede6;border-radius:14px;padding:18px;text-align:center}
a.button{display:block;text-align:center;background:#1f2a37;color:#fff;border-radius:12px;padding:14px;text-decoration:none;font-weight:600}
p{margin:0;color:#5f5e5a}@media(prefers-color-scheme:dark){body{background:#161614;color:#e8e6e1}.code{background:#242320}p{color:#b4b2a9}a.button{background:#e8e6e1;color:#1c1b19}}</style></head>
<body><main><h1 style="margin:0;font-size:24px">Vocabulary Reminder 연결</h1>
<p>이 기기를 단어장 서버에 연결합니다. 앱이 설치돼 있으면 아래 버튼을 누르세요.</p>
<a class="button" href="${escape(androidLink)}">앱에서 열기</a>
<p>버튼이 동작하지 않으면 앱의 "서버에 연결" 화면에 아래 주소와 코드를 입력하세요. 코드는 10분 동안 한 번만 쓸 수 있습니다.</p>
<div class="code">${escape(code || "------")}</div><p style="word-break:break-all">${escape(origin)}</p>
<p style="font-size:13px"><a href="${escape(appLink)}" style="color:inherit">다른 기기에서 열기</a></p></main></body></html>`;
  response.writeHead(200, {
    "Content-Type": "text/html; charset=utf-8",
    "Content-Length": Buffer.byteLength(body),
    "Cache-Control": "no-store",
    "Referrer-Policy": "no-referrer"
  });
  response.end(body);
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
  if ([
    "/health", "/dict/batch", "/sync/batch", "/sync/changes", "/sync/snapshot",
    "/app/android/latest", "/app/android/apk"
  ].includes(pathname)) {
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
