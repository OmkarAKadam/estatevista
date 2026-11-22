/**
 * Stage 4.7 property create/edit page.
 *
 * Create is ADMIN-only. Edit is available to ADMIN and AGENT for properties
 * they may access. PATCH sends only changed fields; an explicit full-replacement
 * option sends PUT. Category, subtype, assignment, status, identifiers, audit
 * fields, and images are never included in update payloads.
 */

import { ApiError } from "../core/api.js";
import { clearFieldErrors, focusFirstInvalid, setFieldError } from "../auth/auth-ui.js";
import {
  CATEGORY_FIELD_SETS,
  ROLES,
  categoryKind,
  listingTypeLabel
} from "./constants.js";
import {
  createProperty,
  getAmenities,
  getCategories,
  getProperty,
  getSubtypes,
  patchProperty,
  putProperty
} from "./property-api.js";
import {
  handleApiError,
  initManagementShell,
  messageForError,
  requireManagementRole,
  showAccessDenied,
  toast
} from "./management-shell.js";
import {
  element,
  formatPrice,
  query,
  safeImageUrl
} from "./dom.js";

const pageBody = document.body;
const mode = pageBody.dataset.managementMode === "edit" ? "edit" : "create";
const declaredRole = pageBody.dataset.managementRole === ROLES.AGENT ? ROLES.AGENT : ROLES.ADMIN;
const isCreate = mode === "create";

const refs = {
  content: query("[data-management-content]"),
  form: query("[data-property-form]"),
  formError: query("[data-form-error]"),
  submit: query("[data-submit-button]"),
  formActions: query("[data-form-actions]"),
  subtitle: query("[data-form-subtitle]"),
  category: query("#categoryId"),
  subtype: query("#subtypeId"),
  amenities: query("[data-amenities]"),
  amenitiesError: query("[data-amenities-error]"),
  replaceAll: query("#replaceAll"),
  imagesLink: query("[data-images-link]"),
  detailLink: query("[data-detail-link]"),
  summaryCategory: query("[data-summary-category]"),
  summarySubtype: query("[data-summary-subtype]"),
  summaryStatus: query("[data-summary-status]"),
  summaryListingType: query("[data-summary-listing-type]"),
  summaryPrice: query("[data-summary-price]"),
  summaryLocation: query("[data-summary-location]"),
  summaryImages: query("[data-summary-images]"),
  summaryImage: query("[data-summary-image]")
};

const state = {
  role: declaredRole,
  propertyId: "",
  property: null,
  categories: new Map(),
  original: {},
  saving: false
};

const TOP_LEVEL_FIELDS = [
  "title",
  "description",
  "listingType",
  "price",
  "currencyCode",
  "areaValue",
  "areaUnit"
];

const LOCATION_FIELDS = [
  "addressLine",
  "locality",
  "city",
  "state",
  "postalCode",
  "country",
  "latitude",
  "longitude"
];

function showFormError(message) {
  if (!refs.formError) {
    return;
  }
  refs.formError.textContent = message;
  refs.formError.hidden = false;
}

function clearFormError() {
  if (refs.formError) {
    refs.formError.textContent = "";
    refs.formError.hidden = true;
  }
}

function control(name) {
  if (!refs.form || typeof name !== "string") {
    return null;
  }
  const found = refs.form.elements.namedItem(name);
  return found instanceof Element ? found : null;
}

function controlValue(name) {
  const input = control(name);
  if (!input) {
    return null;
  }
  if (input instanceof HTMLInputElement && input.type === "checkbox") {
    return input.checked ? "true" : "false";
  }
  return input.value;
}

function setControlValue(name, value) {
  const input = control(name);
  if (!input) {
    return;
  }
  if (input instanceof HTMLInputElement && input.type === "checkbox") {
    input.checked = value === true || value === "true";
    return;
  }
  input.value = value === null || value === undefined ? "" : String(value);
}

