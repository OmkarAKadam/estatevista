/**
 * Shared inquiry integration service for buyer, agent and administrator pages.
 *
 * Transport, validation, request state and private-data invalidation live here;
 * pages own only DOM rendering. Every call uses the shared API client, the
 * approved runtime base URL and the session-bound CSRF lifecycle. No inquiry
 * content is logged, persisted in browser storage or placed in a URL.
 */

import {
  ApiError,
  apiRequest,
  createRateLimitCooldown,
  isCancellation,
  isTimeout
} from "../core/api.js";
import {
  getAuthRevision,
  getAuthState,
  getUserRole
} from "../auth/auth-state.js";
import { clearSessionState } from "../auth/auth-service.js";
import {
  INQUIRY_STATUSES,
  isKnownStatus,
  isTransitionAllowed
} from "./inquiry-lifecycle.js";

const DEFAULT_PAGE = 0;
const DEFAULT_SIZE = 20;
const MAX_SIZE = 100;
const DEFAULT_SORT = "newest";
const SORTS = new Set(["newest", "oldest"]);
const SUBJECT_LIMIT = 200;
const MESSAGE_LIMIT = 5000;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

const MANAGER_ROLES = new Set(["AGENT", "ADMIN"]);
const READ_ROLES = new Set(["BUYER", "AGENT", "ADMIN"]);

const LIST_PATHS = Object.freeze({
  buyer: "/inquiries/me",
  agent: "/agents/me/inquiries",
  admin: "/admin/inquiries"
});

const listeners = new Set();
const generations = new Map();
const controllers = new Map();
const pendingSubmissions = new Set();
const pendingMutations = new Map();

let epoch = 0;

const cooldown = createRateLimitCooldown();

function publish() {
  const snapshot = Object.freeze({
    pendingSubmissions: Object.freeze([...pendingSubmissions]),
    pendingMutations: Object.freeze(
      [...pendingMutations].map(([inquiryId, status]) =>
        Object.freeze({ inquiryId, status })
      )
    ),
    cooldownActive: cooldown.active(),
    cooldownRemainingMs: cooldown.remainingMs()
  });
  listeners.forEach((listener) => {
    try {
      listener(snapshot);
    } catch {
      /* A presentation listener must never break request state. */
    }
  });
}

cooldown.subscribe(publish);

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
    pendingSubmissions: Object.freeze([...pendingSubmissions]),
    pendingMutations: Object.freeze(
      [...pendingMutations].map(([inquiryId, status]) =>
        Object.freeze({ inquiryId, status })
      )
    ),
    cooldownActive: cooldown.active(),
    cooldownRemainingMs: cooldown.remainingMs()
  });
}

export function getCooldown() {
  return cooldown;
}

/** Abort every cancellable private read and invalidate all outstanding work. */
export function reset() {
  epoch += 1;
  controllers.forEach((controller) => controller.abort());
  controllers.clear();
  generations.clear();
  pendingSubmissions.clear();
  pendingMutations.clear();
  cooldown.clear();
  publish();
}

function roleAllows(roles) {
  const state = getAuthState();
  if (
    state.status !== "authenticated" ||
    !state.user ||
    !roles.has(getUserRole())
  ) {
    return false;
  }
  const accountStatus = getAuthState().user.accountStatus;
  return !accountStatus || accountStatus === "ACTIVE";
}

function isUuid(value) {
  return typeof value === "string" && UUID_PATTERN.test(value);
}

export function validateListParams(params = {}) {
  const page = params.page ?? DEFAULT_PAGE;
  const size = params.size ?? DEFAULT_SIZE;
  const sort = params.sort ?? DEFAULT_SORT;
  const status = params.status ?? null;

  if (
    !Number.isInteger(page) ||
    page < 0 ||
    !Number.isInteger(size) ||
    size < 1 ||
    size > MAX_SIZE
  ) {
    throw new TypeError("Invalid inquiry pagination.");
  }
  if (typeof sort !== "string" || !SORTS.has(sort)) {
    throw new TypeError("Invalid inquiry sort.");
  }
  if (status !== null && !INQUIRY_STATUSES.includes(status)) {
    throw new TypeError("Invalid inquiry status filter.");
  }
  return { page, size, sort, status };
}

