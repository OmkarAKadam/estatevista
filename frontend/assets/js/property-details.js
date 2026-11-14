/**
 * EstateVista — property details behaviour (Stage 4).
 *
 * Resolves ?id=<property-id> against window.EV_PROPERTIES, then renders the
 * listing, a selectable image gallery and related properties. The shared card
 * renderer from property-card.js is reused for the related rail.
 */
(function () {
  "use strict";

  var CARD = window.EVPropertyCard;

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

  function joinSentence(parts) {
    if (parts.length === 0) {
      return "";
    }

    if (parts.length === 1) {
      return parts[0];
    }

    if (parts.length === 2) {
      return parts[0] + " and " + parts[1];
    }

    return parts.slice(0, -1).join(", ") + " and " + parts[parts.length - 1];
  }

  /** Reads ?id= and returns the matching record, or null. */
  function findProperty() {
    var params = new URLSearchParams(window.location.search);
    var rawId = params.get("id");
    var id = rawId ? rawId.trim() : "";
    var collection = Array.isArray(window.EV_PROPERTIES) ? window.EV_PROPERTIES : [];

    if (!id) {
      return null;
    }

    for (var index = 0; index < collection.length; index += 1) {
      var property = collection[index];

      if (property && typeof property.id === "string" && property.id === id) {
        return property;
      }
    }

    return null;
  }

  function imagePaths(property) {
    var raw = Array.isArray(property.images) ? property.images : [];
    var seen = [];
    var paths = [];

    raw.forEach(function (value) {
      var path = CARD.resolveImagePath(value);

      if (seen.indexOf(path) === -1) {
        seen.push(path);
        paths.push(path);
      }
    });

    return paths.length > 0 ? paths : [CARD.PLACEHOLDER_IMAGE];
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
    var paths = imagePaths(property);
    var thumbs = byId("detailThumbs");
    var caption = byId("detailImageCaption");
    var buttons = [];

    if (!thumbs) {
      return;
    }

    thumbs.innerHTML = "";
    thumbs.hidden = false;

    function imageAlt(index) {
      if (paths[index] === CARD.PLACEHOLDER_IMAGE) {
        return "Property photograph unavailable";
      }

      if (paths.length === 1) {
        return "Photograph of " + title;
      }

      return "Photograph of " + title + ", image " + (index + 1) + " of " + paths.length;
    }

    function captionFor(index) {
      if (paths[index] === CARD.PLACEHOLDER_IMAGE) {
        return "No photograph is available for this fictional listing.";
      }

      return (
        "Image " + (index + 1) + " of " + paths.length +
        " \u00B7 Demonstration photograph for this fictional listing."
      );
    }

    function selectImage(index) {
      setMainImage(paths[index], imageAlt(index));

      if (caption) {
        caption.textContent = captionFor(index);
      }

      buttons.forEach(function (button, buttonIndex) {
        button.setAttribute("aria-pressed", buttonIndex === index ? "true" : "false");
      });
    }

    paths.forEach(function (path, index) {
      var button = el("button", "property-gallery__thumb");
      var thumbImage = document.createElement("img");

      button.type = "button";
      button.setAttribute("aria-pressed", index === 0 ? "true" : "false");
      button.setAttribute(
        "aria-label",
        "Show image " + (index + 1) + " of " + paths.length +
          (path === CARD.PLACEHOLDER_IMAGE ? " (placeholder image)" : "")
      );

      thumbImage.src = path;
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

    thumbs.addEventListener("keydown", function (event) {
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
    });

    selectImage(0);
  }

  function renderFacts(property) {
    var facts = byId("detailFacts");
    var type = CARD.typeLabel(property.type) || text(property.type);
    var beds = CARD.countLabel(property.bedrooms, "bedroom");
    var baths = CARD.countLabel(property.bathrooms, "bathroom");
    var area = CARD.formatArea(property.areaSqft);
    var items = [
      { label: "Property type", value: type },
      { label: "Bedrooms", value: beds },
      { label: "Bathrooms", value: baths },
      { label: "Built-up area", value: area }
    ];

    if (!facts) {
      return;
    }

    facts.innerHTML = "";

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
    var type = CARD.typeLabel(property.type) || text(property.type);
    var status = CARD.statusBadge(property.status);
    var statusLabel = status ? status.label : text(property.status);
    var items = [
      { label: "Property ID", value: text(property.id) },
      { label: "Listing type", value: type },
      { label: "Status", value: statusLabel },
      { label: "Bedrooms", value: CARD.countLabel(property.bedrooms, "bedroom") },
      { label: "Bathrooms", value: CARD.countLabel(property.bathrooms, "bathroom") },
      { label: "Built-up area", value: CARD.formatArea(property.areaSqft) },
      { label: "Locality", value: text(property.locality) },
      { label: "City", value: text(property.city) }
    ];

    if (!specs) {
      return;
    }

    specs.innerHTML = "";

    items.forEach(function (item) {
      if (!item.value) {
        return;
      }

      specs.appendChild(el("dt", null, item.label));
      specs.appendChild(el("dd", null, item.value));
    });
  }

  function renderDescription(property) {
    var title = text(property.title) || "This property";
    var type = CARD.typeLabel(property.type);
    var location = CARD.locationLabel(property);
    var details = [];
    var beds = CARD.countLabel(property.bedrooms, "bedroom");
    var baths = CARD.countLabel(property.bathrooms, "bathroom");
    var area = CARD.formatArea(property.areaSqft);
    var sentences = [];

    if (beds) {
      details.push(beds);
    }
    if (baths) {
      details.push(baths);
    }
    if (area) {
      details.push(area + " of built-up area");
    }

    sentences.push(
      title +
        " is a " +
        (type ? type.toLowerCase() : "residential") +
        " property" +
        (location ? " in " + location : "") +
        "."
    );

    if (details.length > 0) {
      sentences.push("The listing records " + joinSentence(details) + ".");
    }

    sentences.push(
      "This description is generated from the fields available in the demonstration dataset. No further property claims have been added, and the listing describes no real home."
    );

    setText("detailDescription", sentences.join(" "));
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
      "Amenity details are not included in this demonstration dataset. The specifications above show every field available for this fictional listing."
    );
  }

  function renderBadges(property) {
    var container = byId("detailBadges");
    var type = CARD.typeLabel(property.type);
    var status = CARD.statusBadge(property.status);

    if (!container) {
      return;
    }

    container.innerHTML = "";

    if (type) {
      container.appendChild(el("span", "ev-badge ev-badge--neutral", type));
    }

    if (status) {
      container.appendChild(
        el("span", "ev-badge " + status.modifier, status.label)
      );
    }
  }

  function renderRelated(property) {
    var section = byId("relatedSection");
    var grid = byId("relatedGrid");
    var collection = Array.isArray(window.EV_PROPERTIES) ? window.EV_PROPERTIES : [];
    var currentId = text(property.id);
    var currentType = text(property.type).toLowerCase();
    var others = collection.filter(function (item) {
      return item && text(item.id) !== currentId;
    });
    var sameType = others.filter(function (item) {
      return currentType !== "" && text(item.type).toLowerCase() === currentType;
    });
    var remaining = others.filter(function (item) {
      return sameType.indexOf(item) === -1;
    });
    var related = sameType.concat(remaining).slice(0, 3);

    if (!section || !grid) {
      return;
    }

    if (related.length === 0) {
      section.hidden = true;
      return;
    }

    grid.innerHTML = "";

    var fragment = document.createDocumentFragment();

    related.forEach(function (item) {
      fragment.appendChild(CARD.buildCard(item, { columnClass: "col", headingTag: "h3" }));
    });

    grid.appendChild(fragment);
    section.hidden = false;
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
          ", a fictional EstateVista property listing."
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
    }

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
    renderRelated(property);
    updateMeta(property);
  }

  function showError(title, message) {
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
    }

    setText("breadcrumbCurrent", title);
    setText("propertyErrorTitle", title);
    setText("propertyErrorMessage", message);
    document.title = title + " \u2014 EstateVista";

    if (description) {
      description.setAttribute(
        "content",
        "The requested EstateVista property listing could not be found."
      );
    }
  }

  function init() {
    if (!CARD) {
      showError(
        "Property details unavailable",
        "The shared property components did not load, so this listing cannot be displayed. Return to the properties page to browse the collection."
      );
      return;
    }

    var property = findProperty();

    if (!property) {
      showError(
        "Property not found",
        "The property you requested is not part of the EstateVista collection. It may have been removed, or the link may be incomplete."
      );
      return;
    }

    render(property);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
