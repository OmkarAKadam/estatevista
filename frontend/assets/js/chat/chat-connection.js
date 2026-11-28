/**
 * Application-owned STOMP connection manager.
 *
 * The vendored UMD client supplies STOMP framing over a native WebSocket. This
 * module owns authentication headers, the three approved subscriptions, bounded
 * jittered reconnection and teardown. It never stores tokens or message text
 * outside memory and never replays a SEND automatically.
 */

import { ensureCsrfToken, getCsrfToken } from "../core/api.js";
import { getWebSocketUrl } from "../core/config.js";

export const CONNECTION_STATES = Object.freeze({
  CONNECTING: "connecting",
  CONNECTED: "connected",
  RECONNECTING: "reconnecting",
  DISCONNECTED: "disconnected",
  OFFLINE: "offline",
  AUTH_FAILURE: "auth_failure"
});

const DESTINATIONS = Object.freeze({
  messages: "/user/queue/messages",
  notifications: "/user/queue/notifications",
  errors: "/user/queue/errors",
  send: "/app/chat.send"
});

const MAX_ATTEMPTS = 6;
const BASE_DELAY_MS = 1000;
const MAX_DELAY_MS = 20000;
const JITTER_MS = 500;
const CONNECT_TIMEOUT_MS = 10000;
const HEARTBEAT_MS = 10000;

function delayFor(attempt) {
  const bounded = Math.min(BASE_DELAY_MS * 2 ** Math.max(0, attempt - 1), MAX_DELAY_MS);
  return bounded + Math.floor(Math.random() * (JITTER_MS + 1));
}

function stompClientClass() {
  const client = window.StompJs?.Client;
  if (typeof client !== "function") {
    throw new Error("The local STOMP client is unavailable.");
  }
  return client;
}

function parseFrame(payload) {
  if (!payload) {
    return null;
  }
  try {
    return JSON.parse(payload);
  } catch {
    return null;
  }
}

function isAuthFrame(frame) {
  const reason = String(frame?.reason ?? frame?.code ?? "").toUpperCase();
  return /CSRF|UNAUTH|SESSION|EXPIRED|LOGIN|ORIGIN|FORBIDDEN|DENIED/.test(reason);
}

/**
 * Create one connection for one authenticated chat page. `onEvent` receives
 * normalized frame bodies; `onStatus` receives state transitions; `onReconnect`
 * is called only after an unexpected closure followed by a successful connect.
 */
