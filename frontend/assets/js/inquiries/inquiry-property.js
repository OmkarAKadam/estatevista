/**
 * Property-specific buyer inquiry workflow.
 *
 * The public details page stays a classic script; this module attaches the
 * approved inquiry form after the property has rendered. The shared service
 * owns every private request and never exposes inquiry content in a URL,
 * browser storage, a log or an error toast.
 */

import inquiryService from "./inquiry-service.js";
import { show } from "./inquiry-view.js";
import { initializeSession } from "../auth/auth-service.js";
import {
  getAuthRevision,
  subscribe as subscribeAuth
} from "../auth/auth-state.js";
import { resolveSameOriginTarget } from "../auth/auth-ui.js";

const refs = {
  section: document.getElementById("inquirySection"),
  heading: document.getElementById("inquiryHeading"),
  title: document.getElementById("inquiryPropertyTitle"),
  prompt: document.getElementById("inquiryPrompt"),
  promptText: document.getElementById("inquiryPromptText"),
  loginLink: document.getElementById("inquiryLoginLink"),
  form: document.getElementById("inquiryForm"),
  subject: document.getElementById("inquirySubject"),
  subjectError: document.getElementById("inquirySubjectError"),
  message: document.getElementById("inquiryMessage"),
  messageError: document.getElementById("inquiryMessageError"),
  feedback: document.getElementById("inquiryFeedback"),
  success: document.getElementById("inquirySuccess"),
  historyLink: document.getElementById("inquiryHistoryLink")
};

const state = {
  propertyId: null,
  active: false,
  revision: getAuthRevision(),
  sequence: 0
};

function setText(element, value) {
  if (element) {
    element.textContent = String(value ?? "");
  }
}

function clearFeedback() {
  setText(refs.feedback, "");
  show(refs.feedback, false);
  setText(refs.subjectError, "");
  show(refs.subjectError, false);
  setText(refs.messageError, "");
  show(refs.messageError, false);
}

function formatDuration(ms) {
  const seconds = Math.max(1, Math.ceil(ms / 1000));
  return seconds >= 60
    ? `${Math.ceil(seconds / 60)} minute${seconds < 120 ? "" : "s"}`
    : `${seconds} second${seconds === 1 ? "" : "s"}`;
}

function hideAll() {
  show(refs.prompt, false);
  show(refs.form, false);
  show(refs.success, false);
  clearFeedback();
  updateSubmitState();
}

function setPrompt(title, message, withLogin) {
  hideAll();
  setText(refs.promptText, message);
  if (refs.loginLink) {
    const login = resolveSameOriginTarget("/login.html");
    login.searchParams.set("next", `${window.location.pathname}${window.location.search}`);
    refs.loginLink.href = login.href;
    show(refs.loginLink, Boolean(withLogin));
  }
  show(refs.prompt, true);
  if (title && refs.heading) {
    refs.heading.textContent = title;
  }
}

function showForm() {
  hideAll();
  show(refs.form, true);
}

async function resolveSession() {
  const sequence = ++state.sequence;
  const stateView = await initializeSession();
  if (sequence !== state.sequence || !state.active) {
    return;
  }

  const authenticated =
    stateView.status === "authenticated" &&
    stateView.user &&
    (!stateView.user.accountStatus || stateView.user.accountStatus === "ACTIVE");

  if (!authenticated) {
    setPrompt(
      "Sign in to enquire",
      stateView.status === "authenticated"
        ? "This account cannot submit an inquiry."
        : "Inquiries are private to buyer accounts. Sign in to contact the agent about this property.",
      stateView.status !== "authenticated"
    );
    return;
  }
  if (stateView.user.role !== "BUYER") {
    setPrompt(
      "Inquiry is a buyer action",
      "Agent and administrator accounts do not submit inquiries from property pages.",
      false
    );
    return;
  }
  state.revision = getAuthRevision();
  showForm();
}

function fieldError(element, message) {
  if (!element) {
    return;
  }
  element.textContent = message;
  element.hidden = false;
}

