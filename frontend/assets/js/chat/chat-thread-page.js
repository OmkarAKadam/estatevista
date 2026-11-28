/**
 * Buyer and agent conversation thread controller.
 *
 * REST owns history and read-state. STOMP owns sends and live events only.
 * All private values remain in this page's memory: no token, message body,
 * idempotency key or identity is written to storage, a URL or a diagnostic.
 */

import {
  getAuthRevision,
  getAuthState,
  subscribe as subscribeAuth
} from "../auth/auth-state.js";
import {
  CONNECTION_STATES,
  createChatConnection
} from "./chat-connection.js";
import {
  advanceRecoveryCursor,
  compareMessages,
  createPendingMessage,
  describeChatError,
  isAckCorrelatable,
  isUuid,
  markMessageFailed,
  MAX_CONTENT_LENGTH,
  mergeMessages,
  normalizeMessage,
  reconcileAck,
  SEND_TIMEOUT_MS
} from "./chat-format.js";
import {
  classifySendError,
  generateIdempotencyKey,
  getConversation,
  getMessages,
  markRead,
  reset as resetChatService,
  validateContent
} from "./chat-service.js";
import { registerChatTeardown } from "./chat-lifecycle.js";
import { clear, renderMessages, setText, show } from "./chat-view.js";

const role =
  document.body && document.body.dataset.chatRole === "AGENT" ? "AGENT" : "BUYER";
const conversationId = new URLSearchParams(window.location.search).get("id");

const refs = {
  guard: document.querySelector("[data-chat-guard]"),
  guardTitle: document.querySelector("[data-chat-guard-title]"),
  guardMessage: document.querySelector("[data-chat-guard-message]"),
  loginLink: document.querySelector("[data-chat-login-link]"),
  loading: document.querySelector("[data-chat-loading]"),
  critical: document.querySelector("[data-chat-critical]"),
  criticalTitle: document.querySelector("[data-chat-critical-title]"),
  criticalMessage: document.querySelector("[data-chat-critical-message]"),
  workspace: document.querySelector("[data-chat-workspace]"),
  title: document.querySelector("[data-chat-title]"),
  roleLabel: document.querySelector("[data-chat-role-label]"),
  connectionDot: document.querySelector("[data-chat-connection-dot]"),
  connectionText: document.querySelector("[data-chat-connection-text]"),
  readIndicator: document.querySelector("[data-chat-read-indicator]"),
  property: document.querySelector("[data-chat-property]"),
  propertyTitle: document.querySelector("[data-chat-property-title]"),
  propertyStatus: document.querySelector("[data-chat-property-status]"),
  banner: document.querySelector("[data-chat-connection-banner]"),
  bannerMessage: document.querySelector("[data-chat-connection-message]"),
  reconnect: document.querySelector("[data-chat-reconnect]"),
  bannerLogin: document.querySelector(
    "[data-chat-connection-banner] [data-chat-login-link]"
  ),
  error: document.querySelector("[data-chat-error]"),
  errorMessage: document.querySelector("[data-chat-error-message]"),
  retryLoad: document.querySelector("[data-chat-retry-load]"),
  loadOlder: document.querySelector("[data-chat-load-older]"),
  beginning: document.querySelector("[data-chat-beginning]"),
  history: document.querySelector("[data-chat-history]"),
  list: document.querySelector("[data-chat-message-list]"),
  empty: document.querySelector("[data-chat-empty]"),
  newMessages: document.querySelector("[data-chat-new-messages]"),
  composer: document.querySelector("[data-chat-composer]"),
  input: document.querySelector("[data-chat-input]"),
  send: document.querySelector("[data-chat-send]"),
  count: document.querySelector("[data-chat-count]"),
  feedback: document.querySelector("[data-chat-feedback]")
};

const CONNECTION_COPY = Object.freeze({
  [CONNECTION_STATES.CONNECTING]: "Connecting to live messages…",
  [CONNECTION_STATES.CONNECTED]: "Live messages connected",
  [CONNECTION_STATES.RECONNECTING]: "Connection lost. Reconnecting…",
  [CONNECTION_STATES.DISCONNECTED]: "Live updates are unavailable.",
  [CONNECTION_STATES.OFFLINE]: "You’re offline. Live updates are paused.",
  [CONNECTION_STATES.AUTH_FAILURE]: "Your session has ended."
});

