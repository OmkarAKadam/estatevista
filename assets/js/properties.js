/**
 * EstateVista — property listing behaviour (Stage 3).
 *
 * Reads the shared dataset from window.EV_PROPERTIES (assets/js/data.js),
 * restores q/type/minPrice/maxPrice/sort from the URL, filters and sorts the
 * collection, then renders the property grid and the empty-results state.
 */
(function () {
  "use strict";

  /* The card renderer is shared with property-details.html (related listings),
     so the listing page must not carry its own copy of the markup. */
  var CARD = window.EVPropertyCard;

  if (!CARD) {
    return;
  }

  var TYPE_LABELS = CARD.TYPE_LABELS;
  var cleanText = CARD.cleanText;
  var formatPrice = CARD.formatPrice;

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

  function buildCard(property) {
    return CARD.buildCard(property, { columnClass: "col" });
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
