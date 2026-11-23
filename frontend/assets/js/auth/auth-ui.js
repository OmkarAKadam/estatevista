/**
 * Authentication-aware navigation, shared form helpers, and the optional page
 * guard. This module contains presentation logic only; it never decides whether
 * a user is authorized for a backend operation.
 */

import { initializeSession, logout } from "./auth-service.js";
import { getAuthState, subscribe } from "./auth-state.js";

let navigationInitialized = false;

function createElement(tagName, className, text) {
  const element = document.createElement(tagName);
  if (className) {
    element.className = className;
  }
  if (text !== undefined) {
    element.textContent = text;
  }
  return element;
}

function empty(element) {
  while (element.firstChild) {
    element.removeChild(element.firstChild);
  }
}

function authLink(container, kind, fallbackHref, label, className) {
  const link = createElement("a", className, label);
  const configured =
    kind === "login"
      ? container.dataset.authLoginHref
      : container.dataset.authRegisterHref;
  link.href = configured || fallbackHref;
  return link;
}

/**
 * Render every `[data-auth-nav]` container from the current in-memory state.
 *
 * Anonymous and unknown states show the public sign-in controls. The
 * authenticated state shows the backend-provided first name and a logout
 * control. Roles are never used to reveal unimplemented features.
 */
export function renderAuthNavigation(state = getAuthState()) {
  const containers = document.querySelectorAll("[data-auth-nav]");

  containers.forEach((container) => {
    empty(container);

    const error = createElement("p", "site-auth__error");
    error.hidden = true;
    error.tabIndex = -1;
    error.setAttribute("role", "alert");
    error.dataset.authError = "";

    if (state.status === "authenticated" && state.user) {
      const user = state.user;
      const greeting = createElement("span", "site-auth__greeting");
      const name = createElement("strong", null, user.firstName || "your account");
      greeting.append("Signed in as ", name);

      const logoutButton = createElement(
        "button",
        "btn btn-secondary btn-sm site-auth__logout",
        "Log out"
      );
      logoutButton.type = "button";
      logoutButton.dataset.authLogout = "";

      container.append(greeting, logoutButton, error);
      return;
    }

    container.append(
      authLink(container, "login", "login.html", "Log in", "site-auth__link"),
      authLink(
        container,
        "register",
        "register.html",
        "Create account",
        "btn btn-primary btn-sm site-auth__cta"
      ),
      error
    );
  });
}

async function handleLogoutClick(button) {
  const container = button.closest("[data-auth-nav]");
  const error = container ? container.querySelector("[data-auth-error]") : null;
  const target = (container && container.dataset.authHomeHref) || "index.html";
  const originalLabel = button.textContent;

  if (error) {
    error.hidden = true;
    error.textContent = "";
  }

  button.disabled = true;
  button.textContent = "Logging out…";

  try {
    await logout();
    window.location.assign(target);
  } catch {
    button.disabled = false;
    button.textContent = originalLabel;

    if (error) {
      error.textContent =
        "We could not end your session. Please check your connection and try again.";
      error.hidden = false;
      error.focus();
    }
  }
}

/**
 * Connect navigation rendering and the logout control for the current page.
 */
export function initAuthNavigation() {
  if (navigationInitialized) {
    return;
  }
  navigationInitialized = true;

  subscribe(renderAuthNavigation);
  renderAuthNavigation(getAuthState());

  document.addEventListener("click", (event) => {
    const target = event.target;
    const button =
      target && target.closest ? target.closest("[data-auth-logout]") : null;

    if (!button) {
      return;
    }

    event.preventDefault();
    if (button.disabled) {
      return;
    }
    handleLogoutClick(button);
  });
}

/* --------------------------------------------------------------------------
   Shared form helpers
   -------------------------------------------------------------------------- */

export function showAlert(element, message, variant = "error") {
  if (!element) {
    return;
  }
  element.textContent = message;
  element.hidden = false;
  element.classList.remove(
    "auth-alert--error",
    "auth-alert--success",
    "auth-alert--info"
  );
  element.classList.add(`auth-alert--${variant}`);
  element.setAttribute("role", variant === "error" ? "alert" : "status");
}

export function hideAlert(element) {
  if (!element) {
    return;
  }
  element.hidden = true;
  element.textContent = "";
}

export function setFormBusy(form, button, busy, busyLabel) {
  if (form) {
    form.setAttribute("aria-busy", busy ? "true" : "false");
  }
  if (!button) {
    return;
  }

  if (busy) {
    if (!button.dataset.idleLabel) {
      button.dataset.idleLabel = button.textContent;
    }
    button.textContent = busyLabel || "Please wait…";
    button.disabled = true;
  } else {
    if (button.dataset.idleLabel) {
      button.textContent = button.dataset.idleLabel;
      delete button.dataset.idleLabel;
    }
    button.disabled = false;
  }
}

