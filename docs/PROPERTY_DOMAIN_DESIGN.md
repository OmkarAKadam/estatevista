# EstateVista V2 — Property Domain Design (Stage 4.1)

## 1. Purpose and status

This document defines the **finalized Stage 4.1** property domain architecture for the next implementation stage. It is written against the verified repository state recorded in `PROPERTY_DOMAIN_ASSESSMENT.md`. Every item below is a design specification unless explicitly labelled **VERIFIED** (already present in code/schema) or **DEFERRED** (a later-stage approval prerequisite). Owner decisions D-01…D-11 are incorporated; Stage 4.2 still requires explicit authorization before implementation.

> **Stage 4.1 is documentation-only.** Nothing in this document is implemented. No entity, repository, table, controller, service, DTO, dependency, or configuration is created or changed here.

Decision IDs (`SD4-xx`) and unresolved IDs (`U-xx`) are consistent across all five Stage 4.1 documents and are recorded in `STAGE_4_DECISION_REGISTER.md`.

---

## 2. Design principles

| ID | Principle |
|---|---|
| P-1 | **Extend, do not duplicate.** The existing `property` entities, eight repositories, and Flyway V4–V8/V14 schema are the single source of truth. No new `Listing`, `RealEstateProperty`, or parallel image table is proposed. |
| P-2 | **Principal-derived actor identity.** `createdBy`, `updatedBy`, and an agent's ownership scope come from `AuthenticatedAccount.id()` resolved from the server-side session (VERIFIED mechanism: `AccountAuthorizationService`), never from a request body, query parameter, or header. |
| P-3 | **Backend-authoritative authorization.** Filter-chain role rules are necessary but insufficient; every property mutation and private read calls `ResourceAuthorizationService` (or a clearly named extension of it) before doing work. |
| P-4 | **No direct entity exposure.** All request/response shapes are DTOs, consistent with `API_SPECIFICATION.md` §25.3.9. |
| P-5 | **Fail closed and avoid enumeration.** Unknown/forbidden resources yield the same generic error where the caller is not authorized; public non-visible properties return `404`. |
| P-6 | **Schema changes are explicit.** `ddl-auto: validate` and Flyway `validate-on-migrate: true` mean any future change requires a new versioned migration and design approval. Stage 4.1 proposes none. |
| P-7 | **Preserve the approved security baseline**, including the open findings F-1…F-8. Stage 4.1 neither fixes nor closes them. |

---

## 3. Property information model

### 3.1 Evaluation of the brief's field list against the existing entity

| Brief field | Existing implementation (VERIFIED) | Recommendation |
|---|---|---|
| Property ID | `Property.id` UUID, `GenerationType.UUID`; DB `UUID PRIMARY KEY` | **Reuse.** Server-generated. Client-supplied `id` rejected by `UnknownFieldGuard`. |
| Title | `title VARCHAR(200) NOT NULL` | **Reuse.** DTO: required, 1–200 chars after trim. |
| Description | `description TEXT NOT NULL` | **Reuse.** DTO: trimmed, non-blank, ≤10,000 characters. DB `TEXT` is unchanged; this is the D-07/U-05 request guard. |
| Property type | `category_id` → `RESIDENTIAL`/`COMMERCIAL`, plus `subtype_id` → runtime reference data | **Reuse the normalized two-level model.** Do not add a free-text `propertyType` column. |
| Listing type (sale/rent) | `listing_type` + `ListingType{SALE,RENT}` + DB CHECK | **Reuse.** Required enum in DTO. |
| Price | `price NUMERIC(14,2)` with `ck_properties_price_positive`; `currency_code CHAR(3)` | **Reuse.** Price > 0 and scale ≤2; currency is a required uppercase active ISO 4217 code. API §6.2 requires the field, so no implicit server default is applied; `INR` is the approved example/UI convention (U-06). |
| Address | `address_line`, `locality`, `city`, `state`, `country`, `postal_code` | **Reuse as a nested `location` DTO.** `city`, `state`, `country` are NOT NULL in DB and therefore required. |
| Location | `latitude NUMERIC(10,7)`, `longitude NUMERIC(10,7)` with range CHECKs | **Reuse.** Optional pair; validate both-or-neither and DB ranges in the DTO. |
| Bedrooms / bathrooms | `PropertyResidentialDetails.bedrooms`, `.bathrooms` (`Short`) | **Reuse, category-specific.** Not on the base property. Commercial uses `washrooms`. |
| Area and unit | `area_value NUMERIC(12,2)`, `area_unit VARCHAR(20)` | **Reuse.** `SQ_FT` is the convention in API §6.2; both-or-neither validation. |
| Amenities | `Amenity` + `PropertyAmenity` join with composite PK | **Reuse.** DTO carries `amenityIds`; service resolves and de-duplicates them. |
| Property status | `status` + `PropertyStatus` + DB CHECK (six values) | **Reuse existing values**; see §4 and U-01. |
| Assigned agent | `primary_agent_id` FK → `users.id`, `@ManyToOne User primaryAgent` | **Reuse.** No separate Agent entity. Validate target is ACTIVE with role `AGENT`. |
| Created / updated timestamps | `created_at`, `updated_at` (`@CreationTimestamp` / `@UpdateTimestamp`); `published_at`, `archived_at` | **Reuse.** Never client-writable. |

