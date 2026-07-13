#!/usr/bin/env node
import { spawn as nodeSpawn } from "node:child_process";
import { existsSync, realpathSync, statSync } from "node:fs";
import { mkdir, rename, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const QUICK_TUNNEL_PATTERN = /https:\/\/[a-zA-Z0-9-]+\.trycloudflare\.com/g;
const DEFAULT_UPSTREAM = "http://127.0.0.1:8787";

export class ConfigurationError extends Error {
  constructor(message) {
    super(message);
    this.name = "ConfigurationError";
    this.exitCode = 78;
  }
}

function required(env, name) {
  const value = env[name]?.trim();
  if (!value || value.startsWith("replace-with-")) {
    throw new ConfigurationError(`${name} is required.`);
  }
  return value;
}

function parseUrl(value, name, { httpsOnly = false } = {}) {
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new ConfigurationError(`${name} must be a valid URL.`);
  }
  if (httpsOnly && url.protocol !== "https:") {
    throw new ConfigurationError(`${name} must use https.`);
  }
  if (!httpsOnly && !["http:", "https:"].includes(url.protocol)) {
    throw new ConfigurationError(`${name} must use http or https.`);
  }
  return url.toString().replace(/\/$/, "");
}

function positiveInteger(env, name, fallback, { minimum = 1 } = {}) {
  const raw = env[name]?.trim();
  if (!raw) {
    return fallback;
  }
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value < minimum) {
    throw new ConfigurationError(`${name} must be an integer greater than or equal to ${minimum}.`);
  }
  return value;
}

export function resolveStateFile(env = process.env) {
  const explicit = env.TUNNEL_STATE_FILE?.trim();
  if (explicit) {
    return path.resolve(explicit);
  }

  const runtimeRoot = env.VOCAB_SYNC_RUNTIME_ROOT?.trim();
  if (runtimeRoot) {
    const resolvedRoot = path.resolve(runtimeRoot);
    try {
      if (existsSync(resolvedRoot) && statSync(resolvedRoot).isDirectory()) {
        return path.join(resolvedRoot, "state", "tunnel-supervisor.json");
      }
    } catch {
      // Fall through to the repo-local state path. The writer reports real I/O failures.
    }
  }
  return path.join(SCRIPT_DIR, "state.json");
}

export function loadConfiguration(env = process.env) {
  const workerUrl = parseUrl(required(env, "CF_WORKER_PUBLIC_URL"), "CF_WORKER_PUBLIC_URL", {
    httpsOnly: true,
  });
  const upstreamUrl = parseUrl(
    env.VOCAB_SYNC_UPSTREAM_URL?.trim() || DEFAULT_UPSTREAM,
    "VOCAB_SYNC_UPSTREAM_URL",
  );
  const restartBaseMs = positiveInteger(env, "TUNNEL_RESTART_BASE_MS", 1_000);
  const restartMaxMs = positiveInteger(env, "TUNNEL_RESTART_MAX_MS", 60_000);
  if (restartMaxMs < restartBaseMs) {
    throw new ConfigurationError("TUNNEL_RESTART_MAX_MS must be greater than or equal to TUNNEL_RESTART_BASE_MS.");
  }

  return {
    cloudflaredBin: env.CLOUDFLARED_BIN?.trim() || "cloudflared",
    workerUrl,
    adminToken: required(env, "CF_WORKER_ADMIN_TOKEN"),
    syncToken: env.VOCAB_SYNC_TOKEN?.trim() || "",
    upstreamUrl,
    updateUrl: new URL("/__admin/upstream", `${workerUrl}/`).toString(),
    stateFile: resolveStateFile(env),
    healthIntervalMs: positiveInteger(env, "TUNNEL_HEALTH_INTERVAL_MS", 20_000),
    healthTimeoutMs: positiveInteger(env, "TUNNEL_HEALTH_TIMEOUT_MS", 8_000),
    healthFailureThreshold: positiveInteger(env, "TUNNEL_HEALTH_FAILURE_THRESHOLD", 3),
    tunnelReadyTimeoutMs: positiveInteger(env, "TUNNEL_READY_TIMEOUT_MS", 45_000),
    restartBaseMs,
    restartMaxMs,
    publishAttempts: positiveInteger(env, "TUNNEL_PUBLISH_ATTEMPTS", 5),
    publishRetryMs: positiveInteger(env, "TUNNEL_PUBLISH_RETRY_MS", 1_000),
    metadataRefreshMs: positiveInteger(env, "TUNNEL_METADATA_REFRESH_MS", 300_000),
    childStopGraceMs: positiveInteger(env, "TUNNEL_CHILD_STOP_GRACE_MS", 5_000),
  };
}