const state = {
  conversation: null,
  messages: [],
  olderCursor: null,
  recoveryCursor: null,
  loadingOlder: false,
  loadSequence: 0,
  lastRead: null,
  unreadCount: 0,
  hasUnseen: false,
  connection: null,
  sendTimers: new Map(),
  composerBlocked: false,
  destroyed: false,
  startedRevision: null,
  teardownDisposer: null,
  unsubscribeAuth: null
};

function currentUserId() {
  return getAuthState().user?.id ?? "";
}

function isOwn(message) {
  return Boolean(
    message && message.senderId && message.senderId === currentUserId()
  );
}

function setFeedback(reason) {
  setText(refs.feedback, reason ? describeChatError(reason) : "");
  show(refs.feedback, Boolean(reason));
}

function updateReadIndicator() {
  setText(
    refs.readIndicator,
    state.unreadCount > 0 ? `${state.unreadCount} unread` : "All messages read"
  );
}

function isNearBottom() {
  if (!refs.history) {
    return true;
  }
  return (
    refs.history.scrollTop + refs.history.clientHeight >=
    refs.history.scrollHeight - 96
  );
}

function scrollToLatest() {
  if (refs.history) {
    refs.history.scrollTop = refs.history.scrollHeight;
  }
  state.hasUnseen = false;
  show(refs.newMessages, false);
}

function updateCount() {
  setText(refs.count, `${refs.input?.value.length ?? 0} / ${MAX_CONTENT_LENGTH}`);
}

function updateSendAvailability() {
  if (!refs.send) {
    return;
  }
  const hasText = (refs.input?.value ?? "").trim().length > 0;
  refs.send.disabled = state.composerBlocked || !hasText || !state.connection?.isConnected();
}

function render(options = {}) {
  renderMessages(refs.list, state.messages, {
    currentUserId: currentUserId(),
    onRetry: (message) => retryMessage(message)
  });
  show(refs.empty, state.messages.length === 0);
  show(refs.loadOlder, Boolean(state.olderCursor) && !state.loadingOlder);
  show(refs.beginning, state.messages.length > 0 && !state.olderCursor && !state.loadingOlder);
  show(refs.newMessages, state.hasUnseen);
  updateReadIndicator();
  updateCount();
  updateSendAvailability();
  if (!options.preserveScroll) {
    scrollToLatest();
  }
}

function showGuard(title, message, withLogin = true) {
  show(refs.loading, false);
  show(refs.critical, false);
  show(refs.workspace, false);
  show(refs.guard, true);
  setText(refs.guardTitle, title);
  setText(refs.guardMessage, message);
  if (refs.loginLink) {
    refs.loginLink.hidden = !withLogin;
  }
}

function showCritical(title, message) {
  show(refs.loading, false);
  show(refs.guard, false);
  show(refs.workspace, false);
  show(refs.critical, true);
  setText(refs.criticalTitle, title);
  setText(refs.criticalMessage, message);
}

function showLoadError(reason) {
  show(refs.loading, false);
  show(refs.guard, false);
  show(refs.critical, false);
  show(refs.workspace, false);
  setText(refs.errorMessage, describeChatError(reason));
  show(refs.error, true);
}

function allowed(stateSnapshot) {
  return Boolean(
    stateSnapshot.status === "authenticated" &&
      stateSnapshot.user &&
      stateSnapshot.user.role === role &&
      (!stateSnapshot.user.accountStatus ||
        stateSnapshot.user.accountStatus === "ACTIVE")
  );
}

