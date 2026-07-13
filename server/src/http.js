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

export function createSyncServer({
  db,
  dbPath,
  backupDir,
  backupRetention,
  token,
  maxBodyBytes = defaultMaxBodyBytes,
  maxCollectionItems,
  dictionaryResolver = createDictionaryResolver(),
  maxDictionaryBatchItems = defaultMaxDictionaryBatchItems
}) {
  if (!token || token.trim() === "") {
    throw new Error("VOCAB_SYNC_TOKEN is required.");
  }
  const bodyByteLimit = positiveIntegerOrDefault(maxBodyBytes, defaultMaxBodyBytes);
  const dictionaryBatchLimit = Math.min(
    positiveIntegerOrDefault(maxDictionaryBatchItems, defaultMaxDictionaryBatchItems),
    hardMaxDictionaryBatchItems
  );
  const store = new SyncStore(db, { maxCollectionItems });

  const server = http.createServer(async (request, response) => {
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
        maxDictionaryBatchItems: dictionaryBatchLimit
      });
    } catch (error) {
      sendError(response, error);
    }
  });

  return { server, store };
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
    const lemma = dictionaryLemmaFromPath(url.pathname);
    sendJson(response, 200, await lookupDictionary(context.dictionaryResolver, lemma));
    return;
  }

  if (url.pathname === "/dict/batch" && request.method === "POST") {
    requireBearer(request, context.token);
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
    const result = store.applyBatch(payload);
    await maybeBackup(context);
    sendJson(response, 200, result);
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

function sendError(response, error) {
  const status = Number.isInteger(error.status) ? error.status : 500;
  sendJson(response, status, {
    error: {
      message: status === 500 ? "Internal server error." : error.message
    }
  });
}

function sendJson(response, status, payload) {
  const body = JSON.stringify(payload);
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(body)
  });
  response.end(body);
}
