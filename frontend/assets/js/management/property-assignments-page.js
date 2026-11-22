/**
 * Stage 4.7 admin assignment page.
 *
 * There is no implemented authorized endpoint that lists assignable agents, so
 * this page accepts a manual agent UUID. The backend validates existence, role,
 * and active status before applying the assignment.
 */

import { ApiError } from "../core/api.js";
import {
  MANAGEMENT_PAGE_SIZE,
  ROLES,
  STATUS_FILTERS,
  statusLabel
} from "./constants.js";
import { assignProperty, getProperty, listAdminProperties } from "./property-api.js";
import {
  handleApiError,
  initManagementShell,
  messageForError,
  requireManagementRole,
  showAccessDenied,
  toast
} from "./management-shell.js";
import { agentName, clear, element, query, safeImageUrl, statusBadge } from "./dom.js";
import { UUID_PATTERN } from "./property-api.js";

const refs = {
  content: query("[data-management-content]"),
  notice: query("[data-assignment-notice]"),
  noticeText: query("[data-assignment-notice-text]"),
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
  keywordFilter: query("#filterKeyword"),
  cityFilter: query("#filterCity"),
  agentFilter: query("#filterAgent")
};

const state = {
  page: 0,
  rows: [],
  meta: null
};

function showElement(node, visible) {
  if (node) {
    node.hidden = !visible;
  }
}

function showError(message) {
  if (refs.error) {
    refs.error.textContent = message;
    refs.error.hidden = false;
  }
}

