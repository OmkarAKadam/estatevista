# EstateVista V2 — Property Authorization Matrix (Stage 4.1)

## 1. Status and scope

**FINALIZED Stage 4.1** authorization design for the property domain. It is derived from the verified Stage 3 security architecture in `docs/validation/PROPERTY_DOMAIN_ASSESSMENT.md` §3.7 and the approved contracts in `docs/API_SPECIFICATION.md` §6–8/§25.1.G and `docs/SECURITY_ARCHITECTURE.md` §10.4/§11.9/§13.7. Owner decisions D-01…D-11 are incorporated; Stage 4.2 still requires explicit authorization before implementation.

> No controller, service, or endpoint is implemented by this document. Role gates listed as **VERIFIED** already exist in `SecurityConfig`; **FINALIZED** gates must be added when property endpoints are implemented.

**Backend authority is absolute.** Frontend role-based rendering is a usability measure only. Every decision below is enforced by the Spring Security filter chain and by server-side service code using `AuthenticatedAccount` resolved from the session principal.

---

## 2. Roles and trusted identity (VERIFIED)

| Role | Authority | Source |
|---|---|---|
| `BUYER` | `ROLE_BUYER` | `authorization/PlatformRole.java`; `V2__create_roles_and_users.sql` seed |
| `AGENT` | `ROLE_AGENT` | same |
| `ADMIN` | `ROLE_ADMIN` | same |

- `AccountAuthorizationService.resolve(Authentication)` derives role, account ID, and status from `users` and requires `account_status = 'ACTIVE'`; unknown roles yield no authority.
- `SessionAuthorizationFilter` re-resolves authorities on every request, so deactivation takes effect immediately on the next request.
- `AuthenticatedAccount.id()` is the only valid source of actor identity, `createdBy`, `updatedBy`, and AGENT ownership scope. Client-supplied identity/ownership fields are rejected (`UnknownFieldGuard`).
- Filter-chain rules that already exist (VERIFIED `SecurityConfig.java` lines 125–139): `/api/v1/admin/**` → `ADMIN`; `/api/v1/agents/me/**` → `AGENT`; everything not explicitly allowed → `denyAll()`. New property routes must be added as explicit public/`authenticated()`/method-specific role rules in the same chain — never left implicit.
- **D-01 create-path rule:** `POST /api/v1/properties` is gated by a method-specific ADMIN matcher (for example `requestMatchers(HttpMethod.POST, "/api/v1/properties").hasRole("ADMIN")`). Security configuration must not rename the approved path to `/api/v1/admin/properties`. The existing `/api/v1/admin/**` gate continues to cover only the approved administrative list and assignment routes.

---

## 3. Capability matrix

Legend: ✅ allowed · ⚠️ allowed only with the stated ownership condition · ❌ denied · — not applicable.

