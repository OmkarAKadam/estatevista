/**
 * Dependency-free deterministic checks for the Stage 7.5/7.6 chat service.
 * Run with: node --test frontend/tests/chat-service.test.mjs
 *
 * The real ES modules are imported with minimal window globals. Global fetch
 * is replaced by a recorder; no browser, backend, database or network is used.
 */
import test, { beforeEach } from "node:test";
import assert from "node:assert/strict";

const C1 = "11111111-1111-1111-8111-111111111111";
const C2 = "22222222-2222-2222-8222-222222222222";
const M1 = "33333333-3333-3333-8333-333333333333";
const M2 = "44444444-4444-4444-8444-444444444444";
const P1 = "55555555-5555-5555-8555-555555555555";
const KEY = "66666666-6666-6666-8666-666666666666";

globalThis.window = {
  ESTATEVISTA_CONFIG: { apiBaseUrl: "http://localhost:8080/api/v1" },
  location: {
    hostname: "localhost",
    origin: "http://localhost:5500",
    href: "http://localhost:5500/",
    pathname: "/",
    search: ""
  },
  setTimeout: globalThis.setTimeout,
  clearTimeout: globalThis.clearTimeout,
  addEventListener() {},
  removeEventListener() {}
};

const chatService = await import("../assets/js/chat/chat-service.js");
const format = await import("../assets/js/chat/chat-format.js");
const { setAuthenticated, setAnonymous } = await import(
  "../assets/js/auth/auth-state.js"
);
const { clearCsrfToken } = await import("../assets/js/core/api.js");

const BUYER = Object.freeze({
  id: "77777777-7777-7777-8777-777777777777",
  email: "buyer@example.com",
  role: "BUYER",
  firstName: "Stage",
  lastName: "Seven",
  accountStatus: "ACTIVE"
});

const conversationPayload = Object.freeze({
  conversationId: C1,
  propertyId: P1,
  propertyTitle: "Riverside Home",
  propertyStatus: "PUBLISHED",
  buyerId: BUYER.id,
  assignedAgentId: C2,
  unreadCount: 2,
  lastMessageId: M1,
  lastMessageAt: "2026-11-28T10:00:00Z",
  createdAt: "2026-11-28T09:00:00Z"
});

const messagePayload = Object.freeze({
  id: M1,
  conversationId: C1,
  senderId: C2,
  messageType: "TEXT",
  content: "Hello",
  idempotencyKey: KEY,
  sentAt: "2026-11-27T10:00:00Z"
});

let calls = [];
let responder = () => json({ success: true, data: null });

function installFetch(handler) {
  responder = handler;
  globalThis.fetch = async (input, init = {}) => {
    const url = new URL(typeof input === "string" ? input : input.url);
    calls.push({
      path: url.pathname + url.search,
      pathname: url.pathname,
      params: url.searchParams,
      method: String(init.method ?? "GET").toUpperCase(),
      body: init.body ? JSON.parse(init.body) : null,
      headers: new Headers(init.headers ?? {}),
      signal: init.signal
    });
    const response = responder(url, init);
    if (response instanceof Error) {
      throw response;
    }
    return response;
  };
}

function json(body, status = 200, headers = {}) {
  const finalHeaders = new Headers(headers);
  if (!finalHeaders.has("Content-Type")) {
    finalHeaders.set("Content-Type", "application/json");
  }
  return new Response(JSON.stringify(body), { status, headers: finalHeaders });
}

function csrf(url) {
  if (url.pathname === "/api/v1/auth/csrf") {
    return json({ success: true, data: {} }, 200, {
      "X-CSRF-TOKEN": "test-session-token"
    });
  }
  return null;
}

function envelope(data, meta) {
  return meta ? { success: true, data, meta } : { success: true, data };
}

beforeEach(() => {
  calls = [];
  clearCsrfToken();
  setAnonymous();
  setAuthenticated(BUYER);
  installFetch((url) => csrf(url) ?? json(envelope([])));
});

test("listConversations uses the exact REST path without private query data", async () => {
  installFetch((url) => csrf(url) ?? json(envelope([conversationPayload])));
  const result = await chatService.listConversations("BUYER");
  assert.equal(result.ok, true);
  assert.equal(result.data[0].conversationId, C1);
  assert.equal(result.data[0].unreadCount, 2);
  assert.deepEqual(
    calls.map((call) => call.pathname),
    ["/api/v1/conversations"]
  );
  assert.equal(calls[0].method, "GET");
  assert.equal(calls[0].path.includes("buyerId"), false);
});

test("getConversation and getMessages encode identifiers and cursor direction", async () => {
  await chatService.getConversation("BUYER", C1);
  await chatService.getMessages("BUYER", C1, { limit: 30, before: "cursor|older" });
  await chatService.getMessages("BUYER", C1, { limit: 100, after: "cursor|newer" });

  assert.equal(calls[0].path, `/api/v1/conversations/${C1}`);
  assert.equal(
    calls[1].path,
    `/api/v1/conversations/${C1}/messages?limit=30&before=cursor%7Colder`
  );
  assert.equal(
    calls[2].path,
    `/api/v1/conversations/${C1}/messages?limit=100&after=cursor%7Cnewer`
  );
  assert.equal(calls[2].method, "GET");
});

