/**
 * Login page behaviour.
 *
 * The CSRF bootstrap, credentialed request, session rotation, `/auth/me`
 * verification, and token refresh are handled by the shared authentication
 * service. This file owns validation, messaging, and the safe redirect only.
 */

import { ApiError } from "../core/api.js";
import { initializeSession, login } from "./auth-service.js";
import {
  applyApiFieldErrors,
  clearFieldErrors,
  focusFirstInvalid,
  hideAlert,
  resolveSameOriginTarget,
  setFieldError,
  setFormBusy,
  showAlert
} from "./auth-ui.js";

const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

const form = document.getElementById("loginForm");
const emailInput = document.getElementById("loginEmail");
const passwordInput = document.getElementById("loginPassword");
const submitButton = document.getElementById("loginSubmit");
const alertElement = document.getElementById("loginAlert");
const registeredNotice = document.getElementById("registeredNotice");
const sessionNotice = document.getElementById("sessionNotice");

const params = new URLSearchParams(window.location.search);
const nextTarget = params.get("next");
const registerLink = document.getElementById("loginRegisterLink");

let busy = false;

if (registerLink && nextTarget) {
  const registerUrl = new URL(registerLink.href, window.location.href);
  registerUrl.searchParams.set("next", nextTarget);
  registerLink.href = registerUrl.href;
}

if (params.get("registered") === "1" && registeredNotice) {
  showAlert(
    registeredNotice,
    "Your account was created. Log in with your email address and password.",
    "success"
  );
  params.delete("registered");
  const query = params.toString();
  window.history.replaceState(
    null,
    "",
    `${window.location.pathname}${query ? `?${query}` : ""}${window.location.hash}`
  );
}

if (params.get("expired") === "1" && sessionNotice) {
  showAlert(sessionNotice, "Your session expired. Sign in again to continue.", "info");
  params.delete("expired");
  const query = params.toString();
  window.history.replaceState(
    null,
    "",
    `${window.location.pathname}${query ? `?${query}` : ""}${window.location.hash}`
  );
}

initializeSession().then((state) => {
  if (state.status === "authenticated") {
    window.location.replace(resolveSameOriginTarget(nextTarget).href);
  }
});

function validate() {
  let valid = true;

  const email = emailInput.value.trim();
  if (!email) {
    setFieldError(emailInput, "Enter your email address.");
    valid = false;
  } else if (email.length > 255 || !EMAIL_PATTERN.test(email)) {
    setFieldError(emailInput, "Enter a valid email address.");
    valid = false;
  }

  if (!passwordInput.value) {
    setFieldError(passwordInput, "Enter your password.");
    valid = false;
  }

  return valid;
}

function loginErrorMessage(error) {
  if (!(error instanceof ApiError)) {
    return "We could not sign you in. Please try again.";
  }

  switch (error.status) {
    case 0:
      return "We could not reach EstateVista. Check your connection and try again.";
    case 401:
      return "Email or password is incorrect.";
    case 403:
      return "Your sign-in security token expired. Please refresh the page and try again.";
    case 429:
      return "Too many sign-in attempts. Please wait a few minutes and try again.";
    default:
      if (error.status >= 500) {
        return "Something went wrong on our side. Please try again.";
      }
      return "We could not sign you in. Please check your details and try again.";
  }
}

async function handleSubmit(event) {
  event.preventDefault();

  if (busy) {
    return;
  }

  hideAlert(alertElement);
  clearFieldErrors(form);

  if (!validate()) {
    focusFirstInvalid(form);
    return;
  }

  busy = true;
  setFormBusy(form, submitButton, true, "Signing in…");

  try {
    await login({
      email: emailInput.value.trim(),
      password: passwordInput.value
    });

    passwordInput.value = "";
    window.location.assign(resolveSameOriginTarget(nextTarget).href);
  } catch (error) {
    const matched = applyApiFieldErrors(form, error);
    if (matched) {
      showAlert(alertElement, "Please correct the highlighted fields.", "error");
      focusFirstInvalid(form);
    } else {
      showAlert(alertElement, loginErrorMessage(error), "error");
      alertElement.focus();
    }
  } finally {
    busy = false;
    setFormBusy(form, submitButton, false);
  }
}

if (form && emailInput && passwordInput && submitButton && alertElement) {
  form.addEventListener("submit", handleSubmit);
}
