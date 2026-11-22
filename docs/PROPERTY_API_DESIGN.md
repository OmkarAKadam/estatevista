# EstateVista V2 — Property REST API Design (Stage 4.1)

## 1. Status and scope

**FINALIZED Stage 4.1 REST contracts** for the property domain. These are design specifications only — no controller, service, DTO class, route, or configuration is implemented in Stage 4.1. Owner decisions D-01…D-11 are incorporated; Stage 4.2 still requires explicit authorization before implementation.

Aligned with:

- Verified conventions: `backend/src/main/java/com/estatevista/common/response/ApiResponse.java`, `ApiErrorResponse.java`, `common/exception/ErrorCode.java`, `common/validation/UnknownFieldGuard.java`, `config/SecurityConfig.java`.
- Approved contracts: `docs/API_SPECIFICATION.md` §6–8, §16–19, §25; `docs/SECURITY_ARCHITECTURE.md` §10.4, §11.9, §13.
- Domain design: `PROPERTY_DOMAIN_DESIGN.md` (statuses, transitions, ownership, image architecture).
- Permissions: `PROPERTY_AUTHORIZATION_MATRIX.md` (endpoint IDs and role/ownership rules are identical here).
- Decision record: `STAGE_4_DECISION_REGISTER.md` (D-01…D-11, G-01…G-16, U-01…U-09).

---

## 2. API conventions

| Item | Convention |
|---|---|
| Base path | `/api/v1` (VERIFIED `docs/API_SPECIFICATION.md` §25.2; all implemented routes use this prefix). |
| Media type | `application/json` for JSON endpoints; `multipart/form-data` only for image upload. Incompatible type → `415 UNSUPPORTED_MEDIA_TYPE`. |
| Authentication | Server-side session via host-only `HttpOnly EV_SESSION` cookie (VERIFIED `SessionConfig`, `SessionAuthorizationFilter`). No bearer tokens. |
| CSRF | `X-CSRF-TOKEN` header with 256-bit session-bound token from `GET /api/v1/auth/csrf` on every mutating request. No property endpoint is exempt (F-1/F-2 remain open). |
| Actor identity | Derived from the session principal by `AccountAuthorizationService`; never from request data. |
| Success envelope | Single resources: `{"success": true, "message"?: string, "data": ...}` (`ApiResponse`). Paginated collections: `{"success": true, "data": [...], "meta": {...}}`; `meta` is a **top-level sibling** of `data`, never nested inside it (API §16, §7.2). `message` is optional on retrievals. |
| Error envelope | Every error: `{"success": false, "error": {"code", "message", "details": [...]}, "timestamp", "path"}` (`ApiErrorResponse`). `details` is empty except field-validation failures, which use `[{"field","message"}]`. Generic messages only; no stack traces, SQL, secrets, or internal paths. |
| Error codes | Verified `ErrorCode` values: `BAD_REQUEST`, `VALIDATION_ERROR`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `METHOD_NOT_ALLOWED`, `CONFLICT`, `PAYLOAD_TOO_LARGE`, `UNSUPPORTED_MEDIA_TYPE`, `UNPROCESSABLE_ENTITY`, `TOO_MANY_REQUESTS`, `INTERNAL_ERROR`, `SERVICE_UNAVAILABLE`. Provider failure uses `503 SERVICE_UNAVAILABLE` because no `502` constant exists (G-05). |
| Identifiers | UUID, server-generated. Client-supplied `id` fields are rejected. |
| Pagination | Zero-based `page`; default `size` 12 for public discovery / 20 for management lists; maximum 100. `meta` contains exactly `page`, `size`, `totalElements`, `totalPages`, `hasNext`, `hasPrevious`. Invalid sort fields and out-of-range `page`/`size` → `400 VALIDATION_ERROR`. |
| Validation | Bean Validation on DTOs + `UnknownFieldGuard` for mass assignment + DB CHECK/FK constraints as final layer. Field errors use `error.details[{field,message}]`, never echoing values. |
| Visibility | Public reads return only `status = 'PUBLISHED'`; filter in the query **before** pagination, counting, featured/recent/related selection, and image resolution (C-10/D-08). Non-visible detail or image collection to an unauthenticated caller → `404`. |
| Slugs | Server-generated from the title on create, unique, maximum 220 characters, collision-suffixed deterministically, and protected by the existing nullable unique DB column. Clients never supply `slug`; generation retries are bounded and exhaustion returns `409 CONFLICT` (G-13 / D-10). |
| Rate limits | Approved baselines from SECURITY §11.9, not new thresholds: public search 120/minute/IP; property detail 300/minute/IP; authenticated API 300/minute/user; admin mutations 60/minute/admin; image upload 20/hour/user and ≤10 images/property. Exceeded limits → `429 TOO_MANY_REQUESTS`. F-8 (`Retry-After`) remains open. |

