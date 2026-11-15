# EstateVista V2 — Stage 2.5 & 2.6 Completion Report

| Field | Value |
|---|---|
| Sub-stages | 2.5 — Validation Infrastructure & Exception Handling Review; 2.6 — Configuration, Logging & Health Check Infrastructure |
| Overall status | **COMPLETE — controls accepted; SD-44 remains partially deferred and SD-53 remains out of scope** |
| Execution agent | OpenCode |
| Execution date | 15 November 2026 |
| Feature branch | `stage-2-backend-foundation` |
| Starting HEAD | `3fb3014` |
| Stage 2.5 implementation commit | `b6887d1` |
| Stage 2.6 implementation commit | `096ac58` |
| Report commit | The commit that adds this file (see `git log -1` on the feature branch) |
| Push status | Not pushed |
| Merge status | Not merged into `main` |

---

## A. Executive Summary

Stage 2.5 reviewed the existing exception-handling and validation contracts,
fixed one genuine logging defect, and added contract tests for the complete
error envelope and the security filter chain. Stage 2.6 reviewed configuration,
logging, and health-check behavior, introduced shared exact-origin validation,
made production origin configuration fail fast, and added explicit production
suppression of SQL, bind-parameter, and result-extraction logging.

Delivered work:

- Malformed-request logging no longer writes the framework parser message.
  Parser messages can embed an excerpt of the rejected request body; the log now
  records only the HTTP method, path, and exception simple name.
- 13 `GlobalExceptionHandlerTest` cases covering validation, 400/401/403/404/
  405/413/415/500, safe fallback behavior, and log redaction.
- 7 `SecurityFilterChainTest` cases covering the `401` and `403` envelopes,
  CSRF rejection, the public health endpoint, `/ws` denial, credential CORS on
  an allowed origin, and rejection of a disallowed origin.
- 6 `AllowedOriginsTest` cases covering exact HTTP/HTTPS origins, empty and
  null lists, wildcards, unresolved `${...}` placeholders, paths, queries,
  fragments, user info, blank entries, and non-HTTP schemes.
- Shared `AllowedOrigins` validation wired into both `CorsConfig` and
  `WebSocketConfig`.
- `application-prod.yml` now requires `APP_CORS_ALLOWED_ORIGINS` and
  `APP_WEBSOCKET_ALLOWED_ORIGINS` with no localhost fallback, and forces
  `org.hibernate.SQL`, `org.hibernate.orm.jdbc.bind`, and
  `org.hibernate.orm.jdbc.extract` to `WARN`.
- A test-scoped `spring-boot-webmvc-test` dependency, required because Spring
  Boot 4 modularized the `@WebMvcTest` slice.

Verification was performed against real PostgreSQL 18.3. `mvn -B clean verify`
passed with 40 tests, 0 failures, 0 errors, and 0 skipped. Runtime checks covered
health endpoints with the database up and down, Actuator exposure, `/ws` denial,
CORS accept/reject behavior, production fail-fast configuration, production
logging, and the secure session cookie.

**Stage 2.5 verdict: PASS — accepted.**
**Stage 2.6 verdict: PASS — accepted with documented deferrals.**
**SD-44 verdict: PARTIAL — safe logging baseline improved; structured format,
formal redaction policy, and retention remain unspecified.**
**SD-47 verdict: controls verified for this stage; per-release verification
remains a recurring obligation.**
**SD-53 verdict: NOT IMPLEMENTED — CSRF remains framework-default only.**

This report does not claim production readiness. Authentication, authorization
workflows, SD-53 CSRF wiring, business services, REST endpoints, WebSocket
message handling, and deployment remain unimplemented.

---

## B. Initial Repository State

