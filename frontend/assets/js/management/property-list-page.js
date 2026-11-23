/**
 * Stage 4.7 property list page.
 *
 * Renders the approved admin/agent list endpoints, applies only supported
 * filters, and drives the approved lifecycle and archive endpoints. All
 * untrusted values are inserted with textContent through dom.js helpers.
 */

import {
  MANAGEMENT_PAGE_SIZE,
  ROLES,
  STATUS_FILTERS,
  isConsequentialTransition,
  permittedTransitions,
  statusLabel,
  transitionLabel
} from "./constants.js";
import {
  archiveProperty,
  changePropertyStatus,
  getCategories,
  getProperty,
  listAdminProperties,
  listAgentProperties
} from "./property-api.js";
import {
  confirmDialog,
  handleApiError,
  initManagementShell,
  messageForError,
  requireManagementRole,
  showAccessDenied,
  toast
} from "./management-shell.js";
import {
  clear,
  element,
  formatArea,
  formatPrice,
  query,
  safeImageUrl,
  statusBadge
} from "./dom.js";

const pageBody = document.body;
const declaredRole = pageBody.dataset.managementRole === ROLES.AGENT ? ROLES.AGENT : ROLES.ADMIN;
const isAdmin = declaredRole === ROLES.ADMIN;

const refs = {
  content: query("[data-management-content]"),
  filters: query("[data-list-filters]"),
  reset: query("[data-list-reset]"),
  loading: query("[data-list-loading]"),
  error: query("[data-list-error]"),
  table: query("[data-list-table]"),
  tbody: query("[data-list-body]"),
  records: query("[data-list-records]"),
  empty: query("[data-list-empty]"),
  summary: query("[data-list-summary]"),
  pagination: query("[data-list-pagination]"),
  paginationSummary: query("[data-pagination-summary]"),
  prev: query("[data-page-prev]"),
  next: query("[data-page-next]"),
  statusFilter: query("#filterStatus"),
  categoryFilter: query("#filterCategory"),
  keywordFilter: query("#filterKeyword"),
  cityFilter: query("#filterCity"),
  agentFilter: query("#filterAgent")
};

const state = {
  role: declaredRole,
  page: 0,
  rows: [],
  meta: null
};

function showElement(node, visible) {
  if (node) {
    node.hidden = !visible;
  }
}

function showListError(message) {
  if (!refs.error) {
    return;
  }
  refs.error.textContent = message;
  showElement(refs.error, true);
}

function clearListError() {
  if (refs.error) {
    refs.error.textContent = "";
    showElement(refs.error, false);
  }
}

function setBusy(busy) {
  if (refs.content) {
    refs.content.setAttribute("aria-busy", busy ? "true" : "false");
  }
  showElement(refs.loading, busy);
  if (busy) {
    showElement(refs.table, false);
    showElement(refs.records, false);
    showElement(refs.empty, false);
    showElement(refs.pagination, false);
  }
}

function filterValues() {
  const values = { page: state.page, size: MANAGEMENT_PAGE_SIZE };
  if (!refs.filters) {
    return values;
  }

  const status = refs.statusFilter ? refs.statusFilter.value.trim() : "";
  if (status) {
    values.status = status;
  }
  if (!isAdmin) {
    return values;
  }

  const keyword = refs.keywordFilter ? refs.keywordFilter.value.trim() : "";
  const categoryId = refs.categoryFilter ? refs.categoryFilter.value.trim() : "";
  const city = refs.cityFilter ? refs.cityFilter.value.trim() : "";
  const primaryAgentId = refs.agentFilter ? refs.agentFilter.value.trim() : "";

  if (keyword) {
    values.keyword = keyword;
  }
  if (categoryId) {
    values.categoryId = categoryId;
  }
  if (city) {
    values.city = city;
  }
  if (primaryAgentId) {
    values.primaryAgentId = primaryAgentId;
  }
  return values;
}

function actionButton(label, className, action, propertyId) {
  return element("button", {
    className: `btn btn-sm ${className}`.trim(),
    text: label,
    type: "button",
    dataset: { action, propertyId }
  });
}