---

## 3. DTO contracts (FINALIZED)

### 3.1 `CreatePropertyRequest` (admin only)

| Field | Type | Required | Validation |
|---|---|---|---|
| `title` | String | yes | 1–200 chars after trim; matches `VARCHAR(200)`. |
| `description` | String | yes | Trimmed, non-blank, ≤10,000 characters. The DB remains `TEXT`; this is the Stage 4.2 request guard selected under D-07/U-05, not a schema change. |
| `categoryId` | UUID | yes | Must reference an existing `property_categories` row. |
| `subtypeId` | UUID | yes | Must belong to `categoryId` (DB composite FK + service check). |
| `listingType` | enum | yes | `SALE` or `RENT`. |
| `primaryAgentId` | UUID | yes | Privileged assignment target: must reference an ACTIVE user with role `AGENT`. Not an actor identity. |
| `price` | BigDecimal | yes | > 0, scale ≤ 2, precision ≤ 14. |
| `currencyCode` | String | yes | Uppercase 3-letter active ISO 4217 code validated against a server-maintained allowlist. API §6.2 requires the field, so no implicit server default is applied; `INR` remains the approved example and UI convention only (U-06). |
| `areaValue` | BigDecimal | no | > 0 when present; scale ≤ 2. |
| `areaUnit` | String | no | Required when `areaValue` present; e.g. `SQ_FT`; ≤20 chars. |
| `location` | object | yes | Nested `addressLine` (≤255), `locality` (≤120), `city` (≤100, required), `state` (≤100, required), `country` (≤100, required), `postalCode` (≤20), `latitude`/`longitude` (range −90..90 / −180..180, both-or-neither). |
| `residentialDetails` | object | conditional | Required when category resolves to `RESIDENTIAL`; `bedrooms`, `bathrooms`, `balconies`, `floorNumber`, `totalFloors`, `parkingSpaces` non-negative `Short`; `furnishingStatus` ≤30. |
| `commercialDetails` | object | conditional | Required when category resolves to `COMMERCIAL`; `parkingSpaces`, `floorNumber`, `totalFloors`, `washrooms` non-negative; `powerBackup` boolean; `suitableFor` ≤255. |
| `amenityIds` | UUID[] | no | Existing amenities only; duplicates rejected or de-duplicated deterministically. |

Forbidden (rejected as unknown fields): `id`, `status`, `slug`, `createdBy`, `updatedBy`, `createdAt`, `updatedAt`, `publishedAt`, `archivedAt`, any image/storage field, any role or account field.

### 3.2 `PropertyUpdateRequest` (PATCH/PUT)

Explicit allowlist: `title`, `description`, `listingType`, `price`, `currencyCode`, `areaValue`, `areaUnit`, `location.*`, one of `residentialDetails`/`commercialDetails`, `amenityIds`. For full `PUT`, all required fields must be present; `PATCH` applies only supplied fields. `primaryAgentId` is **not** editable here (use the admin assignment endpoint). `status` is **not** editable here (use the status endpoint). All forbidden fields from §3.1 remain rejected.

### 3.3 `PropertyStatusChangeRequest`

| Field | Type | Required | Validation |
|---|---|---|---|
| `status` | enum | yes | One of the six VERIFIED values; must satisfy the transition table; `ARCHIVED` not accepted here (use archival endpoint). |

### 3.4 `PropertyAssignmentRequest`

| Field | Type | Required | Validation |
|---|---|---|---|
| `primaryAgentId` | UUID | yes | Must reference an ACTIVE user with role `AGENT`; cannot equal the current agent (otherwise `409`). |

### 3.5 `ImageMetadataUpdateRequest`

| Field | Type | Required | Validation |
|---|---|---|---|
| `altText` | String | no | ≤255 chars; treated as text, never HTML. |
| `sortOrder` | Integer | no | ≥0; service normalizes ordering within the property. |