### 3.2 Proposed aggregate boundaries

```
Property (aggregate root, table: properties)
├── PropertyCategory      (ManyToOne, reference data)
├── PropertySubtype       (ManyToOne, reference data; must belong to category)
├── User primaryAgent     (ManyToOne, ACTIVE AGENT required)
├── User createdBy        (ManyToOne, session principal at creation)
├── User updatedBy        (ManyToOne, session principal at last mutation)
├── PropertyResidentialDetails  (OneToOne, shared PK; only for RESIDENTIAL)
├── PropertyCommercialDetails   (OneToOne, shared PK; only for COMMERCIAL)
├── PropertyAmenity[]     (join rows referencing Amenity reference data)
└── PropertyImage[]       (separate aggregate for lifecycle; refs only, no bytes)
```

- **Exactly one detail row per property**, selected by category. The database cannot enforce "exactly one and correct for category"; this is a **service-layer invariant** (per `PropertyResidentialDetails` Javadoc and DATABASE_DESIGN §6.5). No new table is needed.
- **Exactly one primary agent, no unassigned state** (VERIFIED `optional = false`, DB `NOT NULL`, DATABASE_DESIGN §6.3 rule 1, SD-35). Reassignment replaces `primary_agent_id` atomically.
- **Image rows are metadata only.** Bytes live in the approved external object store; the database stores provider, public ID, URL, alt text, ordering, and primary flag (VERIFIED V6).

### 3.3 DTO strategy (FINALIZED)

| DTO | Purpose | Notes |
|---|---|---|
| `CreatePropertyRequest` | Admin-only creation | Includes `primaryAgentId` as a **privileged assignment target** (see §3.4), never as the actor identity. |
| `PropertyUpdateRequest` | Full/partial property mutation | Explicit allowlist; `id`, `status`, `primaryAgentId`, `createdBy`, `updatedBy`, timestamps, and image/provider fields rejected via `UnknownFieldGuard`. |
| `PropertyStatusChangeRequest` | Lifecycle transition | Single `status` target; validated against §4 transition table. |
| `PropertyAssignmentRequest` | Admin reassignment | `primaryAgentId` only. |
| `ImageMetadataUpdateRequest` | Image alt text / sort order | No URL, provider, or public ID accepted. |
| `PropertySummaryResponse` | List/search results | Flattened fields for the existing UI shape (category name, subtype name, bedrooms/bathrooms, primary image URL, status). No agent contact details in public responses. |
| `PropertyDetailResponse` | Single property | Includes category-specific details and amenity names; never `passwordHash`, audit internals, storage credentials, or provider signatures. |
| `PropertyImageResponse` | Image metadata | Exposes public `imageUrl` only for publicly visible properties; safe `altText`; no secret material. |
| `PageResponse<T>` | Pagination wrapper | Zero-based page, default size 12 public / 20 management, max 100 (API §25.2). |

Validation uses Bean Validation on DTOs plus `UnknownFieldGuard` for mass assignment; the database constraints remain the final integrity layer. Exact request-level limits are: title ≤200, description ≤10,000 (trimmed, non-blank), `areaUnit` ≤20, `altText` ≤255, price `NUMERIC(14,2)` >0, `areaValue` `NUMERIC(12,2)` >0 when present, latitude/longitude within the DB CHECK ranges and both-or-neither, non-negative category-specific counts, subtype must belong to category, amenity IDs must exist and be de-duplicated, and currency must be an active ISO 4217 alpha-3 code. Field lengths already present in the database are preserved; no schema change is proposed. Contract limitations: the DB does not enforce the description cap, ISO-code currency validity, “exactly one detail row for the category”, or server-generated slug semantics — these are Stage 4.2 service/DTO invariants.

