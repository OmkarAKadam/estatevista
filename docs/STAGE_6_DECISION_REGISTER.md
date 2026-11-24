# EstateVista V2 — Stage 6 Decision Register

## 1. Purpose and status

This register is the authoritative Stage 6 consistency record for the Favorites & Inquiries architecture. It records the project owner's approval of all 12 architectural decision groups on **24 November 2026**, resolves every applicable Section L decision identifier, and defines the exact Stage 6 contracts that implementation must follow.

**Status: APPROVED FOR STAGE 6 ARCHITECTURE — IMPLEMENTATION NOT AUTHORIZED BY THIS REGISTER.** The prior assessment verdict `REVIEW REQUIRED — ARCHITECTURAL DECISIONS PENDING.` is superseded by the owner approval recorded here. Stage 6.2 and later sub-stages still require their own verification and approval before progression.

This is a documentation-only reconciliation. No source file, test, migration, dependency, configuration, deployment artifact, database, server, or Stage 6.2 implementation was created or changed by this register.

Companion records:

- `docs/validation/STAGE_6_1_ARCHITECTURE_ASSESSMENT_REPORT.md` — ignored, untracked assessment input; its pending-decision verdict is historical.
- `docs/validation/STAGE_6_1_FINAL_COMPLETION_REPORT.md` — ignored, untracked final verification record.
- `docs/STAGE_4_DECISION_REGISTER.md` — finalized Stage 4 property decisions; preserved unchanged in authority.

---

## 2. Baseline and approval record

| Item | Record |
|---|---|
| Branch | `stage-6-favorites-inquiries` |
| Baseline HEAD | `b853989ed52521dc3e7f32aad41807beda0f4247` |
| Local `main` at baseline | Same commit as the working branch |
| Tracked tree at start | Clean; no staged or unstaged changes |
| Existing business migrations | V1–V17; V17 is the image-deletion outbox migration |
| Backend regression baseline | Historical Stage 5 / Stage 5.8 result: `452/452` passed; not rerun for Stage 6.1 documentation |
| Owner approval date | 24 November 2026 |
| Scope of approval | All 12 architectural decision groups recorded in §3, the Stage 6 roadmap in §11, and the boundaries in §12 |
| Explicitly not approved | Stage 6.2 implementation, V18 creation or application, chat implementation, Stage 8 account management, production deployment, blanket approval of the draft UI/UX specification, and any unlisted architectural change |

---

## 3. Owner-approved decision groups

