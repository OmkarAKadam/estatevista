/**
 * Stage 4.7 property image manager.
 *
 * Uploads go directly to the approved multipart backend endpoint. No storage
 * credentials, signatures, or provider URLs are handled by the browser.
 */

import { ApiError } from "../core/api.js";
import { IMAGE_LIMITS, ROLES } from "./constants.js";
import {
  deleteImage,
  getProperty,
  listImages,
  setPrimaryImage,
  updateImageMetadata,
  uploadImage
} from "./property-api.js";
import {
  confirmDialog,
  handleApiError,
  initManagementShell,
  messageForError,
  requireManagementRole,
  showAccessDenied,
  toast
} from "./management-shell.js";
import { clear, element, query, safeImageUrl } from "./dom.js";

const pageBody = document.body;
const declaredRole = pageBody.dataset.managementRole === ROLES.AGENT ? ROLES.AGENT : ROLES.ADMIN;

const refs = {
  content: query("[data-management-content]"),
  title: query("[data-image-title]"),
  count: query("[data-image-count]"),
  error: query("[data-image-error]"),
  notice: query("[data-image-notice]"),
  noticeText: query("[data-image-notice-text]"),
  uploadPanel: query("[data-upload-panel]"),
  form: query("[data-upload-form]"),
  file: query("#imageFile"),
  fileError: query("[data-image-file-error]"),
  alt: query("#imageAlt"),
  primary: query("#imagePrimary"),
  submit: query("[data-image-submit]"),
  progress: query("[data-image-progress]"),
  grid: query("[data-image-grid]"),
  empty: query("[data-image-empty]")
};

const state = {
  role: declaredRole,
  propertyId: "",
  property: null,
  images: [],
  readOnly: false,
  busy: false
};

function showError(message) {
  if (!refs.error) {
    return;
  }
  refs.error.textContent = message;
  refs.error.hidden = false;
}

function clearError() {
  if (refs.error) {
    refs.error.textContent = "";
    refs.error.hidden = true;
  }
}

function setBusy(busy) {
  state.busy = busy;
  if (refs.submit) {
    refs.submit.disabled = busy;
    refs.submit.textContent = busy ? "Uploading…" : "Upload image";
  }
  if (refs.form) {
    refs.form.setAttribute("aria-busy", busy ? "true" : "false");
  }
  if (refs.progress) {
    refs.progress.textContent = busy ? "Uploading and validating image…" : "";
  }
}

function imageErrorMessage(error) {
  if (error instanceof ApiError) {
    if (error.status === 413) {
      return "That file is too large. Choose an image of 5 MB or less.";
    }
    if (error.status === 415) {
      return "Only JPEG, PNG, and WebP images are supported.";
    }
    if (error.status === 409) {
      return "This property already has the maximum of 10 images.";
    }
    if (error.status === 503) {
      return "Image storage is temporarily unavailable. Try again later.";
    }
    if (error.status === 400) {
      return "That image does not meet the size or dimension requirements.";
    }
  }
  return messageForError(error);
}

function readImageDimensions(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const image = new Image();
    image.onload = () => {
      resolve({ width: image.naturalWidth, height: image.naturalHeight });
      URL.revokeObjectURL(url);
    };
    image.onerror = () => {
      reject(new Error("Image could not be read."));
      URL.revokeObjectURL(url);
    };
    image.src = url;
  });
}

async function precheckFile(file) {
  if (!file) {
    return "Choose an image file before uploading.";
  }
  if (state.images.length >= IMAGE_LIMITS.MAX_IMAGES) {
    return "This property already has the maximum of 10 images.";
  }
  if (!IMAGE_LIMITS.ACCEPTED_TYPES.includes(file.type)) {
    return "Only JPEG, PNG, and WebP images are supported.";
  }
  const extension = String(file.name || "").split(".").pop()?.toLowerCase();
  if (!extension || !IMAGE_LIMITS.ACCEPTED_EXTENSIONS.includes(extension)) {
    return "Use a .jpg, .jpeg, .png, or .webp file.";
  }
  if (file.size > IMAGE_LIMITS.MAX_BYTES) {
    return "That file is too large. Choose an image of 5 MB or less.";
  }

  try {
    const { width, height } = await readImageDimensions(file);
    if (
      width < IMAGE_LIMITS.MIN_DIMENSION ||
      height < IMAGE_LIMITS.MIN_DIMENSION ||
      width > IMAGE_LIMITS.MAX_DIMENSION ||
      height > IMAGE_LIMITS.MAX_DIMENSION
    ) {
      return `Images must be between ${IMAGE_LIMITS.MIN_DIMENSION} and ${IMAGE_LIMITS.MAX_DIMENSION} pixels per side.`;
    }
    if (width * height > IMAGE_LIMITS.MAX_PIXELS) {
      return "That image has too many pixels. Choose one under 40 megapixels.";
    }
  } catch {
    return "That file could not be read as an image. Choose a different file.";
  }
  return "";
}