Forbidden: `propertyId`, `storageProvider`, `storagePublicId`, `imageUrl`, `isPrimary`, `id`, `createdAt`.

### 3.6 Response DTOs

| DTO | Fields |
|---|---|
| `PropertySummaryResponse` | `id`, `title`, `slug`, `category`, `subtype`, `listingType`, `price`, `currencyCode`, `city`, `locality`, `areaValue`, `areaUnit`, `bedrooms`, `bathrooms`, `primaryImageUrl`, `status`. |
| `PropertyDetailResponse` | Summary fields plus full `location`, `description`, category-specific details, amenity names/IDs, `primaryAgent` **public-safe display fields only**, `publishedAt`, `createdAt`, `updatedAt`. Public variant omits agent contact/private fields. |
| `PropertyImageResponse` | `id`, `imageUrl`, `altText`, `sortOrder`, `isPrimary`. No provider credentials, public ID, signature, or internal path in public responses; full metadata only for authorized manager responses. |
| Paged envelope | A Stage 4.2 response record with `success`, `data` (array of `T`) and top-level `meta` as defined in §2. It is not `ApiResponse<T>` with nested pagination; no JPA entity is serialized. |

No JPA entity is ever serialized directly (API §25.3.9).

---

## 4. Property management endpoints

All endpoints below require a valid session and CSRF token unless marked public. Error codes use the VERIFIED `ErrorCode` names.

### 4.1 `POST /api/v1/properties` — create property

| Attribute | Value |
|---|---|
| Method / URL | `POST /api/v1/properties` (D-01; approved `API_SPECIFICATION.md` §6.1 and `SECURITY_ARCHITECTURE.md` §10.4.3). The former `/api/v1/admin/properties` proposal is withdrawn. |
| Authentication | Required (session + CSRF) |
| Role | `ADMIN` only. Stage 4.2 must add a **method-specific** Spring Security matcher for `POST /api/v1/properties` with `hasRole("ADMIN")`, plus a service-layer role check; it must not rely on the `/api/v1/admin/**` prefix or change the approved URL. |
| Ownership restriction | `createdBy` = session principal; `primaryAgentId` is a validated ACTIVE `AGENT` assignment target; no client-supplied actor identity. |
| Request DTO | `CreatePropertyRequest` (§3.1) |
| Response DTO | `201 Created` → `ApiResponse<PropertyDetailResponse>` with `Location` header to `GET /api/v1/properties/{propertyId}` |
| Defaults | `status = DRAFT`; `publishedAt = null`; `updatedBy = null` until first update; `archivedAt = null`; `slug` server-generated; timestamps server-generated. |
| Validation | All §3.1 rules; category/subtype consistency; ACTIVE `AGENT` assignment target; one correct category detail row; bounded unique slug generation. |
| Success codes | `201` |
| Errors | `400 VALIDATION_ERROR` (field rules, invalid subtype/category pair, ineligible agent); `401 UNAUTHORIZED`; `403 FORBIDDEN` (non-admin); `404 NOT_FOUND` (category/subtype/amenity/agent not found); `409 CONFLICT` (duplicate slug after bounded retries); `429 TOO_MANY_REQUESTS`; `500 INTERNAL_ERROR`. |
| Security notes | Never accept `createdBy`/`id`/`status`/`slug` from the client. Audit actor + property ID (AR-05). No approved contract is changed to simplify security configuration. |

### 4.2 `GET /api/v1/properties/{propertyId}` — retrieve property

| Attribute | Value |
|---|---|
| Method / URL | `GET /api/v1/properties/{propertyId}` |
| Authentication | Public (conditional visibility) |
| Role | None required for `PUBLISHED`; authenticated assigned AGENT or ADMIN may read non-published |
| Ownership restriction | If caller is AGENT, `primary_agent_id = principal.id` required for non-published; otherwise generic `404`. |
| Request DTO | Path `propertyId` only; ignored query fields rejected or ignored consistently |
| Response DTO | `200 OK` → `ApiResponse<PropertyDetailResponse>` |
| Validation | Valid UUID path variable. |
| Success codes | `200` |
| Errors | `400 BAD_REQUEST` (malformed UUID); `404 NOT_FOUND` (missing, non-published, or unauthorized). Do **not** return `403` for hidden properties to unauthenticated callers. |
| Security notes | Public variant excludes private agent information and internal fields (API §7.2). |

