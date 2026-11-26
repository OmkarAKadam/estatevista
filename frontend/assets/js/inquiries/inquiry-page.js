/**
 * Shared controller for buyer history, agent queue and admin inquiry oversight.
 *
 * The page resolves the session first, enforces a presentation-only role guard,
 * and then renders one authorized server-paginated collection at a time. Every
 * request, validation rule and state transition comes from inquiry-service.js;
 * this file owns only DOM state and curated feedback.
 */

import inquiryService from "./inquiry-service.js";
import {
  clear,
  describeFailure,
  node,
  renderDetail,
  renderItems,
  show
} from "./inquiry-view.js";
import { statusLabel } from "./inquiry-lifecycle.js";
import { initializeSession } from "../auth/auth-service.js";
import {
  getAuthRevision,
  subscribe as subscribeAuth
} from "../auth/auth-state.js";
import {
  confirmDialog,
  initManagementShell,
  redirectToLogin,
  showAccessDenied,
  toast
} from "../management/management-shell.js";

const PAGE_SIZE = 20;
const STATUSES = new Set(["NEW", "IN_PROGRESS", "RESOLVED", "CLOSED"]);
const SORTS = new Set(["newest", "oldest"]);

const body = document.body;
const scope = body.dataset.inquiryScope === "agent" || body.dataset.inquiryScope === "admin"
  ? body.dataset.inquiryScope
  : "buyer";
const role = scope === "buyer" ? "BUYER" : scope.toUpperCase();
const isBuyerPage = scope === "buyer";

const refs = {
  content: document.querySelector("[data-inquiry-content]"),
  guard: document.querySelector("[data-inquiry-guard]"),
  guardTitle: document.querySelector("[data-inquiry-guard-title]"),
  guardMessage: document.querySelector("[data-inquiry-guard-message]"),
  loginLink: document.querySelector("[data-inquiry-login-link]"),
  filters: document.querySelector("[data-inquiry-filters]"),
  reset: document.querySelector("[data-inquiry-reset]"),
  status: document.querySelector("#inquiryStatusFilter"),
  sort: document.querySelector("#inquirySort"),
  summary: document.querySelector("[data-inquiry-summary]"),
  loading: document.querySelector("[data-inquiry-loading]"),
  error: document.querySelector("[data-inquiry-error]"),
  errorMessage: document.querySelector("[data-inquiry-error-message]"),
  retry: document.querySelector("[data-inquiry-retry]"),
  empty: document.querySelector("[data-inquiry-empty]"),
  table: document.querySelector("[data-inquiry-table]"),
  tbody: document.querySelector("[data-inquiry-body]"),
  cards: document.querySelector("[data-inquiry-cards]"),
  pagination: document.querySelector("[data-inquiry-pagination]"),
  pageStatus: document.querySelector("[data-inquiry-page-status]"),
  prev: document.querySelector("[data-inquiry-prev]"),
  next: document.querySelector("[data-inquiry-next]"),
  detail: document.querySelector("[data-inquiry-detail]"),
  detailBody: document.querySelector("[data-inquiry-detail-body]"),
  detailClose: document.querySelector("[data-inquiry-detail-close]"),
  detailError: document.querySelector("[data-inquiry-detail-error]"),
  detailErrorMessage: document.querySelector("[data-inquiry-detail-error-message]"),
  detailRetry: document.querySelector("[data-inquiry-detail-retry]")
};

const state = {
  page: 0,
  sort: "newest",
  status: "",
  allowed: false,
  sequence: 0,
  detailId: null,
  resolvedAt: null,
  starting: false,
  lastRevision: getAuthRevision()
};

let cooldownTimer = null;

function setVisible(element, visible) {
  if (element) {
    element.hidden = !visible;
  }
}

function setText(element, value) {
  if (element) {
    element.textContent = String(value ?? "");
  }
}

function formatDuration(ms) {
  const seconds = Math.max(1, Math.ceil(ms / 1000));
  if (seconds < 60) {
    return `${seconds} second${seconds === 1 ? "" : "s"}`;
  }
  return `${Math.ceil(seconds / 60)} minute${seconds % 60 === 0 ? "" : "+"}`;
}

