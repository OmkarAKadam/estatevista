# EstateVista V2 — Stage 2.1 & 2.2 Completion Report

| Field | Value |
|---|---|
| Sub-stages | 2.1 — Repository Baseline Reconciliation; 2.2 — Spring Boot Application Architecture |
| Overall status | **COMPLETE — implementation and verification performed; documented exclusions remain** |
| Execution agent | OpenCode |
| Date | 14 November 2026 |
| Feature branch | `stage-2-backend-foundation` |
| Implementation commit | `136cfac5f41e5440bd2c7409d02e5c9273f51d17` |
| Report commit | The commit that adds this file (see `git log -1` on the feature branch) |

---

## A. Execution Summary

Sub-stage 2.1 reconciled the repository without discarding work. The recorded
Stage 1 baseline (HEAD `157992d`) had already been superseded locally and on
`origin/main` by two Stage 1 finalisation commits. The starting tree was clean,
so no baseline commit and no destructive Git operation were required.

Sub-stage 2.2 preserved the existing Spring Boot 4.1.2 / Java 21 shell and added
the missing application-foundation pieces: a `test` profile, a standardized API
error envelope with validation and safe 500 handling, a replacement `/error`
controller, security-filter error envelopes, web-layer tests, and a deliberate
hardening of the WebSocket handshake.

Authentication, CSRF (SD-53), business modules, and business schema are **not**
implemented, by design. No production deployment was performed.

---

## B. Repository Baseline

### Recorded versus actual state

| Item | Stage 1 record | State at Stage 2.1 start |
|---|---|---|
| Branch | `main` | `main` |
| HEAD | `157992d7b0d8b9489d35f83f559cd45618567186` | `9e20ed53a7ac92087670cb5b144762b8faf1c721` |
| Working tree | 42 deletions, 11 untracked entries | clean |
| Staged changes | 0 | 0 |
| Untracked entries | 11 | 0 |
| Tracked files | — | 71 |
| Tracking branch | — | `origin/main` (up to date) |

The two intervening commits are:

| Commit | Subject | Effect |
|---|---|---|
| `5cea00d` | `chore(stage-1): finalize project foundation and documentation` | Added `backend/`, `frontend/`, the eight Stage 1 documents, and `docs/validation/`. Resolved the 11 untracked entries. |
| `9e20ed5` | `chore(stage-1): finalize project foundation and documentation` | Removed the 42 root-level duplicates after relocating them into `frontend/`, `backend/`, and `docs/`. Resolved the 42 tracked deletions. |

### Investigation of the historical inconsistencies

- **42 tracked deletions:** `9e20ed5` shows exactly 42 deleted root-level files
  (`index.html`, `properties.html`, `about.html`, `contact.html`,
  `property-details.html`, `404.html`, `README.md`, `DEPLOYMENT.md`,
  `.gitignore`, `.gitattributes`, `assets/**`, `assets/js/**`,
  `docs/QA.md`, `docs/screenshots/**`). The same content exists under
  `frontend/` and `docs/` after `5cea00d`. The deletion was an intentional
  repository-layout move, not data loss. Restoring the root copies would
  duplicate the frontend and contradict the approved `backend/` + `frontend/` +
  `docs/` layout, so nothing was restored.
- **11 untracked entries:** `backend/`, `frontend/`, the eight Stage 1
  documents, and `docs/validation/` were committed by `5cea00d`. All Stage 1
  work is preserved. No untracked work was discarded.
- **Sensitive files:** the only matching file is `backend/.env.example`, which
  contains placeholders only. There is no `backend/.env`, no private key, no
  credential file, and no tracked `target/` output. `backend/.gitignore`
  correctly ignores `.env`, `target/`, logs, and IDE files.
- **Generated artifacts:** `backend/target/` exists locally but is ignored; it
  was not staged or committed.

### Baseline decision

`9e20ed5` on `main` (identical to `origin/main`) is the authoritative baseline.
No separate baseline commit was needed because no ambiguous or uncommitted work
existed. The Stage 2 branch was created from that verified commit. No
`git reset --hard`, `git clean`, `git restore .`, or `git add .` was used.

