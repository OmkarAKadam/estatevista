# EstateVista V2 — Stage 2.7 & 2.8 Completion Report

| Field | Value |
|---|---|
| Sub-stages | 2.7 — Backend Automated Testing Strategy & Coverage; 2.8 — Backend Integration Review, Final Verification & Stage 2 Closure |
| Overall status | **PASS — Stage 2 closed for the approved foundation scope, with documented deferrals** |
| Execution agent | OpenCode |
| Execution date | 15 November 2026 |
| Feature branch | `stage-2-backend-foundation` |
| Starting HEAD | `c835eae` — `docs(stage-2): record Stage 2.5 and 2.6 completion report` |
| Test/tooling commit | `407c338` — `test(stage-2): expand automated coverage and verification tooling` |
| Report commit | The commit that adds this file (see `git log -1`) |
| Push status | Not pushed |
| Merge status | Not merged into `main` |

---

## A. Executive Summary

Stage 2.7 established the backend automated testing strategy. The suite grew from
40 tests in 6 classes to **83 tests in 13 classes**, with 43 new tests covering
the error model, configuration contracts, full application context, security
filter chain, CORS bean wiring, persistence gaps, and composite identifiers.
All database-backed tests run against the real PostgreSQL engine family when the
test database is supplied; H2 is not used and PostgreSQL tests are not silently
skipped in a full run.

Stage 2.7 also added:

- a disposable PostgreSQL test-cluster script that provisions an isolated
  database, runs the full Maven verification, and always stops the cluster;
- JaCoCo 0.8.13 coverage reporting with no arbitrary threshold;
- a GitHub Actions workflow that runs the same full verification against a
  `postgres:18` service container and uploads Surefire and JaCoCo reports.

Stage 2.8 reviewed the integrated backend across architecture, configuration,
database, persistence, security, logging, health/Actuator, documentation
consistency, and scope compliance. It then re-ran the complete suite twice and
performed a packaged-jar runtime smoke test against PostgreSQL 18.3.

Final verification:

| Field | Result |
|---|---|
| Full suite | **83 tests, 0 failures, 0 errors, 0 skipped** |
| Run 1 | `BUILD SUCCESS`, 1 min 38 s |
| Run 2 | `BUILD SUCCESS`, 1 min 37 s |
| Flyway | 14 migrations validated; schema at version 14 |
| Coverage | Instruction 70.09% (1720/2454); branch 75.45% (83/110); line 65.94% (517/784) |
| Runtime smoke | Packaged jar started on PostgreSQL; health, Actuator restriction, deny-by-default, CORS, CSRF, and session persistence verified |

**Stage 2.7 verdict: PASS — accepted.**
**Stage 2.8 verdict: PASS — accepted with documented deferrals.**
**Stage 2 verdict: CLOSED for the approved backend-foundation scope.**

**SD-44 verdict: PARTIAL — safe logging baseline retained; structured format,
formal redaction policy, and retention remain unspecified.**
**SD-47 verdict: VERIFIED FOR EXISTING STAGE 2 CONTROLS — production log levels and
Actuator restrictions remain in place; per-release verification remains a
recurring obligation.**
**SD-53 verdict: NOT IMPLEMENTED — CSRF remains framework-default only.**

This report does not claim production readiness. Authentication, authorization
workflows, business services, REST endpoints, SD-53 CSRF wiring, WebSocket
message handling, CI execution, and production deployment remain unimplemented
or unverified.

---

## B. Initial Repository State

| Item | State at start |
|---|---|
| Branch | `stage-2-backend-foundation` |
| HEAD | `c835eae` — `docs(stage-2): record Stage 2.5 and 2.6 completion report` |
| Working tree | Clean |
| Expected history | Stage 1/Stage 2 commits present, including the 2.5/2.6 report |
| Framework | Java 21 target, Spring Boot 4.1.2, Maven, PostgreSQL, JPA/Hibernate, Flyway |
| Existing tests | 40 total: 6 classes, including 29 unit/web-layer and 11 PostgreSQL-backed |
| Existing CI | None |
| Migrations | `V1`–`V14` present and treated as immutable |
| Docker / Testcontainers | Docker not installed; Testcontainers unavailable |
| Database | PostgreSQL 18.3 installed; isolated trust-auth test cluster on port 55433 |

The installed PostgreSQL service on port `5432` uses `scram-sha-256` with an
unknown password and was never modified. All integration runs used the isolated
cluster.

`V1`–`V14` were not modified before, during, or after either sub-stage. The
migration directory diff is empty.

---

## C. Documentation and Design Traceability

### Documents reviewed