function actionButton(label, className, action, imageId, disabled = false) {
  const button = element("button", {
    className: `btn btn-sm ${className}`.trim(),
    text: label,
    type: "button",
    dataset: { action, imageId }
  });
  button.disabled = disabled || state.readOnly || state.busy;
  return button;
}

function renderImages() {
  if (!refs.grid) {
    return;
  }
  clear(refs.grid);
  const images = state.images.slice().sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0));

  images.forEach((image, index) => {
    const preview = element("img", {
      attributes: {
        src: safeImageUrl(image.imageUrl),
        alt: image.altText || "",
        loading: "lazy"
      }
    });
    const media = element("div", { className: "mgmt-image-card__media" }, [preview]);
    if (image.isPrimary) {
      media.append(element("span", { className: "badge bg-success mgmt-image-card__primary", text: "Primary" }));
    }

    const altInput = element("input", {
      className: "form-control form-control-sm",
      id: `alt-${image.id}`,
      type: "text",
      maxlength: "255",
      value: image.altText || ""
    });
    altInput.disabled = state.readOnly;

    const actions = element("div", { className: "mgmt-image-card__actions" }, [
      actionButton("Save alt text", "btn-outline-primary", "save-meta", image.id),
      actionButton("Set primary", "btn-outline-success", "primary", image.id, Boolean(image.isPrimary)),
      actionButton("Move up", "btn-outline-secondary", "move-up", image.id, index === 0),
      actionButton("Move down", "btn-outline-secondary", "move-down", image.id, index === images.length - 1),
      actionButton("Delete", "btn-outline-danger", "delete", image.id)
    ]);

    const card = element("div", { className: "mgmt-image-card" }, [
      media,
      element("div", { className: "mgmt-image-card__fields" }, [
        element("label", { attributes: { for: `alt-${image.id}` }, text: "Alt text" }),
        altInput
      ]),
      actions,
      element("p", {
        className: "mgmt-image-card__order",
        text: `Order ${image.sortOrder ?? index}`
      })
    ]);
    refs.grid.append(card);
  });

  if (refs.count) {
    const primary = images.find((image) => image.isPrimary);
    refs.count.textContent = `${images.length} of ${IMAGE_LIMITS.MAX_IMAGES} images · ${
      primary ? "Primary image set" : "No primary image selected"
    }`;
  }
  if (refs.empty) {
    refs.empty.hidden = images.length > 0;
  }
}

function applyReadOnly() {
  if (!state.readOnly) {
    return;
  }
  if (refs.uploadPanel) {
    refs.uploadPanel.hidden = true;
  }
  if (refs.notice && refs.noticeText) {
    refs.noticeText.textContent = "This property is archived. Images are read-only and cannot be changed.";
    refs.notice.hidden = false;
  }
}

async function loadImages() {
  try {
    const payload = await listImages(state.propertyId);
    state.images = Array.isArray(payload?.data) ? payload.data : [];
    renderImages();
  } catch (error) {
    if (handleApiError(error, { onForbidden: showAccessDenied })) {
      return;
    }
    showError(messageForError(error, "load"));
  }
}

async function loadProperty() {
  try {
    const payload = await getProperty(state.propertyId);
    state.property = payload?.data || null;
    if (!state.property) {
      showError("This property is no longer available.");
      return false;
    }
    if (refs.title) {
      refs.title.textContent = `Images — ${state.property.title}`;
    }
    state.readOnly = state.property.status === "ARCHIVED";
    applyReadOnly();
    return true;
  } catch (error) {
    if (handleApiError(error, { onForbidden: showAccessDenied })) {
      return false;
    }
    showError(messageForError(error, "load"));
    return false;
  }
}

