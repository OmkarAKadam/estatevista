/**
 * Shared REST client for the EstateVista V2 API.
 *
 * - Uses the Fetch API directly against the separately hosted API.
 * - Sends the host-only `EV_SESSION` cookie with `credentials: "include"`.
 * - Keeps the session-bound CSRF token in memory only. The token is never
 *   written to a cookie, localStorage, sessionStorage, a URL, or a log.
 * - Reads the token exclusively from the CORS-exposed `X-CSRF-TOKEN` response
 *   header and echoes it in the `X-CSRF-TOKEN` request header for state-changing
 *   requests. The JSON body never contains a token.
 * - Preserves the backend success and error envelopes and never exposes raw
 *   HTML, stack traces, or other non-JSON server output to callers.
 *
 * Bounded timeout, one controlled retry and rate-limit classification apply
 * only to explicit public read-only GET calls (`publicRead: true`) made by the
 * public property bridge. Authentication, CSRF bootstrap, session management,
 * logout, property management and upload requests keep their approved semantics.
 */

import { getApiBaseUrl, getPublicRequestConfig, describeConfigurationError } from "./config.js";

const CSRF_HEADER = "X-CSRF-TOKEN";
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);
const PUBLIC_READ_PATH = /^\/(?:properties(?:[/?#]|$)|property-categories(?:[/?#]|$)|property-subtypes(?:[/?#]|$)|amenities(?:[/?#]|$))/;

const MAX_AUTOMATIC_RETRIES = 1;
const RETRY_BASE_DELAY_MS = 250;
const RETRY_JITTER_MS = 250;
const RETRYABLE_STATUSES = new Set([408, 425, 500, 502, 503, 504]);
const FALLBACK_RATE_LIMIT_COOLDOWN_MS = 60000;
const MIN_RETRY_AFTER_MS = 1000;
const MAX_RETRY_AFTER_MS = 300000;

let csrfToken = null;
let csrfRequest = null;

/**
 * Error raised for any unsuccessful API call.
 *
 * `status` is the HTTP status, or 0 for a network/transport failure. `code`,
 * `details`, and `payload` mirror the backend error envelope when one was
 * returned. Pages decide which safe message to show; raw server text is never
 * rendered directly.
 */
export class ApiError extends Error {
  constructor(message, options = {}) {
    super(message);
    const {
      status = 0,
      code = null,
      details = [],
      payload = null,
      reason = null,
      retryAfterMs = null
    } = options;
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.details = Array.isArray(details) ? details : [];
    this.payload = payload;
    this.reason = reason;
    this.retryAfterMs = retryAfterMs;
  }
}

export function hasCsrfToken() {
  return csrfToken !== null;
}

export function clearCsrfToken() {
  csrfToken = null;
}

function buildUrl(path) {
  if (/^https?:\/\//i.test(path)) {
    return path;
  }
  const base = getApiBaseUrl();
  return `${base}${path.startsWith("/") ? path : `/${path}`}`;
}

function requestUrl(path) {
  try {
    return buildUrl(path);
  } catch (error) {
    if (error && error.name === "EstateVistaConfigurationError") {
      const wrapped = new ApiError(describeConfigurationError(), {
        status: 0,
        code: "CONFIGURATION_ERROR",
        reason: "configuration"
      });
      throw wrapped;
    }
    throw error;
  }
}

function captureCsrfToken(response) {
  const token = response.headers.get(CSRF_HEADER);
  if (token && token.trim().length > 0) {
    csrfToken = token.trim();
  }
}

async function parseBody(response) {
  if (response.status === 204 || response.status === 205) {
    return null;
  }

  const contentType = response.headers.get("Content-Type") ?? "";
  if (contentType.includes("application/json")) {
    try {
      return await response.json();
    } catch {
      return null;
    }
  }

  const text = await response.text();
  return text.length > 0 ? text : null;
}

/** Races a body read against the same signal used by the fetch attempt. */
function readWithSignal(promise, signal) {
  return new Promise((resolve, reject) => {
    let settled = false;
    const cleanup = () => signal.removeEventListener("abort", onAbort);
    const onAbort = () => {
      if (settled) {
        return;
      }
      settled = true;
      cleanup();
      reject(cancellationError());
    };

    if (signal.aborted) {
      onAbort();
      return;
    }

    signal.addEventListener("abort", onAbort, { once: true });
    promise.then(
      (value) => {
        if (settled) {
          return;
        }
        settled = true;
        cleanup();
        resolve(value);
      },
      (error) => {
        if (settled) {
          return;
        }
        settled = true;
        cleanup();
        reject(error);
      }
    );
  });
}

function parseBodyWithSignal(response, signal) {
  if (response.status === 204 || response.status === 205) {
    return Promise.resolve(null);
  }

  const contentType = response.headers.get("Content-Type") ?? "";
  if (contentType.includes("application/json")) {
    return readWithSignal(response.json(), signal);
  }

  return readWithSignal(
    response.text().then((text) => (text.length > 0 ? text : null)),
    signal
  );
}

/** Accepts delay-seconds and HTTP-date Retry-After values. */
export function parseRetryAfter(value, now = Date.now()) {
  if (typeof value !== "string") {
    return null;
  }
  const raw = value.trim();
  if (!raw) {
    return null;
  }

  let milliseconds = null;
  if (/^\d+$/.test(raw)) {
    const seconds = Number(raw);
    if (Number.isFinite(seconds)) {
      milliseconds = seconds * 1000;
    }
  } else {
    const date = Date.parse(raw);
    if (!Number.isNaN(date)) {
      milliseconds = date - now;
    }
  }

  if (milliseconds === null || !Number.isFinite(milliseconds)) {
    return null;
  }
  if (milliseconds <= 0) {
    return MIN_RETRY_AFTER_MS;
  }
  return Math.min(Math.floor(milliseconds), MAX_RETRY_AFTER_MS);
}

function apiErrorFromResponse(response, payload) {
  const fallback = `Request failed with status ${response.status}`;
  const envelope =
    payload && typeof payload === "object" && payload.error && typeof payload.error === "object"
      ? payload.error
      : null;

  let error;
  if (!envelope) {
    error = new ApiError(fallback, { status: response.status, payload });
  } else {
    const message =
      typeof envelope.message === "string" && envelope.message.trim().length > 0
        ? envelope.message
        : fallback;
    const code = typeof envelope.code === "string" ? envelope.code : null;
    const details = Array.isArray(envelope.details)
      ? envelope.details.filter(
          (detail) =>
            detail &&
            typeof detail === "object" &&
            typeof detail.field === "string" &&
            typeof detail.message === "string"
        )
      : [];

    error = new ApiError(message, { status: response.status, code, details, payload });
  }

  error.retryAfterMs = parseRetryAfter(response.headers.get("Retry-After"));
  if (response.status === 429) {
    error.reason = "rate_limit";
  } else if (response.status >= 500) {
    error.reason = "server";
  } else if (response.status === 404) {
    error.reason = "not_found";
  } else if (response.status === 400 || response.status === 422) {
    error.reason = "validation";
  } else {
    error.reason = "http";
  }
  return error;
}

function networkError() {
  const error = new ApiError("The server could not be reached.", {
    status: 0,
    code: "NETWORK_ERROR",
    reason: "network"
  });
  return error;
}

function timeoutError(durationMs) {
  const error = new ApiError("The request timed out.", {
    status: 0,
    code: "TIMEOUT",
    reason: "timeout"
  });
  error.timeoutMs = durationMs;
  return error;
}

function deadlineError() {
  return new ApiError("The request deadline was reached.", {
    status: 0,
    code: "DEADLINE_EXCEEDED",
    reason: "timeout"
  });
}

function cancellationError() {
  const error = new Error("The request was cancelled.");
  error.name = "AbortError";
  error.code = "CANCELLED";
  return error;
}

export function isCancellation(error) {
  return Boolean(error && (error.name === "AbortError" || error.code === "CANCELLED"));
}

export function isTimeout(error) {
  return Boolean(
    error &&
      (error.code === "TIMEOUT" ||
        error.code === "DEADLINE_EXCEEDED" ||
        error.reason === "timeout")
  );
}

function isRetryableTransportError(error) {
  return Boolean(
    error &&
      (error.code === "NETWORK_ERROR" || error.code === "TIMEOUT" || error.reason === "timeout")
  );
}

function retryDelay() {
  return RETRY_BASE_DELAY_MS + Math.floor(Math.random() * (RETRY_JITTER_MS + 1));
}

function waitForRetry(delayMs, callerSignal) {
  return new Promise((resolve, reject) => {
    if (callerSignal && callerSignal.aborted) {
      reject(cancellationError());
      return;
    }

    let timer = null;
    const cleanup = () => {
      if (timer !== null) {
        window.clearTimeout(timer);
        timer = null;
      }
      if (callerSignal) {
        callerSignal.removeEventListener("abort", onAbort);
      }
    };
    const finish = () => {
      cleanup();
      resolve();
    };
    const onAbort = () => {
      cleanup();
      reject(cancellationError());
    };

    timer = window.setTimeout(finish, delayMs);
    if (callerSignal) {
      callerSignal.addEventListener("abort", onAbort, { once: true });
    }
  });
}

/** One fetch attempt, including body retrieval, under one bounded timer. */
function fetchWithTimeout(url, init, timeoutMs, callerSignal) {
  const controller = new AbortController();
  let timedOut = false;
  let callerAborted = Boolean(callerSignal && callerSignal.aborted);

  if (callerAborted) {
    return Promise.reject(cancellationError());
  }

  const onCallerAbort = () => {
    callerAborted = true;
    controller.abort();
  };
  if (callerSignal) {
    callerSignal.addEventListener("abort", onCallerAbort, { once: true });
  }

  const timer = window.setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);

  return (async () => {
    try {
      const response = await fetch(url, { ...init, signal: controller.signal });
      const payload = await parseBodyWithSignal(response, controller.signal);
      if (timedOut) {
        throw timeoutError(timeoutMs);
      }
      if (callerAborted) {
        throw cancellationError();
      }
      return { response, payload };
    } catch (error) {
      if (timedOut) {
        throw timeoutError(timeoutMs);
      }
      if (callerAborted || (error && error.name === "AbortError")) {
        throw cancellationError();
      }
      if (error instanceof ApiError) {
        throw error;
      }
      throw networkError();
    } finally {
      window.clearTimeout(timer);
      if (callerSignal) {
        callerSignal.removeEventListener("abort", onCallerAbort);
      }
    }
  })();
}

function isEligiblePublicRead(method, path, options, hasBody, isFormData) {
  return (
    options.publicRead === true &&
    method === "GET" &&
    !hasBody &&
    !isFormData &&
    !/^\/auth\//.test(path) &&
    PUBLIC_READ_PATH.test(path)
  );
}

/**
 * Public read-only GETs get one cancellable retry and one overall deadline.
 * 429 is deliberately never automatically retried; the pages own cooldown.
 */
async function publicReadRequest(path, init, options) {
  const { timeoutMs, deadlineMs } = getPublicRequestConfig();
  const deadline = Date.now() + deadlineMs;

  for (let attempt = 0; attempt <= MAX_AUTOMATIC_RETRIES; attempt += 1) {
    const remaining = deadline - Date.now();
    if (remaining <= 0) {
      throw deadlineError();
    }

    let result;
    try {
      result = await fetchWithTimeout(
        requestUrl(path),
        init,
        Math.min(timeoutMs, remaining),
        options.signal
      );
    } catch (error) {
      if (isCancellation(error)) {
        throw error;
      }
      if (!isRetryableTransportError(error) || attempt === MAX_AUTOMATIC_RETRIES) {
        throw error;
      }
      const delay = retryDelay();
      if (Date.now() + delay >= deadline) {
        throw deadlineError();
      }
      await waitForRetry(delay, options.signal);
      continue;
    }

    const { response, payload } = result;
    captureCsrfToken(response);

    if (response.ok) {
      return payload;
    }

    const error = apiErrorFromResponse(response, payload);
    const statusRetryable = RETRYABLE_STATUSES.has(response.status);
    if (!statusRetryable || attempt === MAX_AUTOMATIC_RETRIES) {
      throw error;
    }

    const delay = retryDelay();
    if (Date.now() + delay >= deadline) {
      throw deadlineError();
    }
    await waitForRetry(delay, options.signal);
  }

  throw deadlineError();
}

async function requestCsrfToken() {
  let response;
  try {
    response = await fetch(requestUrl("/auth/csrf"), {
      method: "GET",
      headers: { Accept: "application/json" },
      credentials: "include",
      cache: "no-store"
    });
  } catch {
    throw networkError();
  }

  captureCsrfToken(response);
  const payload = await parseBody(response);

  if (!response.ok) {
    throw apiErrorFromResponse(response, payload);
  }

  if (!csrfToken) {
    throw new ApiError("The security token could not be obtained.", {
      status: response.status,
      code: "CSRF_UNAVAILABLE"
    });
  }
}

/**
 * Obtain the session-bound CSRF token when it is not already in memory.
 * Concurrent callers share one bootstrap request.
 */
export function ensureCsrfToken({ force = false } = {}) {
  if (csrfToken && !force) {
    return Promise.resolve();
  }
  if (csrfRequest) {
    return csrfRequest;
  }

  csrfRequest = requestCsrfToken().finally(() => {
    csrfRequest = null;
  });
  return csrfRequest;
}

/**
 * Perform an API request.
 *
 * Options:
 * - `method`, `headers`, `body`, `signal`, `cache`, `keepalive` are passed to
 *   Fetch with the exception of the private retry flags.
 * - `publicRead: true` opts an eligible GET into the bounded public-read
 *   timeout and one controlled retry. Only the public property bridge uses it.
 * - `csrf: false` opts a state-changing request out of CSRF injection. Only
 *   `POST /auth/register` uses this, because the backend explicitly exempts it
 *   and validates the exact Origin/Referer instead.
 * - `retryOnCsrfFailure: true` refreshes the token once after a `403`. Login and
 *   logout use it so a session that expired between page load and submit does
 *   not fail with a stale token.
 */
export async function apiRequest(path, options = {}) {
  const method = String(options.method ?? "GET").toUpperCase();
  const headers = new Headers(options.headers ?? {});
  const hasBody = options.body !== undefined && options.body !== null;
  const isFormData = hasBody && options.body instanceof FormData;
  const csrfProtected = !SAFE_METHODS.has(method) && options.csrf !== false;

  if (!headers.has("Accept")) {
    headers.set("Accept", "application/json");
  }
  if (hasBody && !isFormData && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (csrfProtected) {
    await ensureCsrfToken();
    if (csrfToken) {
      headers.set(CSRF_HEADER, csrfToken);
    }
  }

  const init = {
    method,
    headers,
    body: options.body,
    credentials: "include",
    cache: options.cache ?? "no-store",
    keepalive: options.keepalive === true
  };

  if (isEligiblePublicRead(method, path, options, hasBody, isFormData)) {
    return publicReadRequest(path, init, options);
  }

  let response;
  try {
    response = await fetch(requestUrl(path), { ...init, signal: options.signal });
  } catch (error) {
    if (error && error.name === "AbortError") {
      throw error;
    }
    throw networkError();
  }

  captureCsrfToken(response);
  const payload = await parseBody(response);

  if (!response.ok) {
    if (
      response.status === 403 &&
      csrfProtected &&
      options.retryOnCsrfFailure === true &&
      options.csrfRetried !== true
    ) {
      await ensureCsrfToken({ force: true });
      return apiRequest(path, { ...options, csrfRetried: true });
    }
    throw apiErrorFromResponse(response, payload);
  }

  return payload;
}

function formatDuration(ms) {
  const seconds = Math.max(1, Math.ceil(ms / 1000));
  if (seconds < 60) {
    return `${seconds} second${seconds === 1 ? "" : "s"}`;
  }
  const minutes = Math.ceil(seconds / 60);
  return `${minutes} minute${minutes === 1 ? "" : "s"}`;
}

export function isOfflineHint() {
  return typeof navigator !== "undefined" && navigator.onLine === false;
}

/** Shared, safe message selection for public property pages. */
export function describeApiFailure(error, options = {}) {
  if (isCancellation(error)) {
    return "";
  }
  if (error && error.reason === "configuration") {
    return describeConfigurationError();
  }
  if (error && (error.reason === "rate_limit" || error.status === 429)) {
    const cooldown =
      Number.isFinite(error.retryAfterMs) && error.retryAfterMs > 0
        ? error.retryAfterMs
        : FALLBACK_RATE_LIMIT_COOLDOWN_MS;
    return `Too many requests. Please wait about ${formatDuration(cooldown)} before trying again.`;
  }
  if (isTimeout(error)) {
    return "The property service took too long to respond. Please try again.";
  }
  if (isOfflineHint() || options.offlineHint === true) {
    return "You appear to be offline. Your search criteria are preserved; reconnect and try again.";
  }
  if (error && error.code === "NETWORK_ERROR") {
    return "The property service could not be reached. Please retry.";
  }
  if (error && error.reason === "server") {
    return "The property service is temporarily unavailable. Please try again.";
  }
  if (error && error.status === 404) {
    return "The requested property is not available.";
  }
  if (error && error.reason === "validation") {
    return "Some search criteria are invalid. Review your filters and try again.";
  }
  if (error && error.name === "InvalidApiResponseError") {
    return "The property service returned an unexpected response. Please try again.";
  }
  return "This content could not be loaded. Please try again.";
}

/**
 * Shared rate-limit cooldown state. The single timer notifies subscribers when
 * a known cooldown expires; it never issues a request to update a countdown.
 */
export function createRateLimitCooldown(onChange) {
  let until = 0;
  let timer = null;

  function clearTimer() {
    if (timer !== null) {
      window.clearTimeout(timer);
      timer = null;
    }
  }

  function notify(active) {
    if (typeof onChange === "function") {
      onChange(active);
    }
  }

  return {
    start(delayMs) {
      const duration =
        Number.isFinite(delayMs) && delayMs > 0
          ? Math.min(Math.floor(delayMs), MAX_RETRY_AFTER_MS)
          : FALLBACK_RATE_LIMIT_COOLDOWN_MS;
      clearTimer();
      until = Date.now() + duration;
      timer = window.setTimeout(() => {
        timer = null;
        until = 0;
        notify(false);
      }, duration);
      notify(true);
    },
    active() {
      return Date.now() < until;
    },
    remainingMs() {
      return Math.max(0, until - Date.now());
    },
    clear() {
      clearTimer();
      until = 0;
      notify(false);
    }
  };
}

/** Setup-only connectivity check. This is not a product feature. */
export function getHealth() {
  return apiRequest("/health");
}
