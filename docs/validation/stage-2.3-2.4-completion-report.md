# EstateVista V2 — Stage 2.3 & 2.4 Completion Report

| Field | Value |
|---|---|
| Sub-stages | 2.3 — PostgreSQL Business Schema & Flyway Migrations; 2.4 — JPA Entities & Repositories |
| Overall status | **COMPLETE — both sub-stages passed their acceptance gates** |
| Execution agent | OpenCode |
| Execution date | 14 November 2026 |
| Feature branch | `stage-2-backend-foundation` |
| Stage 2.3 implementation commit | `af2e274` |
| Stage 2.4 implementation commit | `fb8d73c` |
| Report commit | The commit that adds this file (see `git log -1` on the feature branch) |
| Push status | Not pushed |
| Merge status | Not merged into `main` |

---

## A. Executive Summary

Stage 2.3 created the approved EstateVista business schema with thirteen
incremental Flyway migrations (`V2`–`V14`), preserving the framework-owned
Spring Session migration `V1` exactly as committed. Stage 2.4 added the matching
JPA entities, embeddable identifiers, enums, and Spring Data repositories.

Delivered work:

- 18 business tables with approved columns, types, primary keys, foreign keys,
  unique constraints, CHECK constraints, and indexes.
- Seeded reference data for the three approved roles and two approved property
  categories.
- 18 entity classes, 2 embeddable composite identifiers, 3 enum types, and 18
  repository interfaces.
- 11 opt-in real-PostgreSQL integration tests covering migration history,
  entity metadata, repository wiring, column types, persistence of the full
  relationship graph, enum storage, and constraint enforcement.
- Updated backend and environment documentation.

Both stages were verified against a fresh, isolated PostgreSQL 18.3 instance.
`mvn clean verify` passed with 17 tests, 0 failures, 0 errors, and 0 skipped.

**Stage 2.3 verdict: PASS — APPROVED.**
**Stage 2.4 verdict: PASS — APPROVED.**

Authentication, authorization workflows, SD-53 CSRF completion, business
services, controllers, REST endpoints, WebSocket handling, and all later-stage
features remain unimplemented by design.

---

## B. Initial Repository State

| Item | State at start |
|---|---|
| Branch | `stage-2-backend-foundation` |
| HEAD | `9c8ab6f54d0ccf4bee39bb9b73e3b23da077362d` |
| Working tree | Clean (no staged, modified, or untracked files) |
| Stage 2.1/2.2 implementation commit | `136cfac5f41e5440bd2c7409d02e5c9273f51d17` present |
| Stage 2.1/2.2 report commit | `9c8ab6f` (HEAD) present |
| `V1__spring_session_schema.sql` | Present, tracked, unmodified |
| Existing Java sources | 11 files: application, config, error handling, health |
| Existing tests | 6 web-layer tests |
| Discrepancies | None found |

The previous completion report's known issues were reviewed. None blocked
schema or persistence work. `V1` was verified byte-for-byte unchanged before
and after both sub-stages (`git diff` on the file was empty).

---

## C. Documentation and Design Traceability

### Documents reviewed

- `docs/PRD.md`
- `docs/FRD.md`
- `docs/SYSTEM_ARCHITECTURE.md`
- `docs/DATABASE_DESIGN.md` (authoritative for schema, §23 approved clarifications)
- `docs/API_SPECIFICATION.md`
- `docs/SECURITY_ARCHITECTURE.md`
- `docs/UI_UX_SPECIFICATION.md`
- `docs/DEVELOPMENT_SETUP.md`
- `docs/validation/stage-2.1-2.2-completion-report.md`
- Existing backend source, `pom.xml`, all profile configuration, and `V1`

### Database requirements extracted

The approved design defines 18 business tables in eight domains:

| Domain | Tables |
|---|---|
| Identity | `roles`, `users`, `agent_profiles` |
| Property | `property_categories`, `property_subtypes`, `properties`, `property_residential_details`, `property_commercial_details` |
| Media | `property_images` |
| Amenities | `amenities`, `property_amenities` |
| Engagement | `favorites`, `inquiries` |
| Chat | `conversations`, `messages`, `conversation_read_states` |
| Notifications | `notifications` |
| Audit | `audit_logs` |