### 3.4 Ownership derivation — explicit rule

| Situation | Trusted source | Client-supplied ownership identifier |
|---|---|---|
| Actor identity for create/update/status/image operations | `AuthenticatedAccount.id()` from session-resolved principal (`AccountAuthorizationService.requireActive`) | **Never trusted.** A body/query/header `createdBy`, `updatedBy`, `ownerId`, `agentId`, or `userId` is rejected as an unknown field. |
| AGENT ownership scope on an existing property | `Property.primaryAgent.id` compared with `AuthenticatedAccount.id()` via `ResourceAuthorizationService.requirePropertyAssignment` | Ignored; a client cannot claim another agent's property. |
| ADMIN creation/reassignment target (`primaryAgentId`) | Admin-supplied UUID is validated server-side against `users` (`role.name = 'AGENT'`, `account_status = 'ACTIVE'`) | Accepted **only** as an explicitly privileged assignment target on the two admin endpoints, and only after validation. It is not actor identity and is audited. This preserves FR-PROP-001 and DATABASE_DESIGN §6.3 rule 2. |
| Image ownership | Resolve `propertyId` from the path, verify `imageId` belongs to that property, then apply the property assignment policy | Client cannot supply an owning property or agent. |

**Security note (SD4-03).** The brief's instruction "never trust client-supplied ownership identifiers" is satisfied for actor identity and agent scope. The admin's `primaryAgentId` is a distinct, pre-existing business capability (assignment), not an ownership claim; treating it as untrusted-and-rejected would make admin creation impossible without an approved FRD change. This distinction must be preserved in implementation and tests. Unresolved U-02 asks whether a future stage should add agent self-service creation, which would remove the need for this field on that flow.

---

## 4. Property lifecycle design

### 4.1 Status evaluation

**VERIFIED:** `PropertyStatus` and `V5` allow `DRAFT`, `PUBLISHED`, `UNAVAILABLE`, `SOLD`, `RENTED`, `ARCHIVED` (`PROPERTY_DOMAIN_ASSESSMENT.md` §3.2–3.3).

**Final decision SD4-02 / D-04:** retain **exactly** `DRAFT`, `PUBLISHED`, `UNAVAILABLE`, `SOLD`, `RENTED`, `ARCHIVED`. Do **not** add `UNPUBLISHED` and do not create a seventh-status migration. `UNAVAILABLE` carries the approved "withdrawn from public visibility" semantics in C-10/D-08 and `API_SPECIFICATION.md` §7; adding another value would require migration and visibility re-approval with no distinct behaviour. `SOLD` and `RENTED` are outcome states already referenced by the approved public-visibility rule.

U-01 is **RESOLVED**: no distinct "temporarily hidden by agent" state exists in this scope. Any future proposal must be handled as a versioned schema/document change, not a code-only enum edit.

### 4.2 Proposed state meanings

| Status | Meaning | Public visibility (C-10/D-08) |
|---|---|---|
| `DRAFT` | Incomplete or not yet offered publicly. Created on admin creation. | Hidden |
| `PUBLISHED` | Active public listing. | Visible |
| `UNAVAILABLE` | Withdrawn / not currently available (the "unpublish" target). | Hidden |
| `SOLD` | Completed sale outcome; historical reference. | Hidden |
| `RENTED` | Completed rental outcome; historical reference. | Hidden |
| `ARCHIVED` | Logical deletion; retained for history. Terminal in MVP. | Hidden |

### 4.3 Authoritative transition matrix (D-06)

This is the single authoritative lifecycle table. It reconciles the former “no admin-only statuses for agents” wording in `SECURITY_ARCHITECTURE.md` §10.4.3: no status value is reserved from agents, but specific transitions are administrative. No approved role gate, endpoint, or visibility rule is changed.

