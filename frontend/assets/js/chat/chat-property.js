/**
 * Property-detail "Message agent" bridge.
 *
 * This is a REST-only CTA: it creates or retrieves the permanent buyer-property
 * conversation and then navigates to the existing thread page. It never opens
 * a socket, collects message text, mutates property status or stores state.
 */

import {
  getAuthRevision,
  getAuthState,
  subscribe as subscribeAuth
} from "../auth/auth-state.js";
import { describeChatError, isUuid } from "./chat-format.js";
import { createConversation, reset as resetChatService } from "./chat-service.js";

const button = document.querySelector("[data-chat-message-agent]");
const feedback = document.querySelector("[data-chat-property-feedback]");

let propertyId = null;
let activeProperty = null;
let busy = false;
let revision = getAuthRevision();
let sequence = 0;

function loginUrl() {
  const url = new URL("/login.html", window.location.origin);
  url.searchParams.set("next", `${window.location.pathname}${window.location.search}`);
  return url.href;
}

function setFeedback(message, withLogin = false) {
  if (!feedback) {
    return;
  }
  feedback.replaceChildren();
  if (message) {
    feedback.append(document.createTextNode(message));
    if (withLogin) {
      const link = document.createElement("a");
      link.href = loginUrl();
      link.textContent = "Log in";
      feedback.append(document.createTextNode(" "), link);
    }
    feedback.hidden = false;
  } else {
    feedback.replaceChildren();
    feedback.hidden = true;
  }
}

function isPublished() {
  return (
    Boolean(activeProperty) &&
    String(activeProperty.status ?? "").toLowerCase() === "published"
  );
}

function canCreate(stateSnapshot) {
  return Boolean(
    stateSnapshot.status === "authenticated" &&
      stateSnapshot.user &&
      stateSnapshot.user.role === "BUYER" &&
      (!stateSnapshot.user.accountStatus ||
        stateSnapshot.user.accountStatus === "ACTIVE") &&
      isUuid(propertyId) &&
      isPublished()
  );
}

function refreshVisibility() {
  if (!button) {
    return;
  }
  /* P-01: the action exists only for authenticated active BUYERs on a
     PUBLISHED property. Anonymous, other-role and inactive visitors get a
     passive explanation instead of an actionable control. */
  button.hidden = !canCreate(getAuthState());
  button.disabled = busy;
  button.setAttribute("aria-busy", busy ? "true" : "false");
}

function updateEligibilityFeedback() {
  if (!isUuid(propertyId) || !isPublished()) {
    setFeedback(null);
    return;
  }
  const snapshot = getAuthState();
  if (snapshot.status === "unknown") {
    return;
  }
  if (
    snapshot.status === "authenticated" &&
    snapshot.user &&
    snapshot.user.role === "BUYER" &&
    (!snapshot.user.accountStatus || snapshot.user.accountStatus === "ACTIVE")
  ) {
    setFeedback(null);
    return;
  }
  showSignInPrompt(snapshot);
}

function showSignInPrompt(stateSnapshot) {
  if (stateSnapshot.status === "unknown") {
    setFeedback("Checking your session. Please try again in a moment.");
    return;
  }
  if (stateSnapshot.status !== "authenticated") {
    setFeedback("Sign in to message the agent for this property.", true);
    return;
  }
  if (stateSnapshot.user?.role !== "BUYER") {
    setFeedback("Only the buyer for this property can message its agent.");
    return;
  }
  setFeedback("This account cannot start a conversation for this property.");
}

async function handleClick() {
  if (busy) {
    return;
  }
  const snapshot = getAuthState();
  if (!canCreate(snapshot)) {
    showSignInPrompt(snapshot);
    return;
  }

  busy = true;
  const currentSequence = ++sequence;
  refreshVisibility();
  setFeedback(null);
  button?.setAttribute("aria-busy", "true");

  const result = await createConversation(propertyId);
  if (currentSequence !== sequence || !busy) {
    return;
  }
  busy = false;
  refreshVisibility();
  button?.setAttribute("aria-busy", "false");

  if (!result.ok) {
    if (result.reason === "auth") {
      setFeedback("Your session has ended. Sign in again to message the agent.", true);
    } else if (result.reason === "not_found") {
      setFeedback("A conversation is not available for this property.");
    } else if (result.reason === "inactive") {
      setFeedback("This property's original agent is no longer active, so messaging is unavailable.");
    } else {
      setFeedback(describeChatError(result.reason));
    }
    return;
  }

  const target = new URL(
    `/dashboard/buyer/conversation.html?id=${encodeURIComponent(result.data.conversationId)}`,
    window.location.origin
  );
  window.location.assign(target.href);
}

export function setActiveProperty(nextId, property) {
  const changed =
    propertyId !== (nextId || null) || activeProperty !== (property || null);
  propertyId = nextId || null;
  activeProperty = property || null;
  sequence += 1;
  busy = false;
  if (changed) {
    resetChatService();
    setFeedback(null);
  }
  refreshVisibility();
  updateEligibilityFeedback();
}

function handleAuth() {
  const current = getAuthRevision();
  if (current !== revision) {
    revision = current;
    sequence += 1;
    busy = false;
    resetChatService();
  }
  refreshVisibility();
  updateEligibilityFeedback();
}

const unsubscribeAuth = subscribeAuth(handleAuth);

function handlePageHide() {
  sequence += 1;
  busy = false;
  resetChatService();
}

window.addEventListener("pagehide", handlePageHide);
button?.addEventListener("click", () => void handleClick());

const pending = window.EVPropertyChatPending;
if (pending) {
  setActiveProperty(pending.propertyId, pending.property);
}
refreshVisibility();
updateEligibilityFeedback();

window.EVPropertyChat = Object.freeze({ setActiveProperty });
window.dispatchEvent(new CustomEvent("ev-property-chat-ready"));

export const teardown = () => {
  unsubscribeAuth();
  window.removeEventListener("pagehide", handlePageHide);
  handlePageHide();
};
