/**
 * Safe DOM and formatting helpers for Stage 4.7 management pages.
 *
 * Every untrusted string is written with textContent. No backend message,
 * property title, description, alt text, or file name is ever passed to
 * innerHTML or inserted through an HTML sink.
 */

import { statusBadgeClass, statusLabel } from "./constants.js";

export const PLACEHOLDER_IMAGE = "/assets/images/brand/property-placeholder.svg";

export function element(tag, options = {}, children = []) {
  const node = document.createElement(tag);
  const config = options || {};

  if (config.className) {
    node.className = config.className;
  }
  if (config.id) {
    node.id = String(config.id);
  }
  if (config.text !== undefined && config.text !== null) {
    node.textContent = String(config.text);
  }
  if (config.type) {
    node.type = config.type;
  }
  if (config.value !== undefined) {
    node.value = config.value;
  }
  if (config.attributes) {
    Object.entries(config.attributes).forEach(([name, value]) => {
      if (value !== undefined && value !== null && value !== false) {
        node.setAttribute(name, String(value));
      }
    });
  }
  if (config.dataset) {
    Object.entries(config.dataset).forEach(([name, value]) => {
      node.dataset[name] = String(value);
    });
  }

  const list = Array.isArray(children) ? children : [children];
  list.forEach((child) => {
    if (child === null || child === undefined || child === false) {
      return;
    }
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  });

  return node;
}

export function clear(node) {
  if (!node) {
    return;
  }
  while (node.firstChild) {
    node.removeChild(node.firstChild);
  }
}

export function byId(id) {
  return document.getElementById(id);
}

export function query(selector, root = document) {
  return root ? root.querySelector(selector) : null;
}

export function safeImageUrl(value) {
  if (typeof value !== "string" || value.trim().length === 0) {
    return PLACEHOLDER_IMAGE;
  }

  try {
    const parsed = new URL(value, window.location.origin);
    if (parsed.protocol === "https:" || parsed.protocol === "http:") {
      return parsed.href;
    }
  } catch {
    // Fall through to the approved placeholder.
  }

  return PLACEHOLDER_IMAGE;
}

export function statusBadge(status) {
  const badge = element("span", {
    className: `ev-badge ${statusBadgeClass(status)}`.trim(),
    text: statusLabel(status)
  });
  badge.dataset.status = String(status || "");
  return badge;
}

export function formatPrice(value, currency = "INR") {
  const amount = Number(value);
  if (!Number.isFinite(amount)) {
    return "Price on request";
  }

  try {
    return new Intl.NumberFormat("en-IN", {
      style: "currency",
      currency,
      maximumFractionDigits: amount % 1 === 0 ? 0 : 2
    }).format(amount);
  } catch {
    return `${currency} ${amount.toLocaleString("en-IN")}`;
  }
}

export function formatArea(value, unit = "") {
  const amount = Number(value);
  if (!Number.isFinite(amount)) {
    return "—";
  }
  const formatted = amount.toLocaleString("en-IN", { maximumFractionDigits: 2 });
  return unit ? `${formatted} ${unit}` : formatted;
}

export function formatDate(value) {
  if (typeof value !== "string" || value.trim().length === 0) {
    return "—";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "—";
  }
  return new Intl.DateTimeFormat("en-IN", {
    year: "numeric",
    month: "short",
    day: "numeric"
  }).format(date);
}

export function agentName(agent) {
  if (!agent || typeof agent !== "object") {
    return "—";
  }
  const full = `${agent.firstName || ""} ${agent.lastName || ""}`.trim();
  return full || "—";
}

export function initials(firstName, lastName) {
  const first = typeof firstName === "string" && firstName.trim() ? firstName.trim()[0] : "";
  const last = typeof lastName === "string" && lastName.trim() ? lastName.trim()[0] : "";
  return `${first}${last}`.toUpperCase() || "EV";
}

export function descriptionList(rows) {
  const list = element("dl", { className: "mgmt-dl" });
  rows.forEach(([term, value]) => {
    list.append(element("dt", { text: term }));
    list.append(element("dd", { text: value === null || value === undefined || value === "" ? "—" : String(value) }));
  });
  return list;
}

export function emptyState(title, message, action) {
  const wrapper = element("div", { className: "ev-empty" });
  wrapper.append(element("h3", { text: title }), element("p", { text: message }));
  if (action) {
    wrapper.append(action);
  }
  return wrapper;
}
