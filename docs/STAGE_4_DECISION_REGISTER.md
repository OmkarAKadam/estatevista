# EstateVista V2 — Stage 4 Decision Register

## 1. Purpose and status

This register is the single consistency record for the final Stage 4.1 correction pass. It records the owner-authorized decisions D-01…D-11, resolves assessment gaps G-01…G-16 and open questions U-01…U-09, preserves Stage 3 findings F-1…F-8 as **OPEN**, and identifies the implementation dependencies that must be respected in later stages.

**Status: FINALIZED — design decisions, with one later implementation ADR recorded in §10.** All decisions needed for a Stage 4.2 design/implementation start are resolved and the Stage 4 documents are consistent with the approved architecture. This does **not** itself authorize Stage 5: explicit stage approval is still required. The Stage 4.1 baseline in §2 made no source change; the separately approved Stage 4 deletion-outbox implementation is recorded in §10.

Companion documents:

- `docs/validation/PROPERTY_DOMAIN_ASSESSMENT.md` — verified evidence (local, ignored).
- `PROPERTY_DOMAIN_DESIGN.md` — authoritative domain and lifecycle design.
- `PROPERTY_AUTHORIZATION_MATRIX.md` — endpoint/role/ownership matrix.
- `PROPERTY_API_DESIGN.md` — finalized REST contracts.
- `docs/validation/STAGE_4_1_ARCHITECTURE_AND_PROPERTY_DOMAIN_ASSESSMENT_REPORT.md` — initial assessment (local, ignored).
- `docs/validation/STAGE_4_1_FINAL_CORRECTION_AND_CLOSURE_REPORT.md` — final independent review (local, ignored).

---

## 2. Baseline and scope record

| Item | Value |
|---|---|
| Branch | `stage-4-property-management` |
| Baseline HEAD | `91c43e80d428e78a06b41780fe322a4db3b63898` |
| Tracked tree at start | Clean; no staged or unstaged diff |
| Pre-existing Stage 4 files | Four untracked design documents in `docs/`; two reports already relocated to ignored `docs/validation/` |
| Documentation organization | Four design documents remain tracking-eligible in `docs/`; reports remain locally ignored under `docs/validation/` |
| Source changes | None. No controller, service, DTO, migration, dependency, configuration, or frontend file was created or modified. |
| Upstream correction | One narrowly authorized clarification to `SECURITY_ARCHITECTURE.md` §10.4.3 (D-06); no role gate, endpoint, status, or visibility rule changed. |
| Git operations | No reset, rebase, merge, push, force-add, history rewrite, or `main` modification. Final commit is limited to intended documentation. |

---

## 3. Owner decisions D-01 … D-11