`DATABASE_DESIGN.md` §14 requires database-level enforcement of normalized email
uniqueness, valid account status, unique role names, one agent profile per user,
unique subtype name per category, valid category/subtype combination, positive
price, valid listing type and status, valid coordinate ranges, one primary image
per property, unique property/amenity, buyer/property favorite, buyer/property
conversation, and conversation/user read-state combinations. §15 defines the
index strategy. §16 fixes data types. §17 and §17.1 define retention and
foreign-key deletion policy. §19 fixes the migration sequence `V2`–`V14`. §23.1
adds the composite subtype integrity rule, backend-only read-state/assignment
rules, and case-insensitive email uniqueness.

### Migration and entity traceability

| Table | Documentation | Migration | Entity | Repository |
|---|---|---|---|---|
| `roles` | §5.1, §14, §15.1 | V2 | `Role` | `RoleRepository` |
| `users` | §5.2, §14, §15.1, §23.1 E | V2 | `User` | `UserRepository` |
| `agent_profiles` | §5.3, §14 | V3 | `AgentProfile` | `AgentProfileRepository` |
| `property_categories` | §6.1, §15 | V4 | `PropertyCategory` | `PropertyCategoryRepository` |
| `property_subtypes` | §6.2, §14, §23.1 A | V4 | `PropertySubtype` | `PropertySubtypeRepository` |
| `properties` | §6.3, §14, §15.2, §23.1 A/C | V5 | `Property` | `PropertyRepository` |
| `property_residential_details` | §6.4 | V5 | `PropertyResidentialDetails` | `PropertyResidentialDetailsRepository` |
| `property_commercial_details` | §6.5 | V5 | `PropertyCommercialDetails` | `PropertyCommercialDetailsRepository` |
| `property_images` | §7.1, §15.3 | V6 | `PropertyImage` | `PropertyImageRepository` |
| `amenities` | §8.1 | V7 | `Amenity` | `AmenityRepository` |
| `property_amenities` | §8.2, §14 | V7 | `PropertyAmenity` (+ `PropertyAmenityId`) | `PropertyAmenityRepository` |
| `favorites` | §9.1, §15.4 | V8 | `Favorite` | `FavoriteRepository` |
| `inquiries` | §10.1, §15.5 | V9 | `Inquiry` | `InquiryRepository` |
| `conversations` | §11.1, §15.6, §23.1 D | V10 | `Conversation` | `ConversationRepository` |
| `messages` | §11.2, §15.7 | V10 | `Message` | `MessageRepository` |
| `conversation_read_states` | §11.3, §23.1 B | V11 | `ConversationReadState` (+ `ConversationReadStateId`) | `ConversationReadStateRepository` |
| `notifications` | §12.1, §15.8 | V12 | `Notification` | `NotificationRepository` |
| `audit_logs` | §13.1, §15.9 | V13 | `AuditLog` | `AuditLogRepository` |

### Relationship mapping

| Database relationship | Mapping | Evidence |
|---|---|---|
| `users.role_id` → `roles.id` | `@ManyToOne(LAZY, optional=false)` | §5.2: one primary role per account |
| `agent_profiles.user_id` → `users.id` (unique) | `@OneToOne(LAZY, optional=false)` | §5.3: one profile per agent |
| `property_subtypes.category_id` → `property_categories.id` | `@ManyToOne(LAZY, optional=false)` | §6.2 |
| `properties.category_id` → `property_categories.id` | `@ManyToOne(LAZY, optional=false)` | §6.3 |
| `properties.(category_id, subtype_id)` → `property_subtypes.(category_id, id)` | Separate `@ManyToOne` subtype plus database composite FK | §23.1 A |
| `properties.primary_agent_id`, `created_by`, `updated_by` → `users.id` | Three `@ManyToOne(LAZY)` associations | §6.3 |
| `property_residential_details.property_id` / `property_commercial_details.property_id` → `properties.id` | Shared-PK `@OneToOne` with `@MapsId` | §6.4, §6.5 |
| `property_images.property_id` → `properties.id` | `@ManyToOne(LAZY, optional=false)` | §7.1 |
| `property_amenities.(property_id, amenity_id)` | Composite `@EmbeddedId` plus two `@MapsId @ManyToOne(LAZY)` | §8.2 |
| `favorites.buyer_id` / `property_id` → `users.id` / `properties.id` | Two `@ManyToOne(LAZY, optional=false)` | §9.1 |
| `inquiries.buyer_id`, `property_id`, `assigned_agent_id` | Three `@ManyToOne(LAZY, optional=false)` | §10.1 |
| `conversations.buyer_id`, `property_id`, `assigned_agent_id` | Three `@ManyToOne(LAZY, optional=false)` | §11.1, §23.1 D |
| `messages.conversation_id`, `sender_id` | Two `@ManyToOne(LAZY, optional=false)` | §11.2 |
| `conversation_read_states.(conversation_id, user_id)` | Composite `@EmbeddedId` plus `@MapsId @ManyToOne` pairs; optional `lastReadMessage` association | §11.3 |
| `notifications.recipient_id` → `users.id` | `@ManyToOne(LAZY, optional=false)` | §12.1 |
| `audit_logs.actor_id` → `users.id` (nullable, `SET NULL`) | Optional `@ManyToOne(LAZY)` | §13.1 |