| Group | Authorized outcome | Controlling effect | Status |
|---|---|---|---|
| **Decision 1 — Inquiry lifecycle and actor authority** | Statuses are `NEW`, `IN_PROGRESS`, `RESOLVED`, `CLOSED`. Permitted transitions are `NEW → IN_PROGRESS`, `NEW → CLOSED`, `IN_PROGRESS → RESOLVED`, `IN_PROGRESS → CLOSED`, `RESOLVED → CLOSED`; `CLOSED` is terminal. Historically assigned active AGENT accounts and authorized ADMIN accounts may make permitted transitions. Buyers have read-only status access and cannot close, withdraw, reopen, or modify submitted inquiries. Invalid transitions return `409`; repeating the current status is an idempotent success with no timestamp or audit change. `resolved_at` is set on entry to `RESOLVED` and retained thereafter; direct `NEW → CLOSED` or `IN_PROGRESS → CLOSED` leaves it `null`. Every real transition is audited without inquiry content. Concurrent changes are serialized or otherwise protected. History is preserved, and admin inquiry access never grants transcript access. | Resolves SD-INQ-01, SD-INQ-02, SD-INQ-04, D-09, and D-10. Supersedes the API buyer-close proposal and any buyer-withdraw/reopen wording. | **APPROVED** |
| **Decision 2 — Duplicate inquiry policy** | At most one active inquiry per buyer/property. `NEW` and `IN_PROGRESS` are active; `RESOLVED` and `CLOSED` are inactive. A new inquiry is allowed after the prior inquiry becomes inactive. Duplicate active submissions return `409`. A PostgreSQL partial unique index is mandatory; a service check alone is insufficient. Concurrency must not create duplicates. The rule applies regardless of historical assignment, and no existing inquiry may be merged, overwritten, or deleted. | Resolves SD-INQ-03 and authorizes the partial-index portion of V18 in Stage 6.4. | **APPROVED** |
| **Decision 3 — Favorite mutation semantics** | Favorites are BUYER-only and buyer identity is principal-derived. `POST` returns `201` for a new favorite and `200` for an existing favorite on a `PUBLISHED` property. `DELETE` returns `204` for either an existing or nonexistent favorite. Adding a nonexistent or non-public property returns `404`, and this restriction takes precedence over duplicate-add success. The existing `UNIQUE(buyer_id, property_id)` constraint is preserved; concurrent duplicate adds are handled safely. Removal affects only the authenticated buyer's row. No soft-delete flag is introduced. | Resolves SD-FAV-01. | **APPROVED** |
| **Decision 4 — Favorite retention and privacy** | New favorites may reference only `PUBLISHED` properties. Existing favorites remain stored when a property becomes `DRAFT`, `UNAVAILABLE`, `SOLD`, `RENTED`, or `ARCHIVED`. Non-public favorites render only the generic placeholder `Property no longer available` plus the minimum identifier/availability data needed to render and remove them. Unpublished titles, prices, descriptions, images, addresses, agent details, and internal lifecycle statuses are never exposed. Buyers can still remove unavailable favorites, and normal public-summary presentation returns if the property is republished. No automatic deletion occurs. | Resolves SD-FAV-02. | **APPROVED** |
| **Decision 5 — Favorite API and state synchronization** | Routes under `/api/v1` are `POST /favorites/{propertyId}`, `DELETE /favorites/{propertyId}`, `GET /favorites`, `GET /favorites/{propertyId}/status`, and `GET /favorites/status?propertyIds=...`. The batched route is explicitly approved, limited to 100 supplied UUIDs, and uses the exact contract in §5.1. Favorite state is fetched in one batched request per displayed collection, the favorites page is paginated, individual detail pages may use the single-status route, and public property responses carry no personalized flags. Private responses use `Cache-Control: no-store`. In-memory state is reset on logout, account changes, and expired sessions; stale in-flight responses are discarded. | Resolves SD-FAV-03, SD-FAV-04, and D-29. | **APPROVED** |
| **Decision 6 — Historical assignment and account deactivation** | Historical inquiries retain the original `assigned_agent_id`; property reassignment never rewrites them. New inquiries require a `PUBLISHED` property and an eligible active assigned AGENT, use the current assignment, and derive buyer/agent identity server-side. Deactivated buyers and agents lose authenticated access while history remains; reactivated agents regain authorized historical access. Authorized admins may manage inquiries assigned to inactive agents. Archival never deletes inquiries, and no automatic reassignment or physical deletion occurs. Per-request account-status and authority revalidation is preserved. Inquiry creation must transactionally recheck property status, assignment, and account eligibility to avoid races. | Resolves SD-ACC-01. | **APPROVED** |
| **Decision 7 — API contracts, pagination, sorting, and IDOR** | Favorites use `page=0`, `size=12`, max `100`, `sort=newest` default, and `sort=newest|oldest`. Inquiries use `page=0`, `size=20`, max `100`, `sort=newest` default, `sort=newest|oldest`, and optional `status` filtering. Ordering is `createdAt` with a deterministic UUID tiebreak. Unsupported sort fields, invalid statuses, and invalid pagination return `400`. The existing `data` plus `meta` envelope and standard error envelope are preserved. Subject is optional and at most 200 characters; message is nonblank and at most 5,000 characters. Client-supplied actor, buyer, agent, owner, or initial-status fields are rejected. Errors use `400`, `401`, `403`, `404`, `409`, and `429`; unauthorized and missing resources receive equivalent generic `404` responses. No SQL, stack trace, internal identifier, or private-existence detail is exposed. | Resolves SD-INQ-05 and SD-INQ-06; reconciles duplicate inquiry route descriptions into §5.2. | **APPROVED** |
| **Decision 8 — Rate limiting** | Authenticated favorites and inquiry APIs are `300 requests/minute/user`; ADMIN mutations are `60 requests/minute/admin`; favorite mutations are `60 requests/minute/user`; inquiry submissions are `5/minute/buyer` and `30/hour/buyer`. All applicable limits apply together. Existing shared buckets are reused or extended where applicable so no independent bucket permits bypass. Identity is server-derived. `429` uses the standard error envelope and a `Retry-After` header reflecting every blocking limit. Existing property and authentication limits are preserved. No automatic mutation retries are introduced. | Resolves SD-RATE-01; exact coverage is in §5.4. | **APPROVED** |
| **Decision 9 — Inquiry frontend and chat boundary** | Stage 6 implements favorite controls, the buyer favorites page, inquiry submission, buyer inquiry history, the agent inquiry queue and status management, and admin inquiry oversight/status management. The illustrative property CTA is replaced with a functional inquiry workflow during implementation. Only authenticated BUYER accounts submit inquiries; anonymous users receive an authentication prompt; ADMIN/AGENT accounts receive no buyer mutation controls; buyer status is read-only. Loading, empty, error, validation, and success states are accessible. The shared Vanilla JavaScript API client is reused, untrusted content is rendered with safe DOM APIs, inquiry content is never stored in browser storage or exposed in URLs/logs/error toasts, and Stage 6 mutations do not set `retryOnCsrfFailure`. The general contact page remains truthful. No chat endpoints, STOMP handlers, conversation creation UI, or inquiry↔conversation FK is introduced in Stage 6, and an inquiry is not a prerequisite for chat. | Resolves SD-UI-01, SD-STG7-01 for Stage 6, and D-11 for Stage 6 only. Does not approve the whole draft UI/UX specification. | **APPROVED** |
| **Decision 10 — Migration authorization** | The additive migration `V18__enforce_inquiry_business_constraints.sql` is authorized **only during Stage 6.4** with exactly two changes: a status `CHECK` for `NEW`, `IN_PROGRESS`, `RESOLVED`, `CLOSED`, and a partial unique index for one active inquiry per buyer/property where status is `NEW` or `IN_PROGRESS`. V18 must not be created in Stage 6.1; V1–V17 remain unchanged. Existing records must be inspected before application, incompatibilities reported, and no incompatible record silently deleted, merged, or rewritten. Separate owner approval is required for any remediation. Stage 6.4 must verify clean V1–V18 installation, V17→V18 upgrade, constraints, concurrency, and migration inventory tests. No favorites migration and no other migration scope is authorized. | Authorizes the database portion of Decisions 1–2 at the approved implementation milestone. | **APPROVED FOR STAGE 6.4 ONLY** |
| **Decision 11 — Security hardening schedule** | F-1, F-2, F-4, and F-7 are mandatory Stage 6.2 prerequisites before new mutations. F-3 is Stage 7; F-5 is Stage 8; F-6 is Stage 9. F-8 receives Stage 6 rate-limit coverage with its remaining obligations assigned to Stage 8/9. Scheduling never equals resolution; every resolved finding requires implementation evidence and independent verification. F-8 remains partially addressed until its individual obligations are verified. | Resolves SD-F-01…08 disposition and SD-RATE-01 scheduling. Full schedule in §9. | **APPROVED** |
| **Decision 12 — Stage boundaries** | Stage 7 retains chat, persistent history, WebSocket/STOMP authorization, F-3, and all conversation lifecycle decisions; no admin transcript access. Stage 8 retains dashboards, role-specific navigation, notifications, and restricted owner-level privileged-account workflows with explicit approval still required; no routine SQL workflow, public privileged registration, or ordinary administrator escalation. Stage 9 retains remaining hardening, Cloudinary/Linux/Java 21/browser/accessibility verification, production configuration, deployment, and final documentation. The featured-property endpoint remains denied and unmapped. | Preserves all deferred requirements and approved exclusions. | **APPROVED / PRESERVED** |

