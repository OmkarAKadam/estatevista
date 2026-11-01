# EstateVista

A responsive, multi-page property discovery website. Static HTML, CSS and
vanilla JavaScript — no framework, no backend, no build step.

> **Fictional content notice.** EstateVista is a demonstration project. All
> company details, agent names, property listings and prices are invented. No
> listing on this site represents a real property.

---

## Stage status

**Stage 1 — Project foundation and design system.** Complete.

This stage delivers the document shell, design tokens, reusable component
foundations and repository setup. Page layouts, the listing grid, filtering,
sorting, galleries and forms are intentionally **not** implemented yet.

---

## Technology

| Concern | Choice |
|---|---|
| Markup | HTML5 |
| Styling | CSS3 with custom properties |
| Behaviour | Vanilla JavaScript (ES5-safe syntax, no bundler) |
| UI framework | Bootstrap 5.3.3 via pinned jsDelivr CDN with SRI |
| Typefaces | Fraunces (headings) and Inter (body) via Google Fonts |
| Currency | Indian Rupees (INR, ₹) |
| Hosting | Vercel static deployment (not deployed during Stage 1) |

No `package.json` exists by design. There is nothing to install and nothing to
compile.

---

## Project structure

```
EstateVista/
├── index.html                  Home
├── properties.html             Listing browse page
├── property-details.html       Single listing (?id=<slug>)
├── about.html                  Company / team
├── contact.html                Contact and enquiry
├── 404.html                    Custom not-found page
├── assets/
│   ├── css/
│   │   ├── theme.css           Tokens, Bootstrap overrides, base elements
│   │   └── components.css      Buttons, badges, chips, property card
│   ├── js/
│   │   ├── data.js             Fictional property dataset (global array)
│   │   └── main.js             Shared progressive enhancement
│   └── images/
│       ├── brand/              Logo mark, favicon
│       ├── properties/         Listing photography (4:3)
│       ├── heroes/             Page hero imagery
│       └── team/               Team portraits
├── .gitignore
└── README.md
```

`properties.js` and `property-details.js` are added in Stage 2 alongside the
features that use them, rather than being created empty now.

---

## Local development

The site is static, so any static file server works. Python's built-in server is
sufficient and requires no installation step.

From the project root:

```bash
python -m http.server 5500
```

Then open <http://localhost:5500/>.

An equivalent alternative if Python is unavailable:

```bash
npx serve .
```

**Why a server instead of double-clicking the HTML files?** Opening pages
directly via `file://` works for the current shells, but later stages load
relative assets and conditionally resolve `?id=` query parameters, which behave
inconsistently across browsers on `file://`. Serving over HTTP from the start
avoids turning that into a debugging detour. The property dataset is a plain
JavaScript global specifically so that no `fetch()` CORS restrictions apply.

To stop the server, press `Ctrl+C` in the terminal.

---

## Stylesheet order

This order is required — custom tokens must load after Bootstrap so the
overrides win:

1. Google Fonts stylesheet
2. Bootstrap 5.3.3 (`bootstrap.min.css`, CDN, with `integrity`)
3. `assets/css/theme.css`
4. `assets/css/components.css`

Bootstrap's JavaScript bundle is only included on pages that use a Bootstrap
component; Stage 1 shells do not need it.

---

## Design tokens

All tokens live in `:root` inside `assets/css/theme.css`. Use the token, never a
raw hex value.

### Colour

| Token | Value | Role |
|---|---|---|
| `--ev-forest-900` | `#14342B` | Primary brand, headings, primary buttons |
| `--ev-forest-700` | `#1E4D3F` | Hover and active states |
| `--ev-sage-300` | `#9DB5A5` | Focus ring, muted accents |
| `--ev-terracotta-500` | `#C05B36` | Accent marks and decorative fills |
| `--ev-terracotta-700` | `#A44A2A` | Accent text and accent buttons (AA on white) |
| `--ev-ivory-50` | `#FAF7F2` | Page background |
| `--ev-sand-100` | `#F0EAE0` | Alternate surfaces, image placeholders |
| `--ev-charcoal-900` | `#1C1C1A` | Body text |
| `--ev-slate-600` | `#5F6660` | Metadata and secondary text |
| `--ev-hairline` | `#E2DCD2` | Borders and dividers |
| `--ev-status-available` | `#2F6F4F` | "Available" status |