No inverse collections were mapped. This is deliberate: it avoids accidental
eager loading and recursive serialization, and the approved service layer can
query from the owning side. No entity is exposed through an API response.

### Resolved ambiguities

| Ambiguity | Resolution | Basis |
|---|---|---|
| Whether reference rows should be created by migration | Roles and categories are seeded because the design explicitly lists "Initial roles" and "Initial categories"; subtypes are not seeded because the document lists them only as examples | §5.1, §6.1, §6.2 |
| Enum-like columns without a finalized lifecycle | CHECK constraints only where the design presents a complete value set (account status, listing type, property status); inquiry, conversation, message-type, and notification-type values remain unconstrained `VARCHAR` | §6.3, §10.1, §11.2, §12.1, §21 |
| Foreign-key delete actions | Applied §17.1 policy: `RESTRICT` for business history, `CASCADE` only for dependent rows with no independent history, `SET NULL` where retaining the record matters more | §17.1 |
| Composite subtype integrity | Implemented exactly as approved: unique `(category_id, id)`, unique `(category_id, name)`, and composite FK from properties | §23.1 A |
| Email uniqueness | Functional unique index on `LOWER(email)` plus application-layer normalization | §5.2, §23.1 E |
| Read-state/participant and agent-role rules | Left to the service layer; no triggers introduced | §14, §23.1 B/C |

No unresolved documentation ambiguity blocks acceptance.

---

## D. Stage 2.3 Implementation

### Migration files

| Migration | Purpose |
|---|---|
| `V2__create_roles_and_users.sql` | `roles`, `users`, role seeds, case-insensitive email index |
| `V3__create_agent_profiles.sql` | `agent_profiles` |
| `V4__create_property_categories_and_subtypes.sql` | `property_categories`, `property_subtypes`, category seeds, composite unique target |
| `V5__create_properties_and_details.sql` | `properties`, residential and commercial detail tables |
| `V6__create_property_images.sql` | `property_images` plus partial primary-image index |
| `V7__create_amenities.sql` | `amenities`, `property_amenities` |
| `V8__create_favorites.sql` | `favorites` |
| `V9__create_inquiries.sql` | `inquiries` |
| `V10__create_conversations_and_messages.sql` | `conversations`, `messages` |
| `V11__create_conversation_read_states.sql` | `conversation_read_states` |
| `V12__create_notifications.sql` | `notifications` |
| `V13__create_audit_logs.sql` | `audit_logs` |
| `V14__add_business_indexes.sql` | Indexes from §15 |

### Data types and important columns

All primary keys are `UUID`. All timestamps are `TIMESTAMPTZ` (mapped to
`Instant`). Monetary and measurement values use exact `NUMERIC`; counts use
`SMALLINT`; flags use `BOOLEAN`; audit snapshots use `JSONB`; client addresses
use `INET`; currency uses `CHAR(3)`. Text lengths match the design exactly.

Key type checks verified in PostgreSQL:

| Column | Physical type |
|---|---|
| `users.created_at` | `timestamp with time zone` |
| `properties.price` | `numeric(14,2)` |
| `properties.currency_code` | `character(3)` |
| `properties.latitude` / `longitude` | `numeric(10,7)` |
| `properties.area_value` | `numeric(12,2)` |
| `property_residential_details.bedrooms` | `smallint` |
| `property_images.sort_order` | `integer` |
| `property_images.is_primary` | `boolean` |
| `messages.content` | `text` |
| `audit_logs.before_data` | `jsonb` |
| `audit_logs.ip_address` | `inet` |

