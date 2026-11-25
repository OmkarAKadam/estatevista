/**
 * EstateVista — buyer favorites service (Stage 6.3).
 *
 * This module owns all in-memory favorite state and every private favorites
 * request. It deliberately reuses the shared Fetch client (no second HTTP
 * client), keeps the CSRF token in that client's memory, and never stores
 * favorites in cookies or browser storage.
 *
 * Approved contract: docs/STAGE_6_DECISION_REGISTER.md §5.1.
 * - only an authenticated BUYER may call a favorites endpoint;
 * - one batched status request per displayed public collection, one individual
 *   status request on property details;
 * - POST 201/200 means saved, DELETE 204 means removed, and a failed mutation
 *   never invents a new confirmed state;
 * - 429 starts the shared, full-duration cooldown before any further request;
 * - logout, account changes, and expired sessions discard all cached state.
 */
import {
  ApiError,
  apiRequest,
  createRateLimitCooldown,
  isCancellation
} from "../core/api.js";
import {
  getAuthState,
  subscribe as subscribeAuth
} from "../auth/auth-state.js";
import {
  clearSessionState,
  initializeSession
} from "../auth/auth-service.js";

/* Backend UUID columns accept canonical 8-4-4-4-12 hexadecimal identifiers,
   including seeded catalogue IDs with zero version/variant nibbles. */
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const MAX_BATCH_IDS = 100;
const SORTS = new Set(["newest", "oldest"]);

const listeners = new Set();
const state = new Map();
const pending = new Map();
const collectionFlights = new Map();
const statusFlights = new Map();

let generation = 0;

function invalid(message, name = "InvalidFavoritesRequestError") {
  const error = new Error(message);
  error.name = name;
  return error;
}

function canonicalId(value) {
  if (typeof value !== "string") {
    return null;
  }
  const id = value.trim().toLowerCase();
  return UUID_PATTERN.test(id) ? id : null;
}

function normalizeIds(ids) {
  if (!Array.isArray(ids)) {
    return { unique: [], supplied: 0 };
  }
  const seen = new Set();
  const result = [];
  let supplied = 0;
  ids.forEach((value) => {
    const id = canonicalId(value);
    if (!id) {
      return;
    }
    /* Duplicates count toward the server's cap before they are removed. */
    supplied += 1;
    if (!seen.has(id)) {
      seen.add(id);
      result.push(id);
    }
  });
  return { unique: result, supplied };
}

function isBuyer(snapshot) {
  return Boolean(
    snapshot &&
      snapshot.status === "authenticated" &&
      snapshot.user &&
      snapshot.user.role === "BUYER"
  );
}

function snapshot() {
  return {
    generation,
    authStatus: getAuthState().status,
    cooldownActive: cooldown.active(),
    cooldownRemainingMs: cooldown.remainingMs(),
    pendingCount: pending.size,
    buyer: isBuyer(getAuthState())
  };
}

function notify() {
  const view = snapshot();
  listeners.forEach((listener) => {
    try {
      listener(view);
    } catch {
      // A presentation listener must never corrupt service state.
    }
  });
}

const cooldown = createRateLimitCooldown(notify);

/** Any authentication revision change supersedes every cached response. */
subscribeAuth(() => {
  generation += 1;
  state.clear();
  pending.clear();
  collectionFlights.clear();
  statusFlights.clear();
  notify();
});

/**
 * Resolve the session once per page load. The auth bridge already performs
 * this check, so an anonymous/agent/admin page adds no extra auth request.
 */
async function requireBuyer() {
  let current = getAuthState();
  if (current.status === "unknown") {
    await initializeSession();
    current = getAuthState();
  }
  return isBuyer(current) ? current : null;
}

function applies(myGeneration) {
  return myGeneration === generation && isBuyer(getAuthState());
}

function apply(id, value, myGeneration) {
  if (!applies(myGeneration)) {
    return false;
  }
  state.set(id, value === true);
  notify();
  return true;
}

function known(id) {
  return state.get(id) ?? null;
}

function startCooldown(error) {
  /* createRateLimitCooldown accepts the full server duration and never
     shortens an existing absolute expiry. No fallback constant is invented. */
  cooldown.start(error && error.retryAfterMs);
  return cooldown.remainingMs();
}

