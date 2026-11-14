# EstateVista V2 — Backend

Spring Boot modular monolith for the EstateVista V2 REST API and
WebSocket/STOMP endpoint.

**Stage 2.2 status:** application foundation established. The build, profiles,
PostgreSQL connectivity, Flyway, Spring Session JDBC, security shell, global
error handling, and health endpoints are in place. Authentication, business
modules, and WebSocket message handling are not implemented.

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
    │   │   └── common/
    │   │       ├── exception/     # Error codes, ApiException, global handler, /error
    │   │       ├── health/        # setup-only health endpoint
    │   │       └── response/      # standard API error envelope
    │   └── resources/
    │       ├── application.yml
    │       ├── application-dev.yml
    │       ├── application-test.yml
    │       ├── application-prod.yml
    │       └── db/migration/      # Flyway migrations (V1 = Spring Session)
    └── test/java/com/estatevista/
        └── common/                # health and error-contract tests
```

Business modules will follow `SYSTEM_ARCHITECTURE.md` §5.3
(`com.estatevista.auth`, `user`, `property`, `favorite`, `inquiry`, `chat`,
`notification`, `dashboard`, `audit`, `storage`). Empty placeholder packages are
deliberately not created.

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
mvn clean verify
```

Current tests are web-layer unit tests and do not require a database. The
`test` profile is reserved for database-backed integration tests added in later
sub-stages; point `TEST_DB_URL`, `TEST_DB_USERNAME`, and `TEST_DB_PASSWORD` at an
isolated database.
