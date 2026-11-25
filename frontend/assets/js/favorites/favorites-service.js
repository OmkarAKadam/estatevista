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
/* A generation protects account boundaries. A per-property revision protects
   the ordering of status reads and mutations inside one authenticated session. */
const revisions = new Map();
/* Non-public favorites are retained server-side but their individual/batch
   status deliberately reports false. Only GET /favorites is authoritative. */
const listAuthoritative = new Set();

let generation = 0;
let stateRevision = 0;
/* Only accepted mutations advance this revision. It protects a paginated list
   from a newer mutation without letting ordinary status reads suppress the
   list's authoritative view of retained non-public favorites. */
let mutationRevision = 0;
let sessionInitFlight = null;

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
  stateRevision += 1;
  state.clear();
  pending.clear();
  collectionFlights.clear();
  statusFlights.clear();
  revisions.clear();
  listAuthoritative.clear();
  notify();
});

/**
 * Resolve the session once per page load. The auth bridge already performs
 * this check, so an anonymous/agent/admin page adds no extra auth request.
 * Concurrent callers share one initialization flight.
 */
async function requireBuyer() {
  let current = getAuthState();
  if (current.status === "unknown") {
    if (!sessionInitFlight) {
      sessionInitFlight = Promise.resolve(initializeSession()).finally(() => {
        sessionInitFlight = null;
      });
    }
    await sessionInitFlight;
    current = getAuthState();
  }
  return isBuyer(current) ? current : null;
}

function revisionOf(id) {
  return revisions.get(id) ?? 0;
}

function bumpRevision(id) {
  const next = revisionOf(id) + 1;
  revisions.set(id, next);
  stateRevision += 1;
  mutationRevision += 1;
  return next;
}

function applies(myGeneration) {
  return myGeneration === generation && isBuyer(getAuthState());
}