The public `property-details.html` page consumes this endpoint with the exact
UUID from `?id=<uuid>`, plus the approved image and related endpoints. It renders
only `PUBLISHED` data with safe DOM insertion; malformed IDs, hidden/missing
properties, and network failures show curated states. This integration adds no
endpoint and does not change the visibility rule above. The home-page featured
rail remains a local showcase because the `featured` endpoint is deferred pending
an approved `is_featured` contract.

### 4.3 `GET /api/v1/properties` — public search/list

| Attribute | Value |
|---|---|
| Method / URL | `GET /api/v1/properties` |
| Authentication | Public |
| Role | None |
| Ownership restriction | Query filtered to `status = 'PUBLISHED'` at repository level before pagination. |
| Request DTO | Query params from API §7.1: `keyword`, `categoryId`, `subtypeId`, `listingType`, `minPrice`, `maxPrice`, `city`, `locality`, `minBedrooms`, `maxBedrooms`, `amenityIds`, `sort` (`newest|oldest|price_asc|price_desc`), `page`, `size`. |
| Response DTO | `200 OK` → paged `ApiResponse<PropertySummaryResponse>` with `meta`. |
| Validation | Unknown params rejected or ignored consistently; `minPrice ≤ maxPrice`; `minBedrooms ≤ maxBedrooms`; `size ≤ 100`; non-negative `page`. |
| Success codes | `200` |
| Errors | `400 VALIDATION_ERROR`; `429`; `500`. |
| Security notes | Totals, featured/recent/related lists must apply the same filter; no hidden-record leakage through counts. |

### 4.4 `GET /api/v1/agents/me/properties` — agent's assigned properties

| Attribute | Value |
|---|---|
| Method / URL | `GET /api/v1/agents/me/properties` |
| Authentication | Required |
| Role | `AGENT` (existing `/api/v1/agents/me/**` gate + `requirePropertyAssignment`/scoped query) |
| Ownership restriction | Results limited to `primary_agent_id = principal.id`; no agent ID accepted from the client. |
| Request DTO | `page`, `size`, optional `status` filter (six valid values) |
| Response DTO | `200 OK` → paged `ApiResponse<PropertySummaryResponse>` |
| Validation | Pagination bounds; status enum. |
| Errors | `400`, `401`, `403`, `429`, `500`. |

### 4.5 `GET /api/v1/admin/properties` — administrative list

| Attribute | Value |
|---|---|
| Method / URL | `GET /api/v1/admin/properties` |
| Authentication | Required |
| Role | `ADMIN` (filter chain + service) |
| Ownership restriction | None; all statuses including `DRAFT`/`ARCHIVED`. |
| Request DTO | Pagination plus optional filters: `status`, `primaryAgentId`, `categoryId`, `city`, `keyword`. |
| Response DTO | `200 OK` → paged `ApiResponse<PropertySummaryResponse>` |
| Errors | `400`, `401`, `403`, `429`, `500`. |

### 4.6 `PATCH /api/v1/properties/{propertyId}` — partial update

| Attribute | Value |
|---|---|
| Method / URL | `PATCH /api/v1/properties/{propertyId}` |
| Authentication | Required |
| Role | Assigned `AGENT` or `ADMIN` |
| Ownership restriction | `requirePropertyManagement(account, propertyId)`; `ARCHIVED` properties rejected. |
| Request DTO | `PropertyUpdateRequest` (§3.2) with only allowed fields present |
| Response DTO | `200 OK` → `ApiResponse<PropertyDetailResponse>` |
| Validation | §3 allowlist; all changed fields validated; category/detail consistency; `updatedBy` set from principal. |
| Errors | `400 VALIDATION_ERROR`; `401`; `403 FORBIDDEN` (other agent/buyer, archived for agent); `404 NOT_FOUND`; `409 CONFLICT` (illegal field combination); `429`; `500`. |

### 4.7 `PUT /api/v1/properties/{propertyId}` — full replacement

Same role, ownership, validation, response, and errors as §4.6, except every required field must be supplied (missing required fields → `400`). Status, assignment, IDs, audit fields, and image fields remain non-writable. Equivalent to replacing all allowed fields in one transaction.

### 4.8 `PATCH /api/v1/properties/{propertyId}/status` — publish / unpublish / outcome