export async function writeJsonAtomically(filePath, value) {
  const directory = path.dirname(filePath);
  await mkdir(directory, { recursive: true, mode: 0o700 });
  const temporary = `${filePath}.${process.pid}.${Date.now()}.tmp`;
  await writeFile(temporary, `${JSON.stringify(value, null, 2)}\n`, { mode: 0o600 });
  await rename(temporary, filePath);
}

function redact(message, secrets) {
  let result = String(message || "unknown error");
  for (const secret of secrets) {
    if (secret) {
      result = result.split(secret).join("[redacted]");
    }
  }
  return result;
}

function isoTime(milliseconds) {
  return new Date(milliseconds).toISOString();
}

function healthUrl(baseUrl) {
  return new URL("/health", `${baseUrl}/`).toString();
}

function isReachableWithoutToken(status) {
  return status === 200 || status === 401;
}

export class QuickTunnelSupervisor {
  constructor(
    configuration,
    {
      spawnImpl = nodeSpawn,
      fetchImpl = globalThis.fetch,
      now = () => Date.now(),
      setTimeoutImpl = setTimeout,
      clearTimeoutImpl = clearTimeout,
      writeStateImpl = writeJsonAtomically,
      logger = console,
    } = {},
  ) {
    if (typeof fetchImpl !== "function") {
      throw new TypeError("A fetch implementation is required.");
    }
    this.configuration = configuration;
    this.spawnImpl = spawnImpl;
    this.fetchImpl = fetchImpl;
    this.now = now;
    this.setTimeoutImpl = setTimeoutImpl;
    this.clearTimeoutImpl = clearTimeoutImpl;
    this.writeStateImpl = writeStateImpl;
    this.logger = logger;
    this.secrets = [configuration.adminToken, configuration.syncToken].filter(Boolean);

    this.running = false;
    this.timer = undefined;
    this.child = undefined;
    this.childStartedAt = 0;
    this.expectedChildExit = false;
    this.forceKillTimer = undefined;
    this.currentTunnelUrl = "";
    this.pendingTunnelUrl = "";
    this.stdoutBuffer = "";
    this.stderrBuffer = "";
    this.generation = 0;
    this.publishedAt = null;
    this.lastLocalHealthAt = null;
    this.lastPublishedHealthAt = null;
    this.lastStableHealthAt = null;
    this.lastMetadataRefreshAt = 0;
    this.restartAttempt = 0;
    this.nextRestartAt = 0;
    this.localFailures = 0;
    this.stableFailures = 0;
    this.childFailures = 0;
    this.supervisorStatus = "starting";
    this.childStatus = "stopped";
    this.lastError = null;
    this.publishPromise = Promise.resolve();
    this.stateWritePromise = Promise.resolve();
  }

  snapshot() {
    return {
      schema_version: 1,
      supervisor_status: this.supervisorStatus,
      generation: this.generation,
      upstream_url: this.configuration.upstreamUrl,
      tunnel_url: this.currentTunnelUrl || null,
      worker_url: this.configuration.workerUrl,
      published_at: this.publishedAt,
      last_local_health_at: this.lastLocalHealthAt,
      last_published_health_at: this.lastPublishedHealthAt,
      last_stable_health_at: this.lastStableHealthAt,
      consecutive_failures: {
        local: this.localFailures,
        stable: this.stableFailures,
        child: this.childFailures,
      },
      child_pid: this.child?.pid ?? null,
      child_status: this.childStatus,
      restart_attempt: this.restartAttempt,
      next_restart_at: this.nextRestartAt ? isoTime(this.nextRestartAt) : null,
      last_error: this.lastError,
      updated_at: isoTime(this.now()),
    };
  }

