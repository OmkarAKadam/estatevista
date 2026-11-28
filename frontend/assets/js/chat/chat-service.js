/**
 * Shared chat integration service for buyer and agent pages.
 *
 * REST owns reads and read-state; STOMP owns message sending and live events.
 * The service never stores private data outside memory, never places content
 * in a URL or log, and never exposes raw backend error text to a page.
 */

import {
  ApiError,
  apiRequest,
  isCancellation,
  isOfflineHint
} from "../core/api.js";
import { getAuthRevision, getAuthState } from "../auth/auth-state.js";
import {
  MAX_CONTENT_LENGTH,
  MESSAGE_LIMIT,
  buildHistoryQuery,
  isUuid,
  normalizeConversationDetail,
  normalizeConversationEnvelope,
  normalizeMessagePage
} from "./chat-format.js";

const PARTICIPANT_ROLES = new Set(["BUYER", "AGENT"]);
const listeners = new Set();

let epoch = 0;
const controllers = new Set();
const pending = new Set();

function publish() {
  const snapshot = Object.freeze({
    pending: Object.freeze([...pending]),
    inFlight: controllers.size,
    revision: getAuthRevision()
  });
  listeners.forEach((listener) => {
    try {
      listener(snapshot);
    } catch {
      /* Presentation listeners must never break a private request. */
    }
  });
}

export function subscribe(listener) {
  if (typeof listener !== "function") {
    return () => {};
  }
  listeners.add(listener);
  listener(getView());
  return () => listeners.delete(listener);
}

export function getView() {
  return Object.freeze({
    pending: Object.freeze([...pending]),
    inFlight: controllers.size,
    revision: getAuthRevision()
  });
}

/** Invalidate all in-flight private work after logout, auth change or pagehide. */
export function reset() {
  epoch += 1;
  controllers.forEach((controller) => controller.abort());
  controllers.clear();
  pending.clear();
  publish();
}

function newController() {
  const controller = new AbortController();
  controllers.add(controller);
  return controller;
}

function releaseController(controller) {
  controllers.delete(controller);
  publish();
}

function allowParticipant(role) {
  const state = getAuthState();
  return Boolean(
    state.status === "authenticated" &&
      state.user &&
      PARTICIPANT_ROLES.has(role) &&
      state.user.role === role &&
      (!state.user.accountStatus || state.user.accountStatus === "ACTIVE")
  );
}

function classify(error) {
  if (isCancellation(error)) {
    return { ok: false, reason: "cancelled" };
  }
  if (!(error instanceof ApiError)) {
    return { ok: false, reason: isOfflineHint() ? "offline" : "network" };
  }
  if (error.status === 401) {
    return { ok: false, reason: "auth", status: 401 };
  }
  if (error.status === 403) {
    const code = typeof error.code === "string" ? error.code.toUpperCase() : "";
    return {
      ok: false,
      reason: /INACTIVE|UNAVAILABLE|AGENT/.test(code) ? "inactive" : "forbidden",
      status: 403
    };
  }
  if (error.status === 404) {
    return { ok: false, reason: "not_found", status: 404 };
  }
  if (error.status === 409) {
    return { ok: false, reason: "conflict", status: 409 };
  }
  if (error.status === 429) {
    return { ok: false, reason: "rate_limit", status: 429, retryAfterMs: error.retryAfterMs };
  }
  if (error.status === 400 || error.status === 422) {
    return { ok: false, reason: "validation", status: error.status };
  }
  if (error.status === 0) {
    return { ok: false, reason: isOfflineHint() ? "offline" : "network", status: 0 };
  }
  return { ok: false, reason: "unknown", status: error.status };
}

function stableResult(result, startedEpoch, controller) {
  releaseController(controller);
  return startedEpoch === epoch ? result : { ok: false, reason: "cancelled" };
}

async function privateRequest(role, path, options, normalize) {
  if (!allowParticipant(role)) {
    return { ok: false, reason: "forbidden" };
  }
  if (isOfflineHint() && (options.method ?? "GET").toUpperCase() !== "GET") {
    return { ok: false, reason: "offline" };
  }

  const startedEpoch = epoch;
  const controller = newController();
  const key = `${options.method ?? "GET"} ${path}`;
  pending.add(key);
  publish();

  try {
    const payload = await apiRequest(path, { ...options, signal: controller.signal });
    const data = normalize(payload);
    if (data === null) {
      return stableResult({ ok: false, reason: "invalid_response" }, startedEpoch, controller);
    }
    return stableResult({ ok: true, data }, startedEpoch, controller);
  } catch (error) {
    return stableResult(classify(error), startedEpoch, controller);
  } finally {
    pending.delete(key);
    publish();
  }
}

