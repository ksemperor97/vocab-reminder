import { execFile } from "node:child_process";
import fs from "node:fs/promises";

const defaultCacheMaxEntries = 1_000;
const defaultHitCacheTtlMs = 24 * 60 * 60 * 1_000;
const defaultMissCacheTtlMs = 5 * 60 * 1_000;
const defaultFailureCacheTtlMs = 15 * 1_000;
const defaultHelperTimeoutMs = 2_500;
const maxLemmaLength = 80;

export function createDictionaryResolver(options = {}) {
  const providers = options.providers ?? configuredProviders(options);
  const cacheMaxEntries = positiveNumberOrDefault(
    options.cacheMaxEntries ?? process.env.VOCAB_DICTIONARY_CACHE_MAX_ENTRIES,
    defaultCacheMaxEntries
  );
  const hitCacheTtlMs = positiveNumberOrDefault(options.hitCacheTtlMs, defaultHitCacheTtlMs);
  const missCacheTtlMs = positiveNumberOrDefault(options.missCacheTtlMs, defaultMissCacheTtlMs);
  const failureCacheTtlMs = positiveNumberOrDefault(options.failureCacheTtlMs, defaultFailureCacheTtlMs);
  const attemptedAt = typeof options.now === "function" ? options.now : () => new Date();
  const cacheNow = typeof options.cacheNow === "function" ? options.cacheNow : Date.now;
  const cache = new Map();
  const inFlight = new Map();

  async function lookup(input) {
    const lemma = normalizeDictionaryLemma(input);
    const cached = readCache(cache, lemma, cacheNow());
    if (cached) return cached;
    if (inFlight.has(lemma)) return cloneResult(await inFlight.get(lemma));

    const operation = resolveFromProviders(lemma, providers, attemptedAt)
      .then((result) => {
        const ttl = result.status === "resolved"
          ? hitCacheTtlMs
          : result.errorCode === "provider_failure"
            ? failureCacheTtlMs
            : missCacheTtlMs;
        writeCache(cache, lemma, result, cacheNow() + ttl, cacheMaxEntries);
        return result;
      })
      .finally(() => inFlight.delete(lemma));
    inFlight.set(lemma, operation);
    return cloneResult(await operation);
  }

  return {
    lookup,
    clearCache() {
      cache.clear();
    }
  };
}

export function createOfflineCatalogProvider({ catalogPath }) {
  let catalogPromise;

  async function loadCatalog() {
    if (!catalogPromise) {
      catalogPromise = fs.readFile(catalogPath, "utf8")
        .then(JSON.parse)
        .then(indexCatalog)
        .catch((error) => {
          catalogPromise = undefined;
          throw error;
        });
    }
    return catalogPromise;
  }

  return {
    name: "offline_catalog",
    async lookup(lemma) {
      const catalog = await loadCatalog();
      return catalog.get(lemma) ?? null;
    }
  };
}

export function createMacDictionaryProvider({
  helperPath,
  timeoutMs = defaultHelperTimeoutMs
}) {
  return {
    name: "mac_dictionary",
    lookup(lemma) {
      return runDictionaryHelper(helperPath, lemma, timeoutMs);
    }
  };
}