function isAmbiguous(error) {
  return Boolean(
    error &&
      (error.status === 0 ||
        error.reason === "network" ||
        error.reason === "timeout" ||
        error.status >= 500)
  );
}

function failureReason(error) {
  if (!error) {
    return "unknown";
  }
  if (error.status === 404) {
    return "not_available";
  }
  if (error.status === 403) {
    return "forbidden";
  }
  if (error.reason === "validation" || error.status === 400) {
    return "validation";
  }
  if (error.reason === "rate_limit" || error.status === 429) {
    return "rate_limit";
  }
  return "unavailable";
}

function normalizeMeta(meta) {
  if (!meta || typeof meta !== "object") {
    return null;
  }
  return {
    page: Number.isInteger(meta.page) && meta.page >= 0 ? meta.page : 0,
    size: Number.isInteger(meta.size) && meta.size > 0 ? meta.size : 0,
    totalElements:
      Number.isInteger(meta.totalElements) && meta.totalElements >= 0
        ? meta.totalElements
        : 0,
    totalPages:
      Number.isInteger(meta.totalPages) && meta.totalPages >= 0
        ? meta.totalPages
        : 0,
    hasNext: meta.hasNext === true,
    hasPrevious: meta.hasPrevious === true
  };
}

/** Individual read. Used by details and to reconcile an ambiguous mutation. */
async function readStatus(id, myGeneration, { signal, force = false } = {}) {
  if (!force && state.has(id) && signal === undefined) {
    return state.get(id);
  }
  if (statusFlights.has(id)) {
    return statusFlights.get(id);
  }

  const controller = new AbortController();
  const onAbort = () => controller.abort();
  if (signal) {
    if (signal.aborted) {
      return null;
    }
    signal.addEventListener("abort", onAbort, { once: true });
  }

  const task = (async () => {
    const payload = await apiRequest(`/favorites/${encodeURIComponent(id)}/status`, {
      method: "GET",
      signal: controller.signal
    });
    const entry = payload && payload.data;
    const returnedId = canonicalId(entry && entry.propertyId);
    if (returnedId !== id || typeof entry.isFavorite !== "boolean") {
      throw invalid("The favorites service returned an unexpected status.", "InvalidFavoritesResponseError");
    }
    const applied = apply(id, entry.isFavorite, myGeneration);
    return applied ? entry.isFavorite : null;
  })();

  statusFlights.set(id, task);
  task.finally(() => {
    if (statusFlights.get(id) === task) {
      statusFlights.delete(id);
    }
    if (signal) {
      signal.removeEventListener("abort", onAbort);
    }
  }).catch(() => {
    /* Callers handle the original task; this observer must not add a second
       unhandled rejection when a read fails. */
  });
  return task;
}

export async function getStatus(id, options = {}) {
  const key = canonicalId(id);
  if (!key) {
    return null;
  }
  if (!options.force && state.has(key) && !cooldown.active()) {
    return state.get(key);
  }
  const buyer = await requireBuyer();
  if (!buyer || cooldown.active()) {
    return null;
  }
  const myGeneration = generation;
  try {
    return await readStatus(key, myGeneration, options);
  } catch (error) {
    if (isCancellation(error)) {
      return null;
    }
    if (error instanceof ApiError && error.status === 401) {
      clearSessionState();
    } else if (error instanceof ApiError && error.status === 429) {
      startCooldown(error);
    }
    /* Preserve the last confirmed state on every other failure. */
    return known(key);
  }
}

/**
 * One bounded batch per displayed collection. Duplicate and mixed-case IDs
 * are de-duplicated before the request; the 100-value cap is enforced before
 * de-duplication exactly as the backend does.
 */