function applyConnectionState(next) {
  const copy = CONNECTION_COPY[next] ?? "Connection state changed.";
  setText(refs.connectionText, copy);
  refs.connectionDot?.classList.toggle("is-connected", next === CONNECTION_STATES.CONNECTED);
  refs.connectionDot?.classList.toggle(
    "is-degraded",
    next === CONNECTION_STATES.RECONNECTING ||
      next === CONNECTION_STATES.OFFLINE ||
      next === CONNECTION_STATES.DISCONNECTED
  );

  const showBanner =
    next !== CONNECTION_STATES.CONNECTED && next !== CONNECTION_STATES.CONNECTING;
  show(refs.banner, showBanner);
  setText(refs.bannerMessage, showBanner ? copy : "");
  show(refs.reconnect, next === CONNECTION_STATES.DISCONNECTED || next === CONNECTION_STATES.OFFLINE);
  if (refs.bannerLogin) {
    refs.bannerLogin.hidden = next !== CONNECTION_STATES.AUTH_FAILURE;
  }
  if (state.composerBlocked && refs.feedback?.hidden) {
    setFeedback("inactive");
  }
  updateSendAvailability();

  if (next === CONNECTION_STATES.AUTH_FAILURE) {
    clearPrivateState();
    showGuard(
      "Your session expired",
      "Sign in again to return to this conversation."
    );
  }
}

function clearPrivateState() {
  clear(refs.list);
  state.messages = [];
  state.conversation = null;
  state.lastRead = null;
  state.unreadCount = 0;
  state.hasUnseen = false;
  state.composerBlocked = false;
  state.sendTimers.forEach((timer) => window.clearTimeout(timer));
  state.sendTimers.clear();
  if (refs.input) {
    refs.input.value = "";
  }
  if (state.teardownDisposer) {
    state.teardownDisposer();
    state.teardownDisposer = null;
  }
  resetChatService();
}

function startConnection() {
  if (state.connection || state.destroyed) {
    return;
  }
  state.connection = createChatConnection({
    onEvent: handleEvent,
    onStatus: applyConnectionState,
    onReconnect: () => {
      void recoverMissedMessages();
    }
  });
  state.teardownDisposer = registerChatTeardown(() => state.connection?.destroy());
  state.connection.start();
}

function clearSendTimer(idempotencyKey) {
  const timer = state.sendTimers.get(idempotencyKey);
  if (timer !== undefined) {
    window.clearTimeout(timer);
    state.sendTimers.delete(idempotencyKey);
  }
}

function markSendFailed(idempotencyKey) {
  state.messages = state.messages.map((message) =>
    message.idempotencyKey === idempotencyKey
      ? markMessageFailed(message)
      : message
  );
  clearSendTimer(idempotencyKey);
  render({ preserveScroll: true });
}

function armSendTimer(idempotencyKey) {
  clearSendTimer(idempotencyKey);
  const timer = window.setTimeout(() => {
    state.sendTimers.delete(idempotencyKey);
    markSendFailed(idempotencyKey);
    setFeedback("timeout");
  }, SEND_TIMEOUT_MS);
  state.sendTimers.set(idempotencyKey, timer);
}

function sendWithKey(message) {
  if (state.composerBlocked) {
    setFeedback("inactive");
    return;
  }
  if (!state.connection?.isConnected()) {
    state.messages = state.messages.map((item) =>
      item.idempotencyKey === message.idempotencyKey
        ? markMessageFailed(item)
        : item
    );
    render({ preserveScroll: true });
    setFeedback("disconnected");
    return;
  }

  const published = state.connection.send({
    conversationId,
    content: message.content,
    idempotencyKey: message.idempotencyKey
  });
  if (!published) {
    markSendFailed(message.idempotencyKey);
    setFeedback("disconnected");
    return;
  }
  armSendTimer(message.idempotencyKey);
}

function sendMessage() {
  const checked = validateContent(refs.input?.value ?? "");
  if (!checked.ok) {
    setFeedback(checked.reason);
    refs.input?.focus();
    return;
  }
  const idempotencyKey = generateIdempotencyKey();
  if (!idempotencyKey) {
    setFeedback("unknown");
    return;
  }
  const pending = createPendingMessage({
    conversationId,
    content: checked.content,
    idempotencyKey,
    clientId: idempotencyKey,
    senderId: currentUserId(),
    sentAt: new Date().toISOString()
  });

  state.messages = mergeMessages(state.messages, [pending]);
  if (refs.input) {
    refs.input.value = "";
  }
  setFeedback(null);
  render();
  sendWithKey(pending);
}

