# EstateVista V2 — Stage 3.1 Authentication Architecture & Security Assessment

| Field | Value |
|---|---|
| Sub-stage | 3.1 — Authentication Architecture & Security Assessment |
| Stage | 3 — Authentication & Authorization |
| Overall status | **COMPLETE — inspection and design only; no authentication implemented** |
| Execution agent | OpenCode |
| Execution date | 15 November 2026 |
| Feature branch | `stage-3-authentication` (created from `1edcd12` during this sub-stage, with owner approval) |
| Starting HEAD | `1edcd12` — `chore: exclude local stage validation reports` |
| Working tree at start | Clean (no modified, staged, or untracked files; no stash entries) |
| Working tree at end | Clean except this document |
| Source code changes | **None** |
| Migration changes | **None** |
| Dependency changes | **None** |
| Security configuration changes | **None** |
| Push / merge status | Not pushed; not merged |

---

## A. Executive summary

The repository at `1edcd12` contains a deliberately deny-by-default Spring Boot shell.
There is **no authentication implementation of any kind**: no `UserDetailsService`,
no `PasswordEncoder`, no authentication provider, no login/registration/logout
controller, no session registry, no CSRF token repository customization, and no
WebSocket handshake authentication.

What *does* exist is unusually complete preparatory work:

- The business schema (`V2`–`V14`) already models roles (`BUYER`, `AGENT`, `ADMIN`),
  users with `password_hash` and `account_status`, and agent profiles.
- The Spring Session JDBC schema (`V1`) exists and is owned by Flyway.
- The session cookie, idle timeout, and Spring Session wiring are configured.
- Exact-origin credentialed CORS with `X-CSRF-TOKEN` exposure is implemented and tested.
- The JSON error envelope for `401`, `403`, and CSRF rejection is implemented and tested.
- The WebSocket transport is configured but its handshake is correctly denied.
- The security design record (`docs/SECURITY_ARCHITECTURE.md`, revision 0.5) already
  approves the exact mechanism required here: server-side session, `HttpOnly` cookie,
  Spring Session JDBC, session-bound synchronizer CSRF token, BCrypt(12).

The assessment therefore finds **no architectural conflict** with the approved
security decisions. The gaps are implementation gaps plus a small number of
previously undocumented consequences of the current schema and configuration.

Findings of note (full register in §E.14):

1. `SPRING_SESSION.PRINCIPAL_NAME` is `VARCHAR(100)` while `users.email` is
   `VARCHAR(255)`. Using email as the Spring Security principal name risks a
   length failure. The principal name should be the user UUID (36 characters).
2. The approved **12-hour absolute session timeout** and **5-session concurrent cap**
   have no implementation mechanism in the current code.
3. `users.password_hash` is `NOT NULL`, but administrator-created agent accounts are
   `INACTIVE` until activation and have no password yet.
4. `POST /auth/logout` idempotency (return `204` when already expired) is in tension
   with the requirement that logout be CSRF-protected, because a session-bound CSRF
   token cannot be validated without a session.
5. CORS preflight cache is configured at 3600 s, while the approved baseline is 600 s.
6. The approved security header set (`Referrer-Policy: no-referrer`,
   `Permissions-Policy`, `Cache-Control: no-store` on auth responses) is not configured.

No test result, configuration value, or security guarantee in this document is invented.
Each factual claim is traceable to a repository path, a command output, or a specific
section of an existing approved document.

---

## B. Repository verification

### B.1 Verification results

| Check | Expected | Observed | Verdict |
|---|---|---|---|
| Branch | `stage-3-authentication` | `stage-2-backend-foundation` | **Mismatch (resolved)** |
| HEAD commit | `1edcd12` | `1edcd1244ef6238d729ee032d311a4a020d3841a` | Match |
| Working tree | Clean | `nothing to commit, working tree clean` | Match |
| Untracked files | None | None | Match |
| Stash | None | None | Match |
| `stage-3-authentication` existence | Exists | Did not exist locally or on `origin` | **Mismatch (resolved)** |
| Tracked files at HEAD | — | 147 | Recorded |

Commands: `git branch --show-current`, `git rev-parse HEAD`, `git status`,
`git status --porcelain=v1`, `git stash list`, `git branch -a -vv`, `git ls-files`.

### B.2 Discrepancy and resolution

The starting baseline **commit** matched `1edcd12`, but the repository was on
`stage-2-backend-foundation`, and `stage-3-authentication` did not exist in any form.
Per the sub-stage instruction, inspection stopped and the discrepancy was reported.

The repository owner authorized creating the expected branch at the correct baseline:

```text
git switch -c stage-3-authentication      # from clean HEAD 1edcd12
```

Result: branch `stage-3-authentication`, HEAD `1edcd12`, working tree clean.
This is a ref-only change; **no file content changed**.

### B.3 Related branches (not used)

| Branch | Tip | Relationship to `1edcd12` |
|---|---|---|
| `main` | `9e20ed5` | Does **not** contain `1edcd12` |
| `stage-2-homepage` | `e6bceca` | Unrelated lineage |
| `stage-3-property-listings` | `79a2817` | Different track (frontend listing work); does **not** contain `1edcd12` |
| `stage-4-details-and-contact` | `f3c1ae8` | Does **not** contain `1edcd12` |
| `stage-6-documentation-deployment` | `5459cb5` | Does **not** contain `1edcd12` |

`1edcd12` is an independent Stage 2 backend lineage. `stage-3-property-listings`
must not be confused with this sub-stage despite the similar name.

### B.4 Repository structure

```text
.github/workflows/backend-verify.yml     CI: Maven verify against postgres:18
backend/                                 Spring Boot 4.1.2 API
  pom.xml
  .env.example
  scripts/postgres-test-cluster.ps1
  src/main/java/com/estatevista/
  src/main/resources/                    application*.yml, db/migration/V1..V14
  src/test/java/com/estatevista/
docs/                                    PRD, FRD, architecture, API, DB, security
frontend/                                Static HTML/CSS/ES-module site
```

There is no Maven wrapper (`mvnw`) in the tracked tree; builds use the system `mvn`.

### B.5 Local toolchain observed

| Tool | Version |
|---|---|
| `java` | 23.0.2 (Maven ran under JDK 23) |
| `mvn` | 3.9.14 |
| Project target | Java 21 (`pom.xml`, `java.version`) |
| Spring Boot | 4.1.2 (`pom.xml` parent) |

The project compiles and tests successfully under JDK 23 with a Java 21 target.
No blocker, but CI pins Temurin 21 while local runs use 23; this difference is noted,
not changed.

---

## C. Existing backend architecture inventory

### C.1 Application entry point

| Item | Value |
|---|---|
| Entry point | `backend/src/main/java/com/estatevista/EstateVistaApplication.java` |
| Class | `com.estatevista.EstateVistaApplication` |
| Annotations | `@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)`, `@ConfigurationPropertiesScan` |
| Significance | Boot's generated in-memory user is **excluded**. Any future authentication must supply a real `UserDetailsService`. |

### C.2 Security configuration and filters

| Item | Path | Class | Current behaviour |
|---|---|---|---|
| Security filter chain | `backend/src/main/java/com/estatevista/config/SecurityConfig.java` | `SecurityConfig` | Deny-by-default; `IF_REQUIRED` sessions; `cors()` and `csrf()` with framework defaults; form login, HTTP Basic, and logout disabled |
| CORS | `backend/src/main/java/com/estatevista/config/CorsConfig.java` | `CorsConfig` | Exact-origin, credentialed CORS for `/api/**` and `/actuator/health` |
| Origin validation | `backend/src/main/java/com/estatevista/config/AllowedOrigins.java` | `AllowedOrigins` | Rejects wildcard, blank, non-HTTP, pathed, or unresolved origin values at startup |
| Properties | `backend/src/main/java/com/estatevista/config/AppProperties.java` | `AppProperties` | Binds `app.cors.*` and `app.websocket.*` |
| WebSocket transport | `backend/src/main/java/com/estatevista/config/WebSocketConfig.java` | `WebSocketConfig` | `/ws` STOMP endpoint with exact origin allowlist; no handlers, no handshake interceptor |

Current authorization rule (verbatim effect of `SecurityConfig`):

```text
permitAll:  /api/v1/health, /actuator/health, /error
anyRequest: denyAll
```

Session policy: `SessionCreationPolicy.IF_REQUIRED`.
Failure handling: custom `AuthenticationEntryPoint` → `401` JSON envelope;
custom `AccessDeniedHandler` → `403` JSON envelope. The access-denied handler is
also used by `CsrfFilter`, so tokenless mutations return the standard envelope.

**No other security filter exists.** There is no authentication filter, no
`UsernamePasswordAuthenticationFilter` registration, no `OncePerRequestFilter`,
no rate-limit filter, and no security audit filter.

### C.3 Authentication-related components

| Expected component | Status |
|---|---|
| `UserDetailsService` | **Absent** (Boot default explicitly excluded) |
| `PasswordEncoder` bean | **Absent** |
| `AuthenticationProvider` / `DaoAuthenticationProvider` | **Absent** |
| `AuthenticationManager` bean | **Absent** |
| Session registry / concurrency control | **Absent** |
| CSRF token repository / generator / header writer | **Absent** (framework defaults only) |
| Security context persistence configuration | **Absent** (framework default) |
| Audit/security event publisher | **Absent** |
| Login attempt / lockout store | **Absent** |

Evidence: repository-wide search for `UserDetailsService|PasswordEncoder|AuthenticationProvider|SecurityContextHolder|@PreAuthorize|GrantedAuthority|CsrfToken` returns only the exclusion import/Javadoc in `EstateVistaApplication.java` and a test method name.

### C.4 User-related entities and repositories