### Constraints

- 18 primary keys.
- 28 foreign keys, including the composite
  `properties(category_id, subtype_id) → property_subtypes(category_id, id)`.
- 10 unique constraints/indexes, including `uk_users_email` on `LOWER(email)`,
  `uk_agent_profiles_user_id`, `uk_property_subtypes_category_id`,
  `uk_property_subtypes_category_name`, `uk_properties_slug`,
  `uk_favorites_buyer_property`, and `uk_conversations_buyer_property`.
- 1 partial unique index, `uk_property_images_primary`, enforcing one primary
  image per property.
- 6 CHECK constraints: account status, listing type, property status, positive
  price, latitude range, longitude range.
- 53 total indexes (18 primary keys + 10 unique indexes + 1 partial unique index
  + 24 §15 business indexes).

### Foreign-key deletion behavior

| Relationship group | Action | Reason |
|---|---|---|
| users→role, agent profile→user, subtype→category, property→category/subtype/agent/creator, image→property, amenity→property link, inquiry→buyer/property/agent, conversation→buyer/property/agent, message→conversation/sender | `RESTRICT` | Preserve business history (§17.1) |
| residential/commercial details→property, property-amenities→property, read state→conversation/user, favorites→buyer/property, notifications→recipient | `CASCADE` | Dependent rows with no independent historical value (§17.1) |
| properties.updated_by→user, read state→last-read message, audit actor→user | `SET NULL` | Retain the record when the reference disappears (§13.1, §17.1) |

No `ON DELETE CASCADE` reaches conversations, messages, inquiries, or audit
records from properties or users.

### Seeds

| Migration | Seeded rows |
|---|---|
| V2 | `BUYER`, `AGENT`, `ADMIN` roles |
| V4 | `RESIDENTIAL`, `COMMERCIAL` categories |

Fixed UUIDs are used so a fresh database produces identical reference data.
Subtypes, amenities, and all transactional records are not seeded.

### V1 preservation

`V1__spring_session_schema.sql` was not modified, renamed, deleted, or
recreated. It contains only the Spring Session JDBC tables and indexes. No
business migration duplicates those tables. `git diff` on V1 was empty before
staging and after implementation.

### Design deviations

None. No business table, column, relationship, role, or endpoint was invented.
The only implementation choices beyond the table definitions are deterministic
seed UUIDs, constraint/index names, and the §17.1 delete actions described
above; each is recorded here.

---

## E. Stage 2.3 Verification

Environment: PostgreSQL 18.3, x86-64 Windows, throwaway cluster at
`localhost:55433`, trust authentication, separate `estatevista` and
`estatevista_test` databases. The normal development database was not touched.

| Verification | Status | Evidence |
|---|---|---|
| Fresh PostgreSQL database | PASS | Empty `estatevista` database created with `createdb` |
| Flyway V1 | PASS | `installed_rank = 1`, `success = true` |
| Flyway V2+ | PASS | Ranks 2–14 applied, all `success = true` |
| Schema constraints | PASS | 18 PKs, 28 FKs, 10 unique indexes, 1 partial unique index, 6 CHECKs queried from `pg_constraint` / `pg_indexes` |
| Indexes | PASS | 53 indexes present, including all §15 entries |
| Foreign keys | PASS | All 28 definitions match the approved relationships; composite subtype FK present |
| Flyway history | PASS | 14 rows, versions `1`–`14`, all successful |
| Migration ordering | PASS | Sequential `V1`→`V14` with no gaps |
| Migration reproducibility | PASS | Application restart reported "Current version of schema: 14" and "up to date"; no reapply |
| Application startup with migrated schema | PASS | Packaged jar started successfully; `/api/v1/health` and `/actuator/health` returned `UP` |
| Hibernate validation | PASS | `ddl-auto=validate`; application started with all 18 entities and no validation error |
| Case-insensitive email uniqueness | PASS | Direct SQL insert of `Case@Test.Example` then `case@test.example` rejected by `uk_users_email` |
| No unapproved schema objects | PASS | 18 tables, all traceable; indexes limited to §15 + declared keys/uniques |
| V1 unchanged | PASS | No diff |

---

## F. Stage 2.4 Implementation

