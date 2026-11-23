# Architecture Decision Record — Durable Property Image Deletion Outbox

| Field | Value |
|---|---|
| Title | **Durable Property Image Deletion Outbox** |
| Status | **APPROVED BY PROJECT OWNER** — implementation authorized for Stage 4 only |
| Approval date | **22 November 2026** |
| Source | `docs/validation/STAGE_4_FINAL_CLOSURE_DECISION.md` §5 (proposed ADR) and the project owner's explicit approval of that proposal |
| Scope | Image-deletion architecture only. No Stage 5 work, deployment, or change to the approved authentication/authorization contracts. |
| Related records | `STAGE_4_DECISION_REGISTER.md` §10; `PROPERTY_DOMAIN_DESIGN.md` §5.2; `PROPERTY_API_DESIGN.md` §5.4; `DATABASE_DESIGN.md` §7.2; `API_SPECIFICATION.md` §23 |

## Approval record and review status

The project owner explicitly approved the §5 proposal on 22 November 2026. That approval is the sole authority for this ADR. Named reviewer roles in `SECURITY_ARCHITECTURE.md` §22.2 — backend reviewer, database reviewer, operations reviewer, and security reviewer — remain **Review pending**. This document must not be read as recording their approval, and no delegated or self-approval is claimed. Their pending review is preserved as a documentation/human-approval requirement for the production-readiness gate, not as permission to expand scope.

`SECURITY_ARCHITECTURE.md` §22.1 remains authoritative: design approval does not itself authorize deployment. C-3/C-5, the Draft UI/UX specification, Stage 3 findings F-1…F-8, the real Cloudinary smoke test, WebP glibc verification, and the deferred featured-property endpoint are unchanged by this decision.

---

## 1. Problem and consistency invariant

The previous `PropertyImageService.delete()` flow deleted the provider object first and the database row second:

1. `ImageStorage.delete(provider, publicId)` succeeded.
2. `imageRepository.delete(image); imageRepository.flush()` failed.
3. Spring rolled back the transaction, leaving an active `property_images` row referencing an asset whose bytes were already gone.

**Invariant:** no active image metadata row may point at an asset the backend has confirmed missing, and no committed metadata deletion may exist without a durable provider-cleanup task. A database rollback must preserve both the active image metadata and the provider asset. Provider failure must never restore already-deleted metadata. The `204 No Content` contract means the metadata removal and its cleanup task committed; provider cleanup is eventual.

## 2. Alternatives considered

| Alternative | Assessment | Outcome |
|---|---|---|
| Provider-first with a compensating database-failure delete | Smallest change, but cannot restore bytes when the compensating call also fails or the process crashes in the window. | Rejected |
| Provider-first with a best-effort cleanup task | The crash window with active metadata pointing at removed bytes remains. | Rejected |
| **Database-first deletion + durable outbox + after-commit worker** | One transaction makes metadata removal and the cleanup task atomic; provider I/O happens after commit and is retried idempotently. | **Approved** |
| Synchronous database-first delete without a durable task | A crash between commit and provider deletion loses cleanup work permanently. | Rejected |
| Restore the deleted provider bytes | No genuine restoration mechanism exists. | Rejected — no false claim made |

## 3. Approved architecture

1. Authenticate the caller; enforce ADMIN or currently assigned AGENT authorization.
2. Lock the property row with the existing approved `findByIdForUpdate` mechanism.
3. Reject mutations on `ARCHIVED` properties.
4. Verify image membership and ownership.
5. In the same transaction: remove the active `property_images` row, insert a `property_image_deletion_outbox` task, and audit metadata deletion.
6. Commit both database changes atomically.
7. Publish an `ImageDeletionRequested` event with `@TransactionalEventListener(phase = AFTER_COMMIT)`.
8. A backend-only worker claims due tasks and calls `ImageStorage.delete` **outside** any metadata transaction; it records `COMPLETED`, `FAILED`, or `DEAD_LETTER` in short independent transactions.
9. Return HTTP `204 No Content` after the metadata/outbox transaction commits, regardless of when provider cleanup finishes.

No automatic primary-image promotion occurs. Deleting a primary leaves the property with no primary until an authorized manager explicitly sets one (D-07/U-08; V6 partial unique index remains the final guard).

## 4. Transaction boundaries