---

## 4. Section L decision resolution matrix

Every identifier in Section L of `docs/validation/STAGE_6_1_ARCHITECTURE_ASSESSMENT_REPORT.md` is dispositioned below. “Resolved” records an owner-approved architectural outcome; it does not claim implementation or verification.

| Section L identifier | Owner-approved disposition | Decision group | Status |
|---|---|---|---|
| SD-INQ-01 / D-09 | Four statuses and the exact transition matrix in §3 Decision 1; active assigned AGENT or authorized ADMIN authority; buyer read-only; `409` invalid transition; audited real transitions. | 1 | **RESOLVED** |
| SD-INQ-02 / D-10 | Buyer close/withdraw/reopen proposals are superseded. Buyer inquiry status is read-only. | 1 | **RESOLVED** |
| SD-INQ-03 | One active inquiry per buyer/property; inactive statuses permit a new inquiry; duplicate active submissions return `409`; partial unique index mandatory. | 2 | **RESOLVED** |
| SD-INQ-04 | ADMIN may perform every permitted transition under the same matrix; no conversation or transcript access is granted. | 1 | **RESOLVED** |
| SD-INQ-05 | Favorites and inquiries use the exact pagination/sort/filter contracts in §5.2; unsupported values return `400`. | 7 | **RESOLVED** |
| SD-INQ-06 | Unauthorized and nonexistent inquiry/favorite records both return the generic standard `404` envelope; no private-existence detail. | 7 | **RESOLVED** |
| SD-FAV-01 | `201` for a new favorite, `200` for an existing favorite on a `PUBLISHED` property, `204` for removal, `404` for nonexistent/non-public add. No soft delete. | 3 | **RESOLVED** |
| SD-FAV-02 | Existing favorites are retained and rendered as `Property no longer available`; only minimal identifier/availability data may be exposed; normal presentation returns on republish. | 4 | **RESOLVED** |
| SD-FAV-03 / D-29 | Individual and batched favorite-status endpoints are approved; no personalized flag in public property responses; stale-response controls in §5.1. | 5 | **RESOLVED** |
| SD-FAV-04 | Favorites list is paginated with saved-property summaries; status state for collections is fetched with the batch endpoint; no full-collection download for card state. | 5 | **RESOLVED** |
| SD-ACC-01 | Historical assignments are immutable; deactivated accounts lose access; reactivated agents regain historical access; admins may manage inactive-agent inquiries; no silent deletion. | 6 | **RESOLVED** |
| SD-RATE-01 | Exact overlapping limits, route coverage, identity derivation, and `429`/`Retry-After` behavior in §5.4 and §9. | 8 and 11 | **RESOLVED** |
| SD-UI-01 / D-11 | Stage 6 is inquiry-only for buyer submission; no chat UI, handlers, endpoints, or inquiry↔conversation FK. The illustrative CTA becomes a functional inquiry workflow during Stage 6 implementation. | 9 | **RESOLVED FOR STAGE 6 ONLY** |
| SD-STG7-01 | No inquiry↔conversation relationship in Stage 6. Conversation creation, lifecycle, ordering, pagination, read state, and F-3 remain Stage 7 decisions. | 9 and 12 | **DEFERRED — STAGE 7** |
| SD-F-01 | Exact Origin/Referer validation for Stage 6 mutation surfaces. | 11 | **RESOLVED — STAGE 6.2 PHASE A VERIFIED** |
| SD-F-02 | CSRF token regeneration after successful login. | 11 | **RESOLVED — STAGE 6.2 PHASE A VERIFIED** |
| SD-F-03 | WebSocket session invalidation on HTTP session destruction. | 12 | **DEFERRED — STAGE 7; OPEN** |
| SD-F-04 | `Cache-Control: no-store` on generic errors and denial responses. | 11 | **RESOLVED — STAGE 6.2 PHASE A VERIFIED** |
| SD-F-05 | Absolute/admin session timeout and session limits. | 12 | **DEFERRED — STAGE 8; OPEN** |
| SD-F-06 | Configurable logout cookie name. | 12 | **DEFERRED — STAGE 9; OPEN** |
| SD-F-07 | Concurrent CSRF bootstrap and token-version hardening. | 11 | **RESOLVED FOR SINGLE-INSTANCE DEPLOYMENT — STAGE 6.2 PHASE A VERIFIED**; multi-instance gate Stage 9 (§14) |
| SD-F-08 | Rate-limit coverage in Stage 6; remaining `last_login_at`, audit timestamps/proactive revocation and full verification in Stage 8/9. | 11 | **PARTIALLY ADDRESSED; REMAINS OPEN** |