export async function syncCollection(ids, options = {}) {
  const { unique, supplied } = normalizeIds(ids);
  if (unique.length === 0) {
    return new Map();
  }
  if (supplied > MAX_BATCH_IDS) {
    throw invalid("A favorite status batch may contain at most 100 properties.");
  }
  const normalized = unique;

  const buyer = await requireBuyer();
  if (!buyer || cooldown.active()) {
    return null;
  }
  if (options.signal && options.signal.aborted) {
    return null;
  }

  const myGeneration = generation;
  const key = normalized.join(",");
  if (collectionFlights.has(key)) {
    return collectionFlights.get(key);
  }

  const controller = new AbortController();
  const onAbort = () => controller.abort();
  if (options.signal) {
    options.signal.addEventListener("abort", onAbort, { once: true });
  }

  const query = new URLSearchParams({ propertyIds: key });
  const task = (async () => {
    const payload = await apiRequest(`/favorites/status?${query.toString()}`, {
      method: "GET",
      signal: controller.signal
    });
    const entries = payload && payload.data && payload.data.favorites;
    if (!Array.isArray(entries)) {
      throw invalid("The favorites service returned an unexpected status list.", "InvalidFavoritesResponseError");
    }

    const requested = new Set(normalized);
    const result = new Map();
    entries.forEach((entry) => {
      const id = canonicalId(entry && entry.propertyId);
      if (id && requested.has(id) && typeof entry.isFavorite === "boolean") {
        result.set(id, entry.isFavorite);
      }
    });

    if (!applies(myGeneration)) {
      return null;
    }
    result.forEach((value, id) => state.set(id, value));
    notify();
    return result;
  })();

  collectionFlights.set(key, task);
  task.finally(() => {
    if (collectionFlights.get(key) === task) {
      collectionFlights.delete(key);
    }
    if (options.signal) {
      options.signal.removeEventListener("abort", onAbort);
    }
  }).catch(() => {
    /* Callers handle the original task; this observer must not add a second
       unhandled rejection when a batch read fails. */
  });
  return task;
}

/** The paginated list is authoritative for retained, non-public favorites. */
export async function listFavorites({ page = 0, size = 12, sort = "newest", signal } = {}) {
  if (!Number.isInteger(page) || page < 0) {
    throw invalid("Invalid favorites page.");
  }
  if (!Number.isInteger(size) || size < 1 || size > MAX_BATCH_IDS) {
    throw invalid("Invalid favorites page size.");
  }
  if (!SORTS.has(sort)) {
    throw invalid("Invalid favorites sort.");
  }

  const buyer = await requireBuyer();
  if (!buyer || cooldown.active()) {
    return null;
  }
  const myGeneration = generation;
  const query = new URLSearchParams({
    page: String(page),
    size: String(size),
    sort
  });

  try {
    const payload = await apiRequest(`/favorites?${query.toString()}`, {
      method: "GET",
      signal
    });
    if (!payload || payload.success !== true || !Array.isArray(payload.data)) {
      throw invalid("The favorites service returned an unexpected list.", "InvalidFavoritesResponseError");
    }

    /* Every returned item is a stored favorite, including generic placeholders
       whose individual/batch status deliberately reports false. */
    payload.data.forEach((item) => {
      const id = canonicalId(item && item.propertyId);
      if (id) {
        apply(id, true, myGeneration);
      }
    });

    return { items: payload.data, meta: normalizeMeta(payload.meta) };
  } catch (error) {
    if (isCancellation(error)) {
      return null;
    }
    if (error instanceof ApiError && error.status === 401) {
      clearSessionState();
    } else if (error instanceof ApiError && error.status === 429) {
      startCooldown(error);
    }
    throw error;
  }
}

/** Applies the favorites-page list result to a card state without a new read. */
export function markFavorite(id) {
  const key = canonicalId(id);
  if (key) {
    apply(key, true, generation);
  }
}

