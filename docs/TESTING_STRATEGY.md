# EstateVista V2 — Backend Testing Strategy

| Field | Value |
|---|---|
| Status | Implemented for Stage 2; expands with later stages |
| Last validated | 2026-11-15 |
| Backend | Java 21 target, Spring Boot 4.1.2, Maven, PostgreSQL 18.3 |
| Full-suite command | `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\postgres-test-cluster.ps1 -Action verify` |
| Result at last validation | 83 tests, 0 failures, 0 errors, 0 skipped |

**Historical baseline note:** The Stage 2 status and 83-test result above are retained as history. The current cross-stage backend evidence is the Stage 5.8 closure report: `452/452` tests passed with V1–V17 migrations. That result is historical evidence only; the Stage 6.1 documentation reconciliation did not rerun the backend suite and does not claim Stage 6 tests were executed.

This document describes the automated tests that exist, how they are executed,
and what they do not prove. It does not claim production readiness and does not
invent a coverage gate that has not been approved.

---

## 1. Principles

1. **Test real behaviour, not mocks of it.** Security, CORS, Actuator, Flyway,
   Hibernate validation, and persistence behavior are exercised through real
   Spring contexts and a real PostgreSQL server wherever practical.
2. **Never substitute H2 for PostgreSQL.** Database-specific types, constraints,
   indexes, and Flyway behavior must be verified on the production database
   engine family.
3. **Do not inflate or silently skip.** PostgreSQL tests are enabled only when an
   isolated test database is supplied. A database-free run reports the skipped
   tests as skipped; it is never reported as persistence coverage.
4. **Test the contract, not the implementation.** Error envelopes, status codes,
   configuration properties, security decisions, and schema constraints are the
   observable contracts.
5. **Coverage is evidence, not a score.** JaCoCo reports what executed. A
   percentage does not prove correctness, and no arbitrary threshold is used.
6. **Fixed migrations stay fixed.** Tests may read `V1`–`V14`; they must not
   modify, renumber, or clean them.

---

## 2. Test layers

| Layer | Purpose | Database | Examples |
|---|---|---|---|
| Unit | Pure model, mapping, and helper contracts | No | `ErrorCodeTest`, `ApiErrorResponseTest`, `AllowedOriginsTest`, composite-ID tests |
| Web slice | HTTP contract with the real security/CORS/error chain, no database context | No | `SecurityFilterChainTest`, `ApiErrorControllerTest`, `GlobalExceptionHandlerTest`, `CorsConfigTest` |
| Configuration | Parse and assert shared/production YAML contracts | No | `ApplicationConfigurationTest` |
| Full context | Complete application with Flyway, Hibernate validation, Spring Session, security, Actuator | Real PostgreSQL | `ApplicationIntegrationTest` |
| Persistence | Schema, metadata, repository wiring, mappings, and database constraints | Real PostgreSQL | `BusinessSchemaIntegrationTest` |

The suite intentionally mixes unit and integration layers: fast tests protect
contracts that do not need a database, while a smaller set of higher-value tests
proves that the real stack works together.

---

## 3. Test inventory

| Test class | Tests | Layer | Primary coverage |
|---|---:|---|---|
| `ApplicationIntegrationTest` | 7 | Full context | Migration state, schema validation, health, Actuator restriction, `/ws` denial, unknown route, CORS, CSRF |
| `BusinessSchemaIntegrationTest` | 16 | Persistence | Entity metadata, repository beans, column types, full business graph, commercial details, enums, constraints |
| `GlobalExceptionHandlerTest` | 16 | Web slice | Validation, status mapping, safe fallback, log redaction, container errors |
| `SecurityFilterChainTest` | 8 | Web slice | Deny-by-default, 401/403 envelopes, CSRF, public health, `/ws`, CORS accept/reject, Actuator denial |
| `AllowedOriginsTest` | 6 | Unit | Exact origins, wildcard/blank/placeholder/path/query/fragment/user-info/scheme rejection |
| `ApplicationConfigurationTest` | 6 | Configuration | Flyway ownership, Hibernate validation, Actuator exposure, secure production cookies, required production properties, SQL-log suppression |
| `ApiErrorControllerTest` | 4 | Web slice | Container `/error` envelope, safe fallback for unknown/missing status |
| `ErrorCodeTest` | 4 | Unit | Status mapping, fallback to internal error, approved status contract |
| `ApiErrorResponseTest` | 4 | Unit | Envelope construction, default messages, immutable defensive details |
| `CorsConfigTest` | 3 | Unit/configuration | Credentialed exact-origin configuration, wildcard/empty rejection |
| `ConversationReadStateIdTest` | 4 | Unit | Composite identifier equality, hash code, JPA no-argument contract |
| `PropertyAmenityIdTest` | 4 | Unit | Composite identifier equality, hash code, JPA no-argument contract |
| `HealthControllerTest` | 1 | Web slice | Health response shape |