---

## 5. Exact API contract

All paths in this section are relative to `/api/v1`. Existing top-level `data`/`meta` and standard error envelopes are authoritative.

### 5.1 Favorites

| Method | Path | Authority | Success behavior |
|---|---|---|---|
| POST | `/favorites/{propertyId}` | Authenticated BUYER | `201` new; `200` existing favorite on a `PUBLISHED` property |
| DELETE | `/favorites/{propertyId}` | Authenticated BUYER | `204` existing or nonexistent favorite |
| GET | `/favorites` | Authenticated BUYER | `200` paginated saved-property summaries |
| GET | `/favorites/{propertyId}/status` | Authenticated BUYER | `200` authoritative state for that buyer and property |
| GET | `/favorites/status?propertyIds=...` | Authenticated BUYER | `200` authoritative batched state; maximum 100 supplied UUIDs |

**POST response data**

```json
{
  "success": true,
  "message": "Property added to favorites",
  "data": {
    "propertyId": "uuid",
    "isFavorite": true
  }
}
```

The HTTP status distinguishes new (`201`) from existing (`200`) state. A nonexistent or non-public property returns generic `404`, including when a stored favorite already exists. The database unique constraint and a safe concurrent insert path remain the final guard.

**DELETE behavior:** returns `204` with no body for both existing and nonexistent favorites and removes only the authenticated buyer's row. A non-public property with an existing favorite remains removable.

**List response:** `GET /favorites?page=0&size=12&sort=newest`. `size` defaults to `12`, maximum `100`; `sort` accepts only `newest` (default) and `oldest`. Order is `createdAt DESC, id DESC` for `newest` and `createdAt ASC, id ASC` for `oldest`. Each `data` item is:

```json
{
  "propertyId": "uuid",
  "createdAt": "2026-11-24T10:30:00Z",
  "property": {
    "...": "approved public property summary from API §7.2"
  }
}
```

For a non-public property, `property` is replaced by:

```json
{
  "availability": "UNAVAILABLE",
  "placeholder": "Property no longer available"
}
```

No title, price, description, image, address, agent detail, internal lifecycle status, or other unpublished field is included in that restricted object. On republish, the normal approved public property summary is restored without a new favorite row.