export function buildListQuery(params = {}) {
  const safe = validateListParams(params);
  const query = new URLSearchParams();
  query.set("page", String(safe.page));
  query.set("size", String(safe.size));
  query.set("sort", safe.sort);
  if (safe.status) {
    query.set("status", safe.status);
  }
  return query.toString();
}

function textOrEmpty(value) {
  return typeof value === "string" ? value : "";
}

function timestampOrNull(value) {
  return typeof value === "string" && value.length > 0 ? value : null;
}

export function normalizeListItem(item) {
  if (!item || typeof item !== "object" || !isUuid(item.id)) {
    return null;
  }
  return Object.freeze({
    id: item.id,
    propertyId: textOrEmpty(item.propertyId),
    propertyTitle: textOrEmpty(item.propertyTitle) || "Property no longer available",
    buyerId: textOrEmpty(item.buyerId),
    assignedAgentId: textOrEmpty(item.assignedAgentId),
    subject: typeof item.subject === "string" ? item.subject : null,
    status: textOrEmpty(item.status),
    resolvedAt: timestampOrNull(item.resolvedAt),
    createdAt: timestampOrNull(item.createdAt),
    updatedAt: timestampOrNull(item.updatedAt)
  });
}

export function normalizeDetail(payload) {
  if (!payload || payload.success !== true || !payload.data) {
    return null;
  }
  const item = normalizeListItem(payload.data);
  if (!item) {
    return null;
  }
  if (typeof payload.data.message !== "string") {
    return null;
  }
  return Object.freeze({ ...item, message: payload.data.message });
}

function normalizeList(payload) {
  if (
    !payload ||
    payload.success !== true ||
    !Array.isArray(payload.data) ||
    !payload.meta ||
    !Number.isInteger(payload.meta.totalElements) ||
    !Number.isInteger(payload.meta.totalPages) ||
    typeof payload.meta.hasNext !== "boolean" ||
    typeof payload.meta.hasPrevious !== "boolean"
  ) {
    return null;
  }
  return {
    items: payload.data.map(normalizeListItem).filter(Boolean),
    meta: Object.freeze({
      page: payload.meta.page,
      size: payload.meta.size,
      totalElements: payload.meta.totalElements,
      totalPages: payload.meta.totalPages,
      hasNext: payload.meta.hasNext,
      hasPrevious: payload.meta.hasPrevious
    })
  };
}

function normalizedError(error, { ambiguous = false } = {}) {
  if (isCancellation(error)) {
    return { ok: false, reason: "cancelled" };
  }
  if (error instanceof ApiError && error.status === 401) {
    clearSessionState();
    return { ok: false, reason: "auth" };
  }
  if (error instanceof ApiError && error.status === 429) {
    const retryAfterMs =
      Number.isFinite(error.retryAfterMs) && error.retryAfterMs > 0
        ? error.retryAfterMs
        : null;
    cooldown.start(retryAfterMs);
    return { ok: false, reason: "rate_limit", retryAfterMs };
  }
  if (error instanceof ApiError && error.status === 400) {
    return {
      ok: false,
      reason: "validation",
      details: Array.isArray(error.details) ? error.details : []
    };
  }
  if (error instanceof ApiError && error.status === 403) {
    return { ok: false, reason: "forbidden" };
  }
  if (error instanceof ApiError && error.status === 404) {
    return { ok: false, reason: "unavailable" };
  }
  if (error instanceof ApiError && error.status === 409) {
    return { ok: false, reason: "conflict" };
  }
  if (error instanceof ApiError && error.status >= 500) {
    return { ok: false, reason: ambiguous ? "ambiguous" : "server" };
  }
  if (isTimeout(error) || error?.code === "NETWORK_ERROR") {
    return { ok: false, reason: ambiguous ? "ambiguous" : "network" };
  }
  if (error?.name === "InvalidApiResponseError") {
    return { ok: false, reason: "invalid_response" };
  }
  return { ok: false, reason: ambiguous ? "ambiguous" : "network" };
}

function beginRead(key) {
  const generation = (generations.get(key) ?? 0) + 1;
  generations.set(key, generation);
  const controller = new AbortController();
  controllers.set(key, controller);
  return {
    key,
    generation,
    epochAtStart: epoch,
    revisionAtStart: getAuthRevision(),
    controller,
    signal: controller.signal
  };
}

