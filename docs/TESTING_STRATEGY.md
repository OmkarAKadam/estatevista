# EstateVista V2 — Backend Testing Strategy

| Field | Value |
|---|---|
| Status | Implemented for Stage 2; expands with later stages |
| Last validated | 2026-11-15 |
| Backend | Java 21 target, Spring Boot 4.1.2, Maven, PostgreSQL 18.3 |
| Full-suite command | `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\postgres-test-cluster.ps1 -Action verify` |
| Result at last validation | 83 tests, 0 failures, 0 errors, 0 skipped |

This document describes the automated tests that exist, how they are executed,
and what they do not prove. It does not claim production readiness and does not
invent a coverage gate that has not been approved.

---

## 1. Principles

1. **Test real behaviour, not mocks of it.** Security, CORS, Actuator, Flyway,
   Hibernate validation, and persistence behavior are exercised through real
   Spring contexts and a real PostgreSQL server wherever practical.
2. **Never substitute H2 for PostgreSQL.** Database-specific types, constraints,
   indexes, and Flyway behavior must be verified on the production database
   engine family.
3. **Do not inflate or silently skip.** PostgreSQL tests are enabled only when an
   isolated test database is supplied. A database-free run reports the skipped
   tests as skipped; it is never reported as persistence coverage.
4. **Test the contract, not the implementation.** Error envelopes, status codes,
   configuration properties, security decisions, and schema constraints are the
   observable contracts.
5. **Coverage is evidence, not a score.** JaCoCo reports what executed. A
   percentage does not prove correctness, and no arbitrary threshold is used.
6. **Fixed migrations stay fixed.** Tests may read `V1`–`V14`; they must not
   modify, renumber, or clean them.

---

## 2. Test layers

| Layer | Purpose | Database | Examples |
|---|---|---|---|
| Unit | Pure model, mapping, and helper contracts | No | `ErrorCodeTest`, `ApiErrorResponseTest`, `AllowedOriginsTest`, composite-ID tests |
| Web slice | HTTP contract with the real security/CORS/error chain, no database context | No | `SecurityFilterChainTest`, `ApiErrorControllerTest`, `GlobalExceptionHandlerTest`, `CorsConfigTest` |
| Configuration | Parse and assert shared/production YAML contracts | No | `ApplicationConfigurationTest` |
| Full context | Complete application with Flyway, Hibernate validation, Spring Session, security, Actuator | Real PostgreSQL | `ApplicationIntegrationTest` |
| Persistence | Schema, metadata, repository wiring, mappings, and database constraints | Real PostgreSQL | `BusinessSchemaIntegrationTest` |

The suite intentionally mixes unit and integration layers: fast tests protect
contracts that do not need a database, while a smaller set of higher-value tests
proves that the real stack works together.

---

## 3. Test inventory

| Test class | Tests | Layer | Primary coverage |
|---|---:|---|---|
| `ApplicationIntegrationTest` | 7 | Full context | Migration state, schema validation, health, Actuator restriction, `/ws` denial, unknown route, CORS, CSRF |
| `BusinessSchemaIntegrationTest` | 16 | Persistence | Entity metadata, repository beans, column types, full business graph, commercial details, enums, constraints |
| `GlobalExceptionHandlerTest` | 16 | Web slice | Validation, status mapping, safe fallback, log redaction, container errors |
| `SecurityFilterChainTest` | 8 | Web slice | Deny-by-default, 401/403 envelopes, CSRF, public health, `/ws`, CORS accept/reject, Actuator denial |
| `AllowedOriginsTest` | 6 | Unit | Exact origins, wildcard/blank/placeholder/path/query/fragment/user-info/scheme rejection |
| `ApplicationConfigurationTest` | 6 | Configuration | Flyway ownership, Hibernate validation, Actuator exposure, secure production cookies, required production properties, SQL-log suppression |
| `ApiErrorControllerTest` | 4 | Web slice | Container `/error` envelope, safe fallback for unknown/missing status |
| `ErrorCodeTest` | 4 | Unit | Status mapping, fallback to internal error, approved status contract |
| `ApiErrorResponseTest` | 4 | Unit | Envelope construction, default messages, immutable defensive details |
| `CorsConfigTest` | 3 | Unit/configuration | Credentialed exact-origin configuration, wildcard/empty rejection |
| `ConversationReadStateIdTest` | 4 | Unit | Composite identifier equality, hash code, JPA no-argument contract |
| `PropertyAmenityIdTest` | 4 | Unit | Composite identifier equality, hash code, JPA no-argument contract |
| `HealthControllerTest` | 1 | Web slice | Health response shape |

Total: **83 tests**. With `TEST_DB_URL` supplied: 60 unit/web-layer and 23
PostgreSQL-backed. Without it: 60 run and 23 are skipped.