| ID | Authorized outcome | Controlling specification | Status and rationale | Implementation dependency |
|---|---|---|---|---|
| **D-01** | Use `POST /api/v1/properties`, ADMIN only. Withdraw the `/api/v1/admin/properties` create-path proposal. Use a method-specific Spring Security matcher when implementing. | `API_SPECIFICATION.md` §6.1/§6.2; `SECURITY_ARCHITECTURE.md` §10.4.3/§10.6.2 | **RESOLVED.** The approved contract is preserved; security configuration is not simplified by renaming a public API path. | AR-01 method-specific matcher plus service role check in Stage 4.2. |
| **D-02** | Preserve every approved property endpoint: `featured`, `recent`, `related`, `property-categories`, `property-subtypes`, `amenities`, plus §6–8 management/image endpoints. Assign §6/detail/search/featured/recent/related to Stage 4.2, reference lookups to Stage 4.4, frontend wiring to Stage 4.7, and leave favorites/inquiries/chat/dashboards to Stage 5+. | `API_SPECIFICATION.md` §6–8/§25.1.D; `SECURITY_ARCHITECTURE.md` §10.4.3 | **RESOLVED.** Complete inventory is recorded in `PROPERTY_API_DESIGN.md` §4.1–4.16 and `PROPERTY_AUTHORIZATION_MATRIX.md` §4. No contract was removed. | `is_featured` migration/admin control needs separate approval (API §25.1.D); reference data needs D-09. |
| **D-03** | Preserve top-level `data` + `meta`; archival returns `200` with the updated resource; successful image deletion returns `204` with no body; document exact pagination and error behavior. | `API_SPECIFICATION.md` §7.2, §16, §17, §19 | **RESOLVED.** Envelope, `meta` fields, pagination defaults/maxima, and error mapping are explicit in `PROPERTY_API_DESIGN.md` §2/§6. No conflicting approved response was found. | Stage 4.2 paged response DTO; Stage 4.5 image client handles `204` without parsing a body. |
| **D-04** | Retain exactly `DRAFT`, `PUBLISHED`, `UNAVAILABLE`, `SOLD`, `RENTED`, `ARCHIVED`; use `UNAVAILABLE` for unpublishing; add no `UNPUBLISHED` and no seventh-status migration. | V5 CHECK; `PropertyStatus.java`; `DATABASE_DESIGN.md` §6.3; `API_SPECIFICATION.md` §7/§25.1.I | **RESOLVED.** Existing enum/schema and approved visibility rule are preserved. | None for Stage 4.2; any future seventh status requires migration and re-approval. |
| **D-05** | ADMIN creates, assigns/reassigns, and archives. AGENT updates only currently assigned properties. BUYER has no management permission. Validate assignment targets as ACTIVE `AGENT`. Derive actor identity from the authenticated principal. Preserve historical inquiry/conversation assignments. | FRD FR-PROP-001/004/005/007/008/010; `DATABASE_DESIGN.md` §6.3; `API_SPECIFICATION.md` §25.1.F/G; `SECURITY_ARCHITECTURE.md` §10.6 | **RESOLVED.** Ownership policy is unchanged; `primaryAgentId` remains a validated admin assignment target, not actor identity. | Reuse `AccountAuthorizationService`, `AuthenticatedAccount`, and `ResourceAuthorizationService`; add missing-ID `404` handling for ADMIN (G-14). |
| **D-06** | Adopt the authoritative transition matrix in `PROPERTY_DOMAIN_DESIGN.md` §4.3. Agents may publish, unpublish, republish, and record matching sale/rent outcomes; `SOLD`/`RENTED` reopening and all archival are ADMIN-only; `ARCHIVED` is terminal; invalid/same-status transitions return `409`. Document prerequisites, audit, timestamps, and same-status handling. Reconcile the SECURITY wording. | `API_SPECIFICATION.md` §25.4 (deferred lifecycle); `DATABASE_DESIGN.md` §23; `SECURITY_ARCHITECTURE.md` §10.4.3/§10.6; FRD FR-PROP-009 | **RESOLVED.** One matrix is authoritative. The former “no admin-only statuses for agents” wording was clarified in `SECURITY_ARCHITECTURE.md` §10.4.3 as “no status exclusively reserved from agents”, not “every transition open to agents”. No role gate, endpoint, status, or visibility rule changed. | One transactional lifecycle service component; image prerequisite depends on Stage 4.5, but must not be relaxed. |
| **D-07** | Preserve DB field lengths; use uppercase active ISO 4217 alpha-3 currency codes with `INR` as example/UI convention only; the field is required by API §6.2 so no implicit server default; refresh `publishedAt` on every publish; leave no primary after primary deletion; preserve the one-primary partial unique index. Document exact validation and remaining limitations. | `DATABASE_DESIGN.md` §6.3/§7.1; `API_SPECIFICATION.md` §6.2/§18; V5/V6; SD-30 | **RESOLVED.** Exact DTO limits and service invariants are documented. DB `TEXT` and nullable slug columns remain unchanged; application-level checks are explicit limitations. | Stage 4.2 DTO/service validation; Stage 4.5 image deletion flow. |
| **D-08** | Retain backend-mediated external image storage. Do not install Cloudinary or another SDK in Stage 4.1. Record dependency, configuration, credential, validation, and failure-handling requirements for separate Stage 4.5 approval. Preserve SD-30 and upload-security requirements. | `SECURITY_ARCHITECTURE.md` §13/§18; `SYSTEM_ARCHITECTURE.md` §10; `API_SPECIFICATION.md` §8 | **RESOLVED for planning; implementation DEFERRED.** Checklist recorded in `PROPERTY_DOMAIN_DESIGN.md` §5.2 and `PROPERTY_API_DESIGN.md` §5. | Separate dependency/configuration/credential approval before Stage 4.5; no image code now. |
| **D-09** | Use controlled migrations or approved seeding for subtypes and amenities; no arbitrary public reference-data mutation endpoints. Document initial ownership, values, and migration strategy; create no migration in Stage 4.1. | V4 comment; `DATABASE_DESIGN.md` §6.2/§8.1; `API_SPECIFICATION.md` §7 | **RESOLVED for planning.** Categories already seeded by V4. Initial subtype/amenity values are the examples in `DATABASE_DESIGN.md` §6.2/§8.1; product/admin owns them; Stage 4.4 uses a controlled migration or approved seeding mechanism. | Reference data must exist before create/lookup flows are usable; no public write endpoint. |
| **D-10** | Server-generated unique slugs with bounded collision handling and no client-supplied value; use approved SECURITY §11.9 rate limits for property mutation, search, detail, admin mutation, and upload; invent no production thresholds. | V5 nullable unique `slug`; `API_SPECIFICATION.md` §19/§25.2; `SECURITY_ARCHITECTURE.md` §11.9 | **RESOLVED.** Slug policy and exact approved limits are documented in `PROPERTY_API_DESIGN.md` §2 and `PROPERTY_DOMAIN_DESIGN.md` §7. | Stage 4.2 slug helper and rate-limit filters; F-8 `Retry-After` remains open. |
| **D-11** | Preserve F-1…F-8 as OPEN; document Stage 4 impact and future verification; mark none resolved without implementation and testing. | `docs/validation/STAGE_3_8_TESTING_AND_CLOSURE_REPORT.md` §I | **RESOLVED disposition; findings remain OPEN.** All eight are reproduced in `PROPERTY_AUTHORIZATION_MATRIX.md` §7 and summarized in §8 below. | Production-readiness gate; no new CSRF exemption or authorization shortcut. |

