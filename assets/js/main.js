/**
 * EstateVista — shared foundation script.
 *
 * Runs on every page: progressive-enhancement class, current year, active nav
 * state, header elevation and mobile navigation behaviour.
 *
 * Page-level behaviour lives in its own file (home.js, properties.js, ...).
 */
(function () {
  "use strict";

  document.documentElement.classList.add("ev-js");

  /** Fill any [data-current-year] element with the current year. */
  function setCurrentYear() {
    var year = String(new Date().getFullYear());
    var nodes = document.querySelectorAll("[data-current-year]");

    Array.prototype.forEach.call(nodes, function (node) {
      node.textContent = year;
    });
  }

  /** Fallback for pages that do not set aria-current in the markup. */
  function markActiveNav() {
    var currentPage = window.location.pathname.split("/").pop() || "index.html";
    var links = document.querySelectorAll("[data-nav-link]");

    Array.prototype.forEach.call(links, function (link) {
      var href = link.getAttribute("href");

      if (!href || href.indexOf("://") !== -1) {
        return;
      }

      if (href.replace(/^\.\//, "") === currentPage) {
        link.setAttribute("aria-current", "page");
      }
    });
  }

  /** Adds a subtle border and shadow once the page scrolls. */
  function initHeader() {
    var header = document.querySelector("[data-site-header]");

    if (!header) {
      return;
    }

    function update() {
      header.classList.toggle("is-scrolled", window.scrollY > 4);
    }

    update();
    window.addEventListener("scroll", update, { passive: true });
  }

  /** Keeps the mobile menu label, state and focus behaviour honest. */
  function initMobileNav() {
    var toggle = document.querySelector(".site-nav__toggle");
    var panel = document.getElementById("primaryNav");

    if (!toggle || !panel) {
      return;
    }

    function collapseInstance() {
      var Collapse = window.bootstrap && window.bootstrap.Collapse;

      if (!Collapse) {
        return null;
      }

      return Collapse.getOrCreateInstance(panel, { toggle: false });
    }

    function isMobileLayout() {
      return window.getComputedStyle(toggle).display !== "none";
    }

    panel.addEventListener("shown.bs.collapse", function () {
      toggle.setAttribute("aria-label", "Close navigation menu");
    });

    panel.addEventListener("hidden.bs.collapse", function () {
      toggle.setAttribute("aria-label", "Open navigation menu");
    });

    // Choosing a link closes the menu, including on same-page targets.
    panel.addEventListener("click", function (event) {
      var target = event.target;
      var link = target && target.closest ? target.closest("a") : null;

      if (!link || !isMobileLayout() || !panel.classList.contains("show")) {
        return;
      }

      var instance = collapseInstance();

      if (instance) {
        instance.hide();
      }
    });

    // Escape closes the menu and returns focus to its toggle.
    // Bound to the document because focus usually sits on the toggle button,
    // which is a sibling of the collapse panel rather than a descendant.
    document.addEventListener("keydown", function (event) {
      if (event.key !== "Escape" || !panel.classList.contains("show")) {
        return;
      }

      var instance = collapseInstance();

      if (instance) {
        instance.hide();
        toggle.focus();
      }
    });
  }

  function init() {
    setCurrentYear();
    markActiveNav();
    initHeader();
    initMobileNav();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