### Entity classes

| Entity | Table | Identifier |
|---|---|---|
| `Role` | `roles` | `UUID` generated by application |
| `User` | `users` | `UUID` |
| `AgentProfile` | `agent_profiles` | `UUID` |
| `PropertyCategory` | `property_categories` | `UUID` |
| `PropertySubtype` | `property_subtypes` | `UUID` |
| `Property` | `properties` | `UUID` |
| `PropertyResidentialDetails` | `property_residential_details` | `UUID` shared PK |
| `PropertyCommercialDetails` | `property_commercial_details` | `UUID` shared PK |
| `PropertyImage` | `property_images` | `UUID` |
| `Amenity` | `amenities` | `UUID` |
| `PropertyAmenity` | `property_amenities` | `PropertyAmenityId` (`propertyId`, `amenityId`) |
| `Favorite` | `favorites` | `UUID` |
| `Inquiry` | `inquiries` | `UUID` |
| `Conversation` | `conversations` | `UUID` |
| `Message` | `messages` | `UUID` |
| `ConversationReadState` | `conversation_read_states` | `ConversationReadStateId` (`conversationId`, `userId`) |
| `Notification` | `notifications` | `UUID` |
| `AuditLog` | `audit_logs` | `UUID` |

A consistent UUID strategy is used: `@GeneratedValue(strategy = GenerationType.UUID)`
for application-generated identifiers. The database does not generate UUIDs,
so the same object graph is reproducible in tests and runtime.

### Enum mappings

| Enum | Values | Persistence |
|---|---|---|
| `AccountStatus` | `ACTIVE`, `INACTIVE`, `SUSPENDED` | `@Enumerated(EnumType.STRING)`, `VARCHAR(30)`, DB CHECK |
| `ListingType` | `SALE`, `RENT` | `@Enumerated(EnumType.STRING)`, `VARCHAR(20)`, DB CHECK |
| `PropertyStatus` | `DRAFT`, `PUBLISHED`, `UNAVAILABLE`, `SOLD`, `RENTED`, `ARCHIVED` | `@Enumerated(EnumType.STRING)`, `VARCHAR(30)`, DB CHECK |

No ordinal storage is used. Inquiry status, conversation status, message type,
and notification type remain `String` because the design does not finalize
their value sets; no Java enum was invented.

### Repository interfaces

All 18 repositories extend `JpaRepository<Entity, IdType>`. Two derived queries
are justified by documented requirements:

| Repository | Query | Justification |
|---|---|---|
| `RoleRepository` | `Optional<Role> findByName(String name)` | Unique role lookup for assigning the single account role (§5.1) |
| `UserRepository` | `Optional<User> findByEmailIgnoreCase(String email)` | Matches the case-insensitive email uniqueness contract (§5.2, §23.1 E) |

No JPQL, native SQL, pagination, arbitrary sorting, or speculative query methods
were added. No repository contains business workflow logic.

### Fetch, cascade, and optionality decisions

- Every association is `FetchType.LAZY`.
- No `CascadeType.ALL`, `CascadeType.REMOVE`, or `orphanRemoval=true` is used.
  Deletion behavior is owned by the database foreign keys, so JPA cannot
  silently delete history.
- Required associations use `optional=false` and `nullable=false` where the
  database requires them.
- Optional associations (`updatedBy`, `actor`, `lastReadMessage`) remain
  nullable.
- Shared-PK detail entities use `@MapsId` to bind their identity to the owning
  property.
- `AuditLog.beforeData` / `afterData` use `@JdbcTypeCode(SqlTypes.JSON)` with
  `String` payloads; `ip_address` uses `@JdbcTypeCode(SqlTypes.INET)` with
  `InetAddress`; `currency_code` uses `@JdbcTypeCode(SqlTypes.CHAR)`.
- No entity inheritance, Lombok, DTO exposure, or bidirectional collections are
  used.

### Deferred mappings

None required by the approved design. Role-specific eligibility, account
status, participant membership, last-read-message scoping, subtype/detail-table
consistency, and email normalization are intentionally service-layer rules
(§6.4, §14, §23.1 B/C).

---

## G. Stage 2.4 Verification

All checks ran against the migrated `estatevista_test` database on PostgreSQL
18.3. `BusinessSchemaIntegrationTest` uses the `test` profile and rolls back
each transaction, leaving Flyway seed data intact.