---

## 4. Updated architecture decisions SD4-01 … SD4-08

| ID | Final decision | Status | Rationale / trade-off |
|---|---|---|---|
| SD4-01 | Extend the existing ten entities, eight repositories, and V4–V8/V14 schema. Add no duplicate entity, repository, or table. | FINALIZED FOR PLANNING | Prevents parallel sources of truth; `ddl-auto: validate` and Flyway validation make ad-hoc schema changes unsafe. |
| SD4-02 | Keep six statuses; `UNAVAILABLE` is unpublish. | FINALIZED (D-04) | Preserves enum/DB CHECK and C-10/D-08 visibility; no seventh-status migration. |
| SD4-03 | Actor identity is principal-derived; admin `primaryAgentId` is a validated assignment target only. | FINALIZED (D-05) | Satisfies “never trust client-supplied identity” while preserving FR-PROP-001. |
| SD4-04 | Admin-only creation/assignment; agents manage assigned listings only. | FINALIZED (D-05) | Confirms FR-PROP-001 and DATABASE_DESIGN §6.3 rule 2. |
| SD4-05 | Enforce the single lifecycle matrix in one transactional service method. | FINALIZED (D-06) | Approved upstream documents deferred transitions; one policy avoids controller-level drift. |
| SD4-06 | Logical archival only; preserve images, inquiries, conversations, messages, and audit history. | FINALIZED (D-05) | RESTRICT FKs and FR-PROP-005/API §25.1.C forbid physical deletion. |
| SD4-07 | Backend-mediated external image storage with existing metadata table; no SDK now. | FINALIZED FOR PLANNING (D-08) | Schema fits; dependency/configuration requires separate approval. |
| SD4-08 | Reuse Stage 3 session, CSRF, CORS, deny-by-default security, authorization services, validation, and envelopes. | FINALIZED (D-11) | Avoids a second security mechanism; F-1…F-8 stay open. |