- **Metadata transaction:** property lock, authorization checks, archived guard, image membership check, metadata delete, outbox insert, and the deletion audit row. Provider I/O is never inside it. A rollback leaves both the image row and the provider asset intact.
- **After-commit dispatch:** the event only submits a background claim pass through Spring's application task executor. Provider I/O and due-task claiming never delay the `204` response; the scheduled poller uses the same short claim transaction.
- **Claim transaction:** `SELECT … FOR UPDATE SKIP LOCKED` on due/expired-lease rows, mark `PROCESSING`, increment attempts, set a lease. It commits before provider I/O begins.
- **Completion/failure transactions:** short updates of the single task row plus a safe audit record. A database failure after a successful provider delete is handled as a retryable task failure; the next attempt observes provider absence as success.

## 5. Retry and idempotency semantics

- Bounded exponential backoff: `initial × 2^(attempt-1)`, capped by `max-backoff-ms`. Both delays are configurable.
- `max-attempts` bounds automatic retries. Exhausted tasks move to `DEAD_LETTER` with a safe failure class and are audited; nothing is silently discarded.
- Provider `not found`/`not_found` is recorded as `COMPLETED / ALREADY_MISSING`.
- Duplicate deletion requests and duplicate claims are harmless: a second API request sees `404`, and a repeated provider delete sees an absent asset.
- A unique key on `(storage_provider, storage_public_id)` prevents duplicate cleanup tasks at the database level.
- Deterministically permanent adapter errors (unsupported provider, unmanaged/malformed public ID, explicit provider rejection such as `invalid public id` or `unauthorized`) are classified as `PermanentProviderException` and dead-lettered immediately — never retried indefinitely. Unknown provider outcomes remain retryable up to the attempt limit.

## 6. Worker recovery

- Claim leases (`locked_until`) expire, so a task claimed by a crashed worker becomes claimable again. A `PROCESSING` row with an expired lease is selected by the same query.
- Tasks are ordinary Flyway-managed PostgreSQL rows: they survive application restart; there is no in-memory queue.
- `FOR UPDATE SKIP LOCKED` prevents multiple worker instances from processing the same task concurrently.
- All state changes are transactional; provider calls are never long-running database transactions.
- Configuration (prefix `app.image-cleanup`, env override in parentheses): `enabled` (`APP_IMAGE_CLEANUP_ENABLED`), `poll-ms` (`…_POLL_MS`), `batch-size` (`…_BATCH_SIZE`), `max-attempts` (`…_MAX_ATTEMPTS`), `backoff-ms` (`…_BACKOFF_MS`), `max-backoff-ms` (`…_MAX_BACKOFF_MS`), `lease-seconds` (`…_LEASE_SECONDS`).

## 7. Cloudinary deletion behavior

- The backend adapter continues to own all provider credentials; the browser never receives them and never calls Cloudinary directly.
- Storage public-ID validation and secure delivery-URL host/path validation remain intact; no upload or stored-URL validation was weakened.
- Successful `ok` is success; confirmed absence is success; transport/timeouts and unknown outcomes remain retryable; deterministic permanent errors are classified as permanent.
- No provider response body, credential, signature, or token is placed in an API response, outbox row, audit row, or log. Only exception class names are stored as diagnostics.
- Real-provider smoke testing is a production prerequisite only; deterministic adapter tests cover the classification logic locally.

## 8. Database migration impact

- Exactly one new migration, `V17__create_property_image_deletion_outbox.sql`. V1–V16 are unchanged; Flyway remains authoritative and `ddl-auto: validate` remains enabled.
- The table carries a UUID task ID, provider, storage public ID, property ID, image ID, actor ID, status CHECK, nonnegative attempt CHECK, next-attempt/last-attempt/completion/creation/update timestamps, lease, and safe diagnostic metadata.
- Indexes support due-task selection and lease recovery. A `RESTRICT` FK to `properties` prevents physical property deletion from erasing pending work; there is no FK to `property_images` or `users`, so deleting active metadata or an actor cannot cascade into the outbox. No image bytes, secrets, access tokens, signatures, or credentials are stored.

## 9. Operational responsibilities

Visibility is through the database and existing audit/log conventions; no new ADMIN-facing cleanup API is introduced.

