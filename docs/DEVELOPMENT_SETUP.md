# EstateVista V2 — Development & Environment Setup

**Stage:** 2.8 — Backend Integration Review & Stage 2 Closure
**Status:** Repository, persistence, validation, configuration, logging, and
automated test baseline complete and validated locally
**Last validated:** 2026-11-15 (Windows, PowerShell, local PostgreSQL 18.3)

This document describes the actual, validated development environment for the
EstateVista V2 backend and frontend. It covers infrastructure, the completed
backend authentication and security configuration, and the Stage 3.7 frontend
authentication integration. Property management, chat handling, Cloudinary
upload, and production deployment remain outside this document's completed
scope.

Approved source-of-truth documents:

- `docs/PRD.md`
- `docs/FRD.md`
- `docs/SYSTEM_ARCHITECTURE.md`
- `docs/DATABASE_DESIGN.md`
- `docs/API_SPECIFICATION.md`
- `docs/SECURITY_ARCHITECTURE.md`
- `docs/UI_UX_SPECIFICATION.md`

Where this document and an approved document disagree, the approved document
wins and this document must be corrected.

---

## 1. Technology baseline

Versions below were resolved from the build on 2026-11-11 with
`mvn dependency:tree` and the generated Spring Boot parent POM.

| Component | Version | Notes |
| --- | --- | --- |
| Java (build/runtime) | 23.0.2 installed | Project targets Java 21 LTS (`<java.version>21</java.version>`) |
| Maven | 3.9.14 | No Maven wrapper is committed |
| Spring Boot | 4.1.2 | Current stable at setup time |
| Spring Framework | 7.0.10 | Managed by Boot |
| Spring Security | 7.1.2 | Managed by Boot; SD-53 recheck in §12 |
| Spring Session | 4.1.1 | JDBC-backed, managed by Boot |
| Flyway | 12.4.0 | Core + PostgreSQL module |
| Hibernate ORM | 7.4.13.Final | Managed by Boot |
| PostgreSQL JDBC | 42.7.14 | Runtime scope |
| Embedded Tomcat | 11.0.27 | Managed by Boot |
| Jackson | 3.1.7 (`tools.jackson.core`) | Boot 4 uses Jackson 3 |
| PostgreSQL server | 18.3 | Local installation |
| Node.js | 24.13.0 | Static dev server and JS syntax checks |
| npm | 11.6.2 | Not required to run the frontend shell |

Spring Boot 4 modularized several auto-configurations. Two non-obvious
dependencies are required and are pinned in `backend/pom.xml`:

- `spring-boot-starter-flyway` — provides `spring-boot-flyway`; adding only
  `flyway-core` starts the application **without running migrations**.
- `spring-boot-starter-session-jdbc` — provides `spring-boot-session-jdbc`;
  adding only `spring-session-jdbc` leaves plain Tomcat sessions active and
  never persists sessions to `SPRING_SESSION`.

`spring-boot-starter-web` is deprecated in Boot 4; the project uses
`spring-boot-starter-webmvc`.

---

## 2. Repository layout (Stage 1.8 additions)

```text
EstateVista/
├── backend/
│   ├── pom.xml
│   ├── .env.example          # dummy values only; safe to commit
│   ├── .gitignore            # ignores .env, target/, logs, IDE files
│   ├── README.md
│   ├── scripts/
│   │   └── postgres-test-cluster.ps1   # disposable test DB + full verification
│   ├── src/main/
│   │   ├── java/com/estatevista/
│   │   │   ├── EstateVistaApplication.java
│   │   │   ├── config/
│   │   │   │   ├── AllowedOrigins.java
│   │   │   │   ├── AppProperties.java
│   │   │   │   ├── CorsConfig.java
│   │   │   │   ├── SecurityConfig.java
│   │   │   │   └── WebSocketConfig.java
│   │   │   ├── common/
│   │   │   │   ├── exception/     # error codes, handler, /error controller
│   │   │   │   ├── health/HealthController.java
│   │   │   │   └── response/      # standard API error envelope
│   │   │   ├── user/              # roles, users, agent profiles
│   │   │   ├── property/          # properties, categories, details, images, amenities
│   │   │   ├── favorite/          # buyer favorites
│   │   │   ├── inquiry/           # buyer inquiries
│   │   │   ├── chat/              # conversations, messages, read states
│   │   │   ├── notification/      # user notifications
│   │   │   └── audit/             # audit history
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-dev.yml
│   │       ├── application-test.yml
│   │       ├── application-prod.yml
│   │       └── db/migration/      # V1 Spring Session; V2–V14 business schema
│   └── src/test/java/com/estatevista/
│       ├── common/                # web-layer contract and handler tests
│       ├── config/                # security-chain, CORS, origin-validation tests
│       └── persistence/           # opt-in PostgreSQL integration tests
├── frontend/
│   ├── dev-server.mjs        # zero-dependency static server, port 5500
│   ├── login.html            # Stage 3.7 login page
│   ├── register.html         # Stage 3.7 buyer registration page
│   ├── assets/js/core/config.js
│   ├── assets/js/core/api.js
│   ├── assets/js/auth/       # auth service, state, navigation, page scripts
│   ├── assets/css/auth.css   # authentication page composition
│   └── dev/setup-check.html  # development-only connectivity check
└── docs/
    └── DEVELOPMENT_SETUP.md  # this document
```