---

## C. Architecture Implemented

### Build and runtime

- Spring Boot `4.1.2` via `spring-boot-starter-parent`.
- Java compiler target `21` (`maven.compiler.release=21` shown in the build).
- Maven `3.9.14`; local runtime JDK `23.0.2` (compiles to 21 bytecode).
- Existing `spring-boot-maven-plugin` retained.
- No version upgrades and no dependency changes.

### Package structure

```text
com.estatevista
├── EstateVistaApplication.java
├── config/                 # AppProperties, CORS, security shell, WebSocket
└── common/
    ├── exception/          # ErrorCode, ApiException, GlobalExceptionHandler, ApiErrorController
    ├── health/             # HealthController
    └── response/           # ApiErrorResponse
```

Business modules (`auth`, `user`, `property`, `favorite`, `inquiry`, `chat`,
`notification`, `dashboard`, `audit`, `storage`) follow
`SYSTEM_ARCHITECTURE.md` §5.3 and will be created only when each module has
code. No empty placeholder packages were added. Security configuration remains
in `config/` to preserve the Stage 1 verified implementation; it can be moved to
`security/` when authentication code is added.

### Profiles and configuration

| Profile | File | Behaviour |
|---|---|---|
| shared | `application.yml` | Port, PostgreSQL, JPA, Flyway, Spring Session, Actuator, CORS/WebSocket properties, logging |
| `dev` | `application-dev.yml` | Debug logging, Flyway SLF4J logging |
| `test` | `application-test.yml` | Isolated test database via `TEST_DB_*`, reduced logging, no production defaults |
| `prod` | `application-prod.yml` | Secure cookie, native forwarded headers, no DB fallbacks |

All environment-specific values remain externalized (`DB_URL`,
`DB_USERNAME`, `DB_PASSWORD`, `DB_POOL_SIZE`, `APP_CORS_ALLOWED_ORIGINS`,
`APP_WEBSOCKET_ALLOWED_ORIGINS`, `SESSION_COOKIE_SECURE`,
`FORWARD_HEADERS_STRATEGY`). `backend/.env.example` now also documents the
optional `TEST_DB_*` variables. No credentials are committed.

### Infrastructure verification

- **PostgreSQL:** Hikari pool `EstateVistaPool` connected to PostgreSQL 18.3.
- **JPA/Hibernate:** `ddl-auto: validate`; startup succeeded, so mappings and
  schema are consistent (no application entities yet).
- **Flyway:** `V1__spring_session_schema.sql` applied and recorded.
- **Spring Session JDBC:** sessions persisted in `SPRING_SESSION`; schema
  ownership remains with Flyway (`initialize-schema: never`).
- **Actuator:** only `health` is exposed; `show-details: never`.
- **Logging:** profile-controlled levels (root/application INFO, web/security
  INFO); Boot's console pattern is used. JSON log encoding and the SD-44
  redaction configuration are deferred. No request bodies or credentials are
  logged.

### Error-handling foundation

- `ApiErrorResponse` implements the `API_SPECIFICATION.md` §17 envelope:
  `success`, `error.code`, `error.message`, `error.details`, `timestamp`,
  `path`.
- `ErrorCode` defines transport-level codes only; business codes are deferred.
- `ApiException` is the base for future client-safe business failures.
- `GlobalExceptionHandler` covers:
  - bean validation (`MethodArgumentNotValidException`),
  - method validation (`HandlerMethodValidationException`,
    `ConstraintViolationException`),
  - malformed bodies, missing parameters/headers, type mismatches,
  - method/media-type/upload-size errors,
  - 404 routing failures,
  - `ResponseStatusException`,
  - Spring Security `AccessDeniedException`,
  - an unexpected-exception fallback that returns a generic 500.
- `ApiErrorController` replaces Boot's default `/error` page with the same
  envelope and never returns the container error message.
- Security-filter 401/403/CSRF responses use the same envelope via the security
  handlers.