function clearError() {
  if (refs.error) {
    refs.error.textContent = "";
    refs.error.hidden = true;
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
  const status = refs.statusFilter?.value.trim();
  const keyword = refs.keywordFilter?.value.trim();
  const city = refs.cityFilter?.value.trim();
  const primaryAgentId = refs.agentFilter?.value.trim();
  if (status) {
    values.status = status;
  }
  if (keyword) {
    values.keyword = keyword;
  }
  if (city) {
    values.city = city;
  }
  if (primaryAgentId) {
    values.primaryAgentId = primaryAgentId;
  }
  return values;
}

function propertyCell(row) {
  const link = element("a", {
    text: row.title || "Untitled property",
    attributes: {
      href: `/dashboard/admin/property-edit.html?id=${encodeURIComponent(row.id)}`
    }
  });
  return element("div", { className: "mgmt-property-cell" }, [
    element("img", {
      attributes: {
        src: safeImageUrl(row.primaryImageUrl),
        alt: "",
        width: "64",
        height: "48",
        loading: "lazy"
      }
    }),
    element("div", { className: "mgmt-property-cell__text" }, [
      link,
      element("span", { text: row.subtype || row.category || "Property" })
    ])
  ]);
}

function assignButton(row) {
  return element("button", {
    className: "btn btn-sm btn-primary",
    text: "Assign agent",
    type: "button",
    dataset: { action: "assign", propertyId: row.id }
  });
}

function renderRows(rows) {
  if (refs.tbody) {
    clear(refs.tbody);
    rows.forEach((row) => {
      const location = row.locality && row.city ? `${row.locality}, ${row.city}` : row.city || "—";
      const tr = element("tr");
      tr.append(
        element("td", {}, [propertyCell(row)]),
        element("td", { text: location }),
        element("td", {}, [statusBadge(row.status)]),
        element("td", {}, [assignButton(row)])
      );
      refs.tbody.append(tr);
    });
  }

  if (refs.records) {
    clear(refs.records);
    rows.forEach((row) => {
      const record = element("div", { className: "mgmt-record" }, [
        element("img", {
          attributes: {
            src: safeImageUrl(row.primaryImageUrl),
            alt: "",
            width: "64",
            height: "64",
            loading: "lazy"
          }
        }),
        element("div", { className: "mgmt-record__body" }, [
          element("h3", { className: "mgmt-record__title" }, [
            element("a", {
              text: row.title || "Untitled property",
              attributes: {
                href: `/dashboard/admin/property-edit.html?id=${encodeURIComponent(row.id)}`
              }
            }),
            statusBadge(row.status)
          ]),
          element("p", {
            className: "mgmt-record__meta",
            text: row.locality || row.city || "Location not set"
          }),
          element("div", { className: "mgmt-record__actions" }, [assignButton(row)])
        ])
      ]);
      refs.records.append(record);
    });
  }
}

function renderPagination(meta) {
  if (!refs.pagination) {
    return;
  }
  const total = Number(meta?.totalElements ?? 0);
  const page = Number(meta?.page ?? state.page);
  const size = Number(meta?.size ?? MANAGEMENT_PAGE_SIZE);
  const start = total === 0 ? 0 : page * size + 1;
  const end = Math.min(total, (page + 1) * size);
  if (refs.paginationSummary) {
    refs.paginationSummary.textContent = `Showing ${start}–${end} of ${total}`;
  }
  if (refs.prev) {
    refs.prev.disabled = !meta?.hasPrevious;
  }
  if (refs.next) {
    refs.next.disabled = !meta?.hasNext;
  }
  showElement(refs.pagination, total > 0);
}

function renderList(payload) {
  const rows = Array.isArray(payload?.data) ? payload.data : [];
  state.rows = rows;
  state.meta = payload?.meta || null;
  const total = Number(state.meta?.totalElements ?? rows.length);
  if (refs.summary) {
    refs.summary.textContent = `${total} ${total === 1 ? "property" : "properties"}`;
  }
  renderRows(rows);
  renderPagination(state.meta);
  showElement(refs.table, rows.length > 0);
  showElement(refs.records, rows.length > 0);
  showElement(refs.empty, rows.length === 0);
}

async function loadList() {
  setBusy(true);
  clearError();
  try {
    const payload = await listAdminProperties(filterValues());
    renderList(payload);
  } catch (error) {
    if (handleApiError(error, { onForbidden: showAccessDenied })) {
      return;
    }
    showError(messageForError(error, "load"));
  } finally {
    setBusy(false);
  }
}

function assignmentErrorMessage(error) {
  if (error instanceof ApiError) {
    if (error.status === 409) {
      return "That agent is already assigned to this property.";
    }
    if (error.status === 400) {
      return "Enter the UUID of an active agent account.";
    }
    if (error.status === 404) {
      return "No active agent was found for that ID.";
    }
  }
  return messageForError(error);
}

function openAssignmentDialog(property) {
  return new Promise((resolve) => {
    const previousFocus = document.activeElement;
    const dialog = element("dialog", {
      className: "ev-modal",
      attributes: {
        "aria-labelledby": "assignmentDialogTitle",
        "aria-describedby": "assignmentDialogMessage"
      }
    });

    const current = property.primaryAgent || null;
    const message = element("p", {
      id: "assignmentDialogMessage",
      className: "ev-modal__body",
      text: `Assign an agent to ${property.title || "this property"}.`
    });
    const currentLine = element("p", {
      className: "mgmt-field__hint",
      text: current
        ? `Current primary agent: ${agentName(current)} (${current.id || "ID unavailable"})`
        : "Current primary agent: none"
    });
    const warning = element("p", {
      className: "ev-modal__warning",
      hidden: !current,
      text: current
        ? "Reassigning replaces the current primary agent. Confirm that this change is intended."
        : ""
    });

    const input = element("input", {
      className: "form-control",
      id: "assignmentAgentId",
      type: "text",
      value: current?.id || "",
      placeholder: "00000000-0000-0000-0000-000000000000",
      required: true
    });
    input.setAttribute("aria-describedby", "assignmentAgentHelp assignmentAgentError");
    const fieldError = element("p", {
      className: "mgmt-field__error",
      id: "assignmentAgentError",
      hidden: true
    });

    const cancel = element("button", {
      className: "btn btn-secondary",
      text: "Cancel",
      type: "button"
    });
    const confirm = element("button", {
      className: "btn btn-primary",
      text: "Assign agent",
      type: "button"
    });

    const form = element("form", { attributes: { method: "dialog" } }, [
      element("h2", { id: "assignmentDialogTitle", className: "ev-modal__title", text: "Assign primary agent" }),
      message,
      currentLine,
      warning,
      element("div", { className: "mgmt-field" }, [
        element("label", { attributes: { for: "assignmentAgentId" }, text: "Agent UUID" }),
        input,
        element("p", {
          className: "mgmt-field__hint",
          id: "assignmentAgentHelp",
          text: "An active agent account is required. The backend validates this identifier."
        }),
        fieldError
      ]),
      element("div", { className: "ev-modal__actions" }, [cancel, confirm])
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
    confirm.addEventListener("click", () => {
      const value = input.value.trim();
      if (!UUID_PATTERN.test(value)) {
        fieldError.textContent = "Enter a valid agent UUID.";
        fieldError.hidden = false;
        input.setAttribute("aria-invalid", "true");
        input.focus();
        return;
      }
      finish(value);
    });
    dialog.addEventListener("cancel", (event) => {
      event.preventDefault();
      finish(null);
    });

    if (typeof dialog.showModal === "function") {
      dialog.showModal();
    } else {
      dialog.setAttribute("open", "");
    }
    if (current) {
      input.select();
    } else {
      input.focus();
    }
  });
}

async function handleAssign(propertyId) {
  try {
    const detail = await getProperty(propertyId);
    const property = detail?.data || state.rows.find((row) => row.id === propertyId);
    if (!property) {
      toast("This property is no longer available.", "error");
      return;
    }
    const agentId = await openAssignmentDialog(property);
    if (!agentId) {
      return;
    }
    await assignProperty(propertyId, agentId);
    toast("Primary agent assigned.");
    await loadList();
  } catch (error) {
    if (handleApiError(error, { onForbidden: showAccessDenied })) {
      return;
    }
    toast(assignmentErrorMessage(error), "error");
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
      refs.filters?.reset();
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
      const button = event.target?.closest?.("[data-action='assign'][data-property-id]");
      if (!button) {
        return;
      }
      button.disabled = true;
      handleAssign(button.dataset.propertyId).finally(() => {
        button.disabled = false;
      });
    });
  }
}

async function start() {
  const user = await requireManagementRole([ROLES.ADMIN]);
  if (!user || user.role !== ROLES.ADMIN) {
    showAccessDenied();
    return;
  }

  initManagementShell({ role: ROLES.ADMIN, active: "assignments", title: "Property Assignments" });
  if (refs.notice && refs.noticeText) {
    refs.noticeText.textContent =
      "Agent selection is manual because no authorized agent-list endpoint is implemented. Enter the active agent's UUID.";
    refs.notice.hidden = false;
  }
  populateStatusFilter();
  wireEvents();
  await loadList();
}

start().catch(() => {
  showError("We could not initialize this page. Reload and try again.");
});
