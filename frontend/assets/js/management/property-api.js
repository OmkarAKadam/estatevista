/**
 * Stage 4.7 API adapter.
 *
 * Every call goes through the shared `core/api.js` client, so session cookies,
 * the in-memory server-issued CSRF token, exact-origin CORS behavior, and the
 * standard error envelope are handled in exactly one place. Only approved
 * endpoints are invoked here.
 */

import { apiRequest } from "../core/api.js";

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function requireId(value, label = "identifier") {
  const id = String(value ?? "").trim();
  if (!UUID_PATTERN.test(id)) {
    throw new Error(`A valid ${label} is required.`);
  }
  return encodeURIComponent(id);
}

function buildQuery(params = {}) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === null || value === "") {
      return;
    }
    search.set(key, String(value));
  });
  const text = search.toString();
  return text ? `?${text}` : "";
}

function unwrap(payload) {
  if (!payload || typeof payload !== "object") {
    return { data: null, meta: null, message: null };
  }
  return {
    data: payload.data ?? null,
    meta: payload.meta ?? null,
    message: typeof payload.message === "string" ? payload.message : null
  };
}

/* --------------------------------------------------------------------------
   Management lists and detail
   -------------------------------------------------------------------------- */

export function listAdminProperties(params = {}) {
  return apiRequest(
    `/admin/properties${buildQuery({
      page: params.page ?? 0,
      size: params.size ?? 20,
      status: params.status,
      keyword: params.keyword,
      categoryId: params.categoryId,
      city: params.city,
      primaryAgentId: params.primaryAgentId
    })}`
  ).then(unwrap);
}

export function listAgentProperties(params = {}) {
  return apiRequest(
    `/agents/me/properties${buildQuery({
      page: params.page ?? 0,
      size: params.size ?? 20,
      status: params.status
    })}`
  ).then(unwrap);
}

export function getProperty(propertyId) {
  return apiRequest(`/properties/${requireId(propertyId, "property identifier")}`).then(unwrap);
}

/* --------------------------------------------------------------------------
   Property writes
   -------------------------------------------------------------------------- */

export function createProperty(payload) {
  return apiRequest("/properties", {
    method: "POST",
    body: JSON.stringify(payload)
  }).then(unwrap);
}

export function patchProperty(propertyId, payload) {
  return apiRequest(`/properties/${requireId(propertyId, "property identifier")}`, {
    method: "PATCH",
    body: JSON.stringify(payload)
  }).then(unwrap);
}

export function putProperty(propertyId, payload) {
  return apiRequest(`/properties/${requireId(propertyId, "property identifier")}`, {
    method: "PUT",
    body: JSON.stringify(payload)
  }).then(unwrap);
}

export function changePropertyStatus(propertyId, status) {
  return apiRequest(`/properties/${requireId(propertyId, "property identifier")}/status`, {
    method: "PATCH",
    body: JSON.stringify({ status })
  }).then(unwrap);
}

export function archiveProperty(propertyId) {
  return apiRequest(`/properties/${requireId(propertyId, "property identifier")}`, {
    method: "DELETE"
  }).then(unwrap);
}

export function assignProperty(propertyId, primaryAgentId) {
  return apiRequest(
    `/admin/properties/${requireId(propertyId, "property identifier")}/assignment`,
    {
      method: "PATCH",
      body: JSON.stringify({ primaryAgentId: String(primaryAgentId).trim() })
    }
  ).then(unwrap);
}

/* --------------------------------------------------------------------------
   Reference catalogs
   -------------------------------------------------------------------------- */

export function getCategories() {
  return apiRequest("/property-categories").then(unwrap);
}

export function getSubtypes(categoryId) {
  return apiRequest(`/property-subtypes${buildQuery({ categoryId })}`).then(unwrap);
}

export function getAmenities() {
  return apiRequest("/amenities").then(unwrap);
}

/* --------------------------------------------------------------------------
   Images
   -------------------------------------------------------------------------- */

export function listImages(propertyId) {
  return apiRequest(`/properties/${requireId(propertyId, "property identifier")}/images`).then(
    unwrap
  );
}

export function uploadImage(propertyId, file, options = {}) {
  const form = new FormData();
  form.append("file", file);
  if (typeof options.altText === "string" && options.altText.trim().length > 0) {
    form.append("altText", options.altText.trim());
  }
  if (options.isPrimary === true) {
    form.append("isPrimary", "true");
  }
  if (Number.isInteger(options.sortOrder)) {
    form.append("sortOrder", String(options.sortOrder));
  }

  return apiRequest(`/properties/${requireId(propertyId, "property identifier")}/images`, {
    method: "POST",
    body: form
  }).then(unwrap);
}

export function updateImageMetadata(propertyId, imageId, payload) {
  return apiRequest(
    `/properties/${requireId(propertyId, "property identifier")}/images/${requireId(
      imageId,
      "image identifier"
    )}`,
    {
      method: "PATCH",
      body: JSON.stringify(payload)
    }
  ).then(unwrap);
}

export function setPrimaryImage(propertyId, imageId) {
  return apiRequest(
    `/properties/${requireId(propertyId, "property identifier")}/images/${requireId(
      imageId,
      "image identifier"
    )}/primary`,
    { method: "PATCH" }
  ).then(unwrap);
}

export function deleteImage(propertyId, imageId) {
  return apiRequest(
    `/properties/${requireId(propertyId, "property identifier")}/images/${requireId(
      imageId,
      "image identifier"
    )}`,
    { method: "DELETE" }
  ).then(unwrap);
}

export { UUID_PATTERN };
