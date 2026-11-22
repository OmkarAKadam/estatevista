/**
 * Stage 4.7 property-management vocabulary and limits.
 *
 * Backend enum values are used verbatim on the wire. Display labels and badge
 * modifiers live here so the V1 public-demo vocabulary is never reused.
 */

export const ROLES = Object.freeze({
  ADMIN: "ADMIN",
  AGENT: "AGENT",
  BUYER: "BUYER"
});

export const STATUSES = Object.freeze([
  "DRAFT",
  "PUBLISHED",
  "UNAVAILABLE",
  "SOLD",
  "RENTED",
  "ARCHIVED"
]);

const STATUS_META = Object.freeze({
  DRAFT: { label: "Draft", modifier: "ev-badge--neutral" },
  PUBLISHED: { label: "Published", modifier: "ev-badge--available" },
  UNAVAILABLE: { label: "Unavailable", modifier: "ev-badge--reserved" },
  SOLD: { label: "Sold", modifier: "ev-badge--sold" },
  RENTED: { label: "Rented", modifier: "ev-badge--forest" },
  ARCHIVED: { label: "Archived", modifier: "ev-badge--neutral" }
});

export function statusLabel(status) {
  const meta = STATUS_META[status];
  return meta ? meta.label : String(status || "Unknown");
}

export function statusBadgeClass(status) {
  const meta = STATUS_META[status];
  return meta ? meta.modifier : "";
}

/**
 * Authoritative Stage 4.6 transition matrix. `ARCHIVED` is never a PATCH
 * target; reopening a completed listing is an ADMIN-only action.
 */
const TRANSITIONS = Object.freeze({
  DRAFT: Object.freeze(["PUBLISHED"]),
  PUBLISHED: Object.freeze(["UNAVAILABLE", "SOLD", "RENTED"]),
  UNAVAILABLE: Object.freeze(["PUBLISHED"]),
  SOLD: Object.freeze(["PUBLISHED"]),
  RENTED: Object.freeze(["PUBLISHED"]),
  ARCHIVED: Object.freeze([])
});

const TRANSITION_LABELS = Object.freeze({
  PUBLISHED: "Publish",
  UNAVAILABLE: "Mark unavailable",
  SOLD: "Record sale",
  RENTED: "Record rental"
});

/**
 * Return only the transitions the current user may be offered. The backend
 * re-checks every transition from the session principal.
 */
export function permittedTransitions(property, role) {
  if (!property || typeof property !== "object") {
    return [];
  }

  const from = property.status;
  const targets = TRANSITIONS[from] || [];
  const listingType = property.listingType;

  return targets.filter((target) => {
    if (target === "SOLD" && listingType !== "SALE") {
      return false;
    }
    if (target === "RENTED" && listingType !== "RENT") {
      return false;
    }
    if (
      (from === "SOLD" || from === "RENTED") &&
      target === "PUBLISHED" &&
      role !== ROLES.ADMIN
    ) {
      return false;
    }
    return true;
  });
}

export function transitionLabel(target) {
  return TRANSITION_LABELS[target] || String(target);
}

export function isConsequentialTransition(from, target) {
  return (
    target === "SOLD" ||
    target === "RENTED" ||
    (from === "SOLD" || from === "RENTED") && target === "PUBLISHED"
  );
}

export const IMAGE_LIMITS = Object.freeze({
  MAX_IMAGES: 10,
  MAX_BYTES: 5 * 1024 * 1024,
  MIN_DIMENSION: 200,
  MAX_DIMENSION: 6000,
  MAX_PIXELS: 40000000,
  ACCEPTED_TYPES: Object.freeze(["image/jpeg", "image/png", "image/webp"]),
  ACCEPTED_EXTENSIONS: Object.freeze(["jpg", "jpeg", "png", "webp"])
});

export const FORM_LIMITS = Object.freeze({
  TITLE_MIN: 10,
  TITLE_MAX: 120,
  DESCRIPTION_MIN: 40,
  DESCRIPTION_MAX: 4000,
  CURRENCY: "INR",
  AREA_UNIT: "SQ_FT",
  MAX_AMENITIES: 20,
  COUNTRY: "India"
});

export const MANAGEMENT_PAGE_SIZE = 20;

export const CATEGORY_FIELD_SETS = Object.freeze({
  RESIDENTIAL: Object.freeze([
    "bedrooms",
    "bathrooms",
    "balconies",
    "floorNumber",
    "totalFloors",
    "parkingSpaces",
    "furnishingStatus"
  ]),
  COMMERCIAL: Object.freeze([
    "parkingSpaces",
    "floorNumber",
    "totalFloors",
    "washrooms",
    "powerBackup",
    "suitableFor"
  ])
});

export function categoryFieldSet(categoryName) {
  return CATEGORY_FIELD_SETS[categoryName] || [];
}

/** Backend category rows are named "Residential" / "Commercial" by seed data. */
export function categoryKind(categoryName) {
  const name = String(categoryName ?? "").toLowerCase();
  if (name.includes("commercial")) {
    return "COMMERCIAL";
  }
  if (name.includes("residential")) {
    return "RESIDENTIAL";
  }
  return "";
}

export function listingTypeLabel(value) {
  if (value === "SALE") {
    return "For sale";
  }
  if (value === "RENT") {
    return "For rent";
  }
  return "—";
}

export const AREA_UNITS = Object.freeze(["SQ_FT", "SQ_M", "ACRE"]);

export function areaUnitLabel(value) {
  const labels = Object.freeze({
    SQ_FT: "sq ft",
    SQ_M: "sq m",
    ACRE: "acre"
  });
  return labels[value] || String(value || "");
}

export const STATUS_FILTERS = Object.freeze(STATUSES);

