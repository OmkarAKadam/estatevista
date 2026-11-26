/**
 * Safe presentation helpers shared by every inquiry surface.
 *
 * All server-provided text is inserted with textContent. This module never
 * calls the API, never stores inquiry content and never decides authorization;
 * it only turns already-authorized service results into DOM nodes.
 */

import {
  actionLabel,
  allowedTransitions,
  statusLabel
} from "./inquiry-lifecycle.js";


const BADGE_CLASSES = Object.freeze({
  NEW: "ev-badge--accent",
  IN_PROGRESS: "ev-badge--primary",
  RESOLVED: "ev-badge--success",
  CLOSED: "ev-badge--neutral"
});

export function node(tag, options = {}, children = []) {
  const element = document.createElement(tag);
  const config = options || {};

  if (config.className) {
    element.className = config.className;
  }
  if (config.text !== undefined && config.text !== null) {
    element.textContent = String(config.text);
  }
  if (config.type) {
    element.type = config.type;
  }
  if (config.value !== undefined) {
    element.value = config.value;
  }
  if (config.attributes) {
    Object.entries(config.attributes).forEach(([name, value]) => {
      if (value !== undefined && value !== null && value !== false) {
        element.setAttribute(name, String(value));
      }
    });
  }
  if (config.dataset) {
    Object.entries(config.dataset).forEach(([name, value]) => {
      if (value !== undefined && value !== null) {
        element.dataset[name] = String(value);
      }
    });
  }

  const list = Array.isArray(children) ? children : [children];
  list.forEach((child) => {
    if (child === null || child === undefined || child === false) {
      return;
    }
    element.append(child instanceof Node ? child : document.createTextNode(String(child)));
  });
  return element;
}

export function clear(element) {
  if (!element) {
    return;
  }
  while (element.firstChild) {
    element.removeChild(element.firstChild);
  }
}

export function show(element, visible) {
  if (element) {
    element.hidden = !visible;
  }
}

export function statusBadge(status) {
  const badge = node("span", {
    className: `ev-badge ${BADGE_CLASSES[status] || "ev-badge--neutral"}`.trim(),
    text: statusLabel(status)
  });
  badge.dataset.status = typeof status === "string" ? status : "";
  return badge;
}

export function formatTimestamp(value) {
  if (typeof value !== "string" || value.trim().length === 0) {
    return "—";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "—";
  }
  return new Intl.DateTimeFormat("en-GB", {
    day: "2-digit",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit"
  }).format(date);
}

/** Curated messages only; backend text and inquiry content are never surfaced. */
export function describeFailure(result, activity = "load") {
  if (!result || typeof result !== "object") {
    return activity === "load"
      ? "We could not load inquiries. Please try again."
      : "The request could not be completed. Please try again.";
  }

  switch (result.reason) {
    case "rate_limit":
      return "Too many requests. Please wait before trying again.";
    case "auth":
    case "auth_changed":
      return "Your session has changed. Please sign in again.";
    case "forbidden":
      return "You do not have permission to view or change this inquiry.";
    case "unavailable":
      return "This inquiry is no longer available.";
    case "validation":
      return "Please check the information you entered and try again.";
    case "invalid_transition":
      return "That status change is no longer available for this inquiry.";
    case "conflict":
      return "This inquiry changed elsewhere. The latest saved status is shown below.";
    case "ambiguous":
      return "The outcome of the last change could not be confirmed. The latest saved status is shown below.";
    case "invalid_response":
      return "The service returned an unexpected response. Please try again.";
    case "cancelled":
    case "stale":
      return "";
    case "duplicate_submission":
      return "Inquiry submitted. Please wait for it to finish.";
    case "duplicate_mutation":
      return "A status change is already in progress.";
    default:
      return activity === "submit"
        ? "Your inquiry could not be sent. Please check your connection and try again."
        : activity === "mutate"
          ? "The status could not be changed. Please try again."
          : "We could not load inquiries. Please try again.";
  }
}

function openButton(item) {
  return node("button", {
    className: "btn btn-sm btn-outline-primary",
    text: "Open",
    type: "button",
    dataset: { action: "open", inquiryId: item.id }
  });
}

