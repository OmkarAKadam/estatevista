/**
 * Buyer registration page behaviour.
 *
 * Registration uses the exact backend DTO: firstName, lastName, email,
 * password, and optional phone. The confirmation field is client-only and is
 * never sent. The backend remains authoritative for every validation rule.
 */

import { ApiError } from "../core/api.js";
import { initializeSession, register } from "./auth-service.js";
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
const encoder = new TextEncoder();

const form = document.getElementById("registerForm");
const firstNameInput = document.getElementById("registerFirstName");
const lastNameInput = document.getElementById("registerLastName");
const emailInput = document.getElementById("registerEmail");
const phoneInput = document.getElementById("registerPhone");
const passwordInput = document.getElementById("registerPassword");
const confirmPasswordInput = document.getElementById("registerConfirmPassword");
const submitButton = document.getElementById("registerSubmit");
const alertElement = document.getElementById("registerAlert");
const successPanel = document.getElementById("registerSuccess");
const loginLink = document.getElementById("registerLoginLink");

const params = new URLSearchParams(window.location.search);
const nextTarget = params.get("next");

let busy = false;

if (loginLink) {
  const loginUrl = new URL("login.html", window.location.href);
  loginUrl.searchParams.set("registered", "1");
  if (nextTarget) {
    loginUrl.searchParams.set("next", nextTarget);
  }
  loginLink.href = loginUrl.href;
}

initializeSession().then((state) => {
  if (state.status === "authenticated") {
    window.location.replace(resolveSameOriginTarget(nextTarget).href);
  }
});

function passwordCodePoints(value) {
  return Array.from(value).length;
}

function validate() {
  let valid = true;

  const firstName = firstNameInput.value.trim();
  if (!firstName) {
    setFieldError(firstNameInput, "Enter your first name.");
    valid = false;
  } else if (firstName.length > 100) {
    setFieldError(firstNameInput, "First name must be 100 characters or fewer.");
    valid = false;
  }

  const lastName = lastNameInput.value.trim();
  if (!lastName) {
    setFieldError(lastNameInput, "Enter your last name.");
    valid = false;
  } else if (lastName.length > 100) {
    setFieldError(lastNameInput, "Last name must be 100 characters or fewer.");
    valid = false;
  }

  const email = emailInput.value.trim();
  if (!email) {
    setFieldError(emailInput, "Enter your email address.");
    valid = false;
  } else if (email.length > 255 || !EMAIL_PATTERN.test(email)) {
    setFieldError(emailInput, "Enter a valid email address.");
    valid = false;
  }

  const phone = phoneInput.value.trim();
  if (phone.length > 20) {
    setFieldError(phoneInput, "Phone number must be 20 characters or fewer.");
    valid = false;
  }

  const password = passwordInput.value;
  if (!password) {
    setFieldError(passwordInput, "Choose a password.");
    valid = false;
  } else if (password.includes("\u0000")) {
    setFieldError(passwordInput, "Password contains an unsupported character.");
    valid = false;
  } else {
    const length = passwordCodePoints(password);
    if (length < 10 || length > 64) {
      setFieldError(passwordInput, "Password must be between 10 and 64 characters.");
      valid = false;
    } else if (encoder.encode(password).length > 72) {
      setFieldError(passwordInput, "Password is too long. Use at most 72 bytes.");
      valid = false;
    } else {
      const lower = password.toLowerCase();
      const localPart = email.includes("@") ? email.split("@")[0].toLowerCase() : "";
      const contextWords = [
        localPart,
        firstName.toLowerCase(),
        lastName.toLowerCase(),
        "estatevista"
      ].filter(Boolean);

      if (contextWords.includes(lower)) {
        setFieldError(
          passwordInput,
          "Choose a password that does not match your name, email address, or the site name."
        );
        valid = false;
      }
    }
  }

  if (!confirmPasswordInput.value) {
    setFieldError(confirmPasswordInput, "Confirm your password.");
    valid = false;
  } else if (confirmPasswordInput.value !== password) {
    setFieldError(confirmPasswordInput, "Passwords do not match.");
    valid = false;
  }

  return valid;
}

function registrationErrorMessage(error) {
  if (!(error instanceof ApiError)) {
    return "We could not create your account. Please try again.";
  }

  switch (error.status) {
    case 0:
      return "We could not reach EstateVista. Check your connection and try again.";
    case 403:
      return "We could not verify this request. Please refresh the page and try again.";
    case 409:
      return "An account with that email address already exists.";
    case 429:
      return "Too many registration attempts. Please wait a minute and try again.";
    default:
      if (error.status >= 500) {
        return "Something went wrong on our side. Please try again.";
      }
      return "We could not create your account. Please check your details and try again.";
  }
}

function showSuccess() {
  passwordInput.value = "";
  confirmPasswordInput.value = "";
  hideAlert(alertElement);

  if (form) {
    form.hidden = true;
  }
  if (successPanel) {
    successPanel.hidden = false;
    successPanel.focus();
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
  setFormBusy(form, submitButton, true, "Creating account…");

  try {
    await register({
      firstName: firstNameInput.value.trim(),
      lastName: lastNameInput.value.trim(),
      email: emailInput.value.trim(),
      password: passwordInput.value,
      phone: phoneInput.value.trim()
    });

    showSuccess();
  } catch (error) {
    if (error instanceof ApiError && error.status === 409) {
      setFieldError(
        emailInput,
        "An account with that email address already exists."
      );
    }

    const matched = applyApiFieldErrors(form, error);
    if (matched) {
      showAlert(alertElement, "Please correct the highlighted fields.", "error");
      focusFirstInvalid(form);
    } else {
      showAlert(alertElement, registrationErrorMessage(error), "error");
      alertElement.focus();
    }
  } finally {
    busy = false;
    setFormBusy(form, submitButton, false);
  }
}

if (
  form &&
  firstNameInput &&
  lastNameInput &&
  emailInput &&
  phoneInput &&
  passwordInput &&
  confirmPasswordInput &&
  submitButton &&
  alertElement
) {
  form.addEventListener("submit", handleSubmit);
}