export function createChatConnection({ onEvent, onStatus, onReconnect } = {}) {
  let client = null;
  let state = CONNECTION_STATES.CONNECTING;
  let attempt = 0;
  let timer = null;
  let stopped = false;
  let everConnected = false;
  const subscriptions = new Set();

  function setState(next) {
    if (state === next) {
      return;
    }
    state = next;
    try {
      onStatus?.(next);
    } catch {
      /* A presentation listener must never break transport state. */
    }
  }

  function clearTimer() {
    if (timer !== null) {
      window.clearTimeout(timer);
      timer = null;
    }
  }

  function teardownClient() {
    subscriptions.clear();
    if (!client) {
      return;
    }
    const previous = client;
    client = null;
    try {
      previous.deactivate();
    } catch {
      /* A half-open client is safe to discard; a new one is built on retry. */
    }
  }

  function stop({ authFailure = false } = {}) {
    stopped = true;
    clearTimer();
    teardownClient();
    setState(authFailure ? CONNECTION_STATES.AUTH_FAILURE : CONNECTION_STATES.DISCONNECTED);
  }

  function handleEvent(destination, frame) {
    if (!frame || typeof frame !== "object") {
      return;
    }
    try {
      onEvent?.({ destination, frame });
    } catch {
      /* A rendering failure must not close the transport. */
    }
  }

  function subscribeQueues(activeClient) {
    subscriptions.clear();
    [
      DESTINATIONS.messages,
      DESTINATIONS.notifications,
      DESTINATIONS.errors
    ].forEach((destination) => {
      const subscription = activeClient.subscribe(destination, (message) => {
        if (client !== activeClient) {
          return;
        }
        handleEvent(destination, parseFrame(message.body));
      });
      subscriptions.add(subscription);
    });
  }

  function scheduleRetry() {
    if (stopped || state === CONNECTION_STATES.AUTH_FAILURE || state === CONNECTION_STATES.OFFLINE) {
      return;
    }
    if (attempt >= MAX_ATTEMPTS) {
      setState(CONNECTION_STATES.DISCONNECTED);
      return;
    }
    setState(CONNECTION_STATES.RECONNECTING);
    clearTimer();
    const wait = delayFor(attempt);
    attempt += 1;
    timer = window.setTimeout(() => {
      timer = null;
      void open();
    }, wait);
  }

  function handleFailure(frame, closeCode) {
    const parsed = parseFrame(frame);
    if (isAuthFrame(parsed) || closeCode === 1008 || closeCode === 4003) {
      stop({ authFailure: true });
      return;
    }
    scheduleRetry();
  }

  async function open() {
    if (stopped || state === CONNECTION_STATES.OFFLINE) {
      return;
    }
    if (state !== CONNECTION_STATES.RECONNECTING && state !== CONNECTION_STATES.DISCONNECTED) {
      setState(CONNECTION_STATES.CONNECTING);
    }

    teardownClient();

    let token;
    try {
      await ensureCsrfToken({ force: true });
      token = getCsrfToken();
    } catch {
      scheduleRetry();
      return;
    }
    if (stopped || !token) {
      scheduleRetry();
      return;
    }

    try {
      const Client = stompClientClass();
      const reconnecting = everConnected;
      client = new Client({
        webSocketFactory: () => new WebSocket(getWebSocketUrl()),
        reconnectDelay: 0,
        connectionTimeout: CONNECT_TIMEOUT_MS,
        heartbeatIncoming: HEARTBEAT_MS,
        heartbeatOutgoing: HEARTBEAT_MS,
        connectHeaders: { "X-CSRF-TOKEN": token }
      });
      const active = client;

      client.onConnect = () => {
        if (stopped || client !== active) {
          return;
        }
        attempt = 0;
        const wasReconnect = everConnected;
        everConnected = true;
        setState(CONNECTION_STATES.CONNECTED);
        subscribeQueues(active);
        if (wasReconnect && reconnecting) {
          try {
            onReconnect?.();
          } catch {
            /* REST recovery is retried on the next reconnect if it fails. */
          }
        }
      };

      client.onStompError = (frame) => {
        if (stopped || client !== active) {
          return;
        }
        handleFailure(frame?.body ?? "", null);
      };

      client.onWebSocketClose = (event) => {
        if (stopped || client !== active) {
          return;
        }
        handleFailure("", event?.code ?? null);
      };

      client.onWebSocketError = () => {
        if (!stopped && client === active) {
          scheduleRetry();
        }
      };

      client.activate();
    } catch {
      scheduleRetry();
    }
  }

  function handleOnline() {
    if (stopped || state === CONNECTION_STATES.AUTH_FAILURE) {
      return;
    }
    attempt = 0;
    setState(CONNECTION_STATES.CONNECTING);
    void open();
  }

  function handleOffline() {
    if (stopped || state === CONNECTION_STATES.AUTH_FAILURE) {
      return;
    }
    clearTimer();
    teardownClient();
    setState(CONNECTION_STATES.OFFLINE);
  }

  window.addEventListener("online", handleOnline);
  window.addEventListener("offline", handleOffline);

  function send({ conversationId, content, idempotencyKey }) {
    if (state !== CONNECTION_STATES.CONNECTED || !client?.connected) {
      return false;
    }
    const frame = {
      conversationId,
      content,
      idempotencyKey
    };
    try {
      client.publish({
        destination: DESTINATIONS.send,
        body: JSON.stringify(frame),
        headers: { "content-type": "application/json" }
      });
      return true;
    } catch {
      return false;
    }
  }

  function retry() {
    if (state === CONNECTION_STATES.AUTH_FAILURE) {
      return;
    }
    stopped = false;
    attempt = 0;
    clearTimer();
    setState(CONNECTION_STATES.CONNECTING);
    void open();
  }

  return Object.freeze({
    start: () => {
      stopped = false;
      attempt = 0;
      void open();
    },
    retry,
    send,
    stop,
    getState: () => state,
    isConnected: () => state === CONNECTION_STATES.CONNECTED && Boolean(client?.connected),
    destroy: () => {
      window.removeEventListener("online", handleOnline);
      window.removeEventListener("offline", handleOffline);
      stop();
    }
  });
}

export { DESTINATIONS };