async function submit(event) {
  event.preventDefault();
  if (!state.active || !state.propertyId || !refs.form) {
    return;
  }
  clearFeedback();

  const validation = inquiryService.validateSubmission({
    subject: refs.subject ? refs.subject.value : "",
    message: refs.message ? refs.message.value : ""
  });
  if (!validation.ok) {
    const target = validation.field === "subject" ? refs.subjectError : refs.messageError;
    fieldError(
      target,
      validation.detail === "required"
        ? "Please enter a message."
        : validation.field === "subject"
          ? "Please use 200 characters or fewer for the subject."
          : "Please use 5,000 characters or fewer for the message."
    );
    const input = validation.field === "subject" ? refs.subject : refs.message;
    input?.focus();
    return;
  }

  const result = await inquiryService.submitInquiry(state.propertyId, validation.body);
  if (!state.active) {
    return;
  }

  if (result.ok) {
    if (refs.subject) {
      refs.subject.value = "";
    }
    if (refs.message) {
      refs.message.value = "";
    }
    show(refs.form, false);
    clearFeedback();
    show(refs.success, true);
    return;
  }

  if (result.reason === "auth" || result.reason === "auth_changed") {
    setPrompt(
      "Your session has ended",
      "Sign in again before sending an inquiry.",
      true
    );
    return;
  }
  if (result.reason === "validation") {
    fieldError(
      refs.messageError,
      "Please check your message and try again."
    );
    return;
  }
  if (result.reason === "conflict") {
    setText(
      refs.feedback,
      "You already have an active inquiry for this property. View your inquiry history instead."
    );
    const history = document.createElement("a");
    history.href = resolveSameOriginTarget("/dashboard/buyer/inquiries.html").href;
    history.textContent = "View my inquiries";
    refs.feedback.append(" ", history);
    show(refs.feedback, true);
    return;
  }
  if (result.reason === "unavailable") {
    setText(
      refs.feedback,
      "This property is no longer accepting inquiries."
    );
    show(refs.feedback, true);
    return;
  }
  if (result.reason === "rate_limit") {
    const remaining = inquiryService.getCooldown().remainingMs();
    setText(
      refs.feedback,
      `Too many requests. Please wait ${formatDuration(remaining)} before trying again.`
    );
    show(refs.feedback, true);
    updateSubmitState();
    return;
  }
  if (result.reason === "duplicate_submission") {
    setText(refs.feedback, "Your inquiry is still being sent. Please wait.");
    show(refs.feedback, true);
    return;
  }

  setText(
    refs.feedback,
    "We could not confirm whether your inquiry was sent. Check your inquiry history before trying again."
  );
  show(refs.feedback, true);
}

function updateSubmitState() {
  const submitButton = refs.form?.querySelector("button[type='submit']");
  if (!submitButton) {
    return;
  }
  const view = inquiryService.getView();
  const pending = state.propertyId
    ? view.pendingSubmissions.includes(state.propertyId)
    : false;
  submitButton.disabled = pending || view.cooldownActive;
  submitButton.textContent = pending
    ? "Sending…"
    : view.cooldownActive
      ? "Please wait"
      : "Send inquiry";
}

function clearPrivateState() {
  state.active = false;
  state.propertyId = null;
  state.sequence += 1;
  if (refs.subject) {
    refs.subject.value = "";
  }
  if (refs.message) {
    refs.message.value = "";
  }
  hideAll();
  show(refs.section, false);
  inquiryService.reset();
}

/** Called by property-details.js whenever the displayed property changes. */
function setActiveProperty(id, property) {
  if (typeof id !== "string" || id.length === 0 || !refs.section) {
    clearPrivateState();
    return;
  }
  state.active = true;
  state.propertyId = id;
  setText(
    refs.title,
    property && typeof property.title === "string" && property.title.trim()
      ? `Regarding ${property.title}`
      : "Send an inquiry about this property"
  );
  show(refs.section, true);
  void resolveSession();
}

document.querySelector("[data-inquiry-focus]")?.addEventListener("click", () => {
  if (!refs.section || refs.section.hidden) {
    return;
  }
  const reduced = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
  refs.section.scrollIntoView({ behavior: reduced ? "auto" : "smooth", block: "start" });
  const target = !refs.form?.hidden && refs.message ? refs.message : refs.loginLink;
  target?.focus();
});

if (refs.form) {
  refs.form.addEventListener("submit", (event) => {
    void submit(event);
  });
  inquiryService.subscribe(updateSubmitState);
}

subscribeAuth(() => {
  const current = getAuthRevision();
  const previous = state.revision;
  state.revision = current;
  if (previous === current || !state.active || !state.propertyId) {
    return;
  }
  const propertyId = state.propertyId;
  clearPrivateState();
  setActiveProperty(propertyId, null);
});

window.addEventListener("pagehide", clearPrivateState);
window.addEventListener("pageshow", (event) => {
  if (event.persisted && state.propertyId) {
    const id = state.propertyId;
    clearPrivateState();
    setActiveProperty(id, null);
  }
});

window.EVInquiryProperty = Object.freeze({ setActiveProperty });
window.dispatchEvent(new CustomEvent("ev-inquiry-property-ready"));
