/**
 * Shared shell, guard, confirmation, and error presentation utilities for
 * Stage 4.7 property-management pages.
 *
 * Role checks here are presentation-only. The Spring Boot backend remains the
 * authorization boundary for every API call.
 */

import { ApiError } from "../core/api.js";
import { clearSessionState } from "../auth/auth-service.js";
import { requireAuthentication } from "../auth/auth-ui.js";
import { ROLES } from "./constants.js";
import { element } from "./dom.js";

let expiredRedirectInFlight = false;

export function redirectToLogin({ expired = false } = {}) {
  if (expiredRedirectInFlight) {
    return;
  }
  expiredRedirectInFlight = true;

  const login = new URL("/login.html", window.location.origin);
  const intended = `${window.location.pathname}${window.location.search}${window.location.hash}`;
  login.searchParams.set("next", intended);
  if (expired) {
    login.searchParams.set("expired", "1");
  }
  window.location.replace(login.href);
}

/**
 * Guard a management page. Anonymous visitors follow the existing Stage 3
 * login flow; signed-in users without a management role see an access-denied
 * panel and no backend data is requested.
 */
export async function requireManagementRole(allowedRoles) {
  const user = await requireAuthentication({ redirectTo: "/login.html" });
  if (!user) {
    return null;
  }

  if (!allowedRoles.includes(user.role)) {
    showAccessDenied();
    return null;
  }

  const content = document.querySelector("[data-management-content]");
  if (content) {
    content.hidden = false;
  }
  return user;
}

export function showAccessDenied() {
  const content = document.querySelector("[data-management-content]");
  if (content) {
    content.hidden = true;
  }
  const panel = document.querySelector("[data-access-denied]");
  if (panel) {
    panel.textContent = "You do not have access to this management area. Contact an administrator if you believe this is a mistake.";
    panel.hidden = false;
    if (typeof panel.focus === "function") {
      panel.setAttribute("tabindex", "-1");
      panel.focus();
    }
  }
}

const SIDEBAR_ITEMS = Object.freeze({
  [ROLES.ADMIN]: Object.freeze([
    { key: "overview", label: "Overview", href: "/index.html" },
    { key: "properties", label: "Properties", href: "./properties.html" },
    { key: "assignments", label: "Assignments", href: "./property-assignments.html" },
    { key: "inquiries", label: "Inquiries", href: "./inquiries.html" },
    { key: "help", label: "Help", href: "/about.html" }
  ]),
  [ROLES.AGENT]: Object.freeze([
    { key: "overview", label: "Overview", href: "/index.html" },
    { key: "properties", label: "Properties", href: "./properties.html" },
    { key: "inquiries", label: "Inquiries", href: "./inquiries.html" },
    { key: "conversations", label: "Conversations", href: "./conversations.html" },
    { key: "help", label: "Help", href: "/about.html" }
  ])
});

/**
 * Populate the compact dashboard shell. Sidebar navigation is rendered with
 * textContent and shares the existing logout control from auth-ui.js.
 */
export function initManagementShell({ role, active = "properties", title = "" }) {
  const sidebar = document.querySelector("[data-dash-sidebar]");
  if (sidebar) {
    const heading = element("p", {
      className: "dash-sidebar__eyebrow",
      text: role === ROLES.ADMIN ? "Administrator" : "Agent"
    });
    const brand = element("a", { className: "dash-sidebar__brand", attributes: { href: "/index.html" } });
    brand.append(
      element("img", {
        attributes: {
          src: "/assets/images/brand/favicon.svg",
          alt: "",
          width: "32",
          height: "32",
          "aria-hidden": "true"
        }
      }),
      element("span", { text: "EstateVista" })
    );

    const list = element("ul", { className: "dash-sidebar__list" });
    (SIDEBAR_ITEMS[role] || []).forEach((item) => {
      const link = element("a", {
        className: "dash-sidebar__link",
        text: item.label,
        attributes: { href: item.href }
      });
      if (item.key === active) {
        link.setAttribute("aria-current", "page");
      }
      list.append(element("li", { className: "dash-sidebar__item" }, [link]));
    });

    const logout = element("button", {
      className: "btn btn-secondary btn-sm dash-sidebar__logout",
      text: "Log out",
      type: "button"
    });
    logout.dataset.authLogout = "";

    clear(sidebar);
    sidebar.append(
      brand,
      heading,
      element("nav", { attributes: { "aria-label": "Management" } }, [list]),
      element("div", { className: "dash-sidebar__footer" }, [logout])
    );
  }

  const roleBadge = document.querySelector("[data-dash-role]");
  if (roleBadge) {
    roleBadge.textContent = role === ROLES.ADMIN ? "Administrator" : "Agent";
  }
  const topbarTitle = document.querySelector("[data-dash-title]");
  if (topbarTitle && title) {
    topbarTitle.textContent = title;
  }

  const toggle = document.querySelector("[data-dash-menu-toggle]");
  const scrim = document.querySelector("[data-dash-scrim]");
  if (toggle && sidebar) {
    const setOpen = (open) => {
      sidebar.classList.toggle("is-open", open);
      toggle.setAttribute("aria-expanded", open ? "true" : "false");
      if (scrim) {
        scrim.hidden = !open;
      }
    };

    toggle.addEventListener("click", () => {
      setOpen(!sidebar.classList.contains("is-open"));
    });

    if (scrim) {
      scrim.addEventListener("click", () => {
        setOpen(false);
        toggle.focus();
      });
    }

    document.addEventListener("keydown", (event) => {
      if (event.key === "Escape" && sidebar.classList.contains("is-open")) {
        setOpen(false);
        toggle.focus();
      }
    });
  }
}

