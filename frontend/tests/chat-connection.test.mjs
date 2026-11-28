/**
 * Dependency-free deterministic checks for the application-owned chat
 * transport. Run with: node --test frontend/tests/chat-connection.test.mjs
 *
 * A fake vendored STOMP client and controlled timers exercise native-WebSocket
 * configuration, subscription restrictions, bounded reconnect, auth failure
 * and send payloads without a browser, backend or network.
 */
import test, { beforeEach } from "node:test";
import assert from "node:assert/strict";

const CONVERSATION = "11111111-1111-1111-8111-111111111111";
const KEY = "22222222-2222-2222-8222-222222222222";

const timerCallbacks = new Map();
let nextTimer = 1;

function installTimers() {
  timerCallbacks.clear();
  nextTimer = 1;
  window.setTimeout = (callback) => {
    const id = nextTimer++;
    timerCallbacks.set(id, callback);
    return id;
  };
  window.clearTimeout = (id) => {
    timerCallbacks.delete(id);
  };
}

function fireTimers() {
  const callbacks = [...timerCallbacks.values()];
  timerCallbacks.clear();
  callbacks.forEach((callback) => callback());
}

const windowListeners = new Map();
globalThis.window = {
  ESTATEVISTA_CONFIG: {
    apiBaseUrl: "http://localhost:8087/api/v1",
    webSocketUrl: "ws://localhost:8087/ws"
  },
  location: { hostname: "localhost", origin: "http://localhost:5504" },
  setTimeout: () => 0,
  clearTimeout: () => {},
  addEventListener(type, listener) {
    const list = windowListeners.get(type) ?? [];
    list.push(listener);
    windowListeners.set(type, list);
  },
  removeEventListener(type, listener) {
    const list = windowListeners.get(type) ?? [];
    windowListeners.set(
      type,
      list.filter((item) => item !== listener)
    );
  }
};
installTimers();

globalThis.WebSocket = class FakeWebSocket {};

const clients = [];
class FakeStompClient {
  constructor(options) {
    this.options = options;
    this.connected = false;
    this.deactivated = false;
    this.subscriptions = [];
    this.publishes = [];
    this.onConnect = options.onConnect ?? null;
    this.onStompError = options.onStompError ?? null;
    this.onWebSocketClose = options.onWebSocketClose ?? null;
    this.onWebSocketError = options.onWebSocketError ?? null;
    clients.push(this);
  }

  activate() {
    this.activated = true;
  }

  deactivate() {
    this.deactivated = true;
    this.connected = false;
    this.onWebSocketClose?.({ code: 1000 });
  }

  subscribe(destination, callback) {
    const subscription = { destination, callback };
    this.subscriptions.push(subscription);
    return subscription;
  }

  publish(frame) {
    this.publishes.push(frame);
  }
}
window.StompJs = { Client: FakeStompClient };

globalThis.fetch = async (input) => {
  const url = new URL(typeof input === "string" ? input : input.url);
  if (url.pathname === "/api/v1/auth/csrf") {
    return new Response("{}", {
      status: 200,
      headers: { "X-CSRF-TOKEN": "live-session-token", "Content-Type": "application/json" }
    });
  }
  return new Response(JSON.stringify({ success: true, data: {} }), {
    status: 200,
    headers: { "Content-Type": "application/json" }
  });
};

const { createChatConnection, CONNECTION_STATES } = await import(
  "../assets/js/chat/chat-connection.js"
);

function dispatch(type, payload = {}) {
  (windowListeners.get(type) ?? []).forEach((listener) => listener(payload));
}

function connect(client) {
  client.connected = true;
  client.onConnect?.();
}

function tick() {
  return new Promise((resolve) => setImmediate(resolve));
}

function fixture() {
  const statuses = [];
  const events = [];
  let reconnects = 0;
  const connection = createChatConnection({
    onStatus: (status) => statuses.push(status),
    onEvent: (event) => events.push(event),
    onReconnect: () => {
      reconnects += 1;
    }
  });
  return { connection, statuses, events, getReconnects: () => reconnects };
}

beforeEach(() => {
  clients.length = 0;
  installTimers();
  windowListeners.clear();
});

