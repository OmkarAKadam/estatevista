/**
 * EstateVista — properties page (Stage 2 placeholder).
 *
 * Stage 2 only needs to prove that the homepage search carried its criteria
 * across. The listing grid, filtering and sorting arrive in Stage 3.
 */
(function () {
  "use strict";

  var PARAMETER_LABELS = {
    q: "Keyword",
    type: "Property type",
    maxPrice: "Budget"
  };

  var TYPE_LABELS = {
    apartment: "Apartment",
    villa: "Villa",
    rowhouse: "Row house",
    penthouse: "Penthouse"
  };

  function formatBudget(value) {
    var amount = Number(value);

    if (!isFinite(amount) || amount <= 0) {
      return null;
    }

    if (amount >= 10000000) {
      return "Up to ₹" + parseFloat((amount / 10000000).toFixed(2)) + " Cr";
    }

    return "Up to ₹" + parseFloat((amount / 100000).toFixed(2)) + " L";
  }

  function describe(key, value) {
    if (!value) {
      return null;
    }

    if (key === "type") {
      return TYPE_LABELS[value.toLowerCase()] || value;
    }

    if (key === "maxPrice") {
      return formatBudget(value);
    }

    return value;
  }

  function init() {
    var summary = document.getElementById("searchSummary");
    var list = document.getElementById("searchSummaryList");

    if (!summary || !list) {
      return;
    }

    var params = new URLSearchParams(window.location.search);
    var items = [];

    Object.keys(PARAMETER_LABELS).forEach(function (key) {
      var description = describe(key, (params.get(key) || "").trim());

      if (description) {
        items.push({ label: PARAMETER_LABELS[key], value: description });
      }
    });

    if (items.length === 0) {
      summary.hidden = true;
      return;
    }

    var fragment = document.createDocumentFragment();

    items.forEach(function (item) {
      var term = document.createElement("dt");
      term.textContent = item.label;

      var detail = document.createElement("dd");
      detail.textContent = item.value;

      fragment.appendChild(term);
      fragment.appendChild(detail);
    });

    list.appendChild(fragment);
    summary.hidden = false;
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