| Entity | Path | Table | Notes |
|---|---|---|---|
| `User` | `backend/src/main/java/com/estatevista/user/entity/User.java` | `users` | UUID id; lazy `@ManyToOne Role`; `passwordHash`; `AccountStatus`; `emailVerifiedAt`; `lastLoginAt`; timestamps |
| `Role` | `.../user/entity/Role.java` | `roles` | UUID id; unique `name` (max 30) |
| `AccountStatus` | `.../user/entity/AccountStatus.java` | — | `ACTIVE`, `INACTIVE`, `SUSPENDED` |
| `AgentProfile` | `.../user/entity/AgentProfile.java` | `agent_profiles` | One-to-one to `User` (`user_id` unique) |

| Repository | Path | Declared queries |
|---|---|---|
| `UserRepository` | `.../user/repository/UserRepository.java` | `findByEmailIgnoreCase(String)` |
| `RoleRepository` | `.../user/repository/RoleRepository.java` | `findByName(String)` |
| `AgentProfileRepository` | `.../user/repository/AgentProfileRepository.java` | none beyond `JpaRepository` |

Both auth-relevant lookups needed for login already exist.

### C.5 API controllers

| Controller | Path | Endpoint |
|---|---|---|
| `HealthController` | `backend/src/main/java/com/estatevista/common/health/HealthController.java` | `GET /api/v1/health` |
| `ApiErrorController` | `backend/src/main/java/com/estatevista/common/exception/ApiErrorController.java` | `${server.error.path:/error}` |

There is no authentication controller, no user controller, and no admin controller.

### C.6 Exception handling and error contract

| Item | Path | Behaviour |
|---|---|---|
| `GlobalExceptionHandler` | `backend/src/main/java/com/estatevista/common/exception/GlobalExceptionHandler.java` | `@RestControllerAdvice`; maps validation, malformed request, method/media errors, access denied, and unexpected errors to the envelope; logs no bodies or headers |
| `ApiException` | `.../common/exception/ApiException.java` | Base runtime exception carrying an `ErrorCode` |
| `ErrorCode` | `.../common/exception/ErrorCode.java` | `UNAUTHORIZED`, `FORBIDDEN`, `VALIDATION_ERROR`, `CONFLICT`, `TOO_MANY_REQUESTS`, `INTERNAL_ERROR`, etc. |
| `ApiErrorResponse` | `.../common/response/ApiErrorResponse.java` | `{success, error:{code,message,details}, timestamp, path}` |

`ErrorCode`'s Javadoc states business-specific codes must not be added prematurely.
Authentication failures must therefore reuse `UNAUTHORIZED` (with a generic message)
rather than inventing `INVALID_CREDENTIALS` in the shared transport enum.

There is **no success envelope class** yet; the documented `{success, message, data}`
shape exists only in `docs/API_SPECIFICATION.md`.

### C.7 Request validation

`spring-boot-starter-validation` is a dependency, and
`GlobalExceptionHandler` handles `MethodArgumentNotValidException`,
`HandlerMethodValidationException`, and `ConstraintViolationException`.

However, **no request DTO or validation annotation exists anywhere in
`src/main/java`**: a search for `@Valid|jakarta.validation|@NotNull|@Size|@Email`
finds only the `ConstraintViolationException` import in the exception handler.
Authentication request DTOs and their constraints must therefore be created from
scratch; the handler already supports field-level error details.

### C.8 Session configuration (current values)

| Setting | Value | Source |
|---|---|---|
| `server.servlet.session.timeout` | `30m` | `application.yml` |
| `spring.session.timeout` | `30m` | `application.yml` |
| `spring.session.jdbc.initialize-schema` | `never` (Flyway owns the schema) | `application.yml` |
| Cookie name | `EV_SESSION` | `application.yml` |
| `HttpOnly` | `true` | `application.yml` |
| `Secure` | `${SESSION_COOKIE_SECURE:false}`; `true` in `prod` | `application.yml`, `application-prod.yml` |
| `SameSite` | `lax` | `application.yml` |
| `Path` | `/` | `application.yml` |
| `Domain` | not set (host-only) | `application.yml` |
| Cookie prefix | not `__Host-` | `application.yml` |

### C.9 Environment and profile configuration

| File | Purpose | Notable current content |
|---|---|---|
| `backend/src/main/resources/application.yml` | Shared | Datasource defaults, JPA `ddl-auto: validate`, Flyway validate/no-clean, Actuator health-only, CORS/WS defaults, log levels |
| `application-dev.yml` | `dev` | `com.estatevista: DEBUG`, SQL logging still off |
| `application-prod.yml` | `prod` | No datasource fallbacks; `secure: true`; Hibernate SQL/bind/extract at `WARN` (SD-47) |
| `application-test.yml` | `test` | DB via `TEST_DB_*`; no usable password default |
| `backend/.env.example` | Template | Documents runtime variables; reserved `MAIL_*` and `CLOUDINARY_*` placeholders |

Datasource, Flyway, Hibernate validation, Spring Session JDBC, and Actuator are all
configured. There is **no `logback-spring.xml`** and no structured logging configuration.

### C.10 Frontend integration points

| File | Relevance |
|---|---|
| `frontend/assets/js/core/api.js` | `fetch` with `credentials: "include"`; in-memory `csrfToken`; attaches `X-CSRF-TOKEN` to non-GET; no storage of the token |
| `frontend/assets/js/core/config.js` | API/WS base URL resolution; local defaults to `http://localhost:8080/api/v1` |

There is no `auth.js`, no login form, and no session bootstrap call yet.
`api.js` already implements the client half of the approved CSRF contract
(excluding bootstrap wiring and the single re-bootstrap/retry behaviour).

---

## D. Database assessment

### D.1 Table inventory (verified from migrations, not assumed)

20 `CREATE TABLE` statements exist: **18 business tables** plus **2 framework tables**.

| # | Table | Migration | Domain |
|---|---|---|---|
| — | `SPRING_SESSION` | `V1__spring_session_schema.sql` | Framework (Spring Session JDBC) |
| — | `SPRING_SESSION_ATTRIBUTES` | `V1` | Framework |
| 1 | `roles` | `V2__create_roles_and_users.sql` | Identity |
| 2 | `users` | `V2` | Identity |
| 3 | `agent_profiles` | `V3__create_agent_profiles.sql` | Identity |
| 4 | `property_categories` | `V4` | Property |
| 5 | `property_subtypes` | `V4` | Property |
| 6 | `properties` | `V5` | Property |
| 7 | `property_residential_details` | `V5` | Property |
| 8 | `property_commercial_details` | `V5` | Property |
| 9 | `property_images` | `V6` | Property |
| 10 | `amenities` | `V7` | Property |
| 11 | `property_amenities` | `V7` | Property |
| 12 | `favorites` | `V8` | Buyer engagement |
| 13 | `inquiries` | `V9` | Buyer engagement |
| 14 | `conversations` | `V10` | Chat |
| 15 | `messages` | `V10` | Chat |
| 16 | `conversation_read_states` | `V11` | Chat |
| 17 | `notifications` | `V12` | Notification |
| 18 | `audit_logs` | `V13` | Audit |

`V14__add_business_indexes.sql` adds indexes only.

### D.2 Authentication-relevant schema (exact columns)

`roles` (`V2`):

