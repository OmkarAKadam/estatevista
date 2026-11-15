# EstateVista V2 — Backend

Spring Boot modular monolith for the EstateVista V2 REST API and
WebSocket/STOMP endpoint.

**Stage 2.6 status:** application foundation, persistence layer, validation
infrastructure, configuration hardening, and logging/health controls are
established. The build, profiles, PostgreSQL connectivity, Flyway, Spring
Session JDBC, security shell, global error handling, health endpoints, approved
business schema, JPA entities, and repositories are in place. Authentication,
business services, API endpoints, and WebSocket message handling are not
implemented.

## Quick start

Prerequisites: JDK 21+ (JDK 23 tested), Maven 3.9+, PostgreSQL 18.x.

```powershell
# 1. Create the local database (see docs/DEVELOPMENT_SETUP.md for details)
# 2. Export local environment variables
$env:DB_URL = "jdbc:postgresql://localhost:5432/estatevista"
$env:DB_USERNAME = "estatevista"
$env:DB_PASSWORD = "your-local-password"
$env:SPRING_PROFILES_ACTIVE = "dev"

# 3. Build and run
mvn clean verify
mvn spring-boot:run
```

Health checks:

```text
GET http://localhost:8080/api/v1/health
GET http://localhost:8080/actuator/health
```

The full setup, environment-variable reference, migration conventions, and
validation commands are in [`../docs/DEVELOPMENT_SETUP.md`](../docs/DEVELOPMENT_SETUP.md).

## Profiles

| Profile | File | Purpose |
| --- | --- | --- |
| `dev` | `application-dev.yml` | Local debug logging and Flyway console output |
| `test` | `application-test.yml` | Isolated test database; no production defaults |
| `prod` | `application-prod.yml` | Secure cookie, native forwarded headers, no DB or origin fallbacks, INFO logging with SQL/bind/extract suppression |

Shared settings live in `application.yml`. The `prod` profile has no fallback
for `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `APP_CORS_ALLOWED_ORIGINS`, or
`APP_WEBSOCKET_ALLOWED_ORIGINS`; startup fails if any is missing or invalid.
Comma-separated origins are validated as exact `http`/`https` origins. Blank
entries, wildcards, unresolved `${...}` placeholders, paths, queries, fragments,
and user info are rejected at startup.

## Structure

```text
backend/
├── pom.xml
├── .env.example
├── .gitignore
└── src/
    ├── main/
    │   ├── java/com/estatevista/
    │   │   ├── EstateVistaApplication.java
    │   │   ├── config/            # CORS, origin validation, security shell, WebSocket, app properties
    │   │   ├── common/
    │   │   │   ├── exception/     # Error codes, ApiException, global handler, /error
    │   │   │   ├── health/        # setup-only health endpoint
    │   │   │   └── response/      # standard API error envelope
    │   │   ├── user/              # Role, User, AgentProfile + repositories
    │   │   ├── property/          # Property, category/subtype, details, images,
    │   │   │                      # amenities + repositories
    │   │   ├── favorite/          # Favorite + repository
    │   │   ├── inquiry/           # Inquiry + repository
    │   │   ├── chat/              # Conversation, Message, ConversationReadState
    │   │   │                      # + repositories
    │   │   ├── notification/      # Notification + repository
    │   │   └── audit/             # AuditLog + repository
    │   └── resources/
    │       ├── application.yml
    │       ├── application-dev.yml
    │       ├── application-test.yml
    │       ├── application-prod.yml
    │       └── db/migration/      # Flyway V1 (Spring Session) + V2–V14 (business)
    └── test/java/com/estatevista/
        ├── common/                # health and error-contract web tests
        ├── config/                # security-chain, CORS, and origin-validation tests
        └── persistence/           # opt-in PostgreSQL schema/mapping tests