| From | To | Actor | Conditions / effect |
|---|---|---|---|
| `DRAFT` | `PUBLISHED` | Assigned AGENT or ADMIN | Publication prerequisites satisfied; set `publishedAt = now`. |
| `PUBLISHED` | `UNAVAILABLE` | Assigned AGENT or ADMIN | Unpublish/withdraw. Preserve `publishedAt` as the last-publication time. |
| `UNAVAILABLE` | `PUBLISHED` | Assigned AGENT or ADMIN | Re-run publication prerequisites; refresh `publishedAt = now`. |
| `PUBLISHED` | `SOLD` | Assigned AGENT or ADMIN | Permitted only when `listingType = SALE`. |
| `PUBLISHED` | `RENTED` | Assigned AGENT or ADMIN | Permitted only when `listingType = RENT`. |
| `SOLD` | `PUBLISHED` | ADMIN only | Reopening a completed sale; re-run publication prerequisites and refresh `publishedAt`. |
| `RENTED` | `PUBLISHED` | ADMIN only | Reopening a completed rental; re-run publication prerequisites and refresh `publishedAt`. |
| `DRAFT`, `PUBLISHED`, `UNAVAILABLE`, `SOLD`, `RENTED` | `ARCHIVED` | ADMIN only | Logical archive via `DELETE /api/v1/properties/{propertyId}`; set `archivedAt = now`. |
| `ARCHIVED` | any | — | **Not permitted.** `ARCHIVED` is terminal; no unarchive flow. |
| `PUBLISHED` | `DRAFT` | — | **Not permitted.** Use `UNAVAILABLE` to withdraw. |
| `DRAFT` | `SOLD` / `RENTED` | — | **Not permitted.** A listing must be published first. |
| `SOLD` / `RENTED` | `UNAVAILABLE` | — | **Not permitted.** Admin reopening targets `PUBLISHED`; agents may then use the normal unpublish transition. |
| `UNAVAILABLE` | `SOLD` / `RENTED` | — | **Not permitted.** An outcome is recorded from `PUBLISHED` only. |
| any | itself | — | **Not permitted.** Same-status requests return `409 CONFLICT` and leave timestamps unchanged. |
| any other pair | — | — | **Not permitted.** Return `409 CONFLICT` with the standard error envelope. |

Agents therefore cannot create properties (D-05), but may publish, unpublish, republish, and record the matching `SOLD`/`RENTED` outcome for properties currently assigned to them. Only ADMIN may reopen `SOLD`/`RENTED`, reassign, or archive. Buyers have no property-management permission.

**Publication prerequisites.** Every transition into `PUBLISHED` requires: non-blank title (≤200) and description (≤10,000); valid category/subtype pair; `SALE` or `RENT`; positive price with active ISO 4217 `currencyCode`; required `city`, `state`, `country`; both-or-neither latitude/longitude within DB ranges; exactly one category-specific detail row matching the category; a current assignment to an ACTIVE `AGENT`; and at least one image with exactly one primary image. A well-formed request that fails these prerequisites returns `422 UNPROCESSABLE_ENTITY`; image-related failure remains a Stage 4.5 dependency and must not be relaxed in Stage 4.2.

**Enforcement and audit.** One transactional service method applies this matrix after `ResourceAuthorizationService` checks; controllers do not decide transitions. The method records property ID, actor, from/to status, outcome, and result via `AuditEventService` without logging secrets or file bytes. An illegal transition or same-status request returns `409`; a wrong outcome for `listingType` returns `400 VALIDATION_ERROR`; an actor lacking permission returns `403`; a missing property returns `404`.

### 4.4 Timestamps and soft deletion

- `createdAt` / `updatedAt`: Hibernate-managed; never client-writable.
- `publishedAt`: set on **every** transition into `PUBLISHED`, including republish and admin reopening (latest publication time). No separate first-publication column is added; U-07 is resolved on that basis and `/properties/recent` uses this field.
- `archivedAt`: set once on archive; archival is terminal so it is never cleared. Same-status and rejected transitions leave all timestamps unchanged.
- **No physical delete** of properties, images, inquiries, or conversations. Verified FK behaviour (`PROPERTY_DOMAIN_ASSESSMENT.md` §3.3) makes physical property deletion impossible while history exists and would violate FR-PROP-005 / API §25.1.C.
- Historical assignment: `inquiries.assigned_agent_id` and `conversations.assigned_agent_id` are independent of `properties.primary_agent_id` (VERIFIED V9/V10). Reassignment must not rewrite them (API §25.1.F).

---

## 5. Property image architecture (evaluated, NOT implemented)

### 5.1 Verified starting point

