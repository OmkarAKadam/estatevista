/**
 * EstateVista — property details behaviour (Stage 5.4).
 *
 * Reads ?id=<uuid> from the approved public URL, fetches the published property
 * and its images from the live API, then renders the listing, a selectable
 * gallery and related properties. The shared card renderer from
 * property-card.js is reused for the related rail. No demonstration data is
 * consulted; every displayed listing value comes from a public API response.
 */
(function () {
  "use strict";

  var CARD = window.EVPropertyCard;
  var SKELETONS = window.EVSkeletons;

  /* Canonical backend UUID shape, including seeded zero-version identifiers. */
  var UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

  var activeId = "";
  var activeApi = null;
  var activeDetail = null;
  var requestSequence = 0;
  var detailSequence = 0;
  var imageSequence = 0;
  var relatedSequence = 0;
  var detailController = null;
  var imageController = null;
  var relatedController = null;
  var detailFavoriteController = null;
  var relatedFavoritesController = null;
  var cooldown = null;
  var lastErrorWasRateLimit = false;

  function byId(id) {
    return document.getElementById(id);
  }

  function text(value) {
    if (CARD && typeof CARD.cleanText === "function") {
      return CARD.cleanText(value);
    }

    return typeof value === "string" ? value.replace(/\s+/g, " ").trim() : "";
  }

  function el(tag, className, content) {
    var node = document.createElement(tag);

    if (className) {
      node.className = className;
    }

    if (content !== undefined && content !== null) {
      node.textContent = content;
    }

    return node;
  }

  function setText(id, value) {
    var node = byId(id);

    if (node) {
      node.textContent = value;
    }
  }

  var API_READY_TIMEOUT_MS = 5000;

  function waitForPublicApi() {
    return new Promise(function (resolve, reject) {
      if (window.EVPublicPropertyApi) {
        resolve(window.EVPublicPropertyApi);
        return;
      }

      var settled = false;
      var timer = window.setTimeout(function () {
        if (settled) {
          return;
        }
        settled = true;
        reject(new Error("Property API unavailable"));
      }, API_READY_TIMEOUT_MS);

      window.addEventListener(
        "ev-public-property-api-ready",
        function onReady() {
          if (settled) {
            return;
          }
          settled = true;
          window.clearTimeout(timer);
          resolve(window.EVPublicPropertyApi);
        },
        { once: true }
      );
    });
  }

  function waitForFavoritesApi() {
    return new Promise(function (resolve, reject) {
      if (window.EVFavorites) {
        resolve(window.EVFavorites);
        return;
      }

      var settled = false;
      var timer = window.setTimeout(function () {
        if (settled) {
          return;
        }
        settled = true;
        reject(new Error("Favorites service unavailable"));
      }, API_READY_TIMEOUT_MS);

      window.addEventListener(
        "ev-favorites-ready",
        function onReady() {
          if (settled) {
            return;
          }
          settled = true;
          window.clearTimeout(timer);
          resolve(window.EVFavorites);
        },
        { once: true }
      );
    });
  }

  function syncFavoriteControls() {
    if (window.EVFavorites && CARD) {
      CARD.syncFavoriteControls(window.EVFavorites.getSnapshot());
    }
  }

  /** One authoritative individual read for the displayed detail property. */
  function refreshDetailStatus(id) {
    if (!id) {
      return;
    }
    if (detailFavoriteController) {
      detailFavoriteController.abort();
    }
    detailFavoriteController = new AbortController();
    var signal = detailFavoriteController.signal;

    waitForFavoritesApi()
      .then(function (client) {
        return client.getStatus(id, { force: true, signal: signal });
      })
      .catch(function () {
        /* An anonymous/non-buyer page makes no private request; a failed read
           leaves the last confirmed state untouched. */
      })
      .then(syncFavoriteControls);
  }

  /** One bounded batch for the related-properties collection. */
  function refreshRelatedStatuses(items) {
    var ids = (Array.isArray(items) ? items : [])
      .map(function (item) { return item && item.id; })
      .filter(function (id) { return typeof id === "string" && id.length > 0; });

    if (ids.length === 0) {
      return;
    }
    if (relatedFavoritesController) {
      relatedFavoritesController.abort();
    }
    relatedFavoritesController = new AbortController();
    var signal = relatedFavoritesController.signal;

    waitForFavoritesApi()
      .then(function (client) {
        return client.syncCollection(ids, { signal: signal });
      })
      .catch(function () {
        /* Related discovery stays usable when private status cannot load. */
      });
  }

  function renderDetailFavorite(property) {
    var host = byId("detailFavoriteHost");

    if (!host || !CARD || !property || !property.id) {
      return;
    }

    var button = CARD.createFavoriteButton({
      id: property.id,
      title: property.title || "this property"
    });
    button.classList.add("ev-favorite--inline");
    host.replaceChildren(button);
    syncFavoriteControls();
    refreshDetailStatus(property.id);
  }

  function isRateLimit(error) {
    return Boolean(error && (error.reason === "rate_limit" || error.status === 429));
  }

  function isCancelled(error) {
    if (window.EVPublicPropertyApi && typeof window.EVPublicPropertyApi.isCancellation === "function") {
      return window.EVPublicPropertyApi.isCancellation(error);
    }
    return Boolean(error && error.name === "AbortError");
  }

  function describe(error) {
    if (window.EVPublicPropertyApi && typeof window.EVPublicPropertyApi.describeApiFailure === "function") {
      return window.EVPublicPropertyApi.describeApiFailure(error);
    }
    return "The property service is not available right now. Please try again later.";
  }

  function clearDetailSkeleton() {
    var skeleton = byId("detailSkeleton");
    if (SKELETONS && typeof SKELETONS.clear === "function") {
      SKELETONS.clear(skeleton);
    } else if (skeleton) {
      skeleton.replaceChildren();
    }
    if (skeleton) {
      skeleton.hidden = true;
    }
  }

  function clearRelatedSkeleton() {
    var skeleton = byId("relatedSkeleton");
    if (SKELETONS && typeof SKELETONS.clear === "function") {
      SKELETONS.clear(skeleton);
    } else if (skeleton) {
      skeleton.replaceChildren();
    }
    if (skeleton) {
      skeleton.hidden = true;
    }
  }

  function cancelControllers() {
    [
      detailController,
      imageController,
      relatedController,
      detailFavoriteController,
      relatedFavoritesController
    ].forEach(function (controller) {
      if (controller) {
        controller.abort();
      }
    });
    detailController = null;
    imageController = null;
    relatedController = null;
    detailFavoriteController = null;
    relatedFavoritesController = null;
  }

  function setRetryState(active, message) {
    var retry = byId("retryDetailLoad");
    if (!retry) {
      return;
    }
    retry.disabled = active;
    if (active) {
      retry.setAttribute("aria-disabled", "true");
      retry.textContent = "Retry after cooldown";
    } else {
      retry.removeAttribute("aria-disabled");
      retry.textContent = "Try again";
    }
    if (message) {
      retry.setAttribute("title", message);
    } else {
      retry.removeAttribute("title");
    }
  }

  function ensureCooldown() {
    var bridge = window.EVPublicPropertyApi;
    if (cooldown || !bridge) {
      return;
    }

    var listener = function (active) {
      if (active) {
        setRetryState(true, "Waiting for the request limit to reset");
        return;
      }
      setRetryState(false, "");
      var errorPanel = byId("propertyError");
      if (lastErrorWasRateLimit && errorPanel && !errorPanel.hidden) {
        setText(
          "propertyErrorMessage",
          "The request limit has reset. You can try again now."
        );
      }
    };

    if (typeof bridge.getPublicReadCooldown === "function") {
      cooldown = bridge.getPublicReadCooldown();
      if (typeof cooldown.subscribe === "function") {
        cooldown.subscribe(listener);
      }
    } else if (typeof bridge.createRateLimitCooldown === "function") {
      cooldown = bridge.createRateLimitCooldown(listener);
    }
  }

  function beginCooldown(error) {
    ensureCooldown();
    if (!cooldown) {
      return;
    }
    lastErrorWasRateLimit = true;
    cooldown.start(error && Number.isFinite(error.retryAfterMs) ? error.retryAfterMs : undefined);
    setRetryState(cooldown.active(), "Waiting for the request limit to reset");
  }

  /**
   * Reads the approved ?id=<uuid> public detail link. Duplicate ids are only
   * accepted when every value is identical; conflicting ids are rejected before
   * any API request is made.
   */
  function readPropertyId() {
    var params = new URLSearchParams(window.location.search);
    var values = params.getAll("id").map(function (value) {
      return text(value);
    });

    if (values.length === 0 || values.some(function (value) { return value === ""; })) {
      return "";
    }
    if (values.some(function (value) { return value !== values[0]; })) {
      return "";
    }

    return UUID_PATTERN.test(values[0]) ? values[0] : "";
  }

  /**
   * Uses the images endpoint verbatim: backend `sortOrder` is the display order.
   * `primaryImageUrl` is only a fallback when the gallery has no image rows.
   */
  function orderedImages(detail, images) {
    var rows = (Array.isArray(images) ? images : [])
      .map(function (image, index) {
        if (!image || typeof image !== "object") {
          return null;
        }
        var url = text(image.imageUrl);
        var order = Number(image.sortOrder);
        return url
          ? {
              url: url,
              alt: text(image.altText),
              index: index,
              order: Number.isFinite(order) ? order : index
            }
          : null;
      })
      .filter(Boolean)
      .sort(function (a, b) {
        return a.order - b.order || a.index - b.index;
      });

    var seen = [];
    var ordered = [];

    rows.forEach(function (row) {
      if (seen.indexOf(row.url) === -1) {
        seen.push(row.url);
        ordered.push(row);
      }
    });

    var primaryUrl = text(detail.primaryImageUrl);
    if (ordered.length === 0 && primaryUrl) {
      ordered.push({ url: primaryUrl, alt: "", index: 0, order: 0 });
    }

    return ordered;
  }

  function toViewModel(detail, images) {
    var location = detail.location && typeof detail.location === "object" ? detail.location : {};
    var residential =
      detail.residentialDetails && typeof detail.residentialDetails === "object"
        ? detail.residentialDetails
        : {};
    var amenities = (Array.isArray(detail.amenities) ? detail.amenities : [])
      .map(function (amenity) {
        return amenity && typeof amenity === "object" ? text(amenity.name) : "";
      })
      .filter(function (name) {
        return name !== "";
      });

    return {
      id: text(detail.id),
      title: text(detail.title) || "Property details",
      description: text(detail.description),
      type: CARD.normalizeApiType(detail),
      subtype: text(detail.subtype),
      category: text(detail.category),
      listingType: text(detail.listingType).toUpperCase(),
      status: text(detail.status).toLowerCase() || "published",
      price: Number.isFinite(Number(detail.price)) ? Number(detail.price) : null,
      locality: text(location.locality),
      city: text(location.city),
      areaValue: Number(detail.areaValue),
      areaUnit: text(detail.areaUnit),
      bedrooms: Number(residential.bedrooms),
      bathrooms: Number(residential.bathrooms),
      amenities: amenities,
      images: orderedImages(detail, images)
    };
  }

  function imageAssets(property) {
    var raw = Array.isArray(property.images) ? property.images : [];
    var seen = [];
    var assets = [];

    raw.forEach(function (item) {
      var path = CARD.resolveImagePath(item && item.url);

      if (seen.indexOf(path) === -1) {
        seen.push(path);
        assets.push({ url: path, alt: text(item.alt) });
      }
    });

    return assets.length > 0
      ? assets
      : [{ url: CARD.PLACEHOLDER_IMAGE, alt: "Property photograph unavailable" }];
  }

  function areaLabel(property) {
    var value = Number(property.areaValue);
    if (!Number.isFinite(value) || value <= 0) {
      return "";
    }

    var amount = value.toLocaleString("en-IN");
    var unit = text(property.areaUnit).toUpperCase();

    if (unit === "SQFT" || unit === "SQ_FT") {
      return amount + " sq ft";
    }
    if (unit === "SQM" || unit === "SQ_M") {
      return amount + " sq m";
    }

    return unit ? amount + " " + unit.replace(/_/g, " ").toLowerCase() : amount;
  }

  function setMainImage(source, alt) {
    var image = byId("detailMainImage");

    if (!image) {
      return;
    }

    image.onerror =
      source === CARD.PLACEHOLDER_IMAGE
        ? null
        : function onImageError() {
            image.onerror = null;
            image.src = CARD.PLACEHOLDER_IMAGE;
            image.alt = "Property photograph unavailable";
          };

    image.src = source;
    image.alt = alt;
  }

  function renderGallery(property, title) {
    var assets = imageAssets(property);
    var thumbs = byId("detailThumbs");
    var caption = byId("detailImageCaption");
    var buttons = [];

    if (!thumbs) {
      return;
    }

    thumbs.replaceChildren();
    thumbs.hidden = false;

    function imageAlt(index) {
      var asset = assets[index];

      if (asset.url === CARD.PLACEHOLDER_IMAGE) {
        return "Property photograph unavailable";
      }
      if (asset.alt) {
        return asset.alt;
      }
      if (assets.length === 1) {
        return "Photograph of " + title;
      }

      return "Photograph of " + title + ", image " + (index + 1) + " of " + assets.length;
    }

    function captionFor(index) {
      if (assets[index].url === CARD.PLACEHOLDER_IMAGE) {
        return "No photograph is available for this property.";
      }

      return (
        "Image " + (index + 1) + " of " + assets.length +
        " \u00B7 Photograph supplied for this listing."
      );
    }

    function selectImage(index) {
      setMainImage(assets[index].url, imageAlt(index));

      if (caption) {
        caption.textContent = captionFor(index);
      }

      buttons.forEach(function (button, buttonIndex) {
        button.setAttribute("aria-pressed", buttonIndex === index ? "true" : "false");
      });
    }

    assets.forEach(function (asset, index) {
      var button = el("button", "property-gallery__thumb");
      var thumbImage = document.createElement("img");

      button.type = "button";
      button.setAttribute("aria-pressed", index === 0 ? "true" : "false");
      button.setAttribute(
        "aria-label",
        "Show image " + (index + 1) + " of " + assets.length +
          (asset.url === CARD.PLACEHOLDER_IMAGE ? " (placeholder image)" : "")
      );

      thumbImage.src = asset.url;
      thumbImage.alt = "";
      thumbImage.width = 160;
      thumbImage.height = 120;
      thumbImage.loading = "lazy";
      thumbImage.decoding = "async";
      thumbImage.addEventListener("error", function onThumbError() {
        thumbImage.removeEventListener("error", onThumbError);
        thumbImage.src = CARD.PLACEHOLDER_IMAGE;
      });

      button.appendChild(thumbImage);
      button.addEventListener("click", function () {
        selectImage(index);
      });

      thumbs.appendChild(button);
      buttons.push(button);
    });

    /* Assigning replaces any prior handler on retry; it does not accumulate. */
    thumbs.onkeydown = function (event) {
      var key = event.key;
      var next = -1;

      if (key === "ArrowRight" || key === "ArrowDown") {
        next = (buttons.indexOf(document.activeElement) + 1) % buttons.length;
      } else if (key === "ArrowLeft" || key === "ArrowUp") {
        next =
          (buttons.indexOf(document.activeElement) - 1 + buttons.length) %
          buttons.length;
      } else if (key === "Home") {
        next = 0;
      } else if (key === "End") {
        next = buttons.length - 1;
      }

      if (next === -1) {
        return;
      }

      event.preventDefault();
      buttons[next].focus();
      selectImage(next);
    };

    selectImage(0);
  }

  function renderFacts(property) {
    var facts = byId("detailFacts");
    var type = CARD.displayTypeLabel(property) || CARD.typeLabel(property.type);
    var items = [
      { label: "Property type", value: type },
      {
        label: "Listing type",
        value: CARD.listingTypeLabel(property.listingType) || text(property.listingType)
      },
      { label: "Bedrooms", value: CARD.countLabel(property.bedrooms, "bedroom") },
      { label: "Bathrooms", value: CARD.countLabel(property.bathrooms, "bathroom") },
      { label: "Built-up area", value: areaLabel(property) }
    ];

    if (!facts) {
      return;
    }

    facts.replaceChildren();

    items.forEach(function (item) {
      if (!item.value) {
        return;
      }

      var wrapper = el("div", "property-facts__item");
      wrapper.appendChild(el("dt", null, item.label));
      wrapper.appendChild(el("dd", null, item.value));
      facts.appendChild(wrapper);
    });
  }

  function renderSpecs(property) {
    var specs = byId("detailSpecs");
    var status = CARD.statusBadge(property.status);
    var items = [
      { label: "Property ID", value: text(property.id) },
      { label: "Listing type", value: CARD.listingTypeLabel(property.listingType) },
      { label: "Category", value: CARD.humanizeEnum(property.category) },
      { label: "Subtype", value: CARD.humanizeEnum(property.subtype) },
      { label: "Status", value: status ? status.label : text(property.status) },
      { label: "Bedrooms", value: CARD.countLabel(property.bedrooms, "bedroom") },
      { label: "Bathrooms", value: CARD.countLabel(property.bathrooms, "bathroom") },
      { label: "Built-up area", value: areaLabel(property) },
      { label: "Locality", value: text(property.locality) },
      { label: "City", value: text(property.city) }
    ];

    if (!specs) {
      return;
    }

    specs.replaceChildren();

    items.forEach(function (item) {
      if (!item.value) {
        return;
      }

      specs.appendChild(el("dt", null, item.label));
      specs.appendChild(el("dd", null, item.value));
    });
  }

  function renderDescription(property) {
    setText(
      "detailDescription",
      text(property.description) || "No description has been supplied for this property."
    );
  }

  function renderAmenities(property) {
    var amenities = Array.isArray(property.amenities)
      ? property.amenities
          .map(function (item) {
            return text(item);
          })
          .filter(function (item) {
            return item !== "";
          })
      : [];

    if (amenities.length > 0) {
      setText("detailAmenities", amenities.join(" \u00B7 "));
      return;
    }

    setText(
      "detailAmenities",
      "No amenity details have been supplied for this property."
    );
  }

  function renderBadges(property) {
    var container = byId("detailBadges");
    var type = CARD.displayTypeLabel(property) || CARD.typeLabel(property.type);
    var listingType = CARD.listingTypeLabel(property.listingType);
    var status = CARD.statusBadge(property.status);

    if (!container) {
      return;
    }

    container.replaceChildren();

    if (type) {
      container.appendChild(el("span", "ev-badge ev-badge--neutral", type));
    }
    if (listingType) {
      container.appendChild(el("span", "ev-badge ev-badge--neutral", listingType));
    }
    if (status) {
      container.appendChild(
        el("span", "ev-badge " + status.modifier, status.label)
      );
    }
  }

  function renderRelated(related) {
    var section = byId("relatedSection");
    var grid = byId("relatedGrid");
    var models = (Array.isArray(related) ? related : [])
      .map(function (item) {
        return CARD.fromApiSummary(item);
      })
      .filter(Boolean);

    if (!section || !grid) {
      return;
    }

    if (models.length === 0) {
      section.hidden = true;
      return;
    }

    grid.replaceChildren();

    var fragment = document.createDocumentFragment();

    models.forEach(function (item) {
      fragment.appendChild(CARD.buildCard(item, { columnClass: "col", headingTag: "h3" }));
    });

    grid.appendChild(fragment);
    section.hidden = false;
    syncFavoriteControls();
    refreshRelatedStatuses(models);
  }

  function updateMeta(property) {
    var title = text(property.title) || "Property details";
    var location = CARD.locationLabel(property);
    var description = document.querySelector('meta[name="description"]');

    document.title = title + " \u2014 EstateVista";

    if (description) {
      description.setAttribute(
        "content",
        "View " + title +
          (location ? " in " + location : "") +
          ", an EstateVista property listing."
      );
    }
  }

  function render(property) {
    var detail = byId("propertyDetail");
    var error = byId("propertyError");
    var title = text(property.title) || "Property details";
    var location = CARD.locationLabel(property);

    if (detail) {
      detail.hidden = false;
    }

    if (error) {
      error.hidden = true;
      error.removeAttribute("aria-busy");
    }

    activeDetail = null;
    clearDetailSkeleton();
    clearRelatedSkeleton();
    lastErrorWasRateLimit = false;
    setRetryVisible(false);
    setText("breadcrumbCurrent", title);
    setText("detailTitle", title);
    setText("detailLocation", location || "Location not provided");
    setText("detailPrice", CARD.formatPrice(property.price));

    renderBadges(property);
    renderFacts(property);
    renderSpecs(property);
    renderDescription(property);
    renderAmenities(property);
    renderGallery(property, title);
    updateMeta(property);
    renderDetailFavorite(property);
    if (window.EVInquiryProperty && typeof window.EVInquiryProperty.setActiveProperty === "function") {
      window.EVInquiryProperty.setActiveProperty(activeId, property);
    }
  }

  function setRetryVisible(visible) {
    var retry = byId("retryDetailLoad");

    if (retry) {
      retry.hidden = !visible;
    }
  }

  function showError(title, message, canRetry) {
    var detail = byId("propertyDetail");
    var error = byId("propertyError");
    var related = byId("relatedSection");
    var description = document.querySelector('meta[name="description"]');

    if (detail) {
      detail.hidden = true;
    }

    if (related) {
      related.hidden = true;
    }

    if (error) {
      error.hidden = false;
      error.removeAttribute("aria-busy");
    }

    activeDetail = null;
    if (window.EVInquiryProperty && typeof window.EVInquiryProperty.setActiveProperty === "function") {
      window.EVInquiryProperty.setActiveProperty(null, null);
    }
    clearDetailSkeleton();
    clearRelatedSkeleton();
    if (!lastErrorWasRateLimit) {
      setRetryVisible(canRetry === true);
    }
    setText("breadcrumbCurrent", title);
    setText("propertyErrorTitle", title);
    setText("propertyErrorMessage", message);
    document.title = title + " \u2014 EstateVista";

    if (description) {
      description.setAttribute(
        "content",
        canRetry === true
          ? "The EstateVista property service is temporarily unavailable."
          : "The requested EstateVista property listing could not be found."
      );
    }
  }

  function showLoading() {
    var detail = byId("propertyDetail");
    var error = byId("propertyError");
    var related = byId("relatedSection");

    if (detail) {
      detail.hidden = true;
    }
    if (related) {
      related.hidden = true;
    }
    if (error) {
      error.hidden = false;
      error.setAttribute("aria-busy", "true");
    }

    lastErrorWasRateLimit = false;
    var skeleton = byId("detailSkeleton");
    if (skeleton) {
      skeleton.hidden = false;
      if (SKELETONS && typeof SKELETONS.detail === "function") {
        SKELETONS.detail(skeleton);
      }
    }
    clearRelatedSkeleton();
    setRetryVisible(false);
    setRetryState(false, "");
    setText("breadcrumbCurrent", "Loading property");
    setText("propertyErrorTitle", "Loading property details");
    setText(
      "propertyErrorMessage",
      "Fetching the latest published information for this property\u2026"
    );
    document.title = "Loading property \u2014 EstateVista";
  }

  /** Optional image request: own controller/sequence, failure yields fallback. */
  function fetchImages(id, sequence) {
    if (!activeApi) {
      return Promise.resolve([]);
    }
    var localSequence = ++imageSequence;
    var controller = new AbortController();
    imageController = controller;

    return activeApi.listPublishedImages(id, { signal: controller.signal })
      .then(function (images) {
        if (sequence !== requestSequence || localSequence !== imageSequence) {
          return [];
        }
        return images;
      })
      .catch(function (error) {
        if (isCancelled(error) || sequence !== requestSequence || localSequence !== imageSequence) {
          return [];
        }
        // The detail response already provides an approved image fallback.
        return [];
      });
  }

  /** Optional related request: hidden skeleton, no effect on the main detail. */
  function loadRelated(id, sequence) {
    if (!activeApi) {
      return;
    }
    var localSequence = ++relatedSequence;
    var controller = new AbortController();
    relatedController = controller;
    var skeleton = byId("relatedSkeleton");

    if (skeleton && SKELETONS && typeof SKELETONS.related === "function") {
      skeleton.hidden = false;
      SKELETONS.related(skeleton);
    }

    activeApi.getRelatedProperties(id, { size: 3, signal: controller.signal })
      .then(function (related) {
        if (sequence !== requestSequence || localSequence !== relatedSequence) {
          return;
        }
        clearRelatedSkeleton();
        renderRelated(related);
      })
      .catch(function (error) {
        if (isCancelled(error) || sequence !== requestSequence || localSequence !== relatedSequence) {
          return;
        }
        clearRelatedSkeleton();
        renderRelated([]);
      });
  }

  /** Fetches a valid UUID; optional requests never block the main detail view. */
  function loadDetail(id) {
    activeId = id;
    ensureCooldown();
    if (cooldown && cooldown.active()) {
      lastErrorWasRateLimit = true;
      setRetryState(true, "Waiting for the request limit to reset");
      showError(
        "Property details unavailable",
        describe({ reason: "rate_limit", retryAfterMs: cooldown.remainingMs() }),
        true
      );
      return Promise.resolve();
    }
    var sequence = ++requestSequence;
    ++detailSequence;
    cancelControllers();
    showLoading();

    return waitForPublicApi()
      .then(function (api) {
        activeApi = api;
        var controller = new AbortController();
        detailController = controller;

        return api.getPublishedDetail(id, { signal: controller.signal })
          .then(function (detail) {
            if (sequence !== requestSequence) {
              return;
            }
            /* Render with the detail response first, then replace the gallery
               if the independent images request completes successfully. */
            return fetchImages(id, sequence).then(function (images) {
              if (sequence !== requestSequence) {
                return;
              }
              activeDetail = detail;
              render(toViewModel(detail, images));
              loadRelated(id, sequence);
            });
          });
      })
      .catch(function (error) {
        if (sequence !== requestSequence || isCancelled(error)) {
          return;
        }

        if (error && error.status === 404) {
          showError(
            "Property not found",
            "This property is not available publicly. It may have been removed, or the link may be incomplete.",
            false
          );
          return;
        }

        if (isRateLimit(error)) {
          beginCooldown(error);
        }

        showError(
          "Property details unavailable",
          describe(error),
          true
        );
      });
  }

  function init() {
    var retry = byId("retryDetailLoad");

    if (retry) {
      retry.addEventListener("click", function () {
        if (activeId) {
          loadDetail(activeId);
        }
      });
    }

    if (!CARD) {
      showError(
        "Property details unavailable",
        "The shared property components did not load, so this listing cannot be displayed. Return to the properties page to browse the collection.",
        false
      );
      return;
    }

    var id = readPropertyId();

    if (!id) {
      showError(
        "Property not found",
        "The property link is incomplete or ambiguous. Return to the properties page to browse published listings.",
        false
      );
      return;
    }

    loadDetail(id);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