**Individual status response data**

```json
{
  "propertyId": "uuid",
  "isFavorite": false
}
```

**Batched status contract**

```text
GET /api/v1/favorites/status?propertyIds=11111111-1111-1111-1111-111111111111,22222222-2222-2222-2222-222222222222
```

- `propertyIds` is a single comma-separated query parameter of canonical lowercase UUID strings; no JSON body is accepted.
- Missing, empty, malformed, or more than 100 supplied identifiers return `400` with the standard error envelope.
- Exact duplicate identifiers are not an error. They are validated, de-duplicated, and returned once in first-occurrence order; duplicates count toward the 100-value cap before de-duplication.
- The response contains one entry per unique supplied UUID and reports only that authenticated buyer's state. Nonexistent and non-public identifiers produce `isFavorite: false`; no property existence or unpublished data is disclosed.

```json
{
  "success": true,
  "data": {
    "favorites": [
      { "propertyId": "uuid", "isFavorite": true },
      { "propertyId": "uuid", "isFavorite": false }
    ]
  }
}
```

Status and list reads use bounded `WHERE buyer_id = :buyer AND property_id IN (...)` queries; no per-card request or N+1 query pattern is permitted. Clients use one batch per displayed collection and may use the individual route on property details. Mutation results and status responses are applied only to their matching property ID after resolution; superseded in-flight reads are cancelled or ignored. Logout, account change, and expired-session detection clear in-memory favorite state before requerying. All private favorite responses set `Cache-Control: no-store`.

### 5.2 Inquiries

| Method | Path | Authority | Success behavior |
|---|---|---|---|
| POST | `/properties/{propertyId}/inquiries` | Authenticated BUYER | `201` new inquiry with `NEW` status |
| GET | `/inquiries/me` | Buyer owner | `200` paginated buyer inquiry history |
| GET | `/inquiries/{inquiryId}` | Buyer owner, historically assigned AGENT, or ADMIN | `200` inquiry detail |
| GET | `/agents/me/inquiries` | Authenticated AGENT | `200` paginated historically assigned inquiries |
| GET | `/admin/inquiries` | ADMIN | `200` paginated inquiry metadata |
| PATCH | `/inquiries/{inquiryId}/status` | Historically assigned active AGENT or ADMIN | `200` updated inquiry detail |

This table is the single authoritative inquiry endpoint inventory; repeated route descriptions elsewhere are superseded by it.

**Submit request**

```json
{
  "subject": "Interested in this property",
  "message": "I would like to schedule a property visit."
}
```

`subject` may be omitted or `null`; if supplied it is at most 200 characters. `message` is required, nonblank after trimming, and at most 5,000 characters. Any client-supplied `buyerId`, `agentId`, `assignedAgentId`, owner, actor, `status`, `resolvedAt`, or initial-status field is rejected with `400`. The property must be `PUBLISHED` and must have an eligible active assigned AGENT; buyer and agent values are derived server-side in one transaction.

**Inquiry detail data**

```json
{
  "id": "uuid",
  "propertyId": "uuid",
  "propertyTitle": "string or generic unavailable placeholder",
  "buyerId": "uuid",
  "assignedAgentId": "uuid",
  "subject": "optional string",
  "message": "inquiry content",
  "status": "NEW",
  "resolvedAt": null,
  "createdAt": "2026-11-24T10:30:00Z",
  "updatedAt": "2026-11-24T10:30:00Z"
}
```

List endpoints use the same fields except `message`, within the standard `data` plus `meta` envelope. Buyer lists include only records owned by the authenticated buyer; agent lists include only historically assigned records; admin lists include inquiry metadata and message on detail only. Admin access never includes conversations or message transcripts.

**Status patch request**

```json
{ "status": "IN_PROGRESS" }
```

Only `status` is accepted. The transition matrix in §3 Decision 1 is authoritative. `CLOSED → any status` and every other unlisted transition return `409`. Repeating the current status returns `200` with unchanged `resolvedAt`, `updatedAt`, and no new audit row. A real transition to `RESOLVED` sets `resolvedAt`; direct closure leaves it `null`; every real transition creates an `INQUIRY_STATUS_CHANGED` audit record containing IDs, actor, before/after status, time, and correlation ID, but no inquiry content. Concurrent status changes are serialized or protected so only one valid transition wins.

**Pagination and filtering**

| Surface | `page` | `size` | Maximum | `sort` | Other parameters |
|---|---|---|---|---|---|
| `/favorites` | 0 | 12 | 100 | `newest` default; `oldest` | none |
| Inquiry lists | 0 | 20 | 100 | `newest` default; `oldest` | `status` optional: `NEW`, `IN_PROGRESS`, `RESOLVED`, `CLOSED` |

