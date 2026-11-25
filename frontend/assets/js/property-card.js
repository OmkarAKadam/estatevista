/**
 * EstateVista — shared property card renderer.
 *
 * Used by the listing grid (properties.js) and the related-properties rail on
 * property-details.html. Keeping one renderer here prevents the card markup
 * from drifting between pages.
 */
(function () {
  "use strict";

  var PLACEHOLDER_IMAGE = "assets/images/brand/property-placeholder.svg";
  var UNAVAILABLE_FAVORITE = "Property no longer available";

  var FAVORITES = null;
  var favoritesWatched = false;

  var TYPE_LABELS = {
    apartment: "Apartment",
    villa: "Villa",
    rowhouse: "Row house",
    penthouse: "Penthouse"
  };

  var STATUS_BADGES = {
    published: { label: "Available", modifier: "ev-badge--available" },
    available: { label: "Available", modifier: "ev-badge--available" },
    reserved: { label: "Reserved", modifier: "ev-badge--reserved" },
    unavailable: { label: "Unavailable", modifier: "ev-badge--reserved" },
    sold: { label: "Sold", modifier: "ev-badge--sold" },
    rented: { label: "Rented", modifier: "ev-badge--reserved" },
    archived: { label: "Archived", modifier: "ev-badge--neutral" }
  };

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

  function cleanText(value) {
    return typeof value === "string" ? value.replace(/\s+/g, " ").trim() : "";
  }

  function isPositiveNumber(value) {
    return typeof value === "number" && isFinite(value) && value > 0;
  }

  function finiteNumber(value) {
    if (value === null || value === undefined || value === "") {
      return null;
    }
    var number = Number(value);
    return isFinite(number) ? number : null;
  }

  /** Turns approved enum strings such as INDEPENDENT_HOUSE into readable labels. */
  function humanizeEnum(value) {
    var text = cleanText(value);
    if (!text) {
      return null;
    }

    var direct = TYPE_LABELS[text.toLowerCase()];
    if (direct) {
      return direct;
    }

    var words = text
      .replace(/[_-]+/g, " ")
      .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
      .toLowerCase()
      .split(/\s+/)
      .filter(Boolean);

    if (words.length === 0) {
      return null;
    }

    return words
      .map(function (word) {
        return word.charAt(0).toUpperCase() + word.slice(1);
      })
      .join(" ");
  }

  function listingTypeLabel(value) {
    var key = cleanText(value).toUpperCase();
    if (key === "SALE") {
      return "For sale";
    }
    if (key === "RENT") {
      return "For rent";
    }
    return null;
  }

  /** Formats whole rupees the way Indian listings usually do: ₹4.85 Cr / ₹95 L. */
  function formatPrice(value) {
    if (typeof value !== "number" || !isFinite(value) || value < 0) {
      return "Price on request";
    }

    if (value === 0) {
      return "\u20B90";
    }

    if (value >= 10000000) {
      return "\u20B9" + parseFloat((value / 10000000).toFixed(2)) + " Cr";
    }

    if (value >= 100000) {
      return "\u20B9" + parseFloat((value / 100000).toFixed(2)) + " L";
    }

    return "\u20B9" + value.toLocaleString("en-IN");
  }

  function formatArea(value) {
    return isPositiveNumber(value) ? value.toLocaleString("en-IN") + " sq ft" : null;
  }

  function countLabel(value, singular) {
    if (!isPositiveNumber(value)) {
      return null;
    }

    var rounded = Math.round(value);
    return rounded + " " + singular + (rounded === 1 ? "" : "s");
  }

  function typeLabel(value) {
    var key = cleanText(value).toLowerCase();
    return TYPE_LABELS[key] || null;
  }

  /** Preferred card badge label: approved subtype first, then category. */
  function displayTypeLabel(property) {
    if (!property || typeof property !== "object") {
      return null;
    }
    return (
      humanizeEnum(property.subtype) ||
      humanizeEnum(property.category) ||
      typeLabel(property.type)
    );
  }

  function statusBadge(value) {
    var key = cleanText(value).toLowerCase();
    return STATUS_BADGES[key] || null;
  }

  /** Maps a backend category/subtype pair onto the established card type keys. */
  function normalizeApiType(property) {
    var subtype = cleanText(property && property.subtype).toLowerCase();
    var category = cleanText(property && property.category).toLowerCase();
    var source = subtype || category;

    if (/penthouse/.test(source)) {
      return "penthouse";
    }
    if (/villa|house/.test(source)) {
      return /row|terrace/.test(source) ? "rowhouse" : "villa";
    }
    if (/flat|apartment/.test(source)) {
      return "apartment";
    }
    if (/row/.test(source)) {
      return "rowhouse";
    }

    return cleanText(subtype || category);
  }

  /** Converts an approved PropertySummaryResponse into the shared card model. */
  function fromApiSummary(summary) {
    if (!summary || typeof summary !== "object") {
      return null;
    }

    var areaValue = finiteNumber(summary.areaValue);
    var unit = cleanText(summary.areaUnit).toUpperCase();
    var image = cleanText(summary.primaryImageUrl);
    var subtype = cleanText(summary.subtype);
    var category = cleanText(summary.category);

    return {
      id: cleanText(summary.id),
      title: cleanText(summary.title) || "Untitled property",
      type: normalizeApiType(summary),
      subtype: subtype,
      category: category,
      listingType: cleanText(summary.listingType).toUpperCase(),
      status: cleanText(summary.status).toLowerCase() || "published",
      price: finiteNumber(summary.price),
      locality: cleanText(summary.locality),
      city: cleanText(summary.city),
      areaSqft:
        (unit === "SQFT" || unit === "SQ_FT") && areaValue !== null ? areaValue : null,
      bedrooms: finiteNumber(summary.bedrooms),
      bathrooms: finiteNumber(summary.bathrooms),
      images: image ? [image] : []
    };
  }

  function locationLabel(property) {
    return [cleanText(property.locality), cleanText(property.city)]
      .filter(function (part) {
        return part !== "";
      })
      .join(", ");
  }

  /**
   * Approved public image fields may contain absolute URLs or root-relative
   * paths such as "assets/images/properties/example.webp". Bare filenames are
   * still accepted for backwards compatibility. Unexpected schemes are replaced
   * with the local placeholder before an element ever receives the value.
   */
  function resolveImagePath(rawValue) {
    var raw = cleanText(rawValue);

    if (raw === "") {
      return PLACEHOLDER_IMAGE;
    }

    if (/^[a-z][a-z0-9+.-]*:/i.test(raw) && !/^https?:\/\//i.test(raw)) {
      return PLACEHOLDER_IMAGE;
    }

    if (/^(?:[a-z][a-z0-9+.-]*:)?\/\//i.test(raw) || raw.charAt(0) === "/") {
      return raw;
    }

    if (raw.indexOf("assets/") === 0) {
      return raw;
    }

    return "assets/images/properties/" + raw;
  }

  function primaryImage(property) {
    var images = property.images;

    if (!Array.isArray(images) || images.length === 0) {
      return PLACEHOLDER_IMAGE;
    }

    return resolveImagePath(images[0]);
  }

  function arrowIcon() {
    var NS = "http://www.w3.org/2000/svg";
    var svg = document.createElementNS(NS, "svg");
    var path = document.createElementNS(NS, "path");

    svg.setAttribute("width", "16");
    svg.setAttribute("height", "16");
    svg.setAttribute("viewBox", "0 0 16 16");
    svg.setAttribute("fill", "none");
    svg.setAttribute("aria-hidden", "true");

    path.setAttribute("d", "M2.5 8h10M8.5 4l4 4-4 4");
    path.setAttribute("stroke", "currentColor");
    path.setAttribute("stroke-width", "1.5");
    path.setAttribute("stroke-linecap", "round");
    path.setAttribute("stroke-linejoin", "round");

    svg.appendChild(path);
    return svg;
  }

  /* ------------------------------------------------------------- favorites */

  function heartIcon(filled) {
    var NS = "http://www.w3.org/2000/svg";
    var svg = document.createElementNS(NS, "svg");
    var path = document.createElementNS(NS, "path");

    svg.setAttribute("width", "18");
    svg.setAttribute("height", "18");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("fill", filled ? "currentColor" : "none");
    svg.setAttribute("stroke", "currentColor");
    svg.setAttribute("stroke-width", "1.7");
    svg.setAttribute("stroke-linecap", "round");
    svg.setAttribute("stroke-linejoin", "round");
    svg.setAttribute("aria-hidden", "true");

    path.setAttribute(
      "d",
      "M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 1 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8z"
    );
    svg.appendChild(path);
    return svg;
  }

  /** Shared control: a real button, outside the title link, with a live label. */
  function createFavoriteButton(property) {
    var id = cleanText(property && property.id);
    var title = cleanText(property && property.title) || "this property";

    var button = el("button", "ev-favorite");
    button.type = "button";
    button.hidden = true;
    button.setAttribute("data-ev-favorite", "");
    button.setAttribute("data-property-id", id);
    button.setAttribute("data-property-title", title);
    button.setAttribute("aria-pressed", "false");
    button.setAttribute("aria-label", "Save " + title + " to favorites");
    button.setAttribute("title", "Save to favorites");
    button.appendChild(heartIcon(false));
    /* Cards can be rendered after the session has already resolved. Apply the
       current auth/cooldown view at creation time so an anonymous sign-in
       prompt is visible without waiting for an unrelated later notification. */
    if (FAVORITES && FAVORITES.getSnapshot) {
      syncFavoriteButton(button, FAVORITES.getSnapshot());
    }
    return button;
  }

  function announceFavorite(message) {
    if (!message) {
      return;
    }
    var region = document.querySelector("[data-favorite-status]");

    if (!region) {
      region = el("div", "visually-hidden");
      region.setAttribute("data-favorite-status", "");
      region.setAttribute("role", "status");
      region.setAttribute("aria-live", "polite");
      if (document.body) {
        document.body.appendChild(region);
      }
    }
    region.textContent = message;
  }

  function syncFavoriteButton(button, view) {
    if (!button || !view) {
      return;
    }
    var id = cleanText(button.getAttribute("data-property-id"));
    var title = cleanText(button.getAttribute("data-property-title")) || "this property";
    var authenticated = view.authStatus === "authenticated";
    var buyer = view.buyer === true;

    if (!id || (authenticated && !buyer) || view.authStatus === "unknown") {
      button.hidden = true;
      button.disabled = true;
      return;
    }

    button.hidden = false;
    var pressed = buyer && FAVORITES && FAVORITES.isFavorite(id);
    var pending = buyer && FAVORITES && FAVORITES.isPending(id);
    var blocked = buyer && view.cooldownActive;

    button.setAttribute("aria-pressed", pressed ? "true" : "false");
    button.setAttribute("aria-busy", pending ? "true" : "false");
    button.disabled = Boolean(pending || blocked || (buyer && !FAVORITES));

    var label;
    if (!buyer) {
      label = "Sign in to save " + title;
    } else if (pressed) {
      label = "Remove " + title + " from favorites";
    } else {
      label = "Save " + title + " to favorites";
    }
    button.setAttribute("aria-label", label);
    button.setAttribute("title", blocked ? "Saving paused during request cooldown" : label);
    button.replaceChildren(heartIcon(pressed === true));
  }

  function syncFavoriteControls(view) {
    if (!view) {
      return;
    }
    var nodes = document.querySelectorAll("[data-ev-favorite]");

    Array.prototype.forEach.call(nodes, function (button) {
      syncFavoriteButton(button, view);
    });
  }

  function redirectToLogin() {
    var login = new URL("login.html", window.location.href);
    var intended =
      window.location.pathname + window.location.search + window.location.hash;
    login.searchParams.set("next", intended);
    window.location.assign(login.href);
  }

  function handleFavoriteClick(event) {
    var button = event.target && event.target.closest
      ? event.target.closest("[data-ev-favorite]")
      : null;

    if (!button) {
      return;
    }
    /* The card title is a stretched link; the favorite control must never
       navigate to the property detail or submit a surrounding form. */
    event.preventDefault();
    event.stopPropagation();
    if (button.disabled) {
      return;
    }

    var id = cleanText(button.getAttribute("data-property-id"));
    var title = cleanText(button.getAttribute("data-property-title")) || "this property";

    if (!FAVORITES) {
      return;
    }
    if (!FAVORITES.canMutate() && !FAVORITES.isPending(id)) {
      if (FAVORITES.getCooldown && FAVORITES.getCooldown().active()) {
        announceFavorite("Favorite updates are paused while the request limit resets.");
        return;
      }
      redirectToLogin();
      return;
    }

    var wasSaved = FAVORITES.isFavorite(id);
    var mutationGeneration = FAVORITES.getSnapshot
      ? FAVORITES.getSnapshot().generation
      : null;
    var action = wasSaved ? FAVORITES.removeFavorite(id) : FAVORITES.addFavorite(id);
    announceFavorite(wasSaved ? "Removing " + title + " from favorites…" : "Saving " + title + " to favorites…");

    Promise.resolve(action)
      .then(function (result) {
        var currentGeneration = FAVORITES.getSnapshot
          ? FAVORITES.getSnapshot().generation
          : mutationGeneration;
        var sameSession = mutationGeneration === null ||
          (result && result.generation === mutationGeneration &&
            currentGeneration === mutationGeneration);
        if (!sameSession || (result && result.reason === "stale")) {
          announceFavorite("Favorite update was interrupted.");
          return;
        }
        var operation = result && result.action === "removed"
          ? "removed"
          : result && result.action === "added"
            ? "added"
            : wasSaved ? "removed" : "added";
        var message = result && result.ok
          ? (operation === "removed"
              ? "Removed " + title + " from favorites."
              : "Saved " + title + " to favorites.")
          : FAVORITES.describeFavoriteFailure(result);
        announceFavorite(message);
        document.dispatchEvent(new CustomEvent("ev-favorite-mutation", {
          detail: {
            propertyId: id,
            action: operation,
            ok: Boolean(result && result.ok),
            state: result ? result.state : null
          }
        }));
      })
      .catch(function () {
        announceFavorite("We could not update your favorites. Please try again.");
      });
  }

  function watchFavorites() {
    if (favoritesWatched) {
      return;
    }
    favoritesWatched = true;

    if (window.EVFavorites) {
      FAVORITES = window.EVFavorites;
      FAVORITES.subscribe(syncFavoriteControls);
    } else {
      window.addEventListener("ev-favorites-ready", function onReady() {
        FAVORITES = window.EVFavorites;
        if (FAVORITES) {
          FAVORITES.subscribe(syncFavoriteControls);
        }
      }, { once: true });
    }
    document.addEventListener("click", handleFavoriteClick);
  }

  /**
   * Builds the standard property card.
   *
   * options:
   *   columnClass  Bootstrap column classes for the wrapper
   *   headingTag   heading element name, defaults to h3
   *   imageWidth   intrinsic width hint
   *   imageHeight  intrinsic height hint
   *   ctaLabel     visible CTA text
   *   lazy         whether the image should lazy-load
   *   showFavorite set false only for non-buyer surfaces
   */
  function buildCard(property, options) {
    options = options || {};

    var title = cleanText(property.title) || "Untitled property";
    var id = cleanText(property.id);
    var href = "property-details.html" + (id ? "?id=" + encodeURIComponent(id) : "");
    var imagePath = primaryImage(property);
    var hasImage = imagePath !== PLACEHOLDER_IMAGE;
    var headingTag = options.headingTag || "h3";
    var ctaLabel = options.ctaLabel || "View details";

    var column = el("div", options.columnClass || "col");
    column.setAttribute("data-id", id);

    var card = el("article", "card-estate");
    var media = el("div", "card-estate__media");
    var image = document.createElement("img");

    image.src = imagePath;
    image.alt = hasImage ? "Photograph of " + title : "Property photograph unavailable";
    image.width = options.imageWidth || 400;
    image.height = options.imageHeight || 300;
    image.loading = options.lazy === false ? "eager" : "lazy";
    image.decoding = "async";
    image.addEventListener("error", function onImageError() {
      image.removeEventListener("error", onImageError);
      image.src = PLACEHOLDER_IMAGE;
      image.alt = "Property photograph unavailable";
    });

    media.appendChild(image);

    var badge = statusBadge(property.status);

    if (badge) {
      media.appendChild(
        el("span", "ev-badge card-estate__badge " + badge.modifier, badge.label)
      );
    }

    if (options.showFavorite !== false && id) {
      media.appendChild(
        createFavoriteButton({ id: id, title: options.favoriteTitle || title })
      );
    }

    card.appendChild(media);

    var body = el("div", "card-estate__body");
    body.appendChild(el("p", "card-estate__price", formatPrice(property.price)));

    var heading = el(headingTag, "card-estate__title");
    var link = el("a", null, title);
    link.href = href;
    heading.appendChild(link);
    body.appendChild(heading);

    var location = locationLabel(property);

    if (location) {
      body.appendChild(el("p", "card-estate__location", location));
    }

    var metaParts = [];
    var type = displayTypeLabel(property);
    var listingType = listingTypeLabel(property.listingType);
    var beds = countLabel(property.bedrooms, "bedroom");
    var baths = countLabel(property.bathrooms, "bathroom");
    var area = formatArea(property.areaSqft);

    if (listingType) {
      metaParts.push(listingType);
    }
    if (beds) {
      metaParts.push(beds);
    }
    if (baths) {
      metaParts.push(baths);
    }
    if (area) {
      metaParts.push(area);
    }

    if (metaParts.length > 0) {
      body.appendChild(el("p", "card-estate__meta", metaParts.join(" \u00B7 ")));
    }

    var footer = el("div", "card-estate__footer");

    if (type) {
      footer.appendChild(el("span", "ev-badge ev-badge--neutral", type));
    }

    var cta = el("span", "card-estate__cta");
    cta.setAttribute("aria-hidden", "true");
    cta.appendChild(document.createTextNode(ctaLabel));
    cta.appendChild(arrowIcon());
    footer.appendChild(cta);

    body.appendChild(footer);
    card.appendChild(body);
    column.appendChild(card);

    return column;
  }

  /** Reusable entry point for future live-property rails outside the listings page. */
  function buildCardFromApiSummary(summary, options) {
    var model = fromApiSummary(summary);
    return model ? buildCard(model, options) : null;
  }

  /**
   * Retained favorite for a property that is no longer public. Only the
   * approved generic placeholder and the saved identifier are used; no title,
   * price, description, address, image, agent or lifecycle status is exposed.
   * The favorite button remains available so the buyer can remove the row.
   */
  function buildUnavailableFavoriteCard(item, options) {
    options = options || {};
    var id = cleanText(item && item.propertyId);
    var property = item && item.property;
    var supplied = property && typeof property === "object"
      ? cleanText(property.placeholder)
      : "";
    /* Accept only the approved backend placeholder string. */
    var label = supplied === UNAVAILABLE_FAVORITE ? supplied : UNAVAILABLE_FAVORITE;
    var column = el("div", options.columnClass || "col");
    column.setAttribute("data-id", id);

    var card = el("article", "card-estate card-estate--unavailable");
    var media = el("div", "card-estate__media");
    var image = document.createElement("img");

    image.src = PLACEHOLDER_IMAGE;
    image.alt = "Property photograph unavailable";
    image.width = options.imageWidth || 400;
    image.height = options.imageHeight || 300;
    image.loading = options.lazy === false ? "eager" : "lazy";
    image.decoding = "async";
    media.appendChild(image);

    if (id) {
      media.appendChild(createFavoriteButton({ id: id, title: label }));
    }
    card.appendChild(media);

    var body = el("div", "card-estate__body");
    body.appendChild(el("p", "ev-favorite-unavailable", label));
    body.appendChild(el("h3", "card-estate__title", label));
    body.appendChild(el("p", "card-estate__location", "This saved property is not currently listed."));

    var footer = el("div", "card-estate__footer");
    footer.appendChild(el("span", "ev-badge ev-badge--neutral", "Saved"));
    body.appendChild(footer);

    card.appendChild(body);
    column.appendChild(card);
    return column;
  }

  /** True only for the approved restricted favorite placeholder object. */
  function isUnavailableFavorite(item) {
    if (!item || typeof item !== "object") {
      return false;
    }
    var property = item.property;
    return Boolean(
      !property ||
        typeof property !== "object" ||
        (cleanText(property.placeholder) === UNAVAILABLE_FAVORITE && !property.id)
    );
  }

  watchFavorites();

  window.EVPropertyCard = {
    PLACEHOLDER_IMAGE: PLACEHOLDER_IMAGE,
    TYPE_LABELS: TYPE_LABELS,
    STATUS_BADGES: STATUS_BADGES,
    buildCard: buildCard,
    cleanText: cleanText,
    formatPrice: formatPrice,
    formatArea: formatArea,
    countLabel: countLabel,
    typeLabel: typeLabel,
    displayTypeLabel: displayTypeLabel,
    listingTypeLabel: listingTypeLabel,
    humanizeEnum: humanizeEnum,
    statusBadge: statusBadge,
    normalizeApiType: normalizeApiType,
    fromApiSummary: fromApiSummary,
    buildCardFromApiSummary: buildCardFromApiSummary,
    createFavoriteButton: createFavoriteButton,
    buildUnavailableFavoriteCard: buildUnavailableFavoriteCard,
    isUnavailableFavorite: isUnavailableFavorite,
    syncFavoriteControls: syncFavoriteControls,
    locationLabel: locationLabel,
    primaryImage: primaryImage,
    resolveImagePath: resolveImagePath
  };
})();