1. **Pending tasks:** `SELECT id, property_id, image_id, attempts, next_attempt_at FROM property_image_deletion_outbox WHERE status IN ('PENDING','FAILED') ORDER BY next_attempt_at;`
2. **Stale claims:** `SELECT id, attempts, locked_until FROM property_image_deletion_outbox WHERE status = 'PROCESSING' AND locked_until < now();`
3. **Exhausted tasks:** `SELECT * FROM property_image_deletion_outbox WHERE status IN ('DEAD_LETTER','FAILED') ORDER BY updated_at;`
4. **Authorized manual requeue (record the actor and reason in the change ticket):**

   ```sql
   BEGIN;
   UPDATE property_image_deletion_outbox
      SET status = 'PENDING', attempts = 0, next_attempt_at = now(),
          locked_until = NULL, last_error = NULL, updated_at = now()
    WHERE id = '<task-id>' AND status IN ('DEAD_LETTER', 'FAILED');
   INSERT INTO audit_logs (id, action, entity_type, entity_id, actor_id, before_snapshot, after_snapshot, created_at)
   VALUES (gen_random_uuid(), 'PROPERTY_IMAGE_CLEANUP', 'PROPERTY_IMAGE',
           (SELECT image_id FROM property_image_deletion_outbox WHERE id = '<task-id>'),
           '<operator-user-id>', NULL,
           '{"outcome":"MANUAL_REQUEUE","taskId":"<task-id>"}', now());
   COMMIT;
   ```

   Adjust `audit_logs` column names to the current schema if needed; the audit row is mandatory so a manual retry is attributable.
5. **Confirm provider cleanup:** query the provider by the stored public ID with a backend-side tool only; a missing asset confirms cleanup. Do not expose provider tooling to the browser.
6. **Investigate persistent failures:** inspect `last_error` (class name only), audit `PROPERTY_IMAGE_CLEANUP` outcomes, worker WARN logs, and cluster/database state. Requeue only after the underlying cause is fixed.
7. Completed rows may be pruned only under an approved retention policy; no automatic pruning is performed by this decision.

Safe outcomes recorded in audit/logging: `TASK_CREATED`, `SUCCESS`, `ALREADY_MISSING`, `RETRY_SCHEDULED`, `DEAD_LETTER`, `PERMANENT_FAILURE`, and manual `MANUAL_REQUEUE`.

## 10. Security boundaries

- Existing session/CSRF/CORS/deny-by-default controls, principal-derived actor identity, property ownership, RBAC, and archived immutability are unchanged.
- The worker is backend-only. No browser-to-Cloudinary deletion, no internal outbox identifier in API responses, and no worker state or credentials exposed to the frontend.
- Outbox and audit rows contain identifiers and safe outcome classes only.

## 11. API compatibility

All five approved image endpoints are preserved:

- `POST /api/v1/properties/{propertyId}/images`
- `GET /api/v1/properties/{propertyId}/images`
- `PATCH /api/v1/properties/{propertyId}/images/{imageId}`
- `PATCH /api/v1/properties/{propertyId}/images/{imageId}/primary`
- `DELETE /api/v1/properties/{propertyId}/images/{imageId}`

`DELETE` still returns `204 No Content` with no body after durable database deletion. Existing `400/401/403/404/409/500/503` error mapping is unchanged. External provider cleanup may complete asynchronously; the frontend must not assume it finished at `204`, attempt direct provider deletion, poll an invented endpoint, or receive internal outbox identifiers.

## 12. Test and acceptance criteria

- Deterministic real-PostgreSQL tests with only the external provider boundary faked; no H2 substitute.
- All 15 mandated deletion scenarios pass, plus database failure after provider cleanup, deletion-audit failure rollback, worker crash/lease recovery, multiple concurrent workers, non-blocking single-claim after-commit dispatch, backoff boundaries, dead-letter transition, manual requeue, outbox uniqueness, migration validation, no primary auto-promotion, and no unexpected property/image FK cascade.
- Full `mvn verify`, Flyway V1–V17 validation, Cloudinary adapter unit tests, security/authorization, rate-limit, lifecycle, and EXIF regression all pass.
- Frontend static checks, Playwright regression, and a live browser-to-backend run with real sessions, CSRF tokens, and PostgreSQL pass; responsive widths 320–1440 verified.
- No unresolved Critical/High Stage 4 defect remains; deployment-only prerequisites and human approvals are documented, not silently resolved.