- No stack trace, exception class, SQL text, bean name, or internal path is
  returned.

### Health endpoints

- `GET /api/v1/health` → `200` with
  `{"status":"UP","service":"estatevista-api","timestamp":"..."}`.
- `GET /actuator/health` → `200` with `{"groups":[...],"status":"UP"}`;
  component details are hidden. Actuator's DataSource health indicator
  participates in the aggregate status where the database is configured.

---

## D. Files Created and Modified

### Created

| File | Purpose |
|---|---|
| `backend/src/main/java/com/estatevista/common/response/ApiErrorResponse.java` | Standard error envelope records and factory |
| `backend/src/main/java/com/estatevista/common/exception/ErrorCode.java` | Transport-level error codes and status mapping |
| `backend/src/main/java/com/estatevista/common/exception/ApiException.java` | Base runtime exception for client-safe API failures |
| `backend/src/main/java/com/estatevista/common/exception/GlobalExceptionHandler.java` | Central controller exception handling |
| `backend/src/main/java/com/estatevista/common/exception/ApiErrorController.java` | Replacement `/error` controller |
| `backend/src/main/resources/application-test.yml` | Test profile with isolated database variables |
| `backend/src/test/java/com/estatevista/common/health/HealthControllerTest.java` | Health endpoint contract test |
| `backend/src/test/java/com/estatevista/common/exception/GlobalExceptionHandlerTest.java` | 500 safety, `ApiException`, and validation-detail tests |
| `backend/src/test/java/com/estatevista/common/exception/ApiErrorControllerTest.java` | Container 404 and safe 500 tests |
| `docs/validation/stage-2.1-2.2-completion-report.md` | This report |

### Modified

| File | Purpose |
|---|---|
| `backend/src/main/java/com/estatevista/config/SecurityConfig.java` | Standard 401/403/CSRF envelopes; `/ws` denied until authenticated |
| `backend/README.md` | Stage 2.2 status, profiles, structure, error contract, tests |
| `backend/.env.example` | Documented optional `TEST_DB_*` variables |
| `docs/DEVELOPMENT_SETUP.md` | Test profile, updated package tree, WebSocket denial, current test status |

No existing business, frontend, or architecture document was deleted.

---

## E. Dependencies

No dependency was added, removed, upgraded, or downgraded. All Stage 1
dependencies were retained:

| Dependency | Retained reason |
|---|---|
| `spring-boot-starter-webmvc` | REST API (Boot 4 replacement for `starter-web`) |
| `spring-boot-starter-security` | Security filter chain, CORS integration, sessions |
| `spring-boot-starter-data-jpa` | Persistence foundation |
| `spring-boot-starter-validation` | Bean Validation baseline |
| `spring-boot-starter-websocket` | STOMP transport configuration |
| `spring-boot-starter-actuator` | Health endpoint |
| `spring-boot-starter-session-jdbc` | Spring Session JDBC auto-configuration |
| `spring-boot-starter-flyway` + `flyway-database-postgresql` | Flyway auto-configuration and PostgreSQL support |
| `postgresql` (runtime) | PostgreSQL driver |
| `spring-boot-starter-test`, `spring-security-test` (test) | JUnit 5, MockMvc, assertion libraries |

Implementation note: Spring Boot 4 / Framework 7 uses **Jackson 3**
(`tools.jackson.databind.ObjectMapper`). `SecurityConfig` uses the
auto-configured `ObjectMapper`; no Jackson dependency was added.

---

## F. Verification Results

### Build and automated tests

Command (from `backend/`):

```powershell
mvn clean verify -B
```

Result: **PASS — BUILD SUCCESS**, 6 tests, 0 failures, 0 errors, 0 skipped.

| Test class | Tests | Result |
|---|---|---|
| `ApiErrorControllerTest` | 2 | PASS |
| `GlobalExceptionHandlerTest` | 3 | PASS |
| `HealthControllerTest` | 1 | PASS |
| **Total** | **6** | **PASS** |

The tests are web-layer tests built with standalone MockMvc; they do not start a
Spring context and do not require a database.

### Runtime verification

