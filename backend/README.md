# EstateVista V2 — Backend

Spring Boot modular monolith for the EstateVista V2 REST API and
WebSocket/STOMP endpoint.

**Stage 2.4 status:** application foundation and persistence layer established.
The build, profiles, PostgreSQL connectivity, Flyway, Spring Session JDBC,
security shell, global error handling, health endpoints, approved business
schema, JPA entities, and repositories are in place. Authentication, business
services, API endpoints, and WebSocket message handling are not implemented.

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
| `prod` | `application-prod.yml` | Secure cookie, native forwarded headers, no DB fallbacks |

Shared settings live in `application.yml`.

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
    │   │   ├── config/            # CORS, security shell, WebSocket, app properties
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
are never returned.

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

Stage 2.4 test totals: 6 web-layer tests plus 11 PostgreSQL integration tests
(17 total, 0 failures, 0 errors, 0 skipped) when the test database is supplied.