function isCurrent(token) {
  return (
    token.epochAtStart === epoch &&
    token.revisionAtStart === getAuthRevision() &&
    generations.get(token.key) === token.generation
  );
}

function staleReason(token) {
  return getAuthRevision() !== token.revisionAtStart ? "auth_changed" : "stale";
}

async function rejectedResult(token, error, options = {}) {
  if (isCancellation(error)) {
    return { ok: false, reason: "cancelled" };
  }
  if (!isCurrent(token)) {
    return { ok: false, reason: staleReason(token) };
  }
  return normalizedError(error, options);
}

function blockedByCooldown() {
  if (!cooldown.active()) {
    return null;
  }
  return { ok: false, reason: "rate_limit", retryAfterMs: cooldown.remainingMs() };
}

/**
 * Paginated reads. `scope` is one of buyer, agent or admin. Each newer call
 * supersedes an older read for the same scope and aborts its fetch.
 */
export async function listInquiries(scope, params = {}) {
  const roles = scope === "buyer"
    ? new Set(["BUYER"])
    : scope === "agent"
      ? new Set(["AGENT"])
      : new Set(["ADMIN"]);
  if (!Object.hasOwn(LIST_PATHS, scope) || !roleAllows(roles)) {
    return { ok: false, reason: "forbidden" };
  }

  let query;
  try {
    query = buildListQuery(params);
  } catch {
    return { ok: false, reason: "validation" };
  }

  const blocked = blockedByCooldown();
  if (blocked) {
    return blocked;
  }

  const token = beginRead(`list:${scope}`);
  try {
    const payload = await apiRequest(`${LIST_PATHS[scope]}?${query}`, {
      method: "GET",
      signal: token.signal
    });
    const normalized = normalizeList(payload);
    if (!isCurrent(token)) {
      return { ok: false, reason: staleReason(token) };
    }
    if (!normalized) {
      return { ok: false, reason: "invalid_response" };
    }
    return { ok: true, ...normalized };
  } catch (error) {
    return rejectedResult(token, error);
  } finally {
    if (controllers.get(token.key) === token.controller) {
      controllers.delete(token.key);
    }
  }
}

/**
 * Detailed read; messages are only present in this response. `force` is used
 * only for authoritative reconciliation after an ambiguous mutation and never
 * bypasses authorization or starts a mutation replay.
 */
export async function getDetail(inquiryId, { force = false } = {}) {
  if (!roleAllows(READ_ROLES) || !isUuid(inquiryId)) {
    return { ok: false, reason: "forbidden" };
  }

  const blocked = force ? null : blockedByCooldown();
  if (blocked) {
    return blocked;
  }

  const token = beginRead(`detail:${inquiryId}`);
  try {
    const payload = await apiRequest(`/inquiries/${inquiryId}`, {
      method: "GET",
      signal: token.signal
    });
    const detail = normalizeDetail(payload);
    if (!isCurrent(token)) {
      return { ok: false, reason: staleReason(token) };
    }
    if (!detail) {
      return { ok: false, reason: "invalid_response" };
    }
    return { ok: true, detail };
  } catch (error) {
    return rejectedResult(token, error);
  } finally {
    if (controllers.get(token.key) === token.controller) {
      controllers.delete(token.key);
    }
  }
}

export function validateSubmission({ subject, message } = {}) {
  if (typeof subject === "string" && subject.length > SUBJECT_LIMIT) {
    return { ok: false, field: "subject", reason: "too_long" };
  }
  if (typeof message === "string" && message.length > MESSAGE_LIMIT) {
    return { ok: false, field: "message", reason: "too_long" };
  }

  const trimmedSubject = typeof subject === "string" ? subject.trim() : "";
  const trimmedMessage = typeof message === "string" ? message.trim() : "";

  if (trimmedMessage.length === 0) {
    return { ok: false, field: "message", reason: "required" };
  }
  return {
    ok: true,
    body: trimmedSubject.length > 0
      ? { subject: trimmedSubject, message: trimmedMessage }
      : { message: trimmedMessage }
  };
}

/**
 * Buyer submission. A transport failure or malformed success body is reported
 * as ambiguous: the request is never replayed automatically.
 */