```

Business modules will follow `SYSTEM_ARCHITECTURE.md` §5.3
(`com.estatevista.auth`, `user`, `property`, `favorite`, `inquiry`, `chat`,
`notification`, `dashboard`, `audit`, `storage`). Only packages with approved
persistence types exist; services and controllers are added in later stages.

## Database schema

Flyway owns the schema and runs all migrations on startup. Hibernate is
configured with `ddl-auto: validate`; it never creates or alters tables.

| Migrations | Contents |
| --- | --- |
| `V1__spring_session_schema.sql` | Framework-owned Spring Session JDBC tables (SD-03) |
| `V2` | `roles`, `users` plus seeded BUYER/AGENT/ADMIN roles |
| `V3` | `agent_profiles` |
| `V4` | `property_categories`, `property_subtypes` plus seeded RESIDENTIAL/COMMERCIAL categories |
| `V5` | `properties`, `property_residential_details`, `property_commercial_details` |
| `V6` | `property_images` |
| `V7` | `amenities`, `property_amenities` |
| `V8` | `favorites` |
| `V9` | `inquiries` |
| `V10` | `conversations`, `messages` |
| `V11` | `conversation_read_states` |
| `V12` | `notifications` |
| `V13` | `audit_logs` |
| `V14` | Approved indexes from `DATABASE_DESIGN.md` §15 |

Database-level rules include the case-insensitive unique email index, a
composite `(category_id, subtype_id)` foreign key that binds a property to a
subtype in the same category, status/type/price/coordinate CHECK constraints,
and a partial unique index for one primary image per property. Foreign-key
delete behavior follows `DATABASE_DESIGN.md` §17.1: `RESTRICT` for business
history, `CASCADE` only for dependent rows, and `SET NULL` where retaining the
record matters more than the reference.

Role-specific authorization, account-status eligibility, conversation
participant rules, last-read-message scoping, subtype/detail-table consistency,
and email normalization remain application-layer rules; they are not enforced
by these mappings. See `docs/validation/stage-2.3-2.4-completion-report.md`.

## API errors

Every REST error uses the envelope from `API_SPECIFICATION.md` §17:

```json
{
  "success": false,
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "The supplied data is invalid",
    "details": [{"field": "email", "message": "A valid email address is required"}]
  },
  "timestamp": "2026-11-14T10:30:00Z",
  "path": "/api/v1/auth/register"
}
```

Security-filter failures (401/403/CSRF) and container error dispatches use the
same envelope. Stack traces, exception class names, SQL text, and internal paths
are never returned. Malformed-request logging records only the failure category;
framework parser messages that can embed a rejected body excerpt are never
logged.

## Configuration, logging, and health

- Exact-origin allowlists are validated once by `AllowedOrigins` and shared by
  `CorsConfig` and `WebSocketConfig`. Startup fails rather than building a list
  that can never match a browser origin.
- Production requires explicit `APP_CORS_ALLOWED_ORIGINS` and
  `APP_WEBSOCKET_ALLOWED_ORIGINS`; there are no localhost fallbacks.
- `prod` pins root/application/Web/Security/Flyway loggers to INFO and forces
  `org.hibernate.SQL`, `org.hibernate.orm.jdbc.bind`, and
  `org.hibernate.orm.jdbc.extract` to WARN so SQL and bind values stay out of
  logs even if a parent logger is raised later.
- `dev` enables application and web debug logging; `test` reduces logging and
  targets an isolated test database. The persistence tests run only when
  `TEST_DB_URL` is supplied.
- `/api/v1/health` is a minimal liveness document (`status`, `service`,
  `timestamp`) and remains `200 UP` even when PostgreSQL is down.
- `/actuator/health` reports the database state: `200 UP` when reachable and
  `503 DOWN` when not. Only `health` is exposed; details are hidden, and every
  other `/actuator/**` path returns the standard `401` envelope.
- `/ws` remains denied by the HTTP security chain. This stage does not add
  handshake authentication or STOMP authorization.

## Tests

```powershell
# Web-layer tests only; no database required (the PostgreSQL tests are skipped)
mvn clean verify

# Full run including real-PostgreSQL persistence tests
$env:TEST_DB_URL = "jdbc:postgresql://localhost:5432/estatevista_test"
$env:TEST_DB_USERNAME = "estatevista"
$env:TEST_DB_PASSWORD = "your-local-test-password"
mvn clean verify
```

`BusinessSchemaIntegrationTest` verifies Flyway history, entity metadata,
repository wiring, column types, full relationship persistence, enum storage,
and database constraint enforcement. It is enabled only when `TEST_DB_URL` is
set, uses the `test` profile, and rolls back its transactions. Never point it at
a development or production database.

Web-layer tests cover the health endpoint, the complete error-response contract,
malformed-request log redaction, the deny-by-default security chain, CSRF
rejection, CORS behavior, and exact-origin validation. They start no
database-backed context.

Stage 2.5/2.6 test totals: 29 web-layer tests plus 11 PostgreSQL integration
tests (40 total, 0 failures, 0 errors, 0 skipped) when the test database is
supplied. Without `TEST_DB_URL`, the 29 web-layer tests run and the 11
persistence tests are skipped.