- `docs/PRD.md`
- `docs/FRD.md`
- `docs/SYSTEM_ARCHITECTURE.md`
- `docs/DATABASE_DESIGN.md`
- `docs/API_SPECIFICATION.md` §17 (standard error envelope)
- `docs/SECURITY_ARCHITECTURE.md` (SD-44, SD-47, SD-53; §11.13, §11.17,
  §14.9, §18.4, Section 17 tests)
- `docs/UI_UX_SPECIFICATION.md`
- `docs/DEVELOPMENT_SETUP.md`
- `backend/README.md`
- `docs/validation/stage-2.3-2.4-completion-report.md`
- `docs/validation/stage-2.5-2.6-completion-report.md`
- Existing backend source, tests, `pom.xml`, profiles, and migrations

### Requirements applied to Stage 2.7 / 2.8

| Area | Requirement |
|---|---|
| Real database | Automated verification must use PostgreSQL; no H2 substitute |
| Existing behavior | Do not change authentication, business services, endpoints, or runtime behavior to make tests pass |
| Migrations | `V1`–`V14` remain immutable |
| Coverage | Produce evidence of execution; do not fabricate coverage or invent an unapproved gate |
| Persistence | Verify mappings and database constraints, including detail tables and composite IDs |
| Configuration | Verify fail-fast production settings and shared configuration contracts |
| Security | Preserve deny-by-default, exact-origin credentialed CORS, `/ws` denial, and framework CSRF |
| Repeatability | Provide a repeatable way to provision the test database without modifying the installed service |
| CI | Add a pipeline that can run the full suite (execution recorded honestly if not run) |
| Integration review | Cross-check architecture, database, persistence, security, logging, health/Actuator, and documentation |
| Closure | Run the full suite twice, verify scope compliance, commit verified work, leave a clean tree |

### Traceability

| Control | Implementation / evidence |
|---|---|
| Standard error envelope | `GlobalExceptionHandlerTest` (16), `ApiErrorControllerTest` (4), `ApiErrorResponseTest` (4), `ErrorCodeTest` (4) |
| Deny-by-default security | `SecurityFilterChainTest` (8), `ApplicationIntegrationTest` (7), runtime probes |
| Exact-origin CORS | `AllowedOriginsTest` (6), `CorsConfigTest` (3), full-context and runtime probes |
| Production configuration | `ApplicationConfigurationTest` (6) |
| Schema ownership and validation | `ApplicationConfigurationTest`, `ApplicationIntegrationTest`, `BusinessSchemaIntegrationTest` (16) |
| Persistence mappings and constraints | `BusinessSchemaIntegrationTest` (16) |
| Composite identifiers | `PropertyAmenityIdTest` (4), `ConversationReadStateIdTest` (4) |
| Health / Actuator restriction | `HealthControllerTest` (1), slice and full-context tests, runtime probes |
| Test provisioning | `backend/scripts/postgres-test-cluster.ps1` |
| CI capability | `.github/workflows/backend-verify.yml` |

---

## D. Stage 2.7 Implementation

### D.1 Test strategy

The strategy follows the test pyramid without pretending that unit tests verify
infrastructure:

| Layer | Database | Purpose |
|---|---|---|
| Unit | No | Error model, origin validator, CORS bean, composite identifiers |
| Web slice | No | HTTP contract with the real security/error chain |
| Configuration | No | Shared and production YAML contracts |
| Full context | PostgreSQL | Flyway, Hibernate validation, Spring Session, Actuator, security, CORS |
| Persistence | PostgreSQL | Entity metadata, repositories, mappings, constraints |

The full strategy is documented in `docs/TESTING_STRATEGY.md`.

### D.2 New test classes

| Class | Tests | Coverage focus |
|---|---:|---|
| `ApplicationIntegrationTest` | 7 | Complete context: DB connection, Flyway state, `ddl-auto=validate`, health endpoints, Actuator restriction, `/ws`, unknown route, CORS, CSRF |
| `ApplicationConfigurationTest` | 6 | Flyway ownership, Hibernate validation, Actuator exposure, secure prod cookie, required prod properties, SQL/bind/extract suppression |
| `ErrorCodeTest` | 4 | Status mapping, fallback to `INTERNAL_ERROR`, approved status contract |
| `ApiErrorResponseTest` | 4 | Default messages, envelope construction, immutable defensive details |
| `CorsConfigTest` | 3 | Credentialed exact-origin configuration, wildcard/empty rejection |
| `PropertyAmenityIdTest` | 4 | Composite-ID equality, hash code, JPA no-argument contract |
| `ConversationReadStateIdTest` | 4 | Composite-ID equality, hash code, JPA no-argument contract |

### D.3 Expanded existing test classes

