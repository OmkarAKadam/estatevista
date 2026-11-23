/**
 * In-memory authentication state.
 *
 * The backend is the only authority on whether a session is authenticated.
 * This module stores only the safe identity fields returned by
 * `GET /api/v1/auth/me`, and that state is discarded whenever the page is
 * reloaded. Nothing here is written to cookies, localStorage, or
 * sessionStorage, and role values are used for presentation only.
 */

const listeners = new Set();

let revision = 0;
let state = Object.freeze({ status: "unknown", user: null });

function text(value) {
  return typeof value === "string" ? value : "";
}

function sanitizeUser(user) {
  if (!user || typeof user !== "object") {
    return null;
  }

  const id = text(user.id);
  const email = text(user.email);
  const role = text(user.role);

  if (!id || !email || !role) {
    return null;
  }

  return Object.freeze({
    id,
    email,
    role,
    firstName: text(user.firstName),
    lastName: text(user.lastName),
    accountStatus: text(user.accountStatus)
  });
}

function emit() {
  const snapshot = state;
  listeners.forEach((listener) => {
    try {
      listener(snapshot);
    } catch {
      // A presentation listener must never break the authentication flow.
    }
  });
}

export function getAuthState() {
  return state;
}

export function getCurrentUser() {
  return state.user;
}

export function isAuthenticated() {
  return state.status === "authenticated" && state.user !== null;
}

export function getUserRole() {
  return state.user ? state.user.role : null;
}

export function getAuthRevision() {
  return revision;
}

export function setAuthenticated(user) {
  const safeUser = sanitizeUser(user);
  if (!safeUser) {
    throw new Error("The server returned an unrecognized account response.");
  }
  revision += 1;
  state = Object.freeze({ status: "authenticated", user: safeUser });
  emit();
}

export function setAnonymous() {
  revision += 1;
  state = Object.freeze({ status: "anonymous", user: null });
  emit();
}

export function setUnknown() {
  revision += 1;
  state = Object.freeze({ status: "unknown", user: null });
  emit();
}

export function subscribe(listener) {
  if (typeof listener !== "function") {
    return () => {};
  }
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
