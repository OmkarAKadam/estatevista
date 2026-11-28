/**
 * Pure helpers for the property-specific chat surface.
 *
 * No DOM, network or storage access belongs here. Every server-provided value
 * is checked before it becomes presentation state; message content is kept as
 * plain text and is never interpreted as markup.
 */

export const MESSAGE_LIMIT = 30;
export const MAX_CONTENT_LENGTH = 2000;
export const SEND_TIMEOUT_MS = 15000;

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const LOOSE_UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export function isUuid(value) {
  return typeof value === "string" && (UUID_PATTERN.test(value) || LOOSE_UUID_PATTERN.test(value));
}

export function textOrEmpty(value) {
  return typeof value === "string" ? value : "";
}

function integerOrNull(value) {
  return Number.isInteger(value) ? value : null;
}

function positiveIntegerOrDefault(value, fallback) {
  return Number.isInteger(value) && value > 0 ? value : fallback;
}

/**
 * Normalize `ConversationResponse` into a frozen, presentation-safe object.
 * `unreadCount` is always the requester's own value from the backend and is
 * never inferred from loaded messages.
 */
export function normalizeConversation(item) {
  if (!item || typeof item !== "object" || !isUuid(item.conversationId)) {
    return null;
  }

  const unread = integerOrNull(item.unreadCount);
  return Object.freeze({
    conversationId: item.conversationId,
    propertyId: textOrEmpty(item.propertyId),
    propertyTitle: textOrEmpty(item.propertyTitle) || "Property no longer available",
    propertyStatus: textOrEmpty(item.propertyStatus).toUpperCase(),
    buyerId: textOrEmpty(item.buyerId),
    assignedAgentId: textOrEmpty(item.assignedAgentId),
    unreadCount: unread !== null && unread >= 0 ? unread : 0,
    lastMessageId: textOrEmpty(item.lastMessageId) || null,
    lastMessageAt: textOrEmpty(item.lastMessageAt) || null,
    createdAt: textOrEmpty(item.createdAt) || null
  });
}

export function normalizeConversationEnvelope(payload) {
  if (!payload || payload.success !== true || !Array.isArray(payload.data)) {
    return null;
  }
  const items = payload.data.map(normalizeConversation).filter(Boolean);
  return Object.freeze(items);
}

export function normalizeConversationDetail(payload) {
  if (!payload || payload.success !== true) {
    return null;
  }
  return normalizeConversation(payload.data);
}

/** Normalize `MessageResponse`. Content remains plain text. */
export function normalizeMessage(item) {
  if (!item || typeof item !== "object" || !isUuid(item.id)) {
    return null;
  }
  const conversationId = textOrEmpty(item.conversationId);
  if (!isUuid(conversationId)) {
    return null;
  }

  return Object.freeze({
    id: item.id,
    conversationId,
    senderId: textOrEmpty(item.senderId),
    messageType: textOrEmpty(item.messageType).toUpperCase() || "TEXT",
    content: typeof item.content === "string" ? item.content : "",
    idempotencyKey: textOrEmpty(item.idempotencyKey) || null,
    sentAt: textOrEmpty(item.sentAt) || null,
    pending: false,
    failed: false,
    clientId: null
  });
}

/**
 * History responses are latest-first, newest page first. `nextCursor` is the
 * opaque cursor passed back as `before` when prepending older rows.
 */
export function normalizeMessagePage(payload) {
  if (
    !payload ||
    payload.success !== true ||
    !Array.isArray(payload.data) ||
    !payload.meta ||
    typeof payload.meta.hasMore !== "boolean"
  ) {
    return null;
  }

  const data = payload.data.map(normalizeMessage).filter(Boolean);
  const nextCursor =
    typeof payload.meta.nextCursor === "string" && payload.meta.nextCursor
      ? payload.meta.nextCursor
      : null;
  const hasMore = payload.meta.hasMore && nextCursor !== null;

  return Object.freeze({
    messages: Object.freeze(data),
    limit: positiveIntegerOrDefault(payload.meta.limit, MESSAGE_LIMIT),
    nextCursor: hasMore ? nextCursor : null,
    hasMore
  });
}

export function buildHistoryQuery({ limit = MESSAGE_LIMIT, before = null, after = null } = {}) {
  const size = positiveIntegerOrDefault(limit, MESSAGE_LIMIT);
  const query = new URLSearchParams();
  query.set("limit", String(size));
  if (before) {
    query.set("before", String(before));
  }
  if (after) {
    query.set("after", String(after));
  }
  return query.toString();
}

export function compareMessages(a, b) {
  const aTime = Date.parse(a.sentAt ?? "");
  const bTime = Date.parse(b.sentAt ?? "");
  const aStamp = Number.isFinite(aTime) ? aTime : 0;
  const bStamp = Number.isFinite(bTime) ? bTime : 0;
  if (aStamp !== bStamp) {
    return aStamp - bStamp;
  }
  return textOrEmpty(a.id).localeCompare(textOrEmpty(b.id));
}

/** Chronological merge that ignores duplicate IDs and duplicate send keys. */
export function mergeMessages(existing, incoming) {
  const byId = new Map();
  const keys = new Set();

  existing.forEach((message) => {
    if (!message) {
      return;
    }
    byId.set(message.id, message);
    if (message.idempotencyKey) {
      keys.add(message.idempotencyKey);
    }
  });

  incoming.forEach((message) => {
    if (!message) {
      return;
    }
    if (byId.has(message.id)) {
      return;
    }
    if (message.idempotencyKey && keys.has(message.idempotencyKey)) {
      return;
    }
    byId.set(message.id, message);
    if (message.idempotencyKey) {
      keys.add(message.idempotencyKey);
    }
  });

  return [...byId.values()].sort(compareMessages);
}

