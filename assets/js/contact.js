/**
 * EstateVista — contact form behaviour (Stage 4).
 *
 * Client-side validation only. No fetch, no form action, no storage: a valid
 * submission shows a demonstration notice, resets the form and stops there.
 */
(function () {
  "use strict";

  var form = document.getElementById("contactForm");

  if (!form) {
    return;
  }

  var status = document.getElementById("formStatus");
  var alert = document.getElementById("formAlert");
  var submit = document.getElementById("contactSubmit");
  var submitLabel = submit ? submit.textContent : "Send message";
  var isSubmitting = false;

  var SUBJECTS = {
    general: true,
    viewing: true,
    buying: true,
    selling: true,
    other: true
  };

  var fields = {
    name: {
      input: document.getElementById("contactName"),
      error: document.getElementById("contactNameError")
    },
    email: {
      input: document.getElementById("contactEmail"),
      error: document.getElementById("contactEmailError")
    },
    subject: {
      input: document.getElementById("contactSubject"),
      error: document.getElementById("contactSubjectError")
    },
    message: {
      input: document.getElementById("contactMessage"),
      error: document.getElementById("contactMessageError")
    }
  };

  function clean(value) {
    return typeof value === "string" ? value.replace(/\s+/g, " ").trim() : "";
  }

  function setError(field, message) {
    if (field.error) {
      field.error.textContent = message;
      field.error.hidden = false;
    }

    field.input.setAttribute("aria-invalid", "true");
  }

  function clearError(field) {
    if (field.error) {
      field.error.textContent = "";
      field.error.hidden = true;
    }

    field.input.removeAttribute("aria-invalid");
  }

  function hideAlert() {
    if (alert) {
      alert.hidden = true;
      alert.textContent = "";
    }
  }

  function showAlert(message) {
    if (alert) {
      alert.textContent = message;
      alert.hidden = false;
    }
  }

  function clearStatus() {
    if (status) {
      status.textContent = "";
    }
  }

  function validateField(key) {
    var field = fields[key];
    var value = field.input.value;
    var trimmed = clean(value);

    if (key === "name") {
      if (trimmed === "") {
        return "Enter your full name.";
      }

      if (trimmed.length < 2) {
        return "Enter at least 2 characters for your name.";
      }

      if (trimmed.length > 80) {
        return "Keep your name to 80 characters or fewer.";
      }
    }

    if (key === "email") {
      if (trimmed === "") {
        return "Enter your email address.";
      }

      if (trimmed.length > 120) {
        return "Keep your email address to 120 characters or fewer.";
      }

      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmed)) {
        return "Enter an email address in the format name@example.com.";
      }
    }

    if (key === "subject") {
      if (trimmed === "") {
        return "Choose a subject for your message.";
      }

      if (!SUBJECTS[trimmed]) {
        return "Choose a subject from the list.";
      }
    }

    if (key === "message") {
      if (trimmed === "") {
        return "Enter a message.";
      }

      if (trimmed.length < 20) {
        return "Please write at least 20 characters.";
      }

      if (value.length > 1200) {
        return "Keep your message to 1,200 characters or fewer.";
      }
    }

    return "";
  }

  function validateAll() {
    var firstInvalid = null;

    Object.keys(fields).forEach(function (key) {
      var message = validateField(key);

      if (message) {
        setError(fields[key], message);

        if (!firstInvalid) {
          firstInvalid = fields[key].input;
        }
      } else {
        clearError(fields[key]);
      }
    });

    return firstInvalid;
  }

  function handleEdit(key) {
    var field = fields[key];

    hideAlert();
    clearStatus();

    if (field.input.getAttribute("aria-invalid") === "true") {
      var message = validateField(key);

      if (message) {
        setError(field, message);
      } else {
        clearError(field);
      }
    }
  }

  Object.keys(fields).forEach(function (key) {
    var input = fields[key].input;

    input.addEventListener("input", function () {
      handleEdit(key);
    });

    input.addEventListener("change", function () {
      handleEdit(key);
    });
  });

  form.addEventListener("submit", function (event) {
    event.preventDefault();

    if (isSubmitting) {
      return;
    }

    hideAlert();
    clearStatus();

    var firstInvalid = validateAll();

    if (firstInvalid) {
      showAlert("Please correct the highlighted fields and try again.");
      firstInvalid.focus();
      return;
    }

    isSubmitting = true;

    if (submit) {
      submit.disabled = true;
      submit.setAttribute("aria-disabled", "true");
      submit.textContent = "Validating\u2026";
    }

    // A short, local delay prevents duplicate submissions without pretending
    // that a network request is taking place.
    window.setTimeout(function () {
      form.reset();

      Object.keys(fields).forEach(function (key) {
        clearError(fields[key]);
      });

      if (submit) {
        submit.disabled = false;
        submit.removeAttribute("aria-disabled");
        submit.textContent = submitLabel;
      }

      isSubmitting = false;

      if (status) {
        status.textContent =
          "Demonstration complete. Your form was validated locally, but no message was sent or stored. " +
          "EstateVista has no backend, so no inquiry reached an agent.";
      }
    }, 450);
  });
})();