function retryMessage(message) {
  if (!message || !message.failed || !message.idempotencyKey) {
    return;
  }
  state.messages = state.messages.map((item) =>
    item.idempotencyKey === message.idempotencyKey
      ? Object.freeze({ ...item, failed: false, pending: true })
      : item
  );
  setFeedback(null);
  render({ preserveScroll: true });
  sendWithKey(message);
}

function upsertServerMessage(message) {
  const index = state.messages.findIndex(
    (item) =>
      item.id === message.id ||
      (Boolean(message.idempotencyKey) &&
        item.idempotencyKey === message.idempotencyKey)
  );
  const remaining = state.messages.filter((_, position) => position !== index);
  state.messages = mergeMessages(remaining, [message]);
}

function eventMessage(frame) {
  return normalizeMessage({
    id: frame.id,
    conversationId: frame.conversationId,
    senderId: frame.senderId,
    messageType: frame.messageType,
    content: frame.content,
    idempotencyKey: frame.idempotencyKey,
    sentAt: frame.sentAt
  });
}

function eventForThisConversation(frame) {
  return (
    typeof frame.conversationId === "string" &&
    frame.conversationId.toLowerCase() === String(conversationId).toLowerCase()
  );
}

function handleMessageCreated(frame) {
  const message = eventMessage(frame);
  if (!message) {
    return;
  }
  const atBottom = isNearBottom();
  upsertServerMessage(message);
  if (!isOwn(message)) {
    if (atBottom) {
      state.hasUnseen = false;
      scheduleMarkRead();
    } else {
      state.hasUnseen = true;
      state.unreadCount += 1;
    }
  }
  render({ preserveScroll: !atBottom });
}

function handlePersistenceAck(frame) {
  const key = typeof frame.idempotencyKey === "string" ? frame.idempotencyKey : "";
  if (!key) {
    return;
  }
  const pending = state.messages.find(
    (item) => item.idempotencyKey === key && (item.pending || item.failed)
  );
  if (!pending || !isAckCorrelatable(frame)) {
    return;
  }
  clearSendTimer(key);
  upsertServerMessage(reconcileAck(pending, frame));
  setFeedback(null);
  render({ preserveScroll: true });
}

function handleMessageError(frame) {
  const key = typeof frame.idempotencyKey === "string" ? frame.idempotencyKey : "";
  const reason = classifySendError(frame);
  if (reason === "inactive") {
    state.composerBlocked = true;
  }
  clearSendTimer(key);
  if (key) {
    markSendFailed(key);
  }
  setFeedback(reason);
}

function handleEvent({ destination, frame }) {
  if (!frame || typeof frame !== "object" || !eventForThisConversation(frame)) {
    return;
  }
  const type = String(frame.type ?? "").toUpperCase();
  if (
    destination === "/user/queue/messages" &&
    type === "MESSAGE_CREATED"
  ) {
    handleMessageCreated(frame);
    return;
  }
  if (
    (destination === "/user/queue/messages" || destination === "/user/queue/errors") &&
    type === "PERSISTENCE_ACK"
  ) {
    handlePersistenceAck(frame);
    return;
  }
  if (
    (destination === "/user/queue/messages" || destination === "/user/queue/errors") &&
    type === "MESSAGE_ERROR"
  ) {
    handleMessageError(frame);
  }
}

let markReadTimer = null;

function scheduleMarkRead() {
  if (markReadTimer !== null) {
    window.clearTimeout(markReadTimer);
  }
  markReadTimer = window.setTimeout(() => {
    markReadTimer = null;
    void markLatestRead();
  }, 750);
}

async function refreshConversationUnread() {
  const result = await getConversation(role, conversationId);
  if (state.destroyed || !result.ok) {
    return;
  }
  state.unreadCount = result.data.unreadCount;
  render({ preserveScroll: true });
}

async function markLatestRead() {
  if (
    !state.conversation ||
    state.destroyed ||
    document.visibilityState === "hidden"
  ) {
    return;
  }
  // Own messages are not a viewed position; only received messages advance it.
  const candidates = state.messages.filter(
    (message) => !message.pending && !message.failed && !isOwn(message)
  );
  const latest = candidates[candidates.length - 1];
  if (!latest) {
    return;
  }
  if (state.lastRead && compareMessages(latest, state.lastRead) <= 0) {
    return;
  }
  const result = await markRead(role, conversationId, latest.id);
  if (state.destroyed || !result.ok) {
    return;
  }
  state.lastRead = { id: latest.id, sentAt: latest.sentAt };
  state.unreadCount = 0;
  state.hasUnseen = false;
  render({ preserveScroll: true });
  void refreshConversationUnread();
}