function fieldErrorElement(input) {
  if (!input || !input.id) {
    return null;
  }
  return (
    document.getElementById(`${input.id}-error`) ||
    document.querySelector(`[data-error-for="${input.id}"]`)
  );
}

export function setFieldError(input, message) {
  if (!input) {
    return;
  }

  input.setAttribute("aria-invalid", "true");
  input.classList.add("is-invalid");

  const error = fieldErrorElement(input);
  if (error) {
    error.textContent = message;
    error.hidden = false;

    const errorId = error.id;
    if (errorId) {
      const describedBy = (input.getAttribute("aria-describedby") || "")
        .split(/\s+/)
        .filter(Boolean);
      if (!describedBy.includes(errorId)) {
        describedBy.push(errorId);
        input.setAttribute("aria-describedby", describedBy.join(" "));
      }
    }
  }
}

export function clearFieldError(input) {
  if (!input) {
    return;
  }

  input.removeAttribute("aria-invalid");
  input.classList.remove("is-invalid");

  const error = fieldErrorElement(input);
  if (error) {
    error.textContent = "";
    error.hidden = true;
  }
}

export function clearFieldErrors(form) {
  if (!form) {
    return;
  }

  form.querySelectorAll("[aria-invalid='true']").forEach((input) => {
    input.removeAttribute("aria-invalid");
    input.classList.remove("is-invalid");
  });

  form.querySelectorAll("[data-field-error]").forEach((error) => {
    error.textContent = "";
    error.hidden = true;
  });
}

/**
 * Map backend validation details onto form fields. Returns true when at least
 * one detail matched a field.
 */
export function applyApiFieldErrors(form, error) {
  if (!form || !error || !Array.isArray(error.details)) {
    return false;
  }

  let matched = false;

  error.details.forEach((detail) => {
    if (!detail || typeof detail.field !== "string") {
      return;
    }

    const input = form.elements.namedItem(detail.field);
    if (
      input instanceof HTMLInputElement ||
      input instanceof HTMLTextAreaElement ||
      input instanceof HTMLSelectElement
    ) {
      setFieldError(input, detail.message || "Please check this value.");
      matched = true;
    }
  });

  return matched;
}

export function focusFirstInvalid(form) {
  if (!form) {
    return false;
  }
  const first = form.querySelector("[aria-invalid='true']");
  if (first && typeof first.focus === "function") {
    first.focus();
    return true;
  }
  return false;
}

/* --------------------------------------------------------------------------
   Safe redirects and the optional page guard
   -------------------------------------------------------------------------- */

function isAuthPage(pathname) {
  return /\/?(login|register)\.html$/i.test(pathname);
}

/**
 * Resolve a post-login destination without allowing an open redirect.
 * Only same-origin HTTP(S) pages are accepted, and auth pages are replaced by
 * the fallback to avoid redirect loops.
 */
export function resolveSameOriginTarget(rawValue, fallback = "index.html") {
  const fallbackUrl = new URL(fallback, window.location.href);

  if (typeof rawValue !== "string" || rawValue.trim().length === 0) {
    return fallbackUrl;
  }

  let candidate;
  try {
    candidate = new URL(rawValue, window.location.href);
  } catch {
    return fallbackUrl;
  }

  if (candidate.origin !== window.location.origin) {
    return fallbackUrl;
  }
  if (candidate.protocol !== "http:" && candidate.protocol !== "https:") {
    return fallbackUrl;
  }
  if (candidate.username || candidate.password) {
    return fallbackUrl;
  }
  if (isAuthPage(candidate.pathname)) {
    return fallbackUrl;
  }

  return candidate;
}

/**
 * Frontend guard for pages that require an authenticated session.
 *
 * This improves the visitor experience only. The backend remains the security
 * boundary for every protected operation. No current Stage 3.7 page requires
 * authentication, so this utility is provided for later user-facing areas.
 */
export async function requireAuthentication({ redirectTo = "login.html" } = {}) {
  const state = await initializeSession();

  if (state.status === "authenticated" && state.user) {
    return state.user;
  }

  const loginUrl = new URL(redirectTo, window.location.href);
  const intended = `${window.location.pathname}${window.location.search}${window.location.hash}`;
  loginUrl.searchParams.set("next", intended);
  window.location.replace(loginUrl.href);
  return null;
}