---

## 5. Assessment gap resolution matrix G-01 … G-16

| ID | Original issue | Controlling specification | Selected resolution and documents updated | Status | Implementation dependency |
|---|---|---|---|---|---|
| **G-01** | Create path deviated to `/api/v1/admin/properties`. | API §6.1; SECURITY §10.4.3 | D-01: corrected to `POST /api/v1/properties` with method-specific ADMIN matcher in `PROPERTY_API_DESIGN.md` §4.1, `PROPERTY_AUTHORIZATION_MATRIX.md` §2/§4, and this register. | **RESOLVED** | AR-01 method-specific matcher. |
| **G-02** | Six approved public endpoints omitted from inventory. | API §7; SECURITY §10.4.3/§4.3 | D-02: added `PROPERTY_API_DESIGN.md` §4.11–4.16 and matrix §4, with Stage 4.2/4.4/4.7 ownership. | **RESOLVED** | `is_featured` migration approval; D-09 reference data. |
| **G-03** | `PageResponse` vs top-level `data` + `meta` unclear. | API §7.2, §16, §19 | D-03: top-level `data` + `meta` fixed; `meta` fields, defaults, max size, and invalid-parameter errors documented in API design §2/§3.6. | **RESOLVED** | Stage 4.2 paged response DTO. |
| **G-04** | DELETE success bodies undecided. | API §17; D-03 | Archival `200` + updated resource; image delete `204` + no body. Both alternatives removed from API design §4.9/§5.4. | **RESOLVED** | Client must not parse body on image delete. |
| **G-05** | Provider failure code needed documentation. | SECURITY §13.9; `ErrorCode` | `503 SERVICE_UNAVAILABLE` is authoritative because no `502` constant exists; safe message, no provider body. Documented in API design §2/§5/§6. | **RESOLVED** | Stage 4.5 provider error mapping. |
| **G-06** | “No admin-only statuses for agents” conflicted with transition proposal. | SECURITY §10.4.3; D-06 | One authoritative matrix added to domain design §4.3; the SECURITY row/footnote was clarified to mean no status is reserved from agents, while reopening/archive remain ADMIN-only. No security rule changed. | **RESOLVED** | Single lifecycle policy component. |
| **G-07** | No property service/controller/DTO/mapper or visibility queries. | Expected greenfield; API §25.3.9 | Recorded as planned Stage 4.2 work in domain design §7 and API design §4; no implementation claimed. | **RESOLVED FOR PLANNING** | Stage 4.2 implementation. |
| **G-08** | Subtypes/amenities unseeded; no data source defined. | V4 comment; DATABASE_DESIGN §6.2/§8.1; D-09 | Initial values and controlled migration/seeding ownership documented in API design §4.14–4.16 and domain design §7; no rows or migration created. | **RESOLVED FOR PLANNING** | Stage 4.4 controlled data load before create/lookup use. |
| **G-09** | `PropertyAmenity` Javadoc says amenity removal cascades; V7 uses `RESTRICT`. | V7 SQL constraint | Stage 4 documents treat V7 `RESTRICT` as authoritative and forbid cascade assumptions. Code-comment cleanup remains a non-blocking code documentation task, not a Stage 4.1 contract change. | **RESOLVED FOR STAGE 4 PLANNING** | Optional Javadoc correction in a later implementation pass. |
| **G-10** | Static frontend status/type vocabulary differs from backend enums. | UI §4.10/§7; API §7; D-04 | Stage 4.7 mapping to six backend statuses and category/subtype IDs documented; no frontend change now. | **RESOLVED FOR PLANNING** | Stage 4.7 API client and shared status dictionary. |
| **G-11** | No Cloudinary SDK, multipart config, or SD-30 pipeline. | SECURITY §13; D-08 | Stage 4.5 dependency/config/credential/validation/failure-handling checklist recorded; no SDK added. | **RESOLVED FOR PLANNING; IMPLEMENTATION DEFERRED** | Separate Stage 4.5 approval. |
| **G-12** | No standalone SRS document. | PRD/FRD/System/API/DB/Security/UI baseline | Traceability distributed across approved documents is accepted; this register §7 and closure report provide the consolidated map. No fabricated SRS created. | **RESOLVED** | None. |
| **G-13** | Slug generation/uniqueness unspecified. | V5 nullable unique `slug`; D-10 | Server-generated unique slug from title, bounded collision suffixing, DB unique constraint as final guard; nullable column retained for compatibility. | **RESOLVED** | Stage 4.2 slug service helper. |
| **G-14** | `requirePropertyManagement` lets ADMIN pass without loading the property. | API §17 `404`; SECURITY §10.10 | Service must load the resource first and return `404` for missing IDs before policy checks; documented in matrix §3 notes/§5 and plan. | **RESOLVED** | Stage 4.2/4.3 service existence check. |
| **G-15** | “Exactly one detail row matching category” not DB-enforced. | DATABASE_DESIGN §6.5; domain design §3.2 | Declared a service-layer invariant with conditional DTO validation; no new table or migration. Documented in domain design §3.3/§4.3. | **RESOLVED** | Stage 4.2 detail-row service validation. |
| **G-16** | No property-specific mutation/search rate limits defined. | SECURITY §11.9; D-10 | Approved baselines recorded in API design §2 and matrix §5 item 14; no new thresholds invented; F-8 remains open. | **RESOLVED** | Stage 4.2/4.5 rate-limit filters. |