async function recoverMissedMessages() {
  if (!state.conversation || state.destroyed) {
    return;
  }
  const options = state.recoveryCursor
    ? { after: state.recoveryCursor, limit: 100 }
    : { limit: 100 };
  const result = await getMessages(role, conversationId, options);
  if (state.destroyed || !result.ok) {
    return;
  }
  state.messages = mergeMessages(state.messages, result.data.messages);
  state.recoveryCursor = advanceRecoveryCursor(state.recoveryCursor, result.data);
  render({ preserveScroll: !isNearBottom() });
  if (isNearBottom()) {
    scheduleMarkRead();
  } else {
    state.hasUnseen = true;
  }
}

async function loadOlder() {
  if (!state.olderCursor || state.loadingOlder || state.destroyed) {
    return;
  }
  state.loadingOlder = true;
  const historyBefore = refs.history;
  const previousHeight = historyBefore?.scrollHeight ?? 0;
  const previousTop = historyBefore?.scrollTop ?? 0;
  render({ preserveScroll: true });

  const result = await getMessages(role, conversationId, {
    before: state.olderCursor,
    limit: 30
  });
  state.loadingOlder = false;
  if (state.destroyed || !result.ok) {
    setFeedback(result.ok ? null : result.reason);
    render({ preserveScroll: true });
    return;
  }
  state.messages = mergeMessages(state.messages, result.data.messages);
  state.olderCursor = result.data.nextCursor;
  render({ preserveScroll: true });
  if (historyBefore) {
    const added = historyBefore.scrollHeight - previousHeight;
    historyBefore.scrollTop = previousTop + Math.max(0, added);
  }
}

async function loadConversation() {
  const sequence = ++state.loadSequence;
  show(refs.guard, false);
  show(refs.critical, false);
  show(refs.workspace, false);
  show(refs.error, false);
  show(refs.loading, true);
  refs.loading?.setAttribute("aria-busy", "true");

  const detail = await getConversation(role, conversationId);
  if (state.destroyed || sequence !== state.loadSequence) {
    return;
  }
  if (!detail.ok) {
    refs.loading?.setAttribute("aria-busy", "false");
    if (detail.reason === "auth") {
      showGuard(
        "Your session has ended",
        "Sign in again to view this conversation."
      );
    } else if (detail.reason === "forbidden" || detail.reason === "not_found") {
      showGuard(
        "Conversation unavailable",
        "This conversation is private to its buyer and original assigned agent."
      );
    } else {
      showLoadError(detail.reason);
    }
    return;
  }

  const history = await getMessages(role, conversationId, { limit: 30 });
  if (state.destroyed || sequence !== state.loadSequence) {
    return;
  }
  refs.loading?.setAttribute("aria-busy", "false");
  if (!history.ok) {
    showLoadError(history.reason);
    return;
  }

  state.conversation = detail.data;
  state.unreadCount = detail.data.unreadCount;
  state.messages = mergeMessages([], history.data.messages);
  state.olderCursor = history.data.nextCursor;
  state.recoveryCursor = history.data.nextCursor;
  state.lastRead = null;
  state.hasUnseen = false;

  setText(refs.title, detail.data.propertyTitle || "Conversation");
  setText(
    refs.roleLabel,
    role === "BUYER" ? "Private thread with your property agent" : "Private thread with the buyer"
  );
  show(refs.property, true);
  setText(refs.propertyTitle, detail.data.propertyTitle || "Property");
  setText(
    refs.propertyStatus,
    detail.data.propertyStatus ? `Status: ${detail.data.propertyStatus}` : ""
  );

  show(refs.loading, false);
  show(refs.workspace, true);
  render();
  startConnection();
  void markLatestRead();
}