| Verification | Status | Evidence |
|---|---|---|
| Entity compilation | PASS | `mvn compile`: 52 source files, `BUILD SUCCESS` |
| Hibernate metadata initialization | PASS | Test asserts the metamodel contains exactly the 18 approved entity types |
| Schema validation | PASS | Application and test context start with `ddl-auto=validate` |
| Repository bean creation | PASS | Test resolves all 18 repository beans from the application context |
| Persistence tests | PASS | 11 PostgreSQL integration tests, 0 failures, 0 errors |
| Full-graph persistence and retrieval | PASS | Users, agent profile, property, residential details, image, amenity link, favorite, inquiry, conversation, message, read state, notification, and audit log persisted and reloaded |
| Relationship retrieval | PASS | Lazy associations resolved correctly inside the transaction; agent/creator IDs, conversation, and participant references verified |
| Constraint enforcement | PASS | Duplicate case-insensitive email, subtype/category mismatch, zero price, and second primary image all rejected by PostgreSQL |
| Enum storage | PASS | Database values verified as `ACTIVE`, `RENT`, and `DRAFT` text |
| Date/time and numeric mapping | PASS | `Instant`↔`TIMESTAMPTZ`, `BigDecimal` price/area/coordinates, `CHAR(3)` currency, `JSONB` snapshots, `INET` address verified |
| Nullable fields | PASS | Optional columns (phone, email verification, address fields, audit actor, read timestamps) were left NULL in persisted rows without error |
| No speculative entities or fields | PASS | 18 entities map one-to-one to the 18 approved tables |
| No services or endpoints | PASS | Only entities, embeddable IDs, enums, and repositories were added |

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
| Total tests | 17 |
| Failures | 0 |
| Errors | 0 |
| Skipped | 0 |
| Web-layer tests | 6 (`ApiErrorControllerTest` 2, `GlobalExceptionHandlerTest` 3, `HealthControllerTest` 1) |
| PostgreSQL integration tests | 11 |
| Build time (final run) | 49.369 s |
| Java compilation | `release 21`; runtime Java 23.0.2 |
| Database | PostgreSQL 18.3, isolated throwaway cluster on port 55433 |
| Warnings | Hibernate logged the five expected constraint violations produced by negative tests; Git reported only LF→CRLF normalization notices. No compiler warnings were observed. |

### Failed attempt recorded

An intermediate `mvn -B clean verify` run produced 11 errors because the
throwaway PostgreSQL cluster had stopped between commands (`Connection to
localhost:55433 refused`). This was an environment failure, not a code failure.
The cluster was restarted, the same command was re-run, and all 17 tests passed.
No code or schema change was made to work around it.

Running `mvn clean verify` without `TEST_DB_URL` executes the 6 web-layer tests
and skips the opt-in persistence tests; that mode is not represented as a full
persistence verification.

---

## I. Security and Scope Review

| Requirement | Status | Evidence |
|---|---|---|
| No JWT introduced | Confirmed | No JWT dependency, filter, token, or configuration added |
| No frontend framework introduced | Confirmed | No frontend files changed |
| Session-based architecture preserved | Confirmed | Spring Session JDBC and `V1` untouched |
| Deny-by-default preserved | Confirmed | `SecurityConfig` unchanged |
| `/ws` remains denied | Confirmed | No WebSocket configuration or matcher changed |
| SD-53 remains unimplemented | Confirmed | CSRF remains framework-default only; no authentication flow added |
| No authentication flows introduced | Confirmed | No login, registration, activation, or password-reset code |
| No business services or API endpoints introduced | Confirmed | Only persistence types and repositories |
| No unrelated functionality introduced | Confirmed | Scope limited to schema, entities, repositories, tests, documentation |
| V1 migration unchanged | Confirmed | `git diff` empty |
| Hibernate schema generation not used | Confirmed | `ddl-auto: validate` in all profiles |
| Credentials not hardcoded | Confirmed | Configuration uses environment placeholders; the test password default is empty |

---

## J. Files Changed

### Created — Stage 2.3 (13 files)

`backend/src/main/resources/db/migration/`:

- `V2__create_roles_and_users.sql`
- `V3__create_agent_profiles.sql`
- `V4__create_property_categories_and_subtypes.sql`
- `V5__create_properties_and_details.sql`
- `V6__create_property_images.sql`
- `V7__create_amenities.sql`
- `V8__create_favorites.sql`
- `V9__create_inquiries.sql`
- `V10__create_conversations_and_messages.sql`
- `V11__create_conversation_read_states.sql`
- `V12__create_notifications.sql`
- `V13__create_audit_logs.sql`
- `V14__add_business_indexes.sql`

### Created — Stage 2.4 (41 main Java files)

- `user/entity`: `AccountStatus.java`, `AgentProfile.java`, `Role.java`, `User.java`
- `user/repository`: `AgentProfileRepository.java`, `RoleRepository.java`, `UserRepository.java`
- `property/entity`: `Amenity.java`, `ListingType.java`, `Property.java`, `PropertyAmenity.java`, `PropertyAmenityId.java`, `PropertyCategory.java`, `PropertyCommercialDetails.java`, `PropertyImage.java`, `PropertyResidentialDetails.java`, `PropertyStatus.java`, `PropertySubtype.java`
- `property/repository`: `AmenityRepository.java`, `PropertyAmenityRepository.java`, `PropertyCategoryRepository.java`, `PropertyCommercialDetailsRepository.java`, `PropertyImageRepository.java`, `PropertyRepository.java`, `PropertyResidentialDetailsRepository.java`, `PropertySubtypeRepository.java`
- `favorite/entity`: `Favorite.java`; `favorite/repository`: `FavoriteRepository.java`
- `inquiry/entity`: `Inquiry.java`; `inquiry/repository`: `InquiryRepository.java`
- `chat/entity`: `Conversation.java`, `ConversationReadState.java`, `ConversationReadStateId.java`, `Message.java`; `chat/repository`: `ConversationReadStateRepository.java`, `ConversationRepository.java`, `MessageRepository.java`
- `notification/entity`: `Notification.java`; `notification/repository`: `NotificationRepository.java`
- `audit/entity`: `AuditLog.java`; `audit/repository`: `AuditLogRepository.java`

### Created — tests

- `backend/src/test/java/com/estatevista/persistence/BusinessSchemaIntegrationTest.java`

### Modified

- `backend/src/main/resources/application-test.yml` — clarified opt-in persistence-test behavior.
- `backend/README.md` — Stage 2.4 status, package/schema structure, migration table, test instructions.
- `docs/DEVELOPMENT_SETUP.md` — schema ownership, migration sequence, validation evidence, test prerequisites.
- `docs/validation/stage-2.3-2.4-completion-report.md` — this report.

### Deleted

None.

---

## K. Known Issues and Deferred Work

| ID | Description | Impact | Severity | Resolution stage | Blocks acceptance? |
|---|---|---|---|---|---|
| K-01 | No services, controllers, or API endpoints for these entities | Persistence exists but is not user-reachable | Expected | Stages 3–8 | No |
| K-02 | Inquiry status, conversation status, message type, and notification type are unconstrained `VARCHAR`; Java types are `String` | Database cannot reject unknown values until lifecycles are approved | Low | Workflow/chat stages | No |
| K-03 | Role eligibility, ownership, participant membership, and last-read-message scoping are not enforced by the database | SQL-level inserts can create semantically invalid rows | Medium | Service-layer stages | No — explicitly assigned to backend by §14 and §23.1 B/C |
| K-04 | Email normalization is required before persistence but is not implemented | A caller could store non-normalized case; uniqueness still holds via `LOWER(email)` | Low | Authentication/service stage | No |
| K-05 | Subtype/detail-table consistency (residential vs commercial) is not enforced by the database | A property can have the wrong or missing detail row until services enforce it | Medium | Property service stage | No — §6.4 assigns this to the backend |
| K-06 | Audit append-only is a design rule, not a database enforcement (no trigger or restricted role) | A privileged application path could update/delete audit rows | Medium | Audit implementation stage | No |
| K-07 | Persistence integration tests are conditional on `TEST_DB_URL` | A default build does not exercise PostgreSQL mappings | Medium | CI/test infrastructure stage | No |
| K-08 | No Testcontainers or automated database provisioning | Tests require an externally supplied isolated PostgreSQL instance | Low | Test infrastructure stage | No |
| K-09 | Seed identifiers are fixed implementation UUIDs | They are deterministic but must not be regenerated in future migrations | Low | Migration governance | No |
| K-10 | Hibernate validation checks tables/columns/types, not indexes or CHECK definitions | Constraint/index regressions are caught by the SQL verification test, not by startup | Low | Ongoing | No |
| K-11 | `application-test.yml` has an empty password default | A real test database must supply `TEST_DB_PASSWORD`; otherwise connection fails | Low | Test setup | No |
| K-12 | No physical retention/deletion job exists | FK actions define behavior only if deletion is ever performed; no retention policy is implemented | Low | Data governance stage | No |