No G item remains an open architecture decision. Items deferred by design (G-08, G-11) are planning dependencies with documented owners and gates, not unresolved Stage 4.1 contradictions.

---

## 6. Open question resolution matrix U-01 … U-09

| ID | Question | Final outcome | Controlling decision/specification | Documents updated | Status | Implementation dependency |
|---|---|---|---|---|---|---|
| U-01 | Add `UNPUBLISHED`? | **No.** Six statuses only; `UNAVAILABLE` is unpublish. | D-04; V5; API §7/§25.1.I | Domain §4.1; API §2/§7; this register | **RESOLVED** | Future seventh status would require migration + re-approval. |
| U-02 | Allow agent self-creation? | **No.** Admin creates and assigns. | D-05; FR-PROP-001; DATABASE_DESIGN §6.3 | Domain §6; API §4.1; matrix §3 | **RESOLVED** | None; agents have no create route. |
| U-03 | Confirm admin-only archival. | **Yes.** ADMIN only; logical archive; `ARCHIVED` terminal. | D-05; API §6.1/§25.1.C | API §4.9; domain §4.3; matrix §3/§4 | **RESOLVED** | Archive service guard. |
| U-04 | Add moderation approval state? | **No.** No approval-before-publish state; ADMIN review/archive/reassign/list only. | D-04/D-05/D-06; API §25.1.G | Matrix §3 capability row; domain §4.3 | **RESOLVED** | No schema change. |
| U-05 | Description maximum. | Trimmed, non-blank, ≤10,000 characters as a Stage 4.2 request guard; DB `TEXT` unchanged. | D-07; API §18 | API §3.1; domain §3.1/§3.3 | **RESOLVED** | DTO Bean Validation. |
| U-06 | Currency validation/default. | Required uppercase active ISO 4217 alpha-3 code; no implicit default because API §6.2 requires it; `INR` remains example/UI convention. | D-07; API §6.2/§18 | API §3.1; domain §3.1/§3.3 | **RESOLVED** | Server-side ISO code allowlist/library. |
| U-07 | Retain first publication time separately? | **No.** `publishedAt` refreshed on every entry to `PUBLISHED`; used by `/properties/recent`. | D-07; API §7 | Domain §4.4; API §4.12 | **RESOLVED** | No extra column. |
| U-08 | Primary-image deletion behavior. | Leave property with **no** primary until explicitly reassigned; do not auto-promote; preserve unique partial index. | D-07; V6; SECURITY §13.10 | Domain §5.2; API §5.4/§5.5 | **RESOLVED** | Stage 4.5 deletion flow. |
| U-09 | DELETE success body. | Property archive `200` + updated resource; image delete `204` + no body. | D-03; API §17 | API §4.9/§5.4/§2 | **RESOLVED** | Client handling in 4.2/4.5. |

