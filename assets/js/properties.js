/**
 * EstateVista — property listing behaviour (Stage 3).
 *
 * Reads the shared dataset from window.EV_PROPERTIES (assets/js/data.js),
 * restores q/type/minPrice/maxPrice/sort from the URL, filters and sorts the
 * collection, then renders the property grid and the empty-results state.
 */
(function () {
  "use strict";

  var PLACEHOLDER_IMAGE = "assets/images/brand/property-placeholder.svg";
  var CARD_IMAGE_WIDTH = 400;
  var CARD_IMAGE_HEIGHT = 300;

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

  /**
   * data.js stores root-relative paths such as
   * "assets/images/properties/sunlit-courtyard-villa-01.webp".
   * Bare filenames are still accepted for backwards compatibility.
   */
  function primaryImage(property) {
    var images = property.images;

    if (!Array.isArray(images) || images.length === 0) {
      return PLACEHOLDER_IMAGE;
    }

    var raw = cleanText(images[0]);

    if (raw === "") {
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

  function parsePrice(value) {
    if (value === null || value === undefined) {
      return null;
    }

    if (typeof value === "string") {
      value = value.trim();

      if (value === "") {
        return null;
      }
    }

    var number = Number(value);
    return isFinite(number) && number >= 0 ? number : null;
  }

  /* ------------------------------------------------------------- criteria */

  function readCriteria() {
    var params = new URLSearchParams(window.location.search);
    var type = cleanText(params.get("type")).toLowerCase();
    var sort = params.get("sort");

    return {
      q: cleanText(params.get("q")).slice(0, 80),
      type: TYPE_LABELS[type] ? type : "",
      minPrice: parsePrice(params.get("minPrice")),
      maxPrice: parsePrice(params.get("maxPrice")),
      sort: sort === "price-asc" || sort === "price-desc" ? sort : ""
    };
  }

  function hasActiveCriteria(criteria) {
    return Boolean(
      criteria.q ||
        criteria.type ||
        criteria.minPrice !== null ||
        criteria.maxPrice !== null ||
        criteria.sort
    );
  }

  function matches(property, criteria) {
    if (criteria.q) {
      var haystack = [
        cleanText(property.title),
        cleanText(property.locality),
        cleanText(property.city)
      ]
        .join(" ")
        .toLowerCase();

      if (haystack.indexOf(criteria.q.toLowerCase()) === -1) {
        return false;
      }
    }

    if (criteria.type && cleanText(property.type).toLowerCase() !== criteria.type) {
      return false;
    }

    var price = typeof property.price === "number" ? property.price : 0;

    if (criteria.minPrice !== null && price < criteria.minPrice) {
      return false;
    }

    if (criteria.maxPrice !== null && price > criteria.maxPrice) {
      return false;
    }

    return true;
  }

  function applyCriteria(collection, criteria) {
    var results = collection.filter(function (property) {
      return property && matches(property, criteria);
    });

    if (criteria.sort === "price-asc") {
      results.sort(function (a, b) {
        return (a.price || 0) - (b.price || 0);
      });
    } else if (criteria.sort === "price-desc") {
      results.sort(function (a, b) {
        return (b.price || 0) - (a.price || 0);
      });
    }

    return results;
  }

  /* ------------------------------------------------------------ card render */

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

  function buildCard(property) {
    var title = cleanText(property.title) || "Untitled property";
    var id = cleanText(property.id);
    var href = "property-details.html" + (id ? "?id=" + encodeURIComponent(id) : "");
    var imagePath = primaryImage(property);
    var hasImage = imagePath !== PLACEHOLDER_IMAGE;

    var column = el("div", "col");
    column.setAttribute("data-id", id);

    var card = el("article", "card-estate");
    var media = el("div", "card-estate__media");
    var image = document.createElement("img");

    image.src = imagePath;
    image.alt = hasImage ? "Photograph of " + title : "Property photograph unavailable";
    image.width = CARD_IMAGE_WIDTH;
    image.height = CARD_IMAGE_HEIGHT;
    image.loading = "lazy";
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
    var beds = countLabel(property.bedrooms, "bedroom");
    var baths = countLabel(property.bathrooms, "bathroom");
    var area = formatArea(property.areaSqft);

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
    cta.appendChild(document.createTextNode("View details"));
    cta.appendChild(arrowIcon());
    footer.appendChild(cta);

    body.appendChild(footer);
    card.appendChild(body);
    column.appendChild(card);

    return column;
  }

  /* --------------------------------------------------------------- render */

  function renderResults(results) {
    var grid = document.getElementById("propertyGrid");
    var emptyState = document.getElementById("emptyResults");
    var count = document.getElementById("resultsCount");

    if (!grid) {
      return;
    }

    grid.innerHTML = "";

    if (results.length > 0) {
      grid.hidden = false;

      if (emptyState) {
        emptyState.hidden = true;
      }

      var fragment = document.createDocumentFragment();

      results.forEach(function (property) {
        fragment.appendChild(buildCard(property));
      });

      grid.appendChild(fragment);
    } else {
      grid.hidden = true;

      if (emptyState) {
        emptyState.hidden = false;
      }
    }

    if (count) {
      count.textContent =
        results.length +
        (results.length === 1 ? " property found" : " properties found");
    }
  }

  function renderSummary(criteria) {
    var summary = document.getElementById("searchSummary");
    var list = document.getElementById("searchSummaryList");

    if (!summary || !list) {
      return;
    }

    list.innerHTML = "";

    if (!hasActiveCriteria(criteria)) {
      summary.hidden = true;
      return;
    }

    var items = [];

    if (criteria.q) {
      items.push({ label: "Keyword", value: criteria.q });
    }
    if (criteria.type) {
      items.push({ label: "Property type", value: TYPE_LABELS[criteria.type] });
    }
    if (criteria.minPrice !== null) {
      items.push({ label: "Min price", value: formatPrice(criteria.minPrice) });
    }
    if (criteria.maxPrice !== null) {
      items.push({ label: "Max price", value: formatPrice(criteria.maxPrice) });
    }
    if (criteria.sort === "price-asc") {
      items.push({ label: "Sort", value: "Price: low to high" });
    } else if (criteria.sort === "price-desc") {
      items.push({ label: "Sort", value: "Price: high to low" });
    }

    var fragment = document.createDocumentFragment();

    items.forEach(function (item) {
      fragment.appendChild(el("dt", null, item.label));
      fragment.appendChild(el("dd", null, item.value));
    });

    list.appendChild(fragment);
    summary.hidden = false;
  }

  function setFieldValue(id, value) {
    var field = document.getElementById(id);

    if (field) {
      field.value = value;
    }
  }

  function populateForm(criteria) {
    setFieldValue("filterSearch", criteria.q);
    setFieldValue("filterType", criteria.type);
    setFieldValue("filterMinPrice", criteria.minPrice === null ? "" : String(criteria.minPrice));
    setFieldValue("filterMaxPrice", criteria.maxPrice === null ? "" : String(criteria.maxPrice));
    setFieldValue("filterSort", criteria.sort);
  }

  /* ------------------------------------------------------------------ form */

  function wireForm() {
    var form = document.getElementById("propertyFilters");

    if (!form) {
      return;
    }

    form.addEventListener("submit", function (event) {
      event.preventDefault();

      var search = document.getElementById("filterSearch");
      var typeField = document.getElementById("filterType");
      var minField = document.getElementById("filterMinPrice");
      var maxField = document.getElementById("filterMaxPrice");
      var sortField = document.getElementById("filterSort");

      var q = cleanText(search ? search.value : "").slice(0, 80);
      var type = cleanText(typeField ? typeField.value : "").toLowerCase();
      var minPrice = parsePrice(minField ? minField.value : null);
      var maxPrice = parsePrice(maxField ? maxField.value : null);
      var sort = sortField ? sortField.value : "";

      if (!TYPE_LABELS[type]) {
        type = "";
      }
      if (sort !== "price-asc" && sort !== "price-desc") {
        sort = "";
      }

      var params = new URLSearchParams();

      if (q) {
        params.set("q", q);
      }
      if (type) {
        params.set("type", type);
      }
      if (minPrice !== null) {
        params.set("minPrice", String(minPrice));
      }
      if (maxPrice !== null) {
        params.set("maxPrice", String(maxPrice));
      }
      if (sort) {
        params.set("sort", sort);
      }

      var query = params.toString();
      window.location.assign("properties.html" + (query ? "?" + query : ""));
    });

    var clear = document.getElementById("clearFilters");

    if (clear) {
      clear.addEventListener("click", function () {
        window.location.assign("properties.html");
      });
    }
  }

  /* ------------------------------------------------------------------ init */

  function init() {
    var grid = document.getElementById("propertyGrid");

    if (!grid) {
      return;
    }

    var collection = Array.isArray(window.EV_PROPERTIES)
      ? window.EV_PROPERTIES.filter(Boolean)
      : [];
    var criteria = readCriteria();

    populateForm(criteria);
    renderResults(applyCriteria(collection, criteria));
    renderSummary(criteria);
    wireForm();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