| Attribute | Value |
|---|---|
| Method / URL | `PATCH /api/v1/properties/{propertyId}/status` |
| Authentication | Required |
| Role | Assigned `AGENT` or `ADMIN`; `ADMIN` only for `SOLD`/`RENTED` reopening. |
| Ownership restriction | `requirePropertyManagement`; `ARCHIVED` properties rejected. |
| Request DTO | `PropertyStatusChangeRequest` (§3.3) |
| Response DTO | `200 OK` → `ApiResponse<PropertyDetailResponse>` with the updated resource |
| Validation | The single authoritative transition matrix in `PROPERTY_DOMAIN_DESIGN.md` §4.3; publication prerequisites (complete required fields, one correct category detail row, ≥1 image and exactly one primary); `SALE→SOLD` / `RENT→RENTED` outcome match; `publishedAt` set on every entry to `PUBLISHED`. |
| Errors | `400 VALIDATION_ERROR` (unknown status, `ARCHIVED` target, outcome/listing-type mismatch); `401 UNAUTHORIZED`; `403 FORBIDDEN` (buyer, unassigned agent, agent attempting admin-only reopening/archive); `404 NOT_FOUND` (missing property); `409 CONFLICT` (illegal transition or same status); `422 UNPROCESSABLE_ENTITY` (well-formed request whose publication prerequisites are unmet, including an inactive current primary-agent assignment); `429 TOO_MANY_REQUESTS`; `500 INTERNAL_ERROR`. |
| Security notes | `ARCHIVED` is not accepted here; use §4.9. Every accepted and rejected transition is audited with property ID, actor, from/to status, and result. Same-status requests return `409` and do not alter timestamps. |

### 4.9 `DELETE /api/v1/properties/{propertyId}` — logical archive

| Attribute | Value |
|---|---|
| Method / URL | `DELETE /api/v1/properties/{propertyId}` |
| Authentication | Required |
| Role | `ADMIN` |
| Ownership restriction | Any property; no physical delete. |
| Request DTO | None (path only) |
| Response DTO | `200 OK` → `ApiResponse<PropertyDetailResponse>` containing the updated manager-view resource, including `status = ARCHIVED` and `archivedAt`. No `204` alternative is open (D-03/U-09). |
| Validation | Property exists; not already `ARCHIVED`. Atomically sets `status = ARCHIVED`, `archivedAt = now`, `updatedBy = session principal`. History and images are retained. |
| Errors | `400 BAD_REQUEST` (malformed ID); `401 UNAUTHORIZED`; `403 FORBIDDEN`; `404 NOT_FOUND` (missing property); `409 CONFLICT` (already archived); `429 TOO_MANY_REQUESTS`; `500 INTERNAL_ERROR`. |
| Security notes | Preserves inquiries, conversations, messages, and image metadata (RESTRICT FKs). Does not delete Cloudinary assets at archive time. |

### 4.10 `PATCH /api/v1/admin/properties/{propertyId}/assignment` — administrative reassignment

| Attribute | Value |
|---|---|
| Method / URL | `PATCH /api/v1/admin/properties/{propertyId}/assignment` |
| Authentication | Required |
| Role | `ADMIN` (filter chain + service) |
| Ownership restriction | Any property; new primary agent validated server-side. |
| Request DTO | `PropertyAssignmentRequest` (§3.4) |
| Response DTO | `200 OK` → `ApiResponse<PropertyDetailResponse>` (manager view) |
| Validation | Target user exists, role `AGENT`, `ACTIVE`; differs from current agent; audit event. |
| Errors | `400 VALIDATION_ERROR`; `401`; `403`; `404` (property or agent not found); `409 CONFLICT` (same agent); `429`; `500`. |
| Security notes | Must not modify `inquiries.assigned_agent_id` or `conversations.assigned_agent_id` (API §25.1.F). Old agent loses access on the next request; new agent gains it. |

### 4.11 `GET /api/v1/properties/featured` — featured properties

| Attribute | Value |
|---|---|
| Method / URL | `GET /api/v1/properties/featured` |
| Authentication | Public |
| Role | None |
| Response DTO | `200 OK` → paginated envelope with `PropertySummaryResponse[]` and top-level `meta` (default `size` 12, max 100) |
| Query / visibility | No client-supplied property IDs; only `PUBLISHED` records, filtered in the query before pagination/counting. |
| Errors | `400 VALIDATION_ERROR`; `429 TOO_MANY_REQUESTS`; `500 INTERNAL_ERROR`. |
| Stage ownership / dependency | **Stage 4.2** backend + **Stage 4.7** frontend. API §25.1.D requires a future Flyway migration adding `is_featured BOOLEAN NOT NULL DEFAULT FALSE` and admin-only featured control. No migration is created in Stage 4.1; the approved endpoint is preserved in this plan and must not be removed or replaced by `recent`. |