function selectedAmenityIds() {
  if (!refs.form) {
    return [];
  }
  return Array.from(refs.form.querySelectorAll('input[name="amenityIds"]:checked')).map(
    (input) => input.value
  );
}

function setSelectedAmenityIds(ids) {
  const selected = new Set(Array.isArray(ids) ? ids : []);
  if (!refs.form) {
    return;
  }
  refs.form.querySelectorAll('input[name="amenityIds"]').forEach((input) => {
    input.checked = selected.has(input.value);
  });
}

function snapshot() {
  const values = {};
  [...TOP_LEVEL_FIELDS].forEach((name) => {
    values[name] = controlValue(name);
  });
  LOCATION_FIELDS.forEach((field) => {
    values[`location.${field}`] = controlValue(`location.${field}`);
  });
  Object.values(CATEGORY_FIELD_SETS).forEach((fields) => {
    fields.forEach((field) => {
      values[`residential.${field}`] = controlValue(`residential.${field}`);
      values[`commercial.${field}`] = controlValue(`commercial.${field}`);
    });
  });
  values.amenityIds = selectedAmenityIds().slice().sort().join(",");
  return values;
}

function isDirty() {
  const current = snapshot();
  return Object.keys(state.original).some((name) => state.original[name] !== current[name]);
}

function setBusy(busy) {
  state.saving = busy;
  if (refs.form) {
    refs.form.setAttribute("aria-busy", busy ? "true" : "false");
  }
  if (refs.submit) {
    refs.submit.disabled = busy;
    refs.submit.textContent = busy ? "Saving…" : isCreate ? "Create draft" : "Save changes";
  }
  if (refs.formActions) {
    refs.formActions.dataset.busy = busy ? "true" : "false";
  }
}

function activeKind() {
  if (!isCreate && state.property) {
    return categoryKind(state.property.category);
  }
  const id = refs.category?.value;
  const category = id ? state.categories.get(id) : null;
  return categoryKind(category?.name);
}

function showCategoryFieldSets() {
  const kind = activeKind();
  document.querySelectorAll("[data-category-kind]").forEach((fieldset) => {
    fieldset.hidden = fieldset.dataset.categoryKind !== kind;
  });
}

function resetFieldErrors() {
  if (refs.form) {
    clearFieldErrors(refs.form);
  }
  if (refs.amenitiesError) {
    refs.amenitiesError.hidden = true;
    refs.amenitiesError.textContent = "";
  }
}

function mapErrorField(field) {
  const value = String(field || "");
  if (value.startsWith("residentialDetails.")) {
    return `residential.${value.slice("residentialDetails.".length)}`;
  }
  if (value.startsWith("commercialDetails.")) {
    return `commercial.${value.slice("commercialDetails.".length)}`;
  }
  return value;
}

function applyFieldErrors(error) {
  resetFieldErrors();
  let matched = false;

  if (error instanceof ApiError && Array.isArray(error.details)) {
    error.details.forEach((detail) => {
      const input = control(mapErrorField(detail?.field));
      if (input) {
        setFieldError(input, "Please check this value.");
        matched = true;
      }
    });
  }

  if (matched) {
    showFormError("Please correct the highlighted fields and try again.");
    focusFirstInvalid(refs.form);
    return;
  }
  showFormError(messageForError(error));
}

function populateAmenities(amenities) {
  if (!refs.amenities) {
    return;
  }
  refs.amenities.replaceChildren();
  if (!Array.isArray(amenities) || amenities.length === 0) {
    refs.amenities.append(element("p", { className: "text-muted mb-0", text: "No amenities are available." }));
    return;
  }

  amenities.forEach((amenity) => {
    const input = element("input", {
      className: "form-check-input",
      id: `amenity-${amenity.id}`,
      type: "checkbox",
      value: amenity.id,
      dataset: { amenity: "" }
    });
    input.name = "amenityIds";
    const label = element("label", {
      className: "form-check-label",
      attributes: { for: `amenity-${amenity.id}` },
      text: amenity.name || "Amenity"
    });
    const wrapper = element("div", { className: "mgmt-amenity mgmt-choice" }, [input, label]);
    if (amenity.description) {
      wrapper.append(
        element("small", {
          className: "text-muted d-block w-100",
          text: amenity.description
        })
      );
    }
    refs.amenities.append(wrapper);
  });
}