Environment: packaged jar, `dev` profile, isolated PostgreSQL 18.3 cluster on
port `55432`, application on port `18080`.

| Check | Command / method | Result |
|---|---|---|
| Application startup | `java -jar target/estatevista-backend-0.1.0-SNAPSHOT.jar` | **PASS** — started in ~21.5 s |
| Database connection | Hikari startup log | **PASS** — `EstateVistaPool` connection established |
| Flyway migration | `flyway_schema_history` | **PASS** — version 1, success `t` |
| Schema objects | `information_schema.tables` | **PASS** — `flyway_schema_history`, `spring_session`, `spring_session_attributes` |
| JPA validation | startup with `ddl-auto: validate` | **PASS** |
| Session persistence | `select count(*) from spring_session` | **PASS** — 2 rows, timeout 1800 s |
| No default user | startup log scan | **PASS** — no generated password |
| `GET /api/v1/health` | HTTP | **PASS** — `200`, expected JSON |
| `GET /actuator/health` | HTTP | **PASS** — `200`, aggregate `UP`, no details |
| Protected unknown route | `GET /api/v1/unknown-protected` | **PASS** — `401` standard envelope |
| Tokenless mutation | `POST /api/v1/unknown-protected` | **PASS (expected)** — `403` standard envelope from framework CSRF defaults |
| CORS allowed preflight | exact origin `http://localhost:5500` | **PASS** — `200`, allow-origin/methods/headers/credentials/expose headers |
| CORS disallowed preflight | `http://evil.example` | **PASS** — `403`, no allow-origin header (framework plain-text body) |
| CORS allowed actual GET | `Origin: http://localhost:5500` | **PASS** — `200` with CORS headers |
| WebSocket unauthenticated handshake | `GET /ws` with upgrade headers | **PASS** — `401` standard envelope, no `101` upgrade |
| Container error envelope | `GET /error` | **PASS** — `500` standard envelope, no internal text |
| `git diff --check` | Git | **PASS** — no whitespace errors |

### Explicitly not performed

| Item | Status | Reason |
|---|---|---|
| Authentication/login/registration tests | NOT PERFORMED | Out of scope for 2.2 |
| SD-53 CSRF implementation tests | NOT PERFORMED | Approved design not yet implemented |
| Full-context Spring integration test | NOT PERFORMED | No database-backed test fixture or test container in scope |
| Browser end-to-end setup check | NOT PERFORMED | No browser automation |
| Production deployment / TLS / hosted database | NOT PERFORMED | Deployment stage |
| WebSocket STOMP message authorization tests | NOT PERFORMED | Messaging stage |
| Load/performance testing | NOT PERFORMED | Out of scope |

No result above is inferred or invented; each PASS is backed by the command or
HTTP evidence described.

---

## G. Security Review

### Implemented in this sub-stage

- **Deny by default:** only `/api/v1/health`, `/actuator/health`, and `/error`
  are public; `anyRequest().denyAll()`.
- **WebSocket hardening:** `/ws` and `/ws/**` are no longer publicly permitted.
  An unauthenticated handshake now returns `401`. This intentionally changes
  the Stage 1 temporary `permitAll` state, because Stage 2.2 explicitly forbids
  exposing unauthenticated WebSocket functionality.
- **Session architecture preserved:** server-side `HttpSession`, Spring Session
  JDBC, `EV_SESSION`, `HttpOnly`, `SameSite=Lax`, configurable `Secure`,
  `IF_REQUIRED`. No JWT or stateless authentication was introduced.
- **Exact-origin credentialed CORS:** explicit origins, wildcard rejected at
  startup, `X-CSRF-TOKEN` exposed, credentials enabled.
- **Actuator minimisation:** only `health` exposed; details hidden.
- **Error safety:** generic 500 messages, no stack traces/SQL/class names,
  validation details limited to field names and validation messages.
- **Secrets:** no credential committed; `.env` ignored; production profile has
  no database fallbacks.