async function mutate(id, action) {
  const key = canonicalId(id);
  if (!key) {
    return { ok: false, reason: "validation", state: null };
  }

  const buyer = await requireBuyer();
  if (!buyer) {
    return { ok: false, reason: "auth", state: known(key) };
  }
  if (cooldown.active()) {
    return {
      ok: false,
      reason: "rate_limit",
      retryAfterMs: cooldown.remainingMs(),
      state: known(key)
    };
  }
  if (pending.has(key)) {
    return pending.get(key);
  }

  const myGeneration = generation;
  const task = (async () => {
    let result;
    try {
      const payload = await apiRequest(`/favorites/${encodeURIComponent(key)}`, {
        method: action === "add" ? "POST" : "DELETE"
        /* No automatic CSRF retry is requested; Stage 6 mutations never retry. */
      });

      if (action === "add") {
        if (payload && payload.data && payload.data.isFavorite === true) {
          apply(key, true, myGeneration);
          result = { ok: true, action: "added", state: true };
        } else {
          /* An unrecognized success shape is reconciled by a safe read, not
             by repeating the mutation. */
          const reconciled = await readStatus(key, myGeneration, { force: true });
          result = {
            ok: false,
            reason: "reconciled",
            reconciled: true,
            state: reconciled === null ? known(key) : reconciled
          };
        }
      } else {
        /* DELETE 204 has no body; the client does not try to parse one. */
        apply(key, false, myGeneration);
        result = { ok: true, action: "removed", state: false };
      }
    } catch (error) {
      if (isCancellation(error)) {
        result = { ok: false, reason: "cancelled", state: known(key) };
      } else if (error instanceof ApiError && error.status === 429) {
        const remaining = startCooldown(error);
        result = {
          ok: false,
          reason: "rate_limit",
          retryAfterMs: remaining,
          state: known(key)
        };
      } else if (error instanceof ApiError && error.status === 401) {
        clearSessionState();
        result = { ok: false, reason: "auth", state: null };
      } else if (isAmbiguous(error) && applies(myGeneration) && !cooldown.active()) {
        /* Network/timeout/5xx after a mutation is ambiguous. The approved
           individual status endpoint resolves it; the mutation is not retried. */
        try {
          const reconciled = await readStatus(key, myGeneration, { force: true });
          result = {
            ok: false,
            reason: "reconciled",
            reconciled: true,
            state: reconciled === null ? known(key) : reconciled
          };
        } catch {
          result = { ok: false, reason: "unavailable", state: known(key) };
        }
      } else {
        result = { ok: false, reason: failureReason(error), state: known(key) };
      }
    }
    return result;
  })();

  pending.set(key, task);
  /* Publish the pending state immediately so controls disable and expose
     aria-busy before an in-flight mutation can be activated again. */
  notify();
  task.finally(() => {
    if (pending.get(key) === task) {
      pending.delete(key);
      /* State is applied while the task is still pending. Notify once more
         after cleanup so controls leave aria-busy/disabled without waiting for
         an unrelated render or subscription event. */
      notify();
    }
  }).catch(() => {
    /* Callers handle the original task; this observer must not add a second
       unhandled rejection when a mutation fails. */
  });
  return task;
}

export function addFavorite(id) {
  return mutate(id, "add");
}

export function removeFavorite(id) {
  return mutate(id, "remove");
}

export function isFavorite(id) {
  const key = canonicalId(id);
  return key ? state.get(key) === true : false;
}

export function isPending(id) {
  const key = canonicalId(id);
  return Boolean(key && pending.has(key));
}

export function canMutate() {
  return isBuyer(getAuthState()) && !cooldown.active();
}

/** Presentation-only view of the current auth/cooldown/cache generation. */
export function getSnapshot() {
  return snapshot();
}

export function getCooldown() {
  return cooldown;
}

export function subscribe(listener) {
  if (typeof listener !== "function") {
    return () => {};
  }
  listeners.add(listener);
  listener(snapshot());
  return () => listeners.delete(listener);
}

/** Safe, non-enumerating messages for presentation. Server text is never used. */
export function describeFavoriteFailure(result) {
  if (!result || result.ok) {
    return "";
  }
  if (result.reason === "rate_limit") {
    const seconds = Math.max(1, Math.ceil((result.retryAfterMs ?? cooldown.remainingMs()) / 1000));
    return `Favorite updates are paused for about ${seconds} second${seconds === 1 ? "" : "s"}.`;
  }
  if (result.reason === "auth") {
    return "Please sign in to manage favorites.";
  }
  if (result.reason === "not_available") {
    return "This property is not available to save.";
  }
  if (result.reason === "forbidden") {
    return "This account cannot manage favorites.";
  }
  if (result.reason === "reconciled") {
    return "The update could not be confirmed. The saved state has been refreshed.";
  }
  return "We could not update your favorites. Please try again.";
}

export function normalizePropertyId(value) {
  return canonicalId(value);
}

export const favoritesService = {
  getStatus,
  syncCollection,
  listFavorites,
  markFavorite,
  addFavorite,
  removeFavorite,
  isFavorite,
  isPending,
  canMutate,
  getSnapshot,
  getCooldown,
  subscribe,
  describeFavoriteFailure,
  normalizePropertyId
};

export default favoritesService;