  log(level, message) {
    const safe = redact(message, this.secrets);
    const method = typeof this.logger[level] === "function" ? this.logger[level] : this.logger.log;
    method.call(this.logger, safe);
  }

  setError(category, message) {
    this.lastError = {
      category,
      at: isoTime(this.now()),
      message: redact(message, this.secrets),
    };
  }

  persistState() {
    const snapshot = this.snapshot();
    this.stateWritePromise = this.stateWritePromise
      .catch(() => undefined)
      .then(() => this.writeStateImpl(this.configuration.stateFile, snapshot))
      .catch((error) => {
        this.log("error", `Failed to write tunnel supervisor state: ${error?.message || error}`);
      });
    return this.stateWritePromise;
  }

  async waitForIdle() {
    await this.publishPromise.catch(() => undefined);
    await this.stateWritePromise.catch(() => undefined);
  }

  async start({ schedule = true } = {}) {
    if (this.running) {
      return;
    }
    this.running = true;
    this.scheduleEnabled = schedule;
    await this.persistState();
    await this.runHealthCycle();
    if (schedule && this.running) {
      this.scheduleNextCycle();
    }
  }

  scheduleNextCycle() {
    if (!this.running || !this.scheduleEnabled) {
      return;
    }
    this.clearTimeoutImpl(this.timer);
    this.timer = this.setTimeoutImpl(async () => {
      try {
        await this.runHealthCycle();
      } catch (error) {
        this.setError("supervisor_cycle", "Unexpected health-cycle failure.");
        this.log("error", `Tunnel supervisor health cycle failed: ${error?.message || error}`);
      } finally {
        await this.persistState();
        this.scheduleNextCycle();
      }
    }, this.configuration.healthIntervalMs);
    this.timer?.unref?.();
  }

  async stop() {
    if (!this.running && !this.child) {
      return;
    }
    this.running = false;
    this.clearTimeoutImpl(this.timer);
    this.timer = undefined;
    if (this.child) {
      this.expectedChildExit = true;
      this.childStatus = "terminating";
      this.child.kill("SIGTERM");
    }
    this.supervisorStatus = "stopped";
    await this.persistState();
  }

  async fetchWithTimeout(url, init = {}) {
    const controller = new AbortController();
    const timeout = this.setTimeoutImpl(() => controller.abort(), this.configuration.healthTimeoutMs);
    timeout?.unref?.();
    try {
      return await this.fetchImpl(url, { ...init, signal: controller.signal });
    } finally {
      this.clearTimeoutImpl(timeout);
    }
  }

  healthHeaders(token) {
    const headers = { accept: "application/json", "user-agent": "VocabReminderTunnelSupervisor/1" };
    if (token) {
      headers.authorization = `Bearer ${token}`;
    }
    return headers;
  }

  async discardResponseBody(response) {
    try {
      await response.body?.cancel();
    } catch {
      // The status is sufficient for a health probe; cancellation is best effort.
    }
  }

  async probeLocalUpstream() {
    try {
      const response = await this.fetchWithTimeout(healthUrl(this.configuration.upstreamUrl), {
        headers: this.healthHeaders(this.configuration.syncToken),
        redirect: "manual",
      });
      const status = response.status;
      await this.discardResponseBody(response);
      const healthy = this.configuration.syncToken ? status === 200 : isReachableWithoutToken(status);
      return { healthy, status, category: healthy ? null : "local_health_status" };
    } catch {
      return { healthy: false, status: null, category: "local_health_network" };
    }
  }

  async probePublicEndpoint(baseUrl) {
    try {
      const valid = await this.fetchWithTimeout(healthUrl(baseUrl), {
        headers: this.healthHeaders(this.configuration.syncToken),
        redirect: "manual",
      });
      const validStatus = valid.status;
      await this.discardResponseBody(valid);
      if (this.configuration.syncToken) {
        if (validStatus !== 200) {
          return { healthy: false, status: validStatus, category: "valid_token_status" };
        }
        const invalid = await this.fetchWithTimeout(healthUrl(baseUrl), {
          headers: this.healthHeaders(`wrong-token-${this.generation || "probe"}`),
          redirect: "manual",
        });
        const invalidStatus = invalid.status;
        await this.discardResponseBody(invalid);
        if (invalidStatus !== 401) {
          return { healthy: false, status: invalidStatus, category: "wrong_token_status" };
        }
        return { healthy: true, status: validStatus, category: null };
      }
      return {
        healthy: isReachableWithoutToken(validStatus),
        status: validStatus,
        category: isReachableWithoutToken(validStatus) ? null : "public_health_status",
      };
    } catch {
      return { healthy: false, status: null, category: "public_health_network" };
    }
  }

