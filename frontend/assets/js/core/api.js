/**
 * Shared REST client for the EstateVista V2 API.
 *
 * - Uses the Fetch API directly against the separately hosted API.
 * - Sends the host-only session cookie with `credentials: "include"`.
 * - Keeps the session-bound CSRF token in memory only. The token is never
 *   written to a cookie, localStorage, sessionStorage, a URL, or a log.
 * - Contains no business endpoints; only the setup health check is exposed.
 */

import { getApiBaseUrl } from "./config.js";

let csrfToken = null;

export function setCsrfToken(token) {
  csrfToken = typeof token === "string" && token.length > 0 ? token : null;
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

export async function apiRequest(path, options = {}) {
  const method = (options.method ?? "GET").toUpperCase();
  const headers = new Headers(options.headers ?? {});
  const hasBody = options.body !== undefined && options.body !== null;
  const isFormData = hasBody && options.body instanceof FormData;

  if (!headers.has("Accept")) {
    headers.set("Accept", "application/json");
  }
  if (hasBody && !isFormData && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (csrfToken && !["GET", "HEAD", "OPTIONS"].includes(method)) {
    headers.set("X-CSRF-TOKEN", csrfToken);
  }

  const response = await fetch(buildUrl(path), {
    ...options,
    method,
    headers,
    credentials: "include"
  });

  const contentType = response.headers.get("Content-Type") ?? "";
  const payload = contentType.includes("application/json")
    ? await response.json()
    : await response.text();

  if (!response.ok) {
    const message =
      payload && typeof payload === "object" && "message" in payload
        ? String(payload.message)
        : `Request failed with status ${response.status}`;
    const error = new Error(message);
    error.status = response.status;
    error.payload = payload;
    throw error;
  }

  return payload;
}

/**
 * Setup-only connectivity check. This is not a product feature.
 */
export function getHealth() {
  return apiRequest("/health");
}
