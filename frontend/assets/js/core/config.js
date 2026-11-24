/**
 * EstateVista V2 frontend runtime configuration.
 *
 * The frontend is static and hosted separately from the API. Local development
 * defaults to the API on port 8080. Non-local deployments must inject an
 * explicit API base URL before the module scripts run, because the approved
 * architecture has no frontend-to-API proxy and no production hostname is
 * committed to the repository.
 *
 * Example (in the deployed HTML, before the module scripts):
 *   window.ESTATEVISTA_CONFIG = {
 *     apiBaseUrl: "https://api.example.com/api/v1",
 *     webSocketUrl: "wss://api.example.com/ws"
 *   };
 *
 * Optional public-read resilience settings (bounded here):
 *   publicRequestTimeoutMs: 15000
 *   publicRequestDeadlineMs: 30000
 */

const LOCAL_HOSTNAMES = new Set(["localhost", "127.0.0.1"]);
const LOCAL_API_PORT = "8080";
const API_BASE_PATH = "/api/v1";
const WEBSOCKET_PATH = "/ws";

const DEFAULT_PUBLIC_REQUEST_TIMEOUT_MS = 15000;
const MIN_PUBLIC_REQUEST_TIMEOUT_MS = 1000;
const MAX_PUBLIC_REQUEST_TIMEOUT_MS = 60000;
const DEFAULT_PUBLIC_REQUEST_DEADLINE_MS = 30000;
const MAX_PUBLIC_REQUEST_DEADLINE_MS = 60000;

function runtimeConfig() {
  return window.ESTATEVISTA_CONFIG ?? {};
}

function trimTrailingSlash(value) {
  return value.replace(/\/+$/, "");
}

function configurationError(message) {
  const error = new Error(message);
  error.name = "EstateVistaConfigurationError";
  return error;
}

/**
 * Validate the configured API URL against the approved architecture:
 * an absolute http(s) URL with no credentials/query/fragment, ending in
 * /api/v1. HTTPS is required outside localhost. This is public configuration
 * only; it never contains a secret or credential.
 */
function validateApiBaseUrl(value) {
  if (typeof value !== "string" || value.trim() === "") {
    throw configurationError(
      "EstateVista API base URL is not configured. " +
        "Set window.ESTATEVISTA_CONFIG.apiBaseUrl to the approved API origin and /api/v1 path."
    );
  }

  let url;
  try {
    url = new URL(value.trim());
  } catch {
    throw configurationError(
      "The configured EstateVista API base URL is not a valid absolute URL."
    );
  }

  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw configurationError("The configured EstateVista API base URL must use http or https.");
  }
  if (url.username || url.password) {
    throw configurationError("The configured EstateVista API base URL must not contain credentials.");
  }
  if (url.search || url.hash) {
    throw configurationError("The configured EstateVista API base URL must not contain a query or fragment.");
  }
  if (trimTrailingSlash(url.pathname) !== API_BASE_PATH) {
    throw configurationError(
      "The configured EstateVista API base URL must end in /api/v1."
    );
  }
  if (url.protocol === "http:" && !LOCAL_HOSTNAMES.has(url.hostname)) {
    throw configurationError(
      "The configured EstateVista API base URL must use https outside local development."
    );
  }

  return `${url.origin}${API_BASE_PATH}`;
}

/**
 * Keep the local API on the same hostname as the page. Using `localhost` for
 * the page and `127.0.0.1` for the API makes the session cookie cross-site, and
 * a `SameSite=Lax` cookie is then not sent on credentialed fetches.
 */
function localApiBaseUrl() {
  return `http://${window.location.hostname}:${LOCAL_API_PORT}${API_BASE_PATH}`;
}

function boundedInteger(value, fallback, minimum, maximum, label) {
  if (value === undefined || value === null || value === "") {
    return fallback;
  }
  const number = Number(value);
  if (!Number.isFinite(number) || !Number.isInteger(number) || number < minimum || number > maximum) {
    throw configurationError(
      `${label} must be a whole number between ${minimum} and ${maximum} milliseconds.`
    );
  }
  return number;
}

export function getApiBaseUrl() {
  const configured = runtimeConfig().apiBaseUrl;
  if (configured) {
    return validateApiBaseUrl(configured);
  }

  if (LOCAL_HOSTNAMES.has(window.location.hostname)) {
    return localApiBaseUrl();
  }

  throw configurationError(
    "EstateVista API base URL is not configured. " +
      "Set window.ESTATEVISTA_CONFIG.apiBaseUrl for this deployment."
  );
}

/**
 * Bounded public-read request timing. A per-attempt timeout covers fetch and
 * body parsing; the deadline covers all automatic retries for one call.
 */
export function getPublicRequestConfig() {
  const configured = runtimeConfig();
  const timeoutMs = boundedInteger(
    configured.publicRequestTimeoutMs,
    DEFAULT_PUBLIC_REQUEST_TIMEOUT_MS,
    MIN_PUBLIC_REQUEST_TIMEOUT_MS,
    MAX_PUBLIC_REQUEST_TIMEOUT_MS,
    "publicRequestTimeoutMs"
  );
  const deadlineMs = boundedInteger(
    configured.publicRequestDeadlineMs,
    Math.max(DEFAULT_PUBLIC_REQUEST_DEADLINE_MS, timeoutMs),
    timeoutMs,
    MAX_PUBLIC_REQUEST_DEADLINE_MS,
    "publicRequestDeadlineMs"
  );
  return { timeoutMs, deadlineMs };
}

export function getWebSocketUrl() {
  const configured = runtimeConfig().webSocketUrl;
  if (configured) {
    return configured;
  }

  const url = new URL(getApiBaseUrl());
  url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
  url.pathname = WEBSOCKET_PATH;
  url.search = "";
  url.hash = "";
  return url.toString();
}

export function isLocalDevelopment() {
  return LOCAL_HOSTNAMES.has(window.location.hostname);
}

/** Safe, deployment-oriented message for configuration failures. */
export function describeConfigurationError() {
  return (
    "Property services are not configured for this deployment. " +
    "Ask the site administrator to provide a valid API base URL (HTTPS and /api/v1 outside localhost)."
  );
}