| Capability | Buyer | Property Agent | Administrator | Ownership / condition (authoritative) |
|---|---|---|---|---|
| Create property (`POST /properties`) | ❌ | ❌ | ✅ | D-05 confirms FR-PROP-001 / DATABASE_DESIGN §6.3 rule 2. Method-specific ADMIN matcher; `primaryAgentId` validated as ACTIVE `AGENT`; `createdBy` from session principal. |
| View public property / images | ✅ | ✅ | ✅ | Only `PUBLISHED` records for unauthenticated callers (C-10/D-08); non-visible property → `404`. |
| View featured / recent / related / reference lookups | ✅ | ✅ | ✅ | Public and read-only; `PUBLISHED`-only filtering for property lists, before pagination/counting (D-02). |
| View own assigned properties | — | ✅ | — | `primary_agent_id = principal.id` (`requirePropertyAssignment`). |
| View all properties (incl. DRAFT/ARCHIVED) | ❌ | ❌ | ✅ | `/api/v1/admin/**` role gate plus management service query. |
| View non-published detail | ❌ | ⚠️ own assigned only | ✅ | Unauthorized caller gets `404` (no existence disclosure). Historical access via authenticated favorite/inquiry/conversation surfaces is governed by their own policies. |
| Edit property (PATCH/PUT) | ❌ | ⚠️ own assigned only | ✅ | `requirePropertyManagement`; deny `ARCHIVED`; protected fields (`status`, `primaryAgentId`, IDs, audit/timestamps, image provider fields) rejected. |
| Publish (`DRAFT → PUBLISHED`) | ❌ | ⚠️ own assigned only | ✅ | Transition allowlist in `PROPERTY_DOMAIN_DESIGN.md` §4.3; preconditions required. |
| Unpublish (`PUBLISHED → UNAVAILABLE`) | ❌ | ⚠️ own assigned only | ✅ | Same transition policy; `UNAVAILABLE` is the existing approved status (SD4-02). |
| Mark sold / rented | ❌ | ⚠️ own assigned only | ✅ | Outcome must match `listingType`. |
| Republish (`UNAVAILABLE → PUBLISHED`) | ❌ | ⚠️ own assigned only | ✅ | Re-run publication preconditions. |
| Reopen `SOLD`/`RENTED` to `PUBLISHED` | ❌ | ❌ | ✅ | D-06 administrative decision; revalidates publication prerequisites, refreshes `publishedAt`, preserves history. |
| Archive (logical delete, `ARCHIVED` + `archived_at`) | ❌ | ❌ | ✅ | Matches `DELETE /properties/{propertyId}` admin rule; no physical delete; terminal state. |
| Reassign primary agent | ❌ | ❌ | ✅ | Admin-only `PATCH /api/v1/admin/properties/{propertyId}/assignment`; validate ACTIVE `AGENT`; audit; never change historical assignment IDs in inquiries/conversations. |
| Upload property image | ❌ | ⚠️ own assigned, non-archived only | ✅ any property | Property from path + `requirePropertyManagement`; magic-byte/MIME/size/dimension pipeline; backend-mediated Cloudinary. |
| Update image metadata / reorder | ❌ | ⚠️ own assigned only | ✅ | `imageId` must belong to path `propertyId`. |
| Set primary image | ❌ | ⚠️ own assigned only | ✅ | Transactional single-primary enforcement. |
| Delete image | ❌ | ⚠️ own assigned only | ✅ | Backend deletes remote asset and row; audit. |
| Administrative moderation (review, archive, reassign, list all) | ❌ | ❌ | ✅ | `/api/v1/admin/**` gate. No separate approval state exists (U-04). |
| Favorite / inquire / chat about a property | ✅ | ✅ (as buyer-capability if also BUYER; roles are single-valued) | ❌ for conversations | Out of property-management scope; existing `ResourceAuthorizationService` policies govern. |

Notes:

1. Roles are single-valued in the verified model (`users.role_id` is one FK; `PlatformRole` maps one role). An agent acting as a buyer is not modelled; treat the matrix rows literally.
2. ADMIN can manage **all** properties but has no automatic access to private conversations/messages (VERIFIED `requireConversationAccess` denies ADMIN), and this design does not change that.
3. `ResourceAuthorizationService.requirePropertyManagement` currently grants ADMIN without checking that the property exists (VERIFIED). The proposed property service must load the resource and return `404` for a missing ID before mutating; failure to do so would leak nothing about other users' data but would violate the API contract.
4. Missing and forbidden resources must produce the same generic `FORBIDDEN` for unauthorized callers (existing service policy). For public reads, non-visible → `404`.

---

## 4. Endpoint-to-permission mapping (FINALIZED)

URLs include the verified `/api/v1/` convention. Prefixes `/api/v1/admin/**` and `/api/v1/agents/me/**` already have role gates in `SecurityConfig`.

