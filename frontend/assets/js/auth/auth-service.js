/**
 * Authentication service for the session-based EstateVista backend.
 *
 * Contract (verified against `AuthController`, `AuthService`, and
 * `docs/API_SPECIFICATION.md` §3.3):
 *
 * - `POST /api/v1/auth/register` — buyer registration, CSRF-exempt but
 *   protected by exact Origin/Referer validation. It does not create a session.
 * - `POST /api/v1/auth/login` — CSRF protected; creates the server-side session
 *   and rotates the session identifier.
 * - `GET  /api/v1/auth/me` — authenticated identity and session-bound CSRF token.
 * - `GET  /api/v1/auth/csrf` — anonymous/authenticated CSRF bootstrap.
 * - `POST /api/v1/auth/logout` — CSRF protected; invalidates the session.
 *
 * Passwords, session identifiers, and CSRF tokens are never stored in browser
 * storage or logged. The CSRF token lives only inside the shared API client.
 */

import { ApiError, apiRequest, clearCsrfToken, ensureCsrfToken } from "../core/api.js";
import { runChatTeardowns } from "../chat/chat-lifecycle.js";
import {
  getAuthRevision,
  getAuthState,
  setAnonymous,
  setAuthenticated,
  setUnknown
} from "./auth-state.js";

let initializePromise = null;

function requireUser(payload) {
  const user = payload && typeof payload === "object" ? payload.data : null;

  if (
    !user ||
    typeof user !== "object" ||
    typeof user.id !== "string" ||
    typeof user.email !== "string" ||
    typeof user.role !== "string"
  ) {
    throw new ApiError("The server returned an unrecognized account response.", {
      status: 0,
      code: "INVALID_RESPONSE"
    });
  }

  return user;
}

async function fetchCurrentUser() {
  const payload = await apiRequest("/auth/me", { method: "GET" });
  return requireUser(payload);
}

function handleUnauthorized(error) {
  if (error instanceof ApiError && error.status === 401) {
    clearCsrfToken();
    setAnonymous();
    return true;
  }
  return false;
}

/**
 * Register a buyer account. The backend does not authenticate the new account.
 */
export async function register({ firstName, lastName, email, password, phone }) {
  const body = { firstName, lastName, email, password };
  const trimmedPhone = typeof phone === "string" ? phone.trim() : "";

  if (trimmedPhone.length > 0) {
    body.phone = trimmedPhone;
  }

  const payload = await apiRequest("/auth/register", {
    method: "POST",
    body: JSON.stringify(body),
    csrf: false
  });

  return payload && typeof payload === "object" ? payload.data : null;
}

/**
 * Authenticate, then verify the resulting identity through `/auth/me`.
 *
 * The login response also carries a fresh CSRF token header and the API client
 * captures it. The `/auth/me` call confirms the rotated session and refreshes
 * the in-memory token for subsequent state-changing requests.
 */
export async function login({ email, password }) {
  await ensureCsrfToken();

  const loginPayload = await apiRequest("/auth/login", {
    method: "POST",
    body: JSON.stringify({ email, password }),
    retryOnCsrfFailure: true
  });

  let user;
  try {
    user = await fetchCurrentUser();
  } catch (error) {
    if (handleUnauthorized(error)) {
      throw error;
    }
    // The login response itself is backend-issued and already established the
    // session; use it only when the follow-up identity check fails for a
    // transport-level reason.
    user = requireUser(loginPayload);
  }

  setAuthenticated(user);
  return user;
}

/**
 * Retrieve the current user from the backend and synchronize local state.
 */
export async function getCurrentUser() {
  try {
    const user = await fetchCurrentUser();
    setAuthenticated(user);
    return user;
  } catch (error) {
    handleUnauthorized(error);
    throw error;
  }
}

/**
 * Determine the current session once per page load. Concurrent callers share a
 * single request, and a login/logout that completes first is not overwritten by
 * an older in-flight check.
 */
export function initializeSession() {
  if (initializePromise) {
    return initializePromise;
  }

  const startingRevision = getAuthRevision();

  initializePromise = (async () => {
    try {
      const user = await fetchCurrentUser();
      if (getAuthRevision() === startingRevision) {
        setAuthenticated(user);
      }
    } catch (error) {
      if (getAuthRevision() === startingRevision) {
        if (error instanceof ApiError && error.status === 401) {
          clearCsrfToken();
          setAnonymous();
        } else {
          // The backend could not confirm a session. Do not claim one.
          setUnknown();
        }
      }
    } finally {
      initializePromise = null;
    }

    return getAuthState();
  })();

  return initializePromise;
}

/**
 * End the server-side session, then clear local state.
 *
 * A `401` means the server already considers the session invalid, so local
 * state is cleared. Any other failure is rethrown without pretending logout
 * succeeded.
 */
export async function logout() {
  // Close live sockets before the server invalidates the session. The registry
  // holds only teardown callbacks, never tokens, identities or message bodies.
  runChatTeardowns();

  try {
    await apiRequest("/auth/logout", {
      method: "POST",
      retryOnCsrfFailure: true
    });
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      clearCsrfToken();
      setAnonymous();
      return { sessionEnded: true, alreadyExpired: true };
    }
    throw error;
  }

  clearCsrfToken();
  setAnonymous();
  return { sessionEnded: true, alreadyExpired: false };
}

/**
 * Force a fresh token for the current session. Used after a session lifecycle
 * change or when a protected request reports a stale token.
 */
export async function refreshCsrfToken() {
  await ensureCsrfToken({ force: true });
}

/**
 * Clear local authentication presentation state without contacting the server.
 * Intended for a `401` from another protected endpoint, not for logout.
 */
export function clearSessionState() {
  clearCsrfToken();
  setAnonymous();
}

export { isAuthenticated } from "./auth-state.js";