async function loadAmenities() {
  try {
    const payload = await getAmenities();
    populateAmenities(payload?.data);
    setSelectedAmenityIds(state.property?.amenities?.map((amenity) => amenity.id));
  } catch (error) {
    populateAmenities([]);
    if (refs.amenitiesError) {
      refs.amenitiesError.textContent = "Amenities are temporarily unavailable. You can still save other details.";
      refs.amenitiesError.hidden = false;
    }
    if (error instanceof ApiError && error.status === 403) {
      showAccessDenied();
    }
  }
}

async function loadCategories() {
  if (!refs.category) {
    return;
  }
  try {
    const payload = await getCategories();
    const categories = Array.isArray(payload?.data) ? payload.data : [];
    categories.forEach((category) => {
      state.categories.set(category.id, category);
      refs.category.append(
        element("option", { value: category.id, text: category.name || "Category" })
      );
    });
  } catch (error) {
    refs.category.disabled = true;
    showFormError(messageForError(error, "load"));
  }
}

async function loadSubtypes(categoryId) {
  if (!refs.subtype) {
    return;
  }
  refs.subtype.replaceChildren();
  refs.subtype.append(element("option", { value: "", text: "Select subtype" }));
  refs.subtype.disabled = !categoryId;
  if (!categoryId) {
    showCategoryFieldSets();
    return;
  }

  try {
    const payload = await getSubtypes(categoryId);
    const subtypes = Array.isArray(payload?.data) ? payload.data : [];
    subtypes.forEach((subtype) => {
      refs.subtype.append(
        element("option", { value: subtype.id, text: subtype.name || "Subtype" })
      );
    });
  } catch (error) {
    showFormError(messageForError(error, "load"));
  }
  showCategoryFieldSets();
}

function fillFromDetail(property) {
  state.property = property;
  TOP_LEVEL_FIELDS.forEach((name) => setControlValue(name, property[name]));

  LOCATION_FIELDS.forEach((field) => {
    setControlValue(`location.${field}`, property.location?.[field]);
  });

  const residential = property.residentialDetails || {};
  const commercial = property.commercialDetails || {};
  CATEGORY_FIELD_SETS.RESIDENTIAL.forEach((field) => {
    setControlValue(`residential.${field}`, residential[field]);
  });
  CATEGORY_FIELD_SETS.COMMERCIAL.forEach((field) => {
    setControlValue(`commercial.${field}`, commercial[field]);
  });
  setSelectedAmenityIds(property.amenities?.map((amenity) => amenity.id));

  showCategoryFieldSets();
  updateSummary(property);
}

function updateSummary(property) {
  const id = property?.id;
  if (refs.summaryCategory) {
    refs.summaryCategory.textContent = property?.category || "—";
  }
  if (refs.summarySubtype) {
    refs.summarySubtype.textContent = property?.subtype || "—";
  }
  if (refs.summaryStatus) {
    refs.summaryStatus.textContent = property?.status || "—";
  }
  if (refs.summaryListingType) {
    refs.summaryListingType.textContent = listingTypeLabel(property?.listingType);
  }
  if (refs.summaryPrice) {
    refs.summaryPrice.textContent = formatPrice(property?.price, property?.currencyCode);
  }
  if (refs.summaryLocation) {
    const location = property?.location || {};
    refs.summaryLocation.textContent = [location.locality, location.city, location.country]
      .filter(Boolean)
      .join(", ") || "—";
  }
  if (refs.summaryImages) {
    refs.summaryImages.textContent = String(property?.images?.length ?? 0);
  }
  if (refs.summaryImage) {
    if (property?.primaryImageUrl) {
      refs.summaryImage.src = safeImageUrl(property.primaryImageUrl);
      refs.summaryImage.hidden = false;
    } else {
      refs.summaryImage.hidden = true;
    }
  }
  if (id) {
    if (refs.imagesLink) {
      refs.imagesLink.href = `/dashboard/${state.role.toLowerCase()}/property-images.html?id=${encodeURIComponent(id)}`;
    }
    if (refs.detailLink) {
      refs.detailLink.href = `/property-details.html?id=${encodeURIComponent(id)}`;
    }
  }
  if (refs.subtitle && property?.title) {
    refs.subtitle.textContent = `Editing “${property.title}”. Only changed fields are sent unless full replacement is selected.`;
  }
}

