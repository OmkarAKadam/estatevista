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
 */

import { getApiBaseUrl } from "./config.js";

const CSRF_HEADER = "X-CSRF-TOKEN";
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

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
  constructor(message, { status = 0, code = null, details = [], payload = null } = {}) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.details = Array.isArray(details) ? details : [];
    this.payload = payload;
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

function apiErrorFromResponse(response, payload) {
  const fallback = `Request failed with status ${response.status}`;
  const envelope =
    payload && typeof payload === "object" && payload.error && typeof payload.error === "object"
      ? payload.error
      : null;

  if (!envelope) {
    return new ApiError(fallback, { status: response.status, payload });
  }

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

  return new ApiError(message, {
    status: response.status,
    code,
    details,
    payload
  });
}

function networkError() {
  return new ApiError("The server could not be reached.", {
    status: 0,
    code: "NETWORK_ERROR"
  });
}

async function requestCsrfToken() {
  let response;
  try {
    response = await fetch(buildUrl("/auth/csrf"), {
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
 *   Fetch with the exception of the private retry flag.
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

  let response;
  try {
    response = await fetch(buildUrl(path), {
      method,
      headers,
      body: options.body,
      credentials: "include",
      cache: options.cache ?? "no-store",
      signal: options.signal,
      keepalive: options.keepalive === true
    });
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

/**
 * Setup-only connectivity check. This is not a product feature.
 */
export function getHealth() {
  return apiRequest("/health");
}