Total: **83 tests**. With `TEST_DB_URL` supplied: 60 unit/web-layer and 23
PostgreSQL-backed. Without it: 60 run and 23 are skipped.

---

## 4. Database strategy

### 4.1 Local and developer runs

`backend/scripts/postgres-test-cluster.ps1` provisions a disposable PostgreSQL
cluster:

- directory `backend/.test-postgres` (Git-ignored);
- port `55433`, localhost only;
- `trust` authentication;
- database `estatevista_test`;
- actions `verify`, `start`, `stop`, `status`.

`verify` initialises or starts the cluster, exports `TEST_DB_URL`,
`TEST_DB_USERNAME`, and `TEST_DB_PASSWORD`, runs `mvn -B clean verify`, and stops
the cluster even when the build fails. The installed PostgreSQL service on `5432`
is never touched.

Manual execution is supported by exporting the same variables and running
`mvn -B clean verify`. See `docs/DEVELOPMENT_SETUP.md` §5.

### 4.2 Why not Testcontainers

Testcontainers was evaluated and rejected for this environment because Docker is
not installed or available. It remains a reasonable future option; the test code
does not depend on the provisioning mechanism, only on `TEST_DB_URL`.

### 4.3 Test data isolation

- Integration tests use `@Transactional` and roll back.
- Flyway seeds roles and property categories; tests read them and do not mutate
  them.
- Every created row uses a random suffix to avoid collisions.
- The script's cluster is disposable and must never contain development or
  production data.

Do not point `TEST_DB_URL` at a development or production database.

---

## 5. Running the tests

### 5.1 Full verification (required for acceptance)

```powershell
cd backend
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\postgres-test-cluster.ps1 -Action verify
```

Expected: `BUILD SUCCESS`; 83 tests, 0 failures, 0 errors, 0 skipped.

### 5.2 Database-free run

```powershell
cd backend
mvn clean verify
```

Expected: 60 tests executed, 23 skipped, no failures. This run does **not**
verify persistence and must not be reported as a full suite.

### 5.3 Reports

- Surefire: `backend/target/surefire-reports/`
- JaCoCo HTML/CSV: `backend/target/site/jacoco/`

---

## 6. Coverage policy

JaCoCo 0.8.13 runs `prepare-agent` and `report` during `verify`. At the last
validation:

| Metric | Covered / Total | Percentage |
|---|---:|---:|
| Instructions | 1720 / 2454 | 70.09% |
| Branches | 83 / 110 | 75.45% |
| Lines | 517 / 784 | 65.94% |

There is **no enforced threshold**. Stage 2 has no approved coverage gate, and
an arbitrary threshold would create a false quality signal. Coverage is used to
find untested production classes, not to declare the backend correct.

Known low-coverage areas are mostly JPA entity accessors that are exercised only
through repository paths, and not every branch of the exception handler. New
behavior should add targeted tests rather than chase a global percentage.

---

## 7. Continuous integration

`.github/workflows/backend-verify.yml`:

- triggers on backend changes to `main` and `stage-2-backend-foundation`, on pull
  requests, and manually;
- runs on `ubuntu-latest` with Temurin Java 21;
- starts a `postgres:18` service container;
- runs `mvn -B clean verify` from `backend/` with `TEST_DB_*` pointing at the
  service;
- uploads Surefire and JaCoCo reports even on failure;
- has read-only repository permissions and a 20-minute timeout.

**Status: configured but not executed.** The branch has not been pushed, so CI
behavior is unverified. Do not claim a green pipeline until it runs.

---

## 8. What the tests do not cover

- Authentication, registration, login, logout, and authorization workflows
  (not implemented).
- Business services, REST endpoints, property CRUD, favorites, inquiries, chat,
  uploads, notifications, dashboards (not implemented).
- WebSocket handshake authentication and STOMP authorization (not implemented).
- SD-53 session-bound CSRF generation/rotation (framework defaults only).
- Structured logging, formal redaction policy, retention, and correlation IDs
  (SD-44 deferred).
- Browser end-to-end behavior and visual rendering.
- Production deployment, TLS, proxy behavior, backups, and monitoring.
- CI execution, because the branch has not been pushed.