function propertyCell(row, editHref) {
  const image = element("img", {
    attributes: {
      src: safeImageUrl(row.primaryImageUrl),
      alt: "",
      width: "64",
      height: "48",
      loading: "lazy"
    }
  });
  const link = element("a", {
    text: row.title || "Untitled property",
    attributes: { href: editHref }
  });
  const meta = element("span", {
    text: `${row.subtype || row.category || "Property"} · ${row.slug || row.id || ""}`
  });
  return element("div", { className: "mgmt-property-cell" }, [
    image,
    element("div", { className: "mgmt-property-cell__text" }, [link, meta])
  ]);
}

function actionGroup(row) {
  const id = row.id;
  const editHref = `/dashboard/${state.role.toLowerCase()}/property-edit.html?id=${encodeURIComponent(id)}`;
  const imagesHref = `/dashboard/${state.role.toLowerCase()}/property-images.html?id=${encodeURIComponent(id)}`;
  const group = element("div", { className: "mgmt-actions" });

  group.append(
    element("a", {
      className: "btn btn-sm btn-outline-primary",
      text: "Edit",
      attributes: { href: editHref }
    }),
    element("a", {
      className: "btn btn-sm btn-outline-secondary",
      text: "Images",
      attributes: { href: imagesHref }
    })
  );

  const targets = permittedTransitions(row, state.role);
  if (targets.length > 0) {
    const label = targets.length === 1 ? transitionLabel(targets[0]) : "Change status";
    group.append(actionButton(label, "btn-outline-dark", "status", id));
  }
  if (isAdmin && row.status !== "ARCHIVED") {
    group.append(actionButton("Archive", "btn-outline-danger", "archive", id));
  }
  return group;
}

function renderTable(rows) {
  if (!refs.tbody) {
    return;
  }
  clear(refs.tbody);

  rows.forEach((row) => {
    const id = row.id;
    const editHref = `/dashboard/${state.role.toLowerCase()}/property-edit.html?id=${encodeURIComponent(id)}`;
    const price = formatPrice(row.price, row.currencyCode);
    const area = formatArea(row.areaValue, row.areaUnit);
    const city = row.locality && row.city ? `${row.locality}, ${row.city}` : row.city || "—";

    const tr = element("tr");
    tr.append(
      element("td", {}, [propertyCell(row, editHref)]),
      element("td", {}, [
        element("span", { text: row.category || "—" }),
        element("br"),
        element("small", { className: "text-muted", text: row.subtype || "" })
      ]),
      element("td", {}, [
        element("span", { text: city }),
        element("br"),
        element("small", { className: "text-muted", text: area })
      ]),
      element("td", { text: price }),
      element("td", {}, [statusBadge(row.status)]),
      element("td", {}, [actionGroup(row)])
    );
    refs.tbody.append(tr);
  });
}

function renderRecords(rows) {
  if (!refs.records) {
    return;
  }
  clear(refs.records);

  rows.forEach((row) => {
    const editHref = `/dashboard/${state.role.toLowerCase()}/property-edit.html?id=${encodeURIComponent(row.id)}`;
    const image = element("img", {
      attributes: {
        src: safeImageUrl(row.primaryImageUrl),
        alt: "",
        width: "64",
        height: "64",
        loading: "lazy"
      }
    });
    const title = element("h3", { className: "mgmt-record__title" }, [
      element("a", {
        text: row.title || "Untitled property",
        attributes: { href: editHref }
      }),
      statusBadge(row.status)
    ]);
    const meta = element("p", {
      className: "mgmt-record__meta",
      text: `${row.category || "Property"} · ${row.locality || row.city || "Location not set"} · ${formatPrice(row.price, row.currencyCode)}`
    });
    const record = element("div", { className: "mgmt-record" }, [
      image,
      element("div", { className: "mgmt-record__body" }, [title, meta, actionGroup(row)])
    ]);
    refs.records.append(record);
  });
}

function renderPagination(meta) {
  if (!refs.pagination) {
    return;
  }
  const total = Number(meta?.totalElements ?? 0);
  const page = Number(meta?.page ?? state.page);
  const size = Number(meta?.size ?? MANAGEMENT_PAGE_SIZE);
  const hasPrevious = Boolean(meta?.hasPrevious);
  const hasNext = Boolean(meta?.hasNext);

  const start = total === 0 ? 0 : page * size + 1;
  const end = Math.min(total, (page + 1) * size);
  if (refs.paginationSummary) {
    refs.paginationSummary.textContent = `Showing ${start}–${end} of ${total}`;
  }
  if (refs.prev) {
    refs.prev.disabled = !hasPrevious;
  }
  if (refs.next) {
    refs.next.disabled = !hasNext;
  }
  showElement(refs.pagination, total > 0);
}

