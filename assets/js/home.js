/**
 * EstateVista — homepage behaviour (Stage 2).
 *
 * Scope is deliberately narrow:
 *   1. Render featured property cards from window.EV_PROPERTIES.
 *   2. Turn the homepage search form into a clean query string.
 *
 * Filtering, sorting and listing rendering belong to Stage 3.
 */
(function () {
  "use strict";

  var PLACEHOLDER_IMAGE = "assets/images/brand/property-placeholder.svg";
  var FEATURED_LIMIT = 3;
  var CARD_IMAGE_WIDTH = 800;
  var CARD_IMAGE_HEIGHT = 600;

  var TYPE_LABELS = {
    apartment: "Apartment",
    villa: "Villa",
    rowhouse: "Row house",
    penthouse: "Penthouse"
  };

  var STATUS_BADGES = {
    available: { label: "Available", modifier: "ev-badge--available" },
    reserved: { label: "Reserved", modifier: "ev-badge--reserved" },
    sold: { label: "Sold", modifier: "ev-badge--sold" }
  };

  /* ---------------------------------------------------------------- helpers */

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

  /** Formats whole rupees the way Indian listings usually do: ₹4.85 Cr / ₹95 L. */
  function formatPrice(value) {
    if (!isPositiveNumber(value)) {
      return "Price on request";
    }

    if (value >= 10000000) {
      return "₹" + parseFloat((value / 10000000).toFixed(2)) + " Cr";
    }

    if (value >= 100000) {
      return "₹" + parseFloat((value / 100000).toFixed(2)) + " L";
    }

    return "₹" + value.toLocaleString("en-IN");
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

  function statusBadge(value) {
    var key = cleanText(value).toLowerCase();
    return STATUS_BADGES[key] || null;
  }

  function locationLabel(property) {
    return [cleanText(property.locality), cleanText(property.city)]
      .filter(function (part) {
        return part !== "";
      })
      .join(", ");
  }

  function primaryImage(property) {
    var images = property.images;

    if (Array.isArray(images) && images.length > 0 && cleanText(images[0]) !== "") {
      return cleanText(images[0]);
    }

    return PLACEHOLDER_IMAGE;
  }

  function hasImage(property) {
    return primaryImage(property) !== PLACEHOLDER_IMAGE;
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

  /* ------------------------------------------------------------ card render */

  function buildCard(property) {
    var title = cleanText(property.title) || "Untitled property";
    var id = cleanText(property.id);
    var href = "property-details.html" + (id ? "?id=" + encodeURIComponent(id) : "");

    var column = el("div", "col-md-6 col-lg-4");
    var card = el("article", "card-estate");

    var media = el("div", "card-estate__media");
    var image = document.createElement("img");

    image.src = primaryImage(property);
    image.alt = hasImage(property)
      ? "Photograph of " + title
      : "Property photograph unavailable";
    image.width = CARD_IMAGE_WIDTH;
    image.height = CARD_IMAGE_HEIGHT;
    image.loading = "lazy";
    image.decoding = "async";

    media.appendChild(image);

    var badge = statusBadge(property.status);

    if (badge) {
      media.appendChild(
        el("span", "ev-badge card-estate__badge " + badge.modifier, badge.label)
      );
    }

    card.appendChild(media);

    var body = el("div", "card-estate__body");
    body.appendChild(el("p", "card-estate__price", formatPrice(property.price)));

    var heading = el("h3", "card-estate__title");
    var link = el("a", null, title);
    link.href = href;
    heading.appendChild(link);
    body.appendChild(heading);

    var location = locationLabel(property);

    if (location) {
      body.appendChild(el("p", "card-estate__location", location));
    }

    var metaParts = [];
    var type = typeLabel(property.type);
    var beds = countLabel(property.bedrooms, "bed");
    var baths = countLabel(property.bathrooms, "bath");
    var area = formatArea(property.areaSqft);

    if (type) {
      metaParts.push(type);
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
      body.appendChild(el("p", "card-estate__meta", metaParts.join(" · ")));
    }

    var footer = el("div", "card-estate__footer");

    if (type) {
      footer.appendChild(el("span", "ev-badge ev-badge--neutral", type));
    }

    var cta = el("span", "card-estate__cta");
    cta.setAttribute("aria-hidden", "true");
    cta.appendChild(document.createTextNode("View details"));
    cta.appendChild(arrowIcon());
    footer.appendChild(cta);

    body.appendChild(footer);
    card.appendChild(body);
    column.appendChild(card);

    return column;
  }

  function renderFeatured() {
    var grid = document.getElementById("featuredGrid");
    var emptyState = document.getElementById("featuredEmpty");

    if (!grid) {
      return;
    }

    var collection = Array.isArray(window.EV_PROPERTIES) ? window.EV_PROPERTIES : [];
    var featured = collection.filter(function (property) {
      return property && property.featured === true;
    });

    // Never fabricate listings: fall back to the dataset, then to the empty state.
    if (featured.length === 0) {
      featured = collection.filter(Boolean);
    }

    featured = featured.slice(0, FEATURED_LIMIT);

    if (featured.length === 0) {
      grid.hidden = true;

      if (emptyState) {
        emptyState.hidden = false;
      }

      return;
    }

    var fragment = document.createDocumentFragment();

    featured.forEach(function (property) {
      fragment.appendChild(buildCard(property));
    });

    grid.appendChild(fragment);

    if (emptyState) {
      emptyState.hidden = true;
    }
  }

  /* ---------------------------------------------------------------- search */

  function initSearch() {
    var form = document.getElementById("homeSearch");

    if (!form) {
      return;
    }

    form.addEventListener("submit", function (event) {
      var locationField = document.getElementById("searchLocation");
      var typeField = document.getElementById("searchType");
      var budgetField = document.getElementById("searchBudget");

      // If the controls are missing, let the browser submit natively.
      if (!locationField || !typeField || !budgetField) {
        return;
      }

      event.preventDefault();

      var params = new URLSearchParams();
      var keyword = cleanText(locationField.value).slice(0, 80);

      if (keyword) {
        params.set("q", keyword);
      }
      if (typeField.value) {
        params.set("type", typeField.value);
      }
      if (budgetField.value) {
        params.set("maxPrice", budgetField.value);
      }

      // An empty search is not an error: it simply opens the full collection.
      var target = form.getAttribute("action") || "properties.html";
      var query = params.toString();

      window.location.assign(query ? target + "?" + query : target);
    });
  }

  function init() {
    renderFeatured();
    initSearch();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
