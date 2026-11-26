/**
 * EstateVista — buyer favorites page (Stage 6.3).
 *
 * This page is a presentation surface only. The shared favorites service owns
 * every private request, in-memory state, CSRF handling and cooldown. The page
 * resolves the session before requesting anything, denies non-buyer surfaces
 * locally, and renders exactly one server-side paginated collection at a time.
 */
import favoritesService from "./favorites-service.js";
import { initializeSession } from "../auth/auth-service.js";
import {
  getAuthRevision,
  subscribe as subscribeAuth
} from "../auth/auth-state.js";
import { resolveSameOriginTarget } from "../auth/auth-ui.js";

const PAGE_SIZE = 12;
const SORTS = new Set(["newest", "oldest"]);

let page = 0;
let sort = "newest";
let allowed = false;
let redirecting = false;
let loading = false;
let requestSequence = 0;
let activeController = null;
let lastErrorWasRateLimit = false;
let retryWasDisabled = false;
let lastAuthRevision = -1;
let focusAfterLoad = null;

function byId(id) {
  return document.getElementById(id);
}

function clean(value) {
  return typeof value === "string" ? value.replace(/\s+/g, " ").trim() : "";
}

function isBuyer(view) {
  return Boolean(
    view &&
      view.status === "authenticated" &&
      view.user &&
      view.user.role === "BUYER"
  );
}

function isCancelled(error) {
  return Boolean(
    error &&
      (error.name === "AbortError" || error.code === "CANCELLED")
  );
}

function formatDuration(ms) {
  const seconds = Math.max(1, Math.ceil(ms / 1000));
  if (seconds < 60) {
    return `${seconds} second${seconds === 1 ? "" : "s"}`;
  }
  const minutes = Math.ceil(seconds / 60);
  return `${minutes} minute${minutes === 1 ? "" : "s"}`;
}

function describeError(error) {
  if (error && (error.reason === "rate_limit" || error.status === 429)) {
    const remaining =
      Number.isFinite(error.retryAfterMs) && error.retryAfterMs > 0
        ? error.retryAfterMs
        : 60000;
    return `Too many requests. Please wait about ${formatDuration(remaining)} before trying again.`;
  }
  if (error && error.status === 401) {
    return "Your session has ended. Please sign in again.";
  }
  if (error && error.name === "EstateVistaConfigurationError") {
    return "Property services are not configured for this deployment. Please contact the site administrator.";
  }
  if (error && error.status === 0) {
    return "The favorites service could not be reached. Please try again.";
  }
  return "We could not load your favorites. Please try again.";
}

function readUrlState() {
  const params = new URLSearchParams(window.location.search);
  const rawPage = params.get("page");
  const rawSort = params.get("sort");

  if (rawPage !== null && /^\d+$/.test(rawPage)) {
    const parsed = Number(rawPage);
    if (Number.isSafeInteger(parsed) && parsed <= 1000000) {
      page = parsed;
    }
  }
  if (rawSort !== null && SORTS.has(rawSort)) {
    sort = rawSort;
  }
  writeUrlState();
}

function writeUrlState() {
  const url = new URL(window.location.href);
  url.search = "";
  url.searchParams.set("page", String(page));
  url.searchParams.set("sort", sort);
  window.history.replaceState(
    null,
    "",
    `${url.pathname}${url.search}${url.hash}`
  );
}

function hideAllStates() {
  [
    "favoritesGuard",
    "favoritesLoading",
    "favoritesError",
    "favoritesEmpty",
    "favoritesGrid",
    "favoritesPagination"
  ].forEach((id) => {
    const node = byId(id);
    if (node) {
      node.hidden = true;
    }
  });
  const grid = byId("favoritesGrid");
  if (grid) {
    grid.replaceChildren();
    grid.setAttribute("aria-busy", "false");
  }
}

function setToolbarVisible(visible) {
  const toolbar = byId("favoritesToolbar");
  if (toolbar) {
    toolbar.hidden = !visible;
  }
}