| Item | State at start |
|---|---|
| Branch | `stage-2-backend-foundation` |
| HEAD | `3fb3014` — `docs(stage-2): record Stage 2.3 and 2.4 completion report` |
| Working tree | Clean (no staged, modified, or untracked files) |
| Expected commit history | Five Stage 1/Stage 2 commits present |
| Framework | Java 21 target, Spring Boot 4.1.2, Maven, PostgreSQL, Spring Data JPA/Hibernate, Flyway |
| Existing tests | 17 total: 6 web-layer and 11 opt-in PostgreSQL integration tests |
| Migrations | `V1`–`V14` present; `V1` is framework-owned Spring Session schema |
| Discrepancies | None found |

`V1__spring_session_schema.sql` and `V2`–`V14` were not modified before, during,
or after either sub-stage. `git diff` on the migration directory is empty.

---

## C. Documentation and Design Traceability

### Documents reviewed

- `docs/PRD.md`
- `docs/FRD.md`
- `docs/SYSTEM_ARCHITECTURE.md`
- `docs/DATABASE_DESIGN.md`
- `docs/API_SPECIFICATION.md` §17 (standard error envelope)
- `docs/SECURITY_ARCHITECTURE.md` (SD-44, SD-47, SD-53; §11.13, §11.17,
  §14.9, Section 17 tests)
- `docs/UI_UX_SPECIFICATION.md`
- `docs/DEVELOPMENT_SETUP.md`
- `docs/validation/stage-2.3-2.4-completion-report.md`
- Existing backend source, tests, `pom.xml`, and all profile configuration

### Requirements extracted

| Area | Requirement | Source |
|---|---|---|
| Error envelope | Every REST error uses `success`, `error.code`, `error.message`, `error.details`, `timestamp`, `path` | API §17 |
| Error safety | Responses never expose stack traces, exception class names, SQL, bean names, or internal paths | API §17; Security §11.13 |
| Expected status mapping | Validation `400`, auth `401`, authorization `403`, not found `404`, method `405`, payload `413`, media type `415`, unexpected `500` | API §17; Security §11.15 |
| Logging privacy | Never log passwords, tokens, cookies, CSRF values, request bodies for auth/profile/inquiry/chat, or private message content | Security §11.13, §11.17, §14.9; System Architecture §11.2 |
| Malformed input | Parser messages must not leak a body excerpt into logs | Security §11.13, §11.17 |
| Correlation | Generate or accept a request/correlation ID and log it with server-side detail | Security §11.17 |
| SD-44 | Redact logs; never log secrets, tokens, passwords, or private content; define retention | Security §18.4 |
| SD-47 | Disable debug/stack traces/SQL logging in production; restrict Actuator/Swagger | Security §18.4 |
| SD-53 | Session-bound synchronizer CSRF, 256-bit token, `X-CSRF-TOKEN`, rotation, JSON `403` | Security §8.5.8, §18.4 |
| CORS | Exact origins, credentials enabled, wildcard rejected | Development Setup §8; Security §8.5 |
| Health | Application health and Actuator health distinct; Actuator restricted | API §17; Development Setup §6 |

### Traceability of implemented controls

| Control | Implementation | Evidence |
|---|---|---|
| Standard error envelope | `GlobalExceptionHandler`, `ApiErrorController`, `ApiErrorResponse` | `GlobalExceptionHandlerTest`, `ApiErrorControllerTest`, runtime probes |
| Malformed-body log redaction | `GlobalExceptionHandler.handleMalformedRequest` logs exception simple name only | `malformedRequestLogDoesNotContainRejectedBody` |
| Deny-by-default contract | `SecurityConfig` | `SecurityFilterChainTest` |
| Exact-origin allowlist | `AllowedOrigins` used by `CorsConfig` and `WebSocketConfig` | `AllowedOriginsTest`, runtime CORS probes |
| Production fail-fast | `application-prod.yml` placeholders without fallback | Production startup exit `1` |
| Production SQL log suppression | `application-prod.yml` logger levels | Production log inspection |
| Health separation | `HealthController` vs Actuator health | Database-up/down runtime probes |

---

## D. Stage 2.5 Implementation