async function loadProperty() {
  try {
    const payload = await getProperty(state.propertyId);
    if (!payload?.data) {
      showFormError("This property is no longer available.");
      return false;
    }
    fillFromDetail(payload.data);
    state.original = snapshot();
    return true;
  } catch (error) {
    if (handleApiError(error, { onForbidden: showAccessDenied })) {
      return false;
    }
    showFormError(messageForError(error, "load"));
    return false;
  }
}

function validateForm() {
  resetFieldErrors();
  clearFormError();
  if (!refs.form) {
    return false;
  }
  const valid = refs.form.checkValidity();
  if (!valid) {
    showFormError("Please complete the required fields before saving.");
    refs.form.reportValidity();
    return false;
  }
  if (isCreate && refs.subtype && !refs.subtype.value) {
    setFieldError(refs.subtype, "Select a subtype.");
    showFormError("Please select a subtype before saving.");
    refs.subtype.focus();
    return false;
  }
  return true;
}

function numericValue(name) {
  const value = controlValue(name);
  if (value === "" || value === null) {
    return null;
  }
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : value;
}

function topLevelPayload() {
  const payload = {};
  TOP_LEVEL_FIELDS.forEach((name) => {
    payload[name] = name === "price" || name === "areaValue" ? numericValue(name) : controlValue(name);
  });
  return payload;
}

function locationPayload(onlyChanged = false) {
  const payload = {};
  LOCATION_FIELDS.forEach((field) => {
    const name = `location.${field}`;
    const value = controlValue(name);
    if (onlyChanged && state.original[name] === value) {
      return;
    }
    if (value === "" || value === null) {
      if (!onlyChanged) {
        payload[field] = null;
      }
      return;
    }
    payload[field] = field === "latitude" || field === "longitude" ? Number(value) : value;
  });
  return payload;
}

function detailsPayload(onlyChanged = false) {
  const kind = activeKind();
  const fields = CATEGORY_FIELD_SETS[kind] || [];
  if (fields.length === 0) {
    return null;
  }
  const prefix = kind === "RESIDENTIAL" ? "residential." : "commercial.";
  const key = kind === "RESIDENTIAL" ? "residentialDetails" : "commercialDetails";
  const payload = {};

  fields.forEach((field) => {
    const name = `${prefix}${field}`;
    const value = controlValue(name);
    if (onlyChanged && state.original[name] === value) {
      return;
    }
    if (value === "" || value === null) {
      if (!onlyChanged) {
        payload[field] = null;
      }
      return;
    }
    if (field === "powerBackup") {
      payload[field] = value === "true";
    } else if (/^(bedrooms|bathrooms|balconies|floorNumber|totalFloors|parkingSpaces|washrooms)$/.test(field)) {
      const parsed = Number(value);
      payload[field] = Number.isFinite(parsed) ? parsed : value;
    } else {
      payload[field] = value;
    }
  });

  return Object.keys(payload).length > 0 || !onlyChanged ? { [key]: payload } : null;
}