/** Returns table rows and compact card variants for the same authorized items. */
export function renderItems(items, { scope, onOpen } = {}) {
  const rows = [];
  const cards = [];
  const manager = scope === "agent" || scope === "admin";

  (Array.isArray(items) ? items : []).forEach((item) => {
    if (!item || typeof item.id !== "string") {
      return;
    }

    const handleOpen = () => {
      if (typeof onOpen === "function") {
        onOpen(item.id);
      }
    };

    const tr = node("tr", { dataset: { inquiryId: item.id } });
    tr.append(
      node("td", {}, [
        node("span", { className: "inquiry-row__title", text: item.propertyTitle || "Property no longer available" }),
        node("small", { className: "text-muted d-block", text: item.subject || "No subject" })
      ]),
      manager
        ? node("td", { text: item.buyerId || "—" })
        : null,
      node("td", {}, [statusBadge(item.status)]),
      node("td", { text: formatTimestamp(item.createdAt) }),
      node("td", {}, [openButton(item)])
    );
    rows.push(tr);

    const cardOpen = openButton(item);
    cards.push(
      node("article", { className: "inquiry-card", dataset: { inquiryId: item.id } }, [
        node("div", { className: "inquiry-card__head" }, [
          node("h3", { text: item.propertyTitle || "Property no longer available" }),
          statusBadge(item.status)
        ]),
        node("p", { className: "inquiry-card__meta", text: item.subject || "No subject" }),
        manager
          ? node("p", { className: "inquiry-card__meta", text: `Buyer account: ${item.buyerId || "—"}` })
          : null,
        node("p", { className: "inquiry-card__meta", text: `Received ${formatTimestamp(item.createdAt)}` }),
        node("div", { className: "inquiry-card__actions" }, [cardOpen])
      ])
    );
    cardOpen.addEventListener("click", handleOpen);
    tr.querySelector("button").addEventListener("click", handleOpen);
  });

  return { rows, cards };
}

function detailRow(label, value) {
  return node("div", { className: "inquiry-detail__row" }, [
    node("dt", { text: label }),
    node("dd", { text: value === null || value === undefined || value === "" ? "—" : String(value) })
  ]);
}

/**
 * Renders an authorized detail response. Status controls are created only for
 * managers and only for transitions approved by the shared lifecycle map.
 */
export function renderDetail(detail, { canManage = false, onAction, onResolvedAt } = {}) {
  const wrapper = node("div", { className: "inquiry-detail" });

  const summary = node("dl", { className: "inquiry-detail__grid" }, [
    detailRow("Status", statusLabel(detail.status)),
    detailRow("Property", detail.propertyTitle || "Property no longer available"),
    detailRow("Property ID", detail.propertyId),
    detailRow("Subject", detail.subject || ""),
    detailRow("Buyer account", detail.buyerId),
    detailRow("Assigned agent", detail.assignedAgentId),
    detailRow("Submitted", formatTimestamp(detail.createdAt)),
    detailRow("Updated", formatTimestamp(detail.updatedAt))
  ]);

  const resolvedValue = node("dd", {
    text: detail.resolvedAt ? formatTimestamp(detail.resolvedAt) : "—"
  });
  if (detail.resolvedAt) {
    resolvedValue.dataset.resolvedAt = detail.resolvedAt;
    if (typeof onResolvedAt === "function") {
      onResolvedAt(detail.resolvedAt);
    }
  }
  const resolvedRow = node("div", { className: "inquiry-detail__row" }, [
    node("dt", { text: "Resolved" }),
    resolvedValue
  ]);
  summary.append(resolvedRow);

  const message = node("p", {
    className: "inquiry-detail__message",
    text: typeof detail.message === "string" ? detail.message : ""
  });

  wrapper.append(
    summary,
    node("h3", { className: "inquiry-detail__subhead", text: "Message" }),
    message
  );

  if (!canManage) {
    return wrapper;
  }

  const actions = node("div", {
    className: "inquiry-detail__actions",
    attributes: { role: "group", "aria-label": "Inquiry status actions" }
  });

  allowedTransitions(detail.status).forEach((target) => {
    const button = node("button", {
      className: `btn btn-sm ${target === "CLOSED" ? "btn-outline-danger" : "btn-primary"}`,
      text: actionLabel(target),
      type: "button",
      dataset: { action: "status", target, inquiryId: detail.id }
    });
    button.addEventListener("click", () => {
      if (typeof onAction === "function") {
        onAction(target, detail.status);
      }
    });
    actions.append(button);
  });

  if (actions.childElementCount === 0) {
    actions.append(node("p", { className: "text-muted", text: "No further status changes are available." }));
  }

  wrapper.append(actions);
  return wrapper;
}