| Class | Before | After | Added coverage |
|---|---:|---:|---|
| `GlobalExceptionHandlerTest` | 13 | 16 | Missing header `400`, `ResponseStatusException` client mapping, server-reason suppression |
| `ApiErrorControllerTest` | 2 | 4 | Unknown container status fallback, missing status fallback |
| `SecurityFilterChainTest` | 7 | 8 | Non-exposed Actuator paths remain denied |
| `BusinessSchemaIntegrationTest` | 11 | 16 | Duplicate agent profile, duplicate favorite, duplicate conversation, out-of-range latitude, commercial-details persistence with shared primary key |

The commercial-details test closed a genuine gap: `PropertyCommercialDetails`
was mapped and wired but never persisted by any test. It now verifies the shared
primary key (`MapsId`), every field, the lazy property relationship, and the
stored `suitable_for` value through JDBC.

### D.4 Repeatable PostgreSQL provisioning

`backend/scripts/postgres-test-cluster.ps1` supports `verify`, `start`, `stop`,
and `status`:

- cluster directory `backend/.test-postgres`, Git-ignored and outside `target/`;
- port `55433`, localhost only, `trust` authentication;
- database `estatevista_test`;
- `verify` exports `TEST_DB_*`, runs `mvn -B clean verify`, and stops the cluster
  in a `finally` block;
- the installed PostgreSQL service is never touched.

Testcontainers was evaluated and rejected because Docker is not installed or
available. The workflow in CI uses a `postgres:18` service container instead.

### D.5 Coverage reporting

JaCoCo 0.8.13 is configured with `prepare-agent` and `report` (bound to
`verify`). No threshold is enforced because Stage 2 has no approved coverage
gate. The report is generated at `backend/target/site/jacoco/`.

### D.6 CI workflow

`.github/workflows/backend-verify.yml` runs on backend changes, pull requests,
and manual dispatch:

- Ubuntu, Temurin Java 21, Maven cache;
- `postgres:18` service with a health check;
- `mvn -B clean verify` with `TEST_DB_*` pointing at the service;
- Surefire and JaCoCo artifact upload even on failure;
- read-only repository permissions and a 20-minute timeout.

The workflow is committed but **has not been executed**, because the branch has
not been pushed.

### D.7 Explicit non-changes

- No main production source file changed.
- No migration changed.
- No dependency was added to production scope; the only `pom.xml` change is the
  JaCoCo build plugin.
- No authentication, service, controller, endpoint, entity, role, or validation
  rule was added.
- No test count was inflated by parameterized or duplicate cases that assert the
  same behavior.

---

## E. Stage 2.7 Verification

### E.1 Full suite