  async runHealthCycle() {
    if (!this.running) {
      return;
    }
    const local = await this.probeLocalUpstream();
    if (!local.healthy) {
      this.localFailures += 1;
      this.supervisorStatus = "waiting_for_local";
      this.setError(local.category, `Local sync health check failed${local.status ? ` with HTTP ${local.status}` : ""}.`);
      await this.persistState();
      return;
    }

    this.localFailures = 0;
    this.lastLocalHealthAt = isoTime(this.now());
    if (!this.child) {
      if (this.nextRestartAt && this.now() < this.nextRestartAt) {
        this.supervisorStatus = "backoff";
        await this.persistState();
        return;
      }
      this.startChild();
      await this.persistState();
      return;
    }

    if (!this.currentTunnelUrl) {
      const age = this.now() - this.childStartedAt;
      this.supervisorStatus = "waiting_for_tunnel_url";
      if (age >= this.configuration.tunnelReadyTimeoutMs) {
        this.requestRestart("tunnel_ready_timeout", "cloudflared did not publish a quick-tunnel URL in time.");
      }
      await this.persistState();
      return;
    }

    const published = await this.probePublicEndpoint(this.currentTunnelUrl);
    if (published.healthy) {
      this.lastPublishedHealthAt = isoTime(this.now());
    }
    const stable = published.healthy ? await this.probePublicEndpoint(this.configuration.workerUrl) : null;
    if (published.healthy && stable?.healthy) {
      const now = this.now();
      this.stableFailures = 0;
      this.childFailures = 0;
      this.restartAttempt = 0;
      this.nextRestartAt = 0;
      this.lastStableHealthAt = isoTime(now);
      this.lastError = null;
      this.supervisorStatus = "healthy";
      if (now - this.lastMetadataRefreshAt >= this.configuration.metadataRefreshMs) {
        try {
          await this.publishCurrentTunnel({ lastSuccessAt: this.lastStableHealthAt });
          this.lastMetadataRefreshAt = now;
        } catch (error) {
          this.setError("worker_metadata", "The stable route is healthy but its success metadata update failed.");
          this.log("error", `Worker success metadata update failed: ${error?.message || error}`);
        }
      }
      await this.persistState();
      return;
    }

    this.stableFailures += 1;
    const failed = published.healthy ? stable : published;
    const category = published.healthy ? "stable_health" : "published_health";
    this.supervisorStatus = "degraded";
    this.setError(
      category,
      `Public sync health check failed${failed?.status ? ` with HTTP ${failed.status}` : ""} (${failed?.category || "unknown"}).`,
    );

    // A repeated publish repairs KV propagation or an overwritten mapping. If the
    // direct quick-tunnel URL is healthy, recycling cloudflared cannot repair a
    // Worker/KV outage, so keep the useful child alive and continue republishing.
    if (published.healthy || this.stableFailures < this.configuration.healthFailureThreshold) {
      try {
        await this.publishCurrentTunnel({ preserveStatus: true });
      } catch (error) {
        this.setError("worker_publish", "Public health failed and the Worker mapping could not be republished.");
        this.log("error", `Worker upstream republish failed: ${error?.message || error}`);
      }
    } else {
      this.requestRestart(category, "Public sync remained unhealthy while the local upstream was healthy.");
    }
    await this.persistState();
  }