function clearSkeleton() {
  const host = byId("favoritesSkeleton");
  const skeletons = window.EVSkeletons;

  if (skeletons && typeof skeletons.clear === "function") {
    skeletons.clear(host);
  } else if (host) {
    host.replaceChildren();
  }
}

function setRetryState(active, message) {
  const retry = byId("favoritesRetry");

  if (!retry) {
    return;
  }

  retry.disabled = active;
  retryWasDisabled = active;
  if (active) {
    retry.setAttribute("aria-disabled", "true");
  } else {
    retry.removeAttribute("aria-disabled");
  }
  retry.textContent = active ? "Retry after cooldown" : "Try again";
  if (message) {
    retry.setAttribute("title", message);
  } else {
    retry.removeAttribute("title");
  }
}

function showGuard(title, message) {
  hideAllStates();
  setToolbarVisible(false);
  setText("favoritesGuardTitle", title);
  setText("favoritesGuardMessage", message);
  show("favoritesGuard");
}

function showLoading() {
  hideAllStates();
  setToolbarVisible(true);
  show("favoritesLoading");
  clearSkeleton();
  const skeletons = window.EVSkeletons;
  if (skeletons && typeof skeletons.cards === "function") {
    skeletons.cards(byId("favoritesSkeleton"), PAGE_SIZE, "col-md-6 col-lg-4");
  }
  const grid = byId("favoritesGrid");
  if (grid) {
    grid.setAttribute("aria-busy", "true");
  }
}

function showError(message) {
  hideAllStates();
  setToolbarVisible(true);
  setText("favoritesErrorMessage", message);
  show("favoritesError");
  setRetryState(favoritesService.getSnapshot().cooldownActive, "");
}

function showEmpty() {
  hideAllStates();
  setToolbarVisible(true);
  setText("favoritesCount", "No saved properties");
  show("favoritesEmpty");
}

function show(nodeId) {
  const node = byId(nodeId);
  if (node) {
    node.hidden = false;
  }
}

function setText(id, value) {
  const node = byId(id);
  if (node) {
    node.textContent = value;
  }
}

function abortActiveLoad() {
  requestSequence += 1;
  if (activeController) {
    activeController.abort();
    activeController = null;
  }
  loading = false;
}