---

## 7. Cross-document consistency review

| Document | Review result |
|---|---|
| `PRD.md` | No conflicting property endpoint/status decision found. PD-03/PD-05 support admin creation/assignment and agent assignment scope. |
| `FRD.md` | FR-PROP-001…010 align with D-01/D-05; FR-PROP-009’s deferred lifecycle is now supplied by D-06 without changing statuses. |
| `SYSTEM_ARCHITECTURE.md` | External object storage and session architecture remain consistent; no new framework or storage path introduced. |
| `DATABASE_DESIGN.md` | Six statuses, field lengths, primary-agent constraints, image primary index, and RESTRICT history FKs are preserved. No migration created. |
| `API_SPECIFICATION.md` | All §6–8 endpoints are accounted for; create path, response envelope, public visibility, and error codes are unchanged. §25.1.D featured migration is recorded as a future dependency. |
| `SECURITY_ARCHITECTURE.md` | One authorized D-06 clarification was applied to §10.4.3. Role gates, deny-by-default, session/CSRF/CORS, SD-30/SD-31, and F-1…F-8 are unchanged. No other upstream text was rewritten. |
| `UI_UX_SPECIFICATION.md` | Status vocabulary, public visibility, admin-only featured control, and Stage 4.7 mapping remain consistent; V1 labels are not treated as backend values. |
| `PROPERTY_DOMAIN_DESIGN.md` | Finalized domain, validation limits, transition matrix, image checklist, and Stage 4.2–4.7 change set. |
| `PROPERTY_AUTHORIZATION_MATRIX.md` | Complete endpoint inventory, method-specific create gate, Stage ownership table, and enforcement checklist. |
| `PROPERTY_API_DESIGN.md` | Approved paths, envelopes, exact error behavior, public endpoints, and response codes. |
| `STAGE_4_DECISION_REGISTER.md` | This final record; D/G/U/F items all explicitly dispositioned. |

**Unresolved material conflict:** none. The only prior ambiguity (G-06) was resolved by the specifically authorized D-06 clarification; no previously approved security rule was silently changed.

---

## 8. Preserved Stage 3 security findings F-1 … F-8

All findings remain **OPEN**. They are reproduced verbatim in `PROPERTY_AUTHORIZATION_MATRIX.md` §7 and must be re-verified at the production-readiness gate. Stage 4 work must not add a CSRF exemption, client-supplied identity path, or authorization shortcut.

| ID | Stage 4 impact | Status |
|---|---|---|
| F-1 | Exact-origin validation still limited to registration; all property mutations rely on existing CSRF/CORS/SameSite/deny-by-default controls. | **OPEN** |
| F-2 | CSRF token is not regenerated at login; property mutations inherit this behavior. | **OPEN** |
| F-3 | Not triggered by property management itself; relevant to later chat. | **OPEN** |
| F-4 | Standard error envelopes inherit missing `Cache-Control: no-store` on generic errors/denials. | **OPEN** |
| F-5 | Long-lived admin property workflows amplify session-timeout gap. | **OPEN** |
| F-6 | Logout cookie-name coupling remains. | **OPEN** |
| F-7 | Frontend property pages reuse shared CSRF bootstrap/retry client. | **OPEN** |
| F-8 | Missing `Retry-After`, `last_login_at`, and proactive revocation; per-request deactivation recheck remains a compensating control. | **OPEN** |

