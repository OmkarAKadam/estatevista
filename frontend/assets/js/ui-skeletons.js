/**
 * EstateVista — lightweight, decorative loading skeletons.
 *
 * These helpers only create aria-hidden placeholders. The owning page provides
 * a single polite status message and toggles aria-busy on the real container.
 * No framework, animation library or network access is involved.
 */
(function () {
  "use strict";

  function el(tag, className) {
    var node = document.createElement(tag);
    if (className) {
      node.className = className;
    }
    node.setAttribute("aria-hidden", "true");
    return node;
  }

  function line(widthClass) {
    return el("span", "ev-skeleton__line" + (widthClass ? " " + widthClass : ""));
  }

  function card(columnClass) {
    var column = document.createElement("div");
    column.className = columnClass || "col";

    var box = el("div", "ev-skeleton ev-skeleton--card");
    var media = el("div", "ev-skeleton__media");
    var body = el("div", "ev-skeleton__body");

    body.appendChild(line("ev-skeleton__line--lg"));
    body.appendChild(line("ev-skeleton__line--md"));
    body.appendChild(line("ev-skeleton__line--sm"));

    box.appendChild(media);
    box.appendChild(body);
    column.appendChild(box);
    return column;
  }

  function cards(container, count, columnClass) {
    if (!container) {
      return;
    }
    var fragment = document.createDocumentFragment();
    for (var index = 0; index < count; index += 1) {
      fragment.appendChild(card(columnClass));
    }
    container.replaceChildren(fragment);
  }

  function detail(container) {
    if (!container) {
      return;
    }
    var box = el("div", "ev-skeleton ev-skeleton--detail");
    var gallery = el("div", "ev-skeleton__gallery");
    var main = el("div", "ev-skeleton__media ev-skeleton__media--wide");
    var thumbs = el("div", "ev-skeleton__thumbs");

    for (var index = 0; index < 3; index += 1) {
      thumbs.appendChild(el("span", "ev-skeleton__thumb"));
    }
    gallery.appendChild(main);
    gallery.appendChild(thumbs);

    var summary = el("div", "ev-skeleton__summary");
    summary.appendChild(line("ev-skeleton__line--xl"));
    summary.appendChild(line("ev-skeleton__line--md"));
    summary.appendChild(line("ev-skeleton__line--lg"));
    summary.appendChild(line("ev-skeleton__line--sm"));
    summary.appendChild(line("ev-skeleton__line--md"));

    box.appendChild(gallery);
    box.appendChild(summary);
    container.replaceChildren(box);
  }

  function gallery(container) {
    if (!container) {
      return;
    }
    var box = el("div", "ev-skeleton ev-skeleton--gallery");
    box.appendChild(el("div", "ev-skeleton__media ev-skeleton__media--wide"));
    var thumbs = el("div", "ev-skeleton__thumbs");
    for (var index = 0; index < 3; index += 1) {
      thumbs.appendChild(el("span", "ev-skeleton__thumb"));
    }
    box.appendChild(thumbs);
    container.replaceChildren(box);
  }

  function related(container) {
    cards(container, 3, "col");
  }

  function clear(container) {
    if (container) {
      container.replaceChildren();
    }
  }

  window.EVSkeletons = {
    card: card,
    cards: cards,
    detail: detail,
    gallery: gallery,
    related: related,
    clear: clear
  };
})();