Contrast note: white text on `--ev-terracotta-500` measures about 4.4:1, which
is below the 4.5:1 AA threshold for normal text. Use `--ev-terracotta-700`
(≈5.8:1) whenever text sits on a terracotta fill. `--ev-terracotta-500` is for
non-text accents.

### Typography

- Headings: `--ev-font-display` (Fraunces), weight 600, tight tracking.
- Body and UI: `--ev-font-body` (Inter), base 16px, line-height 1.6.
- Metadata: `--ev-text-sm` (14px) with muted colour and tabular numerals.
- Prices always use `font-variant-numeric: tabular-nums` so digits align across
  card grids.

### Spacing and layout

- 8px scale: `--ev-space-1` (4px, fine adjustments) through `--ev-space-9`
  (96px).
- Content width `--ev-container-max: 1240px`.
- Grid gutter `--ev-grid-gutter: 24px`, applied through `--bs-gutter-x`.
- Section rhythm `--ev-section-pad-y: clamp(40px, 6vw, 96px)`.
- Radii: 4 / 8 / 12 / 16 px plus a pill.
- Shadows are limited to three restrained tokens.

---

## Component foundations

- **Buttons** — `.btn-primary` (forest solid), `.btn-secondary` (forest
  outline), `.btn-accent` (terracotta solid), `.btn-ghost` (inline text),
  `.btn-sm`. All share a 48px minimum height, 8px radius and a 150ms transition.
- **Badges and chips** — `.ev-badge` with `--accent`, `--available`,
  `--neutral` modifiers; `.ev-chip` for filter controls using `aria-pressed`.
- **Property card** — `.card-estate` with `__media`, `__body`, `__price`,
  `__title`, `__meta`, `__footer`. The card uses a stretched link so the whole
  surface is clickable without nesting anchors.
- **Save control** — `.ev-favorite`, a glass-overlay button driven by
  `aria-pressed`.

---

## Accessibility baseline

- A visible `:focus-visible` outline (3px sage) is defined globally; bootstrap
  outlines are never removed without a replacement.
- `.ev-skip-link` provides a keyboard bypass to `#main` on every page.
- Motion is suppressed under `prefers-reduced-motion: reduce`, including card
  lift and image zoom.
- Colour pairs were chosen for AA contrast on body text; see the terracotta note
  above.
- Landmarks: every page has a single `<main id="main">`.

---

## Property data

`assets/js/data.js` exposes `window.EV_PROPERTIES` — a plain array of fictional
listings. It is a global rather than a fetched JSON file so pages work without
CORS or a JSON round-trip. Prices are stored as whole rupees (`48500000`) and
are formatted for display in a later stage.

Record shape:

| Field | Type | Notes |
|---|---|---|
| `id` | string | Slug used by `property-details.html?id=<slug>` |
| `title` | string | Display name |
| `type` | string | `apartment`, `villa`, `penthouse`, `rowhouse` |
| `status` | string | `available`, `reserved`, `sold` |
| `price` | number | Whole rupees |
| `city` / `locality` | string | Location |
| `bedrooms` / `bathrooms` | number | |
| `areaSqft` | number | Built-up area |
| `featured` | boolean | Home page highlight rail |
| `images` | string[] | Paths relative to the site root |

---

## Troubleshooting

**Uploaded pages show unstyled content.** Confirm `theme.css` loads *after*
`bootstrap.min.css` and that the `integrity` attribute has not been edited by
hand.

**Fonts fall back to Georgia or system sans.** The Google Fonts stylesheet
requires network access. The fallback stacks in `--ev-font-display` and
`--ev-font-body` are intentional and keep the layout readable offline.

---

## Roadmap

1. ~~Stage 1 — Foundation and design system~~
2. Stage 2 — Home page and listing grid
3. Stage 3 — Filtering, sorting and property details
4. Stage 4 — About, contact and forms
5. Stage 5 — Imagery, polish, deployment