### Audit result

The production exception handlers were already correct and were retained. The
handler covered:

- `ApiException` with declared status and details.
- Bean validation (`MethodArgumentNotValidException`) and handler-method
  validation (`HandlerMethodValidationException`).
- `ConstraintViolationException`.
- Malformed JSON, missing parameter/header, and type mismatch as `400`.
- Unsupported method as `405`, unsupported media type as `415`, and upload size
  as `413`.
- Missing resources and unmapped handlers as `404`.
- `ResponseStatusException` status mapping.
- Access denied as `403`.
- Unexpected exceptions as a safe `500`.

### Defect fixed

`GlobalExceptionHandler.handleMalformedRequest` logged
`exception.getMessage()`. Framework parse messages can include a fragment of the
rejected request body, which violates the logging rules for private content and
request bodies. The log statement now records only:

- HTTP method,
- request URI,
- exception simple class name.

No request body, header, token, or raw parser message is written. This was the
only production behavior change in Stage 2.5; no response shape or status code
changed.

### Tests added

`GlobalExceptionHandlerTest` (13 cases):

1. Unexpected exception returns a safe internal-error envelope.
2. `ApiException` maps to its declared status and code.
3. Bean validation returns field details.
4. Malformed JSON returns `400` without echoing the body.
5. Malformed-request logging does not contain the rejected body marker.
6. Unsupported content type returns `415`.
7. Unsupported method returns `405`.
8. Missing request parameter returns `400`.
9. Type mismatch returns `400`.
10. Parameter constraint violation returns field details.
11. Payload too large returns `413`.
12. Missing resource returns `404`.
13. Access denied returns `403`.

`SecurityFilterChainTest` (7 cases):

1. Unauthenticated request returns the `401` envelope.
2. Authenticated request denied by policy returns the `403` envelope.
3. Mutating request without a CSRF token returns `403`.
4. `/api/v1/health` remains public.
5. `/ws` remains denied with `401`.
6. Allowed-origin preflight returns credentialed CORS headers and exposes
   `X-CSRF-TOKEN`.
7. Disallowed-origin preflight is rejected without CORS headers.

The test slice uses `.with(user("probe"))` because `@WithMockUser` did not bind
to the custom security filter chain in the Boot 4 test context.

### Scope discipline

No entity, migration, validation rule, role, endpoint, service, controller, or
authentication flow was added. The tests exercise existing behavior plus the one
logging fix.

---

## E. Stage 2.5 Verification

| Verification | Status | Evidence |
|---|---|---|
| Error envelope shape | PASS | `success=false`, `error.code`, `error.message`, `error.details`, `timestamp`, and `path` asserted by tests |
| Validation `400` | PASS | Bean validation, malformed JSON, missing parameter, type mismatch |
| Authentication `401` | PASS | Unauthenticated request and `/ws` |
| Authorization `403` | PASS | Authenticated denial and tokenless CSRF `POST` |
| Not found `404` | PASS | `NoResourceFoundException` |
| Method `405` | PASS | Unsupported method test |
| Payload `413` | PASS | Upload-size test |
| Media type `415` | PASS | Unsupported content type test |
| Unexpected `500` | PASS | Generic message and no internal detail |
| Malformed-body redaction | PASS | Synthetic secret absent from all captured log events |
| No request-body echo | PASS | Response contains the generic message only |
| Deny-by-default preserved | PASS | `/api/v1/probe/read` returns `401` |
| `/ws` still denied | PASS | `/ws` returns `401` envelope |
| CORS allowed origin | PASS | Preflight returns matching origin, credentials, exposed header |
| CORS disallowed origin | PASS | Preflight `403`, no `Access-Control-Allow-Origin` |
| Full test suite | PASS | 40 tests, 0 failures, 0 errors, 0 skipped |

No unavailable check is reported as PASS.

---

## F. Stage 2.6 Implementation

### Exact-origin validation