export function listConversations(role, options = {}) {
  return privateRequest(role, "/conversations", { method: "GET", ...options }, normalizeConversationEnvelope);
}

export function getConversation(role, conversationId, options = {}) {
  if (!isUuid(conversationId)) {
    return Promise.resolve({ ok: false, reason: "not_found" });
  }
  return privateRequest(
    role,
    `/conversations/${encodeURIComponent(conversationId)}`,
    { method: "GET", ...options },
    normalizeConversationDetail
  );
}

/**
 * Load one latest-first cursor page. `before` prepends older history; `after`
 * is used only for missed-message recovery after reconnect.
 */
export function getMessages(role, conversationId, options = {}) {
  if (!isUuid(conversationId)) {
    return Promise.resolve({ ok: false, reason: "not_found" });
  }
  const query = buildHistoryQuery({
    limit: options.limit ?? MESSAGE_LIMIT,
    before: options.before ?? null,
    after: options.after ?? null
  });
  return privateRequest(
    role,
    `/conversations/${encodeURIComponent(conversationId)}/messages?${query}`,
    { method: "GET", ...options },
    normalizeMessagePage
  );
}

/** PATCH is the only approved read-state mutation; no message content is sent. */
export function markRead(role, conversationId, lastReadMessageId) {
  if (!isUuid(conversationId) || !isUuid(lastReadMessageId)) {
    return Promise.resolve({ ok: false, reason: "validation" });
  }
  return privateRequest(
    role,
    `/conversations/${encodeURIComponent(conversationId)}/read-state`,
    {
      method: "PATCH",
      body: JSON.stringify({ lastReadMessageId })
    },
    (payload) => payload && typeof payload === "object" ? payload : null
  );
}

/**
 * Create or retrieve the permanent buyer-property conversation. Creation has
 * no request body and carries no first message.
 */
export function createConversation(propertyId) {
  if (!isUuid(propertyId)) {
    return Promise.resolve({ ok: false, reason: "validation" });
  }
  return privateRequest(
    "BUYER",
    `/properties/${encodeURIComponent(propertyId)}/conversations`,
    { method: "POST" },
    normalizeConversationDetail
  );
}

export function validateContent(content) {
  if (typeof content !== "string") {
    return { ok: false, reason: "validation" };
  }
  const value = content.trim();
  if (!value) {
    return { ok: false, reason: "required" };
  }
  if (content.length > MAX_CONTENT_LENGTH) {
    return { ok: false, reason: "too_long" };
  }
  return { ok: true, content };
}

/** Generate one UUID per logical message. The same key is reused on retry. */
export function generateIdempotencyKey() {
  const cryptoObject = globalThis.crypto ?? window?.crypto;
  if (cryptoObject && typeof cryptoObject.randomUUID === "function") {
    return cryptoObject.randomUUID();
  }
  if (cryptoObject && typeof cryptoObject.getRandomValues === "function") {
    const bytes = cryptoObject.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = [...bytes].map((byte) => byte.toString(16).padStart(2, "0")).join("");
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
  }
  return null;
}

export function classifySendError(payload) {
  if (!payload || typeof payload !== "object") {
    return "unknown";
  }
  const reason = String(payload.reason ?? payload.code ?? "").toUpperCase();
  if (/CSRF|UNAUTH|SESSION|EXPIRED|LOGIN/.test(reason)) {
    return "auth";
  }
  if (/RATE|LIMIT|429|TOO_MANY/.test(reason)) {
    return "rate_limit";
  }
  if (/CONFLICT|IDEMPOTEN|DIFFERENT/.test(reason)) {
    return "conflict";
  }
  if (/INACTIVE|UNAVAILABLE|AGENT|FORBIDDEN|DENIED|NOT_PARTICIPANT/.test(reason)) {
    return "inactive";
  }
  if (/VALIDATION|BLANK|EMPTY|LENGTH|SIZE|CONTENT/.test(reason)) {
    return "validation";
  }
  return "unknown";
}

export default {
  subscribe,
  getView,
  reset,
  listConversations,
  getConversation,
  getMessages,
  markRead,
  createConversation,
  validateContent,
  generateIdempotencyKey,
  classifySendError
};