/**
 * Curated messages only. Backend text is never displayed verbatim.
 */
export function messageForError(error, context = "request") {
  if (!(error instanceof ApiError)) {
    return context === "load"
      ? "We could not load this information. Check your connection and try again."
      : "We could not reach EstateVista. Check your connection and try again.";
  }

  switch (error.status) {
    case 0:
      return "We could not reach EstateVista. Check your connection and try again.";
    case 400:
      return "Please check the highlighted fields and try again.";
    case 401:
      return "Your session expired. Please sign in again.";
    case 403:
      return "You do not have permission to perform this action.";
    case 404:
      return "This property is no longer available.";
    case 409:
      return "This action conflicts with the property's current state. Refresh and try again.";
    case 413:
      return "That file is too large. Choose an image of 5 MB or less.";
    case 415:
      return "Only JPEG, PNG, and WebP images are supported.";
    case 422:
      return "This property does not meet the requirements for publication. Review the highlighted details.";
    case 429:
      return "Too many requests. Try again shortly.";
    case 503:
      return "Image storage is temporarily unavailable. Try again later.";
    default:
      if (error.status >= 500) {
        return "The server could not complete this request. Try again later.";
      }
      return "The request could not be completed. Please try again.";
  }
}

/**
 * Handle session expiration and forbidden responses centrally.
 * Returns true when the caller should stop processing the error.
 */
export function handleApiError(error, handlers = {}) {
  if (!(error instanceof ApiError)) {
    return false;
  }

  if (error.status === 401) {
    clearSessionState();
    redirectToLogin({ expired: true });
    return true;
  }
  if (error.status === 403) {
    if (typeof handlers.onForbidden === "function") {
      handlers.onForbidden(error);
    }
    return true;
  }
  if (error.status === 404 && typeof handlers.onNotFound === "function") {
    handlers.onNotFound(error);
    return true;
  }
  return false;
}

/**
 * Accessible confirmation dialog backed by the native <dialog> element, which
 * provides focus trapping and Escape handling. All text is inserted safely.
 */
export function confirmDialog({ title, message, confirmLabel = "Confirm", destructive = false }) {
  return new Promise((resolve) => {
    const previousFocus = document.activeElement;
    const dialog = element("dialog", {
      className: "ev-modal",
      attributes: { "aria-labelledby": "evConfirmTitle", "aria-describedby": "evConfirmMessage" }
    });

    const heading = element("h2", {
      id: "evConfirmTitle",
      className: "ev-modal__title",
      text: title
    });
    const body = element("p", {
      id: "evConfirmMessage",
      className: "ev-modal__body",
      text: message
    });

    const cancel = element("button", {
      className: "btn btn-secondary",
      text: "Cancel",
      type: "button"
    });
    const accept = element("button", {
      className: destructive ? "btn btn-accent" : "btn btn-primary",
      text: confirmLabel,
      type: "button"
    });

    const actions = element("div", { className: "ev-modal__actions" }, [cancel, accept]);
    const form = element("form", { attributes: { method: "dialog" } }, [heading, body, actions]);
    dialog.append(form);
    document.body.append(dialog);

    let settled = false;
    function finish(result) {
      if (settled) {
        return;
      }
      settled = true;
      resolve(result);
      if (typeof dialog.close === "function" && dialog.open) {
        dialog.close();
      }
      dialog.remove();
      if (previousFocus && typeof previousFocus.focus === "function") {
        previousFocus.focus();
      }
    }

    cancel.addEventListener("click", () => finish(false));
    accept.addEventListener("click", () => finish(true));
    dialog.addEventListener("cancel", (event) => {
      event.preventDefault();
      finish(false);
    });

    if (typeof dialog.showModal === "function") {
      dialog.showModal();
    } else {
      dialog.setAttribute("open", "");
    }
    accept.focus();
  });
}

export function toast(message, kind = "success") {
  const region = document.querySelector("[data-toast-region]");
  if (!region) {
    return;
  }
  const node = element("div", {
    className: `ev-toast ev-toast--${kind}`,
    text: message,
    attributes: {
      role: kind === "error" ? "alert" : "status",
      tabindex: "-1"
    }
  });
  region.append(node);
  node.focus();
  if (kind !== "error") {
    window.setTimeout(() => node.remove(), 5000);
  }
}

export function clear(node) {
  if (!node) {
    return;
  }
  while (node.firstChild) {
    node.removeChild(node.firstChild);
  }
}
