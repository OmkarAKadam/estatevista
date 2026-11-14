# EstateVista V2 — Development & Environment Setup

**Stage:** 1.8 — Repository & Environment Setup
**Status:** Project shell complete and validated locally
**Last validated:** 2026-11-12 (Windows, PowerShell, local PostgreSQL)

This document describes the actual, validated development environment for the
EstateVista V2 backend and frontend shell. It is intentionally limited to
infrastructure: no authentication workflow, business module, property CRUD,
chat handling, Cloudinary upload, or production deployment is implemented in
this stage.

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
│   ├── src/main/
│   │   ├── java/com/estatevista/
│   │   │   ├── EstateVistaApplication.java
│   │   │   ├── config/
│   │   │   │   ├── AppProperties.java
│   │   │   │   ├── CorsConfig.java
│   │   │   │   ├── SecurityConfig.java
│   │   │   │   └── WebSocketConfig.java
│   │   │   └── common/
│   │   │       ├── exception/     # error codes, handler, /error controller
│   │   │       ├── health/HealthController.java
│   │   │       └── response/      # standard API error envelope
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-dev.yml
│   │       ├── application-test.yml
│   │       ├── application-prod.yml
│   │       └── db/migration/V1__spring_session_schema.sql
│   └── src/test/java/com/estatevista/
├── frontend/
│   ├── dev-server.mjs        # zero-dependency static server, port 5500
│   ├── assets/js/core/config.js
│   ├── assets/js/core/api.js
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
| `APP_CORS_ALLOWED_ORIGINS` | Exact, comma-separated CORS origins | `http://localhost:5500,http://127.0.0.1:5500` |
| `APP_WEBSOCKET_ALLOWED_ORIGINS` | Exact, comma-separated WebSocket origins | `http://localhost:5500,http://127.0.0.1:5500` |
| `SESSION_COOKIE_SECURE` | `Secure` session cookie flag | `false` locally; must be `true` in production |
| `FORWARD_HEADERS_STRATEGY` | Proxy header handling | `none` locally; `native` behind a trusted proxy |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` | Reserved email placeholders | dummy values only; not wired in Stage 1.8 |
| `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`, `CLOUDINARY_API_SECRET` | Reserved media placeholders | dummy values only; not wired in Stage 1.8 |

Rules:

- CORS and WebSocket origins must be explicit. `*` is rejected at startup when
  credentials are enabled.
- Production values are supplied by the hosting environment. No production
  hostname or credential is committed.
- `application-prod.yml` deliberately has no fallback for `DB_URL`,
  `DB_USERNAME`, or `DB_PASSWORD`; startup must fail if they are missing.

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

### 5.2 Isolated validation cluster (used for this stage)

A temporary cluster avoids changing the installed service:

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

Trust authentication is acceptable only for this throwaway cluster on a
nonstandard port. It is not a development or production configuration.

### 5.3 Schema ownership

- Flyway owns the schema. Spring Session is configured with
  `spring.session.jdbc.initialize-schema: never`.
- `V1__spring_session_schema.sql` creates the framework-owned
  `SPRING_SESSION` and `SPRING_SESSION_ATTRIBUTES` tables approved by SD-03.
- No business/domain tables are created in Stage 1.8.
- `ddl-auto` is `validate`; Hibernate must never create or alter schema.
- `flyway.clean-disabled` is `true`.

PostgreSQL folds unquoted identifiers to lowercase, so the physical tables are
`spring_session`, `spring_session_attributes`, and `flyway_schema_history`.
Spring Session queries them with unquoted names and works correctly.

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
| `prod` | `application-prod.yml` | `Secure` session cookie, `forward-headers-strategy: native`, no DB fallbacks, INFO logging |

Shared settings live in `application.yml`.

### Health endpoints

```text
GET http://localhost:8080/api/v1/health
GET http://localhost:8080/actuator/health
```

The application health endpoint returns a minimal JSON document
(`status`, `service`, `timestamp`). Actuator exposes only `health`, with
details hidden.

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
- Wildcard origins cause startup failure.

### WebSocket

- Native WebSocket (no SockJS), endpoint `/ws`.
- Exact allowed origins from `APP_WEBSOCKET_ALLOWED_ORIGINS`.
- Empty or wildcard origin lists fail startup, matching the CORS guard.
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

- Local hostnames (`localhost`, `127.0.0.1`) default to
  `http://localhost:8080/api/v1`.
- Non-local deployments must define
  `window.ESTATEVISTA_CONFIG = { apiBaseUrl, webSocketUrl }` before the module
  scripts run.
- The API client (`api.js`) uses `credentials: "include"` and keeps the CSRF
  token in memory only. It does not use cookies, `localStorage`,
  `sessionStorage`, URLs, or logs for the token.

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

---

## 11. Known limitations and deferred work

- No authentication, registration, login, logout, or authorization rules.
- No business modules, property CRUD, chat handling, or Cloudinary integration.
- No WebSocket message handlers or STOMP authorization.
- CSRF design (SD-53) is not implemented; only framework defaults are active.
- Automated tests cover the health endpoint and the error-response contract
  (Stage 2.2); no authentication or business-flow tests exist yet.
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
| Token generation | Default is UUID-based (~122 bits) | Approved 256-bit generator still required |
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
masked value). It is **not** implemented in Stage 1.8. The authentication stage
must add the 256-bit generator, response-header writer, raw-header-compatible
request handler, post-rotation ordering, audit event, and the Section 17
security tests before the design can be marked complete.

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
Use an exact origin from `APP_CORS_ALLOWED_ORIGINS`. Wildcards are rejected.
Remember that `localhost` and `127.0.0.1` are different origins.

**Port already in use**
Change `SERVER_PORT` for the API or `FRONTEND_PORT` for the dev server. Update
`APP_CORS_ALLOWED_ORIGINS` and `APP_WEBSOCKET_ALLOWED_ORIGINS` if the frontend
origin changes.

**PostgreSQL authentication fails locally**
The installed service uses `scram-sha-256`. Set a known password for the
development role instead of weakening `pg_hba.conf`. Use the isolated cluster
approach only for throwaway validation.

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
authentication exists. Business schema work starts in Stage 2.3.
