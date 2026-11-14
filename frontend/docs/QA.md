# Stage 5 QA Record

> **Status: Stage 5 completed.** This document records the coverage and results
> reported for the Stage 5 quality-assurance pass. It does not re-run the audit,
> certify later changes, or claim full WCAG compliance.

---

## Scope

The Stage 5 audit covered the six site pages:

- `index.html`
- `properties.html`
- `property-details.html`
- `about.html`
- `contact.html`
- `404.html`

It also covered **eight viewport sizes** and the site's interactive behaviour in
the browser.

The audit was a project QA pass for this static site. It was not a formal
conformance audit against every WCAG success criterion.

---

## Reported coverage

| Area | Coverage |
|---|---|
| Pages | Six pages audited |
| Viewport sizes | Eight sizes |
| Responsive layout | Layout and horizontal-overflow checks |
| Functional regression | Exercise of the site's implemented interactive behaviour |
| Keyboard navigation | Navigation and focus checks |
| Automated accessibility | Axe-core scans |
| JavaScript-disabled behaviour | Fallback checks with scripting unavailable |

The Stage 5 report did not enumerate every individual test case in this
repository. This record preserves the reported coverage categories rather than
inventing a case-by-case test log.

---

## Axe-core results

Axe-core reported **zero violations across 18 audited runs**.

This result applies to the code state audited during Stage 5. It does not
guarantee that later changes remain free of accessibility issues.

---

## Responsive and overflow checks

The audit checked the six pages across eight viewport sizes for responsive
layout behaviour and horizontal overflow. The eight sizes were a practical
sample, not a claim to cover every device or browser combination.

---

## Functional regression coverage

Stage 5 included functional regression checks across the site. The site's
implemented interactive behaviour includes:

- Navigation between the six pages.
- Homepage search and featured-property rendering.
- Listing filters, sorting, result counts and empty states.
- Property details selected through `?id=`.
- Image gallery selection.
- Related-properties rendering.
- Contact-form validation and the local-only confirmation state.
- The custom 404 page.

The Stage 5 record reports functional regression coverage across the site
rather than a per-case test log. The absence of a detailed case list here does
not expand or reduce the Stage 5 scope.

---

## Keyboard and focus checks

Keyboard navigation and focus behaviour were part of the reported coverage.
The site's interactive surfaces include links, navigation controls, filter
controls, the gallery and form fields. The site defines a visible
`:focus-visible` treatment and a skip link on every page.

---

## JavaScript-disabled fallbacks

The audit included checks with JavaScript disabled. The current pages provide
`<noscript>` notices for JavaScript-rendered content:

- The homepage explains that featured properties require JavaScript.
- The listing page explains that listings require JavaScript.
- The details page explains that the property view requires JavaScript.
- The contact page explains that local validation will not run and nothing is
  sent.

These fallbacks explain the limitation; they do not make the JavaScript-rendered
listing and details content available without JavaScript.

---

## Known limitations and audit boundaries

- **No full WCAG compliance claim.** Zero axe-core violations do not establish
  conformance with WCAG 2.1 or WCAG 2.2 at any level.
- **Automated checks are not exhaustive.** Axe-core cannot detect every
  accessibility problem; manual judgement remains necessary.
- **Eight viewport sizes are a sample.** They do not represent every screen,
  zoom level, orientation or browser.
- **Results are tied to the audited Stage 5 code state.** Later changes require
  their own verification.
- **JavaScript-disabled users cannot browse the full listing and details
  content.** This is a documented product limitation, not a QA pass.
- **The contact form sends and stores nothing.** This is intentional behaviour,
  not a deployment defect.

---

## Related documentation

- [Project README](../README.md)
- [Planned deployment guide](../DEPLOYMENT.md)
