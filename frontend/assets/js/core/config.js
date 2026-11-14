/**
 * EstateVista V2 frontend runtime configuration.
 *
 * The frontend is static and hosted separately from the API. Local development
 * defaults to the API on port 8080. Production must inject an explicit API base
 * URL before this module is used, because the approved architecture has no
 * frontend-to-API proxy and no production hostname is committed to the
 * repository.
 *
 * Example (in the deployed HTML, before the module scripts):
 *   window.ESTATEVISTA_CONFIG = {
 *     apiBaseUrl: "https://api.example.com/api/v1",
 *     webSocketUrl: "wss://api.example.com/ws"
 *   };
 */

const LOCAL_HOSTNAMES = new Set(["localhost", "127.0.0.1"]);
const LOCAL_API_BASE_URL = "http://localhost:8080/api/v1";
const WEBSOCKET_PATH = "/ws";

function runtimeConfig() {
  return window.ESTATEVISTA_CONFIG ?? {};
}

function trimTrailingSlash(value) {
  return value.replace(/\/+$/, "");
}

export function getApiBaseUrl() {
  const configured = runtimeConfig().apiBaseUrl;
  if (configured) {
    return trimTrailingSlash(configured);
  }

  if (LOCAL_HOSTNAMES.has(window.location.hostname)) {
    return LOCAL_API_BASE_URL;
  }

  throw new Error(
    "EstateVista API base URL is not configured. " +
      "Set window.ESTATEVISTA_CONFIG.apiBaseUrl for this deployment."
  );
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