| Endpoint | Role gate | Ownership check | Detailed contract |
|---|---|---|---|
| `POST /api/v1/properties` | **ADMIN via method-specific matcher** (filter chain + service) | Assignment target validated; actor from principal | API §4.1 (D-01) |
| `GET /api/v1/properties/{propertyId}` | Public / authenticated | PUBLISHED-only for unauthenticated; assigned agent/admin may read non-published | API §4.2 |
| `GET /api/v1/properties` | Public | PUBLISHED-only query filter before pagination | API §4.3 |
| `GET /api/v1/agents/me/properties` | AGENT (filter chain + service) | `primary_agent_id = principal.id` | API §4.4 |
| `GET /api/v1/admin/properties` | ADMIN (existing prefix + service) | All statuses | API §4.5 |
| `PATCH /api/v1/properties/{propertyId}` | AGENT assigned or ADMIN | `requirePropertyManagement` | API §4.6 |
| `PUT /api/v1/properties/{propertyId}` | AGENT assigned or ADMIN | same | API §4.7 |
| `PATCH /api/v1/properties/{propertyId}/status` | AGENT assigned or ADMIN; ADMIN for `SOLD`/`RENTED` reopening | same + authoritative transition matrix | API §4.8 (D-06) |
| `DELETE /api/v1/properties/{propertyId}` | ADMIN | any property; logical archive; `200` with updated resource | API §4.9 (D-03) |
| `PATCH /api/v1/admin/properties/{propertyId}/assignment` | ADMIN (existing prefix + service) | any property; ACTIVE AGENT target validation | API §4.10 |
| `GET /api/v1/properties/featured` | Public | PUBLISHED-only before pagination; `is_featured` future migration dependency | API §4.11 (D-02) |
| `GET /api/v1/properties/recent` | Public | PUBLISHED-only; order by refreshed `publishedAt` | API §4.12 (D-02) |
| `GET /api/v1/properties/{propertyId}/related` | Public | Source must be PUBLISHED; candidates PUBLISHED-only | API §4.13 (D-02) |
| `GET /api/v1/property-categories` | Public | Read-only reference data | API §4.14 (D-02/D-09) |
| `GET /api/v1/property-subtypes` | Public | Read-only reference data; optional `categoryId` | API §4.15 (D-02/D-09) |
| `GET /api/v1/amenities` | Public | Read-only reference data | API §4.16 (D-02/D-09) |
| `POST /api/v1/properties/{propertyId}/images` | AGENT assigned or ADMIN | path property + non-archived | API §5.1 |
| `GET /api/v1/properties/{propertyId}/images` | Public / authenticated | PUBLISHED-only publicly; assigned/admin for own unpublished | API §5.2 |
| `PATCH /api/v1/properties/{propertyId}/images/{imageId}` | AGENT assigned or ADMIN | image belongs to property | API §5.3 |
| `DELETE /api/v1/properties/{propertyId}/images/{imageId}` | AGENT assigned or ADMIN | image belongs to property; `204` no body | API §5.4 (D-03) |
| `PATCH /api/v1/properties/{propertyId}/images/{imageId}/primary` | AGENT assigned or ADMIN | image belongs to property | API §5.5 |

### 4.1 Stage ownership of approved endpoints

| Stage | Property endpoints / work | Deferred outside Stage 4 |
|---|---|---|
| **4.2** | Create, detail, search, featured, recent, related, agent/admin lists, update, status, logical archive, assignment, method-specific filter-chain matchers, base DTO/service/repository queries. | `is_featured` migration and admin featured control require separate approval (API §25.1.D). |
| **4.3** | Ownership/authorization enforcement on all §4 mutations and private reads; missing-ID `404` before policy for ADMIN. | — |
| **4.4** | Public category/subtype/amenity lookups and controlled reference-data strategy. | No arbitrary public mutation endpoints. |
| **4.5** | Image endpoints after separate D-08 approval of SDK/config/credentials and SD-30 pipeline. | No SDK or upload code in Stage 4.1. |
| **4.6** | Lifecycle policy and moderation guards (D-04…D-07). | — |
| **4.7** | Frontend API client, role-gated management views, status vocabulary mapping, public discovery wiring. | No framework introduction or redesign. |
| **Stage 5+** | Favorites, inquiries, conversations/messages, dashboards, notifications. | Their approved contracts remain outside this Stage 4 property-management plan and are not deleted. |