`AllowedOrigins.requireExplicit(List<String>, String)` now centralizes origin
validation for CORS and WebSocket configuration. It:

- rejects `null` and empty lists;
- rejects blank entries;
- rejects `*`;
- rejects unresolved `${...}` placeholders;
- requires an absolute URI;
- accepts only `http` and `https` schemes;
- requires a host;
- rejects a non-empty path, including a trailing `/`;
- rejects a query, fragment, or user info.

`CorsConfig` and `WebSocketConfig` both call this helper. The previous duplicated
checks were removed. Startup now fails for a list that could never match a
browser origin, instead of silently accepting it.

### Production configuration

`application-prod.yml` now contains:

```yaml
app:
  cors:
    allowed-origins: ${APP_CORS_ALLOWED_ORIGINS}
  websocket:
    allowed-origins: ${APP_WEBSOCKET_ALLOWED_ORIGINS}
```

There is no localhost fallback. A missing property resolves to the literal
placeholder and is rejected by `AllowedOrigins`, so a misconfigured production
deployment cannot start with development origins.

### Production logging

`application-prod.yml` pins `root`, `com.estatevista`,
`org.springframework.web`, `org.springframework.security`, and `org.flywaydb` to
`INFO`, and forces:

```yaml
org.hibernate.SQL: WARN
org.hibernate.orm.jdbc.bind: WARN
org.hibernate.orm.jdbc.extract: WARN
```

This keeps SQL text, bind values, and extracted row values out of production
logs even if a parent logger is raised later. `spring.jpa.show-sql` remains
`false`.

### Health and Actuator

No production health code changed. The existing behavior was verified:

- `/api/v1/health` is a liveness endpoint returning `status`, `service`, and
  `timestamp` and stays `200 UP` when PostgreSQL is unavailable.
- `/actuator/health` includes the database indicator and returns `200 UP` or
  `503 DOWN`.
- Only `health` is exposed; details are hidden.
- All other Actuator paths fall under the deny-by-default chain and return the
  standard `401` envelope.

### Test dependency

Spring Boot 4 modularized `@WebMvcTest` into
`org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`. The test-scoped
`spring-boot-webmvc-test` dependency was added so the security filter chain can
be tested without a database-backed context. No production dependency changed.

### What was not added

- No Logback XML, JSON encoder, correlation-ID filter, or MDC integration.
- No retention or rotation policy.
- No separate Actuator management port.
- No CSRF token repository, generator, or response-header writer.
- No authentication, service, controller, endpoint, entity, or migration.

These omissions are deliberate: the approved documents do not specify the
structured format, formal redaction policy, retention duration, or CSRF wiring
for this stage, and SD-53 is owned by the authentication stage.

---

## G. Stage 2.6 Verification

Environment: packaged jar, Windows, PowerShell, throwaway PostgreSQL 18.3
cluster at `localhost:55433` with `estatevista` and `estatevista_test` databases
and trust authentication. The installed PostgreSQL service on port `5432` was
not touched.

