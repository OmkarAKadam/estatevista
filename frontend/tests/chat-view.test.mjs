/**
 * Dependency-free safe-DOM checks for Stage 7.5 chat rendering.
 * Run with: node --test frontend/tests/chat-view.test.mjs
 *
 * A minimal DOM stub records text nodes and attributes. It deliberately has no
 * innerHTML implementation: any attempt to interpret server content as markup
 * would fail these tests.
 */
import test from "node:test";
import assert from "node:assert/strict";

class FakeNode {
  constructor(tagName) {
    this.tagName = String(tagName).toUpperCase();
    this.children = [];
    this.attributes = Object.create(null);
    this.dataset = Object.create(null);
    this.listeners = Object.create(null);
    this._text = "";
    this.className = "";
    this.hidden = false;
    this.href = "";
    this.title = "";
    this.type = "";
    this.parent = null;
    this.classes = new Set();
    const self = this;
    this.classList = Object.freeze({
      add(...names) {
        String(self.className ?? "")
          .split(/\s+/)
          .filter(Boolean)
          .forEach((name) => self.classes.add(name));
        names.forEach((name) => {
          self.classes.add(name);
        });
        self.className = [...self.classes].join(" ");
      },
      contains(name) {
        return self.classes.has(name);
      }
    });
  }

  set textContent(value) {
    this._text = value === undefined || value === null ? "" : String(value);
    this.children = [];
  }

  get textContent() {
    return this._text + this.children.map((child) => child.textContent).join("");
  }

  append(...nodes) {
    nodes.forEach((node) => {
      if (node === null || node === undefined) {
        return;
      }
      node.parent = this;
      this.children.push(node);
    });
  }

  replaceChildren(...nodes) {
    this._text = "";
    this.children = [];
    this.append(...nodes);
  }

  setAttribute(name, value) {
    this.attributes[name] = String(value);
    if (name.startsWith("data-")) {
      const key = name.slice(5).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
      this.dataset[key] = String(value);
    }
  }

  getAttribute(name) {
    return Object.prototype.hasOwnProperty.call(this.attributes, name)
      ? this.attributes[name]
      : null;
  }

  addEventListener(type, listener) {
    (this.listeners[type] ??= []).push(listener);
  }

  get firstChild() {
    return this.children[0] ?? null;
  }
}

globalThis.window = {
  location: {
    origin: "http://localhost:5500",
    hostname: "localhost",
    href: "http://localhost:5500/",
    pathname: "/",
    search: ""
  }
};
globalThis.document = Object.freeze({
  createElement: (tag) => new FakeNode(tag)
});

const { renderConversationList, renderMessages, setText, show } = await import(
  "../assets/js/chat/chat-view.js"
);

const ID = "11111111-1111-1111-8111-111111111111";
const KEY = "22222222-2222-2222-8222-222222222222";
const USER = "33333333-3333-3333-8333-333333333333";

function message(overrides = {}) {
  return {
    id: ID,
    conversationId: "44444444-4444-4444-8444-444444444444",
    senderId: USER,
    messageType: "TEXT",
    content: "Hello",
    idempotencyKey: KEY,
    sentAt: "2026-11-28T10:00:00Z",
    pending: false,
    failed: false,
    ...overrides
  };
}

function hasClass(node, className) {
  return Boolean(
    node &&
      (node.classes?.has(className) ||
        String(node.className ?? "")
          .split(/\s+/)
          .includes(className))
  );
}

function findByClass(node, className) {
  for (const child of node.children ?? []) {
    if (hasClass(child, className)) {
      return child;
    }
    const nested = findByClass(child, className);
    if (nested) {
      return nested;
    }
  }
  return null;
}

function findMessageItem(list) {
  return list.children.find((child) => hasClass(child, "msg"));
}

test("message content is inserted as inert text, never parsed as markup", () => {
  const payload = '<img src=x onerror="alert(1)"><script>alert(2)</script>';
  const list = new FakeNode("ul");
  renderMessages(list, [message({ content: payload })], { currentUserId: USER });

  const bubble = findByClass(findMessageItem(list), "msg__bubble");
  assert.equal(bubble.textContent, payload);
  assert.equal(bubble.children.length, 0);
  assert.equal(Object.prototype.hasOwnProperty.call(bubble, "innerHTML"), false);
  assert.equal(bubble.tagName, "P");
});

test("messages expose sender, time, idempotency key and safe retry controls", () => {
  let retried = null;
  const list = new FakeNode("ul");
  renderMessages(
    list,
    [message({ failed: true, content: "Not sent yet" })],
    {
      currentUserId: USER,
      onRetry: (item) => {
        retried = item;
      }
    }
  );

  const item = findMessageItem(list);
  assert.match(item.getAttribute("aria-label"), /^You, /);
  assert.equal(item.dataset.messageId, ID);
  assert.equal(item.dataset.idempotencyKey, KEY);
  const retry = findByClass(item, "msg__retry");
  assert.equal(retry.dataset.retryMessageId, ID);
  assert.match(retry.getAttribute("aria-label"), /^Retry message: Not sent yet$/);
  retry.listeners.click[0]();
  assert.equal(retried.id, ID);
});

test("incoming messages are labelled as the other participant", () => {
  const list = new FakeNode("ul");
  renderMessages(
    list,
    [message({ senderId: "99999999-9999-9999-8999-999999999999", senderName: "Agent Rivera" })],
    {
      currentUserId: USER
    }
  );
  const item = findMessageItem(list);
  assert.equal(hasClass(item, "msg--other"), true);
  assert.equal(findByClass(item, "msg__sender").textContent, "Agent Rivera");
  assert.match(item.getAttribute("aria-label"), /^Other participant, /);
});

test("conversation links are encoded URLs with accessible unread counts", () => {
  const list = new FakeNode("ul");
  renderConversationList(
    list,
    [
      {
        conversationId: ID,
        propertyTitle: '<img src=x onerror="alert(1)">',
        lastMessageId: KEY,
        lastMessageAt: "2026-11-28T10:00:00Z",
        unreadCount: 3
      }
    ],
    { role: "BUYER", currentUserId: USER }
  );

  const link = list.children[0];
  assert.equal(
    link.href,
    `http://localhost:5500/dashboard/buyer/conversation.html?id=${ID}`
  );
  assert.equal(link.tagName, "A");
  const unread = findByClass(link, "conversation-row__unread");
  assert.equal(unread.textContent, "3");
  assert.equal(unread.getAttribute("aria-label"), "3 unread");
  const preview = findByClass(link, "conversation-row__preview");
  assert.equal(preview.textContent, "View latest message");
  assert.equal(preview.children.length, 0);
});

test("safe text and visibility helpers do not introduce markup", () => {
  const node = new FakeNode("p");
  setText(node, "<b>private</b>");
  assert.equal(node.textContent, "<b>private</b>");
  assert.equal(node.children.length, 0);
  show(node, false);
  assert.equal(node.hidden, true);
  show(node, true);
  assert.equal(node.hidden, false);
});