Every mutation above requires a valid session and CSRF token. `POST /api/v1/auth/register` is the only CSRF-exempt mutation (VERIFIED `SecurityConfig`), and no property endpoint may copy that exemption.

---

## 5. Backend enforcement checklist for implementation

1. Explicit filter-chain rule for every new route: public GETs are explicitly permitted; `POST /api/v1/properties` uses a method-specific ADMIN matcher (D-01); authenticated mutations use `authenticated()` plus service-level role/ownership checks; unlisted routes remain denied.
2. `AccountAuthorizationService.requireActive(authentication)` or equivalent to obtain `AuthenticatedAccount` — never read a role/ID from the request.
3. `ResourceAuthorizationService.requirePropertyManagement(account, propertyId)` for agent/admin mutations; `requirePropertyAssignment` for agent-scoped lists.
4. Resource existence check → `404` for missing IDs; generic `FORBIDDEN` for unauthorized callers; no cross-user enumeration.
5. `UnknownFieldGuard` on every request DTO to block mass assignment of `id`, `status`, `primaryAgentId`, audit fields, timestamps, and provider fields.
6. Service-layer lifecycle transition table; never trust a status supplied in a general update DTO.
7. Status-based public visibility applied in the repository query **before** pagination, counting, and related-property selection (C-10/D-08).
8. Image ownership: `imageId` must belong to the path property; one primary image enforced transactionally plus the DB partial unique index.
9. Admin assignment: target must exist, have role `AGENT`, and be `ACTIVE`; write an audit event; do not rewrite historical assignment IDs.
10. CSRF, exact-origin validation, rate limits, and audit hooks preserved. Additionally, F-1…F-8 below remain open and are not silently resolved by property work.
11. Response envelopes match D-03: top-level `data` + `meta` for pages, standard error envelope for all failures, `200` with updated resource for archival, and `204` with no body for image deletion.
12. Publication and lifecycle decisions match D-04/D-06: six statuses only; `UNAVAILABLE` is unpublish; `ARCHIVED` is terminal; `SOLD`/`RENTED` reopening and all archival are ADMIN-only; invalid/same-status transitions return `409`.
13. Request invariants match D-07/D-10: ≤10,000-character trimmed description, uppercase active ISO 4217 currency with no silent default (field is required), `publishedAt` refreshed on every publish, no primary image after primary deletion, one-primary DB index preserved, and server-generated unique slugs with bounded collision retries.
14. Approved SECURITY §11.9 rate limits are applied (public search 120/minute/IP, detail 300/minute/IP, authenticated 300/minute/user, admin mutations 60/minute/admin, upload 20/hour/user and ≤10 images/property); F-8 `Retry-After` remains open.

---

## 6. Additional requirements identified for Stage 4.x

| ID | Requirement | Rationale |
|---|---|---|
| AR-01 | Add explicit filter-chain matchers for all property routes: public GETs permitted, method-specific ADMIN matcher for `POST /api/v1/properties` (D-01), `authenticated()` for other mutations with service checks, existing `/admin/**` and `/agents/me/**` prefixes retained where approved; rely on `denyAll()` fallback. | Current chain has no property routes; anything unlisted is denied (VERIFIED). |
| AR-02 | Extend `ResourceAuthorizationService` with an image-to-property ownership check that delegates to `requirePropertyManagement`. | Avoids duplicating property policy in image code; no second security mechanism. |
| AR-03 | Enforce `ARCHIVED` immutability for agents in the service layer, not only the UI. | UI §9.6 and SECURITY §13.7 require backend enforcement. |
| AR-04 | Ensure public queries filter `status = 'PUBLISHED'` at the database/query layer. | Prevents pagination totals, related lists, and direct detail from leaking hidden records (C-10/D-08). |
| AR-05 | Audit property create, update, status transition, archival, assignment, image upload/metadata/primary/delete with actor + property ID + result. | Extends the existing `AuditEventService` pattern; never log bytes or secrets. |
| AR-06 | Add authorization tests for cross-agent property access, buyer mutation attempts, archived-property image upload, cross-property image ID, admin assignment of inactive/non-agent users, and non-published public detail. | Mirrors existing `ResourceAuthorizationIntegrationTest` style (test source exists; no execution claimed here). |
| AR-07 | Do not treat F-1…F-8 as closed. Any property mutation must continue to rely on the existing CSRF/session controls while those hardening items remain open. | Explicit instruction; see §7. |