### 4.12 `GET /api/v1/properties/recent` — recently published properties

| Attribute | Value |
|---|---|
| Method / URL | `GET /api/v1/properties/recent` |
| Authentication | Public |
| Role | None |
| Response DTO | `200 OK` → paginated envelope with `PropertySummaryResponse[]` and top-level `meta` |
| Query / visibility | Order by `publishedAt DESC`; only `PUBLISHED` records, filtered before pagination/counting. Re-publishing refreshes `publishedAt` (D-07). |
| Errors | `400`; `429`; `500`. |
| Stage ownership | **Stage 4.2** backend + **Stage 4.7** frontend. Depends on `publishedAt` semantics and a bounded `size` parameter; no new threshold is invented. |

### 4.13 `GET /api/v1/properties/{propertyId}/related` — related properties

| Attribute | Value |
|---|---|
| Method / URL | `GET /api/v1/properties/{propertyId}/related` |
| Authentication | Public |
| Role | None |
| Response DTO | `200 OK` → paged or bounded-list envelope as agreed in the endpoint contract, using `PropertySummaryResponse[]` and top-level `meta` when paginated |
| Query / visibility | Resolve the source property first; a missing or non-`PUBLISHED` source returns `404`. Candidates are `PUBLISHED` only, filtered before limiting/selecting. Selection may use category/subtype/locality but must not leak hidden records or counts. |
| Errors | `400 BAD_REQUEST` (malformed UUID); `404 NOT_FOUND`; `429`; `500`. |
| Stage ownership | **Stage 4.2** backend + **Stage 4.7** frontend. |

### 4.14 `GET /api/v1/property-categories` — categories lookup

| Attribute | Value |
|---|---|
| Method / URL | `GET /api/v1/property-categories` |
| Authentication | Public |
| Role | None |
| Response DTO | `200 OK` → `ApiResponse<List<ReferenceDataResponse>>` containing only `id`, `name` |
| Errors | `429`; `500`. |
| Stage ownership / data | **Stage 4.4** backend + **Stage 4.7** frontend. `RESIDENTIAL` and `COMMERCIAL` are already seeded by V4. Read-only; no public mutation endpoint. |

### 4.15 `GET /api/v1/property-subtypes` — subtypes lookup

| Attribute | Value |
|---|---|
| Method / URL | `GET /api/v1/property-subtypes` |
| Authentication | Public |
| Role | None |
| Query | Optional `categoryId` filter; invalid filter → `400`. |
| Response DTO | `200 OK` → `ApiResponse<List<ReferenceDataResponse>>` containing only `id`, `categoryId`, `name` |
| Errors | `400`; `429`; `500`. |
| Stage ownership / data | **Stage 4.4** backend + **Stage 4.7** frontend. Initial values are the names documented in `DATABASE_DESIGN.md` §6.2 (Apartment, Villa, Independent House, Penthouse, Residential Plot; Office Space, Retail Shop, Showroom, Warehouse, Commercial Plot), supplied by a controlled migration or approved seeding mechanism. No rows or migration are created in Stage 4.1. |

### 4.16 `GET /api/v1/amenities` — amenities lookup

| Attribute | Value |
|---|---|
| Method / URL | `GET /api/v1/amenities` |
| Authentication | Public |
| Role | None |
| Response DTO | `200 OK` → `ApiResponse<List<ReferenceDataResponse>>` containing only `id`, `name`, optional public `description` |
| Errors | `429`; `500`. |
| Stage ownership / data | **Stage 4.4** backend + **Stage 4.7** frontend. Initial values are the examples in `DATABASE_DESIGN.md` §8.1 (Swimming Pool, Gym, Parking, Security, Garden, Lift, Power Backup, Clubhouse), supplied by a controlled migration or approved seeding mechanism. No rows or migration are created in Stage 4.1. |

**Stage 4 / Stage 5 responsibility:** all endpoints in §4.1–4.16 and the image endpoints in §5 are property-management/discovery work assigned to Stages 4.2–4.7. Favorites, inquiries, conversations, dashboards, and notifications remain Stage 5+ work; their approved contracts are neither implemented nor removed here.

