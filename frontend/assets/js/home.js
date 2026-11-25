/**
 * EstateVista — homepage behaviour (Stages 5.5 and 5.6).
 *
 * The property rail is a live PUBLIC search result:
 * GET /api/v1/properties?sort=newest&page=0&size=3
 *
 * Cards are rendered by the shared window.EVPropertyCard renderer. The page
 * never selects, filters, sorts, or fabricates properties locally, and it never
 * consults demonstration data. Only this section is affected by API failures;
 * the rest of the homepage remains usable.
 *
 * Stage 5.6 adds decorative skeletons, shared failure classification and a
 * bounded rate-limit cooldown without changing the approved public query.
 */
(function () {
  "use strict";

  var CARD = window.EVPropertyCard;
  var SKELETONS = window.EVSkeletons;
  var HOME_PAGE_SIZE = 3;
  var API_READY_TIMEOUT_MS = 5000;

  var grid = null;
  var loading = null;
  var skeletonHost = null;
  var empty = null;
  var errorPanel = null;
  var errorMessage = null;
  var retry = null;
  var api = null;
  var favoritesController = null;
  var cooldown = null;
  var lastErrorWasRateLimit = false;
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

  function isRateLimit(error) {
    return Boolean(error && (error.reason === "rate_limit" || error.status === 429));
  }

  function isCancelled(error) {
    if (api && typeof api.isCancellation === "function") {
      return api.isCancellation(error);
    }
    return Boolean(error && error.name === "AbortError");
  }

  function describe(error) {
    if (api && typeof api.describeApiFailure === "function") {
      return api.describeApiFailure(error);
    }
    if (error && error.code === "NETWORK_ERROR") {
      return "The property service could not be reached. Please retry.";
    }
    return "Published properties could not be loaded. Please try again.";
  }

  function setRetryState(active, message) {
    if (!retry) {
      return;
    }
    retry.disabled = active;
    if (active) {
      retry.setAttribute("aria-disabled", "true");
    } else {
      retry.removeAttribute("aria-disabled");
    }
    retry.textContent = active ? "Retry after cooldown" : "Try again";
    if (message) {
      retry.setAttribute("title", message);
    } else {
      retry.removeAttribute("title");
    }
  }

  function ensureCooldown() {
    if (cooldown || !api) {
      return;
    }

    var listener = function (active) {
      if (active) {
        setRetryState(true, "Waiting for the request limit to reset");
        return;
      }
      setRetryState(false, "");
      if (lastErrorWasRateLimit && errorPanel && !errorPanel.hidden) {
        setText(
          errorMessage,
          "The request limit has reset. You can try again now."
        );
      }
    };

    if (typeof api.getPublicReadCooldown === "function") {
      cooldown = api.getPublicReadCooldown();
      if (typeof cooldown.subscribe === "function") {
        cooldown.subscribe(listener);
      }
    } else if (typeof api.createRateLimitCooldown === "function") {
      cooldown = api.createRateLimitCooldown(listener);
    }
  }

  function beginCooldown(error) {
    ensureCooldown();
    if (!cooldown) {
      return;
    }
    lastErrorWasRateLimit = true;
    cooldown.start(Number.isFinite(error && error.retryAfterMs) ? error.retryAfterMs : undefined);
    setRetryState(
      cooldown.active(),
      "Waiting for the request limit to reset"
    );
  }

  function clearSkeleton() {
    if (SKELETONS && typeof SKELETONS.clear === "function") {
      SKELETONS.clear(skeletonHost);
    } else if (skeletonHost) {
      skeletonHost.replaceChildren();
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

      window.addEventListener("ev-favorites-ready", function onReady() {
        if (settled) {
          return;
        }
        settled = true;
        window.clearTimeout(timer);
        resolve(window.EVFavorites);
      }, { once: true });
    });
  }

  /** One batched status request for the currently rendered public cards. */
  function refreshFavoriteStatuses(items) {
    var ids = (Array.isArray(items) ? items : [])
      .map(function (item) { return item && item.id; })
      .filter(function (id) { return typeof id === "string" && id.length > 0; });

    if (ids.length === 0) {
      return;
    }
    if (favoritesController) {
      favoritesController.abort();
    }
    favoritesController = new AbortController();
    var signal = favoritesController.signal;

    waitForFavoritesApi()
      .then(function (client) {
        return client.syncCollection(ids, { signal: signal });
      })
      .catch(function () {
        /* Public discovery remains usable if private status cannot load. */
      });
  }

  /* ------------------------------------------------------------ live results */

  function setLoading() {
    hide(grid);
    hide(empty);
    hide(errorPanel);
    show(loading);
    if (SKELETONS && typeof SKELETONS.cards === "function") {
      SKELETONS.cards(skeletonHost, HOME_PAGE_SIZE, "col-md-6 col-lg-4");
    }
    if (grid) {
      grid.setAttribute("aria-busy", "true");
    }
  }

  function setEmpty() {
    clearSkeleton();
    hide(loading);
    hide(errorPanel);
    show(empty);
    hide(grid);
    if (grid) {
      grid.setAttribute("aria-busy", "false");
    }
  }

  function setError(message) {
    clearSkeleton();
    hide(loading);
    hide(empty);
    hide(grid);
    show(errorPanel);
    setText(errorMessage, message);
    if (grid) {
      grid.setAttribute("aria-busy", "false");
    }
  }

  function showApiError(error) {
    if (isRateLimit(error)) {
      beginCooldown(error);
    } else {
      lastErrorWasRateLimit = false;
      setRetryState(false, "");
    }
    setError(describe(error));
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

    clearSkeleton();
    grid.replaceChildren(fragment);
    hide(loading);
    hide(empty);
    hide(errorPanel);
    show(grid);
    grid.setAttribute("aria-busy", "false");
    refreshFavoriteStatuses(items);
    return true;
  }

  function load() {
    if (!CARD || !grid) {
      return;
    }

    if (cooldown && cooldown.active()) {
      return;
    }

    var sequence = ++requestSequence;
    if (activeController) {
      activeController.abort();
    }
    if (favoritesController) {
      favoritesController.abort();
    }
    var controller = new AbortController();
    activeController = controller;

    setLoading();

    if (!api) {
      setError("Property services are unavailable. Please try again.");
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
        if (sequence !== requestSequence || isCancelled(error)) {
          return;
        }
        showApiError(error);
      });
  }

  function start() {
    waitForPublicApi()
      .then(function (client) {
        api = client;
        ensureCooldown();
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
    skeletonHost = byId("latestSkeletons");
    empty = byId("latestEmpty");
    errorPanel = byId("latestError");
    errorMessage = byId("latestErrorMessage");
    retry = byId("retryLatest");
    setRetryState(false, "");

    if (retry) {
      retry.addEventListener("click", function () {
        if (retry.disabled) {
          return;
        }
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