---

## 7. Preserved Stage 3 security findings F-1 through F-8

Source: `docs/validation/STAGE_3_8_TESTING_AND_CLOSURE_REPORT.md` §I (local, gitignored file; preserved here verbatim so the findings survive in tracked Stage 4.1 documentation). **Status: OPEN / DEFERRED. None is resolved by Stage 4.1.**

| ID | Severity | Finding | Disposition (original) | Stage 4.1 status |
|---|---|---|---|---|
| F-1 | Medium | Exact Origin/Referer validation is implemented only for `POST /api/v1/auth/register`; the approved design calls for exact-origin validation on state-changing requests. Other mutations rely on CSRF tokens, exact CORS, `SameSite=Lax`, and deny-by-default. | No exploit observed. Defer to security hardening before production; extend origin validation to state-changing routes. | **Preserved — open** |
| F-2 | Medium | CSRF token is not regenerated on successful login. `changeSessionId()` rotates the session identifier but preserves the session attribute containing the token; the design requires a new token at session lifecycle boundaries. | Token remains 256-bit, session-bound, header-only, and no cookie is used. Defer rotation hardening. | **Preserved — open** |
| F-3 | Medium | No server-side session-destroyed listener or heartbeat re-check closes an established WebSocket when the HTTP session is invalidated. REST logout is effective, but an already-open socket may remain until the client closes it. | Stage 3 has no authenticated chat feature, so current impact is limited. Must be resolved before chat ships. | **Preserved — open** |
| F-4 | Low | `Cache-Control: no-store` is set on successful auth responses and CSRF bootstrap, but not on generic error envelopes or the security deny handler. | Defer to a global error-header hardening pass. | **Preserved — open** |
| F-5 | Medium | Idle timeout is configured as 30 minutes, but the approved 12-hour absolute timeout, optional session cap, and shorter admin idle timeout are not implemented. | No admin UI or long-lived authenticated workflow ships in Stage 3. Defer to session lifecycle hardening. | **Preserved — open** |
| F-6 | Low | Logout clears a cookie named with the hardcoded literal `EV_SESSION` rather than deriving it from the configured session cookie name. | Harmless with current configuration. Defer configuration-coupling cleanup. | **Preserved — open** |
| F-7 | Low | The frontend shares concurrent in-flight CSRF bootstrap calls and retries a stale token once. A token invalidated by an out-of-band session change can still require a retry. | Verified retry recovery works. Defer optional token-version hardening. | **Preserved — open** |
| F-8 | Low | `last_login_at` is not updated on successful login; `429` responses do not include `Retry-After`; deactivated accounts are rechecked per request rather than having all sessions proactively invalidated. | Security decisions fail closed on the next request. Defer audit/rate-limit/session-revocation enhancements. | **Preserved — open** |

Security implication for property work: F-1 and F-2 affect every proposed mutating property endpoint because they rely on the same CSRF mechanism; F-5 matters once long-lived admin management workflows exist; F-8's per-request deactivation recheck is currently a compensating control that must not be removed. Property implementation must not introduce a second CSRF exemption, a client-supplied identity path, or an authorization shortcut that assumes these findings are fixed.
