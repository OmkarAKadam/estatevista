import { getApiBaseUrl, getWebSocketUrl } from "../assets/js/core/config.js";
import { getHealth } from "../assets/js/core/api.js";

const originElement = document.querySelector("#frontend-origin");
const apiElement = document.querySelector("#api-base-url");
const websocketElement = document.querySelector("#websocket-url");
const healthElement = document.querySelector("#health-status");
const outputElement = document.querySelector("#output");

originElement.textContent = window.location.origin;

try {
  apiElement.textContent = getApiBaseUrl();
  websocketElement.textContent = getWebSocketUrl();
} catch (error) {
  apiElement.textContent = "Not configured";
  websocketElement.textContent = "Not configured";
  healthElement.textContent = "FAIL";
  healthElement.className = "fail";
  outputElement.textContent = error.message;
  throw error;
}

try {
  const health = await getHealth();
  healthElement.textContent = "PASS — API reachable";
  healthElement.className = "pass";
  outputElement.textContent = JSON.stringify(health, null, 2);
} catch (error) {
  healthElement.textContent = `FAIL — ${error.status ?? "network error"}`;
  healthElement.className = "fail";
  outputElement.textContent =
    error.payload === undefined
      ? error.message
      : JSON.stringify(error.payload, null, 2);
}