test("CONNECT uses the local client, native WebSocket URL and in-memory CSRF header", async () => {
  const { connection, statuses } = fixture();
  connection.start();
  await tick();

  assert.equal(clients.length, 1);
  const client = clients[0];
  assert.equal(client.options.reconnectDelay, 0);
  assert.equal(client.options.webSocketFactory() instanceof WebSocket, true);
  assert.equal(client.options.connectHeaders["X-CSRF-TOKEN"], "live-session-token");
  assert.equal(Object.keys(client.options.connectHeaders).length, 1);
  assert.equal(client.options.heartbeatIncoming, 10000);
  assert.equal(client.options.heartbeatOutgoing, 10000);

  connect(client);
  assert.equal(connection.getState(), CONNECTION_STATES.CONNECTED);
  assert.equal(statuses.at(-1), CONNECTION_STATES.CONNECTED);
  assert.deepEqual(
    client.subscriptions.map((item) => item.destination),
    [
      "/user/queue/messages",
      "/user/queue/notifications",
      "/user/queue/errors"
    ]
  );
  connection.destroy();
});

test("send publishes only conversationId, content and idempotencyKey", async () => {
  const { connection } = fixture();
  connection.start();
  await tick();
  const client = clients[0];
  connect(client);

  const sent = connection.send({
    conversationId: CONVERSATION,
    content: "<script>alert(1)</script>\nHello",
    idempotencyKey: KEY,
    senderId: "spoofed",
    sentAt: "spoofed"
  });
  assert.equal(sent, true);
  const published = client.publishes[0];
  assert.equal(published.destination, "/app/chat.send");
  assert.deepEqual(Object.keys(JSON.parse(published.body)).sort(), [
    "content",
    "conversationId",
    "idempotencyKey"
  ]);
  assert.equal(JSON.parse(published.body).senderId, undefined);
  connection.destroy();
});

test("unexpected close schedules one bounded retry and invokes onReconnect once", async () => {
  const { connection, statuses, getReconnects } = fixture();
  connection.start();
  await tick();
  const first = clients[0];
  connect(first);

  first.onWebSocketClose({ code: 1006 });
  assert.equal(connection.getState(), CONNECTION_STATES.RECONNECTING);
  assert.equal(timerCallbacks.size, 1);
  assert.equal(getReconnects(), 0);

  fireTimers();
  await tick();
  assert.equal(clients.length, 2);
  connect(clients[1]);
  assert.equal(connection.getState(), CONNECTION_STATES.CONNECTED);
  assert.equal(getReconnects(), 1);
  assert.ok(statuses.includes(CONNECTION_STATES.RECONNECTING));
  connection.destroy();
});

test("stale client callbacks cannot alter the active connection", async () => {
  const { connection, statuses, getReconnects } = fixture();
  connection.start();
  await tick();
  const first = clients[0];
  connect(first);

  first.onWebSocketClose({ code: 1006 });
  fireTimers();
  await tick();
  const second = clients[1];
  connect(second);
  const connectedBefore = statuses.filter(
    (status) => status === CONNECTION_STATES.CONNECTED).length;

  first.onConnect?.();
  first.onWebSocketClose({ code: 1006 });
  first.onStompError?.({ body: "ERROR\nmessage:unexpected\n\n" });
  first.onWebSocketError?.();

  assert.equal(connection.getState(), CONNECTION_STATES.CONNECTED);
  assert.equal(statuses.filter((status) => status === CONNECTION_STATES.CONNECTED).length,
    connectedBefore);
  assert.equal(timerCallbacks.size, 0);
  assert.equal(getReconnects(), 1);
  assert.equal(connection.send({
    conversationId: CONVERSATION,
    content: "still active",
    idempotencyKey: KEY
  }), true);
  assert.equal(second.publishes.length, 1);
  connection.destroy();
});

test("authoritative auth close stops retrying and refuses further sends", async () => {
  const { connection } = fixture();
  connection.start();
  await tick();
  const client = clients[0];
  connect(client);

  client.onWebSocketClose({ code: 4003 });
  assert.equal(connection.getState(), CONNECTION_STATES.AUTH_FAILURE);
  assert.equal(timerCallbacks.size, 0);
  assert.equal(connection.send({ conversationId: CONVERSATION, content: "Hi", idempotencyKey: KEY }), false);

  fireTimers();
  await tick();
  assert.equal(clients.length, 1);
  connection.destroy();
});

test("offline pauses transport and online resumes with a fresh token", async () => {
  const { connection } = fixture();
  connection.start();
  await tick();
  const first = clients[0];
  connect(first);

  dispatch("offline");
  assert.equal(connection.getState(), CONNECTION_STATES.OFFLINE);
  assert.equal(first.deactivated, true);

  dispatch("online");
  await tick();
  assert.equal(clients.length, 2);
  connect(clients[1]);
  assert.equal(connection.getState(), CONNECTION_STATES.CONNECTED);
  assert.equal(clients[1].options.connectHeaders["X-CSRF-TOKEN"], "live-session-token");
  connection.destroy();
});
