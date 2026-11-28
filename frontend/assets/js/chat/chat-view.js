/**
 * Safe DOM rendering for chat lists and threads.
 *
 * Every server-provided value is inserted with textContent or setAttribute.
 * Message content is never parsed as HTML, and no URL is built from content.
 */

import {
  initialsFor,
  formatAbsolute,
  formatDateDivider,
  formatRelative,
  formatTime,
  textOrEmpty
} from "./chat-format.js";

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) {
    node.className = className;
  }
  if (text !== undefined && text !== null) {
    node.textContent = String(text);
  }
  return node;
}

function clear(node) {
  if (node) {
    node.replaceChildren();
  }
}

function initialsNode(conversation, role) {
  const name =
    role === "BUYER"
      ? textOrEmpty(conversation.propertyTitle)
      : textOrEmpty(conversation.propertyTitle);
  return element("span", "conversation-row__avatar", initialsFor(name));
}

function conversationHref(conversation, role) {
  return new URL(
    `/dashboard/${role.toLowerCase()}/conversation.html?id=${encodeURIComponent(conversation.conversationId)}`,
    window.location.origin
  ).href;
}

export function renderConversationList(container, conversations, options = {}) {
  if (!container) {
    return;
  }
  clear(container);
  const role = options.role === "AGENT" ? "AGENT" : "BUYER";
  const activeId = options.activeId ?? null;

  conversations.forEach((conversation) => {
    const link = element("a", "conversation-row");
    if (conversation.conversationId === activeId) {
      link.classList.add("is-active");
      link.setAttribute("aria-current", "page");
    }
    link.href = conversationHref(conversation, role);

    const body = element("span", "conversation-row__body");
    const participant = element(
      "span",
      "conversation-row__participant",
      role === "BUYER" ? "Property agent" : conversation.propertyTitle
    );
    const property = element("span", "conversation-row__property", conversation.propertyTitle);
    const preview = element("span", "conversation-row__preview");
    if (conversation.lastMessageId) {
      preview.textContent = "View latest message";
    } else {
      preview.textContent = "Start the conversation.";
    }
    body.append(participant, property, preview);

    const meta = element("span", "conversation-row__meta");
    meta.append(
      element(
        "span",
        "conversation-row__time",
        conversation.lastMessageAt ? formatRelative(conversation.lastMessageAt) : ""
      )
    );
    if (conversation.lastMessageAt) {
      meta.firstChild.title = formatAbsolute(conversation.lastMessageAt);
    }
    if (options.failedIds?.has(conversation.conversationId)) {
      meta.append(element("span", "conversation-row__failed", "Not sent"));
    }
    if (conversation.unreadCount > 0) {
      const unread = element("span", "conversation-row__unread", String(conversation.unreadCount));
      unread.setAttribute("aria-label", `${conversation.unreadCount} unread`);
      meta.append(unread);
    }

    link.append(initialsNode(conversation, role), body, meta);
    container.append(link);
  });
}

function messageLabel(message, currentUserId) {
  const sender = message.senderId && message.senderId === currentUserId ? "You" : "Other participant";
  const time = formatTime(message.sentAt);
  return `${sender}, ${time || "time unavailable"}`;
}

export function renderMessages(list, messages, options = {}) {
  if (!list) {
    return;
  }
  clear(list);
  const currentUserId = options.currentUserId ?? "";
  let lastDay = "";

  messages.forEach((message) => {
    const day = formatDateDivider(message.sentAt);
    if (day && day !== lastDay) {
      const divider = element("li", "ev-chat-date", day);
      divider.setAttribute("role", "separator");
      list.append(divider);
      lastDay = day;
    }

    const own = Boolean(message.senderId && message.senderId === currentUserId);
    const item = element("li", `msg ${own ? "msg--own" : "msg--other"}`);
    item.setAttribute("aria-label", messageLabel(message, currentUserId));
    item.dataset.messageId = message.id;
    if (message.idempotencyKey) {
      item.dataset.idempotencyKey = message.idempotencyKey;
    }
    if (message.pending) {
      item.classList.add("msg--sending");
    }
    if (message.failed) {
      item.classList.add("msg--failed");
    }

    if (!own && message.senderName) {
      item.append(element("span", "msg__sender", message.senderName));
    }
    item.append(element("p", "msg__bubble", message.content));

    const meta = element("span", "msg__meta");
    meta.append(
      element(
        "span",
        "msg__state",
        message.failed ? "Not sent" : message.pending ? "Sending" : own ? `Sent at ${formatTime(message.sentAt)}` : formatTime(message.sentAt)
      )
    );
    if (message.failed && typeof options.onRetry === "function") {
      const retry = element("button", "btn btn-link btn-sm msg__retry", "Retry");
      retry.type = "button";
      retry.setAttribute("aria-label", `Retry message: ${message.content.slice(0, 80)}`);
      retry.dataset.retryMessageId = message.id;
      retry.addEventListener("click", () => options.onRetry(message));
      meta.append(retry);
    }
    item.append(meta);
    list.append(item);
  });
}

export function setText(node, value) {
  if (node) {
    node.textContent = String(value ?? "");
  }
}

export function show(node, visible) {
  if (node) {
    node.hidden = !visible;
  }
}

export { clear };
