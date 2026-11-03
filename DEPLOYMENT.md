# Deployment Guide — Planned Vercel Setup

> **Status: planned, not completed.** EstateVista has not been deployed from
> this repository yet. This document describes the intended Vercel
> configuration and the checks to run after deployment. It is not evidence that
> a deployment exists.

---

## Target platform

EstateVista is a static multi-page site with no build step. The planned host is
[Vercel](https://vercel.com/), using its GitHub integration and deploying the
production branch.

| Setting | Planned value |
|---|---|
| Platform | Vercel |
| Framework preset | **Other** |
| Root directory | Repository root (`./`) |
| Build command | **None** — leave empty |
| Output directory | Repository root (`./`) |
| Install command | **None** |
| Environment variables | **None** |
| Production branch | `main` |
| Source repository | GitHub repository to be created |

No `vercel.json` is currently committed. Vercel's zero-config static deployment
is expected to work with the settings above. Add configuration only if the
platform requires it during setup.

---

## Prerequisites

Before deploying:

- The Stage 6 documentation changes are reviewed and merged.
- A GitHub repository exists and contains the project.
- The production branch is `main`.
- A Vercel account is available.
- The custom `404.html` path issue has been reviewed (see
  [Custom 404 behavior](#custom-404-behavior)).
- The final repository and demo URLs are added to `README.md`.

These prerequisites are not all complete yet.

---

## Planned GitHub integration

1. Create the GitHub repository and push the project in a later phase.
2. In Vercel, choose **Add New → Project**.
3. Import the GitHub repository.
4. Set the framework preset to **Other**.
5. Leave the build command, output directory and install command at their
   no-build defaults, using the repository root.
6. Deploy from the `main` branch.
7. Confirm that later pushes to `main` create production deployments.

No GitHub repository, remote or Vercel project is configured by this document.

---

## Custom 404 behavior

Vercel static hosting uses a root `404.html` file for unmatched routes.

**Known issue:** the current `404.html` uses relative paths such as
`assets/css/theme.css` and `properties.html`. When Vercel serves the 404 page
for a nested missing URL such as `/missing/nested-page`, the browser resolves
those paths relative to the missing directory, so the page may load without
styles, scripts or working navigation.

This is intentionally **not modified in the current documentation phase**. It
must be reviewed before deployment is considered complete. The likely fix is to
use root-relative paths or an appropriate base path in `404.html` only.

Test both:

- A missing top-level route, for example `/missing-page`.
- A missing nested route, for example `/missing/nested-page`.

Confirm the response uses the custom page and the intended HTTP 404 status.

---

## Post-deployment verification checklist

### Pages and navigation

- [ ] The homepage loads at `/` and at `/index.html`.
- [ ] Primary navigation works from every page.
- [ ] The mobile navigation opens, closes and returns focus appropriately.
- [ ] `properties.html` renders all five listings.
- [ ] `about.html` and `contact.html` load with their page-specific styling.
- [ ] `404.html` is served for an unmatched route with a 404 status.
- [ ] The footer year and footer links render correctly.

### Listing and detail behaviour

- [ ] Keyword, property-type, minimum-price and maximum-price filters update the
      listing grid.
- [ ] Sorting by price ascending and descending works.
- [ ] Filter state survives a page reload through the query string.
- [ ] Clearing filters returns to the full collection.
- [ ] `property-details.html?id=sunlit-courtyard-villa` renders the correct
      property.
- [ ] An unknown or missing `?id=` value shows the property-not-found state.
- [ ] Gallery thumbnails change the main image and keyboard arrow keys work.
- [ ] Related properties render and link correctly.

### Contact form

- [ ] Empty submissions show field-level validation messages.
- [ ] Invalid email addresses are rejected.
- [ ] Valid submissions show the local demonstration notice.
- [ ] No network request is made when the form is submitted.
- [ ] The page clearly states that submissions are not sent or stored.

### Visual and runtime checks

- [ ] Pages are checked at mobile, tablet and desktop widths.
- [ ] No horizontal page overflow appears at the checked widths.
- [ ] Bootstrap styles and Fraunces/Inter fonts load from their CDNs.
- [ ] Font fallbacks remain readable if a CDN is blocked.
- [ ] Deep links to property details work directly, not only through the
      listing page.
- [ ] The browser console shows no unexpected errors.
- [ ] Local images and the favicon load with successful responses.

### Platform checks

- [ ] The production deployment is built from the intended `main` commit.
- [ ] HTTPS is active and the site does not redirect to an insecure URL.
- [ ] Cache headers and asset responses are appropriate for a static site.
- [ ] The final deployment URL is added to `README.md`.

---

## HTTPS

Vercel provides HTTPS automatically for its generated deployment domains. No
manual certificate setup is planned. After a custom domain is attached, verify
that HTTPS works for both the apex and `www` variants if both are configured.

---

## Custom domain

The custom domain is optional and has not been chosen.

Planned steps:

1. Open the Vercel project.
2. Go to **Settings → Domains**.
3. Add the domain.
4. Follow Vercel's DNS instructions for the apex and/or `www` record.
5. Wait for DNS verification and certificate issuance.
6. Decide whether one hostname should redirect to the other.
7. Update the live demo URL in `README.md`.

Do not add a domain until the repository owner has confirmed ownership and DNS
access.

---

## Rollback

If a deployment has a problem:

1. Open the Vercel project's **Deployments** list.
2. Select the last known-good production deployment.
3. Promote it back to production, or redeploy that commit.
4. If the problem is in the repository, revert the offending commit on `main`
   and push the revert.

Because the site is static, there is no database migration or server rollback to
coordinate.

---

## Troubleshooting

### The site loads without Bootstrap styling

- Confirm the Bootstrap CDN request succeeds.
- Confirm the `integrity` and `crossorigin` attributes in the page head still
  match the pinned Bootstrap 5.3.3 files.
- Confirm `theme.css` loads after Bootstrap.

### Fonts fall back to system fonts

- Google Fonts requires network access.
- The fallback stacks are intentional; a blocked font CDN is not a deployment
  failure by itself.
- If privacy or offline use matters, self-hosting the fonts is a future option,
  not part of the current implementation.

### Listings or details are empty

- Confirm JavaScript is enabled.
- Confirm `assets/js/data.js` and the page-specific scripts return successful
  responses.
- Check the browser console for blocked assets or script errors.
- Confirm the details URL includes a valid `?id=` value.

### The custom 404 page is unstyled

This is the known relative-path issue described above. It affects nested missing
URLs and is not fixed in this documentation phase.

### The contact form does not send anything

This is intentional. The form is a client-side validation demonstration with no
backend or external form service.

### The deployment shows older content

- Check which branch and commit Vercel deployed.
- Confirm the production branch is `main`.
- Confirm the latest commit was pushed successfully.
- Use the deployment list to inspect the commit SHA.

### A `.html` URL redirects unexpectedly

The planned configuration does not require clean URLs. If clean URLs are enabled
later, review internal links and the active-navigation script before changing
the setting.

---

## Out of scope

This deployment phase does not add:

- A backend or serverless functions.
- A database.
- Chat or conversational functionality.
- Animations or new interactive features.
- Analytics, tracking or advertising.
- SEO/social metadata beyond what already exists.
