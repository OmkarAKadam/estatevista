/**
 * EstateVista — shared foundation script.
 *
 * Deliberately small: this file only wires up progressive enhancement that every
 * page needs. Page-level behaviour lives in its own file (properties.js,
 * property-details.js) from Stage 2 onward.
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

  /** Mark the nav link that matches the current document. */
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

  function init() {
    setCurrentYear();
    markActiveNav();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