The `frontend/` directory also contains the preserved static site from V1.
Those files were not modified by Stage 1.8 except for additive Stage 1.8 files.

---

## 3. Prerequisites

- JDK 21 or newer. The validated build used JDK 23.0.2.
- Maven 3.9 or newer.
- PostgreSQL 18.x (a local server or a container).
- Node.js 20 or newer for the static dev server (validated with 24.13.0).
- PowerShell examples below assume Windows; the commands map directly to
  bash/zsh on macOS/Linux.

Verify:

```powershell
java -version
mvn -version
node --version
psql --version
```

If `psql` is not on `PATH` on Windows, use the full path, for example
`C:\Program Files\PostgreSQL\18\bin\psql.exe`.

---

## 4. Environment variables

Copy `backend/.env.example` to `backend/.env` for your own reference and export
the values into the shell before starting the application. **Spring Boot does
not load `.env` automatically** in this project; either export the variables or
configure them in your IDE run configuration.

`backend/.env` is ignored by Git. Never commit real credentials.

| Variable | Purpose | Local default / example |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | Activates `dev`, `test`, or `prod` profile | `dev` |
| `SERVER_PORT` | API port | `8080` |
| `DB_URL` | JDBC URL | `jdbc:postgresql://localhost:5432/estatevista` |
| `DB_USERNAME` | Database user | `estatevista` |
| `DB_PASSWORD` | Database password | local-only value; never committed |
| `DB_POOL_SIZE` | Hikari maximum pool size | `10` |
| `TEST_DB_URL` | Isolated database for integration tests | `jdbc:postgresql://localhost:5432/estatevista_test` |
| `TEST_DB_USERNAME` | Test database user | `estatevista` |
| `TEST_DB_PASSWORD` | Test database password | local-only value; never committed |
| `APP_CORS_ALLOWED_ORIGINS` | Exact, comma-separated CORS origins; **required** in `prod` | `http://localhost:5500,http://127.0.0.1:5500` |
| `APP_WEBSOCKET_ALLOWED_ORIGINS` | Exact, comma-separated WebSocket origins; **required** in `prod` | `http://localhost:5500,http://127.0.0.1:5500` |
| `SESSION_COOKIE_SECURE` | `Secure` session cookie flag | `false` locally; must be `true` in production |
| `FORWARD_HEADERS_STRATEGY` | Proxy header handling | `none` locally; `native` behind a trusted proxy |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` | Reserved email placeholders | dummy values only; not wired in Stage 1.8 |
| `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET` | Reserved media placeholders | dummy values only; not wired in Stage 1.8 |

Rules:

- CORS and WebSocket origins must be explicit comma-separated `http`/`https`
  origins. `AllowedOrigins` rejects wildcards, blank or empty lists, unresolved
  `${...}` placeholders, paths (including a trailing `/`), queries, fragments,
  user info, and non-HTTP schemes at startup.
- Production values are supplied by the hosting environment. No production
  hostname or credential is committed.
- `application-prod.yml` deliberately has no fallback for `DB_URL`,
  `DB_USERNAME`, `DB_PASSWORD`, `APP_CORS_ALLOWED_ORIGINS`, or
  `APP_WEBSOCKET_ALLOWED_ORIGINS`; startup must fail if any is missing.

---

## 5. PostgreSQL setup

### 5.1 Normal local development database

Run as a PostgreSQL superuser (for example the `postgres` role):

```sql
CREATE ROLE estatevista LOGIN PASSWORD 'choose-a-local-password';
CREATE DATABASE estatevista OWNER estatevista;
```

Then export:

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/estatevista"
$env:DB_USERNAME = "estatevista"
$env:DB_PASSWORD = "choose-a-local-password"
$env:SPRING_PROFILES_ACTIVE = "dev"
```

The installed PostgreSQL service on the validation machine uses
`scram-sha-256` authentication. The `postgres` password was not available, so
the validation run used an isolated temporary cluster instead (next section).
Do not weaken the installed service's authentication to make local setup work.

### 5.2 Repeatable isolated test cluster (used for this stage)

The integration tests require a real PostgreSQL database. Testcontainers is not
used because Docker is not installed or available in this environment. Instead,
`backend/scripts/postgres-test-cluster.ps1` provisions a disposable cluster that
never touches the installed service:

```powershell
# Full verification: init/start cluster, run mvn -B clean verify, stop cluster
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\postgres-test-cluster.ps1 -Action verify

# Or leave the cluster running for repeated runs
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\postgres-test-cluster.ps1 -Action start
$env:TEST_DB_URL = "jdbc:postgresql://localhost:55433/estatevista_test"
$env:TEST_DB_USERNAME = "postgres"
$env:TEST_DB_PASSWORD = ""
mvn -B clean verify
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\postgres-test-cluster.ps1 -Action stop
```

Facts about the script:

- Cluster directory: `backend/.test-postgres` (Git-ignored, outside `target/` so
  `mvn clean` cannot delete a running cluster).
- Port: `55433` (the installed service on `5432` is not used).
- Authentication: `trust`, localhost only.
- Database: `estatevista_test`.
- Actions: `verify`, `start`, `stop`, `status`.
- `verify` always stops the cluster, even when Maven fails.