---

## 9. Final review outcome and next steps

- Every approved property endpoint is present in the API and authorization inventories.
- No unauthorized API contract change remains; the former `/admin` create proposal is withdrawn.
- Response envelopes, pagination, error behavior, archival, and image-deletion responses are exact.
- Authorization matches the approved role model, with the method-specific ADMIN matcher required for creation.
- The lifecycle is expressed in one matrix, consistent with the D-06 SECURITY clarification.
- Existing statuses, constraints, one-primary index, and upload-security requirements are preserved.
- F-1…F-8 remain open and documented.
- No Stage 4.2 code, migration, dependency, or configuration was added during the Stage 4.1 documentation pass; the later Stage 4.5 deletion-outbox implementation is recorded in §10.

**Next step:** independent final closure report in `docs/validation/`, followed only by explicit user authorization for Stage 4.2. The four design documents are ready for the approved documentation commit; reports under `docs/validation/` must not be committed or force-added.

---

## 10. Stage 4 final closure ADR — durable image-deletion outbox

The full formal ADR is `docs/STAGE_4_IMAGE_DELETION_OUTBOX_ADR.md`. This section is the decision-register index entry.

| Field | Decision |
|---|---|
| Status | **APPROVED BY PROJECT OWNER** on **22 November 2026**, from `docs/validation/STAGE_4_FINAL_CLOSURE_DECISION.md` §5. No self-approval or delegated-signer assumption is made. The backend, database, operations, and security reviewer rows in `SECURITY_ARCHITECTURE.md` §22.2 remain **Review pending** and are not represented as approved. |
| Problem | The previous provider-first delete could succeed at the provider and then roll back the database row, leaving active metadata pointing at a missing asset. |
| Selected design | In one transaction, lock the property, authorize, reject archived, delete the `property_images` row, and insert `property_image_deletion_outbox` with provider/public ID, property/image/actor IDs, attempt state, and status. Publish an after-commit event. A backend worker claims due rows with PostgreSQL `FOR UPDATE SKIP LOCKED`, calls `ImageStorage.delete` outside any metadata transaction, and marks `COMPLETED`, `FAILED`, or `DEAD_LETTER`. |
| Consistency contract | `204 No Content` means metadata deletion and the durable task committed. Provider cleanup is eventual. No active metadata row can point at an asset the backend has confirmed missing. |
| Idempotency and recovery | Provider `not found` is success; duplicate cleanup requests and claims are harmless. Bounded exponential backoff is used; exhausted rows are dead-lettered and audited. Operator requeue: set `status = 'PENDING'`, `attempts = 0`, `next_attempt_at = now()`, `locked_until = NULL`, `last_error = NULL`. |
| Scope controls | Five approved endpoints, role rules, CSRF, archived immutability, and the one-primary invariant are unchanged. No direct browser-to-provider deletion, new frontend framework, queue service, H2 database, or Stage 5 surface is introduced. |
| Migration and operations | V17 adds the outbox table, unique asset key, status check, and due/lease indexes; V1–V16 are untouched. `app.image-cleanup.*` controls enablement, poll interval, batch size, max attempts, backoff, and lease. Eventual-consistency and retry behavior are documented in `PROPERTY_API_DESIGN.md` §5.4 and `DATABASE_DESIGN.md` §7.2. |
| Verification | Deterministic real-PostgreSQL tests cover all 15 required deletion scenarios with only the external storage boundary faked; full backend, frontend-static, and live-browser gates are recorded in the ignored final closure decision report. |