Sorting uses `createdAt` with UUID tiebreak as defined for favorites. Invalid page, size, sort, or status values return `400`. Unknown actor/identity fields are never accepted.

### 5.3 Errors and IDOR

The standard error envelope is unchanged. Stage 6 status semantics are:

| Code | Use |
|---|---|
| `400` | Invalid UUID, batch, pagination, sort, status filter, body field, or validation |
| `401` | Missing or invalid authentication |
| `403` | Authenticated role-level access denied, with no private-resource existence hint |
| `404` | Missing or unauthorized record/property; both cases use the same generic response |
| `409` | Business-state conflict: invalid inquiry transition or duplicate active inquiry |
| `429` | Rate limit exceeded, with `Retry-After` |

No response may expose SQL, stack traces, internal exception text, hidden record existence, unpublished property data, inquiry content in URLs/logs/toasts, or other users' identifiers beyond an authorized detail DTO.

### 5.4 Rate-limit coverage

| Route or surface | Limits applied |
|---|---|
| All authenticated `/favorites/**` reads and mutations | `300/minute/user` plus route-specific limits below |
| All authenticated inquiry reads and `/properties/{propertyId}/inquiries` | `300/minute/user` plus submission limits below |
| `POST`/`DELETE /favorites/{propertyId}` | `60/minute/user` plus `300/minute/user` |
| `POST /properties/{propertyId}/inquiries` | `5/minute/buyer`, `30/hour/buyer`, plus `300/minute/user` |
| `PATCH /inquiries/{inquiryId}/status` when performed by ADMIN | `60/minute/admin` plus `300/minute/user`; agent execution uses the authenticated-user buckets |
| Existing authentication, property, search, detail, and upload routes | All previously approved limits remain unchanged |

Limits are derived from the authenticated principal, overlap cumulatively, and must be implemented by extending/reusing approved shared infrastructure rather than by creating a bucket that bypasses an existing limit. `429` uses the standard envelope and a `Retry-After` value equal to the longest remaining block among all triggered buckets. `Retry-After` must be exposed to approved browser origins through the existing CORS exposure configuration; that configuration work is a Stage 6.2 implementation prerequisite and is not performed by this documentation-only reconciliation. No automatic retry of any Stage 6 mutation is permitted.

---

## 6. Domain and persistence rules

- Favorites keep the existing `UNIQUE(buyer_id, property_id)` constraint and never gain a soft-delete/active flag.
- Inquiry history retains `assigned_agent_id` permanently; reassignment of the property is not an update to prior inquiries.
- New inquiry creation reads property status, current assignment, and account eligibility transactionally and fails with a safe generic `404` if the property or eligible agent is unavailable.
- Every actual inquiry transition is audited without subject/message content; same-status requests are idempotent and unaudited.
- Active-inquiry uniqueness is database-enforced by the Stage 6.4 partial index; service checks are advisory only.
- No favorites/inquiries record is physically deleted as part of property lifecycle changes.
- Private responses, including errors and denied requests described by Stage 6 security prerequisites, use `no-store`.

---

## 7. Conflict resolution

| Prior conflict or ambiguity | Owner-approved resolution |
|---|---|
| API buyer-close operation versus UI read-only status | Buyer status is read-only; the buyer-close proposal is superseded. |
| Proposed status map versus FRD “authorized agents update status” with no map | The §3 Decision 1 matrix is authoritative for active assigned AGENT and ADMIN authority. |
| D-11 one-channel versus two-channel property action | Stage 6 implements inquiry only; chat remains Stage 7. |
| Favorite retention “until removed” versus draft automatic-removal wording | No automatic removal; non-public favorites are retained with generic presentation. |
| Duplicate favorite `409` versus treat-as-saved wording | `201` new, `200` existing published, `204` remove, `404` non-public/nonexistent add. |
| Favorite state embedded in public responses versus explicit status calls | No personalized flags in public property responses; approved individual and batched status routes are used. |
| “No endpoint-specific rate limit” versus required Stage 6 limits | The §5.4 limits are approved and overlap; existing limits are preserved. |
| UI draft details versus approved API/DB/Security baseline | Approved contracts control; UI details are approved only for the Stage 6-specific requirements in Decision 9. |
| V1 frontend roadmap “Stage 6 documentation/deployment” versus V2 stage status | Treat as separate V1 and V2 roadmaps; no V2 deployment or Stage 6.2 completion is claimed. |
| Assessment verdict “decisions pending” | Superseded by the owner approval recorded in this register; the assessment remains a historical report. |

No material unresolved architectural conflict remains from the owner-approved decision set.

---

## 8. Migration authorization

`V18__enforce_inquiry_business_constraints.sql` is authorized only in **Stage 6.4** and only for:

1. `CHECK (status IN ('NEW', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'))` on inquiries.
2. A PostgreSQL partial unique index on `(buyer_id, property_id)` where `status IN ('NEW', 'IN_PROGRESS')`.

Stage 6.1 must not create this file. V1–V17 are immutable for this stage. Before Stage 6.4 application, existing rows must be inspected and incompatible statuses or duplicate active records reported. No silent delete, merge, or rewrite is allowed; remediation requires separate owner approval. Stage 6.4 acceptance includes clean V1→V18, V17→V18 upgrade, Flyway validation, constraint enforcement, concurrent-submission behavior, and updated migration inventory tests. No favorites migration and no other migration change is authorized.

---

## 9. Security remediation schedule

| Finding | Milestone | Stage 6 disposition |
|---|---|---|
| F-1 — Exact Origin/Referer validation | Stage 6.2 Phase A | **RESOLVED — implemented and verified** |
| F-2 — CSRF token regeneration after login | Stage 6.2 Phase A | **RESOLVED — implemented and verified** |
| F-3 — WebSocket session invalidation | Stage 7, before chat completion | **OPEN — deferred** |
| F-4 — Generic error and denial `no-store` | Stage 6.2 Phase A | **RESOLVED — implemented and verified** |
| F-5 — Absolute/admin timeout and session limits | Stage 8, before privileged account management | **OPEN — deferred** |
| F-6 — Configurable logout cookie name | Stage 9 | **OPEN — deferred** |
| F-7 — Concurrent CSRF bootstrap/token-version hardening | Stage 6.2 Phase A | **RESOLVED — SINGLE-INSTANCE SCOPE ONLY**; multi-instance gate Stage 9 (§14) |
| F-8 — Rate limits, audit timestamps, proactive revocation | Rate-limit coverage in Stage 6; remaining obligations Stage 8/9 | **PARTIALLY ADDRESSED — OPEN** |

F-8 exact remaining obligations outside the Stage 6 route coverage are: complete and independently verify `last_login_at` capture, the remaining audit-timestamp behavior, and proactive revocation/expiry behavior; schedule and verify these under Stage 8/9. A scheduled item is never marked resolved. Every closure claim requires implementation evidence and independent verification. Server-side sessions, session-bound CSRF, exact-origin CORS, deny-by-default authorization, and per-request account revalidation remain mandatory. No JWT, wildcard CORS, new CSRF exemption, mutation retry, or other security weakening is approved.

---

## 10. Acceptance criteria for later implementation

Implementation may be accepted only when all of the following are evidenced:

- Buyer-only favorite mutation and listing; ownership comes from the principal; duplicate add and idempotent remove follow §5.1; concurrent additions create at most one row.
- Non-public favorites retain only generic placeholder data, remain removable, expose no unpublished fields, and recover normal presentation on republish.
- One batched status request per displayed collection; bounded queries with no N+1; no full-collection download for card state; no personalized flags in public property responses; stale responses cannot overwrite newer state; private responses are `no-store`.
- Inquiry submit validation, `PUBLISHED` property and eligible active-assigned-agent checks, principal-derived identities, and transactional consistency under concurrent status/assignment/account changes.
- Concurrent duplicate active inquiries are prevented by the Stage 6.4 partial index and return `409`; history is never merged, overwritten, or deleted.
- Every permitted and forbidden transition, idempotent same-status behavior, `resolved_at` rules, actor authority, concurrency protection, and audit records without inquiry content.
- Historical assignment immutability, deactivation denial, reactivated-agent access, admin management of inactive-agent inquiries, and property archival preservation.
- Exact pagination, sort, status filtering, deterministic ordering, DTO field sets, and generic `404`/`409`/`400` behavior; IDOR attempts are non-enumerating.
- CSRF header handling, exact Origin/Referer enforcement, `no-store` on errors/denials, session revalidation, and no `retryOnCsrfFailure` on mutations.
- Every overlapping rate limit in §5.4, correct server-derived identity, `429` envelope, and `Retry-After` reflecting all blocking buckets.
- Authentication, registration, property discovery, property management, and image-deletion regression coverage remains green.
- Responsive and accessible frontend states; safe DOM rendering for untrusted content; no browser storage of inquiry content; no chat surface or linkage.
- Clean V1–V18 and V17→V18 migration verification when V18 is implemented, with migration inventory tests updated and V1–V17 unchanged.
- Testing uses the existing disposable PostgreSQL infrastructure; H2, production databases, and ordinary development data are never used for destructive tests.
- Backend, frontend/static, security, and final independent verification are recorded before Stage 6 closure. The historical `452/452` result is Stage 5 evidence only and does not satisfy new Stage 6 test requirements.

---

