/**
 * EstateVista — public property API bridge.
 *
 * The public pages are classic-script pages, while the shared REST client is an
 * ES module. This bridge imports that client once and exposes a small,
 * read-only surface on window for the listing and detail pages. It never
 * renders raw server text and never mutates application state.
 *
 * Search parameters use the exact approved names from API §7.1. Optional empty
 * values are omitted, amenity IDs are sent as repeated `amenityIds` parameters,
 * and the backend `meta` object is preserved as the pagination source of truth.
 */
import {
  apiRequest,
  describeApiFailure,
  parseRetryAfter,
  createRateLimitCooldown,
  getPublicReadCooldown,
  isCancellation,
  isOfflineHint,
  isTimeout
} from "../core/api.js";

/* Backend UUID columns accept any canonical 8-4-4-4-12 hexadecimal value,
   including seeded catalogue IDs with a zero version/variant nibble. */
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const SORTS = new Set(["newest", "oldest", "price_asc", "price_desc"]);
const LISTING_TYPES = new Set(["SALE", "RENT"]);
const MAX_KEYWORD_LENGTH = 100;
const MAX_AMENITY_IDS = 20;
const MAX_PAGE_SIZE = 100;
const MAX_BEDROOMS = 32767;

function invalid(message) {
  const error = new Error(message);
  error.name = "InvalidDiscoveryQueryError";
  return error;
}

function requireUuid(value, label = "identifier") {
  const id = typeof value === "string" ? value.trim() : "";
  if (!UUID_PATTERN.test(id)) {
    throw invalid(`Invalid ${label}`);
  }
  return id;
}

function isValidUuid(value) {
  return typeof value === "string" && UUID_PATTERN.test(value.trim());
}

function trimmed(value) {
  return typeof value === "string" ? value.trim() : "";
}

function nonnegativeInteger(value, label, maximum) {
  if (value === null || value === undefined || value === "") {
    return null;
  }
  const number = Number(value);
  if (!Number.isInteger(number) || number < 0 || (maximum !== undefined && number > maximum)) {
    throw invalid(`Invalid ${label}`);
  }
  return number;
}