| Verification | Status | Evidence |
|---|---|---|
| Full build with test database | PASS | `mvn -B clean verify`, `BUILD SUCCESS`, exit `0` |
| Test totals | PASS | 40 tests, 0 failures, 0 errors, 0 skipped |
| Web-only run without database | PASS | 29 executed, 11 skipped, no failures |
| Flyway validation | PASS | `Successfully validated 14 migrations`; `Current version of schema "public": 14` |
| `dev` application health | PASS | `200 {"status":"UP","service":"estatevista-api",...}` |
| `dev` Actuator health | PASS | `200 {"status":"UP"}` |
| Non-exposed Actuator paths | PASS | `/actuator`, `/actuator/env`, `/actuator/beans`, `/actuator/configprops`, `/actuator/heapdump` all `401` envelopes |
| `/ws` denial | PASS | `401` envelope |
| Unknown API path | PASS | `401` envelope |
| CORS allowed origin | PASS | `200`; matching `Access-Control-Allow-Origin`; `Access-Control-Allow-Credentials: true`; `Access-Control-Expose-Headers: X-CSRF-TOKEN` |
| CORS disallowed origin | PASS | `403`; body is Spring's plain-text `Invalid CORS request` (known limitation) |
| Health with database stopped | PASS | Actuator `503 {"status":"DOWN"}`; application health `200 UP` |
| Production missing origins | PASS | Exit code `1`; `app.cors.allowed-origins contains an invalid origin: ${APP_CORS_ALLOWED_ORIGINS}` |
| Production configured startup | PASS | Started successfully; profile `prod` active |
| Production debug logging | PASS | No `DEBUG` lines in the production log |
| Production secret leakage | PASS | Synthetic DB password absent from the production log |
| Production session cookie | PASS | `EV_SESSION=...; Path=/; Secure; HttpOnly; SameSite=Lax` |
| Production CORS | PASS | `200` with credentialed CORS headers for the configured origin |
| `V1`–`V14` unchanged | PASS | No migration diff |

### Recorded environment failure

An early production fail-fast attempt exited `1` because the throwaway
PostgreSQL cluster had stopped between commands, not because of the origin
validation. The cluster was restarted, the same command was re-run, and the
origin validation failure was then observed with the expected message. No code
change was made to work around the environment failure.

The same cluster stopped again during a later `mvn -B clean verify`, producing
11 `BusinessSchemaIntegrationTest` errors (`Connection to localhost:55433
refused`). The cluster was restarted, the identical command was re-run, and all
40 tests passed. This was an environment failure, not a code failure; no code or
configuration change was made.

### Not performed

| Check | Status | Reason |
|---|---|---|
| Structured JSON log format | NOT PERFORMED | Format is not specified (SD-44 deferred) |
| Formal redaction policy verification | NOT PERFORMED | Policy is not specified (SD-44 deferred) |
| Log retention/rotation | NOT PERFORMED | Retention duration is not specified (SD-44 deferred) |
| Correlation-ID behavior | NOT PERFORMED | Not implemented; design element remains open |
| Browser end-to-end session flow | NOT PERFORMED | No browser automation available and no auth flow exists |
| Production deployment/TLS/proxy | NOT PERFORMED | Out of scope; no production credentials or hostnames |

---

## H. Full Build and Test Results

Command executed from `backend/`:

```powershell
$env:TEST_DB_URL = "jdbc:postgresql://localhost:55433/estatevista_test"
$env:TEST_DB_USERNAME = "postgres"
$env:TEST_DB_PASSWORD = ""
mvn -B clean verify
```

| Field | Result |
|---|---|
| Build result | **BUILD SUCCESS** |
| Maven exit code | `0` |
| Total tests | 40 |
| Failures | 0 |
| Errors | 0 |
| Skipped | 0 |
| Web-layer tests | 29 |
| PostgreSQL integration tests | 11 |
| Build time | 1 min 11 s (final run) |
| Java compilation | `release 21`; runtime Java 23.0.2 |
| Database | PostgreSQL 18.3, isolated throwaway cluster on port 55433 |
| Flyway | 14 migrations validated; schema at version 14 |

### Test breakdown

| Test class | Tests |
|---|---|
| `ApiErrorControllerTest` | 2 |
| `GlobalExceptionHandlerTest` | 13 |
| `HealthControllerTest` | 1 |
| `AllowedOriginsTest` | 6 |
| `SecurityFilterChainTest` | 7 |
| `BusinessSchemaIntegrationTest` | 11 |

### Web-only run

Running `mvn clean verify` without `TEST_DB_URL` executed the 29 web-layer tests
and skipped the 11 opt-in persistence tests. That mode is not represented as a
full persistence verification.

### Warnings

- Mockito reported that it is self-attaching to enable the inline mock maker and
  that this will change in a future JDK. Tests passed.