---

## 5. Property image endpoints (design only — NOT implemented)

Implementation prerequisites: Cloudinary SDK dependency, multipart configuration, upload service, and the SD-30 validation pipeline (`PROPERTY_DOMAIN_DESIGN.md` §5.2). None exists in Stage 4.1.

### 5.1 `POST /api/v1/properties/{propertyId}/images`

| Attribute | Value |
|---|---|
| Method / URL | `POST /api/v1/properties/{propertyId}/images` |
| Authentication | Required |
| Role | Assigned `AGENT` or `ADMIN` |
| Ownership restriction | Property from path; agent must be current `primary_agent_id`; property not `ARCHIVED`. |
| Request | `multipart/form-data`, single field `file`; optional `altText`, `isPrimary`, `sortOrder` form fields (server-controlled provider/public ID only) |
| Response DTO | `201 Created` → `ApiResponse<PropertyImageResponse>` |
| Validation | SD-30 pipeline: auth/CSRF → ownership → rate/size limits → content type → filename ignored → extension/MIME allowlist → magic bytes → decoder → dimensions/pixels → no animation → decode+re-encode+strip metadata → server-side signed Cloudinary upload → DB row → audit. ≤5 MB file, ≤6 MB request, JPEG/PNG/WebP only, 200×200–6000×6000, ≤40 MP, ≤10 images/property. |
| Errors | `400 VALIDATION_ERROR`; `401`; `403`; `404` (property missing/unauthorized); `409 CONFLICT` (count limit); `413 PAYLOAD_TOO_LARGE`; `415 UNSUPPORTED_MEDIA_TYPE`; `429 TOO_MANY_REQUESTS`; `503 SERVICE_UNAVAILABLE` (provider failure, safe message); `500`. |
| Security notes | No direct browser→Cloudinary path; no secret, signature, preset, or provider error body in the response. Compensating asset deletion on DB failure (SD-32). |

### 5.2 `GET /api/v1/properties/{propertyId}/images`

| Attribute | Value |
|---|---|
| Method / URL | `GET /api/v1/properties/{propertyId}/images` |
| Authentication | Public (conditional visibility) |
| Role | None for `PUBLISHED` properties; assigned agent/admin for non-published |
| Ownership restriction | Public caller gets `404` for non-published property; manager sees own property's images. |
| Response DTO | `200 OK` → `ApiResponse<List<PropertyImageResponse>>` ordered by `sort_order` |
| Errors | `400`, `404`; `403` only for authenticated but unauthorized manager access where existence disclosure is acceptable. |

### 5.3 `PATCH /api/v1/properties/{propertyId}/images/{imageId}`

| Attribute | Value |
|---|---|
| Method / URL | `PATCH /api/v1/properties/{propertyId}/images/{imageId}` |
| Authentication | Required |
| Role | Assigned `AGENT` or `ADMIN` |
| Ownership restriction | `imageId` must belong to path `propertyId`, then `requirePropertyManagement`. |
| Request DTO | `ImageMetadataUpdateRequest` (§3.5) |
| Response DTO | `200 OK` → `ApiResponse<PropertyImageResponse>` |
| Validation | `altText` ≤255; `sortOrder` ≥0. Provider/public ID immutable. |
| Errors | `400`, `401`, `403`, `404` (property/image mismatch or missing), `429`, `500`. |

### 5.4 `DELETE /api/v1/properties/{propertyId}/images/{imageId}`

| Attribute | Value |
|---|---|
| Method / URL | `DELETE /api/v1/properties/{propertyId}/images/{imageId}` |
| Authentication | Required |
| Role | Assigned `AGENT` or `ADMIN` |
| Ownership restriction | Image belongs to property; property assignment check; archived property rejects agent deletion. |
| Response DTO | `204 No Content` with **no response body** (D-03/U-09). The client refreshes image metadata separately if needed. |
| Validation | Confirm remote asset deletion by stored `storage_public_id`; audit; on primary deletion leave the property with **no primary image** until an authorized manager explicitly sets one (D-07/U-08). The existing partial unique index remains the final one-primary guard. |
| Errors | `400 BAD_REQUEST` (malformed IDs); `401 UNAUTHORIZED`; `403 FORBIDDEN`; `404 NOT_FOUND` (missing property/image or mismatch); `409 CONFLICT` (provider/cleanup conflict); `503 SERVICE_UNAVAILABLE` (provider failure); `500 INTERNAL_ERROR`. |

