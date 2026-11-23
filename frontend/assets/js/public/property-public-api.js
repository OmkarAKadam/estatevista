/**
 * EstateVista — public property API bridge.
 *
 * The public pages are classic-script pages, while the shared REST client is an
 * ES module. This bridge imports that client once and exposes a small,
 * read-only surface on window for the listing and detail pages. It never
 * renders raw server text and never mutates application state.
 */
import { apiRequest } from "../core/api.js";

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

function requireUuid(value) {
  const id = typeof value === "string" ? value.trim() : "";
  if (!UUID_PATTERN.test(id)) {
    const error = new Error("Invalid property identifier");
    error.name = "InvalidPropertyIdError";
    throw error;
  }
  return id;
}

function unwrap(payload) {
  if (!payload || payload.success !== true) {
    const error = new Error("Unexpected API response");
    error.name = "InvalidApiResponseError";
    throw error;
  }
  return payload.data;
}

function asArray(value) {
  return Array.isArray(value) ? value : [];
}

async function listPublishedProperties({ page = 0, size = 100 } = {}) {
  const query = new URLSearchParams({ page: String(page), size: String(size) });
  return asArray(unwrap(await apiRequest(`/properties?${query.toString()}`)));
}

async function getPublishedDetail(id) {
  const property = unwrap(await apiRequest(`/properties/${encodeURIComponent(requireUuid(id))}`));
  if (!property || typeof property !== "object") {
    const error = new Error("Missing property detail");
    error.name = "InvalidApiResponseError";
    throw error;
  }
  return property;
}

async function listPublishedImages(id) {
  return asArray(unwrap(await apiRequest(`/properties/${encodeURIComponent(requireUuid(id))}/images`)));
}

async function getRelatedProperties(id, { size = 3 } = {}) {
  const query = new URLSearchParams({ page: "0", size: String(size) });
  return asArray(
    unwrap(await apiRequest(`/properties/${encodeURIComponent(requireUuid(id))}/related?${query.toString()}`))
  );
}

window.EVPublicPropertyApi = {
  UUID_PATTERN,
  listPublishedProperties,
  getPublishedDetail,
  listPublishedImages,
  getRelatedProperties
};

window.dispatchEvent(new CustomEvent("ev-public-property-api-ready"));
