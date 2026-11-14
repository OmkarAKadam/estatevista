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
  function resolveImagePath(rawValue) {
    var raw = cleanText(rawValue);

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
    cta.appendChild(document.createTextNode(ctaLabel));
    cta.appendChild(arrowIcon());
    footer.appendChild(cta);

    body.appendChild(footer);
    card.appendChild(body);
    column.appendChild(card);

    return column;
  }

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
    statusBadge: statusBadge,
    locationLabel: locationLabel,
    primaryImage: primaryImage,
    resolveImagePath: resolveImagePath
  };
})();