test("markRead sends only lastReadMessageId and uses PATCH", async () => {
  installFetch((url) => csrf(url) ?? json(envelope({ unreadCount: 0 })));
  const result = await chatService.markRead("BUYER", C1, M1);
  assert.equal(result.ok, true);
  const patch = calls.find((call) => call.method === "PATCH");
  assert.equal(patch.pathname, `/api/v1/conversations/${C1}/read-state`);
  assert.deepEqual(patch.body, { lastReadMessageId: M1 });
  assert.equal(Object.keys(patch.body).length, 1);
  assert.equal(patch.headers.get("x-csrf-token"), "test-session-token");
});

test("createConversation posts no body and does not send an initial message", async () => {
  installFetch((url) => csrf(url) ?? json(envelope(conversationPayload), 201));
  const result = await chatService.createConversation(P1);
  assert.equal(result.ok, true);
  const post = calls.find((call) => call.pathname === `/api/v1/properties/${P1}/conversations`);
  assert.equal(post.method, "POST");
  assert.equal(post.body, null);
  assert.equal(JSON.stringify(calls).includes("initialMessage"), false);
});

test("non-participants and invalid identifiers make no protected request", async () => {
  setAnonymous();
  const anonymous = await chatService.listConversations("BUYER");
  assert.equal(anonymous.ok, false);
  assert.equal(calls.length, 0);

  const { setAuthenticated: authenticate } = await import(
    "../assets/js/auth/auth-state.js"
  );
  authenticate({ ...BUYER, role: "ADMIN" });
  const admin = await chatService.getConversation("ADMIN", C1);
  assert.equal(admin.ok, false);
  assert.equal(calls.length, 0);

  setAuthenticated(BUYER);
  const invalid = await chatService.getMessages("BUYER", "not-a-uuid");
  assert.equal(invalid.ok, false);
  assert.equal(invalid.reason, "not_found");
  assert.equal(calls.length, 0);
});

test("history normalization requires success, data, meta and valid message rows", () => {
  assert.equal(
    format.normalizeMessagePage({
      success: true,
      data: [messagePayload, { ...messagePayload, id: "bad" }],
      meta: { limit: 30, nextCursor: "cursor", hasMore: true }
    }).messages.length,
    1
  );
  assert.equal(
    format.normalizeMessagePage({ success: true, data: [messagePayload] }),
    null
  );
  assert.equal(
    format.buildHistoryQuery({ limit: 30, before: "a b", after: null }),
    "limit=30&before=a+b"
  );
  assert.equal(
    format.describeChatError("MESSAGE_DENIED").includes("MESSAGE_DENIED"),
    false
  );
  assert.match(format.describeChatError("rate_limit"), /too quickly/i);
});

test("merge deduplicates by server id and idempotency key, then orders chronologically", () => {
  const older = format.normalizeMessage({
    ...messagePayload,
    id: M2,
    idempotencyKey: null,
    sentAt: "2026-11-27T09:00:00Z"
  });
  const newer = format.normalizeMessage(messagePayload);
  const duplicateId = format.normalizeMessage({ ...messagePayload, content: "spoof" });
  const duplicateKey = format.normalizeMessage({
    ...messagePayload,
    id: "88888888-8888-8888-8888-888888888888",
    content: "spoof"
  });

  const merged = format.mergeMessages([newer, older], [duplicateId, duplicateKey]);
  assert.deepEqual(merged.map((message) => message.id), [M2, M1]);
  assert.equal(merged[1].content, "Hello");
});

test("ack reconciliation correlates by key and never invents delivery state", () => {
  const pending = format.createPendingMessage({
    conversationId: C1,
    content: "Hello",
    idempotencyKey: KEY,
    clientId: KEY,
    senderId: BUYER.id,
    sentAt: "2026-11-28T10:00:00Z"
  });
  const reconciled = format.reconcileAck(pending, {
    id: M1,
    conversationId: C1,
    idempotencyKey: KEY,
    sentAt: "2026-11-28T10:00:05Z"
  });
  assert.equal(reconciled.id, M1);
  assert.equal(reconciled.idempotencyKey, KEY);
  assert.equal(reconciled.pending, false);
  assert.equal(reconciled.failed, false);
  assert.equal("delivered" in reconciled, false);
  assert.equal("read" in reconciled, false);
  assert.equal(format.reconcileAck(pending, { id: "bad" }).id, pending.id);
  assert.equal(format.isAckCorrelatable({ id: M1 }), true);
  assert.equal(format.isAckCorrelatable({ messageId: M1 }), true);
  assert.equal(format.isAckCorrelatable({ id: "bad" }), false);
});

test("recovery cursor advances only when the backend supplies a newer boundary", () => {
  const current = "cursor-one";
  assert.equal(format.advanceRecoveryCursor(current, { nextCursor: "cursor-two" }), "cursor-two");
  assert.equal(format.advanceRecoveryCursor(current, { nextCursor: null }), current);
  assert.equal(format.advanceRecoveryCursor(null, { nextCursor: "" }), null);
});

test("idempotency keys are UUID-shaped and stable content validation is client-side only", () => {
  const key = chatService.generateIdempotencyKey();
  assert.equal(format.isUuid(key), true);
  assert.deepEqual(chatService.validateContent("   "), { ok: false, reason: "required" });
  assert.deepEqual(chatService.validateContent("x".repeat(2001)), {
    ok: false,
    reason: "too_long"
  });
  assert.equal(chatService.validateContent("Hello").ok, true);
});