function normalizeMeta(meta) {
  if (!meta || typeof meta !== "object") {
    return null;
  }
  return {
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

function syncCards() {
  if (window.EVPropertyCard) {
    window.EVPropertyCard.syncFavoriteControls(favoritesService.getSnapshot());
  }
}

function buildItem(item) {
  const card = window.EVPropertyCard;
  if (!card) {
    return null;
  }

  const id = favoritesService.normalizePropertyId(item && item.propertyId);
  if (!id) {
    return null;
  }
  favoritesService.markFavorite(id);

  if (card.isUnavailableFavorite(item)) {
    return card.buildUnavailableFavoriteCard(item, {
      columnClass: "col-md-6 col-lg-4",
      imageWidth: 800,
      imageHeight: 600
    });
  }

  const model = card.fromApiSummary(item.property);
  if (!model) {
    return card.buildUnavailableFavoriteCard(
      { propertyId: id },
      { columnClass: "col-md-6 col-lg-4", imageWidth: 800, imageHeight: 600 }
    );
  }

  /* The list identity is authoritative for the favorite mutation. */
  model.id = id;
  return card.buildCard(model, {
    columnClass: "col-md-6 col-lg-4",
    imageWidth: 800,
    imageHeight: 600
  });
}

function updatePagination(meta, rendered) {
  const nav = byId("favoritesPagination");
  const prev = byId("favoritesPrev");
  const next = byId("favoritesNext");
  const count = meta ? meta.totalElements : rendered;
  const totalPages = meta ? meta.totalPages : 1;

  setText(
    "favoritesCount",
    `${count} saved ${count === 1 ? "property" : "properties"}`
  );

  if (!nav || !prev || !next) {
    return;
  }

  if (!meta || (totalPages <= 1 && !meta.hasNext && !meta.hasPrevious)) {
    nav.hidden = true;
    return;
  }

  nav.hidden = false;
  prev.disabled = page <= 0 || !meta.hasPrevious;
  next.disabled = meta.hasNext !== true;
  setText(
    "favoritesPageStatus",
    totalPages > 0
      ? `Page ${page + 1} of ${totalPages}`
      : `Page ${page + 1}`
  );
}

function restoreFocus() {
  if (!focusAfterLoad) {
    return;
  }
  const request = focusAfterLoad;
  focusAfterLoad = null;
  const grid = byId("favoritesGrid");
  if (!grid || grid.hidden) {
    const sortControl = byId("favoriteSort");
    if (sortControl) {
      sortControl.focus();
    }
    return;
  }
  const buttons = Array.from(grid.querySelectorAll("[data-ev-favorite]"));
  if (buttons.length === 0) {
    const sortControl = byId("favoriteSort");
    if (sortControl) {
      sortControl.focus();
    }
    return;
  }
  const target = buttons[Math.min(request.index, buttons.length - 1)];
  if (target && !target.disabled) {
    target.focus();
  } else if (buttons[0]) {
    buttons[0].focus();
  }
}

function render(items, meta) {
  const grid = byId("favoritesGrid");

  hideAllStates();
  setToolbarVisible(true);
  clearSkeleton();

  if (!grid) {
    showError("We could not display your favorites. Please try again.");
    return;
  }

  const fragment = document.createDocumentFragment();
  let rendered = 0;

  items.forEach((item) => {
    const column = buildItem(item);
    if (column) {
      fragment.appendChild(column);
      rendered += 1;
    }
  });

  grid.replaceChildren(fragment);
  grid.setAttribute("aria-busy", "false");
  grid.hidden = rendered === 0;

  if (rendered === 0) {
    showEmpty();
    const sortControl = byId("favoriteSort");
    if (sortControl) {
      sortControl.value = sort;
    }
    restoreFocus();
    return;
  }

  show("favoritesGrid");
  syncCards();
  updatePagination(meta, rendered);

  const sortControl = byId("favoriteSort");
  if (sortControl) {
    sortControl.value = sort;
  }
  restoreFocus();
}

async function load() {
  if (!allowed || loading) {
    return;
  }

  const sequence = ++requestSequence;
  const controller = new AbortController();
  activeController = controller;
  loading = true;
  lastErrorWasRateLimit = false;
  showLoading();

  try {
    const result = await favoritesService.listFavorites({
      page,
      size: PAGE_SIZE,
      sort,
      signal: controller.signal
    });

    if (sequence !== requestSequence || !allowed || controller.signal.aborted) {
      return;
    }
    if (!result) {
      showError("Your session could not be confirmed. Please sign in again.");
      return;
    }

    const meta = normalizeMeta(result.meta);
    const items = Array.isArray(result.items) ? result.items : [];
    let correctedPage = page;

    if (meta && meta.totalPages > 0 && page >= meta.totalPages) {
      correctedPage = meta.totalPages - 1;
    } else if (meta && meta.totalPages === 0 && page > 0) {
      correctedPage = 0;
    } else if (items.length === 0 && page > 0) {
      /* A removal can empty a page that is still numerically in range. */
      correctedPage = page - 1;
    }

    if (correctedPage !== page) {
      page = correctedPage;
      writeUrlState();
      loading = false;
      await load();
      return;
    }

    render(items, meta);
  } catch (error) {
    if (sequence !== requestSequence || isCancelled(error) || !allowed) {
      return;
    }
    lastErrorWasRateLimit = Boolean(
      error && (error.reason === "rate_limit" || error.status === 429)
    );
    showError(describeError(error));
  } finally {
    if (sequence === requestSequence) {
      activeController = null;
      loading = false;
    }
  }
}

function redirectToLogin() {
  if (redirecting) {
    return;
  }
  redirecting = true;
  const login = resolveSameOriginTarget("login.html", "login.html");
  const intended =
    window.location.pathname + window.location.search + window.location.hash;
  login.searchParams.set("next", intended);
  window.location.replace(login.href);
}

function handleAuthView(view) {
  if (!view || view.status === "unknown") {
    return;
  }

  if (!isBuyer(view)) {
    if (allowed) {
      abortActiveLoad();
    }
    allowed = false;
    lastAuthRevision = -1;
    focusAfterLoad = null;
    if (view.status !== "authenticated" || !view.user) {
      showGuard("Sign in to view favorites", "Your session is not active. You will be directed to sign in.");
      redirectToLogin();
    } else {
      showGuard(
        "Favorites are for buyer accounts",
        "This account can browse published properties, but it cannot save favorites."
      );
    }
    return;
  }

  const revision = getAuthRevision();
  if (!allowed) {
    allowed = true;
    lastAuthRevision = revision;
    showLoading();
    load();
  } else if (revision !== lastAuthRevision) {
    /* An auth revision clears the service cache even when the account is still
       a BUYER, so the authoritative list must be fetched again. */
    lastAuthRevision = revision;
    abortActiveLoad();
    showLoading();
    load();
  }
}

function wireEvents() {
  const retry = byId("favoritesRetry");
  if (retry) {
    retry.addEventListener("click", () => {
      if (!favoritesService.getSnapshot().cooldownActive) {
        load();
      }
    });
  }

  const sortControl = byId("favoriteSort");
  if (sortControl) {
    sortControl.value = sort;
    sortControl.addEventListener("change", () => {
      if (!SORTS.has(sortControl.value)) {
        return;
      }
      sort = sortControl.value;
      page = 0;
      writeUrlState();
      abortActiveLoad();
      load();
    });
  }

  const prev = byId("favoritesPrev");
  if (prev) {
    prev.addEventListener("click", () => {
      if (page <= 0) {
        return;
      }
      page -= 1;
      writeUrlState();
      abortActiveLoad();
      load();
    });
  }

  const next = byId("favoritesNext");
  if (next) {
    next.addEventListener("click", () => {
      page += 1;
      writeUrlState();
      abortActiveLoad();
      load();
    });
  }

  favoritesService.subscribe((view) => {
    if (view.cooldownActive) {
      setRetryState(true, "Waiting for the request limit to reset");
      return;
    }
    if (retryWasDisabled) {
      setRetryState(false, "");
      if (lastErrorWasRateLimit) {
        setText(
          "favoritesErrorMessage",
          "The request limit has reset. You can try again now."
        );
      }
    }
  });

  subscribeAuth(handleAuthView);

  document.addEventListener("ev-favorite-mutation", (event) => {
    const detail = event && event.detail;
    if (
      !allowed ||
      !detail ||
      detail.ok !== true ||
      detail.action !== "removed" ||
      !detail.propertyId
    ) {
      return;
    }

    const grid = byId("favoritesGrid");
    if (grid) {
      const columns = Array.from(grid.children);
      const index = columns.findIndex(
        (column) => column.getAttribute("data-id") === detail.propertyId
      );
      if (index >= 0) {
        focusAfterLoad = { index };
      }
      columns.forEach((column) => {
        if (column.getAttribute("data-id") === detail.propertyId) {
          column.remove();
        }
      });
    }
    abortActiveLoad();
    load();
  });
}

async function init() {
  readUrlState();
  wireEvents();
  showGuard("Checking your session…", "Confirming whether this account can view favorites.");

  /* initializeSession is shared with auth-init.js and never makes a private
     favorites request. No favorites endpoint is called until BUYER is known. */
  const snapshot = await initializeSession();
  handleAuthView(snapshot);
  if (allowed) {
    syncCards();
  }
}

if (document.readyState === "loading") {
  document.addEventListener("DOMContentLoaded", init, { once: true });
} else {
  init();
}
