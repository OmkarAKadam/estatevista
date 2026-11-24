/**
 * EstateVista — server-side property listing behaviour (Stage 5.3).
 *
 * All discovery work is performed by GET /api/v1/properties. This page only:
 * validates and serialises criteria, synchronises shareable URL state, renders
 * the approved PagedResponse cards, and manages request lifecycle/UI states.
 * It never fetches all properties, filters, sorts, or counts locally.
 */
(function () {
  "use strict";

  /* The card renderer is shared with property-details.html (related listings). */
  var CARD = window.EVPropertyCard;

  if (!CARD) {
    return;
  }

  /* Same canonical UUID shape accepted by the public API bridge and backend.
     Kept local so URL validation also works before the module bridge loads. */
  var UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

  var PAGE_SIZE = 12;
  var MAX_KEYWORD_LENGTH = 100;
  var MAX_AMENITY_IDS = 20;
  var MAX_BEDROOMS = 32767;
  var MAX_PAGE = 10000;
  var KEYWORD_DEBOUNCE_MS = 350;
  var API_READY_TIMEOUT_MS = 5000;

  var SORTS = ["newest", "oldest", "price_asc", "price_desc"];
  var SORT_LABELS = {
    newest: "Newest first",
    oldest: "Oldest first",
    price_asc: "Price: low to high",
    price_desc: "Price: high to low"
  };
  var LEGACY_SORTS = {
    "": "newest",
    "price-asc": "price_asc",
    "price-desc": "price_desc"
  };
  var LEGACY_TYPE_SYNONYMS = {
    apartment: "apartment",
    villa: "villa",
    rowhouse: "rowhouse",
    "row house": "rowhouse"
  };

  var elements = {};
  var api = null;
  var references = {
    categories: [],
    subtypes: [],
    amenities: [],
    loaded: false,
    failed: false,
    promise: null
  };
  var state = defaultState();
  var lastMeta = null;
  var activeController = null;
  var requestSequence = 0;
  var keywordTimer = null;
  var pendingWarning = "";

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

  function defaultState() {
    return {
      keyword: "",
      categoryId: "",
      subtypeId: "",
      listingType: "",
      minPrice: null,
      maxPrice: null,
      city: "",
      locality: "",
      minBedrooms: null,
      maxBedrooms: null,
      amenityIds: [],
      sort: "newest",
      page: 0
    };
  }

  function clean(value, maximum) {
    if (typeof value !== "string") {
      return "";
    }
    var text = value
      .replace(/[\u0000-\u001f\u007f]+/g, " ")
      .replace(/\s+/g, " ")
      .trim();
    return typeof maximum === "number" ? text.slice(0, maximum) : text;
  }

  function isValidUuid(value) {
    return typeof value === "string" && UUID_PATTERN.test(value.trim());
  }

  function addWarning(message) {
    if (!message) {
      return;
    }
    pendingWarning = pendingWarning ? pendingWarning + " " + message : message;
  }

  function renderFilterNotice() {
    if (!elements.filterNotice) {
      return;
    }
    elements.filterNotice.textContent = pendingWarning;
    elements.filterNotice.hidden = pendingWarning === "";
  }

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

  /* ------------------------------------------------------------- URL state */

  function readTextParam(params, name, maximum, warnings) {
    var raw = params.get(name);
    if (raw === null) {
      return "";
    }
    var text = clean(raw, maximum);
    if (clean(raw).length > text.length) {
      warnings.push(name + " was shortened to the approved limit.");
    }
    return text;
  }

  function readDecimalParam(params, name, warnings) {
    var raw = params.get(name);
    if (raw === null) {
      return null;
    }
    var value = clean(raw, 40);
    if (!value) {
      warnings.push(name + " was ignored because it was blank.");
      return null;
    }
    var number = Number(value);
    if (!isFinite(number) || number < 0) {
      warnings.push(name + " was ignored because it was invalid.");
      return null;
    }
    return number;
  }

  function readIntegerParam(params, name, maximum, warnings) {
    var raw = params.get(name);
    if (raw === null) {
      return null;
    }
    var value = clean(raw, 20);
    if (!value) {
      warnings.push(name + " was ignored because it was blank.");
      return null;
    }
    var number = Number(value);
    if (!Number.isInteger(number) || number < 0 || (maximum && number > maximum)) {
      warnings.push(name + " was ignored because it was invalid.");
      return null;
    }
    return number;
  }

  function readUrlState() {
    var params = new URLSearchParams(window.location.search);
    var warnings = [];
    var next = defaultState();

    next.keyword = readTextParam(params, "keyword", MAX_KEYWORD_LENGTH, warnings);
    if (!next.keyword) {
      next.keyword = readTextParam(params, "q", MAX_KEYWORD_LENGTH, warnings);
    }

    next.categoryId = clean(params.get("categoryId"), 50);
    next.subtypeId = clean(params.get("subtypeId"), 50);
    if (next.categoryId && !isValidUuid(next.categoryId)) {
      warnings.push("categoryId was ignored because it was not a valid identifier.");
      next.categoryId = "";
    }
    if (next.subtypeId && !isValidUuid(next.subtypeId)) {
      warnings.push("subtypeId was ignored because it was not a valid identifier.");
      next.subtypeId = "";
    }

    var listingType = clean(params.get("listingType"), 20).toUpperCase();
    if (listingType === "SALE" || listingType === "RENT") {
      next.listingType = listingType;
    } else if (listingType) {
      warnings.push("listingType was ignored because it was not SALE or RENT.");
    }

    next.minPrice = readDecimalParam(params, "minPrice", warnings);
    next.maxPrice = readDecimalParam(params, "maxPrice", warnings);
    if (next.minPrice !== null && next.maxPrice !== null && next.minPrice > next.maxPrice) {
      warnings.push("The saved price range was invalid and was removed.");
      next.minPrice = null;
      next.maxPrice = null;
    }

    next.city = readTextParam(params, "city", 100, warnings);
    next.locality = readTextParam(params, "locality", 120, warnings);

    next.minBedrooms = readIntegerParam(params, "minBedrooms", MAX_BEDROOMS, warnings);
    next.maxBedrooms = readIntegerParam(params, "maxBedrooms", MAX_BEDROOMS, warnings);
    if (
      next.minBedrooms !== null &&
      next.maxBedrooms !== null &&
      next.minBedrooms > next.maxBedrooms
    ) {
      warnings.push("The saved bedroom range was invalid and was removed.");
      next.minBedrooms = null;
      next.maxBedrooms = null;
    }

    var candidates = [];
    params.getAll("amenityIds").forEach(function (raw) {
      clean(raw, 50)
        .split(",")
        .forEach(function (candidate) {
          if (candidate) {
            candidates.push(candidate);
          }
        });
    });
    var seen = {};
    candidates.forEach(function (candidate) {
      if (
        next.amenityIds.length < MAX_AMENITY_IDS &&
        isValidUuid(candidate) &&
        !seen[candidate]
      ) {
        seen[candidate] = true;
        next.amenityIds.push(candidate);
      }
    });
    if (candidates.length > MAX_AMENITY_IDS) {
      warnings.push("Only the first 20 saved amenities were kept.");
    } else if (candidates.length > next.amenityIds.length) {
      warnings.push("Some saved amenity identifiers were invalid and were removed.");
    }

    var rawSort = clean(params.get("sort"), 20);
    if (SORT_LABELS[rawSort]) {
      next.sort = rawSort;
    } else if (LEGACY_SORTS[rawSort]) {
      next.sort = LEGACY_SORTS[rawSort];
    } else if (rawSort) {
      warnings.push("The saved sort order was invalid and was reset to newest.");
    }

    var page = readIntegerParam(params, "page", undefined, warnings);
    if (page !== null) {
      if (page > MAX_PAGE) {
        warnings.push("The saved page number was too large and was reset.");
        next.page = 0;
      } else {
        next.page = page;
      }
    }

    /* V1-compatible links from the homepage and footer. */
    next._legacyType = clean(params.get("type"), 40).toLowerCase();

    warnings.forEach(addWarning);
    return next;
  }

  function canonicalParams() {
    var params = new URLSearchParams();
    if (state.keyword) {
      params.set("keyword", state.keyword);
    }
    if (state.categoryId) {
      params.set("categoryId", state.categoryId);
    }
    if (state.subtypeId) {
      params.set("subtypeId", state.subtypeId);
    }
    if (state.listingType) {
      params.set("listingType", state.listingType);
    }
    if (state.minPrice !== null) {
      params.set("minPrice", String(state.minPrice));
    }
    if (state.maxPrice !== null) {
      params.set("maxPrice", String(state.maxPrice));
    }
    if (state.city) {
      params.set("city", state.city);
    }
    if (state.locality) {
      params.set("locality", state.locality);
    }
    if (state.minBedrooms !== null) {
      params.set("minBedrooms", String(state.minBedrooms));
    }
    if (state.maxBedrooms !== null) {
      params.set("maxBedrooms", String(state.maxBedrooms));
    }
    state.amenityIds.forEach(function (id) {
      params.append("amenityIds", id);
    });
    params.set("sort", state.sort);
    params.set("page", String(state.page));
    return params;
  }

  function writeUrl(mode) {
    var query = canonicalParams().toString();
    var url = window.location.pathname + (query ? "?" + query : "") + window.location.hash;
    window.history[mode](null, "", url);
  }

  /* ---------------------------------------------------------- reference data */

  function normalizeReferences(items) {
    if (!Array.isArray(items)) {
      return [];
    }
    return items
      .filter(function (item) {
        return (
          item &&
          typeof item === "object" &&
          isValidUuid(item.id) &&
          typeof item.name === "string" &&
          clean(item.name, 120)
        );
      })
      .map(function (item) {
        return {
          id: item.id.trim(),
          name: clean(item.name, 120),
          categoryId: isValidUuid(item.categoryId) ? item.categoryId.trim() : ""
        };
      });
  }

  function loadReferences() {
    if (references.promise) {
      return references.promise;
    }

    references.promise = Promise.all([
      api.listPropertyCategories(),
      api.listPropertySubtypes(),
      api.listAmenities()
    ])
      .then(function (results) {
        references.categories = normalizeReferences(results[0]);
        references.subtypes = normalizeReferences(results[1]);
        references.amenities = normalizeReferences(results[2]);
        references.loaded = true;
        references.failed = false;
      })
      .catch(function () {
        references.categories = [];
        references.subtypes = [];
        references.amenities = [];
        references.loaded = true;
        references.failed = true;
        addWarning(
          "Category, subtype and amenity lists are unavailable; those filters were cleared. Other filters still work."
        );
      });

    return references.promise;
  }

  function findReference(list, id) {
    if (!id) {
      return null;
    }
    for (var index = 0; index < list.length; index += 1) {
      if (list[index].id === id) {
        return list[index];
      }
    }
    return null;
  }

  function normalizedName(value) {
    return clean(value, 120).toLowerCase().replace(/[^a-z0-9]/g, "");
  }

  function applyReferencesToState() {
    var legacyType = state._legacyType;
    delete state._legacyType;

    if (references.failed) {
      if (state.categoryId || state.subtypeId || state.amenityIds.length > 0 || legacyType) {
        addWarning("Saved reference-based filters were cleared because the reference lists are unavailable.");
      }
      state.categoryId = "";
      state.subtypeId = "";
      state.amenityIds = [];
      return;
    }
    if (!references.loaded) {
      state._legacyType = legacyType;
      return;
    }

    if (state.categoryId && !findReference(references.categories, state.categoryId)) {
      addWarning("The saved category was not recognised and was removed.");
      state.categoryId = "";
    }

    if (state.subtypeId) {
      var subtype = findReference(references.subtypes, state.subtypeId);
      if (!subtype) {
        addWarning("The saved subtype was not recognised and was removed.");
        state.subtypeId = "";
      } else if (state.categoryId && subtype.categoryId !== state.categoryId) {
        addWarning("The saved subtype does not belong to the saved category and was removed.");
        state.subtypeId = "";
      }
    }

    var before = state.amenityIds.length;
    state.amenityIds = state.amenityIds.filter(function (id) {
      return Boolean(findReference(references.amenities, id));
    });
    if (state.amenityIds.length < before) {
      addWarning("Saved amenities that are no longer available were removed.");
    }

    if (legacyType && !state.subtypeId) {
      var wanted = LEGACY_TYPE_SYNONYMS[legacyType] || legacyType;
      for (var index = 0; index < references.subtypes.length; index += 1) {
        var candidate = references.subtypes[index];
        if (normalizedName(candidate.name) === normalizedName(wanted)) {
          state.subtypeId = candidate.id;
          if (!state.categoryId && candidate.categoryId) {
            state.categoryId = candidate.categoryId;
          }
          break;
        }
      }
    }
  }

  /* ---------------------------------------------------------- form controls */

  function appendOption(select, text, value) {
    var option = new Option(text, value);
    select.appendChild(option);
    return option;
  }

  function unavailableOption(select, label) {
    var option = appendOption(select, label, "");
    option.disabled = true;
    select.value = "";
  }

  function renderCategories() {
    var select = elements.category;
    select.replaceChildren();
    appendOption(select, "All categories", "");

    if (!references.loaded) {
      unavailableOption(select, "Loading categories…");
    } else if (references.failed) {
      unavailableOption(select, "Categories unavailable");
    } else {
      references.categories.forEach(function (item) {
        appendOption(select, item.name, item.id);
      });
    }

    select.value = findReference(references.categories, state.categoryId) ? state.categoryId : "";
  }

  function renderSubtypes(categoryId) {
    var select = elements.subtype;
    var selectedCategory = categoryId === undefined ? state.categoryId : categoryId;
    select.replaceChildren();
    appendOption(select, "All subtypes", "");

    if (!references.loaded) {
      unavailableOption(select, "Loading subtypes…");
    } else if (references.failed) {
      unavailableOption(select, "Subtypes unavailable");
    } else {
      references.subtypes
        .filter(function (item) {
          return !selectedCategory || item.categoryId === selectedCategory;
        })
        .forEach(function (item) {
          appendOption(select, item.name, item.id);
        });
    }

    var wanted = state.subtypeId;
    select.value = wanted && Array.from(select.options).some(function (option) {
      return option.value === wanted;
    }) ? wanted : "";
  }

  function enforceAmenityCap() {
    if (!elements.amenities) {
      return;
    }
    var inputs = Array.from(
      elements.amenities.querySelectorAll('input[name="amenityIds"]')
    );
    var atCap = inputs.filter(function (input) {
      return input.checked;
    }).length >= MAX_AMENITY_IDS;
    inputs.forEach(function (input) {
      if (!input.checked) {
        input.disabled = atCap;
      }
    });
  }

  function renderAmenities() {
    var container = elements.amenities;
    container.replaceChildren();

    if (!references.loaded) {
      container.appendChild(el("span", "text-muted small", "Loading amenities…"));
      return;
    }
    if (references.failed) {
      container.appendChild(el("span", "text-muted small", "Amenities unavailable."));
      return;
    }
    if (references.amenities.length === 0) {
      container.appendChild(el("span", "text-muted small", "No amenities are available."));
      return;
    }

    references.amenities.forEach(function (item) {
      var label = el("label", "ev-amenity-filter__option");
      var checkbox = document.createElement("input");
      checkbox.type = "checkbox";
      checkbox.name = "amenityIds";
      checkbox.value = item.id;
      checkbox.checked = state.amenityIds.indexOf(item.id) !== -1;
      checkbox.addEventListener("change", function () {
        if (checkbox.checked) {
          var checked = Array.from(
            container.querySelectorAll('input[name="amenityIds"]:checked')
          );
          if (checked.length > MAX_AMENITY_IDS) {
            checkbox.checked = false;
            pendingWarning = "Select no more than 20 amenities.";
            renderFilterNotice();
          }
        }
        enforceAmenityCap();
        applyFormChanges();
      });
      label.appendChild(checkbox);
      label.appendChild(el("span", null, item.name));
      container.appendChild(label);
    });

    enforceAmenityCap();
  }

  function setField(id, value) {
    if (elements[id]) {
      elements[id].value = value === null || value === undefined ? "" : String(value);
    }
  }

  function populateForm() {
    renderCategories();
    renderSubtypes(state.categoryId);
    renderAmenities();

    setField("keyword", state.keyword);
    setField("listingType", state.listingType);
    setField("sort", SORTS.indexOf(state.sort) === -1 ? "newest" : state.sort);
    setField("minPrice", state.minPrice);
    setField("maxPrice", state.maxPrice);
    setField("city", state.city);
    setField("locality", state.locality);
    setField("minBedrooms", state.minBedrooms);
    setField("maxBedrooms", state.maxBedrooms);
  }

  function renderSummary() {
    var summary = elements.summary;
    var list = elements.summaryList;
    if (!summary || !list) {
      return;
    }
    list.replaceChildren();

    var items = [];
    if (state.keyword) {
      items.push({ label: "Keyword", value: state.keyword });
    }
    var category = findReference(references.categories, state.categoryId);
    if (category) {
      items.push({ label: "Category", value: category.name });
    }
    var subtype = findReference(references.subtypes, state.subtypeId);
    if (subtype) {
      items.push({ label: "Subtype", value: subtype.name });
    }
    if (state.listingType) {
      items.push({ label: "Listing", value: CARD.listingTypeLabel(state.listingType) });
    }
    if (state.minPrice !== null) {
      items.push({ label: "Min price", value: CARD.formatPrice(state.minPrice) });
    }
    if (state.maxPrice !== null) {
      items.push({ label: "Max price", value: CARD.formatPrice(state.maxPrice) });
    }
    if (state.city) {
      items.push({ label: "City", value: state.city });
    }
    if (state.locality) {
      items.push({ label: "Locality", value: state.locality });
    }
    if (state.minBedrooms !== null) {
      items.push({ label: "Min bedrooms", value: String(state.minBedrooms) });
    }
    if (state.maxBedrooms !== null) {
      items.push({ label: "Max bedrooms", value: String(state.maxBedrooms) });
    }
    if (state.amenityIds.length > 0) {
      var names = state.amenityIds
        .map(function (id) {
          var item = findReference(references.amenities, id);
          return item ? item.name : null;
        })
        .filter(Boolean);
      var amenityText = names.slice(0, 2).join(", ");
      if (names.length > 2) {
        amenityText += " +" + (names.length - 2) + " more";
      }
      if (amenityText) {
        items.push({ label: "Amenities", value: amenityText });
      }
    }
    if (state.sort !== "newest") {
      items.push({ label: "Sort", value: SORT_LABELS[state.sort] });
    }

    items.forEach(function (item) {
      list.appendChild(el("dt", null, item.label));
      list.appendChild(el("dd", null, item.value));
    });
    summary.hidden = items.length === 0;
  }

  function readNumberField(field, label, integer, maximum) {
    var raw = clean(field ? field.value : "", 40);
    if (!raw) {
      return { value: null, error: "" };
    }
    var number = Number(raw);
    if (
      !isFinite(number) ||
      number < 0 ||
      (integer && !Number.isInteger(number)) ||
      (maximum && number > maximum)
    ) {
      return { value: null, error: label + " must be a valid non-negative number." };
    }
    return { value: number, error: "" };
  }

  function valuesFromForm() {
    var errors = [];
    var warnings = [];

    var keyword = clean(elements.keyword.value, MAX_KEYWORD_LENGTH);
    if (clean(elements.keyword.value).length > keyword.length) {
      warnings.push("Keyword was shortened to 100 characters.");
    }

    var minPrice = readNumberField(elements.minPrice, "Minimum price", false);
    var maxPrice = readNumberField(elements.maxPrice, "Maximum price", false);
    var minBedrooms = readNumberField(elements.minBedrooms, "Minimum bedrooms", true, MAX_BEDROOMS);
    var maxBedrooms = readNumberField(elements.maxBedrooms, "Maximum bedrooms", true, MAX_BEDROOMS);

    [minPrice, maxPrice, minBedrooms, maxBedrooms].forEach(function (result) {
      if (result.error) {
        errors.push(result.error);
      }
    });

    if (
      !errors.length &&
      minPrice.value !== null &&
      maxPrice.value !== null &&
      minPrice.value > maxPrice.value
    ) {
      errors.push("Minimum price must not exceed maximum price.");
    }
    if (
      !errors.length &&
      minBedrooms.value !== null &&
      maxBedrooms.value !== null &&
      minBedrooms.value > maxBedrooms.value
    ) {
      errors.push("Minimum bedrooms must not exceed maximum bedrooms.");
    }

    var city = clean(elements.city.value, 100);
    var locality = clean(elements.locality.value, 120);
    var listingType = clean(elements.listingType.value, 20).toUpperCase();
    var sort = clean(elements.sort.value, 20);
    if (SORTS.indexOf(sort) === -1) {
      sort = "newest";
    }

    var amenityIds = Array.from(
      elements.amenities.querySelectorAll('input[name="amenityIds"]:checked')
    ).map(function (input) {
      return input.value;
    });

    if (errors.length) {
      return { errors: errors, warnings: warnings, values: null };
    }
    return {
      errors: [],
      warnings: warnings,
      values: {
        keyword: keyword,
        categoryId: clean(elements.category.value, 50),
        subtypeId: clean(elements.subtype.value, 50),
        listingType: listingType === "SALE" || listingType === "RENT" ? listingType : "",
        minPrice: minPrice.value,
        maxPrice: maxPrice.value,
        city: city,
        locality: locality,
        minBedrooms: minBedrooms.value,
        maxBedrooms: maxBedrooms.value,
        amenityIds: amenityIds.slice(0, MAX_AMENITY_IDS),
        sort: sort
      }
    };
  }

  function cancelKeywordTimer() {
    if (keywordTimer !== null) {
      window.clearTimeout(keywordTimer);
      keywordTimer = null;
    }
  }

  function applyFormChanges() {
    cancelKeywordTimer();
    var parsed = valuesFromForm();
    pendingWarning = parsed.warnings.concat(parsed.errors).join(" ");
    renderFilterNotice();
    if (!parsed.values) {
      return;
    }

    var next = defaultState();
    Object.keys(parsed.values).forEach(function (key) {
      next[key] = parsed.values[key];
    });
    next.page = 0;
    state = next;
    renderSummary();
    load({ historyMode: "pushState" });
  }

  /* ---------------------------------------------------------- request/render */

  function setLoading() {
    elements.loading.hidden = false;
    elements.error.hidden = true;
    elements.empty.hidden = true;
    elements.grid.hidden = true;
    elements.grid.setAttribute("aria-busy", "true");
    elements.count.textContent = "Loading published properties…";
  }

  function showError(message) {
    elements.loading.hidden = true;
    elements.error.hidden = false;
    elements.empty.hidden = true;
    elements.grid.hidden = true;
    elements.grid.setAttribute("aria-busy", "false");
    elements.pagination.hidden = true;
    elements.errorMessage.textContent = message;
    elements.count.textContent = "Property listings unavailable";
  }

  function renderCount(meta, visible) {
    var total = meta.totalElements;
    if (total === 0 || visible === 0) {
      elements.count.textContent = "No published properties found";
      return;
    }
    var first = meta.page * meta.size + 1;
    var last = Math.min(first + visible - 1, total);
    elements.count.textContent =
      "Showing " +
      first +
      "\u2013" +
      last +
      " of " +
      total +
      (total === 1 ? " published property" : " published properties");
  }

  function renderCards(items) {
    var fragment = document.createDocumentFragment();
    var rendered = 0;

    items.forEach(function (item) {
      var card = CARD.buildCardFromApiSummary(item, { columnClass: "col" });
      if (card) {
        fragment.appendChild(card);
        rendered += 1;
      }
    });

    elements.grid.replaceChildren(fragment);
    return rendered;
  }

  function addPageButton(list, label, target, options) {
    var item = el("li", "page-item" + (options.current ? " active" : ""));
    var button = el("button", "page-link", label);
    button.type = "button";

    if (options.ariaLabel) {
      button.setAttribute("aria-label", options.ariaLabel);
    }
    if (options.current) {
      button.setAttribute("aria-current", "page");
    }
    if (options.disabled) {
      item.classList.add("disabled");
      button.disabled = true;
    } else {
      button.addEventListener("click", function () {
        goToPage(target);
      });
    }
    item.appendChild(button);
    list.appendChild(item);
  }

  function addEllipsis(list) {
    var item = el("li", "page-item disabled");
    var span = el("span", "page-link text-muted", "\u2026");
    span.setAttribute("aria-hidden", "true");
    item.appendChild(span);
    list.appendChild(item);
  }

  function renderPagination(meta) {
    var list = elements.paginationList;
    list.replaceChildren();

    if (meta.totalPages <= 1) {
      elements.pagination.hidden = true;
      return;
    }
    elements.pagination.hidden = false;

    var current = meta.page;
    addPageButton(list, "Previous", current - 1, {
      disabled: !meta.hasPrevious || current <= 0,
      ariaLabel: "Previous page"
    });

    var seen = {};
    var pages = [];
    function addPage(page) {
      if (page >= 0 && page < meta.totalPages && !seen[page]) {
        seen[page] = true;
        pages.push(page);
      }
    }
    addPage(0);
    addPage(meta.totalPages - 1);
    for (var offset = -2; offset <= 2; offset += 1) {
      addPage(current + offset);
    }
    pages.sort(function (a, b) {
      return a - b;
    });

    pages.forEach(function (page, index) {
      if (index > 0 && page - pages[index - 1] > 1) {
        addEllipsis(list);
      }
      addPageButton(list, String(page + 1), page, {
        current: page === current,
        ariaLabel: "Go to page " + (page + 1)
      });
    });

    addPageButton(list, "Next", current + 1, {
      disabled: !meta.hasNext || current >= meta.totalPages - 1,
      ariaLabel: "Next page"
    });
  }

  function goToPage(page) {
    if (
      !Number.isInteger(page) ||
      page < 0 ||
      (lastMeta && page >= lastMeta.totalPages)
    ) {
      return;
    }
    state.page = page;
    load({ historyMode: "pushState" });
  }

  function handleResult(result, sequence) {
    if (sequence !== requestSequence) {
      return;
    }

    var meta = result && result.meta;
    if (!meta || !Number.isInteger(meta.totalElements) || !Number.isInteger(meta.totalPages)) {
      showError("The property API returned an unexpected response. Please try again.");
      return;
    }
    lastMeta = meta;

    if (meta.totalElements > 0 && meta.totalPages > 0 && state.page >= meta.totalPages) {
      state.page = meta.totalPages - 1;
      writeUrl("replaceState");
      load();
      return;
    }
    if (meta.totalElements === 0 && state.page !== 0) {
      state.page = 0;
      writeUrl("replaceState");
      load();
      return;
    }

    var visible = renderCards(Array.isArray(result.items) ? result.items : []);
    elements.grid.setAttribute("aria-busy", "false");
    elements.loading.hidden = true;
    elements.error.hidden = true;

    if (meta.totalElements === 0) {
      elements.grid.hidden = true;
      elements.empty.hidden = false;
      elements.pagination.hidden = true;
      renderCount(meta, 0);
      return;
    }
    if (visible === 0) {
      showError("Results were returned but could not be displayed. Please try again.");
      return;
    }

    elements.grid.hidden = false;
    elements.empty.hidden = true;
    renderCount(meta, visible);
    renderPagination(meta);
  }

  function load(options) {
    options = options || {};
    if (options.historyMode) {
      writeUrl(options.historyMode);
    }

    var sequence = ++requestSequence;
    if (activeController) {
      activeController.abort();
    }
    var controller = new AbortController();
    activeController = controller;
    setLoading();

    if (!api) {
      showError("Property services are unavailable. Please try again.");
      return;
    }

    var criteria = {
      keyword: state.keyword,
      categoryId: state.categoryId,
      subtypeId: state.subtypeId,
      listingType: state.listingType,
      minPrice: state.minPrice,
      maxPrice: state.maxPrice,
      city: state.city,
      locality: state.locality,
      minBedrooms: state.minBedrooms,
      maxBedrooms: state.maxBedrooms,
      amenityIds: state.amenityIds.slice(),
      sort: state.sort,
      page: state.page,
      size: PAGE_SIZE
    };

    api.listPublishedProperties(criteria, { signal: controller.signal })
      .then(function (result) {
        handleResult(result, sequence);
      })
      .catch(function (error) {
        if (!error || error.name === "AbortError" || sequence !== requestSequence) {
          return;
        }
        showError(
          error && error.code === "NETWORK_ERROR"
            ? "The property service could not be reached. Please retry."
            : "Property listings could not be loaded. Check your filters and retry."
        );
      });
  }

  /* -------------------------------------------------------------- lifecycle */

  function resetAll() {
    cancelKeywordTimer();
    pendingWarning = "";
    renderFilterNotice();
    state = defaultState();
    lastMeta = null;
    populateForm();
    renderSummary();
    load({ historyMode: "pushState" });
  }

  function prepareAndLoad() {
    return loadReferences()
      .then(function () {
        applyReferencesToState();
        renderFilterNotice();
        populateForm();
        renderSummary();
        writeUrl("replaceState");
        load();
      });
  }

  function start() {
    waitForPublicApi()
      .then(function (client) {
        api = client;
        return prepareAndLoad();
      })
      .catch(function () {
        showError("Property services are unavailable. Please try again.");
      });
  }

  function retry() {
    if (!api) {
      start();
      return;
    }
    if (references.failed) {
      references.promise = null;
      references.loaded = false;
      references.failed = false;
      prepareAndLoad();
      return;
    }
    load();
  }

  function wireForm() {
    elements.form.addEventListener("submit", function (event) {
      event.preventDefault();
      applyFormChanges();
    });

    elements.keyword.addEventListener("input", function () {
      cancelKeywordTimer();
      keywordTimer = window.setTimeout(applyFormChanges, KEYWORD_DEBOUNCE_MS);
    });

    [
      elements.listingType,
      elements.sort,
      elements.minPrice,
      elements.maxPrice,
      elements.city,
      elements.locality,
      elements.minBedrooms,
      elements.maxBedrooms
    ].forEach(function (field) {
      if (field) {
        field.addEventListener("change", applyFormChanges);
      }
    });

    elements.category.addEventListener("change", function () {
      renderSubtypes(clean(elements.category.value, 50));
      applyFormChanges();
    });
    elements.subtype.addEventListener("change", applyFormChanges);

    [elements.clear, elements.clearSummary, elements.resetEmpty].forEach(function (button) {
      if (button) {
        button.addEventListener("click", resetAll);
      }
    });

    elements.retry.addEventListener("click", retry);

    window.addEventListener("popstate", function () {
      cancelKeywordTimer();
      pendingWarning = "";
      state = readUrlState();
      if (!references.loaded) {
        start();
        return;
      }
      applyReferencesToState();
      renderFilterNotice();
      populateForm();
      renderSummary();
      load();
    });
  }

  function init() {
    elements = {
      form: document.getElementById("propertyFilters"),
      keyword: document.getElementById("filterSearch"),
      category: document.getElementById("filterCategory"),
      subtype: document.getElementById("filterSubtype"),
      listingType: document.getElementById("filterListingType"),
      sort: document.getElementById("filterSort"),
      minPrice: document.getElementById("filterMinPrice"),
      maxPrice: document.getElementById("filterMaxPrice"),
      city: document.getElementById("filterCity"),
      locality: document.getElementById("filterLocality"),
      minBedrooms: document.getElementById("filterMinBedrooms"),
      maxBedrooms: document.getElementById("filterMaxBedrooms"),
      amenities: document.getElementById("filterAmenities"),
      filterNotice: document.getElementById("filterNotice"),
      clear: document.getElementById("clearFilters"),
      clearSummary: document.getElementById("clearSummaryFilters"),
      resetEmpty: document.getElementById("resetEmptyFilters"),
      summary: document.getElementById("searchSummary"),
      summaryList: document.getElementById("searchSummaryList"),
      count: document.getElementById("resultsCount"),
      loading: document.getElementById("loadingState"),
      error: document.getElementById("errorState"),
      errorMessage: document.getElementById("errorMessage"),
      retry: document.getElementById("retryLoad"),
      grid: document.getElementById("propertyGrid"),
      empty: document.getElementById("emptyResults"),
      pagination: document.getElementById("propertyPagination"),
      paginationList: document.getElementById("paginationList")
    };

    if (!elements.form || !elements.grid || !elements.keyword) {
      return;
    }

    pendingWarning = "";
    state = readUrlState();
    renderFilterNotice();
    populateForm();
    renderSummary();
    wireForm();
    start();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