function renderList(payload) {
  const rows = Array.isArray(payload?.data) ? payload.data : [];
  const meta = payload?.meta || null;
  state.rows = rows;
  state.meta = meta;

  const total = Number(meta?.totalElements ?? rows.length);
  if (refs.summary) {
    refs.summary.textContent = `${total} ${total === 1 ? "property" : "properties"}`;
  }

  renderTable(rows);
  renderRecords(rows);
  renderPagination(meta);

  showElement(refs.table, rows.length > 0);
  showElement(refs.records, rows.length > 0);
  showElement(refs.empty, rows.length === 0);
}

async function loadList() {
  setBusy(true);
  clearListError();
  try {
    const params = filterValues();
    const payload = isAdmin
      ? await listAdminProperties(params)
      : await listAgentProperties(params);
    renderList(payload);
  } catch (error) {
    if (handleApiError(error, { onForbidden: showAccessDenied })) {
      return;
    }
    showListError(messageForError(error, "load"));
  } finally {
    setBusy(false);
  }
}

function openStatusDialog(property, targets) {
  return new Promise((resolve) => {
    const previousFocus = document.activeElement;
    const dialog = element("dialog", {
      className: "ev-modal",
      attributes: {
        "aria-labelledby": "propertyStatusDialogTitle",
        "aria-describedby": "propertyStatusDialogMessage"
      }
    });

    const heading = element("h2", {
      id: "propertyStatusDialogTitle",
      className: "ev-modal__title",
      text: "Change property status"
    });
    const message = element("p", {
      id: "propertyStatusDialogMessage",
      className: "ev-modal__body",
      text: `${property.title || "This property"} is currently ${statusLabel(property.status)}.`
    });

    const select = element("select", {
      className: "form-select",
      id: "propertyStatusTarget",
      attributes: { "aria-label": "Target status" }
    });
    targets.forEach((target) => {
      select.append(
        element("option", {
          value: target,
          text: `${transitionLabel(target)} (${statusLabel(target)})`
        })
      );
    });

    const warning = element("p", {
      className: "ev-modal__warning",
      hidden: true
    });

    const updateWarning = () => {
      const target = select.value;
      const consequential = isConsequentialTransition(property.status, target);
      warning.hidden = !consequential;
      warning.textContent = consequential
        ? "This changes the listing's availability and may affect customers. Continue only when the transaction or decision is final."
        : "";
    };
    select.addEventListener("change", updateWarning);

    const cancel = element("button", {
      className: "btn btn-secondary",
      text: "Cancel",
      type: "button"
    });
    const accept = element("button", {
      className: "btn btn-primary",
      text: "Confirm status change",
      type: "button"
    });
    const form = element("form", { attributes: { method: "dialog" } }, [
      heading,
      message,
      element("div", { className: "mgmt-field" }, [
        element("label", { attributes: { for: "propertyStatusTarget" }, text: "New status" }),
        select
      ]),
      warning,
      element("div", { className: "ev-modal__actions" }, [cancel, accept])
    ]);
    dialog.append(form);
    document.body.append(dialog);

    let settled = false;
    const finish = (value) => {
      if (settled) {
        return;
      }
      settled = true;
      resolve(value);
      if (dialog.open && typeof dialog.close === "function") {
        dialog.close();
      }
      dialog.remove();
      if (previousFocus && typeof previousFocus.focus === "function") {
        previousFocus.focus();
      }
    };

    cancel.addEventListener("click", () => finish(null));
    accept.addEventListener("click", () => finish(select.value));
    dialog.addEventListener("cancel", (event) => {
      event.preventDefault();
      finish(null);
    });
    updateWarning();
    if (typeof dialog.showModal === "function") {
      dialog.showModal();
    } else {
      dialog.setAttribute("open", "");
    }
    select.focus();
  });
}