async function handleUpload(event) {
  event.preventDefault();
  if (state.busy || state.readOnly) {
    return;
  }
  clearError();
  if (refs.fileError) {
    refs.fileError.hidden = true;
    refs.fileError.textContent = "";
  }

  const file = refs.file?.files?.[0];
  const problem = await precheckFile(file);
  if (problem) {
    if (refs.fileError) {
      refs.fileError.textContent = problem;
      refs.fileError.hidden = false;
    }
    return;
  }

  setBusy(true);
  try {
    await uploadImage(state.propertyId, file, {
      altText: refs.alt?.value,
      isPrimary: refs.primary?.checked === true,
      sortOrder: state.images.length
    });
    toast("Image uploaded.");
    if (refs.form) {
      refs.form.reset();
    }
    await loadImages();
  } catch (error) {
    if (handleApiError(error, { onForbidden: showAccessDenied })) {
      return;
    }
    showError(imageErrorMessage(error));
  } finally {
    setBusy(false);
    renderImages();
  }
}

async function handleMetaSave(imageId) {
  const input = document.getElementById(`alt-${imageId}`);
  if (!input) {
    return;
  }
  try {
    await updateImageMetadata(state.propertyId, imageId, { altText: input.value });
    toast("Alt text saved.");
    await loadImages();
  } catch (error) {
    if (handleApiError(error, { onForbidden: showAccessDenied })) {
      return;
    }
    showError(imageErrorMessage(error));
  }
}

async function handleSetPrimary(imageId) {
  try {
    await setPrimaryImage(state.propertyId, imageId);
    toast("Primary image updated.");
    await loadImages();
  } catch (error) {
    if (handleApiError(error, { onForbidden: showAccessDenied })) {
      return;
    }
    showError(imageErrorMessage(error));
  }
}

async function handleDelete(imageId) {
  const confirmed = await confirmDialog({
    title: "Delete image",
    message: "Delete this image from the property? This cannot be undone.",
    confirmLabel: "Delete image",
    destructive: true
  });
  if (!confirmed) {
    return;
  }
  try {
    await deleteImage(state.propertyId, imageId);
    toast("Image deleted.");
    await loadImages();
  } catch (error) {
    if (handleApiError(error, { onForbidden: showAccessDenied })) {
      return;
    }
    showError(imageErrorMessage(error));
  }
}

async function handleMove(imageId, direction) {
  const ordered = state.images.slice().sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0));
  const index = ordered.findIndex((image) => image.id === imageId);
  const nextIndex = index + direction;
  if (index < 0 || nextIndex < 0 || nextIndex >= ordered.length) {
    return;
  }

  const current = ordered[index];
  const neighbor = ordered[nextIndex];
  const currentOrder = current.sortOrder ?? index;
  const neighborOrder = neighbor.sortOrder ?? nextIndex;

  try {
    await Promise.all([
      updateImageMetadata(state.propertyId, current.id, { sortOrder: neighborOrder }),
      updateImageMetadata(state.propertyId, neighbor.id, { sortOrder: currentOrder })
    ]);
    await loadImages();
  } catch (error) {
    if (handleApiError(error, { onForbidden: showAccessDenied })) {
      return;
    }
    showError(imageErrorMessage(error));
  }
}

function wireEvents() {
  if (refs.form) {
    refs.form.addEventListener("submit", handleUpload);
  }
  if (refs.grid) {
    refs.grid.addEventListener("click", (event) => {
      const button = event.target?.closest?.("[data-action][data-image-id]");
      if (!button || button.disabled) {
        return;
      }
      const { action, imageId } = button.dataset;
      button.disabled = true;
      if (action === "save-meta") {
        handleMetaSave(imageId).finally(() => renderImages());
      } else if (action === "primary") {
        handleSetPrimary(imageId).finally(() => renderImages());
      } else if (action === "delete") {
        handleDelete(imageId).finally(() => renderImages());
      } else if (action === "move-up") {
        handleMove(imageId, -1).finally(() => renderImages());
      } else if (action === "move-down") {
        handleMove(imageId, 1).finally(() => renderImages());
      }
    });
  }
}

async function start() {
  const user = await requireManagementRole([ROLES.ADMIN, ROLES.AGENT]);
  if (!user) {
    return;
  }
  state.role = user.role === ROLES.AGENT ? ROLES.AGENT : ROLES.ADMIN;
  if (state.role !== declaredRole) {
    showAccessDenied();
    return;
  }

  initManagementShell({
    role: state.role,
    active: "properties",
    title: "Property Images"
  });
  wireEvents();

  state.propertyId = new URLSearchParams(window.location.search).get("id") || "";
  if (!state.propertyId) {
    showError("A property identifier is required. Open this page from the property list.");
    if (refs.submit) {
      refs.submit.disabled = true;
    }
    return;
  }

  if (await loadProperty()) {
    await loadImages();
  }
}

start().catch(() => {
  showError("We could not initialize this page. Reload and try again.");
});