These gaps are explicit and are not represented as passing checks.

---

## 9. Maintenance rules

1. Add or update tests with every behavior change; do not lower the suite by
   deleting assertions to make a build pass.
2. Keep PostgreSQL integration tests enabled in full verification; do not mark
   them `@Disabled` to get a green local build.
3. Never modify `V1`–`V14`. Add a new migration for schema changes and update
   the persistence tests.
4. Keep `ddl-auto: validate`; Hibernate must not own schema creation.
5. Keep production configuration fail-fast tests aligned with
   `application-prod.yml`.
6. If a coverage threshold is ever approved, add it together with a documented
   rationale and a baseline; do not add an arbitrary number.
7. When CI is first pushed, record the run URL and result in the next validation
   report.

## 10. Stage 6 approved acceptance matrix

The owner-approved architecture is recorded in `docs/STAGE_6_DECISION_REGISTER.md`.
The matrix below defines future verification obligations; it does not claim any
Stage 6 test has been written, executed, or passed.

| Decision | Required verification when implemented |
|---|---|
| 1 — Inquiry lifecycle | All statuses and transitions, invalid-transition `409`, idempotent same-status `200` with no audit/timestamp change, `resolved_at` rules, audit records without content, serialized concurrency, buyer read-only status, active AGENT/ADMIN authority, and no transcript access. |
| 2 — Duplicate inquiry | One active `NEW`/`IN_PROGRESS` inquiry per buyer/property; inactive statuses allow a new inquiry; duplicate active submission returns `409`; concurrent submissions cannot bypass the Stage 6.4 partial unique index; no merge/overwrite/delete. |
| 3 — Favorite mutations | `201` new, `200` existing published, `204` existing/nonexistent removal, `404` nonexistent/non-public add, principal-derived buyer, preserved unique constraint, safe concurrency, and no soft delete. |
| 4 — Favorite retention/privacy | Retention through non-public property states, generic unavailable placeholder with no unpublished fields, continued removal, republish restoration, and no automatic deletion. |
| 5 — Favorite API/state sync | Approved route set, 100-UUID comma-separated batch cap, duplicate de-duplication, nonexistent/non-public batch state as `isFavorite:false`, one batch request per collection, paginated favorites page, no public personalized flags, bounded queries without N+1, stale-response control, in-memory reset, and private `no-store` responses. |
| 6 — Assignment/deactivation | Immutable historical `assigned_agent_id`, current eligible active assignment on new inquiries, deactivation denial, reactivated-agent access, admin inactive-agent management, transactional rechecks, and retained history. |
| 7 — API contracts | Standard envelope, exact DTO field sets, `400/401/403/404/409/429`, equivalent generic `404` for unauthorized/missing records, subject ≤200, message ≤5,000, rejected client identity/status fields, exact pagination/sort/status filters, and deterministic UUID tiebreak. |
| 8 — Rate limits | Overlapping `300/min/user`, `60/min/admin`, `60/min/user` favorite mutations, `5/min` and `30/hour` inquiry submissions, shared-bucket semantics, server-derived identity, `429` envelope, longest-block `Retry-After`, CORS exposure of that header, and no automatic mutation retry. |
| 9 — Frontend scope | Functional inquiry CTA replacing the illustrative link, auth prompt, buyer-only submission, role-aware controls, buyer read-only status, accessible loading/empty/error/validation/success states, shared Vanilla JS client, safe DOM rendering, no browser storage of inquiry content, no chat UI/handlers/FK, and no `retryOnCsrfFailure` on mutations. |
| 10 — V18 gate | No V18 file in Stage 6.1; V1–V17 unchanged; in Stage 6.4 verify data inspection, clean V1→V18, V17→V18, Flyway validation, status CHECK, partial unique index, concurrency behavior, and updated migration inventory tests. |
| 11 — Security schedule | Stage 6.2 evidence for F-1, F-2, F-4, and F-7 before mutations; F-3 Stage 7; F-5 Stage 8; F-6 Stage 9; F-8 partially open with remaining obligations in Stage 8/9. Scheduling is not resolution. |
| 12 — Boundaries | No chat, dashboard/account management, deployment, featured endpoint, or other Stage 7–9 work is tested as part of Stage 6 acceptance. |

V1–V14 in §9 rule 3 remains the historical Stage 2 wording. The Stage 5.8 baseline is V1–V17, and the authorized V18 is Stage 6.4-only. The historical `452/452` result does not satisfy any new Stage 6 verification row above.