- Hibernate logged the expected constraint violations produced by negative
  persistence tests. Tests passed.
- Git reported only LF→CRLF normalization notices.
- No compiler warnings were observed.

---

## I. Security and Scope Review

| Requirement | Status | Evidence |
|---|---|---|
| Java 21 / Spring Boot 4.1.2 / Maven / PostgreSQL / JPA / Flyway preserved | Confirmed | No version or build-stack change beyond the test-scoped dependency |
| `/api/v1` prefix preserved | Confirmed | No endpoint path changed |
| Spring Session JDBC preserved | Confirmed | No session configuration change; `V1` untouched |
| Session authentication model preserved | Confirmed | No JWT or token authentication added |
| Deny-by-default preserved | Confirmed | Security chain unchanged; tests assert `401`/`403` |
| `/ws` remains denied | Confirmed | Runtime and test `401` |
| Exact-origin credentialed CORS preserved | Confirmed | `AllowedOrigins`, `CorsConfig`, CORS tests |
| Session-bound CSRF design preserved | Confirmed | No CSRF repository or cookie introduced |
| SD-53 not claimed complete | Confirmed | Framework-default CSRF only |
| No authentication flow introduced | Confirmed | No login, registration, activation, or reset code |
| No business service/controller/endpoint introduced | Confirmed | No production endpoint added |
| No property CRUD, favorites, inquiries, chat, uploads, notifications, dashboards | Confirmed | No such code added |
| No entity, role, validation rule, or migration invented | Confirmed | No persistence or migration change |
| `V1`–`V14` unchanged | Confirmed | No diff |
| `ddl-auto=validate` preserved | Confirmed | No profile change to schema generation |
| No H2 substitute | Confirmed | Tests ran against real PostgreSQL 18.3 |
| No secrets committed | Confirmed | Only `${...}` placeholders; synthetic runtime secret used for the log check |
| Production fail-fast | Confirmed | Missing origins exit `1` |
| Production SQL/bind logging suppressed | Confirmed | `WARN` logger configuration and runtime log inspection |
| Actuator restricted | Confirmed | Only `health`; other paths `401` |
| No push or merge | Confirmed | Branch remains local |
| V1 frontend files untouched | Confirmed | No frontend change |

---

## J. Files Changed

### Created — Stage 2.5

- `backend/src/test/java/com/estatevista/config/SecurityFilterChainTest.java`

### Created — Stage 2.6

- `backend/src/main/java/com/estatevista/config/AllowedOrigins.java`
- `backend/src/test/java/com/estatevista/config/AllowedOriginsTest.java`

### Modified — Stage 2.5

- `backend/pom.xml` — test-scoped `spring-boot-webmvc-test`.
- `backend/src/main/java/com/estatevista/common/exception/GlobalExceptionHandler.java`
  — malformed-request logging redaction.
- `backend/src/test/java/com/estatevista/common/exception/GlobalExceptionHandlerTest.java`
  — expanded to 13 cases.

### Modified — Stage 2.6

- `backend/src/main/java/com/estatevista/config/CorsConfig.java` — shared origin
  validation.
- `backend/src/main/java/com/estatevista/config/WebSocketConfig.java` — shared
  origin validation.
- `backend/src/main/resources/application-prod.yml` — required origins and SQL
  logging suppression.

### Modified — documentation

- `backend/README.md` — Stage 2.6 status, profiles, configuration/logging/health
  notes, test totals.
- `docs/DEVELOPMENT_SETUP.md` — environment variables, origin validation,
  production fail-fast behavior, health/Actuator behavior, logging hygiene,
  Stage 2.5/2.6 evidence, known limitations, troubleshooting.
- `docs/validation/stage-2.5-2.6-completion-report.md` — this report.

### Deleted

None.

### Migrations

None. No migration file was created, modified, renamed, or removed.

---

## K. Known Issues and Deferred Work