## 11. Approved Stage 6 roadmap

| Sub-stage | Scope |
|---|---|
| 6.1 | Architecture assessment, decision reconciliation, and closure — this documentation stage |
| 6.2 | Security prerequisites (F-1, F-2, F-4, F-7) and favorites backend |
| 6.3 | Favorites frontend |
| 6.4 | Inquiry database constraints, submission, and history backend |
| 6.5 | Inquiry lifecycle, authorization, and agent/admin management |
| 6.6 | Inquiry frontend integration |
| 6.7 | Functional, security, responsive, and regression verification |
| 6.8 | Independent final verification and Stage 6 closure |

Each sub-stage requires its own verification and approval before progression. This register does not approve or start Stage 6.2.

---

## 12. Implementation boundaries and deferred milestones

### Stage 6 exclusions

- No chat endpoints, STOMP handlers, conversation creation UI, inquiry↔conversation FK, or mandatory inquiry-before-chat rule.
- No admin conversation or transcript access.
- No favorite soft delete, automatic favorite removal, personalized public property flags, or whole-collection state download.
- No buyer close/withdraw/reopen path; no client-supplied identity or initial status.
- No V18 in Stage 6.1; no edit to V1–V17; no unapproved migration scope.
- No Stage 8 account management, privileged-account registration, role escalation, or routine manual SQL workflow.
- No dependency, configuration, deployment, source, test, or database change from Stage 6.1.

### Stage 7

Property-specific buyer–agent chat, persistent message history, WebSocket/STOMP authorization, F-3 remediation, conversation lifecycle, message ordering, pagination, read-state decisions, and preservation of approved MVP chat exclusions. Conversation contracts remain subject to explicit Stage 7 approval.

### Stage 8

Buyer/agent/admin dashboards, role-specific navigation, dashboard visual refinement, notifications, restricted owner-level ADMIN/AGENT provisioning/editing/deactivation/role management, and secure activation/account workflows. The final owner-level authorization model requires explicit architectural approval. F-5 and remaining F-8 obligations are Stage 8 dependencies.

### Stage 9

Remaining security remediation, F-6, real Cloudinary verification, Linux WebP compatibility, Java 21 runtime verification, Firefox and WebKit testing, remaining accessibility verification, production runtime configuration, deployment, and final documentation.

### Permanent MVP boundary

The featured-property endpoint remains denied and unmapped unless a future owner-approved change explicitly revises that decision.

---

## 13. Document authority

- This register controls Stage 6 favorites/inquiries interpretations and supersedes the pending-decision state of the Stage 6.1 assessment.
- Approved Stage 1–5 decisions, the Stage 4 decision register, and historical validation evidence remain authoritative for their own scope and are not reopened.
- Baseline design documents remain in their existing approved/proposed/draft status; Stage 6-specific addenda in those documents record only the approvals in this register and do not grant blanket approval to draft documents.
- Validation reports under `docs/validation/` are ignored local evidence and are not source-of-truth design documents.
- Any new implementation detail that changes a route, role, status, response, migration, or security boundary requires explicit owner approval and a register update before it is treated as approved.

---

## 14. Stage 6.2 Phase A verification record and deployment gate

| Item | Record |
|---|---|
| Date | 24 November 2026 |
| Verification baseline | Security commit `7e9cbaa0cb2dc3a1dcadeb41a697c87aa93409c0` on `stage-6-favorites-inquiries` |
| Evidence | Ignored local records under `docs/validation/`: live browser/API 34/34, frontend CSRF behavior 12/12, real HTTP concurrency 5/5, targeted authorized mutation verification 33/33 checks (201 creation plus two authorized PATCH mutations accepted, four rejected mutations with no persisted change), historical full backend suite 457/457 |
| Resolved findings | F-1, F-2 and F-4 resolved on completed verification; F-7 resolved only within the verified single-instance deployment scope |
| Still open | F-3 (Stage 7), F-5 (Stage 8), F-6 (Stage 9), and all remaining F-8 obligations (Stage 8/9) |

**F-7 deployment constraint.** F-7 is verified for single-instance deployment. The striped JVM lock serializes deferred CSRF bootstrap only inside one JVM instance and does not provide cross-instance coordination. Multi-instance deployment must not be approved until an appropriate cross-instance CSRF consistency mechanism is approved, implemented and independently verified. This production-readiness gate is assigned to Stage 9. Multi-instance concurrency is not resolved and is not approved by this record.

This verification record updates only the disposition of the four Stage 6.2 Phase A findings in §4 and §9. It does not authorize Phase B favorites implementation, change any V1–V17 migration, approve chat, account management, deployment, or a blanket UI approval, or close any other finding. Validation reports remain locally available, ignored and untracked evidence, not tracked design authority.