- `PropertyImage` entity and V6 table already exist: `storage_provider`, `storage_public_id`, `image_url`, `alt_text`, `sort_order`, `is_primary`, `created_at`.
- Partial unique index `uk_property_images_primary` enforces at most one primary image per property (VERIFIED).
- V6 FK to `properties` is `ON DELETE RESTRICT`: archiving (not row deletion) is required to retain image metadata safely.
- No Cloudinary SDK dependency, no multipart configuration, no upload service, and no image controller exist (VERIFIED NOT FOUND).
- Approved upstream design: `SECURITY_ARCHITECTURE.md` §13 — Cloudinary or compatible object store, backend-mediated signed uploads, no direct browser upload, no secret or signature in the browser, metadata in PostgreSQL.

### 5.2 Proposed architecture (for a later implementation stage)

1. **Delivery path:** browser → `POST /api/v1/properties/{propertyId}/images` (multipart, session cookie, `X-CSRF-TOKEN`) → Spring Boot validation → server-side signed Cloudinary upload → `property_images` row → safe metadata response. A future image-delivery/CDN decision must not expose private URLs for non-published properties (SD-31 / C-10).
2. **Storage:** Cloudinary `cloud_name`, `api_key`, `api_secret` as server-side environment secrets only. Deterministic folder such as `estatevista/properties/{propertyId}`; server-generated UUID `storage_public_id`. Client never chooses a path and never receives a signature, preset, or secret.
3. **Metadata:** only the verified columns are written. `imageUrl` must be validated as `https:` on an approved delivery host (or a controlled relative URL); never accept a client-supplied URL as authoritative. No new columns required for MVP.
4. **Ordering:** `sort_order` is an explicit integer maintained by the service; the verified index `(property_id, sort_order)` supports ordered reads. Reordering is metadata-only and never changes `property_id` or `storage_public_id`.
5. **Primary selection:** transactional "unset all others for this property, set target true"; the partial unique index is the final guard. Setting primary on an image of a different property is denied.
6. **Deletion:** backend deletes the Cloudinary asset by stored `storage_public_id`, then removes the row (or records a cleanup task on partial failure, SD-32). Deleting a row does not by itself delete the external asset (VERIFIED entity Javadoc). Default if the primary image is deleted: leave the property with no primary until an authorized manager sets one (U-08); auto-promotion of the lowest `sort_order` is an alternative requiring a product decision.
7. **Validation pipeline (approved SD-30, to implement later, not now):** authentication + CSRF → authorization → rate/size limits → `multipart/form-data` and single `file` part → extension allowlist → declared MIME allowlist → magic-byte check (JPEG `FF D8 FF`, PNG `89 50 4E 47 0D 0A 1A 0A`, WebP `RIFF`/`WEBP`) → decoder validation → dimension/pixel checks → full decode + re-encode + orientation correction → strip EXIF/GPS/metadata → server-side upload → DB write → audit → safe response.
8. **Limits (approved SD-30):** JPEG/PNG/WebP only (no SVG/GIF/BMP/TIFF/HEIC/executables); ≤5 MB per file, ≤6 MB request, 200×200…6000×6000, ≤40 megapixels, ≤10 images per property, ≤1 file per request, ≤255 char alt text; rate limit baseline 20 uploads/hour/user.
9. **Ownership:** resolve property from path, verify `imageId` belongs to it, then apply `requirePropertyManagement`. Agents may not upload to a property assigned to another agent or to an `ARCHIVED` property; administrators may act on any property. Buyers have no image capability. A reassignment takes effect on the next request.
10. **Audit:** record property ID, actor, image ID, action (upload/metadata/primary/delete), and result via `AuditEventService`; never log bytes, secret material, or tokens (VERIFIED audit redaction policy).

**Stage 4.5 approval checklist (D-08, recorded not authorized here).** Before image implementation, a separate approval must cover: (1) **dependency** — Cloudinary SDK version and any WebP decoder, with supply-chain review; (2) **configuration** — multipart file/request limits (5 MB/6 MB) and approved provider delivery settings; (3) **credentials** — `cloud_name`, `api_key`, `api_secret` as server-side environment secrets only, never in repository/frontend/database/logs; (4) **validation** — the full SD-30 pipeline in §5.2 item 7 and the exact allowlist/limits in item 8; (5) **failure handling** — validation `400`/`413`/`415`, provider failure safe `503` with no provider body, DB-write failure compensating asset deletion or cleanup task, duplicate primary `409`, and audited outcomes; (6) **ownership/privacy** — current-assignment checks, SD-31 signed/private delivery for non-published images, and C-10/D-08 public filtering. No SDK, dependency, endpoint, service, configuration, or storage integration was added in Stage 4.1.

