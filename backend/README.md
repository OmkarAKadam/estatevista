# EstateVista V2 — Backend

Spring Boot modular monolith for the EstateVista V2 REST API and
WebSocket/STOMP endpoint.

**Stage 1.8 status:** environment and project shell only. Authentication,
authorization workflows, business modules, and WebSocket message handling are
not implemented.

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
mvn clean package
mvn spring-boot:run
```

Health checks:

```text
GET http://localhost:8080/api/v1/health
GET http://localhost:8080/actuator/health
```

The full setup, environment-variable reference, migration conventions, and
validation commands are in [`../docs/DEVELOPMENT_SETUP.md`](../docs/DEVELOPMENT_SETUP.md).

## Structure

```text
backend/
├── pom.xml
├── .env.example
├── .gitignore
└── src/main/
    ├── java/com/estatevista/
    │   ├── EstateVistaApplication.java
    │   ├── config/          # CORS, security shell, WebSocket, app properties
    │   └── common/health/   # setup-only health endpoint
    └── resources/
        ├── application.yml
        ├── application-dev.yml
        ├── application-prod.yml
        └── db/migration/    # Flyway migrations
```