export function normalizeDictionaryLemma(input) {
  if (typeof input !== "string") throw invalidLemma("Dictionary lemma must be a string.");
  const normalized = input
    .normalize("NFKC")
    .replace(/[\u2018\u2019]/g, "'")
    .replace(/[\u2010-\u2015]/g, "-")
    .trim()
    .replace(/\s+/gu, " ")
    .toLocaleLowerCase("en-US");
  const length = [...normalized].length;
  if (length === 0 || length > maxLemmaLength) {
    throw invalidLemma(`Dictionary lemma must contain between 1 and ${maxLemmaLength} characters.`);
  }
  if (!/^[\p{L}\p{M}]+(?:[ '\-][\p{L}\p{M}]+)*$/u.test(normalized)) {
    throw invalidLemma("Dictionary lemma contains unsupported characters.");
  }
  return normalized;
}

export function normalizeDictionaryResult(lemmaInput, value, fallbackAttemptedAt = new Date()) {
  const lemma = normalizeDictionaryLemma(lemmaInput);
  const source = value && typeof value === "object" ? value : {};
  const meaningKo = trimmedString(source.meaningKo ?? source.meaning_ko, 2_000);
  const definitionEn = trimmedString(source.definitionEn ?? source.definition_en, 4_000);
  const partOfSpeech = trimmedString(source.partOfSpeech ?? source.part_of_speech, 120);
  const exampleSentence = trimmedString(source.exampleSentence ?? source.example_sentence, 2_000);
  const hasDefinition = meaningKo !== "" || definitionEn !== "";
  const sourceType = hasDefinition
    ? normalizedSourceType(source.sourceType ?? source.source_type)
    : "none";

  return {
    status: hasDefinition ? "resolved" : "pending",
    lemma,
    meaningKo,
    definitionEn,
    partOfSpeech,
    exampleSentence,
    sourceType,
    confidence: hasDefinition ? normalizedConfidence(source.confidence) : 0,
    attemptedAt: normalizedTimestamp(source.attemptedAt ?? source.attempted_at, fallbackAttemptedAt),
    errorCode: hasDefinition ? null : normalizedErrorCode(source.errorCode ?? source.error_code)
  };
}

export function pendingDictionaryResult(lemma, errorCode = "not_found", attemptedAt = new Date()) {
  return normalizeDictionaryResult(lemma, { errorCode }, attemptedAt);
}

async function resolveFromProviders(lemma, providers, now) {
  const timestamp = normalizeNow(now());
  let providerFailed = false;

  for (const provider of providers) {
    try {
      if (!provider || typeof provider.lookup !== "function") {
        providerFailed = true;
        continue;
      }
      const raw = await provider.lookup(lemma);
      if (raw === null || raw === undefined) continue;
      if (typeof raw !== "object") {
        providerFailed = true;
        continue;
      }
      const result = normalizeDictionaryResult(lemma, raw, timestamp);
      if (result.status === "resolved") return result;
      if (result.errorCode === "provider_failure") providerFailed = true;
    } catch {
      providerFailed = true;
    }
  }

  return pendingDictionaryResult(
    lemma,
    providerFailed ? "provider_failure" : "not_found",
    timestamp
  );
}

function configuredProviders(options) {
  const providers = [];
  const catalogPath = nonEmptyString(
    options.catalogPath ?? process.env.VOCAB_DICTIONARY_CATALOG_PATH
  );
  const helperPath = nonEmptyString(
    options.helperPath ?? process.env.VOCAB_DICTIONARY_HELPER_PATH
  );
  if (catalogPath) providers.push(createOfflineCatalogProvider({ catalogPath }));
  if (helperPath) {
    providers.push(createMacDictionaryProvider({
      helperPath,
      timeoutMs: positiveNumberOrDefault(
        options.helperTimeoutMs ?? process.env.VOCAB_DICTIONARY_HELPER_TIMEOUT_MS,
        defaultHelperTimeoutMs
      )
    }));
  }
  return providers;
}

function indexCatalog(payload) {
  const index = new Map();
  const entries = Array.isArray(payload)
    ? payload
    : Array.isArray(payload?.entries)
      ? payload.entries
      : objectCatalogEntries(payload);
  if (!entries) throw new Error("Offline dictionary catalog must contain an entries array or object map.");

  for (const entry of entries) {
    if (!entry || typeof entry !== "object") continue;
    try {
      const lemma = normalizeDictionaryLemma(entry.lemma);
      const normalized = normalizeDictionaryResult(lemma, {
        ...entry,
        sourceType: entry.sourceType ?? entry.source_type ?? "offline_catalog",
        confidence: entry.confidence ?? 0.9
      });
      if (normalized.status === "resolved") {
        index.set(lemma, {
          meaningKo: normalized.meaningKo,
          definitionEn: normalized.definitionEn,
          partOfSpeech: normalized.partOfSpeech,
          exampleSentence: normalized.exampleSentence,
          sourceType: normalized.sourceType,
          confidence: normalized.confidence
        });
      }
    } catch {
      // One malformed catalog row must not disable the remaining offline catalog.
    }
  }
  return index;
}

function objectCatalogEntries(payload) {
  if (!payload || typeof payload !== "object" || Array.isArray(payload)) return null;
  const ignoredMetadata = new Set(["source", "generatedAt", "generated_at", "version"]);
  const entries = [];
  for (const [lemma, value] of Object.entries(payload)) {
    if (ignoredMetadata.has(lemma) || !value || typeof value !== "object" || Array.isArray(value)) continue;
    entries.push({ lemma, ...value });
  }
  return entries.length > 0 ? entries : null;
}

function runDictionaryHelper(helperPath, lemma, timeoutMs) {
  return new Promise((resolve, reject) => {
    execFile(
      helperPath,
      [lemma],
      {
        encoding: "utf8",
        maxBuffer: 128 * 1_024,
        timeout: positiveNumberOrDefault(timeoutMs, defaultHelperTimeoutMs),
        windowsHide: true
      },
      (error, stdout) => {
        if (error) {
          if (Number(error.code) === 2) {
            resolve(null);
            return;
          }
          reject(new Error("Mac dictionary helper failed."));
          return;
        }
        try {
          const result = JSON.parse(stdout);
          if (!result || typeof result !== "object" || Array.isArray(result)) {
            throw new Error("invalid helper response");
          }
          resolve(result);
        } catch {
          reject(new Error("Mac dictionary helper returned invalid JSON."));
        }
      }
    );
  });
}

function readCache(cache, key, now) {
  const entry = cache.get(key);
  if (!entry) return null;
  if (entry.expiresAt <= now) {
    cache.delete(key);
    return null;
  }
  cache.delete(key);
  cache.set(key, entry);
  return cloneResult(entry.result);
}

function writeCache(cache, key, result, expiresAt, maxEntries) {
  cache.delete(key);
  cache.set(key, { result: cloneResult(result), expiresAt });
  while (cache.size > maxEntries) {
    const oldestKey = cache.keys().next().value;
    cache.delete(oldestKey);
  }
}

function cloneResult(result) {
  return { ...result };
}

function invalidLemma(message) {
  const error = new Error(message);
  error.status = 400;
  error.code = "invalid_lemma";
  return error;
}

function normalizedTimestamp(candidate, fallback) {
  const parsed = new Date(candidate ?? fallback);
  return Number.isNaN(parsed.getTime()) ? normalizeNow(fallback) : parsed.toISOString();
}

function normalizeNow(value) {
  const parsed = value instanceof Date ? value : new Date(value);
  return Number.isNaN(parsed.getTime()) ? new Date().toISOString() : parsed.toISOString();
}

function normalizedErrorCode(value) {
  return value === "provider_failure" ? "provider_failure" : "not_found";
}

function normalizedSourceType(value) {
  const candidate = trimmedString(value, 64).toLowerCase();
  return /^[a-z0-9_-]+$/.test(candidate) ? candidate : "dictionary";
}

function normalizedConfidence(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) return 0;
  return Math.max(0, Math.min(1, number));
}

function trimmedString(value, maxLength) {
  if (typeof value !== "string") return "";
  return value.trim().slice(0, maxLength);
}

function nonEmptyString(value) {
  return typeof value === "string" && value.trim() !== "" ? value.trim() : null;
}

function positiveNumberOrDefault(value, fallback) {
  const number = Number(value);
  return Number.isFinite(number) && number > 0 ? Math.floor(number) : fallback;
}