### 5.5 `PATCH /api/v1/properties/{propertyId}/images/{imageId}/primary`

| Attribute | Value |
|---|---|
| Method / URL | `PATCH /api/v1/properties/{propertyId}/images/{imageId}/primary` |
| Authentication | Required |
| Role | Assigned `AGENT` or `ADMIN` |
| Ownership restriction | Image belongs to property; property assignment check. |
| Request DTO | None (or empty body); the URL states the intent — no client-supplied `isPrimary` value needed. |
| Response DTO | `200 OK` → `ApiResponse<List<PropertyImageResponse>>` reflecting new ordering/primary |
| Validation | Transactionally unset other primary rows for the property, set target true; DB partial unique index is the final guard. |
| Errors | `400`, `401`, `403`, `404`, `409 CONFLICT` (concurrent primary change), `429`, `500`. |

---

## 6. Error scenario catalogue

| Scenario | Status / code | Notes |
|---|---|---|
| Missing or invalid session on mutation/public-private read | `401 UNAUTHORIZED` | JSON envelope from existing filter-chain handler. |
| Wrong role or cross-agent access | `403 FORBIDDEN` | Generic message; audit via `AccessDeniedHandler`. |
| Missing/invalid CSRF token | `403 FORBIDDEN` with `SECURITY_CSRF_FAILURE` audit | Existing behaviour (F-1/F-2 open); never exempt a property mutation. |
| Property ID not found | `404 NOT_FOUND` | For unauthorized callers, hidden/non-visible also `404`. |
| Public detail for non-`PUBLISHED` property | `404 NOT_FOUND` | C-10/D-08: no existence disclosure. |
| Validation failure | `400 VALIDATION_ERROR` with field details | `UnknownFieldGuard` fields reported without values. |
| Illegal lifecycle transition | `409 CONFLICT` | Transition table is authoritative. |
| Reassignment to same/ineligible agent | `409 CONFLICT` / `400` | Ineligible target (non-agent/inactive) is validation. |
| Payload / multipart too large | `413 PAYLOAD_TOO_LARGE` | Before full stream read. |
| Wrong content type | `415 UNSUPPORTED_MEDIA_TYPE` | JSON endpoints require `application/json`. |
| Cloudinary/provider unavailable | `503 SERVICE_UNAVAILABLE` | Safe message; no provider error body, secret, or signature exposed. |
| Rate limit exceeded | `429 TOO_MANY_REQUESTS` | F-8 notes `Retry-After` is not currently emitted; remains open. |

---

## 7. Cross-document consistency

- Every approved property endpoint in `API_SPECIFICATION.md` §6–8 is accounted for here: §4.1–4.10 covers management, §4.11–4.16 covers approved public discovery/reference lookups, and §5 covers image operations. No approved contract was removed or renamed.
- Every endpoint in §4 and §5 uses the same URL, role gate, and ownership condition as its row in `PROPERTY_AUTHORIZATION_MATRIX.md` §4.
- Roles match the capability matrix exactly: Buyer has no management capability; Agent is limited to current assignments; Admin creates, assigns, archives, reopens completed listings, and may manage any property.
- Creation uses `POST /api/v1/properties` with a method-specific ADMIN matcher (D-01); `/api/v1/admin/properties` remains only the approved administrative list path and the assignment path prefix.
- Pagination uses top-level `data` + `meta`; single resources use `data`; errors use the standard envelope. Archival is `200` with the updated resource; image deletion is `204` with no body (D-03).
- Status names/transitions match `PROPERTY_DOMAIN_DESIGN.md` §4 and the D-06 reconciliation in `SECURITY_ARCHITECTURE.md` §10.4.3: six statuses only, `UNAVAILABLE` is the unpublish target, `ARCHIVED` is terminal, invalid transitions return `409`.
- No property migration is created in Stage 4.1. The separately approved future `is_featured` migration (API §25.1.D) and controlled subtype/amenity seeding (D-09) are planning dependencies only.
- Provider dependency/configuration is required only before implementing §5 (D-08); SD-30 and F-1…F-8 remain intact.
- D-01…D-11, G-01…G-16, U-01…U-09, and SD4-01…SD4-08 are recorded in `STAGE_4_DECISION_REGISTER.md`.