---

## 6. Architectural trade-offs

| Area | Alternative considered | Decision and rationale |
|---|---|---|
| Status vocabulary | Add `UNPUBLISHED` as the brief lists | **Reject (D-04).** Existing DB CHECK, enum, C-10/D-08, API §7 and DATABASE_DESIGN §6.3 all use `UNAVAILABLE`. U-01 is resolved with no seventh status or migration. |
| Property type | Single denormalized `property_type` field | **Reject.** Existing `category` + `subtype` tables already normalize this and enforce subtype/category consistency via composite FK. |
| Bedrooms/bathrooms on base table | Add columns to `properties` | **Reject.** Existing residential/commercial detail tables already model category-specific attributes with shared PKs. |
| Image storage in PostgreSQL (`bytea`) | Store bytes in DB | **Reject.** Approved external object storage; existing `property_images` stores references only. DB keeps metadata and ordering. |
| Ownership field | New `owner_id` or separate ownership table | **Reject.** `primary_agent_id` is the verified single source; adding another creates ambiguity and conflicts with V5. |
| Agent entity | Separate `Agent` entity/table | **Reject.** Verified model is `User` + `Role.AGENT` + optional `AgentProfile`; property already FKs to `users`. |
| Authorization | New property-specific security filter/annotations | **Reject.** Reuse `ResourceAuthorizationService`; filter chain handles coarse role gates only. |
| Creation | Agent self-service creation | **Reject for Stage 4.** D-05 confirms FR-PROP-001 and DATABASE_DESIGN §6.3 rule 2: admin creates and assigns. U-02 is resolved. |
| Reopening `SOLD`/`RENTED` | Agent-initiated reopening or `ADMIN`-only direct reopen to `PUBLISHED` | **ADMIN only.** Agents may record outcomes and manage ordinary transitions; reopening completed listings is administrative under D-06. |
| Archival vs deletion | Physical `DELETE` | **Reject.** FKs RESTRICT and history retention rules make it unsafe; use `ARCHIVED` + `archived_at` (D-05). |

---

## 7. Proposed change set for the next stage (NOT performed here)

- New package `com.estatevista.property.service` with a property service, an image service (when approved), and a lifecycle policy component.
- New `com.estatevista.property.dto` request/response records; `com.estatevista.property.web` or controller package following existing controller conventions.
- New repository query methods or specifications on the **existing** repositories for `PUBLISHED`-only public reads, featured/recent/related selection, and ownership-scoped management reads (no new repository interfaces).
- No property schema migration in Stage 4.1 or Stage 4.2 for the lifecycle: U-01 resolved with no seventh status. Separately approved work remains: `is_featured BOOLEAN NOT NULL DEFAULT FALSE` (API §25.1.D) and controlled subtype/amenity reference-data seeding (D-09).
- Server-generated unique slug helper with bounded collision retries against the existing nullable unique column; no client-supplied slug (D-10).
- Approved rate-limit baselines from SECURITY §11.9 for mutation, search, detail, admin mutation, and upload surfaces; no invented production thresholds (D-10).
- New dependencies/configuration **only for the image implementation stage** (Cloudinary SDK, multipart limits) after separate approval (D-08).
- Extend `ResourceAuthorizationService` (or add a clearly named property-image policy that delegates to it) rather than duplicating ownership checks.

---

## 8. Consistency references

- Endpoint contracts and DTO validation: `PROPERTY_API_DESIGN.md`
- Role/ownership permissions and method-specific filter-chain matchers: `PROPERTY_AUTHORIZATION_MATRIX.md`
- Owner decisions D-01…D-11, gap resolutions G-01…G-16, item resolutions U-01…U-09, and preserved F-1…F-8: `STAGE_4_DECISION_REGISTER.md`
- Verified evidence and file paths: `docs/validation/PROPERTY_DOMAIN_ASSESSMENT.md`
- Approved upstream contracts: `API_SPECIFICATION.md`, `SECURITY_ARCHITECTURE.md`, `DATABASE_DESIGN.md`, `FRD.md`, `UI_UX_SPECIFICATION.md`