export async function submitInquiry(propertyId, form = {}) {
  if (!roleAllows(new Set(["BUYER"])) || !isUuid(propertyId)) {
    return { ok: false, reason: "forbidden" };
  }
  const validation = validateSubmission(form);
  if (!validation.ok) {
    return { ok: false, reason: "validation", field: validation.field, detail: validation.reason };
  }
  if (pendingSubmissions.has(propertyId)) {
    return { ok: false, reason: "duplicate_submission" };
  }
  const blocked = blockedByCooldown();
  if (blocked) {
    return blocked;
  }

  const revisionAtStart = getAuthRevision();
  pendingSubmissions.add(propertyId);
  publish();

  try {
    const payload = await apiRequest(`/properties/${propertyId}/inquiries`, {
      method: "POST",
      body: JSON.stringify(validation.body)
    });
    if (getAuthRevision() !== revisionAtStart) {
      return { ok: false, reason: "auth_changed" };
    }
    if (
      !payload ||
      payload.success !== true ||
      !payload.data ||
      typeof payload.data.id !== "string"
    ) {
      return { ok: false, reason: "ambiguous" };
    }
    return {
      ok: true,
      inquiry: Object.freeze({
        id: payload.data.id,
        propertyId: textOrEmpty(payload.data.propertyId) || propertyId,
        status: textOrEmpty(payload.data.status) || "NEW",
        createdAt: timestampOrNull(payload.data.createdAt)
      })
    };
  } catch (error) {
    if (getAuthRevision() !== revisionAtStart) {
      return { ok: false, reason: "auth_changed" };
    }
    return normalizedError(error, { ambiguous: true });
  } finally {
    pendingSubmissions.delete(propertyId);
    publish();
  }
}

/**
 * Authorized status mutation. Only the approved target field is sent. Conflicts
 * and ambiguous outcomes are reconciled with a fresh authoritative detail read;
 * the mutation itself is never repeated.
 */
export async function changeStatus(inquiryId, target, expectedStatus = null) {
  if (!roleAllows(MANAGER_ROLES) || !isUuid(inquiryId)) {
    return { ok: false, reason: "forbidden" };
  }
  if (typeof target !== "string" || !isKnownStatus(target)) {
    return { ok: false, reason: "validation" };
  }
  if (!isKnownStatus(expectedStatus) || !isTransitionAllowed(expectedStatus, target)) {
    return { ok: false, reason: "invalid_transition" };
  }
  if (pendingMutations.has(inquiryId)) {
    return { ok: false, reason: "duplicate_mutation" };
  }
  const blocked = blockedByCooldown();
  if (blocked) {
    return blocked;
  }

  const revisionAtStart = getAuthRevision();
  pendingMutations.set(inquiryId, target);
  publish();

  try {
    const payload = await apiRequest(`/inquiries/${inquiryId}/status`, {
      method: "PATCH",
      body: JSON.stringify({ status: target })
    });
    const detail = normalizeDetail(payload);
    if (getAuthRevision() !== revisionAtStart) {
      return { ok: false, reason: "auth_changed" };
    }
    if (!detail) {
      return { ok: false, reason: "ambiguous" };
    }
    return { ok: true, detail };
  } catch (error) {
    if (getAuthRevision() !== revisionAtStart) {
      return { ok: false, reason: "auth_changed" };
    }
    const result = normalizedError(error, { ambiguous: true });
    if (result.reason === "conflict" || result.reason === "ambiguous") {
      const authoritative = await getDetail(inquiryId, { force: true });
      if (authoritative.ok) {
        return { ...result, detail: authoritative.detail };
      }
      if (authoritative.reason === "unavailable" || authoritative.reason === "forbidden") {
        return { ...result, detail: null, unavailable: true };
      }
    }
    if (result.reason === "unavailable" || result.reason === "forbidden") {
      return { ok: false, reason: result.reason, unavailable: true };
    }
    return result;
  } finally {
    pendingMutations.delete(inquiryId);
    publish();
  }
}

export default {
  subscribe,
  getView,
  getCooldown,
  reset,
  validateListParams,
  buildListQuery,
  validateSubmission,
  listInquiries,
  getDetail,
  submitInquiry,
  changeStatus
};