- **Security headers:** Spring Security defaults remain active
  (`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, no-store cache
  headers).
- **CSRF defaults remain enabled:** tokenless mutating requests return `403`.

### Not implemented / remains incomplete

- **SD-53 CSRF design:** 256-bit session-bound token, raw-header-compatible
  request handler, response-header writer, rotation ordering, audit event, and
  Section 17 tests. Calling this "complete" would be false.
- **Authentication and authorization:** no login, logout, registration,
  password handling, roles, or ownership rules.
- **WebSocket authentication/authorization:** handshake is denied, but the
  session handshake interceptor and STOMP CONNECT/SUBSCRIBE/SEND rules are
  pending.
- **Rate limiting, audit logging, correlation IDs:** specified for later
  security/observability work.
- **Structured JSON logging and SD-44 redaction policy:** profile-level console
  logging is configured, but a JSON encoder and formal redaction appender are
  not. This belongs with the observability/logging hardening work.
- **Security-header work beyond framework defaults:** CSP/Referrer-Policy and
  production header verification are deployment-stage items.

### Identified risks and observations

1. Framework-default CSRF is not the approved SD-53 mechanism; the frontend
   currently holds no token. Authentication must not ship before SD-53.
2. A `401` response creates a session cookie because the request cache/session
   policy is `IF_REQUIRED`. This is harmless but should be reviewed when the
   authentication flow is added.
3. Disallowed CORS requests receive Spring's plain-text `Invalid CORS request`
   body rather than the JSON envelope. This is emitted by `CorsFilter` before
   MVC and reveals no internals.
4. `/error` is public by necessity and returns a generic `500` when called
   directly without error attributes; it exposes no internal data.
5. `application-test.yml` defaults to an empty test password; tests that need a
   database must supply `TEST_DB_PASSWORD` and an isolated database. The current
   tests do not read it.
6. Actuator health details are hidden, so the database component's individual
   status is not externally visible; the aggregate status still reflects it.
7. No correlation identifier is included yet. The specification calls for one
   "where appropriate"; this is deferred rather than invented.

---

## H. Git Details

| Item | Value |
|---|---|
| Feature branch | `stage-2-backend-foundation` |
| Baseline commit | `9e20ed53a7ac92087670cb5b144762b8faf1c721` (`main`, `origin/main`) |
| Implementation commit | `136cfac5f41e5440bd2c7409d02e5c9273f51d17` |
| Implementation message | `feat(stage-2): establish repository baseline and Spring Boot architecture` |
| Implementation scope | 13 files changed, 843 insertions, 61 deletions |
| Report commit | The commit that adds this report (see `git log -1`) |
| Working tree after implementation commit | clean |
| Merge status | Not merged into `main` |
| Push status | Not pushed |
| Destructive Git operations | None |

---

## I. Known Issues

| ID | Issue | Impact | Owner |
|---|---|---|---|
| K-01 | SD-53 CSRF not implemented; only framework defaults | Blocks authentication exposure | Authentication stage |
| K-02 | No authentication, roles, or ownership authorization | Business endpoints cannot be exposed | Authentication/authorization stage |
| K-03 | WebSocket handshake intentionally denied and STOMP authorization absent | Real-time chat unavailable | Messaging stage |
| K-04 | Tests are web-layer only; no full-context or DB integration test | Wiring regressions in a full context are not covered | Stage 2.3+ / test infrastructure |
| K-05 | CORS rejection body is plain text | Minor contract inconsistency on rejected origins | Later hardening (optional) |
| K-06 | No correlation ID in errors/logs | Reduced request tracing | Observability hardening |
| K-07 | Actuator DB component details hidden | Component-level diagnosis requires logs | Operations |
| K-08 | `test` profile DB credentials have an empty password default | A context test fails fast without `TEST_DB_PASSWORD` | Test infrastructure |
| K-09 | No structured JSON log encoder or formal SD-44 redaction appender | Logs are human-readable and leveled, not machine-structured | Observability hardening |

---

## J. Acceptance Assessment

### Sub-stage 2.1 — Repository Baseline Reconciliation

| Criterion | Result | Evidence |
|---|---|---|
| Current Git state documented | **MET** | Section B |
| Historical inconsistencies investigated | **MET** | 42 deletions and 11 untracked entries traced to `5cea00d`/`9e20ed5` |
| All existing work preserved | **MET** | No reset/clean/restore; frontend, backend, docs retained |
| Sensitive files identified | **MET** | Only `.env.example` placeholders; no real secrets |
| Baseline decision documented | **MET** | `9e20ed5` authoritative; no baseline commit needed |
| Stage 2 branch from approved baseline | **MET** | `stage-2-backend-foundation` from `9e20ed5` |
| No unrelated changes introduced | **MET** | Diff limited to backend foundation and accurate doc updates |
| Working tree state documented | **MET** | Clean before and after implementation commit |

### Sub-stage 2.2 — Spring Boot Application Architecture

| Criterion | Result | Evidence |
|---|---|---|
| Existing backend inspected and preserved | **MET** | Existing shell retained; no second project generated |
| Layered structure established | **MET** | `config`, `common/exception`, `common/response`, `common/health`, `src/test`; business modules deferred |
| Spring Boot 4.1.2 / Java 21 verified | **MET** | POM and build output |
| Maven build configured | **MET** | `mvn clean verify` succeeds |
| Application startup/profile configuration | **MET** | Shared, `dev`, `test`, `prod` |
| PostgreSQL configuration | **MET** | Hikari connection verified |
| Spring Data JPA / Hibernate | **MET** | `ddl-auto: validate`, startup succeeds |
| Flyway | **MET** | V1 applied and recorded |
| Spring Session JDBC | **MET** | Session rows persisted |
| Spring Security foundation | **MET** | Deny-by-default, session-based, no JWT |
| Spring Boot Actuator | **MET** | Health only, details hidden |
| Environment configuration externalized | **MET** | `DB_*`, `APP_*`, `SESSION_*`, `TEST_DB_*` documented |
| No hardcoded production credentials | **MET** | Placeholder `.env.example` only |
| Basic infrastructure implemented/verified | **MET** | Startup, profiles, DB, Flyway, JPA, sessions, logging, health |
| Security foundation preserved | **MET** | Deny-by-default, CSRF defaults, exact-origin credentialed CORS |
| Sensitive actuator endpoints protected | **MET** | Only health exposed; no env/config endpoints |
| No public administrative endpoints | **MET** | `denyAll()` |
| No unauthenticated WebSocket exposure | **MET** | `/ws` returns `401`; hardening recorded |
| Global exception-handling foundation | **MET** | Envelope, validation, 404/405/415, safe 500, `/error` replacement |
| Health endpoints verified | **MET** | `/api/v1/health` and `/actuator/health` return `200` |
| No internal details exposed | **MET** | Evidence shows generic messages and no stack traces |
| No premature business error codes | **MET** | Only transport-level codes |
| Automated verification executed | **MET** | 6 tests PASS; runtime checks PASS |
| Full authentication/CSRF claimed | **NOT CLAIMED** | Explicitly excluded and recorded |

**Verdict:** Sub-stage 2.1 is complete. Sub-stage 2.2 is complete for its defined
foundation scope, with authentication, SD-53 CSRF, business modules, and
database schema correctly deferred to their owning sub-stages.

---

## K. Recommended Next Action

Proceed to **Stage 2.3 — PostgreSQL Business Schema & Flyway Migrations**:

1. Derive migrations `V2` onward from `docs/DATABASE_DESIGN.md`; keep
   `V1__spring_session_schema.sql` unchanged.
2. Introduce entities and repositories only as their migrations land; keep
   `ddl-auto: validate`.
3. Add a database-backed test strategy (isolated PostgreSQL or Testcontainers)
   using the `test` profile and `TEST_DB_*` variables.
4. Preserve `open-in-view: false`, UTC JDBC time zone, UUID identifiers,
   database constraints, and Flyway ownership of the schema.
5. Do not begin authentication or business endpoints in Stage 2.3; keep SD-53
   and session-handshake authorization with the authentication/messaging stages.