function hideCollections() {
  setVisible(refs.loading, false);
  setVisible(refs.error, false);
  setVisible(refs.empty, false);
  setVisible(refs.table, false);
  setVisible(refs.cards, false);
  setVisible(refs.pagination, false);
  if (refs.content) {
    refs.content.setAttribute("aria-busy", "false");
  }
}

function clearDetail() {
  state.detailId = null;
  state.resolvedAt = null;
  clear(refs.detailBody);
  setText(refs.detailErrorMessage, "");
  setVisible(refs.detailError, false);
  setVisible(refs.detail, false);
}

/** Remove every private value from the DOM and invalidate in-flight requests. */
function clearPrivateState() {
  state.allowed = false;
  state.sequence += 1;
  clear(refs.tbody);
  clear(refs.cards);
  clearDetail();
  hideCollections();
  setText(refs.summary, "");
  setText(refs.pageStatus, "");
  setText(refs.errorMessage, "");
  inquiryService.reset();
}

function showGuard(title, message, { expired = false } = {}) {
  clearPrivateState();
  setText(refs.guardTitle, title);
  setText(refs.guardMessage, message);
  if (refs.loginLink) {
    const login = new URL("/login.html", window.location.origin);
    const intended = `${window.location.pathname}${window.location.search}`;
    login.searchParams.set("next", intended);
    if (expired) {
      login.searchParams.set("expired", "1");
    }
    refs.loginLink.href = login.href;
  }
  setVisible(refs.guard, true);
  if (refs.content) {
    refs.content.hidden = true;
  }
  if (!isBuyerPage) {
    showAccessDenied();
  }
}

function denyAccess(stateView, { expired = false } = {}) {
  if (!isBuyerPage) {
    showGuard(
      "Access restricted",
      "This area is not available for your account.",
      { expired }
    );
    return;
  }
  const anonymous = !stateView || stateView.status !== "authenticated" || !stateView.user;
  if (anonymous) {
    showGuard(
      expired ? "Your session has ended" : "Sign in to view your inquiries",
      expired
        ? "Sign in again to continue reviewing your inquiries."
        : "Inquiries are private to the account that submitted them.",
      { expired }
    );
    return;
  }
  showGuard(
    "Buyer account required",
    "Only buyer accounts can view submitted inquiries."
  );
}

function roleAllows(user) {
  return Boolean(
    user &&
      user.role === role &&
      (!user.accountStatus || user.accountStatus === "ACTIVE")
  );
}

async function start() {
  if (state.starting) {
    return;
  }
  state.starting = true;
  try {
    const stateView = await initializeSession();
    if (!roleAllows(stateView.user) || stateView.status !== "authenticated") {
      if (isBuyerPage) {
        denyAccess(stateView);
      } else if (!stateView.user || stateView.status !== "authenticated") {
        redirectToLogin();
      } else {
        denyAccess(stateView);
      }
      return;
    }

    setVisible(refs.guard, false);
    if (refs.content) {
      refs.content.hidden = false;
    }
    state.allowed = true;
    state.lastRevision = getAuthRevision();
    await loadList();
  } finally {
    state.starting = false;
  }
}

function filterParams() {
  const params = { page: state.page, size: PAGE_SIZE, sort: state.sort };
  if (STATUSES.has(state.status)) {
    params.status = state.status;
  }
  return params;
}

function setBusy(busy) {
  if (refs.content) {
    refs.content.setAttribute("aria-busy", busy ? "true" : "false");
  }
  setVisible(refs.loading, busy);
  if (busy) {
    setVisible(refs.error, false);
    setVisible(refs.empty, false);
    setVisible(refs.table, false);
    setVisible(refs.cards, false);
    setVisible(refs.pagination, false);
  }
}

function showListError(message) {
  setText(refs.errorMessage, message);
  setVisible(refs.error, true);
  setVisible(refs.table, false);
  setVisible(refs.cards, false);
  setVisible(refs.empty, false);
  setVisible(refs.pagination, false);
  updateRetryState();
}