function createPayload() {
  const payload = {
    ...topLevelPayload(),
    categoryId: refs.category?.value,
    subtypeId: refs.subtype?.value,
    primaryAgentId: controlValue("primaryAgentId"),
    location: locationPayload(false),
    amenityIds: selectedAmenityIds()
  };
  const details = detailsPayload(false);
  if (details) {
    Object.assign(payload, details);
  }
  return payload;
}

function editPayload(full) {
  if (full) {
    const payload = {
      ...topLevelPayload(),
      location: locationPayload(false),
      amenityIds: selectedAmenityIds()
    };
    const details = detailsPayload(false);
    if (details) {
      Object.assign(payload, details);
    }
    return payload;
  }

  const payload = {};
  TOP_LEVEL_FIELDS.forEach((name) => {
    if (state.original[name] !== controlValue(name)) {
      payload[name] = controlValue(name);
    }
  });

  const changedLocation = locationPayload(true);
  if (Object.keys(changedLocation).length > 0) {
    payload.location = changedLocation;
  }

  const changedDetails = detailsPayload(true);
  if (changedDetails) {
    Object.assign(payload, changedDetails);
  }

  if (state.original.amenityIds !== selectedAmenityIds().slice().sort().join(",")) {
    payload.amenityIds = selectedAmenityIds();
  }
  return payload;
}

async function handleCreate() {
  const payload = createPayload();
  const response = await createProperty(payload);
  const created = response?.data;
  toast("Draft created.");
  if (created?.id) {
    window.location.assign(
      `/dashboard/admin/property-edit.html?id=${encodeURIComponent(created.id)}&created=1`
    );
  } else {
    window.location.assign("/dashboard/admin/properties.html");
  }
}

async function handleEdit() {
  const full = refs.replaceAll?.checked === true;
  const payload = editPayload(full);
  if (!full && Object.keys(payload).length === 0) {
    showFormError("No changes were made. Update a field before saving.");
    return;
  }

  if (full) {
    await putProperty(state.propertyId, payload);
  } else {
    await patchProperty(state.propertyId, payload);
  }
  toast("Property details saved.");
  await loadProperty();
  clearFormError();
}

async function handleSubmit(event) {
  event.preventDefault();
  if (state.saving) {
    return;
  }
  if (!validateForm()) {
    return;
  }

  setBusy(true);
  clearFormError();
  try {
    if (isCreate) {
      await handleCreate();
      return;
    }
    await handleEdit();
  } catch (error) {
    if (handleApiError(error, { onForbidden: showAccessDenied })) {
      return;
    }
    if (error instanceof ApiError && error.status === 422) {
      showFormError(
        "The property does not meet publication requirements. Check assignment, images, and category-specific details."
      );
    } else {
      applyFieldErrors(error);
    }
  } finally {
    setBusy(false);
  }
}

function wireEvents() {
  if (refs.form) {
    refs.form.addEventListener("submit", handleSubmit);
  }
  if (refs.category) {
    refs.category.addEventListener("change", () => loadSubtypes(refs.category.value));
  }
  window.addEventListener("beforeunload", (event) => {
    if (!state.saving && !isCreate && isDirty()) {
      event.preventDefault();
      event.returnValue = "";
    }
  });
}

async function start() {
  const allowed = isCreate ? [ROLES.ADMIN] : [ROLES.ADMIN, ROLES.AGENT];
  const user = await requireManagementRole(allowed);
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
    title: isCreate ? "Create Property" : "Edit Property"
  });
  wireEvents();

  if (isCreate) {
    await loadCategories();
    await loadAmenities();
    showCategoryFieldSets();
    state.original = snapshot();
    return;
  }

  state.propertyId = new URLSearchParams(window.location.search).get("id") || "";
  if (!state.propertyId) {
    showFormError("A property identifier is required. Open this page from the property list.");
    if (refs.submit) {
      refs.submit.disabled = true;
    }
    return;
  }

  await Promise.all([loadProperty(), loadAmenities()]);
  state.original = snapshot();
}

start().catch(() => {
  showFormError("We could not initialize this page. Reload and try again.");
});