| Column | Type | Constraint |
|---|---|---|
| `id` | `UUID` | PK |
| `name` | `VARCHAR(30)` | `NOT NULL`, `uk_roles_name` |
| `description` | `VARCHAR(255)` | nullable |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT now()` |

Seeded rows (`V2`, deterministic UUIDs):

| Role | UUID |
|---|---|
| `BUYER` | `00000000-0000-0000-0000-000000000001` |
| `AGENT` | `00000000-0000-0000-0000-000000000002` |
| `ADMIN` | `00000000-0000-0000-0000-000000000003` |

`users` (`V2`):

| Column | Type | Constraint |
|---|---|---|
| `id` | `UUID` | PK |
| `role_id` | `UUID` | `NOT NULL`, FK → `roles.id` `ON DELETE RESTRICT` |
| `first_name` | `VARCHAR(100)` | `NOT NULL` |
| `last_name` | `VARCHAR(100)` | `NOT NULL` |
| `email` | `VARCHAR(255)` | `NOT NULL` |
| `password_hash` | `VARCHAR(255)` | `NOT NULL` |
| `phone` | `VARCHAR(20)` | nullable |
| `account_status` | `VARCHAR(30)` | `NOT NULL`, CHECK in (`ACTIVE`,`INACTIVE`,`SUSPENDED`) |
| `email_verified_at` | `TIMESTAMPTZ` | nullable |
| `last_login_at` | `TIMESTAMPTZ` | nullable |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT now()` |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT now()` |

Additional objects:

- `CREATE UNIQUE INDEX uk_users_email ON users (LOWER(email))` — case-insensitive
  uniqueness. The column itself is **not** declared `UNIQUE`; uniqueness is the
  functional index only.
- `idx_users_role_id`, `idx_users_account_status` (`V14`).

`agent_profiles` (`V3`): `id` PK, `user_id` `NOT NULL` + `uk_agent_profiles_user_id`
+ FK → `users.id` `ON DELETE RESTRICT`, `agency_name VARCHAR(150)`,
`license_number VARCHAR(100)`, `bio TEXT`, `profile_image_url TEXT`, timestamps.

`SPRING_SESSION` (`V1`):

| Column | Type | Notes |
|---|---|---|
| `PRIMARY_ID` | `CHAR(36)` | PK |
| `SESSION_ID` | `CHAR(36)` | unique index `SPRING_SESSION_IX1` |
| `CREATION_TIME` | `BIGINT` | |
| `LAST_ACCESS_TIME` | `BIGINT` | |
| `MAX_INACTIVE_INTERVAL` | `INT` | per-session idle timeout |
| `EXPIRY_TIME` | `BIGINT` | index `SPRING_SESSION_IX2` |
| `PRINCIPAL_NAME` | `VARCHAR(100)` | index `SPRING_SESSION_IX3` |

`SPRING_SESSION_ATTRIBUTES`: `(SESSION_PRIMARY_ID, ATTRIBUTE_NAME)` PK,
`ATTRIBUTE_BYTES BYTEA`, FK → `SPRING_SESSION` `ON DELETE CASCADE`.

### D.3 Foreign keys and delete behaviour (identity-relevant)

| Column | References | On delete |
|---|---|---|
| `users.role_id` | `roles.id` | `RESTRICT` |
| `agent_profiles.user_id` | `users.id` | `RESTRICT` |
| `properties.primary_agent_id`, `properties.created_by` | `users.id` | `RESTRICT` |
| `properties.updated_by` | `users.id` | `SET NULL` |
| `favorites.buyer_id` | `users.id` | `CASCADE` |
| `inquiries.buyer_id`, `inquiries.assigned_agent_id` | `users.id` | `RESTRICT` |
| `conversations.buyer_id`, `conversations.assigned_agent_id` | `users.id` | `RESTRICT` |
| `messages.sender_id` | `users.id` | `RESTRICT` |
| `conversation_read_states.user_id` | `users.id` | `CASCADE` |
| `notifications.recipient_id` | `users.id` | `CASCADE` |
| `audit_logs.actor_id` | `users.id` | `SET NULL` |

Consequence: a user row cannot be hard-deleted while owning properties, inquiries,
conversations, or messages. This is consistent with the approved "deactivate, never
delete" policy (SD-17) and reinforces that account status, not row deletion, is the
access-control lever.

### D.4 Answers to the specific questions

| Question | Evidence-based answer |
|---|---|
| Does a user table exist? | Yes — `users` (`V2`) with a matching JPA entity. |
| Existing user-related columns? | Listed in §D.2. Includes `first_name`, `last_name`, `email`, `phone`, `account_status`, `email_verified_at`, `last_login_at`, timestamps. |
| Password storage provisions? | `users.password_hash VARCHAR(255) NOT NULL`. No encoder, no policy enforcement, no rehash column. 255 is sufficient for BCrypt (60 chars) and Argon2 encodings. |
| Role representation? | Single primary role per user via `users.role_id` FK to `roles`; three seeded roles; no permission table, no many-to-many. |
| Account status representation? | `users.account_status` text with a CHECK constraint and a matching Java enum. |
| Unique constraints? | `uk_roles_name`; `uk_users_email` on `LOWER(email)`; `uk_agent_profiles_user_id`; plus business-domain uniques (`uk_properties_slug`, `uk_favorites_buyer_property`, `uk_conversations_buyer_property`, etc.). |
| Foreign-key relationships? | See §D.3; all identity FKs point at `users.id`. |
| Existing indexes? | `V14` creates `idx_users_role_id` and `idx_users_account_status`; `V1` indexes session id, expiry, and principal name; `V2` unique email index. |

**Not present anywhere:** password reset/activation token table, login-attempt or
lockout table, `credentials_updated_at`, MFA secret, role-permission mapping,
session revocation table, soft-delete flags.

### D.5 Required authentication schema changes (proposed, not created)

Next free Flyway version is **V15**. Migrations `V1`–`V14` must not be edited
(`validate-on-migrate: true`, `clean-disabled: true`).

| # | Change | Required? | Purpose |
|---|---|---|---|
| 1 | `account_tokens` table: `id UUID PK`, `user_id UUID NOT NULL` FK → `users.id ON DELETE CASCADE`, `token_hash CHAR(64) NOT NULL UNIQUE` (SHA-256 hex), `purpose VARCHAR(30) NOT NULL` CHECK in (`PASSWORD_RESET`,`ACCOUNT_ACTIVATION`), `expires_at TIMESTAMPTZ NOT NULL`, `used_at TIMESTAMPTZ`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `requested_ip INET` | **Yes** for reset/activation | SD-11/SD-12; no raw token is ever stored |
| 2 | Indexes: `idx_account_tokens_user_purpose (user_id, purpose)`, `idx_account_tokens_expires_at (expires_at)` | Yes | Lookup and cleanup |
| 3 | Login-attempt persistence: `login_attempts` (`id`, `email_hash CHAR(64)`, `user_id UUID NULL`, `ip_address INET`, `attempted_at TIMESTAMPTZ`, `successful BOOLEAN`, `reason_code VARCHAR(40)`) with indexes on `(email_hash, attempted_at)` and `(ip_address, attempted_at)` | **Yes** for SD-13 baselines | Survives restart and multiple instances; never stores raw email or password |
| 4 | `users.credentials_updated_at TIMESTAMPTZ` | Optional | Invalidates sessions/tokens issued before a credential change; useful defence in depth |
| 5 | `users.password_hash` NULLable **or** a documented unusable-hash sentinel | Decision required (OD-3) | Administrator-created `INACTIVE` agents have no password yet, but the column is `NOT NULL` |
| 6 | Widen `SPRING_SESSION.PRINCIPAL_NAME` to `VARCHAR(255)` | **Only if** email is chosen as principal name | Avoids 100-char truncation; not needed if the UUID is used (recommended) |
| 7 | Role/status lookup indexes | No change needed | Already present |

Explicitly **not** proposed: any JWT, refresh-token, API-key, or stateless-auth table.
Those remain prohibited.

---

## E. Security architecture assessment

### E.1 Deny-by-default

`SecurityConfig` permits only `GET /api/v1/health`, `GET /actuator/health`, and the
error dispatch; everything else is `denyAll()`. Verified by tests
`unauthenticatedRequestReturnsUnauthorizedEnvelope`,
`authenticatedRequestDeniedByPolicyReturnsForbiddenEnvelope`,
`nonExposedActuatorPathsAreDeniedByDefault`, and
`websocketHandshakePathRemainsDenied` in
`backend/src/test/java/com/estatevista/config/SecurityFilterChainTest.java`.

Assessment: **strong, correct starting posture.** The risk in Stage 3.2 is
regression through broad `permitAll` matchers. The approved rule is that every
`permitAll` entry must be exact-method, exact-path, and justified.

### E.2 Session-based authentication

`SessionCreationPolicy.IF_REQUIRED` matches the approved design (session created on
successful login, not on every request). No sessions are currently created because
no endpoint authenticates.

Session fixation protection is **not explicitly configured**. The pinned framework's
default strategy applies, but it has no test coverage in this repository and is not
documented in `SecurityConfig`. Stage 3.2 must configure it explicitly and test it.

### E.3 Spring Session JDBC

`spring-boot-starter-session-jdbc` is a declared dependency
(`pom.xml`), `spring.session.jdbc.initialize-schema: never`, and the schema is
Flyway-owned (`V1`). The `pom.xml` comment records why the Boot 4 starter (not the
raw `spring-session-jdbc` artifact) is required.

Assessment: **correctly wired for durable, revocable server-side sessions.**
Revocation by principal will depend on `PRINCIPAL_NAME`, which is why the principal
choice in §E.14/D-1 matters.

### E.4 Session cookie

Current cookie configuration matches the approved shape with three exceptions:

| Approved | Current | Gap |
|---|---|---|
| `EV_SESSION`, `HttpOnly`, `Path=/`, `SameSite=Lax`, host-only, `Secure` in production | Satisfied | None |
| `__Host-` prefix recommended hardening (SD-02) | Not applied | **Gap**; requires `Secure`, so it cannot be used for plain-HTTP local development without profile-specific naming |
| Session cookie only; no CSRF cookie | Satisfied (no CSRF cookie exists) | None |
| Idle 30 min | Satisfied (`server.servlet.session.timeout`, `spring.session.timeout`) | None |
| Absolute 12 h | **No mechanism** | **Gap**: Spring Session has no absolute timeout; requires an authenticated-at attribute plus filter/scheduled cleanup |
| Admin idle 15 min (SD-49) | Not implemented | **Gap**: can be implemented per-session via `MAX_INACTIVE_INTERVAL` on login |

### E.5 CSRF (current state vs SD-53)

Current: `http.csrf(Customizer.withDefaults())`.

Therefore the active mechanism is the framework default: session-backed
`HttpSessionCsrfTokenRepository`, default `X-CSRF-TOKEN` header, default
`XorCsrfTokenRequestAttributeHandler`, default UUID-based (~122-bit) token, no
response-header delivery, and no exemption matchers.

Consequences today:

- A tokenless mutating request returns the `403 FORBIDDEN` JSON envelope.
  Verified by `mutatingRequestWithoutCsrfTokenReturnsForbiddenEnvelope`.
- Because `anyRequest().denyAll()` is in force, CSRF exemptions for
  `/auth/register`, `/auth/login`, `/auth/forgot-password`, and
  `/auth/reset-password` do **not** exist yet and are not needed yet.
- The approved SD-53 requirements are **not met**: no 256-bit generator, no
  `X-CSRF-TOKEN` response-header writer, no raw-header handler, no post-authentication
  rotation ordering, no audit event.
- `DEVELOPMENT_SETUP.md` §12 records that `csrf.spa()` in 7.1.2 unconditionally
  installs `CookieCsrfTokenRepository.withHttpOnlyFalse()` and therefore must not be
  used as-is, and that the default XOR handler must not be paired with a raw header token.

`X-CSRF-TOKEN` is already exposed through CORS and allowed as a request header,
so the transport half of the contract is in place.

### E.6 CORS

Implemented and tested:

| Setting | Current value |
|---|---|
| Allowed origins | Exact list from `APP_CORS_ALLOWED_ORIGINS`; startup fails on wildcard/blank/invalid |
| Methods | `GET, POST, PUT, PATCH, DELETE, OPTIONS` |
| Allowed headers | `Content-Type`, `X-CSRF-TOKEN`, `Accept`, `Origin`, `X-Requested-With` |
| Exposed headers | `X-CSRF-TOKEN` |
| Credentials | `true` |
| Max age | `3600` |
| Scope | `/api/**`, `/actuator/health` |

Assessment: functionally correct for Option C, with two documented deviations from
the approved baseline: preflight cache is **3600 s vs 600 s approved**, and
`X-Requested-With` is allowed though not in the approved header list (low risk, but
it is unnecessary surface). `Vary: Origin` is emitted by Spring's CORS processing.

### E.7 Security headers

`SecurityConfig` does not call `.headers(...)`. Only framework defaults apply.
The approved API header set is therefore **partially unimplemented**:

| Approved | Status |
|---|---|
| `X-Content-Type-Options: nosniff` | Framework default header writers are active; not explicitly declared |
| `X-Frame-Options: DENY` | Framework default header writers are active; not explicitly declared |
| `Cache-Control: no-store` on auth/authenticated/error responses | Not declared in configuration and not covered by any test. Framework cache-control defaults apply, but the approved explicit policy for auth responses is unverified |
| `Referrer-Policy: no-referrer` | **Not configured** on API responses |
| `Permissions-Policy` restrictive | **Not configured** |
| HSTS | Framework default, HTTPS-only; production policy not explicitly declared |
| `Server` banner suppression | Not controlled at the application layer |

No guarantee is claimed for headers that are not explicitly configured and tested.
Stage 3.2 must declare the approved header set and assert it in tests rather than rely
on framework defaults that a future configuration change could alter.

### E.8 Actuator exposure

```yaml
management.endpoints.web.exposure.include: health
management.endpoint.health.show-details: never
```

`/actuator/health` is public (explicit matcher); every other actuator path falls to
`denyAll()` and returns the `401` envelope, verified by two tests. Assessment:
**compliant with SD-47's actuator restriction.**

### E.9 WebSocket security

`WebSocketConfig` registers `/ws`, enables a simple broker on `/queue` and `/topic`,
sets `/app` and `/user` prefixes, and enforces exact allowed origins. There are:

- no handshake interceptor (no session/principal resolution),
- no STOMP `ChannelInterceptor` (no CONNECT/SUBSCRIBE/SEND authorization),
- no message handlers,
- and the handshake path is **denied** by the security chain.

Assessment: **correctly closed.** Stage 3.2 must not open `/ws` casually. Opening it
without handshake authentication and CHANNEL authorization would be a High-severity
regression. Full STOMP authorization belongs to the messaging stage; the minimum
Stage 3.2 obligation is to keep it denied or to implement handshake authentication
plus a deny-by-default channel interceptor.

### E.10 Authentication failure handling

| Case | Current behaviour | Compliance |
|---|---|---|
| No session on protected route | `401` JSON envelope via `AuthenticationEntryPoint` | Compliant |
| CSRF token missing/invalid | `403` JSON envelope via `AccessDeniedHandler` | Compliant shape; audit event missing |
| Authenticated but denied | `403` JSON envelope | Compliant |
| Unexpected server error | `500` envelope, no stack trace/message | Compliant (SD-47) |
| Rate limit | `429` code exists; no limiter exists | **Not implemented** |

The existing handlers give Stage 3.2 a consistent error contract to reuse. No HTML
redirect exists anywhere in the API path.

### E.11 SD-44 — Logging policy

**Status: PARTIAL (unchanged from the Stage 2.8 verdict).**

Evidence:

- `application.yml` sets INFO levels; `application-prod.yml` pins
  `org.hibernate.SQL`, `org.hibernate.orm.jdbc.bind`, and
  `org.hibernate.orm.jdbc.extract` to `WARN`.
- `GlobalExceptionHandler` logs failure categories only and explicitly avoids logging
  bodies, headers, and framework parse messages (comments cite SD-44).
- `server.error.include-stacktrace/message/binding-errors: never`.

Missing for authentication:

- No logback configuration, therefore no pattern-level redaction.
- No defined retention.
- No structured audit/security event pipeline; `audit_logs` exists with an entity and
  repository but **no writer service**.
- No correlation-ID filter, although the schema has `request_id` and the security
  document requires correlation IDs.

Stage 3.2 risk: authentication code is exactly where raw emails, passwords, tokens,
session IDs, and CSRF values are most likely to leak into logs. The assessment
requires an explicit logging rule set before the first auth endpoint is merged.

### E.12 SD-47 — Existing security controls

**Status: VERIFIED FOR EXISTING CONTROLS; per-release verification remains.**

| Control | Evidence | Verdict |
|---|---|---|
| No stack traces/messages in responses | `application.yml`; `ApiErrorResponse`; tests | Verified |
| SQL/bind logging off in production | `application-prod.yml` | Verified by configuration |
| Actuator restricted to health, details hidden | `application.yml`; tests | Verified |
| Swagger/OpenAPI absent | No dependency, no config | Verified absent |
| Debug endpoints | None present | Verified absent |
| JSON error page instead of HTML | `ApiErrorController`; tests | Verified |
| `show-sql` false in all profiles | `application*.yml` | Verified |
| Session cookie `Secure` in production | `application-prod.yml` | Verified by configuration |

No new SD-47 regression was introduced by this sub-stage because no code changed.

### E.13 SD-53 — Session-bound CSRF

**Status: APPROVED AS DESIGN, NOT IMPLEMENTED.** This sub-stage adds the
implementation plan (§F.9), not the implementation.

Requirements restated from the approved record, against the actual pinned versions:

| Element | Approved requirement | 7.1.2 reality (`DEVELOPMENT_SETUP.md` §12) |
|---|---|---|
| Storage | Session attribute, never a cookie | `HttpSessionCsrfTokenRepository` is session-backed |
| Generation | ≥ 256 bits from `SecureRandom` | Default is UUID (~122 bits) → custom generator required |
| Header name | `X-CSRF-TOKEN` | Default already correct |
| Request handler | Resolve the **raw** header token | Default XOR handler expects masked input → plain `CsrfTokenRequestAttributeHandler` (or masked output) required |
| Response delivery | `X-CSRF-TOKEN` on login and `/auth/me` | Not provided → custom writer required |
| SPA shorthand | Must not be used blindly | `csrf.spa()` installs a cookie repository → prohibited |
| Rotation | After login, session-ID rotation, privilege change, password change | `CsrfAuthenticationStrategy` exists; ordering must be wired and tested |
| Failure | JSON `403`, no state change, audit event | JSON shape exists; audit event missing |
| Exemptions | Only 4 unauthenticated auth POSTs and the WS handshake | Not yet configured |
| CORS | Expose and allow `X-CSRF-TOKEN` | Already configured and tested |

No design change is proposed. The sub-stage confirms the design is implementable
unchanged on Spring Security 7.1.2 and identifies the exact wiring points.

### E.14 Deviation and conflict register

| ID | Finding | Severity | Evidence | Recommended resolution |
|---|---|---|---|---|
| D-1 | `SPRING_SESSION.PRINCIPAL_NAME` is `VARCHAR(100)`; `users.email` is `VARCHAR(255)` | Medium | `V1` vs `V2` | Use the user UUID string (36 chars) as the principal name; do not widen the column |
| D-2 | Approved 12 h absolute session timeout has no mechanism | Medium | `SECURITY_ARCHITECTURE.md` §8.4 vs no code/config | Store `authenticatedAt`; enforce in a filter; test |
| D-3 | Approved 5-session concurrent cap not implemented | Low | §8.4 vs no `SessionRegistry` | Use `SpringSessionBackedSessionRegistry` with `maximumSessions(5)` |
| D-4 | Admin 15-min idle timeout (SD-49) not implemented | Low | §18.2 SD-49 vs `application.yml` | Set `MaxInactiveInterval` per session on admin login |
| D-5 | `users.password_hash` is `NOT NULL` but unactivated agents have no password | Medium | `V2` vs §9.5 | Prefer a documented unusable-hash sentinel + status gate; alternative is a nullable column. **Decision required** |
| D-6 | Logout idempotency (`204` when already expired) conflicts with mandatory session-bound CSRF | Low | §8.8.3(6) vs §8.5.10 | Require authentication for logout; return `401` when no session. **Decision required** |
| D-7 | CORS preflight `maxAge` is 3600 s; approved baseline is 600 s | Low | `CorsConfig.java` vs §8.5.5 | Align to 600 s when CORS is next touched |
| D-8 | CORS allows `X-Requested-With`, which is not in the approved header list | Low | `CorsConfig.java` | Remove unless a concrete need appears |
| D-9 | Approved security headers not explicitly configured or tested (`Referrer-Policy`, `Permissions-Policy`, `no-store` on auth) | Medium | no `.headers(...)` in `SecurityConfig` | Declare and test the approved header set with the auth endpoints |
| D-10 | `__Host-` cookie prefix hardening not applied | Low | `application.yml` cookie name | Evaluate profile-specific naming; document if deferred |
| D-11 | No logback/redaction config; no audit writer despite `audit_logs` | Medium | resources tree; no service class | Add logging rules and an audit writer before auth merge |
| D-12 | API specification defines no body for `PATCH /users/me/password` | Low | `API_SPECIFICATION.md` §4.1 vs §9.3 | Adopt the proposed contract in §G.6 (current + new + confirmation) |
| D-13 | No success-envelope type exists although the API documents one | Low | only `ApiErrorResponse` exists | Add an `ApiResponse`-style record when the first success endpoint is built |
| D-14 | Local build uses JDK 23; CI pins Java 21 | Informational | `java -version`; workflow | No action; keep CI authoritative |

None of these blocks the Stage 3.2 start; D-5 and D-6 require an explicit decision
recorded before the corresponding endpoint ships.

---

## F. Authentication architecture proposal

This section proposes the implementation shape. It preserves every approved decision:
server-side sessions, Spring Session JDBC, session-bound CSRF, `HttpOnly` cookie,
BCrypt(12), and role-based access control with ownership checks. **JWT and stateless
authentication remain prohibited.**

### F.1 Proposed component map

| Responsibility | Proposed class (package) | Notes |
|---|---|---|
| Principal adapter | `com.estatevista.auth.security.EstateVistaUserDetails` | Wraps `User`; exposes UUID, email, `ACCOUNT_STATUS`, and a single `ROLE_<name>` authority |
| User loading | `com.estatevista.auth.security.EstateVistaUserDetailsService` | Implements `UserDetailsService`; `userRepository.findByEmailIgnoreCase`; normalizes input; `isEnabled` = `ACTIVE` only |
| Password encoding | `com.estatevista.auth.security.PasswordConfig` | `DelegatingPasswordEncoder` with `{bcrypt}` default, `BCryptPasswordEncoder(12)` |
| Authentication provider | framework `DaoAuthenticationProvider` | No custom provider unless a real need appears |
| Registration | `com.estatevista.auth.AuthController` + `AuthService` | Forces `BUYER` |
| Session lifecycle | `com.estatevista.auth.session.SessionLifecycleService` | Fixation, absolute timeout, revocation by principal, admin idle override |
| Absolute timeout enforcement | `com.estatevista.auth.session.AbsoluteTimeoutFilter` | Checks `authenticatedAt` |
| CSRF storage/generation | `com.estatevista.auth.csrf.SessionCsrfTokenRepository` | 256-bit `SecureRandom`, session attribute, no cookie |
| CSRF header delivery | `com.estatevista.auth.csrf.CsrfTokenHeaderWriter` | Writes `X-CSRF-TOKEN` on login and `/auth/me` |
| Auth failure audit | `com.estatevista.audit.AuditService` | Writes to `audit_logs`; no secrets |
| Login throttling | `com.estatevista.auth.security.LoginAttemptService` | Backed by the proposed `login_attempts` table |
| Admin operations | `com.estatevista.admin.AdminUserController` / `AdminAgentController` | `@PreAuthorize("hasRole('ADMIN')")` plus service checks |

Package placement follows the existing feature-package convention
(`com.estatevista.<domain>.<layer>`).

### F.2 Registration

**Buyer self-registration only.**

1. `POST /api/v1/auth/register`; no session, CSRF-exempt but Origin-validated.
2. Rate limit by IP (5/hour baseline) — requires the throttling store decision (OD-6).
3. Validate: required names, email format and length ≤ 255, password policy (§F.10),
   optional phone ≤ 20. Reject unknown fields (`FAIL_ON_UNKNOWN_PROPERTIES`).
4. Normalize email: `trim()` then `toLowerCase(Locale.ROOT)`. The unique index is on
   `LOWER(email)`, so normalization and lookup must agree.
5. Force role: `roleRepository.findByName("BUYER")`; any client-supplied `role`,
   `accountStatus`, `id`, or `passwordHash` is ignored or rejected.
6. Hash with the delegating encoder; never log or return it.
7. Persist with `account_status = ACTIVE` (email verification deferred, SD-16).
8. Map `DataIntegrityViolationException` on `uk_users_email` to `409 CONFLICT`.
   The duplicate-email disclosure is a documented, UI-driven trade-off (§9.9).
9. Do **not** create a session and do **not** auto-login.
10. Return `201` with safe fields only.
11. Audit `USER_REGISTERED` without the password or raw email in the message.

### F.3 Administrator-controlled agent creation and activation

1. `POST /api/v1/admin/agents` (`ADMIN`, CSRF required).
2. Create `users` row with role `AGENT`, `account_status = INACTIVE`, and the
   password-hash strategy decided in OD-3 (sentinel hash recommended; the account
   cannot authenticate because status is not `ACTIVE` **and** the plaintext is unknown).
3. Create the linked `agent_profiles` row in the same transaction (one-to-one is
   enforced by `uk_agent_profiles_user_id`).
4. Generate a 256-bit activation token, store only its SHA-256 hash with
   `purpose = ACCOUNT_ACTIVATION` and a 72-hour expiry.
5. Email the activation link to the agent's address; never return the token in the
   response and never log it. **Blocked on the email provider (OD-2).**
6. The agent redeems the token through `POST /api/v1/auth/reset-password`
   (purpose-scoped, no duplicate endpoint), sets a policy-compliant password, and the
   backend flips `account_status` to `ACTIVE` in the same transaction.
7. No administrator ever knows or sets an agent password; no temporary password exists.
8. If email delivery fails, the account stays `INACTIVE` and the administrator may
   retry provisioning; there is no fallback to a predictable credential.

Activation token redemption must verify **purpose** so that an activation token can
never reset an active account and a reset token can never activate an account.

### F.4 Administrator account provisioning

There is **no API path to create an administrator** and none should be added.
Approved rule: controlled deployment/seed process, individual accounts, no shared
credentials. Options:

| Option | Assessment |
|---|---|
| A. Flyway data migration containing a BCrypt hash | Rejected: a credential hash enters version control and cannot be rotated cleanly |
| B. One-time operator script/CLI that reads the initial password from an environment variable and writes `users` + `roles` link using the production encoder | **Recommended**; idempotent, auditable, no secret in Git |
| C. Documented SQL runbook executed by the DBA with a hash generated locally | Acceptable fallback; manual and easy to get wrong |

This is **OD-1** and blocks any end-to-end admin workflow until decided.

### F.5 Login

1. `POST /api/v1/auth/login`; no session; CSRF-exempt; Origin-validated.
2. Rate limit by IP and normalized email; `429` with `Retry-After` when exceeded.
3. Load by normalized email; if absent, perform a dummy password comparison to
   reduce timing signal.
4. Reject `INACTIVE` and `SUSPENDED` with the **same** generic failure as bad
   credentials: `401` and message "Email or password is incorrect."
5. Verify with the encoder (constant-time comparison inside the encoder).
6. On failure: increment the attempt counter, audit a reason code, return `401`.
   Never reveal which field failed.
7. On success: reset counters, update `last_login_at`, rotate the session ID, store
   `authenticatedAt`, set the principal, generate a new session-bound CSRF token,
   and write it to the `X-CSRF-TOKEN` response header **after** rotation.
8. Set the cookie implicitly through the container; never emit `Set-Cookie` manually
   and never place the session ID or token in the body.
9. Return the approved user DTO.
10. For `ADMIN`, set the session's `MaxInactiveInterval` to 15 minutes (SD-49).
11. Do not include `passwordHash`, `emailVerifiedAt`, role IDs, or audit fields.

### F.6 Session creation, expiration, invalidation, fixation, concurrency

| Control | Proposed implementation |
|---|---|
| Creation | Only on successful login. Registration creates no session. |
| Idle expiration | 30 min (`server.servlet.session.timeout` / `spring.session.timeout`); admin sessions 15 min |
| Absolute expiration | 12 h from `authenticatedAt`, enforced by `AbsoluteTimeoutFilter`; on violation invalidate and return `401` |
| Invalidation on logout | `Session.invalidate()` + `HttpSessionEventPublisher`/`SessionDestroyedEvent` handling; Spring Session deletes the row |
| Invalidation on deactivation/suspension | `FindByIndexNameSessionRepository.findByPrincipalName(uuid)` → delete all sessions |
| Invalidation on password change | Delete all other sessions; keep and rotate the current one |
| Fixation | Explicit `sessionManagement().sessionFixation().changeSessionId()` and a test asserting the ID changes on login |
| Concurrent sessions | `maximumSessions(5).maxSessionsPreventsLogin(false)` with `SpringSessionBackedSessionRegistry`; oldest expires |
| Principal name | **User UUID** (36 chars) — satisfies `VARCHAR(100)` and enables revocation by principal |
| Session store outage | Fail closed: `401`/`503`, never an anonymous session |

Because sessions are JWTs-in-disguise nowhere: the cookie holds only an opaque
identifier. There is no token parsing, signing, or client-side authority.

### F.7 Logout

1. `POST /api/v1/auth/logout`; authenticated; **CSRF required**.
2. Invalidate the server-side session; the CSRF token and all session attributes are
   destroyed with it.
3. Clear the `EV_SESSION` cookie (`Max-Age=0`, same path/host); no CSRF cookie exists.
4. Clear the security context.
5. Emit a session-destroyed event so the future WebSocket layer closes the socket
   (do not assume invalidation closes an established socket).
6. Respond `204 No Content`.
7. If no valid session exists, the entry point returns `401`. This is the chosen
   resolution of D-6: a session-bound CSRF token cannot be validated without a
   session, and `401` discloses nothing beyond "no valid session".

### F.8 Authorization model

**Roles are server-side only. Frontend role information is never an authority input.**

| Layer | Rule |
|---|---|
| Route layer | `@PreAuthorize`/matcher by role: `/api/v1/admin/**` → `ADMIN`; `/api/v1/agents/me/**` → `AGENT`; buyer mutations → `BUYER`; shared participant routes require authentication + ownership |
| Service layer | Ownership/assignment predicates: `buyer_id == principal`, `primary_agent_id == principal`, conversation participant, inquiry assignee |
| Repository layer | Scoped finders (e.g. `findByIdAndBuyerId`) so an unscoped `findById` cannot leak |
| Database layer | Existing uniques/FKs constrain duplicates and orphans |
| Presentation layer | Hides controls only; never a boundary |

Role representation:

- `roles.name` ∈ {`BUYER`, `AGENT`, `ADMIN`} seeded by `V2` with fixed UUIDs.
- Spring Security authority = `ROLE_` + role name, granting `hasRole('BUYER')` etc.
- One primary role per user (`users.role_id`); no permission table, no multi-role.
- Status is **not** an authority. It gates authentication (`isEnabled`) and is
  re-checked server-side; it is never accepted from a request.

Concrete role behaviour:

| Role | Can | Cannot |
|---|---|---|
| `BUYER` | Own profile, own favorites/inquiries/conversations/notifications, buyer dashboard | Agent/admin routes; other buyers' records; create properties |
| `AGENT` | Own agent profile, assigned properties, assigned inquiries/conversations, agent dashboard | Create/reassign properties; admin data; other agents' records |
| `ADMIN` | User/agent/property/inquiry management, assignments, audit read, platform dashboards | Impersonate; change own role; read passwords/hashes; read conversation transcripts |

Registration can only create `BUYER`; agent creation can only create `AGENT`;
nothing can create `ADMIN` at runtime. Any `role` field in a request body is a
mass-assignment attack and must be rejected by DTO design, not merely ignored.

### F.9 CSRF (session-bound synchronizer)

**Storage and generation**

- Custom `SessionCsrfTokenRepository` (wrap or extend `HttpSessionCsrfTokenRepository`)
  storing `CsrfToken` under a namespaced session key.
- Token = 32 bytes from `SecureRandom`, Base64url-encoded without padding (256 bits).
- Never a cookie; never in the body; never in a URL/query string/STOMP frame.
- Installed against the session so `spring_session_attributes` persists it; no new table.

**Request validation**

- Header name `X-CSRF-TOKEN`.
- Use a plain `CsrfTokenRequestAttributeHandler` (or an equivalent raw-header
  resolver). Do **not** pair the default XOR handler with a raw header token.
- Compare the header value to the session value; reject on missing/mismatch/expired.

**Delivery**

- `X-CSRF-TOKEN` response header on successful login (after rotation) and on
  `GET /auth/me` (lazy generation if absent).
- CORS already allows and exposes the header.
- Response also sets `Cache-Control: no-store`.

**Exemptions** (exact method + path; nothing broader):

```text
POST /api/v1/auth/register
POST /api/v1/auth/login
POST /api/v1/auth/forgot-password
POST /api/v1/auth/reset-password
```

Everything else that mutates requires the token, including `POST /auth/logout` and
`PATCH /users/me/password`. `/ws` is a `GET` handshake and is **not** CSRF-exempt
from a security-matcher standpoint in a way that opens it; it must remain
unreachable until handshake authentication exists.

**Origin/Referer** validation is required on every state-changing request, including
the exempt auth POSTs. Missing both `Origin` and `Referer` on a mutating browser
request is rejected. This is independent of, and additional to, token validation.

**Rotation**: on login, session-ID rotation, privilege change, and password change.
**Destruction**: with the session on logout, expiry, deactivation, suspension.

**Failure**: `403` JSON envelope, no state change, audit `CSRF_REJECTED`.

**Frontend**: `api.js` already holds the token in memory and attaches it; the missing
pieces are the `/auth/me` bootstrap call, `setCsrfToken` invocation from the response
header, and the single re-bootstrap/retry-on-403 behaviour. No token storage is
permitted in `localStorage`, `sessionStorage`, IndexedDB, or a cookie.

### F.10 Password security

| Control | Proposal |
|---|---|
| Encoding | `DelegatingPasswordEncoder` with `{bcrypt}` prefix and `BCryptPasswordEncoder(12)` |
| Why not Argon2id now | Verified: **no BouncyCastle on the dependency tree**, so Argon2 would require a new dependency and an approved change. BCrypt is available in `spring-security-crypto` 7.1.2 already resolved |
| Storage | Only the hash in `users.password_hash` (VARCHAR(255)); never plaintext, never reversible |
| Comparison | Encoder's constant-time check; never `String.equals` |
| Input limits | 10–64 characters, ≤ 72 UTF-8 **bytes** (reject rather than truncate), reject `U+0000`, accept Unicode without normalization |
| Composition | No forced character classes; reject common/breached passwords and context words if a local blocklist is approved (OD-5) |
| Rehash | On successful login, upgrade older cost/algorithm and persist; audit without the secret |
| Change | Require current password + new + confirmation; reject reuse; rotate current session; invalidate other sessions; issue a new CSRF token |
| Reset | Purpose-scoped token (§F.3/F.5), 30 min for reset, 72 h for activation, single use, SHA-256 at rest, all sessions revoked |
| Logging | Never log passwords, hashes, reset/activation tokens, session IDs, or CSRF values |
| Timing | Dummy hash for unknown accounts on login and neutral responses on reset request |
| Transport | HTTPS only; the API already assumes TLS in production |

---

## G. Proposed API contracts

Base path `/api/v1`. All responses use the existing JSON envelope. Success responses
use the documented `{ "success": true, "message"?: string, "data": {...} }` shape
(a success envelope record does not yet exist in code — D-13).

Error envelope (implemented):

```json
{
  "success": false,
  "error": { "code": "UNAUTHORIZED", "message": "Authentication is required", "details": [] },
  "timestamp": "2026-11-15T10:30:00Z",
  "path": "/api/v1/auth/login"
}
```

### G.1 Contract summary

| # | Method | URL | Auth | Role | CSRF | Success |
|---|---|---|---|---|---|---|
| 1 | POST | `/auth/register` | No | Visitor | Exempt (Origin required) | `201` |
| 2 | POST | `/auth/login` | No | Any active account | Exempt (Origin required) | `200` |
| 3 | POST | `/auth/logout` | Yes | Any | **Required** | `204` |
| 4 | GET | `/auth/me` | Yes | Any | Not required (safe) | `200` |
| 5 | GET | `/auth/me` (bootstrap) | Yes | Any | Not required | `200` + `X-CSRF-TOKEN` |
| 6 | PATCH | `/users/me/password` | Yes | Any | **Required** | `200` or `204` |
| 7 | POST | `/admin/agents` | Yes | `ADMIN` | **Required** | `201` |
| 8 | PATCH | `/admin/users/{userId}/status` | Yes | `ADMIN` | **Required** | `200` |
| 9 | PATCH | `/admin/agents/{agentId}/status` | Yes | `ADMIN` | **Required** | `200` |
| 10 | POST | `/auth/forgot-password` | No | Visitor | Exempt (Origin required) | `202` neutral |
| 11 | POST | `/auth/reset-password` | No | Token holder | Exempt (Origin required) | `200` generic |

### G.2 `POST /auth/register`

| Field | Value |
|---|---|
| Authentication | None |
| Required role | Visitor |
| CSRF | Exempt; exact Origin/Referer required |
| Request | `{firstName, lastName, email, password, phone?}` |
| Success | `201` `{success:true, message:"Account registered successfully", data:{id, firstName, lastName, email, role:"BUYER", accountStatus:"ACTIVE"}}` |
| Errors | `400 VALIDATION_ERROR` (field details), `409 CONFLICT` (email exists), `415 UNSUPPORTED_MEDIA_TYPE`, `429 TOO_MANY_REQUESTS`, `403 FORBIDDEN` (origin) |
| Side effects | No session; no CSRF header; audit `USER_REGISTERED` |
| Notes | `role`, `accountStatus`, `id`, `passwordHash` must be rejected if supplied |

### G.3 `POST /auth/login`

| Field | Value |
|---|---|
| Authentication | None |
| Required role | Any account with `account_status = ACTIVE` |
| CSRF | Exempt; exact Origin/Referer required |
| Request | `{email, password}` |
| Success | `200` `{success:true, message:"Login successful", data:{id, firstName, lastName, email, role}}` |
| Response headers | `Set-Cookie: EV_SESSION=...` (HttpOnly, SameSite=Lax, Secure in prod, Path=/), `X-CSRF-TOKEN: <raw token>`, `Cache-Control: no-store` |
| Errors | `401 UNAUTHORIZED` generic "Email or password is incorrect.", `429 TOO_MANY_REQUESTS` + `Retry-After`, `400 BAD_REQUEST`, `403 FORBIDDEN` (origin) |
| Side effects | Session created; ID rotated; `last_login_at` updated; CSRF token generated; audit `LOGIN_SUCCESS`/`LOGIN_FAILED` (reason code only) |
| Notes | Identical response for unknown email, wrong password, inactive, and suspended |

### G.4 `POST /auth/logout`

| Field | Value |
|---|---|
| Authentication | Required |
| Required role | Any |
| CSRF | **Required** |
| Request | No body |
| Success | `204 No Content` |
| Errors | `401 UNAUTHORIZED` (no session), `403 FORBIDDEN` (missing/invalid CSRF) |
| Side effects | Session + CSRF token destroyed; cookie cleared; security context cleared; audit `LOGOUT` |
| Notes | Resolution of D-6: no unauthenticated idempotent variant |

### G.5 `GET /auth/me` — current user **and CSRF bootstrap**

| Field | Value |
|---|---|
| Authentication | Required |
| Required role | Any |
| CSRF | Not required (safe method); **this response is the token bootstrap** |
| Request | No body |
| Success | `200` `{success:true, data:{id, firstName, lastName, email, role}}` |
| Response headers | `X-CSRF-TOKEN: <current session token>`, `Cache-Control: no-store` |
| Errors | `401 UNAUTHORIZED` |
| Notes | No separate CSRF endpoint will be created. The approved design explicitly reuses this response (`SECURITY_ARCHITECTURE.md` §8.7/§8.8.4). Adding `/auth/csrf` would be an unapproved API change. |

### G.6 `PATCH /users/me/password`

| Field | Value |
|---|---|
| Authentication | Required |
| Required role | Any |
| CSRF | **Required** |
| Request | `{currentPassword, newPassword, confirmPassword}` (proposed; the API specification defines no body — D-12) |
| Success | `200` `{success:true, message:"Password updated"}` |
| Response headers | New `X-CSRF-TOKEN` after rotation; `Cache-Control: no-store` |
| Errors | `400 VALIDATION_ERROR` (policy violations, mismatch, reuse), `401 UNAUTHORIZED` (bad current password or no session), `403 FORBIDDEN` (CSRF), `429 TOO_MANY_REQUESTS` |
| Side effects | Hash replaced; other sessions invalidated; current session ID rotated; audit `PASSWORD_CHANGED` |
| Notes | Must not return the hash; must not log either password |

### G.7 `POST /admin/agents`

| Field | Value |
|---|---|
| Authentication | Required |
| Required role | `ADMIN` |
| CSRF | **Required** |
| Request | `{firstName, lastName, email, phone?, agencyName?, bio?}` |
| Success | `201` `{success:true, message:"Agent account created", data:{userId, agentProfileId, accountStatus:"INACTIVE"}}` |
| Errors | `400 VALIDATION_ERROR`, `401`, `403` (role or CSRF), `409 CONFLICT` (email), `503` (email delivery unavailable) |
| Side effects | User role `AGENT`, status `INACTIVE`, agent profile, activation token hash, audit `AGENT_CREATED` |
| Notes | Never returns a password or token; no administrator-set password |

### G.8 `PATCH /admin/users/{userId}/status`

| Field | Value |
|---|---|
| Authentication | Required |
| Required role | `ADMIN` |
| CSRF | **Required** |
| Request | `{accountStatus: "ACTIVE"\|"INACTIVE"\|"SUSPENDED", reason?: string}` |
| Success | `200` `{success:true, data:{id, accountStatus}}` |
| Errors | `400 VALIDATION_ERROR`, `401`, `403`, `404 NOT_FOUND`, `409 CONFLICT` (self-target or last active admin) |
| Side effects | Session revocation on non-`ACTIVE`; audit `ACCOUNT_STATUS_CHANGED` with before/after |
| Notes | Must be idempotent for the same target status; must not allow self-deactivation of the last active admin |

### G.9 `PATCH /admin/agents/{agentId}/status`

| Field | Value |
|---|---|
| Authentication | Required |
| Required role | `ADMIN` |
| CSRF | **Required** |
| Request | `{accountStatus, reason?}` |
| Success | `200` |
| Errors | `400` (target is not an `AGENT`), `401`, `403`, `404`, `409` |
| Side effects | Same as G.8, scoped to agent accounts; audit entry |
| Notes | Property reassignment remains a separate admin operation; deactivation does not auto-reassign |

### G.10 `POST /auth/forgot-password`

| Field | Value |
|---|---|
| Authentication | None |
| CSRF | Exempt; Origin required |
| Request | `{email}` |
| Success | `202 Accepted` neutral: "If an account exists for that email, a reset link has been sent." |
| Errors | `400` (malformed email only), `429`, `403` (origin) |
| Notes | Same response and comparable timing for unknown accounts; depends on the email provider (OD-2) |

### G.11 `POST /auth/reset-password` (reset **and** activation)

| Field | Value |
|---|---|
| Authentication | None; valid purpose-scoped token |
| CSRF | Exempt; Origin required |
| Request | `{token, newPassword, confirmPassword}` |
| Success | `200` generic success |
| Errors | `400` generic invalid/expired token or policy failure, `429 TOO_MANY_REQUESTS`, `403` (origin) |
| Side effects | Hash updated; token marked used; other tokens invalidated; all sessions revoked; activation flips `INACTIVE` → `ACTIVE` only for an `AGENT` with an `ACCOUNT_ACTIVATION` token |
| Notes | Purpose must match; a reset token must never activate, an activation token must never reset an active account |

### G.12 Contract-wide rules

- All mutating endpoints are JSON only; `Content-Type: application/json` required.
- `Cache-Control: no-store` on every auth response and on `401`/`403`.
- No endpoint returns `password_hash`, session IDs, CSRF tokens in the body, or raw
  reset/activation tokens.
- `401` is generic; `403` is generic; `404` must not confirm unauthorized existence.
- Validation details name the field and the rule, never the rejected value.
- Frontend role data is display-only; every authorization decision is server-side.

---

## H. Security threat assessment

Severity uses the approved scale from `SECURITY_ARCHITECTURE.md` §7.1.

| ID | Threat | Severity (pre-implementation) | Current exposure | Proposed mitigation |
|---|---|---|---|---|
| T-1 | **Broken access control / IDOR** on future authenticated routes | High | Not exploitable today (deny-all), but no ownership layer exists | Route role checks **plus** service ownership predicates **plus** scoped repository finders; two-user tests per role |
| T-2 | **Session fixation** | High | Framework default only; no explicit configuration or test | `changeSessionId()` on login and privilege change; assert the ID changes; rotate CSRF with it |
| T-3 | **Session hijacking** via stolen cookie | High | Cookie is `HttpOnly`/`SameSite=Lax`; `Secure` only in prod; no `__Host-` prefix | Enforce `Secure` in production, `__Host-` prefix where feasible, absolute 12 h timeout, 30 min idle, revocation on deactivation/password change, TLS-only |
| T-4 | **CSRF** on state-changing requests | High | CSRF enabled but SD-53 not implemented; exemptions absent | Session-bound 256-bit token in `X-CSRF-TOKEN`, raw-header handler, Origin/Referer validation, single re-bootstrap, no cookie |
| T-5 | **Brute-force / credential stuffing** | High | No limiter, no lockout store, no audit | SD-13 baselines: 5 failures/15 min per account+IP, 10/hour per account, 20/15 min per IP, 10/min/IP, `429` + `Retry-After`; persistent attempt store |
| T-6 | **User enumeration** | Medium | No login endpoint yet; future duplicate-email `409` is a documented trade-off | Generic `401` message; dummy hash for unknown users; identical inactive/suspended response; neutral reset response; comparable timing |
| T-7 | **Password exposure** in logs, responses, or audit | High | No logback redaction; no audit writer; no DTOs | Never log bodies/headers for auth; explicit response DTOs; audit whitelist; logback redaction; SD-44 rules before auth merge |
| T-8 | **Privilege escalation** via mass assignment | High | No endpoints yet; `User` entity has setters for `role`, `passwordHash`, `accountStatus` | Dedicated request DTOs with no role/status/hash fields; reject unknown properties; never bind entities from requests; server-derived principals only |
| T-9 | **Insecure CORS** | Medium | Exact origins enforced; no wildcard; credentials scoped | Keep exact allowlist; remove `X-Requested-With`; align preflight cache to 600 s; never add `*` |
| T-10 | **Sensitive information in logs** (tokens, emails, session IDs) | Medium | SD-44 partial; no structured redaction | Correlation ID filter, reason-code logging, no raw email in messages, no cookies/headers, retention policy |
| T-11 | **Unauthorized WebSocket access** | High if opened | Handshake path currently denied | Keep denied until handshake interceptor + exact Origin + STOMP CONNECT/SUBSCRIBE/SEND authorization + session-destroyed socket close exist |
| T-12 | **Login CSRF** (forcing a victim into the attacker's session) | Medium | Login will be CSRF-exempt | Exact Origin check, `SameSite=Lax`, response unreadable cross-origin, session-ID rotation on success |
| T-13 | **Session-cookie shadowing / duplicate cookies** | Low–Medium | Host-only cookie, no `Domain`; no duplicate-cookie rejection | `__Host-` prefix, reject ambiguous duplicate `EV_SESSION` values, treat ambiguity as unauthenticated (SD-02 hardening) |
| T-14 | **Denial of service via lockout** | Medium | No lockout exists yet | Short temporary windows, per-IP and per-account counters, no permanent lock, monitoring |
| T-15 | **Token leakage in URLs** (reset/activation) | Medium | Design approved, not implemented | Token only in the emailed HTTPS link, `Referrer-Policy: no-referrer` on the reset page, `history.replaceState`, never log the URL |
| T-16 | **Account state bypass** (inactive user keeps access) | High | No auth yet | Re-check status per request or on session validation; revoke all sessions on status change; test deactivation with an active session |
| T-17 | **Error-based information disclosure** | Low | Generic envelopes already implemented and tested | Keep generic `401`/`403`; no framework messages; correlation ID for `5xx` only |
| T-18 | **Dependency / cryptographic drift** | Low | Spring Security 7.1.2 pinned; BCrypt available | Keep pins; no new crypto dependency without approval; re-verify SD-53 wiring per version change |

Residual risks after mitigation are the ones already accepted in
`SECURITY_ARCHITECTURE.md` §7.5: XSS on the approved frontend origin defeats CSRF
(no client-side control can prevent that), deployment without same-site hostnames
breaks the cookie design, and admin MFA remains deferred (SD-50).

---

## I. Automated verification performed

Every command below was executed in this sub-stage. Nothing is claimed beyond its output.

### I.1 Git integrity

| Check | Command | Result |
|---|---|---|
| Branch | `git branch --show-current` | `stage-3-authentication` (after approved creation) |
| HEAD | `git rev-parse HEAD` | `1edcd1244ef6238d729ee032d311a4a020d3841a` |
| Worktree | `git status --porcelain` | Empty before and after inspection (this document is the only addition) |
| Tracked files | `git ls-files` | 147 |
| Stash | `git stash list` | Empty |
| Lineage | `git merge-base --is-ancestor 1edcd12 <branch>` | `1edcd12` is **not** contained in `main`, `stage-3-property-listings`, `stage-4-details-and-contact`, or `stage-6-documentation-deployment` |

### I.2 Test-suite baseline (executed)

```text
cd backend
mvn -B test
```

| Result | Value |
|---|---|
| Outcome | `BUILD SUCCESS` |
| Tests run | **83** |
| Failures | **0** |
| Errors | **0** |
| Skipped | **23** (opt-in database tests; `TEST_DB_URL` not set) |
| Total time | 23.084 s |
| Runtime | Java 23.0.2, Spring Boot 4.1.2 |

Per-class results:

| Class | Run | Skipped |
|---|---|---|
| `ApplicationIntegrationTest` | 7 | 7 |
| `BusinessSchemaIntegrationTest` | 16 | 16 |
| `SecurityFilterChainTest` | 8 | 0 |
| `GlobalExceptionHandlerTest` | 16 | 0 |
| `ApiErrorControllerTest` | 4 | 0 |
| `ErrorCodeTest` | 4 | 0 |
| `ApiErrorResponseTest` | 4 | 0 |
| `AllowedOriginsTest` | 6 | 0 |
| `ApplicationConfigurationTest` | 6 | 0 |
| `CorsConfigTest` | 3 | 0 |
| `HealthControllerTest` | 1 | 0 |
| `ConversationReadStateIdTest` | 4 | 0 |
| `PropertyAmenityIdTest` | 4 | 0 |

Interpretation:

- The suite is green for the existing scope. It does **not** exercise authentication,
  because no authentication endpoint exists.
- The 23 skipped tests are explicitly environment-gated by
  `@EnabledIfEnvironmentVariable` / profile conditions, not silently ignored.
- No authentication API test was run, and none is claimed.

### I.3 Dependency inspection

```text
mvn -B dependency:tree "-Dincludes=org.springframework.security:*"
```

| Artifact | Version |
|---|---|
| `spring-boot-starter-security` | 4.1.2 |
| `spring-security-config` | 7.1.2 |
| `spring-security-core` | 7.1.2 |
| `spring-security-web` | 7.1.2 |
| `spring-security-crypto` | 7.1.2 |
| `spring-security-test` | 7.1.2 (test) |

```text
mvn -B dependency:tree "-Dincludes=org.bouncycastle:*"
```

Result: no BouncyCastle artifacts. Consequence: `Argon2PasswordEncoder` is **not**
usable without adding a dependency; BCrypt requires none.

Also present in `pom.xml`: `spring-boot-starter-session-jdbc`,
`spring-boot-starter-flyway`, `flyway-database-postgresql`,
`spring-boot-starter-validation`, `spring-boot-starter-websocket`,
`spring-boot-starter-actuator`, JaCoCo 0.8.13.

### I.4 Configuration validation

| Check | Basis | Result |
|---|---|---|
| Origin validation rejects wildcard/blank/pathed values | `AllowedOriginsTest` (6 tests) | Pass |
| CORS bean wiring, credentials, exposed header | `CorsConfigTest` (3 tests) | Pass |
| Property binding and profile defaults | `ApplicationConfigurationTest` (6 tests) | Pass |
| Deny-by-default, JSON `401`/`403`, actuator denial, WS denial | `SecurityFilterChainTest` (8 tests) | Pass |
| Production has no datasource/origin fallbacks | Inspection of `application-prod.yml` | Confirmed by reading |
| Hibernate `ddl-auto: validate`, Flyway validate, clean disabled | `application.yml`; existing test assertion | Confirmed |

### I.5 Checks deliberately **not** run

| Not run | Reason |
|---|---|
| Database-backed integration tests (`ApplicationIntegrationTest`, `BusinessSchemaIntegrationTest`) | `TEST_DB_URL` not set; they are opt-in and would require an isolated test database |
| Flyway `clean` / any destructive DB operation | Prohibited by the sub-stage and by configuration |
| Application runtime smoke test | No database available; no authentication endpoint exists to exercise |
| Auth endpoint API tests | **Endpoints do not exist**; claiming them would be false |
| Load/rate-limit tests | No limiter exists |
| Dependency vulnerability scan | No scanner configured in this environment; SD-45 remains an open CI obligation |

---

## J. Implementation dependency sequence (for Stage 3.2+)

Ordering is driven by hard dependencies, not preference.

| Step | Work | Depends on | Why here |
|---|---|---|---|
| 0 | Record decisions OD-1…OD-6 (below) | — | Several endpoints cannot ship without them |
| 1 | Add `PasswordEncoder` (`{bcrypt}`, cost 12) and a unit test | none | Everything credential-related needs it |
| 2 | Add `EstateVistaUserDetails` + `EstateVistaUserDetailsService` (UUID principal, `ACTIVE` gate) | 1 | Login, `/auth/me`, and revocation all depend on principal shape |
| 3 | Migration `V15` for `account_tokens` (+ indexes) | 0 | Required by reset and activation |
| 4 | Migration for `login_attempts` (+ optional `credentials_updated_at`) | 0, OD-6 | Required for SD-13 throttling |
| 5 | SD-53 CSRF: repository, 256-bit generator, raw-header handler, header writer, exemptions, audit event | 1, 2 | Must exist before the first mutating auth endpoint is exposed |
| 6 | Session policy: explicit fixation, absolute timeout filter, concurrent cap, admin idle override, revocation service | 2 | Login must not create a session that violates the approved lifecycle |
| 7 | `POST /auth/register` (+ validation DTOs, duplicate handling) | 1, 2 | No session needed; safe early win |
| 8 | `POST /auth/login` + `GET /auth/me` + `POST /auth/logout` | 2, 5, 6 | The core session loop; must land together to be testable |
| 9 | Authorization matchers + `@PreAuthorize` + ownership helpers | 2, 8 | Replace `denyAll` incrementally, never with wildcards |
| 10 | `PATCH /users/me/password` | 5, 6, 8 | High-value mutation requiring CSRF and rotation |
| 11 | Admin user/agent endpoints + status management | 5, 9 | Requires working ADMIN authentication |
| 12 | Password reset and agent activation (email) | 3, 11, OD-2 | Blocked on the email provider |
| 13 | WebSocket handshake authentication + channel deny-by-default | 2, 6, 8 | Must be done before `/ws` is ever permitted |
| 14 | Security header configuration + `no-store` on auth responses | 5, 8 | Cheap, testable, and closes D-9 |
| 15 | Section 17 authentication/authorization/CSRF test suites | 7–14 | Evidence before any "Verified" claim |
| 16 | Logging redaction and audit writer (SD-44) | 5, 7 | Required before auth traffic exists in production |

Steps 1–8 are the minimum coherent increment; they are not independently shippable
in fragments because a login without rotation/CSRF/timeout would create a false
sense of security.

---

## K. Outstanding decisions and blockers

| ID | Item | Impact if unresolved | Recommended decision | Owner |
|---|---|---|---|---|
| OD-1 | Administrator provisioning mechanism (script vs runbook vs migration) | No usable `ADMIN` account; admin endpoints untestable end-to-end | One-time operator script reading credentials from environment; never a migration; never a public endpoint | Project owner |
| OD-2 | Email provider, sender domain, SPF/DKIM/DMARC | Agent activation and password reset cannot be completed; accounts stay `INACTIVE` | Select provider; store API key in the secret store; implement neutral responses before it is live | Project owner |
| OD-3 | `users.password_hash NOT NULL` vs unactivated agents | Agent creation either fails or stores a meaningless hash | Store a documented unusable sentinel hash for `INACTIVE` agents and gate on status; do not relax the column without a migration decision | Technical lead |
| OD-4 | Logout idempotency vs mandatory CSRF | Ambiguous endpoint behaviour | Require authentication; `401` when no session; `204` on success (D-6) | Technical lead |
| OD-5 | Breached-password blocklist source | Weak-password acceptance; policy partially enforced | Local blocklist resource bundled with the app; no third-party password transmission | Technical lead |
| OD-6 | Rate-limit and lockout storage (in-memory vs database) | Multi-instance deployments would bypass limits | Database-backed attempts (proposed `login_attempts`) or an approved shared store | Technical lead |
| OD-7 | Absolute timeout and concurrent-session mechanism approval | Approved session policy cannot be implemented as written | Approve the `authenticatedAt` filter plus `SpringSessionBackedSessionRegistry` approach | Technical lead |
| OD-8 | `__Host-` cookie prefix adoption | Residual cookie-shadowing exposure | Adopt in production; use a profile-specific name for HTTP local development, or document the deferral | Deployment owner |
| OD-9 | Deployment hostnames, CORS origin, WebSocket origin (SD-48) | Session cookie becomes cross-site; auth cannot work in production | Supply exact same-site custom hostnames; never fall back to unrelated hosts | Deployment owner |
| OD-10 | Security header ownership (API vs platform) | Missing `Referrer-Policy`/`Permissions-Policy` on API responses | Configure on the API for API responses; keep HTML CSP on the static host | Technical lead |

Blockers (hard):

- **B-1**: `stage-3-authentication` existed nowhere at start. Resolved during this
  sub-stage by explicit owner approval to branch from `1edcd12`.
- **B-2**: No authentication code, encoder, principal, or CSRF implementation exists.
  All of Stage 3.2 is greenfield on top of the existing shell.
- **B-3**: Agent activation cannot complete without OD-2 (email).
- **B-4**: Admin workflows cannot be exercised without OD-1.

Non-blockers: OD-4 through OD-10 have safe interim behaviours and can be decided in
parallel with Steps 1–8.

---

## L. Readiness verdict

**Stage 3.1 verdict: PASS — assessment complete, no implementation performed.**

**Readiness for Stage 3.2: READY WITH CONDITIONS.**

Justification:

| Criterion | Verdict |
|---|---|
| Existing architecture accurately documented | Met (§C, with paths and class names) |
| Database requirements understood | Met (§D; 18 business tables verified individually) |
| Design compatible with existing security decisions | Met — no conflict with SD-01/03/05/09/10/11/12/13/14/15/44/47/48/49/53 |
| CSRF strategy defined | Met (§F.9), implementable on 7.1.2 as recorded |
| Role and permission model defined | Met (§F.8), server-side only |
| API contracts documented | Met (§G, as proposals) |
| Security risks assessed | Met (§H, 18 threats with severity and mitigation) |
| No unrelated implementation changes | Met — only this document was added; branch creation is ref-only |

Conditions before authentication is considered complete:

1. OD-1, OD-2, OD-3, OD-6, OD-7 decided and recorded.
2. SD-53 wiring implemented and tested (not merely designed).
3. Approved session lifecycle (fixation, idle, absolute, concurrency, revocation)
   implemented and tested.
4. `/ws` remains unreachable until handshake authentication and channel
   authorization exist.
5. Section 17 authentication, authorization, and CSRF tests pass before any
   "Implemented" or "Verified" claim is made.

Stage 3.2 may begin immediately with Steps 1–8 (encoder, principal, migrations,
CSRF, session policy, register/login/me/logout). Steps 11–13 must wait for the
blockers above.

---

## M. Deliverables and change record

| Deliverable | Location |
|---|---|
| This assessment | `docs/STAGE_3_1_AUTHENTICATION_ASSESSMENT.md` |
| Existing architecture inventory | §C |
| Database assessment and change requirements | §D |
| Security architecture assessment (SD-44, SD-47, SD-53) | §E |
| Authentication architecture proposal | §F |
| Proposed API contracts | §G |
| Security threat assessment | §H |
| Automated verification evidence | §I |
| Implementation dependency sequence | §J |
| Outstanding decisions and blockers | §K |

| Change | Detail |
|---|---|
| Files created | `docs/STAGE_3_1_AUTHENTICATION_ASSESSMENT.md` |
| Files modified | None |
| Java source modified | None |
| Database migrations modified | None |
| Dependencies added | None |
| Security configuration modified | None |
| Git refs created | Branch `stage-3-authentication` at `1edcd12` (owner-approved) |
| Commit | Not created by this sub-stage |

**Explicit non-claims:** authentication is not implemented; no JWT exists; no login
endpoint exists; no CSRF token repository exists; no authorization rules exist beyond
`denyAll()`; no authentication test has been written or executed; no security control
listed as "proposed" has been verified in code.