| ID | Description | Impact | Severity | Resolution stage | Blocks acceptance? |
|---|---|---|---|---|---|
| K-01 | SD-44 structured JSON log format is not specified or implemented | Logs remain human-readable text; aggregation/parsing is limited | Low | Logging policy stage | No — explicitly deferred |
| K-02 | SD-44 formal redaction policy and retention/rotation are not specified | Operators must rely on current safe practices; no defined retention window | Medium | Logging/operations stage | No — explicitly deferred |
| K-03 | No request correlation ID is generated or logged | Cross-request tracing relies on timestamps and paths | Low | Observability stage | No |
| K-04 | SD-53 CSRF implementation is absent | Only framework-default CSRF behavior exists; not the approved 256-bit session-bound design | High (before auth) | Authentication stage | No — out of this stage's scope |
| K-05 | Disallowed-origin CORS responses use Spring's plain-text `Invalid CORS request` | Origin decisions are correct, but the body is not the JSON envelope | Low | Error-contract refinement | No |
| K-06 | Actuator shares the application port | Management traffic is not network-isolated | Low | Deployment/operations stage | No |
| K-07 | Multipart and some data-binding failures can fall through to `500` | Responses stay safe but are not semantically precise | Low | Exception-handler refinement | No |
| K-08 | Render supplies `PORT`, while the application reads `SERVER_PORT` | A deployment that sets only `PORT` may bind an unexpected port unless bridged | Medium | Deployment stage | No — no deployment in scope |
| K-09 | Persistence tests require an externally supplied `TEST_DB_URL` | A default build does not exercise PostgreSQL mappings | Medium | CI/test infrastructure stage | No |
| K-10 | No Testcontainers or automated database provisioning | Integration tests need manual database setup | Low | Test infrastructure stage | No |
| K-11 | No CI pipeline runs the full suite | Regressions depend on local runs | Medium | CI stage | No |
| K-12 | No browser automation | Session/CORS behavior is verified at the HTTP layer only | Low | E2E stage | No |
| K-13 | Production deployment, TLS, and proxy behavior are not verified | No production-readiness claim is possible | Medium | Deployment stage | No |
| K-14 | Inquiry/chat/notification status and type columns remain unconstrained `VARCHAR` | Unknown values cannot be rejected until lifecycles are approved | Low | Workflow/chat stages | No |
| K-15 | Audit append-only is an application rule, not database-enforced | A privileged path could update/delete audit rows | Medium | Audit implementation stage | No |
| K-16 | No services, controllers, or endpoints for the persisted entities | Persistence is not user-reachable | Expected | Stages 3–8 | No |
| K-17 | Malformed-request log redaction is tested for the parser path; other logging paths rely on code review | A future handler could add unsafe logging without a test failing | Low | Ongoing test expansion | No |

No failed test or incomplete acceptance criterion is hidden in this section.

---

## L. Acceptance Assessment

### Stage 2.5 acceptance gate

| Criterion | Verdict | Explanation |
|---|---|---|
| Existing exception handlers reviewed against the approved envelope | PASS | All handlers return `ApiErrorResponse` |
| Validation errors produce field details | PASS | Bean validation, handler-method validation, constraint violations |
| Expected client errors map to the approved status codes | PASS | `400`, `401`, `403`, `404`, `405`, `413`, `415` |
| Unexpected errors return a safe generic `500` | PASS | No internal detail |
| Parsed request bodies are not echoed or logged | PASS | Body-echo test and log-redaction test |
| Security filter failures return the envelope | PASS | `SecurityFilterChainTest` |
| `/ws` remains denied | PASS | Test and runtime `401` |
| CORS behavior is covered by contract tests | PASS | Allowed and disallowed origin cases |
| No unrelated feature or endpoint introduced | PASS | Scope review §I |
| Evidence documented | PASS | §D, §E, §H |

**Stage 2.5 verdict: PASS — ACCEPTED.**

### Stage 2.6 acceptance gate