function nonnegativeDecimal(value, label) {
  if (value === null || value === undefined || value === "") {
    return null;
  }
  const number = Number(value);
  if (!Number.isFinite(number) || number < 0) {
    throw invalid(`Invalid ${label}`);
  }
  return number;
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

function normalizeMeta(meta) {
  if (!meta || typeof meta !== "object") {
    return null;
  }
  return {
    page: Number.isInteger(meta.page) && meta.page >= 0 ? meta.page : 0,
    size: Number.isInteger(meta.size) && meta.size > 0 ? meta.size : 0,
    totalElements: Number.isInteger(meta.totalElements) && meta.totalElements >= 0 ? meta.totalElements : 0,
    totalPages: Number.isInteger(meta.totalPages) && meta.totalPages >= 0 ? meta.totalPages : 0,
    hasNext: meta.hasNext === true,
    hasPrevious: meta.hasPrevious === true
  };
}

/**
 * Perform a fully server-side public property search.
 *
 * @param {object} criteria approved query values from listings page state
 * @param {object} options supports `signal` for superseded-request cancellation
 * @returns {Promise<{items: object[], meta: object|null}>}
 */
async function listPublishedProperties(criteria = {}, options = {}) {
  const query = new URLSearchParams();
  const page = nonnegativeInteger(criteria.page ?? 0, "page");
  const size = nonnegativeInteger(criteria.size ?? 12, "size", MAX_PAGE_SIZE);
  const sort = trimmed(criteria.sort) || "newest";

  if (size === null || size < 1 || size > MAX_PAGE_SIZE) {
    throw invalid("Invalid size");
  }
  if (!SORTS.has(sort)) {
    throw invalid("Invalid sort");
  }

  query.set("page", String(page));
  query.set("size", String(size));
  query.set("sort", sort);

  const keyword = trimmed(criteria.keyword);
  if (keyword.length > MAX_KEYWORD_LENGTH) {
    throw invalid("Keyword is too long");
  }
  if (keyword) {
    query.set("keyword", keyword);
  }

  ["categoryId", "subtypeId"].forEach((field) => {
    if (criteria[field]) {
      query.set(field, requireUuid(criteria[field], field));
    }
  });

  const listingType = trimmed(criteria.listingType).toUpperCase();
  if (listingType) {
    if (!LISTING_TYPES.has(listingType)) {
      throw invalid("Invalid listingType");
    }
    query.set("listingType", listingType);
  }

  const minPrice = nonnegativeDecimal(criteria.minPrice, "minPrice");
  const maxPrice = nonnegativeDecimal(criteria.maxPrice, "maxPrice");
  if (minPrice !== null && maxPrice !== null && minPrice > maxPrice) {
    throw invalid("Invalid price range");
  }
  if (minPrice !== null) {
    query.set("minPrice", String(minPrice));
  }
  if (maxPrice !== null) {
    query.set("maxPrice", String(maxPrice));
  }

  ["city", "locality"].forEach((field) => {
    const value = trimmed(criteria[field]);
    if (value) {
      query.set(field, value);
    }
  });

  const minBedrooms = nonnegativeInteger(criteria.minBedrooms, "minBedrooms", MAX_BEDROOMS);
  const maxBedrooms = nonnegativeInteger(criteria.maxBedrooms, "maxBedrooms", MAX_BEDROOMS);
  if (minBedrooms !== null && maxBedrooms !== null && minBedrooms > maxBedrooms) {
    throw invalid("Invalid bedroom range");
  }
  if (minBedrooms !== null) {
    query.set("minBedrooms", String(minBedrooms));
  }
  if (maxBedrooms !== null) {
    query.set("maxBedrooms", String(maxBedrooms));
  }

  const amenities = Array.isArray(criteria.amenityIds) ? criteria.amenityIds : [];
  if (amenities.length > MAX_AMENITY_IDS) {
    throw invalid("Too many amenityIds");
  }
  const seenAmenities = new Set();
  amenities.forEach((candidate) => {
    const id = requireUuid(candidate, "amenityIds");
    if (!seenAmenities.has(id)) {
      seenAmenities.add(id);
      query.append("amenityIds", id);
    }
  });

  const payload = await apiRequest(`/properties?${query.toString()}`, {
    signal: options.signal,
    publicRead: true
  });
  return {
    items: asArray(unwrap(payload)),
    meta: normalizeMeta(payload.meta)
  };
}

async function getPublishedDetail(id, options = {}) {
  const property = unwrap(
    await apiRequest(`/properties/${encodeURIComponent(requireUuid(id, "property identifier"))}`, {
      signal: options.signal,
      publicRead: true
    })
  );
  if (!property || typeof property !== "object") {
    const error = new Error("Missing property detail");
    error.name = "InvalidApiResponseError";
    throw error;
  }
  return property;
}

async function listPublishedImages(id, options = {}) {
  return asArray(
    unwrap(
      await apiRequest(`/properties/${encodeURIComponent(requireUuid(id, "property identifier"))}/images`, {
        signal: options.signal,
        publicRead: true
      })
    )
  );
}

async function getRelatedProperties(id, { size = 3, signal } = {}) {
  const query = new URLSearchParams({ page: "0", size: String(size) });
  return asArray(
    unwrap(
      await apiRequest(
        `/properties/${encodeURIComponent(requireUuid(id, "property identifier"))}/related?${query.toString()}`,
        { signal, publicRead: true }
      )
    )
  );
}

/* Reference catalogues are public and read-only. The listings page fetches
   each one once per page load; callers may pass a signal for cancellation. */
async function listPropertyCategories(options = {}) {
  return asArray(
    unwrap(await apiRequest("/property-categories", { signal: options.signal, publicRead: true }))
  );
}

async function listPropertySubtypes(options = {}) {
  return asArray(
    unwrap(await apiRequest("/property-subtypes", { signal: options.signal, publicRead: true }))
  );
}

async function listAmenities(options = {}) {
  return asArray(unwrap(await apiRequest("/amenities", { signal: options.signal, publicRead: true })));
}

window.EVPublicPropertyApi = {
  UUID_PATTERN,
  isValidUuid,
  listPublishedProperties,
  getPublishedDetail,
  listPublishedImages,
  getRelatedProperties,
  listPropertyCategories,
  listPropertySubtypes,
  listAmenities,
  describeApiFailure,
  parseRetryAfter,
  createRateLimitCooldown,
  getPublicReadCooldown,
  isCancellation,
  isOfflineHint,
  isTimeout
};

window.dispatchEvent(new CustomEvent("ev-public-property-api-ready"));