async function loadList({ clearDetailOnLoad = true } = {}) {
  if (!state.allowed) {
    return;
  }
  const sequence = ++state.sequence;
  if (clearDetailOnLoad) {
    clearDetail();
  }
  setBusy(true);

  const result = await inquiryService.listInquiries(scope, filterParams());
  if (sequence !== state.sequence || !state.allowed) {
    return;
  }

  setBusy(false);
  if (!result.ok) {
    handleFailure(result, "load");
    return;
  }

  const total = Number.isInteger(result.meta?.totalElements) ? result.meta.totalElements : 0;
  setText(
    refs.summary,
    `${total} ${total === 1 ? "inquiry" : "inquiries"}${state.status ? ` · ${statusLabel(state.status)}` : ""}`
  );

  const rendered = renderItems(result.items, {
    scope,
    onOpen: (id) => {
      void openDetail(id);
    }
  });
  clear(refs.tbody);
  clear(refs.cards);
  rendered.rows.forEach((row) => refs.tbody?.append(row));
  rendered.cards.forEach((card) => refs.cards?.append(card));

  setVisible(refs.table, rendered.rows.length > 0);
  setVisible(refs.cards, rendered.cards.length > 0);
  setVisible(refs.empty, rendered.rows.length === 0);

  const meta = result.meta || {};
  const hasNext = meta.hasNext === true;
  const hasPrevious = meta.hasPrevious === true;
  if (refs.prev) {
    refs.prev.disabled = !hasPrevious;
  }
  if (refs.next) {
    refs.next.disabled = !hasNext;
  }
  setText(
    refs.pageStatus,
    `Page ${(Number.isInteger(meta.page) ? meta.page : state.page) + 1} of ${Math.max(1, Number(meta.totalPages) || 1)}`
  );
  setVisible(refs.pagination, hasNext || hasPrevious);
  updateActionStates();
}

function handleFailure(result, activity) {
  if (result.reason === "cancelled" || result.reason === "stale") {
    return;
  }
  if (result.reason === "auth" || result.reason === "auth_changed") {
    if (!isBuyerPage) {
      redirectToLogin({ expired: true });
    } else {
      showGuard("Your session has ended", "Sign in again to continue.", { expired: true });
    }
    return;
  }

  const message = describeFailure(result, activity);
  if (!message) {
    return;
  }

  if (activity === "load") {
    showListError(message);
    return;
  }

  if (activity === "detail") {
    clear(refs.detailBody);
    setText(refs.detailErrorMessage, message);
    setVisible(refs.detailError, true);
    updateRetryState();
    return;
  }

  if (result.detail) {
    renderAuthoritativeDetail(result.detail);
  }
  if (!isBuyerPage) {
    toast(message, "error");
  } else {
    showListError(message);
  }
}

async function openDetail(id) {
  if (!state.allowed || typeof id !== "string") {
    return;
  }
  state.detailId = id;
  state.resolvedAt = null;
  clear(refs.detailBody);
  setText(refs.detailErrorMessage, "");
  setVisible(refs.detailError, false);
  refs.detailBody?.append(node("p", { className: "text-muted", text: "Loading inquiry…" }));
  setVisible(refs.detail, true);

  const result = await inquiryService.getDetail(id);
  if (!state.allowed || state.detailId !== id) {
    return;
  }
  if (!result.ok) {
    handleFailure(result, "detail");
    return;
  }
  renderAuthoritativeDetail(result.detail);
  refs.detail?.scrollIntoView({ block: "nearest" });
}

function renderAuthoritativeDetail(detail) {
  clear(refs.detailBody);
  refs.detailBody?.append(
    renderDetail(detail, {
      canManage: !isBuyerPage,
      onResolvedAt: (value) => {
        state.resolvedAt = value;
      },
      onAction: (target, expectedStatus) => {
        void changeStatus(target, expectedStatus);
      }
    })
  );
  updateActionStates();
}