| Criterion | Verdict | Explanation |
|---|---|---|
| Origins are exact and shared by CORS and WebSocket | PASS | `AllowedOrigins` used by both |
| Wildcards, blanks, placeholders, paths, queries, fragments, user info, and non-HTTP schemes fail fast | PASS | 6 validator tests and production runtime check |
| Production requires explicit origin configuration | PASS | No fallback; missing value exits `1` |
| Production does not log SQL, bind values, or extracted values | PASS | `WARN` logger configuration and runtime log inspection |
| Production has no debug logging enabled | PASS | Runtime inspection |
| Production secrets do not appear in logs | PASS | Synthetic password check |
| Application health and Actuator health remain distinct | PASS | Database-up and database-down probes |
| Actuator exposes only health | PASS | Other paths `401` |
| Secure production session cookie retained | PASS | `Secure; HttpOnly; SameSite=Lax` |
| No unrelated feature or endpoint introduced | PASS | Scope review §I |
| Evidence documented | PASS | §F, §G, §H |

**Stage 2.6 verdict: PASS — ACCEPTED WITH DOCUMENTED DEFERRALS.**

### SD-44 / SD-47 / SD-53 status

| Decision | Status | Explanation |
|---|---|---|
| SD-44 | **PARTIAL** | Safe logging and malformed-body redaction improved. Structured JSON format, formal redaction policy, and retention remain unspecified and unimplemented. |
| SD-47 | **VERIFIED FOR THIS STAGE** | Production logging levels and Actuator restrictions verified. Per-release verification remains required. |
| SD-53 | **NOT IMPLEMENTED** | CSRF remains framework-default only. The approved session-bound 256-bit design belongs to the authentication stage. |

### Overall verdict

**ACCEPTED WITH DOCUMENTED DEFERRALS.** Both sub-stages satisfy their mandatory
acceptance criteria against real PostgreSQL. The deferrals are explicit,
documented requirements (`SD-44`) or later-stage work (`SD-53`, deployment).
This is not a production-readiness claim.

---

## M. Git and Commit Record

| Item | Value |
|---|---|
| Branch | `stage-2-backend-foundation` |
| Starting HEAD | `3fb3014` |
| Stage 2.5 commit | `b6887d1` — `feat(stage-2): refine validation and exception infrastructure` |
| Stage 2.6 commit | `096ac58` — `feat(stage-2): harden configuration logging and health checks` |
| Report commit | The commit that adds this file |
| Final HEAD | Recorded by `git log -1` after the report commit |
| Working tree | Only documentation and the report remained before the final commit; no generated artifacts or secrets staged |
| Push | Not pushed |
| Merge into `main` | Not performed |

`V1__spring_session_schema.sql` and `V2`–`V14` were not part of any commit in
this work.

---

## N. Next Stage Recommendation

Stage 2.5/2.6 introduce no blocking prerequisite for further backend work, but
the following must be resolved before authentication or production deployment:

1. Keep `V1`–`V14` immutable once shared; any schema correction must be a new
   migration.
2. Implement SD-53 completely with authentication: session-backed repository,
   256-bit generator, raw `X-CSRF-TOKEN` resolution, response-header writer,
   post-rotation ordering, audit event, and the Section 17 security tests.
3. Define and implement SD-44: structured log format, formal redaction policy,
   retention/rotation, and a request correlation ID.
4. Add a separate management port or documented network restriction for
   Actuator before production.
5. Bridge the Render `PORT` / `SERVER_PORT` mismatch and verify TLS and proxy
   headers in a real deployment.
6. Map multipart and remaining data-binding failures to `400` where safe.
7. Supply an isolated PostgreSQL instance through `TEST_DB_URL` in CI, preferably
   with Testcontainers, so the 11 persistence tests run on every change.
8. Keep `/ws` denied until session handshake authentication and STOMP
   authorization exist.
9. Do not merge this branch into `main` or push it without explicit
   instruction.
