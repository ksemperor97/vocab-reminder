import crypto from "node:crypto";
import http from "node:http";
import { ensureDailyBackup } from "./backup.js";
import { SyncStore } from "./sync.js";

const maxBodyBytes = 1024 * 1024 * 5;

export function createSyncServer({ db, dbPath, backupDir, backupRetention, token }) {
  if (!token || token.trim() === "") {
    throw new Error("VOCAB_SYNC_TOKEN is required.");
  }
  const store = new SyncStore(db);

  const server = http.createServer(async (request, response) => {
    try {
      await route({ request, response, store, db, dbPath, backupDir, backupRetention, token });
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

  if (url.pathname === "/sync/batch" && request.method === "POST") {
    requireBearer(request, context.token);
    const payload = await readJsonBody(request);
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

async function readJsonBody(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.byteLength;
    if (size > maxBodyBytes) {
      const error = new Error("Request body is too large.");
      error.status = 413;
      throw error;
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