  startChild() {
    if (!this.running || this.child) {
      return;
    }
    this.currentTunnelUrl = "";
    this.publishedAt = null;
    this.lastPublishedHealthAt = null;
    this.stdoutBuffer = "";
    this.stderrBuffer = "";
    this.nextRestartAt = 0;
    this.expectedChildExit = false;
    this.childStartedAt = this.now();
    this.childStatus = "starting";
    this.supervisorStatus = "starting_child";
    this.log("log", `Starting cloudflared quick tunnel for ${this.configuration.upstreamUrl}`);

    let child;
    try {
      child = this.spawnImpl(
        this.configuration.cloudflaredBin,
        ["tunnel", "--no-autoupdate", "--url", this.configuration.upstreamUrl],
        {
          stdio: ["ignore", "pipe", "pipe"],
          env: Object.fromEntries(
            Object.entries(process.env).filter(
              ([name]) => !["CF_WORKER_ADMIN_TOKEN", "VOCAB_SYNC_TOKEN"].includes(name),
            ),
          ),
        },
      );
    } catch {
      this.childStatus = "stopped";
      this.childFailures += 1;
      this.scheduleRestart("child_spawn", "cloudflared could not be started.");
      return;
    }

    this.child = child;
    this.childStatus = "running";
    child.stdout?.on("data", (chunk) => this.handleChildOutput(chunk, false));
    child.stderr?.on("data", (chunk) => this.handleChildOutput(chunk, true));
    child.on("error", () => {
      if (child !== this.child) {
        return;
      }
      this.setError("child_error", "cloudflared reported a process error.");
      this.persistState();
    });
    child.on("exit", (code, signal) => this.handleChildExit(child, code, signal));
  }

  handleChildOutput(chunk, stderr) {
    const text = String(chunk);
    const safeText = redact(text, this.secrets);
    if (stderr) {
      process.stderr.write(safeText);
    } else {
      process.stdout.write(safeText);
    }
    const bufferName = stderr ? "stderrBuffer" : "stdoutBuffer";
    const combined = `${this[bufferName]}${text}`;
    this[bufferName] = combined.slice(-512);
    const matches = combined.match(QUICK_TUNNEL_PATTERN);
    if (!matches) {
      return;
    }
    const tunnelUrl = matches.at(-1);
    if (tunnelUrl === this.currentTunnelUrl || tunnelUrl === this.pendingTunnelUrl) {
      return;
    }
    this.pendingTunnelUrl = tunnelUrl;
    this.publishPromise = this.publishPromise
      .catch(() => undefined)
      .then(() => this.acceptTunnelUrl(tunnelUrl))
      .catch((error) => {
        this.setError("worker_publish", "Failed to publish the quick-tunnel URL.");
        this.log("error", `Failed to publish quick-tunnel URL: ${error?.message || error}`);
        this.requestRestart("worker_publish", "Worker upstream publication failed.");
        return this.persistState();
      })
      .finally(() => {
        if (this.pendingTunnelUrl === tunnelUrl) {
          this.pendingTunnelUrl = "";
        }
      });
  }

  async acceptTunnelUrl(tunnelUrl) {
    const candidate = new URL(tunnelUrl);
    if (candidate.protocol !== "https:" || !candidate.hostname.endsWith(".trycloudflare.com")) {
      throw new Error("cloudflared supplied an invalid quick-tunnel URL.");
    }
    const normalized = candidate.origin;
    this.currentTunnelUrl = normalized;
    this.generation = Math.max(this.generation + 1, this.now());
    this.publishedAt = null;
    this.lastMetadataRefreshAt = 0;
    this.supervisorStatus = "publishing";
    await this.persistState();
    await this.publishCurrentTunnel();
  }