export function createPendingMessage({ conversationId, content, idempotencyKey, clientId, sentAt, senderId = null }) {
  return Object.freeze({
    id: clientId,
    conversationId,
    senderId,
    messageType: "TEXT",
    content,
    idempotencyKey,
    sentAt: sentAt || new Date().toISOString(),
    pending: true,
    failed: false,
    clientId
  });
}

/** Only an ack carrying a UUID message identity may clear a send timer. */
export function isAckCorrelatable(ack) {
  const id = textOrEmpty(ack?.messageId) || textOrEmpty(ack?.id);
  return isUuid(id);
}

/**
 * Advance the forward recovery cursor. The backend supplies a new opaque
 * cursor only when another page remains; otherwise the existing boundary is
 * kept so the next reconnect can retry the same recovery page.
 */
export function advanceRecoveryCursor(current, page) {
  return textOrEmpty(page?.nextCursor) || current || null;
}

/**
 * An `after` page is newest-first and its next cursor is its oldest visible
 * row. Continuing with `after` would repeat that window, so recovery switches
 * to `before` pages and walks back until it reaches the pre-reconnect floor
 * message or the beginning of history.
 */
export function nextRecoveryRequest(page, floorMessageId = null) {
  if (!page?.hasMore || !page.nextCursor) {
    return null;
  }
  if (floorMessageId && page.messages.some((message) => message.id === floorMessageId)) {
    return null;
  }
  return Object.freeze({ direction: "before", cursor: page.nextCursor });
}

/** Clear every private thread field before auth invalidation or page teardown. */
export function clearThreadPrivateState(state) {
  if (!state || typeof state !== "object") {
    return state;
  }
  state.conversation = null;
  state.messages = [];
  state.olderCursor = null;
  state.recoveryCursor = null;
  state.recoveryFloorId = null;
  state.lastRead = null;
  state.unreadCount = 0;
  state.hasUnseen = false;
  state.composerBlocked = false;
  return state;
}

export function reconcileAck(message, ack) {
  if (!message || !ack || !isAckCorrelatable(ack)) {
    return message;
  }
  const id = textOrEmpty(ack.messageId) || textOrEmpty(ack.id);
  return Object.freeze({
    ...message,
    id,
    sentAt: textOrEmpty(ack.sentAt) || message.sentAt,
    pending: false,
    failed: false
  });
}

export function markMessageFailed(message) {
  if (!message) {
    return message;
  }
  return Object.freeze({ ...message, pending: false, failed: true });
}

export function initialsFor(name) {
  const parts = textOrEmpty(name).trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) {
    return "EV";
  }
  return (parts[0][0] + (parts.length > 1 ? parts[parts.length - 1][0] : "")).toUpperCase();
}

export function formatTime(value) {
  const timestamp = Date.parse(textOrEmpty(value));
  if (!Number.isFinite(timestamp)) {
    return "";
  }
  return new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit" }).format(timestamp);
}

export function formatDateDivider(value) {
  const timestamp = Date.parse(textOrEmpty(value));
  if (!Number.isFinite(timestamp)) {
    return "";
  }
  const date = new Date(timestamp);
  const now = new Date();
  const startOf = (candidate) => new Date(candidate.getFullYear(), candidate.getMonth(), candidate.getDate()).getTime();
  const days = Math.round((startOf(now) - startOf(date)) / 86400000);
  if (days === 0) {
    return "Today";
  }
  if (days === 1) {
    return "Yesterday";
  }
  return new Intl.DateTimeFormat(undefined, {
    day: "numeric",
    month: "short",
    year: "numeric"
  }).format(timestamp);
}

export function formatRelative(value) {
  const timestamp = Date.parse(textOrEmpty(value));
  if (!Number.isFinite(timestamp)) {
    return "";
  }
  const seconds = Math.max(0, Math.round((Date.now() - timestamp) / 1000));
  if (seconds < 60) {
    return "just now";
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return `${minutes}m ago`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return `${hours}h ago`;
  }
  return formatDateDivider(value);
}

export function formatAbsolute(value) {
  const timestamp = Date.parse(textOrEmpty(value));
  if (!Number.isFinite(timestamp)) {
    return "";
  }
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "short"
  }).format(timestamp);
}

/** Curated user-facing text; raw backend codes are never rendered. */
export function describeChatError(reason) {
  switch (reason) {
    case "auth":
      return "Your session has ended. Sign in again to continue chatting.";
    case "forbidden":
      return "This conversation is not available to you.";
    case "not_found":
      return "This conversation is not available to you.";
    case "inactive":
      return "This conversation is unattended because its original agent is no longer active.";
    case "validation":
      return "Check the message and try again.";
    case "rate_limit":
      return "You’re sending messages too quickly. Try again in a moment.";
    case "conflict":
      return "That message was already sent with different content. Refresh the conversation before retrying.";
    case "offline":
      return "You’re offline. Messages can’t be sent.";
    case "disconnected":
      return "You’re disconnected. Reload to reconnect.";
    case "network":
    case "timeout":
      return "We couldn’t reach EstateVista. Check your connection and try again.";
    default:
      return "We couldn’t complete that action. Please try again.";
  }
}