function destroyRuntime() {
  state.destroyed = true;
  state.loadSequence += 1;
  clear(refs.list);
  if (markReadTimer !== null) {
    window.clearTimeout(markReadTimer);
    markReadTimer = null;
  }
  state.sendTimers.forEach((timer) => window.clearTimeout(timer));
  state.sendTimers.clear();
  state.connection?.destroy();
  state.connection = null;
  if (state.teardownDisposer) {
    state.teardownDisposer();
    state.teardownDisposer = null;
  }
  resetChatService();
}

function handleAuth() {
  if (state.destroyed) {
    return;
  }
  const revision = getAuthRevision();
  if (state.startedRevision === null) {
    const snapshot = getAuthState();
    if (snapshot.status === "unknown") {
      showGuard(
        "Checking your session",
        "Confirming whether you can view this private conversation…",
        false
      );
      return;
    }
    state.startedRevision = revision;
    if (!allowed(snapshot)) {
      showGuard(
        snapshot.status === "authenticated"
          ? "Conversation unavailable"
          : "Sign in to view this conversation",
        "Only the active buyer or original assigned agent can open a property thread."
      );
      return;
    }
    if (!isUuid(conversationId)) {
      showCritical("Invalid conversation link", "Open the conversation from your conversations list.");
      return;
    }
    void loadConversation();
    return;
  }

  if (revision !== state.startedRevision) {
    destroyRuntime();
    showGuard(
      "Your session changed",
      "Reload the conversation to continue after signing in or out."
    );
  }
}

function handleSubmit(event) {
  event.preventDefault();
  sendMessage();
}

function handleNewMessages() {
  scrollToLatest();
  scheduleMarkRead();
}

function autoResizeInput() {
  if (!refs.input) {
    return;
  }
  refs.input.style.height = "auto";
  const lineHeight = 24;
  refs.input.style.height = `${Math.min(refs.input.scrollHeight, lineHeight * 5)}px`;
}

function handleInput() {
  updateCount();
  autoResizeInput();
  updateSendAvailability();
  if (refs.feedback && !refs.feedback.hidden) {
    setFeedback(null);
  }
}

function handleInputKeydown(event) {
  if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
    event.preventDefault();
    sendMessage();
  }
}

function handleVisibility() {
  if (document.visibilityState === "visible" && state.conversation && !state.destroyed) {
    scheduleMarkRead();
  }
}

state.unsubscribeAuth = subscribeAuth(handleAuth);
refs.composer?.addEventListener("submit", handleSubmit);
refs.input?.addEventListener("input", handleInput);
refs.input?.addEventListener("keydown", handleInputKeydown);
document.addEventListener("visibilitychange", handleVisibility);
refs.retryLoad?.addEventListener("click", () => {
  show(refs.error, false);
  void loadConversation();
});
refs.loadOlder?.addEventListener("click", () => void loadOlder());
refs.reconnect?.addEventListener("click", () => {
  setFeedback(null);
  state.connection?.retry();
});
refs.newMessages?.addEventListener("click", handleNewMessages);
function handlePageHide() {
  if (state.unsubscribeAuth) {
    state.unsubscribeAuth();
    state.unsubscribeAuth = null;
  }
  document.removeEventListener("visibilitychange", handleVisibility);
  destroyRuntime();
}

/**
 * A back/forward-cache restore reuses this document after `pagehide`. Rebuild
 * private runtime state instead of leaving a destroyed connection and stale
 * message DOM on the restored page.
 */
function handlePageShow(event) {
  if (!event?.persisted || !state.destroyed) {
    return;
  }
  state.destroyed = false;
  state.startedRevision = null;
  state.loadSequence += 1;
  clearPrivateState();
  setFeedback(null);
  show(refs.error, false);
  show(refs.banner, false);
  show(refs.newMessages, false);
  show(refs.workspace, false);
  document.addEventListener("visibilitychange", handleVisibility);
  if (!state.unsubscribeAuth) {
    state.unsubscribeAuth = subscribeAuth(handleAuth);
  }
  handleAuth();
}

window.addEventListener("pagehide", handlePageHide);
window.addEventListener("pageshow", handlePageShow);

autoResizeInput();
updateCount();
handleAuth();

export const teardown = () => {
  window.removeEventListener("pagehide", handlePageHide);
  window.removeEventListener("pageshow", handlePageShow);
  handlePageHide();
};
