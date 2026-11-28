/**
 * Buyer and agent conversation list controller.
 *
 * The list is REST-only. It never opens a socket, never writes private data to
 * storage, and renders every server value through the safe DOM helpers.
 */

import {
  getAuthRevision,
  getAuthState,
  subscribe as subscribeAuth
} from "../auth/auth-state.js";
import { describeChatError } from "./chat-format.js";
import { listConversations, reset as resetChatService } from "./chat-service.js";
import { clear, renderConversationList, setText, show } from "./chat-view.js";

const role =
  document.body && document.body.dataset.chatRole === "AGENT" ? "AGENT" : "BUYER";

const refs = {
  guard: document.querySelector("[data-chat-guard]"),
  guardTitle: document.querySelector("[data-chat-guard-title]"),
  guardMessage: document.querySelector("[data-chat-guard-message]"),
  loginLink: document.querySelector("[data-chat-login-link]"),
  content: document.querySelector("[data-chat-content]"),
  summary: document.querySelector("[data-chat-summary]"),
  error: document.querySelector("[data-chat-error]"),
  errorMessage: document.querySelector("[data-chat-error-message]"),
  retry: document.querySelector("[data-chat-retry]"),
  empty: document.querySelector("[data-chat-empty]"),
  list: document.querySelector("[data-chat-conversation-list]")
};

let activeRevision = null;
let loadSequence = 0;
let disposed = false;

function showGuard(title, message) {
  show(refs.content, false);
  show(refs.guard, true);
  setText(refs.guardTitle, title);
  setText(refs.guardMessage, message);
  if (refs.loginLink) {
    refs.loginLink.href = role === "AGENT" ? "../../login.html" : "../../login.html";
  }
}

function showError(reason) {
  show(refs.guard, false);
  show(refs.content, true);
  show(refs.empty, false);
  show(refs.list, false);
  clear(refs.list);
  setText(refs.summary, "");
  setText(refs.errorMessage, describeChatError(reason));
  show(refs.error, true);
}

function allowed(state) {
  return Boolean(
    state.status === "authenticated" &&
      state.user &&
      state.user.role === role &&
      (!state.user.accountStatus || state.user.accountStatus === "ACTIVE")
  );
}

async function load() {
  const sequence = ++loadSequence;
  show(refs.guard, false);
  show(refs.error, false);
  show(refs.empty, false);
  show(refs.content, true);
  show(refs.list, true);
  setText(refs.summary, "Loading conversations…");
  refs.content?.setAttribute("aria-busy", "true");

  const result = await listConversations(role);
  if (disposed || sequence !== loadSequence) {
    return;
  }
  refs.content?.setAttribute("aria-busy", "false");

  if (!result.ok) {
    if (result.reason === "auth") {
      showGuard(
        "Your session has ended",
        "Sign in again to view your private conversations."
      );
      return;
    }
    if (result.reason === "forbidden" || result.reason === "not_found") {
      showGuard(
        "Conversations are not available for this account",
        "Only active buyers and agents can use private property conversations."
      );
      return;
    }
    showError(result.reason);
    return;
  }

  const conversations = result.data;
  renderConversationList(refs.list, conversations, {
    role,
    currentUserId: getAuthState().user?.id ?? ""
  });
  show(refs.list, true);
  show(refs.empty, conversations.length === 0);
  const unread = conversations.reduce((total, item) => total + item.unreadCount, 0);
  setText(
    refs.summary,
    conversations.length === 0
      ? "No conversations yet."
      : `${conversations.length} conversation${conversations.length === 1 ? "" : "s"}${
          unread > 0 ? ` · ${unread} unread` : ""
        }`
  );
}

function handleAuth() {
  if (disposed) {
    return;
  }
  const revision = getAuthRevision();
  if (revision === activeRevision) {
    return;
  }
  const changed = activeRevision !== null;
  activeRevision = revision;
  loadSequence += 1;
  if (changed) {
    resetChatService();
  }

  const state = getAuthState();
  if (state.status === "unknown") {
    showGuard("Checking your session", "Confirming whether you can view private conversations…");
    return;
  }
  if (!allowed(state)) {
    showGuard(
      state.status === "authenticated"
        ? "Conversations are not available for this account"
        : "Sign in to view your conversations",
      state.status === "authenticated"
        ? "Only the active buyer or original assigned agent can view a property thread."
        : "Private conversations require an EstateVista account."
    );
    return;
  }
  void load();
}

function handlePageHide() {
  disposed = true;
  loadSequence += 1;
  resetChatService();
}

function handlePageShow() {
  if (!disposed) {
    return;
  }
  disposed = false;
  activeRevision = null;
  handleAuth();
}

const unsubscribeAuth = subscribeAuth(handleAuth);
window.addEventListener("pagehide", handlePageHide);
window.addEventListener("pageshow", handlePageShow);

refs.retry?.addEventListener("click", () => {
  show(refs.error, false);
  void load();
});

/* Keep a reference so bundlers and static analysis do not drop the listener
   registration; no private data is retained by this module. */
export const teardown = () => {
  unsubscribeAuth();
  window.removeEventListener("pagehide", handlePageHide);
  window.removeEventListener("pageshow", handlePageShow);
  handlePageHide();
};