---

## 4. Database strategy

### 4.1 Local and developer runs

`backend/scripts/postgres-test-cluster.ps1` provisions a disposable PostgreSQL
cluster:

- directory `backend/.test-postgres` (Git-ignored);
- port `55433`, localhost only;
- `trust` authentication;
- database `estatevista_test`;
- actions `verify`, `start`, `stop`, `status`.

`verify` initialises or starts the cluster, exports `TEST_DB_URL`,
`TEST_DB_USERNAME`, and `TEST_DB_PASSWORD`, runs `mvn -B clean verify`, and stops
the cluster even when the build fails. The installed PostgreSQL service on `5432`
is never touched.

Manual execution is supported by exporting the same variables and running
`mvn -B clean verify`. See `docs/DEVELOPMENT_SETUP.md` §5.

### 4.2 Why not Testcontainers

Testcontainers was evaluated and rejected for this environment because Docker is
not installed or available. It remains a reasonable future option; the test code
does not depend on the provisioning mechanism, only on `TEST_DB_URL`.

### 4.3 Test data isolation

- Integration tests use `@Transactional` and roll back.
- Flyway seeds roles and property categories; tests read them and do not mutate
  them.
- Every created row uses a random suffix to avoid collisions.
- The script's cluster is disposable and must never contain development or
  production data.

Do not point `TEST_DB_URL` at a development or production database.

---

## 5. Running the tests

### 5.1 Full verification (required for acceptance)

```powershell
cd backend
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\postgres-test-cluster.ps1 -Action verify
```

Expected: `BUILD SUCCESS`; 83 tests, 0 failures, 0 errors, 0 skipped.

### 5.2 Database-free run

```powershell
cd backend
mvn clean verify
```

Expected: 60 tests executed, 23 skipped, no failures. This run does **not**
verify persistence and must not be reported as a full suite.

### 5.3 Reports

- Surefire: `backend/target/surefire-reports/`
- JaCoCo HTML/CSV: `backend/target/site/jacoco/`

---

## 6. Coverage policy

JaCoCo 0.8.13 runs `prepare-agent` and `report` during `verify`. At the last
validation:

| Metric | Covered / Total | Percentage |
|---|---:|---:|
| Instructions | 1720 / 2454 | 70.09% |
| Branches | 83 / 110 | 75.45% |
| Lines | 517 / 784 | 65.94% |

There is **no enforced threshold**. Stage 2 has no approved coverage gate, and
an arbitrary threshold would create a false quality signal. Coverage is used to
find untested production classes, not to declare the backend correct.

Known low-coverage areas are mostly JPA entity accessors that are exercised only
through repository paths, and not every branch of the exception handler. New
behavior should add targeted tests rather than chase a global percentage.

---

## 7. Continuous integration

`.github/workflows/backend-verify.yml`:

- triggers on backend changes to `main` and `stage-2-backend-foundation`, on pull
  requests, and manually;
- runs on `ubuntu-latest` with Temurin Java 21;
- starts a `postgres:18` service container;
- runs `mvn -B clean verify` from `backend/` with `TEST_DB_*` pointing at the
  service;
- uploads Surefire and JaCoCo reports even on failure;
- has read-only repository permissions and a 20-minute timeout.

**Status: configured but not executed.** The branch has not been pushed, so CI
behavior is unverified. Do not claim a green pipeline until it runs.

---

## 8. What the tests do not cover

- Authentication, registration, login, logout, and authorization workflows
  (not implemented).
- Business services, REST endpoints, property CRUD, favorites, inquiries, chat,
  uploads, notifications, dashboards (not implemented).
- WebSocket handshake authentication and STOMP authorization (not implemented).
- SD-53 session-bound CSRF generation/rotation (framework defaults only).
- Structured logging, formal redaction policy, retention, and correlation IDs
  (SD-44 deferred).
- Browser end-to-end behavior and visual rendering.
- Production deployment, TLS, proxy behavior, backups, and monitoring.
- CI execution, because the branch has not been pushed.

These gaps are explicit and are not represented as passing checks.

---

## 9. Maintenance rules

1. Add or update tests with every behavior change; do not lower the suite by
   deleting assertions to make a build pass.
2. Keep PostgreSQL integration tests enabled in full verification; do not mark
   them `@Disabled` to get a green local build.
3. Never modify `V1`–`V14`. Add a new migration for schema changes and update
   the persistence tests.
4. Keep `ddl-auto: validate`; Hibernate must not own schema creation.
5. Keep production configuration fail-fast tests aligned with
   `application-prod.yml`.
6. If a coverage threshold is ever approved, add it together with a documented
   rationale and a baseline; do not add an arbitrary number.
7. When CI is first pushed, record the run URL and result in the next validation
   report.