No failed test or incomplete acceptance criterion is hidden in this section.

---

## L. Acceptance Assessment

### Stage 2.3 acceptance gate

| Criterion | Verdict | Explanation |
|---|---|---|
| Every implemented table traceable to approved documentation | PASS | 18/18 tables mapped in §C |
| V1 remains unchanged | PASS | No diff; content still framework-only |
| Business migrations sequential and valid | PASS | `V2`–`V14`, ranks 2–14 successful |
| Fresh PostgreSQL migration succeeds | PASS | Empty database migrated to version 14 |
| Expected constraints and indexes present | PASS | 28 FKs, 6 CHECKs, 53 indexes verified |
| Flyway history valid | PASS | 14 rows, all `success = true` |
| Hibernate validation succeeds against migrated schema | PASS | Application started with `ddl-auto=validate` |
| No unapproved schema objects introduced | PASS | All objects traceable; seeds limited to approved initial values |
| Verification evidence documented | PASS | §E, §H, and this report |

**Stage 2.3 verdict: PASS — APPROVED.**

### Stage 2.4 acceptance gate

| Criterion | Verdict | Explanation |
|---|---|---|
| Every entity maps to an approved business table | PASS | 18/18 |
| Every mapped column matches the verified schema | PASS | Type, length, nullability, precision verified by test and DDL review |
| Relationships match the approved database design | PASS | §C relationship matrix |
| No speculative entities or fields | PASS | No extra types, columns, or enums |
| Repository interfaces compile and initialize | PASS | 18 beans resolved from context |
| Hibernate schema validation succeeds | PASS | Context startup and tests |
| Required persistence tests pass | PASS | 11 integration tests pass |
| No business services or endpoints introduced | PASS | Scope review §I |
| Verification evidence documented | PASS | §G, §H, and this report |

**Stage 2.4 verdict: PASS — APPROVED.**

### Overall verdict

**APPROVED.** Both sub-stages satisfy their mandatory acceptance criteria against
real PostgreSQL. The known issues are documented constraints of the approved
design or explicitly deferred later-stage work; none invalidates the schema or
persistence foundation.

---

## M. Git and Commit Record

| Item | Value |
|---|---|
| Branch | `stage-2-backend-foundation` |
| Starting HEAD | `9c8ab6f54d0ccf4bee39bb9b73e3b23da077362d` |
| Stage 2.3 commit | `af2e274` — `feat(stage-2): add business schema and Flyway migrations` |
| Stage 2.4 commit | `fb8d73c` — `feat(stage-2): add JPA entities and repositories` |
| Report commit | The commit that adds this file |
| Final HEAD | Recorded by `git log -1` after the report commit |
| Working tree | Only the report and documentation changes remained before the final commit; no generated artifacts or secrets staged |
| Push | Not pushed |
| Merge into `main` | Not performed |

`V1__spring_session_schema.sql` was not part of any commit in this work.

---

## N. Next Stage Recommendation

Stage 2.5 may begin once its scope is confirmed by the project plan. Stage
2.3/2.4 introduce no blocking prerequisite for further backend work.

Prerequisites and cautions for the next stage:

1. Keep `V1`–`V14` immutable once shared; any schema correction must be a new
   migration.
2. Add service-layer validation for role eligibility, account status,
   participant membership, last-read-message ownership, subtype/detail
   consistency, and email normalization before exposing any operation.
3. Implement SD-53 completely with authentication; do not treat the current
   framework-default CSRF behavior as the approved design.
4. Keep `/ws` denied until session handshake interception, subscription
   authorization, and SD-53 wiring exist.
5. Supply an isolated PostgreSQL instance through `TEST_DB_URL` in CI so the 11
   persistence tests run on every change.
6. Do not merge this branch into `main` or push it without explicit
   instruction.
