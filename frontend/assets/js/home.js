/**
 * EstateVista — homepage behaviour (Stage 5.5).
 *
 * The property rail is a live PUBLIC search result:
 * GET /api/v1/properties?sort=newest&page=0&size=3
 *
 * Cards are rendered by the shared window.EVPropertyCard renderer. The page
 * never selects, filters, sorts, or fabricates properties locally, and it never
 * consults demonstration data. Only this section is affected by API failures;
 * the rest of the homepage remains usable.
 */
(function () {
  "use strict";

  var CARD = window.EVPropertyCard;
  var HOME_PAGE_SIZE = 3;
  var API_READY_TIMEOUT_MS = 5000;

  var grid = null;
  var loading = null;
  var empty = null;
  var errorPanel = null;
  var errorMessage = null;
  var retry = null;
  var api = null;
  var activeController = null;
  var requestSequence = 0;

  /* ---------------------------------------------------------------- helpers */

  function byId(id) {
    return document.getElementById(id);
  }

  function clean(value) {
    return typeof value === "string" ? value.replace(/\s+/g, " ").trim() : "";
  }

  function setText(node, value) {
    if (node) {
      node.textContent = value;
    }
  }

  function hide(node) {
    if (node) {
      node.hidden = true;
    }
  }

  function show(node) {
    if (node) {
      node.hidden = false;
    }
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

  /* ------------------------------------------------------------ live results */

  function setLoading() {
    hide(grid);
    hide(empty);
    hide(errorPanel);
    show(loading);
    if (grid) {
      grid.setAttribute("aria-busy", "true");
    }
  }

  function setEmpty() {
    hide(loading);
    hide(errorPanel);
    show(empty);
    hide(grid);
    if (grid) {
      grid.setAttribute("aria-busy", "false");
    }
  }

  function setError(message) {
    hide(loading);
    hide(empty);
    hide(grid);
    show(errorPanel);
    setText(errorMessage, message);
    if (grid) {
      grid.setAttribute("aria-busy", "false");
    }
  }

  function renderItems(items) {
    var fragment = document.createDocumentFragment();
    var rendered = 0;

    items.forEach(function (item) {
      var card = CARD.buildCardFromApiSummary(item, {
        columnClass: "col-md-6 col-lg-4",
        imageWidth: 800,
        imageHeight: 600
      });
      if (card) {
        fragment.appendChild(card);
        rendered += 1;
      }
    });

    if (rendered === 0) {
      return false;
    }

    grid.replaceChildren(fragment);
    hide(loading);
    hide(empty);
    hide(errorPanel);
    show(grid);
    grid.setAttribute("aria-busy", "false");
    return true;
  }

  function load() {
    if (!CARD || !grid) {
      return;
    }

    var sequence = ++requestSequence;
    if (activeController) {
      activeController.abort();
    }
    var controller = new AbortController();
    activeController = controller;

    setLoading();

    if (!api) {
      setError("Published properties could not be loaded. Please try again.");
      return;
    }

    /* Exact approved query: no client-side paging, sorting, or substitution. */
    api
      .listPublishedProperties(
        { sort: "newest", page: 0, size: HOME_PAGE_SIZE },
        { signal: controller.signal }
      )
      .then(function (result) {
        if (sequence !== requestSequence) {
          return;
        }
        var items = Array.isArray(result && result.items) ? result.items : [];

        if (items.length === 0) {
          setEmpty();
          return;
        }
        if (!renderItems(items)) {
          setError("Published properties were returned but could not be displayed. Please try again.");
        }
      })
      .catch(function (error) {
        if (sequence !== requestSequence || (error && error.name === "AbortError")) {
          return;
        }
        setError(
          error && error.code === "NETWORK_ERROR"
            ? "The property service could not be reached. Please retry."
            : "Published properties could not be loaded. Please try again."
        );
      });
  }

  function start() {
    waitForPublicApi()
      .then(function (client) {
        api = client;
        load();
      })
      .catch(function () {
        setError("Property services are unavailable. Please try again.");
      });
  }

  /* ------------------------------------------------------------------ search */

  function initSearch() {
    var form = byId("homeSearch");

    if (!form) {
      return;
    }

    form.addEventListener("submit", function (event) {
      var locationField = byId("searchLocation");
      var typeField = byId("searchType");
      var budgetField = byId("searchBudget");

      // If the controls are missing, let the browser submit natively.
      if (!locationField || !typeField || !budgetField) {
        return;
      }

      event.preventDefault();

      var params = new URLSearchParams();
      var keyword = clean(locationField.value);

      if (keyword) {
        params.set("keyword", keyword);
      }
      // Legacy `type` is resolved against approved subtype reference data by
      // the listings page; no fictional identifier is hardcoded here.
      if (typeField.value) {
        params.set("type", typeField.value);
      }
      if (budgetField.value) {
        params.set("maxPrice", budgetField.value);
      }

      var target = form.getAttribute("action") || "properties.html";
      var query = params.toString();

      window.location.assign(query ? target + "?" + query : target);
    });
  }

  /* -------------------------------------------------------------- lifecycle */

  function init() {
    grid = byId("latestGrid");
    loading = byId("latestLoading");
    empty = byId("latestEmpty");
    errorPanel = byId("latestError");
    errorMessage = byId("latestErrorMessage");
    retry = byId("retryLatest");

    if (retry) {
      retry.addEventListener("click", function () {
        if (!api) {
          start();
          return;
        }
        load();
      });
    }

    if (!grid || !CARD) {
      setError("Property components are unavailable. Please try again later.");
      return;
    }

    initSearch();
    start();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