async function changeStatus(target, expectedStatus) {
  const id = state.detailId;
  if (!id || !state.allowed) {
    return;
  }

  if (target === "CLOSED") {
    const confirmed = await confirmDialog({
      title: "Close inquiry",
      message: "Closing an inquiry is final. It cannot be reopened. Continue only when the matter is complete.",
      confirmLabel: "Close inquiry",
      destructive: true
    });
    if (!confirmed || state.detailId !== id) {
      return;
    }
  }

  const result = await inquiryService.changeStatus(id, target, expectedStatus);
  if (result.ok) {
    renderAuthoritativeDetail(result.detail);
    if (!isBuyerPage) {
      toast(`Status saved: ${statusLabel(target)}.`);
    }
    await loadList({ clearDetailOnLoad: false });
    return;
  }
  handleFailure(result, "mutate");
}

function updateActionStates() {
  const view = inquiryService.getView();
  const locked = view.cooldownActive;
  document.querySelectorAll("[data-inquiry-detail] [data-action='status']").forEach((button) => {
    const pending = view.pendingMutations.some(
      (entry) => entry.inquiryId === state.detailId
    );
    button.disabled = pending || locked;
  });
  updateRetryState();
}

function updateRetryState() {
  if (!refs.retry) {
    return;
  }
  const active = inquiryService.getCooldown().active();
  const remaining = inquiryService.getCooldown().remainingMs();
  refs.retry.disabled = active;
  if (active) {
    refs.retry.setAttribute("aria-disabled", "true");
    refs.retry.textContent = `Retry after ${formatDuration(remaining)}`;
  } else {
    refs.retry.removeAttribute("aria-disabled");
    refs.retry.textContent = "Try again";
  }
}

function tickCooldown() {
  if (inquiryService.getCooldown().active()) {
    updateActionStates();
    if (!cooldownTimer) {
      cooldownTimer = window.setInterval(tickCooldown, 1000);
    }
  } else if (cooldownTimer) {
    window.clearInterval(cooldownTimer);
    cooldownTimer = null;
    updateActionStates();
  }
}

function wireEvents() {
  if (refs.filters) {
    refs.filters.addEventListener("submit", (event) => {
      event.preventDefault();
      if (!state.allowed) {
        return;
      }
      const status = refs.status ? refs.status.value : "";
      const sort = refs.sort ? refs.sort.value : "newest";
      if (!STATUSES.has(status) && status !== "") {
        return;
      }
      if (!SORTS.has(sort)) {
        return;
      }
      state.status = status;
      state.sort = sort;
      state.page = 0;
      void loadList();
    });
  }
  if (refs.reset) {
    refs.reset.addEventListener("click", () => {
      if (!state.allowed) {
        return;
      }
      if (refs.filters) {
        refs.filters.reset();
      }
      state.status = "";
      state.sort = "newest";
      state.page = 0;
      void loadList();
    });
  }
  if (refs.prev) {
    refs.prev.addEventListener("click", () => {
      if (state.allowed && state.page > 0) {
        state.page -= 1;
        void loadList();
      }
    });
  }
  if (refs.next) {
    refs.next.addEventListener("click", () => {
      if (state.allowed) {
        state.page += 1;
        void loadList();
      }
    });
  }
  if (refs.retry) {
    refs.retry.addEventListener("click", () => {
      if (state.allowed && !inquiryService.getCooldown().active()) {
        void loadList();
      }
    });
  }
  if (refs.detailClose) {
    refs.detailClose.addEventListener("click", clearDetail);
  }
  if (refs.detailRetry) {
    refs.detailRetry.addEventListener("click", () => {
      if (state.allowed && state.detailId) {
        void openDetail(state.detailId);
      }
    });
  }
  inquiryService.subscribe(updateActionStates);

  subscribeAuth(() => {
    const current = getAuthRevision();
    if (current === state.lastRevision) {
      return;
    }
    state.lastRevision = current;
    clearPrivateState();
    void start();
  });

  window.addEventListener("pagehide", clearPrivateState);
  window.addEventListener("pageshow", (event) => {
    if (event.persisted) {
      clearPrivateState();
      void start();
    }
  });
}

if (!isBuyerPage) {
  initManagementShell({
    role,
    active: "inquiries",
    title: scope === "admin" ? "Inquiry monitoring" : "My inquiries"
  });
}

wireEvents();
void start();