async function handleStatusAction(propertyId) {
  try {
    const detail = await getProperty(propertyId);
    const property = detail.data || state.rows.find((row) => row.id === propertyId);
    if (!property) {
      toast("This property is no longer available.", "error");
      return;
    }

    const targets = permittedTransitions(property, state.role);
    if (targets.length === 0) {
      toast("No further status changes are available for this listing.", "error");
      return;
    }

    const target = await openStatusDialog(property, targets);
    if (!target) {
      return;
    }

    await changePropertyStatus(property.id, target);
    toast(`${statusLabel(target)} status saved.`);
    await loadList();
  } catch (error) {
    if (handleApiError(error, { onForbidden: showAccessDenied })) {
      return;
    }
    toast(messageForError(error), "error");
  }
}

async function handleArchiveAction(propertyId) {
  const row = state.rows.find((item) => item.id === propertyId);
  const label = row?.title || "this property";
  const confirmed = await confirmDialog({
    title: "Archive property",
    message: `Archive ${label}? Archived listings are terminal and cannot be reopened from this page.`,
    confirmLabel: "Archive property",
    destructive: true
  });
  if (!confirmed) {
    return;
  }

  try {
    await archiveProperty(propertyId);
    toast("Property archived.");
    await loadList();
  } catch (error) {
    if (handleApiError(error, { onForbidden: showAccessDenied })) {
      return;
    }
    toast(messageForError(error), "error");
  }
}

function wireEvents() {
  if (refs.filters) {
    refs.filters.addEventListener("submit", (event) => {
      event.preventDefault();
      state.page = 0;
      loadList();
    });
  }
  if (refs.reset) {
    refs.reset.addEventListener("click", () => {
      if (refs.filters) {
        refs.filters.reset();
      }
      state.page = 0;
      loadList();
    });
  }
  if (refs.prev) {
    refs.prev.addEventListener("click", () => {
      if (state.page > 0) {
        state.page -= 1;
        loadList();
      }
    });
  }
  if (refs.next) {
    refs.next.addEventListener("click", () => {
      if (state.meta?.hasNext) {
        state.page += 1;
        loadList();
      }
    });
  }
  if (refs.content) {
    refs.content.addEventListener("click", (event) => {
      const button = event.target?.closest?.("[data-action][data-property-id]");
      if (!button) {
        return;
      }
      const { action, propertyId } = button.dataset;
      if (action === "status") {
        button.disabled = true;
        handleStatusAction(propertyId).finally(() => {
          button.disabled = false;
        });
      } else if (action === "archive") {
        button.disabled = true;
        handleArchiveAction(propertyId).finally(() => {
          button.disabled = false;
        });
      }
    });
  }
}

function populateStatusFilter() {
  if (!refs.statusFilter) {
    return;
  }
  STATUS_FILTERS.forEach((status) => {
    refs.statusFilter.append(
      element("option", { value: status, text: `${statusLabel(status)} (${status})` })
    );
  });
  const initial = new URLSearchParams(window.location.search).get("status");
  if (STATUS_FILTERS.includes(initial)) {
    refs.statusFilter.value = initial;
  }
}

async function populateCategoryFilter() {
  if (!refs.categoryFilter) {
    return;
  }
  try {
    const payload = await getCategories();
    const categories = Array.isArray(payload?.data) ? payload.data : [];
    categories.forEach((category) => {
      refs.categoryFilter.append(
        element("option", { value: category.id, text: category.name || "Category" })
      );
    });
  } catch {
    refs.categoryFilter.disabled = true;
    refs.categoryFilter.title = "Categories are temporarily unavailable.";
  }
}

async function start() {
  const user = await requireManagementRole([ROLES.ADMIN, ROLES.AGENT]);
  if (!user) {
    return;
  }
  state.role = user.role === ROLES.AGENT ? ROLES.AGENT : ROLES.ADMIN;
  if (state.role !== declaredRole) {
    showAccessDenied();
    return;
  }

  initManagementShell({
    role: state.role,
    active: "properties",
    title: state.role === ROLES.ADMIN ? "Properties" : "My Properties"
  });
  populateStatusFilter();
  wireEvents();

  if (isAdmin) {
    await populateCategoryFilter();
  }
  await loadList();
}

start();