Command, executed from `backend/`:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\postgres-test-cluster.ps1 -Action verify
```

| Field | Run 1 | Run 2 |
|---|---|---|
| Result | `BUILD SUCCESS` | `BUILD SUCCESS` |
| Tests | 83 | 83 |
| Failures | 0 | 0 |
| Errors | 0 | 0 |
| Skipped | 0 | 0 |
| Duration | 1 min 38 s | 1 min 37 s |
| Database | PostgreSQL 18.3, isolated port 55433 | Same |
| Flyway | 14 validated, version 14 | 14 validated, version 14 |

The second run started from a fresh cluster lifecycle and produced identical
totals, confirming repeatability of the provisioning and verification path. A
third full run, executed after a database-free check to restore the full JaCoCo
report, also passed with 83/0/0/0 (Maven build 1 min 32 s).

A database-free run (`mvn -B clean verify` without `TEST_DB_URL`) executed 60
tests, skipped the 23 PostgreSQL-backed tests, and passed with 0 failures and 0
errors. That mode is not a full verification.

### E.2 Test breakdown

| Test class | Tests |
|---|---:|
| `ApplicationIntegrationTest` | 7 |
| `BusinessSchemaIntegrationTest` | 16 |
| `GlobalExceptionHandlerTest` | 16 |
| `SecurityFilterChainTest` | 8 |
| `AllowedOriginsTest` | 6 |
| `ApplicationConfigurationTest` | 6 |
| `ApiErrorControllerTest` | 4 |
| `ErrorCodeTest` | 4 |
| `ApiErrorResponseTest` | 4 |
| `CorsConfigTest` | 3 |
| `ConversationReadStateIdTest` | 4 |
| `PropertyAmenityIdTest` | 4 |
| `HealthControllerTest` | 1 |
| **Total** | **83** |

Database-backed: 23 (`ApplicationIntegrationTest` 7 + `BusinessSchemaIntegrationTest`
16). Unit/web-layer: 60.

### E.3 Coverage

| Metric | Covered / Total | Percentage |
|---|---:|---:|
| Instructions | 1720 / 2454 | 70.09% |
| Branches | 83 / 110 | 75.45% |
| Lines | 517 / 784 | 65.94% |

Selected package instruction coverage:

| Package | Coverage |
|---|---:|
| `com.estatevista.config` | 98.70% |
| `com.estatevista.common.response` | 100.00% |
| `com.estatevista.common.health` | 100.00% |
| `com.estatevista.common.exception` | 79.33% |
| `com.estatevista.audit.entity` | 64.38% |
| `com.estatevista.property.entity` | 61.91% |
| `com.estatevista.chat.entity` | 60.62% |
| `com.estatevista.notification.entity` | 46.58% |
| `com.estatevista.favorite.entity` | 45.16% |
| `com.estatevista.inquiry.entity` | 39.73% |
| `com.estatevista.user.entity` | 32.83% |

Low entity percentages are dominated by accessor and JPA-lifecycle methods that
are only partially exercised through repository paths. They are reported as
observed, not hidden or padded with tests that call getters without asserting
behavior.

### E.4 Repeatability notes

- The script initially lived under `target/`; `mvn clean` deleted a running
  cluster. It was moved to `backend/.test-postgres` before final verification.
- PowerShell treats native stderr from `pg_ctl status` as an error; the script
  temporarily sets `$ErrorActionPreference = 'SilentlyContinue'` around that
  probe.
- These were tooling defects in the new script, fixed before the recorded runs.
  The final two runs required no manual intervention.

---

## F. Stage 2.8 Integration Review

### F.1 Architecture and configuration

| Check | Verdict | Evidence |
|---|---|---|
| Package structure follows the approved modular monolith | PASS | `config`, `common`, `user`, `property`, `favorite`, `inquiry`, `chat`, `notification`, `audit` packages |
| `/api/v1` prefix retained | PASS | No endpoint path changed |
| Shared configuration remains centralized | PASS | `application.yml` |
| Production fails fast on missing DB/origin values | PASS | `application-prod.yml`; `ApplicationConfigurationTest` |
| Hibernate owns no schema | PASS | `ddl-auto: validate`; Flyway enabled and validated |
| Session schema remains Flyway-owned | PASS | `spring.session.jdbc.initialize-schema: never` |
| Actuator exposure remains `health` only | PASS | Configuration test, full-context test, runtime probe |
| No production configuration weakened | PASS | No main source or profile diff during 2.7/2.8 |

### F.2 Database and migrations

| Check | Verdict | Evidence |
|---|---|---|
| `V1`–`V14` unchanged | PASS | `git diff` on migration directory empty |
| Fresh/existing schema validates | PASS | 14 migrations validated, version 14 |
| Schema matches entity mappings | PASS | Hibernate validation at context startup |
| Expected tables/columns/types | PASS | `BusinessSchemaIntegrationTest` column-type assertions |
| Seeded roles and categories | PASS | `BusinessSchemaIntegrationTest` |
| Composite category/subtype FK | PASS | Constraint rejection test |
| Positive price and coordinate range | PASS | Constraint rejection tests |
| One primary image per property | PASS | Partial unique index test |
| Duplicate agent profile/favorite/conversation | PASS | Constraint rejection tests |
| Detail tables (residential and commercial) | PASS | Full-graph and commercial-details tests |

### F.3 Persistence

| Check | Verdict | Evidence |
|---|---|---|
| Every approved entity is mapped | PASS | Metamodel assertion |
| Every repository bean initializes | PASS | Context bean lookup |
| Full business graph persists and reloads | PASS | `persistsAndRetrievesTheFullBusinessGraph` |
| Commercial details persist with shared PK | PASS | New Stage 2.7 test |
| Enums persist as approved text | PASS | JDBC assertions |
| Composite IDs implement equality correctly | PASS | Unit tests |

No entity or mapping defect was found during review.

### F.4 Security

| Check | Verdict | Evidence |
|---|---|---|
| Deny-by-default | PASS | Slice, full-context, and runtime `401` for unknown API paths |
| `/ws` denied | PASS | Slice, full-context, and runtime `401` |
| Only health endpoints public | PASS | `/api/v1/health`, `/actuator/health`, `/error` |
| Non-health Actuator denied | PASS | `/actuator/beans`, `/actuator/env` `401` |
| Exact-origin CORS | PASS | Allowed origin headers, disallowed origin `403` without allow-origin |
| Credentials enabled only with explicit origin | PASS | Runtime and test headers |
| CSRF default remains enabled | PASS | Tokenless `POST` `403` JSON envelope |
| No JWT or token authentication introduced | PASS | No auth code or dependency added |
| No endpoint or service introduced | PASS | Main source diff empty |
| SD-53 not overstated | PASS | Framework defaults only; documented as not implemented |

### F.5 Logging and exception hygiene

| Check | Verdict | Evidence |
|---|---|---|
| Error envelope remains the only client error shape | PASS | Contract tests |
| No stack traces/exception classes/SQL in responses | PASS | Handler tests |
| Malformed-request logging excludes parser messages/body | PASS | Log capture test |
| Production SQL/bind/extract suppressed | PASS | Configuration contract test and existing runtime evidence |
| SD-44 not overstated | PASS | Documented as partial |

### F.6 Health and Actuator

| Check | Verdict | Evidence |
|---|---|---|
| Application health is liveness-only | PASS | `HealthControllerTest`; no DB query |
| Actuator includes DB health | PASS | Full-context test and runtime `UP` |
| Details hidden | PASS | `show-details: never`; `components` absent in test |
| Only `health` exposed | PASS | Configuration and runtime probes |

Runtime returned
`{"groups":["liveness","readiness"],"status":"UP"}` for `/actuator/health`; this
is the Boot 4 group metadata with no component details.

### F.7 Cross-document consistency

Reviewed and corrected:

- `backend/README.md` — Stage 2.8 status, 83-test totals, layered breakdown,
  script command, coverage, CI status, repository structure.
- `docs/DEVELOPMENT_SETUP.md` — Stage 2.8 header, repeatable cluster workflow,
  Stage 2.7/2.8 verification table, updated limitations and troubleshooting, and
  Stage 2 boundary.
- `docs/TESTING_STRATEGY.md` — new authoritative description of test layers,
  inventory, database strategy, coverage policy, CI, and gaps.
- `backend/scripts/postgres-test-cluster.ps1` — corrected a stale comment that
  said the cluster lived under `target/`.

Historical validation reports were left unchanged because they accurately
record the state at their own execution time. No approved design document was
modified.

No cross-document contradiction was found in the current behavior. The prior
known issues that this stage improves (test provisioning and CI) are recorded as
improved-but-not-fully-closed in §K.

### F.8 Scope compliance

| Prohibition | Status |
|---|---|
| No JWT | Confirmed — no token authentication added |
| No H2 substitute | Confirmed — tests use PostgreSQL 18.3 |
| No disabled schema validation | Confirmed — `ddl-auto=validate` |
| No disabled CSRF | Confirmed — framework CSRF still enabled |
| No `V1`–`V14` modification | Confirmed — empty migration diff |
| No auth/business service/endpoint changes | Confirmed — empty main-source diff |
| No production deployment | Confirmed — no deployment performed |
| No frontend changes | Confirmed — no `frontend/` diff |
| No push/merge/destructive Git | Confirmed — local branch only |

---

## G. Stage 2.8 Final Verification

### G.1 Full build

The two full runs recorded in §E are the Stage 2.8 final builds. No code or
configuration changed between them and the runtime smoke test, except the
documentation and tooling-comment updates listed in §J.

| Field | Result |
|---|---|
| Build | `BUILD SUCCESS` |
| Tests | 83 |
| Failures / errors / skipped | 0 / 0 / 0 |
| Java compilation | `release 21`; runtime Java 23.0.2 |
| Maven | 3.9.14 |
| PostgreSQL | 18.3, isolated cluster on port 55433 |
| Flyway | 14 migrations validated, version 14 |
| JAR | Repackaged successfully |

### G.2 Packaged-jar runtime smoke

Environment: packaged jar, `dev` profile, port `18080`, isolated PostgreSQL
cluster on port `55433`, database `estatevista_test`.

| Check | Result |
|---|---|
| Startup | PASS — started in 29.6 s; Tomcat on 18080 |
| Flyway at startup | PASS — 14 validated; current version 14 |
| `GET /api/v1/health` | PASS — `200 {"status":"UP","service":"estatevista-api",...}` |
| `GET /actuator/health` | PASS — `200 {"groups":["liveness","readiness"],"status":"UP"}` |
| `GET /actuator/beans` | PASS — `401` standard JSON envelope |
| `GET /ws` | PASS — `401` standard JSON envelope |
| `GET /api/v1/not-implemented` | PASS — `401` standard JSON envelope |
| CORS allowed preflight | PASS — `200`; matching origin; credentials `true`; exposed `X-CSRF-TOKEN` |
| CORS disallowed preflight | PASS — `403`; no allow-origin header |
| CSRF tokenless `POST` | PASS — `403` standard JSON envelope |
| Session cookie | PASS — `EV_SESSION=...; Path=/; HttpOnly; SameSite=Lax` |
| Session persistence | PASS — row in `spring_session`; `MAX_INACTIVE_INTERVAL` 1800 |

The application log contained no startup failure. The `dev` profile intentionally
enables debug logging, so the production-log assertions from Stage 2.6 were not
re-run here; their configuration contract remains covered by
`ApplicationConfigurationTest`.

### G.3 Not performed

| Check | Status | Reason |
|---|---|---|
| CI pipeline execution | NOT VERIFIED | Branch not pushed |
| Browser end-to-end flow | NOT PERFORMED | No browser automation and no auth flow |
| Production deployment/TLS/proxy | NOT PERFORMED | Out of scope; no credentials or hostnames |
| Structured JSON logs / retention | NOT PERFORMED | SD-44 requirements unspecified |
| SD-53 CSRF generation/rotation | NOT IMPLEMENTED | Authentication-stage work |
| Authentication and business flows | NOT PERFORMED | Not implemented |

No unavailable check is reported as PASS.

---

## H. Test Totals and Execution Details

| Item | Baseline (HEAD `c835eae`) | Final |
|---|---:|---:|
| Test classes | 6 | 13 |
| Total tests | 40 | 83 |
| Unit/web-layer | 29 | 60 |
| PostgreSQL-backed | 11 | 23 |
| Failures | 0 | 0 |
| Errors | 0 | 0 |
| Skipped (full run) | 0 | 0 |

New tests: **43**.

Command:

```powershell
cd backend
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\postgres-test-cluster.ps1 -Action verify
```

Database-free mode (`mvn clean verify` without `TEST_DB_URL`) executes the 60
unit/web-layer tests and skips the 23 integration tests. It is not reported as a
full verification.

### Warnings observed

- Mockito self-attached its inline mock maker and warned about future JDK
  restrictions. Tests passed.
- Hibernate logged the expected constraint violations produced by negative
  persistence tests. Tests passed.
- Git reported LF→CRLF normalization notices on Windows.
- No compiler warnings were observed.

---

## I. Security and Scope Review

| Requirement | Status | Evidence |
|---|---|---|
| Java 21 target preserved | Confirmed | `pom.xml`; `release 21` compilation |
| Spring Boot 4.1.2 / Maven / PostgreSQL / JPA / Flyway preserved | Confirmed | No production dependency or version change |
| `/api/v1` prefix preserved | Confirmed | No path change |
| Spring Session JDBC preserved | Confirmed | Session row persisted at runtime |
| Session authentication model preserved | Confirmed | No JWT or token auth added |
| Deny-by-default preserved | Confirmed | Slice, full-context, runtime `401` |
| `/ws` denied | Confirmed | Slice, full-context, runtime `401` |
| Exact-origin credentialed CORS preserved | Confirmed | Tests and runtime headers |
| CSRF framework default preserved | Confirmed | Tokenless `POST` `403` |
| SD-53 not claimed complete | Confirmed | Documented as not implemented |
| No authentication flow introduced | Confirmed | Main source diff empty |
| No business service/controller/endpoint introduced | Confirmed | Main source diff empty |
| No entity/role/validation rule invented | Confirmed | No main source change |
| `V1`–`V14` unchanged | Confirmed | Empty migration diff |
| `ddl-auto=validate` preserved | Confirmed | Config test and context startup |
| No H2 substitute | Confirmed | Real PostgreSQL 18.3 |
| No secrets committed | Confirmed | Script uses trust auth on localhost; CI uses service credentials only |
| No generated artifacts or cluster data staged | Confirmed | `.gitignore` covers `target/` and `.test-postgres/` |
| No push or merge | Confirmed | Branch remains local |
| Frontend untouched | Confirmed | No `frontend/` diff |
| No production-readiness claim | Confirmed | This report states the opposite |

---

## J. Files Changed

### Created — test and tooling

- `backend/src/test/java/com/estatevista/ApplicationIntegrationTest.java`
- `backend/src/test/java/com/estatevista/common/exception/ErrorCodeTest.java`
- `backend/src/test/java/com/estatevista/common/response/ApiErrorResponseTest.java`
- `backend/src/test/java/com/estatevista/config/ApplicationConfigurationTest.java`
- `backend/src/test/java/com/estatevista/config/CorsConfigTest.java`
- `backend/src/test/java/com/estatevista/chat/entity/ConversationReadStateIdTest.java`
- `backend/src/test/java/com/estatevista/property/entity/PropertyAmenityIdTest.java`
- `backend/scripts/postgres-test-cluster.ps1`
- `.github/workflows/backend-verify.yml`

### Modified — test and build

- `backend/pom.xml` — JaCoCo 0.8.13 `prepare-agent` and `report`; no threshold.
- `backend/.gitignore` — ignore `.test-postgres/`.
- `backend/src/test/java/com/estatevista/common/exception/ApiErrorControllerTest.java`
  — 2 new tests.
- `backend/src/test/java/com/estatevista/common/exception/GlobalExceptionHandlerTest.java`
  — 3 new tests.
- `backend/src/test/java/com/estatevista/config/SecurityFilterChainTest.java`
  — 1 new test.
- `backend/src/test/java/com/estatevista/persistence/BusinessSchemaIntegrationTest.java`
  — 5 new tests, including commercial-details persistence.

### Created / modified — documentation

- `backend/README.md` — Stage 2.8 status, test strategy, totals, coverage, CI.
- `docs/DEVELOPMENT_SETUP.md` — repeatable test cluster, verification evidence,
  limitations, troubleshooting, stage boundary.
- `docs/TESTING_STRATEGY.md` — new authoritative testing strategy.
- `docs/validation/stage-2.7-2.8-completion-report.md` — this report.

### Deleted

None.

### Migrations

None. No migration file was created, modified, renamed, or removed.

### Production source

None. `backend/src/main/java` has no diff.

---

## K. Known Issues and Deferred Work

| ID | Description | Impact | Severity | Resolution stage | Blocks Stage 2 closure? |
|---|---|---|---|---|---|
| K-01 | SD-44 structured JSON log format is not specified or implemented | Logs remain human-readable; aggregation is limited | Low | Logging policy stage | No — deferred |
| K-02 | SD-44 formal redaction policy and retention/rotation are not specified | No defined retention window | Medium | Logging/operations stage | No — deferred |
| K-03 | No request correlation ID is generated or logged | Cross-request tracing relies on timestamps and paths | Low | Observability stage | No |
| K-04 | SD-53 CSRF implementation is absent | Only framework-default CSRF exists | High (before auth) | Authentication stage | No — out of scope |
| K-05 | Disallowed-origin CORS responses use Spring's plain-text body | Origin decisions are correct; body is not the JSON envelope | Low | Error-contract refinement | No |
| K-06 | Actuator shares the application port | Management traffic is not network-isolated | Low | Deployment/operations stage | No |
| K-07 | Multipart and some data-binding failures can fall through to `500` | Responses stay safe but are not semantically precise | Low | Exception-handler refinement | No |
| K-08 | Render supplies `PORT`; the application reads `SERVER_PORT` | A deployment setting only `PORT` may bind unexpectedly | Medium | Deployment stage | No — no deployment in scope |
| K-09 | Persistence tests still require an externally supplied isolated database | The default database-free build skips them | Medium | Now mitigated locally; full closure needs mandatory CI | No — full verification is documented and repeatable |
| K-10 | Testcontainers is not used | Provisioning is environment-specific (PowerShell script locally, service container in CI) | Low | Future test-infrastructure improvement | No |
| K-11 | CI workflow is configured but has not executed | Pipeline behavior is unverified until first push | Medium | First push / CI validation | No — execution is explicitly not claimed |
| K-12 | No browser automation | Session/CORS behavior is verified at the HTTP layer only | Low | E2E stage | No |
| K-13 | Production deployment, TLS, and proxy behavior are not verified | No production-readiness claim is possible | Medium | Deployment stage | No |
| K-14 | Inquiry/chat/notification status/type columns remain unconstrained `VARCHAR` | Unknown values cannot be rejected until lifecycles are approved | Low | Workflow/chat stages | No |
| K-15 | Audit append-only is an application rule, not database-enforced | A privileged path could update/delete audit rows | Medium | Audit implementation stage | No |
| K-16 | No services, controllers, or endpoints for persisted entities | Persistence is not user-reachable | Expected | Stages 3–8 | No |
| K-17 | Malformed-request redaction is tested for the parser path only | A future handler could log unsafely without a failing test | Low | Ongoing test expansion | No |
| K-18 | No approved coverage threshold exists | Coverage can regress without failing the build | Low | Quality-gate decision | No — no gate has been approved |
| K-19 | Entity accessor/JPA-lifecycle coverage remains low | Coverage percentage is dragged down by unasserted getters/setters | Low | Later feature tests | No |
| K-20 | The local cluster script is Windows/PowerShell-specific | macOS/Linux developers must use manual provisioning or CI | Low | Optional cross-platform script | No |

No failed test or incomplete acceptance criterion is hidden in this section.

---

## L. Acceptance Assessment

### Stage 2.7 acceptance gate

| Criterion | Verdict | Explanation |
|---|---|---|
| Testing strategy is defined and documented | PASS | `docs/TESTING_STRATEGY.md` |
| Full suite runs against real PostgreSQL | PASS | 83 tests; PostgreSQL 18.3 |
| No H2 or silent PostgreSQL skips | PASS | 0 skipped in full runs |
| Existing behavior is protected by tests | PASS | Error, security, CORS, configuration, health |
| Genuine persistence gaps are covered | PASS | Commercial details, duplicate constraints, composite IDs |
| Test database provisioning is repeatable | PASS | Disposable-cluster script; two clean runs |
| Coverage is measured honestly | PASS | JaCoCo report; no arbitrary threshold |
| CI capability exists | PASS (configured) | Workflow committed; execution not claimed |
| No production behavior changed to pass tests | PASS | Main source diff empty |
| Evidence is recorded | PASS | §D, §E, §H |

**Stage 2.7 verdict: PASS — ACCEPTED.**

### Stage 2.8 acceptance gate

| Criterion | Verdict | Explanation |
|---|---|---|
| Architecture and configuration review | PASS | §F.1 |
| Database and migration review | PASS | §F.2; 14 migrations immutable |
| Persistence review | PASS | §F.3 |
| Security review | PASS | §F.4 |
| Logging and exception review | PASS | §F.5 |
| Health and Actuator review | PASS | §F.6 |
| Documentation consistency | PASS | §F.7 |
| Scope and prohibition compliance | PASS | §F.8, §I |
| Full verification, twice | PASS | 83/0/0/0 both runs |
| Runtime integrated smoke test | PASS | §G.2 |
| Clean working tree after commit | PASS | Verified after the report commit |
| No production-readiness overclaim | PASS | §A, §G.3, §K |

**Stage 2.8 verdict: PASS — ACCEPTED WITH DOCUMENTED DEFERRALS.**

### SD-44 / SD-47 / SD-53 status

| Decision | Status | Explanation |
|---|---|---|
| SD-44 | **PARTIAL** | Safe logging and redaction retained. Structured JSON format, formal redaction policy, retention, and correlation ID remain unspecified/unimplemented. |
| SD-47 | **VERIFIED FOR EXISTING STAGE 2 CONTROLS** | Production log-level configuration and Actuator restriction remain in place and covered by tests. Per-release verification remains required. |
| SD-53 | **NOT IMPLEMENTED** | CSRF remains framework-default. The approved session-bound 256-bit design belongs to the authentication stage. |

### Stage 2 closure

**CLOSED FOR THE APPROVED FOUNDATION SCOPE, WITH DOCUMENTED DEFERRALS.**

The backend foundation, schema, mappings, security shell, error contract,
configuration, logging controls, health checks, automated tests, coverage
reporting, repeatable test provisioning, and CI configuration are in place and
verified against real PostgreSQL. Stage 2 does not include authentication,
business workflows, endpoints, WebSocket authorization, CSRF implementation, or
production deployment. The deferrals are explicit and do not block the next
approved stage.

---

## M. Git and Commit Record

| Item | Value |
|---|---|
| Branch | `stage-2-backend-foundation` |
| Starting HEAD | `c835eae` |
| Test/tooling commit | `407c338` — `test(stage-2): expand automated coverage and verification tooling` |
| Documentation commit | `docs(stage-2): record Stage 2.7 and 2.8 completion report` |
| Report commit | The documentation commit that adds this file |
| Final HEAD | Recorded by `git log -1` after the report commit |
| Working tree | Clean after both commits; no generated artifacts, cluster data, or secrets staged |
| Push | Not pushed |
| Merge into `main` | Not performed |

`V1`–`V14` are not part of either commit.

---

## N. Next Stage Recommendation

Stage 2 is closed for its approved scope. The next stage may proceed only with
the following constraints and carry-forward items:

1. Implement SD-53 completely with authentication: session-backed repository,
   256-bit generator, raw `X-CSRF-TOKEN` resolution, response-header writer,
   post-rotation ordering, audit event, and the Section 17 security tests.
2. Keep `V1`–`V14` immutable. Any schema correction requires a new migration and
   updated persistence tests.
3. Add authentication and business-flow tests as those features are implemented;
   do not rely on the current 70.09% instruction coverage as a quality gate.
4. Push the branch and verify the CI workflow on its first real run; record the
   run URL and result. Treat the workflow as unverified until then.
5. Define and implement SD-44: structured log format, formal redaction policy,
   retention/rotation, and a request correlation ID.
6. Add a separate management port or documented network restriction for Actuator
   before production.
7. Bridge the Render `PORT` / `SERVER_PORT` mismatch and verify TLS and proxy
   headers in a real deployment.
8. Map multipart and remaining data-binding failures to `400` where safe.
9. Keep `/ws` denied until session handshake authentication and STOMP
   authorization exist.
10. Do not merge this branch into `main` or push it without explicit instruction.