function apply(id, value, myGeneration, myRevision, options = {}) {
  if (
    myGeneration !== generation ||
    !isBuyer(getAuthState()) ||
    (myRevision !== undefined && revisionOf(id) !== myRevision)
  ) {
    return false;
  }
  if (value !== true && listAuthoritative.has(id) && options.authoritative !== true) {
    /* A status endpoint cannot disprove a retained non-public favorite. */
    return false;
  }
  state.set(id, value === true);
  /* Any later write supersedes reads that captured the previous revision. */
  revisions.set(id, revisionOf(id) + 1);
  stateRevision += 1;
  if (options.authoritative === true) {
    if (value === true) {
      listAuthoritative.add(id);
    } else {
      listAuthoritative.delete(id);
    }
  }
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

/**
 * A shared read flight is cancelled only when every caller has cancelled.
 * One caller's AbortSignal therefore never resolves a different caller's read
 * prematurely, while a genuinely obsolete flight still releases its request.
 */
function createFlight(map, key, generationToken, revisionToken, work) {
  const flight = {
    controller: new AbortController(),
    generation: generationToken,
    revision: revisionToken,
    task: null,
    callers: new Set(),
    cleanups: new Set()
  };
  flight.task = (async () => {
    try {
      return await work(flight.controller.signal);
    } finally {
      if (map.get(key) === flight) {
        map.delete(key);
      }
      flight.cleanups.forEach((cleanup) => cleanup());
      flight.cleanups.clear();
      flight.callers.clear();
    }
  })();
  map.set(key, flight);
  return flight;
}

function releaseCaller(flight, token) {
  if (flight.callers.delete(token) && flight.callers.size === 0) {
    flight.controller.abort();
  }
}

function attachCaller(flight, signal) {
  const token = {};
  flight.callers.add(token);
  if (!signal) {
    return flight.task;
  }
  if (signal.aborted) {
    releaseCaller(flight, token);
    return Promise.resolve(null);
  }

  let settled = false;
  let resolvePromise;
  let rejectPromise;
  const promise = new Promise((resolve, reject) => {
    resolvePromise = resolve;
    rejectPromise = reject;
  });
  const settle = (value, error) => {
    if (settled) {
      return;
    }
    settled = true;
    signal.removeEventListener("abort", onAbort);
    flight.cleanups.delete(cleanup);
    if (error) {
      rejectPromise(error);
    } else {
      resolvePromise(value);
    }
  };
  const onAbort = () => {
    releaseCaller(flight, token);
    settle(null);
  };
  const cleanup = () => {
    signal.removeEventListener("abort", onAbort);
    flight.cleanups.delete(cleanup);
  };
  flight.cleanups.add(cleanup);
  signal.addEventListener("abort", onAbort, { once: true });
  flight.task.then((value) => settle(value), (error) => settle(null, error));
  return promise;
}

/** Individual read. Used by details and to reconcile an ambiguous mutation. */
async function readStatus(id, myGeneration, myRevision, { signal, force = false } = {}) {
  if (!force && state.has(id) && signal === undefined) {
    return state.get(id);
  }
  const existing = statusFlights.get(id);
  if (
    existing &&
    existing.generation === myGeneration &&
    existing.revision === myRevision
  ) {
    return attachCaller(existing, signal);
  }

  const flight = createFlight(
    statusFlights,
    id,
    myGeneration,
    myRevision,
    async (flightSignal) => {
      const payload = await apiRequest(`/favorites/${encodeURIComponent(id)}/status`, {
        method: "GET",
        signal: flightSignal
      });
      const entry = payload && payload.data;
      const returnedId = canonicalId(entry && entry.propertyId);
      if (returnedId !== id || typeof entry.isFavorite !== "boolean") {
        throw invalid("The favorites service returned an unexpected status.", "InvalidFavoritesResponseError");
      }
      /* A non-public retained favorite reports false here by contract; the
         authoritative list is the only surface allowed to disprove it. */
      const value = listAuthoritative.has(id) ? true : entry.isFavorite;
      const applied = apply(id, value, myGeneration, myRevision);
      return applied ? value : null;
    }
  );
  return attachCaller(flight, signal);
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
  const myRevision = revisionOf(key);
  try {
    return await readStatus(key, myGeneration, myRevision, options);
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
  const revisionsAtStart = new Map(
    normalized.map((id) => [id, revisionOf(id)])
  );
  const revisionKey = JSON.stringify([...revisionsAtStart]);
  const existing = collectionFlights.get(key);
  if (
    existing &&
    existing.generation === myGeneration &&
    existing.revision === revisionKey
  ) {
    return attachCaller(existing, options.signal);
  }

  const query = new URLSearchParams({ propertyIds: key });
  const flight = createFlight(
    collectionFlights,
    key,
    myGeneration,
    revisionKey,
    async (flightSignal) => {
      const payload = await apiRequest(`/favorites/status?${query.toString()}`, {
        method: "GET",
        signal: flightSignal
      });
      const entries = payload && payload.data && payload.data.favorites;
      if (!Array.isArray(entries)) {
        throw invalid("The favorites service returned an unexpected status list.", "InvalidFavoritesResponseError");
      }

      const requested = new Set(normalized);
      const received = new Map();
      entries.forEach((entry) => {
        const id = canonicalId(entry && entry.propertyId);
        if (id && requested.has(id) && typeof entry.isFavorite === "boolean") {
          received.set(id, entry.isFavorite);
        }
      });

      if (!applies(myGeneration)) {
        return null;
      }
      const result = new Map();
      received.forEach((rawValue, id) => {
        const value = listAuthoritative.has(id) ? true : rawValue;
        if (apply(id, value, myGeneration, revisionsAtStart.get(id))) {
          result.set(id, value);
        }
      });
      return result;
    }
  );
  return attachCaller(flight, options.signal);
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
  const myMutationRevision = mutationRevision;
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
       whose individual/batch status deliberately reports false. A mutation that
       began after this request supersedes the whole page; an intervening status
       read does not because this list is authoritative for retained rows. */
    if (myMutationRevision === mutationRevision) {
      payload.data.forEach((item) => {
        const id = canonicalId(item && item.propertyId);
        if (id) {
          apply(id, true, myGeneration, revisionOf(id), {
            authoritative: true
          });
        }
      });
    }

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

/** The approved collection remains authoritative for retained non-public rows. */
async function reconcileByList(id, myGeneration) {
  let page = 0;
  const myMutationRevision = mutationRevision;
  try {
    while (true) {
      const result = await listFavorites({ page, size: MAX_BATCH_IDS, sort: "newest" });
      if (!result || !applies(myGeneration) || mutationRevision !== myMutationRevision) {
        return null;
      }
      const items = Array.isArray(result.items) ? result.items : [];
      if (items.some((item) => canonicalId(item && item.propertyId) === id)) {
        apply(id, true, myGeneration, revisionOf(id), { authoritative: true });
        return true;
      }
      const meta = result.meta;
      if (!meta || !meta.hasNext || page + 1 >= meta.totalPages) {
        break;
      }
      page += 1;
    }
    if (mutationRevision !== myMutationRevision) {
      return null;
    }
    listAuthoritative.delete(id);
    apply(id, false, myGeneration, revisionOf(id), { authoritative: true });
    return false;
  } catch {
    return null;
  }
}

function withOperationContext(result, myGeneration, myRevision) {
  return {
    ...result,
    generation: myGeneration,
    revision: myRevision,
    state: result.state === undefined ? null : result.state
  };
}

function mutate(id, action) {
  const key = canonicalId(id);
  if (!key) {
    return Promise.resolve(
      withOperationContext({ ok: false, reason: "validation", state: null }, 0, 0)
    );
  }

  const current = getAuthState();
  if (current.status !== "unknown" && !isBuyer(current)) {
    return Promise.resolve(
      withOperationContext(
        { ok: false, reason: "auth", state: known(key) },
        generation,
        revisionOf(key)
      )
    );
  }
  if (cooldown.active()) {
    return Promise.resolve(
      withOperationContext(
        {
          ok: false,
          reason: "rate_limit",
          retryAfterMs: cooldown.remainingMs(),
          state: known(key)
        },
        generation,
        revisionOf(key)
      )
    );
  }
  /* Dedupe synchronously, before any await, so two activations in one task
     cannot issue two requests. */
  if (pending.has(key)) {
    return pending.get(key);
  }

  const myGeneration = generation;
  const myRevision = bumpRevision(key);
  const task = (async () => {
    let result;
    try {
      const buyer = await requireBuyer();
      if (!buyer || !applies(myGeneration)) {
        result = { ok: false, reason: "auth", state: null };
      } else {
        const payload = await apiRequest(`/favorites/${encodeURIComponent(key)}`, {
          method: action === "add" ? "POST" : "DELETE"
          /* No automatic CSRF retry is requested; Stage 6 mutations never retry. */
        });

        if (action === "add") {
          if (payload && payload.data && payload.data.isFavorite === true) {
            if (apply(key, true, myGeneration, myRevision, { authoritative: true })) {
              result = { ok: true, action: "added", state: true };
            } else {
              result = { ok: false, reason: "stale", state: known(key) };
            }
          } else {
            /* An unrecognized success shape is reconciled by a safe read, not
               by repeating the mutation. */
            const reconciled = await readStatus(
              key,
              myGeneration,
              revisionOf(key),
              { force: true }
            );
            result = {
              ok: false,
              reason: "reconciled",
              reconciled: true,
              source: "status",
              state: reconciled === null ? known(key) : reconciled
            };
          }
        } else {
          /* DELETE 204 has no body; the client does not try to parse one. */
          listAuthoritative.delete(key);
          if (apply(key, false, myGeneration, myRevision, { authoritative: true })) {
            result = { ok: true, action: "removed", state: false };
          } else {
            result = { ok: false, reason: "stale", state: known(key) };
          }
        }
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
        /* A network/timeout/5xx outcome is ambiguous. Removal is reconciled
           through the paginated collection because status reports false for a
           retained non-public favorite. No mutation is replayed. */
        const reconciled =
          action === "remove"
            ? await reconcileByList(key, myGeneration)
            : await readStatus(key, myGeneration, revisionOf(key), { force: true });
        result = {
          ok: false,
          reason: "reconciled",
          reconciled: true,
          source: action === "remove" ? "list" : "status",
          state: reconciled === null ? known(key) : reconciled
        };
      } else {
        result = { ok: false, reason: failureReason(error), state: known(key) };
      }
    }
    if (!applies(myGeneration)) {
      result = { ok: false, reason: "stale", state: null };
    }
    return withOperationContext(result, myGeneration, myRevision);
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