Trust authentication is acceptable only for this throwaway cluster on a
nonstandard port. It is not a development or production configuration.

A manual throwaway cluster remains possible if the script cannot be used:

```powershell
$pg = "C:\Program Files\PostgreSQL\18\bin"
$data = "$env:TEMP\estatevista-pg"

& "$pg\initdb.exe" -D $data -U postgres --auth=trust -E UTF8 --locale=C
& "$pg\pg_ctl.exe" -D $data -o "-p 55432 -c listen_addresses=localhost" -l "$data\server.log" start
& "$pg\createdb.exe" -h localhost -p 55432 -U postgres estatevista
```

Then run the backend with:

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:55432/estatevista"
$env:DB_USERNAME = "postgres"
$env:DB_PASSWORD = ""
```

### 5.3 Schema ownership

- Flyway owns the schema. Spring Session is configured with
  `spring.session.jdbc.initialize-schema: never`.
- `V1__spring_session_schema.sql` creates the framework-owned
  `SPRING_SESSION` and `SPRING_SESSION_ATTRIBUTES` tables approved by SD-03.
  It must never be modified or renumbered.
- `V2`–`V14` create the approved business schema (18 tables, seeded roles and
  property categories, constraints, and indexes). The sequence is defined in
  `DATABASE_DESIGN.md` §19; the implementation and evidence are recorded in
  `docs/validation/stage-2.3-2.4-completion-report.md`.
- `ddl-auto` is `validate`; Hibernate must never create or alter schema.
- `flyway.clean-disabled` is `true`.
- A fresh database must migrate through all 14 versions before the application
  starts. Startup fails if Hibernate validation finds a schema mismatch.

PostgreSQL folds unquoted identifiers to lowercase, so the physical tables are
`spring_session`, `spring_session_attributes`, `users`, `properties`, and so on.
Spring Session and JPA queries use unquoted names and work correctly.

---

## 6. Backend build and run

From `backend/`:

```powershell
mvn clean package
java -jar target/estatevista-backend-0.1.0-SNAPSHOT.jar
```

Or, with Maven:

```powershell
mvn spring-boot:run
```

The packaged-jar path was validated in this stage. `mvn spring-boot:run` was not
separately re-validated; it uses the same configuration.

Startup succeeds only when the database is reachable and the migration state is
valid. Flyway logs the applied version, and Tomcat logs the listening port.

### Profiles

| Profile | File | Behavior |
| --- | --- | --- |
| `dev` | `application-dev.yml` | Debug logging for application and web; Flyway logs via SLF4J |
| `test` | `application-test.yml` | Isolated test database; reduced logging; no production fallbacks |
| `prod` | `application-prod.yml` | `Secure` session cookie, `forward-headers-strategy: native`, no DB or origin fallbacks, INFO logging, SQL/bind/extract at WARN |

Shared settings live in `application.yml`. The `prod` profile fails fast:
starting without `APP_CORS_ALLOWED_ORIGINS` exits with code `1` and reports
`app.cors.allowed-origins contains an invalid origin:
${APP_CORS_ALLOWED_ORIGINS}`; the WebSocket list behaves the same way. This is
intentional, so a misconfigured deployment cannot silently accept development
origins.

### Health endpoints

```text
GET http://localhost:8080/api/v1/health
GET http://localhost:8080/actuator/health
```

The application health endpoint returns a minimal liveness JSON document
(`status`, `service`, `timestamp`) and does not query the database. Actuator
exposes only `health`, with details hidden. Actuator includes the database
indicator, so it returns `200 {"status":"UP"}` while PostgreSQL is reachable and
`503 {"status":"DOWN"}` when it is not; `/api/v1/health` stays `200 UP` in both
cases. Every other `/actuator/**` path (for example `/actuator/env`,
`/actuator/beans`, `/actuator/configprops`, `/actuator/heapdump`, or
`/actuator`) is not exposed and returns the standard `401` envelope.

### Logging and exception hygiene

- `GlobalExceptionHandler` logs client errors at debug and unexpected server
  errors at error with the exception, but never logs request bodies or headers.
- Malformed JSON and similar parser failures log only the method, path, and
  exception simple name. Framework parser messages can contain an excerpt of the
  rejected body and are not logged (SD-44, Security Architecture §11.13,
  §11.17, and §14.9).
- `prod` pins SQL, JDBC bind, and result-extraction loggers to `WARN` even if a
  parent logger is raised later (SD-47).
- Responses never contain stack traces, exception class names, SQL text, bean
  names, or internal paths.

---

## 7. Security shell behavior

`SecurityConfig` is a deny-by-default shell:

- Public: `/api/v1/health`, `/actuator/health`, `/error`.
- Everything else, including `/ws` and `/ws/**`, is denied until the owning
  stage adds an explicit matcher. Unauthenticated requests return `401` with the
  standard error envelope.
- Form login, HTTP Basic, and logout are disabled.
- Boot's default in-memory user is excluded, so the shell never starts with
  generated credentials.
- CSRF protection remains enabled with framework defaults.
- The access-denied handler writes the standard JSON error envelope, including
  for CSRF failures.

This is **not** an authentication implementation, and it is not a CSRF
implementation. The WebSocket transport is configured but the handshake stays
denied until the authentication stage adds a session handshake interceptor,
subscription authorization, and the SD-53 CSRF wiring.

### Session persistence

Spring Session JDBC stores sessions in PostgreSQL:

- Cookie: `EV_SESSION`, `HttpOnly`, `SameSite=Lax`, `Path=/`.
- `Secure` is environment-controlled; it is `false` locally and `true` in the
  `prod` profile.
- Timeout: 30 minutes (`1800` seconds persisted in `MAX_INACTIVE_INTERVAL`).
- Cookie values are Base64-encoded UUIDs; sessions are stored in PostgreSQL and
  were confirmed to survive an application restart.
- CSRF token storage is not yet wired (see §12).

---

## 8. CORS and WebSocket

### CORS

Registered for `/api/**` and `/actuator/health`:

- Exact allowed origins from `APP_CORS_ALLOWED_ORIGINS`.
- Methods: `GET, POST, PUT, PATCH, DELETE, OPTIONS`.
- Allowed request headers: `Content-Type`, `X-CSRF-TOKEN`, `Accept`, `Origin`,
  `X-Requested-With`.
- Exposed response header: `X-CSRF-TOKEN` (reserved for SD-53).
- `Access-Control-Allow-Credentials: true`.
- Preflight cache: 3600 seconds.
- Wildcard, blank, malformed, placeholder, path, query, fragment, user-info, and
  non-HTTP origins cause startup failure through the shared `AllowedOrigins`
  validator.

### WebSocket

- Native WebSocket (no SockJS), endpoint `/ws`.
- Exact allowed origins from `APP_WEBSOCKET_ALLOWED_ORIGINS`.
- Empty, wildcard, and otherwise invalid origin lists fail startup through the
  same `AllowedOrigins` validator as CORS.
- STOMP broker destinations: `/queue`, `/topic`; application prefix `/app`;
  user prefix `/user`.
- No message handlers, subscriptions, or authorization exist yet.
- The HTTP security chain denies `/ws` and `/ws/**`; an unauthenticated
  handshake returns `401` with the standard error envelope. The authentication
  stage must add session handshake authentication and STOMP authorization
  before the endpoint is made public.

---

## 9. Frontend development server

The frontend is static and hosted separately from the API. There is no proxy.

```powershell
node frontend/dev-server.mjs
```

Defaults:

- Frontend: `http://localhost:5500/`
- Setup check: `http://localhost:5500/dev/setup-check.html`
- Override host/port with `FRONTEND_HOST` and `FRONTEND_PORT`.

Runtime API configuration (`frontend/assets/js/core/config.js`):

- Local hostnames (`localhost`, `127.0.0.1`) use port `8080` on the same
  hostname as the page, e.g. `http://localhost:8080/api/v1` or
  `http://127.0.0.1:8080/api/v1`. Keeping the hostname aligned preserves the
  `SameSite=Lax` session cookie on credentialed fetches.
- Non-local deployments must define
  `window.ESTATEVISTA_CONFIG = { apiBaseUrl, webSocketUrl }` before the module
  scripts run.
- The API client (`api.js`) uses `credentials: "include"` and keeps the CSRF
  token in memory only. It does not use cookies, `localStorage`,
  `sessionStorage`, URLs, or logs for the token.

Stage 3.7 adds the authentication experience:

- `frontend/login.html` and `frontend/register.html`.
- `frontend/assets/js/auth/` for the auth service, in-memory state,
  authentication-aware navigation, safe redirects, and page bootstrap.
- `frontend/assets/css/auth.css` for the authentication page layout.
- Every page includes `assets/js/auth/auth-init.js`, which asks
  `GET /api/v1/auth/me` whether a session exists and updates the header.
- Registration uses `POST /api/v1/auth/register` (CSRF-exempt, exact
  Origin/Referer required). Login and logout are CSRF protected.
- Login obtains `GET /api/v1/auth/csrf`, reads `X-CSRF-TOKEN` from the response
  header, submits credentials with `credentials: "include"`, and verifies the
  rotated session through `GET /api/v1/auth/me`.
- HTML, CSS, and Vanilla ES modules only; no framework and no build step.

Full contract, flows, and the manual verification checklist are documented in
[`frontend/docs/AUTHENTICATION.md`](../frontend/docs/AUTHENTICATION.md).

The dev server rejects path traversal and returns `404` for missing files. It
is not a production server.

---

## 10. Validation evidence (2026-11-12)

All commands were executed against the isolated PostgreSQL 18.3 cluster on port
`55432` and the packaged backend jar on port `8080`.

| # | Check | Command / method | Result |
| --- | --- | --- | --- |
| 1 | Clean build | `mvn -B clean package` | PASS — `BUILD SUCCESS` |
| 2 | Automated tests | Surefire during build | PASS (no test sources yet) — `No tests to run` |
| 3 | Fresh migration | Start against empty database | PASS — `Successfully applied 1 migration ... now at version v1` |
| 4 | Migration idempotency | Restart against migrated database | PASS — `Current version of schema "public": 1`, no reapply |
| 5 | Schema objects | `information_schema.tables` query; compare DDL with `spring-session-jdbc-4.1.1.jar` | PASS — `flyway_schema_history`, `spring_session`, `spring_session_attributes`; DDL matches Spring Session 4.1.1 `schema-postgresql.sql` |
| 6 | Application health | `GET /api/v1/health` | PASS — `200`, `{"status":"UP",...}` |
| 7 | Actuator health | `GET /actuator/health` | PASS — `200`, `{"status":"UP"}` |
| 8 | Deny-by-default | `GET /api/v1/unknown-protected` | PASS — `401` |
| 9 | No default user | Startup log after excluding `UserDetailsServiceAutoConfiguration` | PASS — no generated-password warning |
| 10 | CSRF failure contract | `POST` without token + allowed `Origin` | PASS — `403`, `{"error":"FORBIDDEN"}`, CORS headers present |
| 11 | CORS allowed preflight | `OPTIONS` + `Origin: http://localhost:5500` | PASS — `200`, matching `Access-Control-Allow-Origin`, credentials, exposed header |
| 12 | CORS rejected preflight | `OPTIONS` + `Origin: http://evil.example.com` | PASS — `403 Invalid CORS request` |
| 13 | CORS allowed actual request | `GET` + allowed `Origin` | PASS — `200`, `Access-Control-Allow-Origin`, credentials, exposed header |
| 14 | CORS rejected actual request | `GET` + disallowed `Origin` | PASS — `403 Invalid CORS request` |
| 15 | CORS wildcard fail-fast | Start with `APP_CORS_ALLOWED_ORIGINS=*` | PASS — exit code `1`, `Wildcard CORS origins are not permitted...` |
| 16 | WebSocket allowed handshake | Raw HTTP upgrade with allowed `Origin` | PASS — `101 Switching Protocols`, subprotocol `v12.stomp` |
| 17 | WebSocket rejected handshake | Raw HTTP upgrade with disallowed `Origin` | PASS — `403` |
| 18 | WebSocket wildcard fail-fast | Start with `APP_WEBSOCKET_ALLOWED_ORIGINS=*` | PASS — exit code `1`, `Wildcard WebSocket origins are not permitted` |
| 19 | Session persistence | `401` request, then query `spring_session` | PASS — Base64 UUID cookie, row persisted, `MAX_INACTIVE_INTERVAL = 1800` |
| 20 | Session reuse across restart | Restart backend, repeat request with session cookie | PASS — same row retained (`creation_time` unchanged), access time advanced |
| 21 | Frontend index | `GET http://localhost:5500/` | PASS — `200 text/html` |
| 22 | Setup-check page | `GET /dev/setup-check.html` | PASS — `200 text/html` |
| 23 | Frontend module | `GET /assets/js/core/config.js` | PASS — `200 text/javascript` |
| 24 | Dev-server 404 | `GET /nope.html` | PASS — `404` |
| 25 | Dev-server traversal guard | Encoded `../` request | PASS — `403` |
| 26 | JS syntax | `node --check` on four JS/MJS files | PASS |
| 27 | Browser end-to-end setup-check | Automated browser render | BLOCKED — no browser automation available in this environment; HTTP/CORS/API behavior was verified separately |
| 28 | Production deployment | Render/TLS/proxy checks | BLOCKED — out of scope for Stage 1.8 and no production credentials/hostnames are available |

No unavailable check is reported as PASS.

The curated, trimmed command transcript for these checks is retained at
[`docs/validation/stage-1.8-evidence.txt`](validation/stage-1.8-evidence.txt).
Session identifiers in that transcript are redacted.

### 10.1 Stage 2.3 / 2.4 persistence validation (2026-11-14)

Executed against a throwaway PostgreSQL 18.3 cluster on port `55433`
(`estatevista` and `estatevista_test` databases, trust authentication, deleted
after validation). Full evidence and acceptance assessment are in
`docs/validation/stage-2.3-2.4-completion-report.md`.

| # | Check | Result |
| --- | --- | --- |
| 1 | Fresh cold build (`mvn -B clean package`) | PASS — `BUILD SUCCESS` |
| 2 | Fresh migration V1→V14 | PASS — all 14 versions applied, `success = true` |
| 3 | Business tables created | PASS — 18 tables |
| 4 | Foreign keys | PASS — 28, including composite `(category_id, subtype_id)` |
| 5 | CHECK constraints | PASS — 6 (account status, listing type, status, price, latitude, longitude) |
| 6 | Indexes | PASS — 53, including partial primary-image unique index |
| 7 | Migration idempotency | PASS — restart reported version `14`, no reapply |
| 8 | Hibernate `ddl-auto=validate` | PASS — application started, health `UP` |
| 9 | Case-insensitive duplicate email | PASS — rejected by `uk_users_email` |
| 10 | Real-PostgreSQL integration tests | PASS — 11 tests |
| 11 | Full `mvn -B clean verify` with test DB | PASS — 17 tests, 0 failures, 0 errors, 0 skipped |
| 12 | `V1__spring_session_schema.sql` unchanged | PASS — no diff |

### 10.2 Stage 2.5 / 2.6 validation infrastructure (2026-11-15)

Executed against the packaged jar and a throwaway PostgreSQL 18.3 cluster on
port `55433` (`estatevista`, `estatevista_test`, trust authentication, deleted
after validation). The full acceptance assessment is in
`docs/validation/stage-2.5-2.6-completion-report.md`.

| # | Check | Result |
| --- | --- | --- |
| 1 | Full `mvn -B clean verify` with test DB | PASS — 40 tests, 0 failures, 0 errors, 0 skipped; Flyway validated 14 migrations at version 14 |
| 2 | Web-only `mvn clean verify` without test DB | PASS — 29 executed, 11 skipped, no failures |
| 3 | Exception/logging unit tests | PASS — 13 tests, including malformed-body log redaction |
| 4 | Security filter-chain tests | PASS — 7 tests covering 401, 403, CSRF, public health, `/ws` denial, allowed/disallowed CORS |
| 5 | Exact-origin validator tests | PASS — 6 tests |
| 6 | `dev` application health | PASS — `200 {"status":"UP",...}` |
| 7 | `dev` Actuator health | PASS — `200 {"status":"UP"}` |
| 8 | Non-exposed Actuator paths | PASS — `/actuator`, `/actuator/env`, `/actuator/beans`, `/actuator/configprops`, `/actuator/heapdump` all `401` JSON envelope |
| 9 | `/ws` remains denied | PASS — `401` JSON envelope |
| 10 | Unknown API path | PASS — `401` JSON envelope |
| 11 | CORS allowed origin | PASS — `200`, matching `Access-Control-Allow-Origin`, `Access-Control-Allow-Credentials: true`, `Access-Control-Expose-Headers: X-CSRF-TOKEN` |
| 12 | CORS disallowed origin | PASS — `403`; body is Spring's plain-text `Invalid CORS request` (known limitation) |
| 13 | Health with PostgreSQL stopped | PASS — `/actuator/health` `503 {"status":"DOWN"}`; `/api/v1/health` `200 UP` |
| 14 | `prod` missing origin configuration | PASS — exit code `1`, `app.cors.allowed-origins contains an invalid origin: ${APP_CORS_ALLOWED_ORIGINS}` |
| 15 | `prod` configured startup | PASS — started on the configured port; no `DEBUG` lines; synthetic DB password absent from logs |
| 16 | `prod` session cookie | PASS — `EV_SESSION=...; Path=/; Secure; HttpOnly; SameSite=Lax` |
| 17 | `prod` CORS allowed origin | PASS — `200` with credentialed CORS headers |
| 18 | `V1`–`V14` unchanged | PASS — no migration diff |
| 19 | Structured JSON logs, formal redaction policy, log retention | NOT PERFORMED — requirements remain unspecified (SD-44 deferred) |
| 20 | Production deployment/TLS/proxy checks | NOT PERFORMED — out of scope and no production credentials |

### 10.3 Stage 2.7 / 2.8 automated verification (2026-11-15)

Executed from `backend/` with
`postgres-test-cluster.ps1 -Action verify` against a disposable PostgreSQL 18.3
cluster on port `55433`. The full acceptance assessment is in
`docs/validation/stage-2.7-2.8-completion-report.md`.

A database-free run (`mvn -B clean verify` without `TEST_DB_URL`) executed 60
tests, skipped the 23 PostgreSQL-backed tests, and passed with 0 failures and 0
errors. That mode does not verify persistence.

| # | Check | Result |
| --- | --- | --- |
| 1 | Full `mvn -B clean verify` with test DB (run 1) | PASS — 83 tests, 0 failures, 0 errors, 0 skipped; `BUILD SUCCESS`; 1 min 38 s |
| 2 | Full `mvn -B clean verify` with test DB (run 2) | PASS — identical totals; 1 min 37 s; confirms repeatability with a fresh cluster |
| 3 | Flyway validation | PASS — 14 migrations validated, schema at version 14 |
| 4 | Hibernate schema validation | PASS — `ddl-auto=validate`; full context started |
| 5 | Full-context integration tests | PASS — 7 tests: migration state, health, Actuator, `/ws`, unknown route, CORS, CSRF |
| 6 | Persistence/schema tests | PASS — 16 tests: metadata, repositories, column types, full graph, commercial details, enums, constraints |
| 7 | Unit/web-layer tests | PASS — 60 tests across error contract, configuration, security, CORS, and identifiers |
| 8 | JaCoCo report | PASS — instruction 70.09% (1720/2454), branch 75.45% (83/110), line 65.94% (517/784); no threshold enforced |
| 9 | Packaged-jar runtime smoke (dev profile, port 18080) | PASS — started in 29.6 s; Flyway validated 14 migrations; `/api/v1/health` 200; `/actuator/health` 200 |
| 10 | Actuator restriction at runtime | PASS — `/actuator/beans` 401 JSON envelope |
| 11 | Deny-by-default at runtime | PASS — `/ws` and `/api/v1/not-implemented` 401 JSON envelopes |
| 12 | Credentialed CORS at runtime | PASS — allowed origin 200 with matching origin, credentials, exposed `X-CSRF-TOKEN`; disallowed origin 403 without allow-origin |
| 13 | CSRF default at runtime | PASS — tokenless `POST` 403 JSON envelope |
| 14 | Session persistence at runtime | PASS — `EV_SESSION` cookie `HttpOnly; SameSite=Lax`; row in `spring_session`; `MAX_INACTIVE_INTERVAL` 1800 |
| 15 | CI workflow | CONFIGURED, NOT EXECUTED — `.github/workflows/backend-verify.yml`; branch has not been pushed |
| 16 | `V1`–`V14` unchanged | PASS — no migration diff |
| 17 | Browser E2E / production deployment | NOT PERFORMED — no browser automation and no production credentials |

No unavailable check is reported as PASS.

---

## 11. Known limitations and deferred work

- No authentication, registration, login, logout, or authorization rules.
- No business modules, property CRUD, chat handling, or Cloudinary integration.
- No WebSocket message handlers or STOMP authorization.
- CSRF design (SD-53) is not implemented; only framework defaults are active.
- SD-44 is only partially addressed. Safe logging practices and malformed-body
  redaction are in place, but a structured JSON log format, a formal redaction
  policy, log retention/rotation, and a correlation-ID scheme remain
  unspecified/deferred.
- There is no request correlation ID yet. Error envelopes expose success/code/
  message/details/timestamp/path only.
- Disallowed-origin CORS responses are Spring's plain-text
  `Invalid CORS request`, not the JSON error envelope. Allowed and disallowed
  decisions are correct; only the body format differs.
- Actuator shares the application port. It is protected by the deny-by-default
  chain and exposes only `health`, but a separate management port is not
  configured.
- Multipart and some data-binding failures can fall through to the generic
  `500` handler; they are safe (no internal detail) but not mapped to `400`.
- Automated tests cover the health endpoint, the complete error-response
  contract, configuration contracts, security-chain/CORS behavior, origin
  validation, and composite identifiers (Stages 2.2/2.5/2.6/2.7) plus schema,
  mapping, and constraint verification (Stage 2.3/2.4/2.7). The suite is 83 tests
  when `TEST_DB_URL` points at an isolated PostgreSQL database: 60 unit/web-layer
  and 23 PostgreSQL-backed. Without `TEST_DB_URL`, the 23 integration tests are
  skipped and are not represented as persistence coverage. No authentication or
  business-flow tests exist because those features are not implemented.
- Testcontainers is not used because Docker is not available in the current
  environment. The repository provides a disposable-cluster script instead, and
  the CI workflow uses a `postgres:18` service container. Like `TEST_DB_URL`
  itself, this is an environment/provisioning choice, not an application
  dependency.
- The GitHub Actions workflow is committed but has not run: the branch has not
  been pushed. CI execution remains unverified until the first push.
- JaCoCo reports coverage but enforces no threshold, because no coverage gate has
  been approved. Current instruction coverage is 70.09%; this is an execution
  indicator, not a correctness or production-readiness claim.
- Render supplies `PORT`, while this application reads `SERVER_PORT`. Deployment
  configuration must bridge that mismatch; it is not resolved in this stage.
- The persistence layer has no services, controllers, or endpoints; role,
  ownership, participant, and status-transition rules remain application-layer
  work for later stages.
- The setup-check page was validated at the HTTP/API layer, not through an
  automated browser.
- Production deployment, TLS, email, and media storage are not configured.
- The temporary validation cluster uses trust authentication and must not be
  reused as a development/production database.

---

## 12. SD-53 CSRF compatibility recheck (Spring Security 7.1.2)

The approved SD-53 design was rechecked against the exact resolved version,
Spring Security **7.1.2**:

| Design element | 7.1.2 status | Implementation caveat |
| --- | --- | --- |
| Session-backed token storage | `HttpSessionCsrfTokenRepository` retained | Compatible |
| Default header/parameter | `X-CSRF-TOKEN` / `_csrf` retained | Compatible |
| Token generation | Default is UUID-based (~122 bits) | Stage 3.6 implements `com.estatevista.config.SecureCsrfTokenRepository` to generate 32 bytes (256 bits) from `java.security.SecureRandom`, encoded as URL-safe Base64 without padding |
| Lazy token loading | `loadDeferredToken` supported | Compatible |
| Token masking | `XorCsrfTokenRequestAttributeHandler` is the default and unmasks submitted values | The approved frontend sends the raw session token in `X-CSRF-TOKEN`. Use a plain `CsrfTokenRequestAttributeHandler` (or an equivalent raw-header resolver), or emit the masked value in the response header. Do not pair the default XOR handler with a raw header token |
| Rotation after authentication | `CsrfAuthenticationStrategy` available | Rotation ordering must be wired and tested |
| SPA shorthand | `csrf.spa()` exists in 7.1, but bytecode inspection shows it unconditionally installs `CookieCsrfTokenRepository.withHttpOnlyFalse()` | SD-53 rejects cookie-based double-submit storage. Do **not** call `spa()` as-is. If reusing `SpaCsrfTokenRequestHandler`, override the repository back to `HttpSessionCsrfTokenRepository` afterward (order-sensitive) or use a custom raw-header handler |
| Response header delivery | Not provided by default | Custom `X-CSRF-TOKEN` response-header writer required |
| Failure response | `SecurityConfig`'s `accessDeniedHandler` is already used by `CsrfFilter`; a tokenless `POST` returned `403 {"error":"FORBIDDEN"}` | JSON shape exists; add the approved audit event and a contract test |
| CORS exposure | Header exposure is already configured | Compatible |

**Result:** the approved design remains implementable on Spring Security 7.1.2
with no blocking API removal, but two framework defaults must not be used
as-is: `csrf.spa()` (cookie-backed) and the default XOR handler (expects a
masked value). Stage 3.6 implements the SD-53 baseline on Spring Security 7.1.2
with `com.estatevista.config.SecureCsrfTokenRepository` (session-backed,
32-byte `java.security.SecureRandom` token generation, URL-safe Base64 without
padding, no cookie), a plain `CsrfTokenRequestAttributeHandler` for raw header
tokens, header-only CSRF delivery on bootstrap/login/me responses, and the
existing JSON failure/audit handling. Verification evidence is recorded in
`docs/validation/STAGE_3_6_SD53_FINAL_VERIFICATION_REPORT.md`; the broader
Section 17 suite remains additional acceptance evidence for the full security
architecture.

This caveat is also recorded in the `SecurityConfig` Javadoc so it is visible
to the next stage.

---

## 13. External prerequisites for later stages

These are intentionally not configured and must not be claimed operational:

- Production frontend/API hostnames and DNS.
- Production PostgreSQL instance, credentials, backups, and TLS settings.
- Hosting platform (Render) environment variables and trusted proxy settings.
- SMTP provider credentials and sender identity.
- Cloudinary cloud name, API key, and API secret.
- Domain/TLS certificates and cookie domain decisions.
- Monitoring, log aggregation, and alerting.

---

## 14. Troubleshooting

**Migrations do not run**
Confirm `spring-boot-starter-flyway` is on the classpath. In Boot 4,
`flyway-core` alone does not activate Flyway auto-configuration.

**Sessions are not stored in PostgreSQL**
Confirm `spring-boot-starter-session-jdbc` is on the classpath and
`spring.session.jdbc.initialize-schema` is `never`. A Tomcat-style 32-character
hex cookie instead of a Base64 UUID means Spring Session is not active.

**Startup fails on database connection**
Verify `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`. The `prod` profile has no
fallbacks by design.

**CORS preflight fails**
Use an exact origin from `APP_CORS_ALLOWED_ORIGINS`. Wildcards, trailing
slashes, paths, queries, fragments, user info, and non-HTTP schemes are rejected
at startup and will not match a browser origin. Remember that `localhost` and
`127.0.0.1` are different origins.

**Production startup fails on an origin property**
`prod` requires `APP_CORS_ALLOWED_ORIGINS` and
`APP_WEBSOCKET_ALLOWED_ORIGINS`. An unresolved placeholder or an empty list
produces `contains an invalid origin` or `must contain at least one explicit
origin` and exit code `1`. Supply comma-separated exact origins.

**Actuator returns 401**
Only `/actuator/health` is exposed. Every other Actuator path is protected by
the deny-by-default chain and returns the standard `401` envelope; this is
expected, not a misconfiguration.

**`/actuator/health` is DOWN but `/api/v1/health` is UP**
Actuator includes the database indicator; the application health endpoint is a
liveness check and does not. Check PostgreSQL connectivity and `DB_URL`.

**Port already in use**
Change `SERVER_PORT` for the API or `FRONTEND_PORT` for the dev server. Update
`APP_CORS_ALLOWED_ORIGINS` and `APP_WEBSOCKET_ALLOWED_ORIGINS` if the frontend
origin changes.

**PostgreSQL authentication fails locally**
The installed service uses `scram-sha-256`. Set a known password for the
development role instead of weakening `pg_hba.conf`. Use the isolated cluster
approach only for throwaway validation.

**Persistence tests do not run**
`ApplicationIntegrationTest` and `BusinessSchemaIntegrationTest` are disabled
unless `TEST_DB_URL` is set. Run the disposable cluster script first:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\postgres-test-cluster.ps1 -Action verify
```

For a manual setup, provide `TEST_DB_URL`, `TEST_DB_USERNAME`, and
`TEST_DB_PASSWORD` for an isolated PostgreSQL database. The `test` profile
defaults the password to empty, so a password-protected test database must supply
it explicitly.

---

## 15. Stage boundary

Stage 1.8 establishes the repository, build, database, session, CORS, WebSocket
transport, frontend hosting, and validation baseline. It does not complete
Stage 1 as a whole. The Stage 1.9 documentation review and closure remain
separate, and the authentication stage owns SD-53 implementation and all
security tests.

Stage 2.1 reconciles the repository baseline (HEAD `9e20ed5`, clean tree). Stage
2.2 adds the layered application foundation: the `test` profile, the standard
API error envelope and handlers, a replacement `/error` controller, and
web-layer tests. It also closes the temporary public WebSocket handshake until
authentication exists. Stage 2.3 creates the approved business schema through
Flyway `V2`–`V14`; Stage 2.4 adds the matching JPA entities and repositories.
Stage 2.5 expands exception-handling and security-filter contract tests and
removes parser messages from malformed-request logs. Stage 2.6 adds shared
exact-origin validation, fail-fast production origin configuration, explicit
production SQL/bind/extract logging suppression, and verified health/Actuator
behavior. Stage 2.7 adds the automated testing strategy: unit, web-layer,
full-context, and persistence coverage; JaCoCo reporting; a disposable
PostgreSQL test-cluster script; and a CI workflow. Stage 2.8 performs the
integration review and final Stage 2 verification. Business services,
authentication, and API endpoints remain in later stages. SD-44 (structured
logging, formal redaction policy, retention) and SD-53 (CSRF wiring) remain
incomplete; no production-readiness claim is made.