  async publishCurrentTunnel({ lastSuccessAt = null, preserveStatus = false } = {}) {
    if (!this.currentTunnelUrl) {
      throw new Error("No quick-tunnel URL is available to publish.");
    }
    let lastStatus = null;
    for (let attempt = 1; attempt <= this.configuration.publishAttempts; attempt += 1) {
      try {
        const response = await this.fetchWithTimeout(this.configuration.updateUrl, {
          method: "POST",
          headers: {
            accept: "application/json",
            "content-type": "application/json",
            "user-agent": "VocabReminderTunnelSupervisor/1",
            "x-vocab-sync-admin": this.configuration.adminToken,
          },
          body: JSON.stringify({
            url: this.currentTunnelUrl,
            generation: this.generation,
            updated_at: isoTime(this.now()),
            last_success_at: lastSuccessAt,
          }),
        });
        lastStatus = response.status;
        const responseOk = response.ok;
        await this.discardResponseBody(response);
        if (responseOk) {
          if (!this.publishedAt) {
            this.publishedAt = isoTime(this.now());
          }
          if (!preserveStatus) {
            this.supervisorStatus = lastSuccessAt ? "healthy" : "published";
          }
          this.log("log", `Published Cloudflare quick-tunnel generation ${this.generation}.`);
          await this.persistState();
          return;
        }
      } catch {
        lastStatus = null;
      }

      if (attempt < this.configuration.publishAttempts) {
        await new Promise((resolve) => {
          const timer = this.setTimeoutImpl(resolve, this.configuration.publishRetryMs * attempt);
          timer?.unref?.();
        });
      }
    }
    throw new Error(
      lastStatus ? `Worker upstream update failed with HTTP ${lastStatus}.` : "Worker upstream update failed by network error.",
    );
  }

  handleChildExit(exitedChild, code, signal) {
    if (exitedChild !== this.child) {
      return;
    }
    this.clearTimeoutImpl(this.forceKillTimer);
    this.forceKillTimer = undefined;
    this.child = undefined;
    this.childStatus = "stopped";
    this.currentTunnelUrl = "";
    this.pendingTunnelUrl = "";
    this.publishedAt = null;
    const expected = this.expectedChildExit;
    this.expectedChildExit = false;
    if (!this.running) {
      this.supervisorStatus = "stopped";
      this.persistState();
      return;
    }
    if (!expected) {
      this.childFailures += 1;
      this.log("error", `cloudflared exited unexpectedly: code=${code ?? ""} signal=${signal ?? ""}`);
      this.scheduleRestart("child_exit", "cloudflared exited unexpectedly.");
    }
    this.persistState();
  }

  requestRestart(category, message) {
    if (!this.child) {
      this.scheduleRestart(category, message);
      return;
    }
    this.setError(category, message);
    this.expectedChildExit = true;
    this.childStatus = "terminating";
    const child = this.child;
    this.scheduleRestart(category, message);
    child.kill("SIGTERM");
    this.clearTimeoutImpl(this.forceKillTimer);
    this.forceKillTimer = this.setTimeoutImpl(() => {
      if (this.child === child) {
        child.kill("SIGKILL");
      }
    }, this.configuration.childStopGraceMs);
    this.forceKillTimer?.unref?.();
  }

  scheduleRestart(category, message) {
    this.restartAttempt += 1;
    const exponent = Math.min(this.restartAttempt - 1, 30);
    const delay = Math.min(this.configuration.restartBaseMs * 2 ** exponent, this.configuration.restartMaxMs);
    this.nextRestartAt = this.now() + delay;
    this.supervisorStatus = "backoff";
    this.childStatus = this.child ? this.childStatus : "backoff";
    this.setError(category, message);
    this.log("error", `${message} Restart scheduled in ${delay} ms.`);
  }
}

export function isDirectExecution(executablePath = process.argv[1], moduleUrl = import.meta.url) {
  if (!executablePath) {
    return false;
  }
  try {
    const executableRealPath = realpathSync(path.resolve(executablePath));
    const moduleRealPath = realpathSync(fileURLToPath(moduleUrl));
    return executableRealPath === moduleRealPath;
  } catch {
    return pathToFileURL(path.resolve(executablePath)).href === moduleUrl;
  }
}

if (isDirectExecution()) {
  let supervisor;
  try {
    supervisor = new QuickTunnelSupervisor(loadConfiguration());
  } catch (error) {
    console.error(error.message);
    process.exit(error.exitCode || 1);
  }

  for (const signal of ["SIGINT", "SIGTERM"]) {
    process.on(signal, () => {
      void supervisor.stop().finally(() => {
        const fallback = setTimeout(() => process.exit(0), 500);
        fallback.unref();
      });
    });
  }

  supervisor.start().catch((error) => {
    supervisor.setError("supervisor_start", "Tunnel supervisor failed to start.");
    supervisor.log("error", `Tunnel supervisor failed to start: ${error?.message || error}`);
    void supervisor.persistState().finally(() => process.exit(1));
  });
}
