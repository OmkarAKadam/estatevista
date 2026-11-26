/**
 * Inquiry status presentation rules.
 *
 * This is a pure view model. The backend is the authority for every transition;
 * these rules only decide which controls may be offered and how a status is
 * labelled. They mirror Decision 1 of docs/STAGE_6_DECISION_REGISTER.md.
 */

const TRANSITIONS = Object.freeze({
  NEW: Object.freeze(["IN_PROGRESS", "CLOSED"]),
  IN_PROGRESS: Object.freeze(["RESOLVED", "CLOSED"]),
  RESOLVED: Object.freeze(["CLOSED"]),
  CLOSED: Object.freeze([])
});

const LABELS = Object.freeze({
  NEW: "New",
  IN_PROGRESS: "In progress",
  RESOLVED: "Resolved",
  CLOSED: "Closed"
});

const ACTIONS = Object.freeze({
  IN_PROGRESS: "Start processing",
  RESOLVED: "Mark as resolved",
  CLOSED: "Close inquiry"
});

export const INQUIRY_STATUSES = Object.freeze(Object.keys(TRANSITIONS));

export function isKnownStatus(status) {
  return typeof status === "string" && Object.hasOwn(TRANSITIONS, status);
}

export function allowedTransitions(status) {
  return isKnownStatus(status) ? TRANSITIONS[status] : Object.freeze([]);
}

export function statusLabel(status) {
  return isKnownStatus(status) ? LABELS[status] : "Status unavailable";
}

export function actionLabel(target) {
  return Object.hasOwn(ACTIONS, target) ? ACTIONS[target] : "";
}

export function isTransitionAllowed(status, target) {
  return allowedTransitions(status).includes(target);
}

export function requiresClosureConfirmation(target) {
  return target === "CLOSED";
}
