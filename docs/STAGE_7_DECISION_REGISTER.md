# Stage 7 Decision Register

**Status:** APPROVED — OWNER DECISIONS RECORDED; IMPLEMENTATION NOT EXECUTED
**Architecture stage:** Stage 7.1 — Property-Specific Real-Time Chat
**Verified starting baseline:** `f894488064f22f058f3f719b67b7fe1c30b53fd7` (Stage 6 final)
**Feature branch:** `stage-7-realtime-chat`
**Scope:** documentation, contracts, decision reconciliation and acceptance planning only. No chat implementation is authorized by this register.

This register follows the structure of `docs/STAGE_6_DECISION_REGISTER.md`. It records previously approved decisions without reopening them, records the owner-approved Stage 7.1 decisions P-01–P-13, resolves findings F-7.1-01–F-7.1-08, and preserves deferred requirements. Approval closes Stage 7.1 at the documentation and architecture-contract level only; it does not execute any acceptance scenario or authorize code, migration, dependency, or deployment changes beyond their documented future stages.

---

## 1. Authority hierarchy

1. Owner-approved product decisions: `docs/PRD.md` §14 (PD-06–PD-09).
2. Approved functional requirements: `docs/FRD.md` §9 (FR-CHAT-001–014).
3. Owner-approved Stage 6 boundary: `docs/STAGE_6_DECISION_REGISTER.md`, especially Decision 9 and SD-STG7-01.
4. Approved technical designs: `docs/API_SPECIFICATION.md` §11/§20/§25, `docs/DATABASE_DESIGN.md` §11/§18/§23, `docs/SECURITY_ARCHITECTURE.md` §12/§21.
5. Draft guidance: `docs/UI_UX_SPECIFICATION.md` (chat sections are guidance, not blanket approval).
6. Local ignored validation evidence under `docs/validation/` (evidence only, not design authority).

Where sources conflict, the higher-authority source controls. Confirmed lower-level contradictions were corrected in Stage 7.1 and are recorded in §6.

---

## 2. Already approved and verified decisions

These decisions are not reopened. They are recorded with implementation evidence so that Stage 7 does not assume unimplemented functionality.

### A-01 — One property-specific conversation per buyer-property pair

- **Existing authoritative requirement:** PRD PD-06/PD-07; FRD FR-CHAT-002/FR-CHAT-003; API §11.1; V10 DDL.
- **Current implementation evidence:** `conversations` table has `UNIQUE (buyer_id, property_id)` (`V10__create_conversations_and_messages.sql`); `Conversation` entity maps buyer, property and historical assigned agent; `BusinessSchemaIntegrationTest.rejectsDuplicateConversationForTheSameBuyerAndProperty` asserts the constraint. No chat service exists.
- **Identified conflict or gap:** None in the rule. Repository query and get-or-create service are missing.
- **Available options:** None — approved rule is retained.
- **Recommended option:** Preserve permanent uniqueness; implement idempotent get-or-create at REST.
- **Technical justification:** The constraint is the concurrency authority; it prevents duplicate histories and avoids a separate application-level uniqueness race.
- **Security implications:** Prevents duplicate private transcripts and ambiguous participant sets.
- **Database implications:** Existing `uk_conversations_buyer_property` is sufficient; do not replace with a partial/unique-on-active rule.
- **API implications:** Duplicate POST returns `200` with the existing conversation; new POST returns `201`.
- **Frontend implications:** Disable submit while in flight; treat `200` and `201` as successful navigation to the same conversation.
- **Affected documentation:** PRD §14; FRD §9; API §11.1; DATABASE_DESIGN §11/§18.
- **Approval status:** APPROVED AND VERIFIED.
- **Assigned implementation sub-stage:** 7.3.

### A-02 — Historical conversation retention and immutable original agent

- **Existing authoritative requirement:** PRD PD-08/PD-09; FRD FR-CHAT-011/FR-CHAT-012; DATABASE_DESIGN §18; API §25.1.E/F.
- **Current implementation evidence:** `conversations.assigned_agent_id` is a snapshot; no endpoint updates it; `ResourceAuthorizationIntegrationTest.conversationAccessUsesOriginalAssignmentNotTheCurrentPropertyAgent` and `PropertyAssignmentIntegrationTest.historicalConversationAssignmentAndMessagesRemainUnchanged` verify reassignment preservation; no inquiry-conversation FK exists.
- **Identified conflict or gap:** The product limitation caused by permanent uniqueness after reassignment is closed by P-02: no second conversation with the newly assigned agent is created and no history is transferred.
- **Available options:** None — retention and no silent transfer are approved.
- **Recommended option:** Preserve history and original agent on reassignment, deactivation, unpublication, sale, rental and archival.
- **Technical justification:** Reassignment is a property-management event, not a conversation-membership event.
- **Security implications:** Prevents unauthorized lateral access by a new property agent.
- **Database implications:** No cascade/delete/rewrite of conversation or message rows.
- **API implications:** Conversation access uses `conversations.assigned_agent_id`, never `properties.primary_agent_id`.
- **Frontend implications:** Historical conversations remain visible to original participants with property context marked unavailable where applicable.
- **Affected documentation:** DATABASE_DESIGN §18/§23; SECURITY_ARCHITECTURE §10.7/§12.9; UI_UX §8.7/§9.8.
- **Approval status:** APPROVED AND VERIFIED.
- **Assigned implementation sub-stage:** 7.3/7.4.

### A-03 — Explicit buyer initiation with no initial message in creation

- **Existing authoritative requirement:** API §11.1 and §25.1.A; STAGE_6 Decision 9.
- **Current implementation evidence:** API contract specifies no request body; first message goes through `/app/chat.send`. A stale DATABASE_DESIGN §18 statement that creation stored the first message was corrected in Stage 7.1.
- **Identified conflict or gap:** No chat service/controller/handler exists; frontend property page currently exposes only inquiry actions.
- **Available options:** None — approved separation is retained.
- **Recommended option:** Create/retrieve first; send the first message only through STOMP after the conversation response.
- **Technical justification:** Avoids two mutation mechanisms, keeps creation idempotent and ensures all messages share validation, persistence and delivery paths.
- **Security implications:** No message content in a REST creation body or URL.
- **Database implications:** No message row is written during creation.
- **API implications:** `POST /properties/{propertyId}/conversations` accepts no `initialMessage`; unknown fields are rejected.
- **Frontend implications:** Property-page action creates/retrieves then opens the composer; it does not submit a message.
- **Affected documentation:** API §11.1/§25.1.A; DATABASE_DESIGN §18 (corrected).
- **Approval status:** APPROVED AND VERIFIED.
- **Assigned implementation sub-stage:** 7.3/7.4/7.5.

### A-04 — Server-derived buyer and agent identity with eligibility checks

- **Existing authoritative requirement:** FR-CHAT-002; API §11.1; SECURITY_ARCHITECTURE §10.7.
- **Current implementation evidence:** Stage 6 `InquiryService` demonstrates the trusted pattern: `AuthenticatedAccount` supplies buyer identity; property supplies current primary agent; buyer/agent status and roles are rechecked. Stage 7 chat service is absent.
- **Identified conflict or gap:** Closed by P-13: new creation explicitly requires a `PUBLISHED` property, an active buyer, and an active current agent.
- **Available options:** None for server derivation; property eligibility is approved by P-13.
- **Recommended option:** Derive buyer from session and agent from `properties.primary_agent_id` inside a transaction; ignore client identity fields.
- **Technical justification:** Client-supplied ownership fields are a known spoofing vector; `ResourceAuthorizationService` and Stage 6 tests already enforce trusted identity patterns.
- **Security implications:** Prevents buyer/agent impersonation and assignment spoofing.
- **Database implications:** No schema change; property and user rows are locked in stable UUID order before validation.
- **API implications:** Request body is empty; protected fields are rejected by `UnknownFieldGuard`.
- **Frontend implications:** Never send identity or assignment fields.
- **Affected documentation:** API §11.1; SECURITY_ARCHITECTURE §10.7/§12.9.
- **Approval status:** APPROVED AND VERIFIED.
- **Assigned implementation sub-stage:** 7.3.

### A-05 — Inquiry, favorites and conversations are independent resources

- **Existing authoritative requirement:** STAGE_6 Decision 9; V18 migration header; DATABASE_DESIGN §24.4; API §11.
- **Current implementation evidence:** No inquiry-conversation FK or link column; no chat endpoint created in Stage 6; favorites table has only buyer/property uniqueness; inquiry submission does not create conversations.
- **Identified conflict or gap:** UI_UX §7.3.1 contains historical two-channel text, already marked superseded for Stage 6; Stage 7 chat CTA still needs approval (P-01).
- **Available options:** None — independence is approved.
- **Recommended option:** No automatic mutation across resources in either direction.
- **Technical justification:** Business boundaries were explicitly approved in Stage 6 and must not be reintroduced through UI or lifecycle coupling.
- **Security implications:** Prevents unintended data exposure through inquiry/admin surfaces.
- **Database implications:** No cross-resource FK, trigger or lifecycle cascade.
- **API implications:** No endpoint mutates another resource as a side effect.
- **Frontend implications:** Chat CTA is separate from inquiry CTA and does not require a favorite or inquiry.
- **Affected documentation:** STAGE_6_DECISION_REGISTER Decision 9/SD-STG7-01; DATABASE_DESIGN §24.4.
- **Approval status:** APPROVED AND VERIFIED.
- **Assigned implementation sub-stage:** 7.3–7.5.

### A-06 — Participant-only access; administrators denied

- **Existing authoritative requirement:** FR-CHAT-010; API §11; SECURITY_ARCHITECTURE SD-21/SD-36; UI D-16.
- **Current implementation evidence:** `ResourceAuthorizationService.requireConversationAccess` allows only buyer and original assigned agent and explicitly denies ADMIN; `ResourceAuthorizationIntegrationTest` verifies denial of other buyer, other agent and admin; admin inquiry views state they do not open conversations.
- **Identified conflict or gap:** PRD §15's former administrative-access open question is resolved in C-04; no exception is approved.
- **Available options:** None — denial is retained unless owner explicitly approves an exception.
- **Recommended option:** Deny ADMIN list, detail, history, send, read-state and status access.
- **Technical justification:** Conversation transcripts are private communications; inquiry access does not imply chat access.
- **Security implications:** Prevents privileged disclosure; unauthorized resources return generic `404`.
- **Database implications:** No admin-scoped chat query or view.
- **API implications:** All chat endpoints call participant checks before loading/returning content.
- **Frontend implications:** No admin conversation navigation or transcript rendering.
- **Affected documentation:** API §11/§25.1.E; SECURITY_ARCHITECTURE §10.4.6/§12; UI_UX §10.7/§18.2/§20.5.
- **Approval status:** APPROVED AND VERIFIED.
- **Assigned implementation sub-stage:** 7.2–7.5.

### A-07 — Native WebSocket/STOMP allowlist contract

- **Existing authoritative requirement:** API §20; SECURITY_ARCHITECTURE §12.
- **Current implementation evidence:** `WebSocketConfig` registers native `/ws` with exact allowed origins, `/app` application prefix, `/user` user prefix and simple broker; no SockJS; `StompAuthorizationInterceptor` allows only three user queues and `/app/chat.send`. No message handler exists.
- **Identified conflict or gap:** No STOMP client dependency is present in frontend; see P-11.
- **Available options:** None for native WebSocket; client delivery method is approved by P-11 with verification gates.
- **Recommended option:** Keep native WebSocket `/ws`, three user queues and one SEND destination.
- **Technical justification:** No SockJS HTTP fallback is needed or approved; exact queues avoid unrestricted conversation topics.
- **Security implications:** Cross-origin WebSocket connections are rejected; no wildcard or conversation-topic subscriptions.
- **Database implications:** None.
- **API implications:** No REST mutation path duplicates STOMP SEND.
- **Frontend implications:** Client must use native `WebSocket` and STOMP frames over the approved URL helper.
- **Affected documentation:** API §20; SECURITY_ARCHITECTURE §12; DEVELOPMENT_SETUP §8/§11.
- **Approval status:** APPROVED AND VERIFIED.
- **Assigned implementation sub-stage:** 7.2/7.4/7.5.

### A-08 — Persist before broadcast and never acknowledge before commit

- **Existing authoritative requirement:** API §20.7; SECURITY_ARCHITECTURE §12.13.
- **Current implementation evidence:** No message handler exists; Stage 6 audit pattern demonstrates transactional writes with `saveAndFlush`.
- **Identified conflict or gap:** Broker publishing, application-level persistence acknowledgement semantics and failed-broadcast recovery are not implemented; F-7.1-01 is resolved in §10.
- **Available options:** None — approved ordering is retained.
- **Recommended option:** Validate → authorize → rate-limit → persist/flush in transaction → commit → publish to user queues → send the application-level `PERSISTENCE_ACK` to the sender only.
- **Technical justification:** A failed publish must not undo a durable message; a client must not see success for an uncommitted write.
- **Security implications:** Prevents false delivery claims and stale optimistic UI state.
- **Database implications:** Message insert and required audit/unread writes share one transaction; publish occurs after commit.
- **API implications:** REST history is the recovery path when publish fails.
- **Frontend implications:** Show `Sent` only after the matching application-level persistence acknowledgement; show `Failed` on explicit error or timeout, then use the manual recovery procedure.
- **Affected documentation:** API §20.7; SECURITY_ARCHITECTURE §12.13.
- **Approval status:** APPROVED AND VERIFIED.
- **Assigned implementation sub-stage:** 7.4.

### A-09 — Server-generated message identity, timestamp and stable ordering

- **Existing authoritative requirement:** FR-CHAT-008/FR-CHAT-013; SECURITY_ARCHITECTURE §12.13.
- **Current implementation evidence:** `Message.id` uses `GenerationType.UUID`; `sentAt` uses `@CreationTimestamp`; `idx_messages_conversation_sent(conversation_id, sent_at)` exists.
- **Identified conflict or gap:** Closed by P-06 and F-7.1-04: retrieval is latest-first by `(sent_at DESC, id DESC)` with a deterministic cursor; the client renders the loaded window in chronological order.
- **Available options:** None — the approved contract is retained.
- **Recommended option:** Order history by `(sent_at DESC, id DESC)` for retrieval and render the returned window chronologically.
- **Technical justification:** `sent_at` alone is not unique under concurrent commits; UUID tiebreaker is deterministic without a new sequence column.
- **Security implications:** Consistent ordering prevents client-side reordering ambiguity.
- **Database implications:** V19 adds the order index `(conversation_id, sent_at, id)`; no monotonic sequence column is added without further owner approval.
- **API implications:** `GET /conversations/{id}/messages` returns a latest-first cursor page with `nextCursor`/`hasMore`; the client renders chronologically.
- **Frontend implications:** Deduplicate by server message ID; append by server order.
- **Affected documentation:** API §11.2; SECURITY_ARCHITECTURE §12.13; UI_UX §11.5.
- **Approval status:** APPROVED AND VERIFIED; ordering detail approved in P-06.
- **Assigned implementation sub-stage:** 7.3/7.4.

### A-10 — Approved MVP exclusions

- **Existing authoritative requirement:** SECURITY_ARCHITECTURE §12; UI_UX §11.1/§20.5; STAGE_6 Decision 9.
- **Current implementation evidence:** No read/delivery receipt UI, typing indicator, editing, deletion, attachment, presence or search code exists; `edited_at`/`deleted_at` columns exist only as deferred schema fields.
- **Identified conflict or gap:** None; do not implement excluded features by assumption.
- **Available options:** None — exclusions are retained.
- **Recommended option:** Defer typing, editing, deletion, attachments, presence, search, read receipts and delivery receipts.
- **Technical justification:** These are outside approved MVP requirements and would expand the security/privacy surface.
- **Security implications:** Avoids additional mutation paths and metadata disclosure.
- **Database implications:** Do not add columns or constraints for deferred features in Stage 7.
- **API implications:** No endpoints/destinations for deferred features.
- **Frontend implications:** No “Seen”, “Delivered”, typing, edit, delete or attachment controls.
- **Affected documentation:** SECURITY_ARCHITECTURE §12.14; UI_UX §11.1/§20.5.
- **Approval status:** APPROVED AND VERIFIED; exclusions preserved.
- **Assigned implementation sub-stage:** Deferred beyond Stage 7.

### A-11 — Preserved session, CSRF, origin, no-store and fail-closed controls

- **Existing authoritative requirement:** PRD/FRD security requirements; SECURITY_ARCHITECTURE §11; STAGE_6 Decision register §9/§14.
- **Current implementation evidence:** `SecurityConfig`, `AuthOriginValidationFilter`, `CorsConfig`, `SecureCsrfTokenRepository`, `PrivateResponseNoStoreFilter`, `SessionAuthorizationFilter`, `ResourceAuthorizationService` and `ApiErrorResponse` are implemented and tested.
- **Identified conflict or gap:** Chat endpoints and STOMP frames are not yet wired to these controls; F-3 design is approved in §10 and implementation remains a Stage 7.2 gate.
- **Available options:** None — controls are retained.
- **Recommended option:** Reuse existing filters, authorization service and error envelope; do not create a parallel auth mechanism.
- **Technical justification:** Reuse preserves verified Stage 6 controls and avoids security regression.
- **Security implications:** Deny-by-default, exact-origin, session-bound CSRF and generic unauthorized responses remain intact.
- **Database implications:** None.
- **API implications:** All private chat responses use `Cache-Control: no-store`; errors use the standard envelope.
- **Frontend implications:** Reuse `api.js`, `auth-state.js` and safe DOM helpers.
- **Affected documentation:** SECURITY_ARCHITECTURE §11/§18; API §2/§17.
- **Approval status:** APPROVED AND VERIFIED.
- **Assigned implementation sub-stage:** 7.2–7.5.

### A-12 — Single-instance deployment constraint

- **Existing authoritative requirement:** STAGE_6 Decision register §14; SECURITY_ARCHITECTURE §22.9; F-7 single-instance resolution.
- **Current implementation evidence:** `RateLimitService` uses PostgreSQL advisory locks; F-7 multi-instance CSRF consistency is not approved; simple in-memory STOMP broker is configured.
- **Identified conflict or gap:** None in constraint; P-12 approves same-site custom domains, while exact hostnames remain deployment-verification values and do not block Stage 7.1.
- **Available options:** None — no multi-instance claim.
- **Recommended option:** Keep single-instance Stage 7 deployment; no Redis or external broker.
- **Technical justification:** Cross-instance CSRF/session consistency is unresolved and is a Stage 9 gate.
- **Security implications:** Prevents false scale-out security claims.
- **Database implications:** Sessions remain in Spring Session JDBC; messages remain in PostgreSQL.
- **API implications:** Same-origin/CORS and WebSocket origin allowlists are exact per instance.
- **Frontend implications:** Direct REST/WSS to API host; no proxy assumptions.
- **Affected documentation:** SECURITY_ARCHITECTURE §8.5/§21.1/§22.9; DEVELOPMENT_SETUP §11.
- **Approval status:** APPROVED AND VERIFIED.
- **Assigned implementation sub-stage:** 7.2/7.7/Stage 9.

### A-13 — F-3 gate before Stage 7 closure

- **Existing authoritative requirement:** SECURITY_ARCHITECTURE §12.15/§18.8; STAGE_6 register §9; Stage 3/5/6 validation reports.
- **Current implementation evidence:** No session-destroyed listener, WebSocket session registry or close-on-logout logic exists; `AuthController.logout` invalidates HTTP session and clears the cookie but does not close sockets.
- **Identified conflict or gap:** F-3 design is approved now and implemented in 7.2; session-destroyed events alone are not authoritative.
- **Available options:** The approved design is recorded in §10 of this register.
- **Recommended option:** Implement authoritative session existence/expiry validation, a WebSocket session registry, bounded revalidation, logout/revocation integration, rotation handling and per-frame authorization in 7.2 before any chat message feature ships.
- **Technical justification:** HTTP session invalidation alone does not terminate an established WebSocket.
- **Security implications:** Prevents continued authenticated access after logout/expiry/deactivation.
- **Database implications:** No new session table; use Spring Session JDBC tables and audit logs.
- **API implications:** Logout must proactively close sockets before/with session invalidation.
- **Frontend implications:** Logout and auth revision changes close sockets and clear private state.
- **Affected documentation:** SECURITY_ARCHITECTURE §12.15/§18.8; validation F-3 evidence.
- **Approval status:** APPROVED REQUIREMENT; IMPLEMENTATION OPEN.
- **Assigned implementation sub-stage:** 7.2.

---

## 3. Approved requirements requiring implementation

These are approved requirements, not new proposals. They are listed with their implementation gap and assigned sub-stage.

### B-01 — Conversation persistence and query layer

- **Authoritative requirement:** A-01/A-02/A-04.
- **Implementation evidence:** Entities and V10/V11 schema exist; three repositories are empty stubs; no service, DTO or controller.
- **Gap:** Get-or-create, participant-scoped list/detail queries and read-state initialization.
- **Recommendation:** Implement in 7.3 using the unique constraint and Stage 6 locking/refresh pattern.
- **Security implications:** All queries must be participant-scoped; no unscoped `findById` response.
- **Database implications:** V19 is approved for the idempotency constraint and supporting indexes; no other conversation schema change is authorized.
- **API implications:** `POST /properties/{id}/conversations`, `GET /conversations`, `GET /conversations/{id}`.
- **Frontend implications:** Buyer/agent conversation lists consume these endpoints.
- **Affected documentation:** API §11; DATABASE_DESIGN §18.
- **Approval status:** APPROVED — IMPLEMENTATION PENDING.
- **Assigned implementation sub-stage:** 7.3.

### B-02 — Conversation REST lifecycle and metadata

- **Authoritative requirement:** API §11; PRD PD-08.
- **Implementation evidence:** No REST controller; `PATCH /status` documented but no transition table or implementation.
- **Gap:** P-04 is approved: no conversation status mutation is implemented in Stage 7.
- **Recommendation:** Implement create/list/detail only; keep `PATCH /status` documented as deferred and unmapped.
- **Security implications:** Original-agent-only if later approved; ADMIN denied.
- **Database implications:** No status CHECK; P-04 defers all status mutation beyond Stage 7.
- **API implications:** Use standard success/error envelopes and no-store.
- **Frontend implications:** No status control until approved.
- **Affected documentation:** API §11/§25.1.E; SECURITY_ARCHITECTURE §10.4.6.
- **Approval status:** APPROVED for create/list/detail; status mutation DEFERRED beyond Stage 7 by P-04.
- **Assigned implementation sub-stage:** 7.3.

### B-03 — STOMP message handler and durable persistence

- **Authoritative requirement:** A-03/A-08/A-09; API §20; FRD FR-CHAT-004/005.
- **Implementation evidence:** Interceptor and broker exist; no `@MessageMapping`, persistence or broadcast.
- **Gap:** Validation, mandatory idempotency key, transaction, post-commit publish, sender-only persistence acknowledgement and error delivery.
- **Recommendation:** Implement `/app/chat.send` handler in 7.4.
- **Security implications:** Sender derived from principal; membership rechecked per frame; no client sender ID.
- **Database implications:** Insert into `messages` with mandatory `idempotency_key`; V19 unique constraint is the duplicate authority.
- **API implications:** No REST SEND duplicate.
- **Frontend implications:** Composer sends via STOMP only.
- **Affected documentation:** API §20/§25.1; SECURITY_ARCHITECTURE §12.13.
- **Approval status:** APPROVED — IMPLEMENTATION PENDING.
- **Assigned implementation sub-stage:** 7.4.

### B-04 — Paginated message history

- **Authoritative requirement:** FR-CHAT-006/FR-CHAT-013; API §11.2.
- **Implementation evidence:** `MessageRepository` is empty; V14 index exists; no controller.
- **Gap:** Participant-scoped cursor query with deterministic order.
- **Recommendation:** Implement latest-first `(sent_at DESC, id DESC)` cursor retrieval and chronological rendering.
- **Security implications:** Unauthorized conversation IDs return generic `404`.
- **Database implications:** Use the approved V19 `(conversation_id, sent_at, id)` index; no sequence column.
- **API implications:** Cursor metadata (`nextCursor`, `hasMore`, `limit`); no message content in URLs.
- **Frontend implications:** Render with `role="log"` and dedupe by ID.
- **Affected documentation:** API §11.2/§12; UI_UX §11.
- **Approval status:** APPROVED; cursor contract approved in P-06.
- **Assigned implementation sub-stage:** 7.3/7.4.

### B-05 — Read-state update and unread information

- **Authoritative requirement:** FR-CHAT-009; API §11/§12; SECURITY_ARCHITECTURE §12.14.
- **Implementation evidence:** V11 table, entity and composite ID exist; repository is empty; no REST handler; no unread query.
- **Gap:** Own-read update, same-conversation validation and own unread count.
- **Recommendation:** Implement REST-only monotonic read-state update; do not expose other participant’s read position.
- **Security implications:** `requireReadStateOwner` must be called; no read receipts.
- **Database implications:** Optional user-leading index in P-05/V19.
- **API implications:** `PATCH /conversations/{id}/read-state` and `unreadCount` in conversation responses (P-10).
- **Frontend implications:** Own unread badges only; no “Seen” indicator.
- **Affected documentation:** API §11/§12; SECURITY_ARCHITECTURE §12.14; UI_UX §11.1/§11.3.
- **Approval status:** APPROVED; monotonic read semantics approved in P-07 and unread surface in P-10.
- **Assigned implementation sub-stage:** 7.3/7.6.

### B-06 — Reconnection and missed-message recovery

- **Authoritative requirement:** FR-CHAT-014; SECURITY_ARCHITECTURE §12.15; UI_UX §11.11.
- **Implementation evidence:** `getWebSocketUrl()` exists in frontend config; no client consumes it; no resync logic.
- **Gap:** Resubscribe, REST refresh and ID-based deduplication.
- **Recommendation:** P-09 is approved: reuse existing REST endpoints and cursor history only; no dedicated sync endpoint.
- **Security implications:** Re-handshake and re-authenticate on reconnect; stop on auth failure.
- **Database implications:** Messages are durable in PostgreSQL.
- **API implications:** History is the recovery authority.
- **Frontend implications:** Backoff with jitter; reconnect banner; private state preserved only in memory.
- **Affected documentation:** SECURITY_ARCHITECTURE §12.15; UI_UX §11.10/§11.11.
- **Approval status:** APPROVED — IMPLEMENTATION PENDING.
- **Assigned implementation sub-stage:** 7.6.

### B-07 — Chat rate and size limits

- **Authoritative requirement:** SECURITY_ARCHITECTURE §12 limits; API §20.
- **Implementation evidence:** `RateLimitService` has generic scopes and advisory locks; no chat scope; no STOMP size/rate enforcement.
- **Gap:** 30 messages/min/user, 10 messages/min/conversation per sender, 5 sockets/user, 20 connection attempts/IP/min, 32 KB frame and 2,000-character content enforcement.
- **Recommendation:** Add chat-specific scopes and frame-level enforcement in 7.2/7.4.
- **Security implications:** Limits message/connection abuse; hashed keys avoid raw identity storage.
- **Database implications:** Reuse existing advisory-lock pattern; no new table required.
- **API implications:** `429` with `Retry-After` for REST mutations; STOMP ERROR for rejected frames.
- **Frontend implications:** Cooldown and curated failure messages; no automatic mutation replay.
- **Affected documentation:** SECURITY_ARCHITECTURE §12/§18; API §20.
- **Approval status:** APPROVED — IMPLEMENTATION PENDING.
- **Assigned implementation sub-stage:** 7.2/7.4.

### B-08 — F-3 remediation

- **Authoritative requirement:** A-13.
- **Implementation evidence:** No registry or lifecycle listener; logout only invalidates HTTP session.
- **Gap:** Actual WebSocket termination and per-frame revalidation.
- **Recommendation:** Implement the approved design in §10 of this register during 7.2.
- **Security implications:** Mandatory blocking gate before chat closure.
- **Database implications:** Use Spring Session JDBC; audit revocation events without message content.
- **API implications:** Logout path closes sockets and invalidates session.
- **Frontend implications:** Close socket on logout/auth revision change.
- **Affected documentation:** SECURITY_ARCHITECTURE §12.15/§18.8.
- **Approval status:** APPROVED REQUIREMENT; DESIGN RECORDED; IMPLEMENTATION PENDING.
- **Assigned implementation sub-stage:** 7.2.

### B-09 — Frontend conversation and chat UI

- **Authoritative requirement:** UI_UX §11; property-detail and dashboard patterns; A-03/A-05/A-06.
- **Implementation evidence:** No chat UI; property page has inquiry-only CTA; safe DOM and auth bridge patterns exist.
- **Gap:** Buyer/agent conversation pages, property-detail initiation, connection states and private-state clearing.
- **Recommendation:** Use vanilla ES modules, shared `api.js`, safe DOM helpers and existing visual tokens; no framework/build system.
- **Security implications:** No `innerHTML`, no browser storage, no message content in URLs/titles/analytics.
- **Database implications:** None.
- **API implications:** REST for reads/read-state; STOMP for send/delivery.
- **Frontend implications:** P-01 is approved; P-11 permits a locally vendored client only after exact version, license and integrity verification.
- **Affected documentation:** UI_UX §11/§14/§18; frontend README/DEPLOYMENT (update after approval).
- **Approval status:** APPROVED requirement; UI initiation decision P-01 approved and client-delivery decision P-11 approved with verification gates.
- **Assigned implementation sub-stage:** 7.5.

### B-10 — Safe audit metadata

- **Authoritative requirement:** SECURITY_ARCHITECTURE SD-44/§14.9; UI_UX §18.10.
- **Implementation evidence:** `AuditEventService` exists with no chat event methods; Stage 6 inquiry audit uses same-transaction records.
- **Gap:** Conversation created, message sent/rejected, read-state update and session revocation audit metadata.
- **Recommendation:** Log actor, conversation ID, message ID, reason and correlation ID only; never message content, subject-like text or private tokens.
- **Security implications:** Avoids transcript leakage through logs.
- **Database implications:** Append-only `audit_logs`.
- **API implications:** Error toasts remain generic.
- **Frontend implications:** No raw error text from backend rendered as user-facing content.
- **Affected documentation:** SECURITY_ARCHITECTURE §14.9.
- **Approval status:** APPROVED — IMPLEMENTATION PENDING.
- **Assigned implementation sub-stage:** 7.2–7.4.

---

## 4. Owner-approved Stage 7.1 decisions

P-01–P-13 are approved by the owner. Their future implementation remains gated by the sub-stage shown on each decision. P-11 additionally requires exact artifact version, license and integrity verification before the client is introduced. P-12 approves the same-site custom-domain topology; exact hostnames are supplied during deployment verification and do not block Stage 7.1 closure or local Stage 7.2–7.6 implementation.

### P-01 — Property-detail chat initiation entry point

- **Existing authoritative requirement:** FR-CHAT-001; Stage 6 Decision 9 (inquiry-only Stage 6 surface); UI_UX §7.3.1 historical two-channel text marked superseded for Stage 6.
- **Current implementation evidence:** `property-details.html` exposes `Enquire about this home`, inquiry form and favorite control only; no chat action exists.
- **Identified conflict or gap:** Stage 7 needs a buyer action to initiate a conversation, but the prior two-channel UI proposal was explicitly superseded.
- **Available options:**
  1. Add a separate `Message agent` action for authenticated active BUYERs on `PUBLISHED` properties; no favorite/inquiry prerequisite.
  2. Add chat entry only inside the inquiry success panel.
  3. Do not add a property-page action; require navigation from buyer conversation dashboard only.
- **Recommended option:** Option 1 — separate action, explicit creation, no cross-resource coupling.
- **Technical justification:** Preserves A-03/A-05 while satisfying FR-CHAT-001; reuses the existing `property-detail__actions` and wide-panel patterns.
- **Security implications:** No anonymous action; no message content in URL; role/status presentation guard only, backend remains authority.
- **Database implications:** None.
- **API implications:** Calls `POST /properties/{propertyId}/conversations` on explicit user action.
- **Frontend implications:** New `chat-property.js` bridge modeled on `inquiry-property.js`; clear private state on auth revision/pagehide.
- **Affected documentation:** UI_UX §7.3.1/§11; property-details.html; frontend README/DEPLOYMENT.
- **Approval status:** APPROVED BY OWNER — IMPLEMENTATION PENDING.
- **Assigned implementation sub-stage:** 7.5.

### P-02 — Conversation uniqueness versus property reassignment

- **Existing authoritative requirement:** PRD PD-07/PD-09; FRD FR-CHAT-003/FR-CHAT-012; V10 permanent `UNIQUE(buyer_id, property_id)`.
- **Current implementation evidence:** Conversation access uses original `assigned_agent_id` and is verified by integration tests; the unique constraint prevents a second conversation for the same buyer/property even after property reassignment.
- **Identified conflict or gap:** A buyer whose property is reassigned cannot open a new conversation with the new current agent without changing either the permanent uniqueness rule or the historical-assignment rule.
- **Available options:**
  1. Preserve the existing conversation and original agent; no new conversation for the same buyer/property; show that the conversation remains with the original agent.
  2. Change uniqueness to `(buyer_id, property_id, assigned_agent_id)` and allow a new conversation per assignment generation (requires migration and conflicts with approved one-conversation-per-buyer-property wording unless redefined).
  3. Transfer the existing conversation to the new agent (conflicts with PD-09/FR-CHAT-012).
- **Recommended option:** Option 1 — preserve current approved rules; document the limitation and present it clearly in the UI.
- **Technical justification:** It is the only option consistent with both PD-07 and PD-09 without a new migration or silent participant change.
- **Security implications:** New agent cannot read historical private messages; no accidental disclosure through reassignment.
- **Database implications:** No schema change. Option 2 requires V19 and owner-approved redefinition of uniqueness; option 3 requires rewriting historical participant data.
- **API implications:** Existing conversation is returned with `200`; no reassignment endpoint.
- **Frontend implications:** Buyer sees original agent context and an explanation that reassignment does not transfer the conversation.
- **Affected documentation:** PRD §14; FRD §9; DATABASE_DESIGN §18/§23; API §11; SECURITY_ARCHITECTURE §12.9.
- **Approval status:** APPROVED BY OWNER — IMPLEMENTATION PENDING.
- **Assigned implementation sub-stage:** 7.3/7.5.

### P-03 — Deactivated or inactive original agent

- **Existing authoritative requirement:** PRD §15 formerly open inactive-agent question, resolved by P-03; SECURITY_ARCHITECTURE §12.15/§21.3; DATABASE_DESIGN §18.
- **Current implementation evidence:** Only `ACTIVE` accounts can authenticate; deactivation revokes sessions and must close sockets; no runtime account-status endpoint exists yet; conversation history is not transferred.
- **Identified conflict or gap:** A formerly open question about buyer chat when the original agent is inactive; P-03 resolves it with preserved history, disabled messaging, and no fallback or transfer.
- **Available options:**
  1. Preserve history; the inactive original agent cannot authenticate or send, messaging in that conversation is disabled, and the buyer can read history but cannot start a new conversation with a fallback or newly assigned agent.
  2. Automatically reassign the conversation to a new active agent (conflicts with PD-09).
  3. Permit a new conversation with a fallback active agent (requires uniqueness change and assignment policy approval).
- **Recommended option:** Option 1 — no transfer, no fallback, no automatic mutation.
- **Technical justification:** Matches approved historical retention and fail-closed account-status rules; avoids inventing an assignment policy.
- **Security implications:** Inactive principal cannot send or receive live messages; history remains private to original participants.
- **Database implications:** No changes; read access returns on reactivation subject to session revocation.
- **API implications:** Creation fails safely when current primary agent is not `ACTIVE`; history remains readable to the buyer.
- **Frontend implications:** Show an unavailable/unattended state; do not offer a fallback agent automatically.
- **Affected documentation:** PRD §15; SECURITY_ARCHITECTURE §12.15; DATABASE_DESIGN §18.
- **Approval status:** APPROVED BY OWNER — IMPLEMENTATION PENDING.
- **Assigned implementation sub-stage:** 7.3/7.5.

### P-04 — Conversation status lifecycle and status endpoint

- **Existing authoritative requirement:** API §11 lists `PATCH /conversations/{conversationId}/status`; SECURITY_ARCHITECTURE SD-36 restricts it to the original assigned agent; V10 deliberately leaves status unconstrained.
- **Current implementation evidence:** No approved status enum, transition table, service or controller exists; Stage 6.1 validation records the lifecycle as deferred; `AuditEventService` has no conversation status event method.
- **Identified conflict or gap:** The endpoint was documented without an approved transition table; P-04 resolves this by deferring status mutation beyond Stage 7 and rejecting inquiry-rule reuse.
- **Available options:**
  1. Do not implement status mutation in Stage 7; retain stored status only, and base eligibility on participant/account/property rules.
  2. Approve a chat-specific two-state lifecycle (`OPEN`, `CLOSED`) with original-agent-only `OPEN → CLOSED` and `CLOSED → OPEN`, no admin authority, and audit on real transitions.
  3. Reuse the inquiry four-state matrix (not recommended; conflicts with independence requirement).
- **Recommended option:** Option 1 for Stage 7 is approved. Option 2 is not approved and would require a new owner decision before any status endpoint is introduced.
- **Technical justification:** No approved transition table exists; delaying avoids unnecessary mutation surface and prevents inquiry-rule leakage.
- **Security implications:** Option 1 minimizes authorization paths; option 2 still denies ADMIN and requires participant recheck plus audit.
- **Database implications:** Option 1 keeps V10 unconstrained. Option 2 may add `ck_conversations_status` in V19.
- **API implications:** Option 1 removes/defers `PATCH /status` from implementation scope; option 2 implements it with idempotent same-status success.
- **Frontend implications:** No close/reopen control under option 1.
- **Affected documentation:** API §11/§25.1.E; SECURITY_ARCHITECTURE §10.4.6/SD-36; DATABASE_DESIGN §18/§23.
- **Approval status:** APPROVED BY OWNER — IMPLEMENTATION PENDING.
- **Assigned implementation sub-stage:** None in Stage 7; post-Stage 7 owner decision required for any status lifecycle implementation.

### P-05 — Message idempotency key and duplicate-message prevention

- **Existing authoritative requirement:** Stage 6 SECURITY SD-29 and UI D-23 defer message idempotency; Stage 7.1 request explicitly requires duplicate-message prevention and failed/ambiguous recovery.
- **Current implementation evidence:** `messages` has no idempotency column or unique constraint; STOMP payload is `conversationId` + `content` only; frontend disables submit while in flight and deduplicates received messages by server ID.
- **Identified conflict or gap:** Without a server idempotency key, an ambiguous retry can create a duplicate persisted message. No exactly-once network delivery can be claimed.
- **Available options:**
  1. No server idempotency key: in-flight submit lock, client dedupe by server ID, history check before manual retry; duplicates possible on ambiguous retry.
  2. Add client-generated `idempotencyKey` to `/app/chat.send` and V19 `UNIQUE (conversation_id, sender_id, idempotency_key)`; retry with the same key returns the existing persisted message and the sender-only application `PERSISTENCE_ACK`.
  3. Use client-generated message ID as primary key (larger protocol change).
- **Recommended option:** Option 2 — approve V19 and idempotency key.
- **Technical justification:** It directly satisfies duplicate-message prevention and ambiguous recovery while preserving server-generated persistent IDs and timestamps; it does not claim exactly-once network delivery.
- **Security implications:** Keys must be scoped by conversation and sender; content is never accepted from an untrusted sender field; keys are audit-safe identifiers.
- **Database implications:** Approved V19 scope: add nullable `idempotency_key UUID`, backfill existing rows with generated UUIDs, set `NOT NULL`, add `UNIQUE (conversation_id, sender_id, idempotency_key)`, add `idx_messages_conversation_order (conversation_id, sent_at, id)`, add `idx_conversation_read_states_user (user_id, conversation_id)`. V1–V18 remain unchanged.
- **API implications:** STOMP payload becomes `{conversationId, content, idempotencyKey}`; the same key and content return the existing message, the same key with different content returns a safe conflict, and concurrent duplicates yield one row, one audit event and one presentation. Unknown fields are rejected.
- **Frontend implications:** Generate one UUID per logical message; reuse it on retry; recovery after an ambiguous ack failure only through explicit user retry, never automatic replay.
- **Affected documentation:** SECURITY_ARCHITECTURE §12/SD-29; UI_UX D-23/§11.5; DATABASE_DESIGN §18; API §20.
- **Approval status:** APPROVED BY OWNER — IMPLEMENTATION PENDING.
- **Assigned implementation sub-stage:** Decision before 7.3; V19 in 7.3; handler in 7.4; frontend in 7.5/7.6.

### P-06 — Message pagination direction and deterministic ordering

- **Existing authoritative requirement:** FR-CHAT-013; SECURITY_ARCHITECTURE §12.13 requires a deterministic tiebreaker; P-06 approves the cursor contract and UI D-22 is resolved.
- **Current implementation evidence:** Only `idx_messages_conversation_sent(conversation_id, sent_at)` exists; no paged repository query; random UUID tiebreaker is deterministic if paired with `sent_at`.
- **Identified conflict or gap:** The formerly open pagination direction and stable tiebreaker are resolved by P-06.
- **Available options:**
  1. Zero-based offset pages, oldest first, order by `sent_at ASC, id ASC`.
  2. Newest-first offset pages, order by `sent_at DESC, id DESC`.
  3. Cursor/`after` pagination.
- **Recommended option:** Option 3 — cursor-based `after`/`before` retrieval, ordered `(sent_at DESC, id DESC)` and rendered chronologically.
- **Technical justification:** Stable under concurrent inserts, avoids a new monotonic sequence column, and the approved V19 index supports the leading keys.
- **Security implications:** No cross-conversation ordering leak because queries are participant-scoped.
- **Database implications:** V19 adds `(conversation_id, sent_at, id)`; no new sequence column is authorized.
- **API implications:** Message history is a cursor exception to generic offset pagination and returns `nextCursor`, `hasMore` and `limit`.
- **Frontend implications:** Load the latest cursor page first, prepend older pages and render incoming messages chronologically.
- **Affected documentation:** API §11.2; SECURITY_ARCHITECTURE §12.13; UI_UX D-22.
- **Approval status:** APPROVED BY OWNER — IMPLEMENTATION PENDING.
- **Assigned implementation sub-stage:** 7.3/7.4.

### P-07 — Read-state update timing and semantics

- **Existing authoritative requirement:** FR-CHAT-009; API §11/§12; SECURITY_ARCHITECTURE §12.14; P-07/P-10 approve monotonic read state and UI D-37 is resolved; no read receipts approved.
- **Current implementation evidence:** Read-state table/entity exist; no update path; no unread query; no read receipt UI.
- **Identified conflict or gap:** The formerly open timing of “mark as read” and definition of unread counts are resolved by P-07/P-10.
- **Available options:**
  1. Explicit `PATCH read-state` when the participant opens a conversation and has loaded the latest page; mark only up to a `lastReadMessageId` belonging to that conversation; expose only own unread count.
  2. Mark read automatically on WebSocket delivery regardless of view state.
  3. Expose other participant read positions as receipts (rejected by approved exclusions).
- **Recommended option:** Option 1.
- **Technical justification:** Prevents marking messages read that were never displayed; preserves no-read-receipt privacy.
- **Security implications:** `lastReadMessageId` must pass `requireReadStateOwner` and same-conversation validation; advancement is monotonic and the highest viewed valid position wins under concurrency.
- **Database implications:** Existing composite PK supports one state per participant; optional user-leading index in P-05/V19.
- **API implications:** PATCH is the only read-state mutation; no STOMP read destination.
- **Frontend implications:** Own unread badge clears after successful PATCH; no “Seen” UI in either participant view.
- **Affected documentation:** API §11/§12; SECURITY_ARCHITECTURE §12.14; UI_UX §11.1/§11.3.
- **Approval status:** APPROVED BY OWNER — IMPLEMENTATION PENDING.
- **Assigned implementation sub-stage:** 7.3/7.6.

### P-08 — Delivery acknowledgement semantics

- **Existing authoritative requirement:** API §20; SECURITY_ARCHITECTURE §12.13; UI_UX §11.1 prohibits delivery receipts.
- **Current implementation evidence:** No STOMP handler or application acknowledgement path; UI has no delivery state.
- **Identified conflict or gap:** Need a precise acknowledgement contract without introducing a delivery receipt.
- **Available options:**
  1. Native STOMP `RECEIPT` after handler commit.
  2. Application-level persistence acknowledgement sent to the sender only.
  3. No acknowledgement (ambiguous UX).
- **Recommended option:** Option 2 — `PERSISTENCE_ACK` on `/user/queue/messages`, correlated by the mandatory idempotency key, sent only after transaction commit.
- **Technical justification:** The pinned Spring Framework 7.0.10 `StompSubProtocolHandler` auto-generates native `RECEIPT` only for `DISCONNECT_ACK`, not application SEND commits. An explicit application frame is therefore required. It proves durable acceptance, not recipient delivery.
- **Security implications:** The acknowledgement contains message ID and idempotency key only; no recipient, delivery or read state is exposed.
- **Database implications:** None beyond the committed message row.
- **API implications:** SEND carries a mandatory UUID `idempotencyKey`; the sender-only ack mirrors it. Errors go to `/user/queue/errors` and may additionally use STOMP `ERROR`.
- **Frontend implications:** `Sent` only after the matching application ack; `Failed` on safe error or timeout; never `Delivered` or `Seen`.
- **Affected documentation:** API §20; UI_UX §11.1/§11.5.
- **Approval status:** APPROVED BY OWNER — IMPLEMENTATION PENDING.
- **Assigned implementation sub-stage:** 7.4/7.5.

### P-09 — Reconnection synchronization mechanism

- **Existing authoritative requirement:** FR-CHAT-014; SECURITY_ARCHITECTURE §12.15; UI_UX §11.11.
- **Current implementation evidence:** No reconnect client; no sync endpoint; history REST endpoint is documented but unimplemented.
- **Identified conflict or gap:** Need to decide whether a dedicated sync endpoint is required.
- **Available options:**
  1. Reuse `GET /conversations` and `GET /conversations/{id}/messages`, resubscribe to user queues and dedupe by server message ID.
  2. Add `GET /conversations/{id}/messages?after=<messageId>` cursor support.
  3. Add a separate `/conversations/{id}/sync` endpoint.
- **Recommended option:** Option 1 for reconnection, using the approved cursor form of Option 2 from P-06 for history retrieval; no separate sync endpoint.
- **Technical justification:** Reuses approved participant-scoped REST endpoints and keeps the read path singular; cursor retrieval covers gaps created by concurrent inserts.
- **Security implications:** Every refresh remains participant-scoped and no-store.
- **Database implications:** Existing ordering key supports page refresh.
- **API implications:** No new endpoint in Stage 7.
- **Frontend implications:** On reconnect: re-handshake, resubscribe, refresh list/history, reconcile by ID.
- **Affected documentation:** API §11.2/§20; SECURITY_ARCHITECTURE §12.15; UI_UX §11.11.
- **Approval status:** APPROVED BY OWNER — IMPLEMENTATION PENDING.
- **Assigned implementation sub-stage:** 7.6.

### P-10 — Unread-count retrieval surface

- **Existing authoritative requirement:** FR-CHAT-009; API §11 conversation collection/detail; UI_UX §11.3.
- **Current implementation evidence:** No unread field or query exists; no standalone endpoint is documented.
- **Identified conflict or gap:** Need an explicit retrieval contract.
- **Available options:**
  1. Include `unreadCount` in `GET /conversations` and `GET /conversations/{id}` responses.
  2. Add `GET /conversations/{id}/unread-count`.
  3. Add `GET /conversations/unread-count` aggregate endpoint.
- **Recommended option:** Option 1 — no standalone endpoint.
- **Technical justification:** Avoids redundant REST surface and extra requests; unread count is conversation metadata.
- **Security implications:** Count is own only; no other participant’s read state exposed.
- **Database implications:** Unread computed from participant-scoped messages and own `last_read_at`.
- **API implications:** Add field to existing response schemas.
- **Frontend implications:** Conversation list badge and detail header use the returned value.
- **Affected documentation:** API §11; UI_UX §11.3/§8.6.
- **Approval status:** APPROVED BY OWNER — IMPLEMENTATION PENDING.
- **Assigned implementation sub-stage:** 7.3/7.6.

### P-11 — STOMP client dependency delivery and version pinning

- **Existing authoritative requirement:** API §20 requires STOMP over native WebSocket; frontend has no framework/build system and no package.json.
- **Current implementation evidence:** `getWebSocketUrl()` exists but is unused; no STOMP/SockJS client library is present; frontend uses classic scripts plus ES module bridges.
- **Identified conflict or gap:** A STOMP client is needed, but introducing a dependency requires owner approval.
- **Available options:**
  1. Vendor a pinned, audited MIT-licensed STOMP JS UMD build locally with license file, exact version and integrity record; no build system.
  2. Hand-write minimal STOMP frame handling in project code.
  3. Introduce npm/bundler/package.json (disallowed by Stage 7.1 constraints).
- **Recommended option:** Option 1, subject to license/security verification and exact version pinning before use; no CDN-only loading.
- **Technical justification:** Preserves the no-build-system architecture and avoids an unaudited floating CDN dependency.
- **Security implications:** Pin exact artifact, verify license, integrity and CSP; do not trust frames from arbitrary origins; close on auth changes.
- **Database implications:** None.
- **API implications:** Client must use `/ws` and only approved destinations/headers.
- **Frontend implications:** Loaded as a local ES module/UMD bridge; no React/Vue/Angular/Next.js.
- **Affected documentation:** frontend README/DEPLOYMENT; API §20.
- **Approval status:** APPROVED BY OWNER — IMPLEMENTATION PENDING. No dependency is introduced in 7.1. Before any introduction, record the exact version, license, artifact integrity and security review; absence of those records blocks 7.5 integration.
- **Assigned implementation sub-stage:** Approval before 7.5; integration 7.5.

### P-12 — Exact production origins and host-only cookie topology

- **Existing authoritative requirement:** SECURITY_ARCHITECTURE SD-48/§8.5/§21.1; single-instance constraint A-12.
- **Current implementation evidence:** `application-prod.yml` requires `APP_CORS_ALLOWED_ORIGINS` and `APP_WEBSOCKET_ALLOWED_ORIGINS`; no localhost fallback in prod; frontend config supports explicit `webSocketUrl`; default `vercel.app`/`onrender.com` hosts are a blocker.
- **Identified conflict or gap:** Exact production hostnames are not recorded; WebSocket origin allowlist and host-only `EV_SESSION` cookie require concrete values.
- **Available options:**
  1. Owner supplies exact frontend/API hosts on the same registrable domain; direct credentialed REST and direct WSS, host-only cookie, exact origin allowlists.
  2. Proxy WebSocket through frontend host (requires deployment spike and cookie/origin redesign).
  3. Deploy on default unrelated hostnames (blocked).
- **Recommended option:** Option 1.
- **Technical justification:** Preserves host-only `EV_SESSION`, exact-origin CORS/WebSocket restrictions and no weakening of cookie security.
- **Security implications:** No domain-widened cookie, no query token, no permissive wildcard origin.
- **Database implications:** None.
- **API implications:** Handshake and REST origins use the same approved exact values.
- **Frontend implications:** Explicit `apiBaseUrl` and `webSocketUrl` configuration for the approved hosts.
- **Affected documentation:** SECURITY_ARCHITECTURE §8.5/§21.1; DEVELOPMENT_SETUP §11; frontend DEPLOYMENT.
- **Approval status:** APPROVED BY OWNER — same-site custom-domain topology approved. Exact hostnames are deployment-verification inputs and do not block Stage 7.1 closure or local Stage 7.2–7.6 implementation.
- **Assigned implementation sub-stage:** 7.2 configuration design; 7.7 production verification.

### P-13 — Property publication eligibility for new conversations

- **Existing authoritative requirement:** FR-CHAT-001; SECURITY_ARCHITECTURE §10.7 requires a current active agent; UI_UX §7.3.1/§20.8 disables new-chat actions on non-published properties; API §11.1 does not explicitly state publication.
- **Current implementation evidence:** Stage 6 inquiry submission requires `PUBLISHED`; conversation service does not exist.
- **Identified conflict or gap:** API omission could allow creation on draft/unpublished/sold/archived properties if implemented literally.
- **Available options:**
  1. Require `PUBLISHED` plus active BUYER plus active current AGENT for new conversation creation; preserve all existing histories regardless of later property status.
  2. Allow creation on any non-archived property with an active agent.
  3. Allow creation on any property.
- **Recommended option:** Option 1.
- **Technical justification:** Aligns API, security and UI guidance and prevents unattended or stale listings from opening new chats.
- **Security implications:** Fail-closed creation; no generic existence disclosure for non-public properties.
- **Database implications:** No schema change.
- **API implications:** Non-eligible property returns generic `404`.
- **Frontend implications:** Chat CTA hidden/disabled for non-published properties; historical conversations remain accessible.
- **Affected documentation:** API §11.1; SECURITY_ARCHITECTURE §10.7; UI_UX §11.
- **Approval status:** APPROVED BY OWNER — IMPLEMENTATION PENDING.
- **Assigned implementation sub-stage:** 7.3.

---

## 5. Deferred requirements preserved

These are not part of Stage 7 implementation and are not approved for introduction by assumption.

| ID | Deferred requirement | Authority / reason | Revisit |
|---|---|---|---|
| D-01 | Typing indicators | Not in approved FRD; expands real-time surface | Post-Stage 7 owner decision |
| D-02 | Message editing | `edited_at` schema exists but no approved operation | Post-Stage 7 owner decision |
| D-03 | Message deletion and retention policy | `deleted_at` schema exists; no approved retention rule | Post-Stage 7 owner decision |
| D-04 | Attachments and non-TEXT message types | Only TEXT approved; API payload is content-only | Post-Stage 7 owner decision |
| D-05 | Read receipts and delivery receipts | Explicitly prohibited in UI_UX §11.1 | Do not introduce without new approval |
| D-06 | Conversation search | Not in approved FRD | Stage 8+ |
| D-07 | Multi-instance deployment / F-7 cross-instance CSRF | Stage 6 §14; Stage 9 gate | Stage 9 |
| D-08 | Stage 8 dashboards, account management, F-5, F-8 remnants | Preserved outstanding obligations | Stage 8 |
| D-09 | Stage 9 runtime/browser/device/CI/production verification | Preserved outstanding obligations | Stage 9 |
| D-10 | Conversation status mutation | P-04 defers `PATCH /status` beyond Stage 7 | Post-Stage 7 owner-approved lifecycle |
| D-11 | New monotonic V19 ordering-sequence column | Not approved; current cursor contract uses `(sent_at, id)` | Only if a demonstrated defect requires owner approval |

---

## 6. Conflicting or unresolved requirements

| ID | Conflict / gap | Authority or correction | Current status |
|---|---|---|---|
| C-01 | DATABASE_DESIGN §18 said the first message is stored during conversation creation | API §11.1/§25.1.A control; corrected in 7.1 to state creation has no message and first message goes through `/app/chat.send` | RESOLVED BY DOC CORRECTION |
| C-02 | `GET /agents/me/conversations` appeared in API §5, SECURITY §10.4.6/checklists while API §11 uses `GET /conversations` for buyer/agent | Corrected in 7.1 to `GET /conversations` with agent scope | RESOLVED BY DOC CORRECTION |
| C-03 | SECURITY §10.4.6 listed `409` for conversation creation while API §11.1 specifies `200` existing / `201` new | Corrected in 7.1 to document idempotent `200`/`201` and no `409` | RESOLVED BY DOC CORRECTION |
| C-04 | PRD §15 listed contact-before-inquiry, inactive-agent chat and administrative transcript access as open | A-05, P-01, P-03 and A-06 answer them without reopening prior approvals | RESOLVED — PRD §15 reconciled |
| C-05 | API chat creation omitted publication eligibility | P-13 requires `PUBLISHED`, active buyer and active current agent for new creation; historical retrieval is unchanged | RESOLVED — API and security contracts reconciled |
| C-06 | UI_UX §7.3.1 historical two-channel proposal was superseded for Stage 6 but chat CTA was not approved for Stage 7 | P-01 approves a separate `Message agent` action; no inquiry/favorite prerequisite | RESOLVED — UI contract reconciled |
| C-07 | `SECURITY_WS_AUTH_FAILURE` was reported not implemented for STOMP frames in Stage 3 corrective evidence | §10/F-2 requires fail-closed frame validation and safe audit correlation | DESIGN RESOLVED — Stage 7.2 implementation obligation remains |
| C-08 | `PATCH /conversations/{id}/status` was documented but no enum/transition table exists | P-04 defers status mutation beyond Stage 7 | RESOLVED — endpoint marked deferred and unmapped in Stage 7 |
| C-09 | Stage 7.1 request asked for client-generated idempotency identifiers while approved docs deferred them | P-05 approves mandatory UUID key and V19 unique constraint | RESOLVED — SD-29/D-23 reconciled |
| C-10 | Stage 7.1 request asked for read receipts/unread counts; approved docs prohibit read receipts but require unread info | P-07/P-10 approve monotonic own read state and participant-specific `unreadCount`; no read receipts | RESOLVED — no receipt UI or events added |
| C-11 | Native STOMP `RECEIPT` was treated as durable-send acknowledgement | Pinned Spring Framework 7.0.10 auto-generates it only for `DISCONNECT_ACK` | RESOLVED — F-7.1-01 application ack |
| C-12 | Session-destroyed events were treated as authoritative socket invalidation | Spring Session JDBC uses cron cleanup and has no per-socket awareness | RESOLVED — F-7.1-02 authoritative validation |
| C-13 | Outbound dispatch relied on send-time subscription state only | F-7.1-03 requires authorization immediately before each send | RESOLVED — design recorded; Stage 7.2 implementation gate |
| C-14 | Offset pagination and commit/timestamp mismatch could shift or duplicate history | F-7.1-04 approves cursor, advisory serialization and no new sequence column | RESOLVED — API/Database contracts reconciled |
| C-15 | Ambiguous retries could create duplicate rows | F-7.1-05/P-05 approve mandatory UUID idempotency key and V19 unique constraint | RESOLVED — no exactly-once claim |
| C-16 | Non-monotonic or viewed-unaware read advancement | F-7.1-06/P-07 approve transactionally protected highest-viewed-position semantics | RESOLVED — no read receipts |
| C-17 | Retrieval after reassignment could be conflated with new creation eligibility | F-7.1-07/P-13 split creation eligibility from historical retrieval | RESOLVED — UI and API reconciled |
| C-18 | CSRF on STOMP CONNECT was deferred | Spring Security 7.1.2 supports session-bound tokens in CONNECT headers; F-7.1-08 approves it | RESOLVED — SD-40 approved; host-only cookies and HTTP CSRF unchanged |

---

## 7. Stage 7 acceptance ledger

All scenarios below are **NOT EXECUTED** as of Stage 7.1. They are mandatory verification items for later sub-stages. “BLOCKING” means the item must pass before Stage 7 closure.

| ID | Scenario | Expected behavior | Required evidence | Verification method | Sub-stage | Blocking | Status |
|---|---|---|---|---|---|---|---|
| AC-01 | Conversation creation | Active BUYER can create/retrieve conversation for eligible `PUBLISHED` property; buyer/agent derived by server; new = `201`, existing = `200` | API response, DB row with original agent, two read-state rows, audit metadata | Integration test + HTTP test with two sessions | 7.3 | BLOCKING | NOT EXECUTED |
| AC-02 | Duplicate conversation prevention | Repeated POST for same buyer/property returns existing conversation with `200`; no second row | Unique constraint assertion, response IDs equal | Concurrent/sequential API test | 7.3 | BLOCKING | NOT EXECUTED |
| AC-03 | Concurrent conversation creation | N simultaneous POSTs yield exactly one conversation and all responses use its ID | DB count = 1, `uk_conversations_buyer_property` respected | `ExecutorService`/`CountDownLatch` integration test | 7.3 | BLOCKING | NOT EXECUTED |
| AC-04 | Property-specific conversation isolation | Buyer cannot access another property’s conversation; agent only original participant | Generic `404`, no message content in response | Participant/non-participant API matrix | 7.3/7.4 | BLOCKING | NOT EXECUTED |
| AC-05 | Historical participant authorization | Original buyer/agent retain access; other agent and ADMIN denied | Authorization matrix test results | `ResourceAuthorizationIntegrationTest` extension | 7.3 | BLOCKING | NOT EXECUTED |
| AC-06 | Property reassignment | Reassignment does not change `conversations.assigned_agent_id`; new agent denied history | Raw DB ID assertion, denial response | Integration test modeled on `PropertyAssignmentIntegrationTest` | 7.3/7.7 | BLOCKING | NOT EXECUTED |
| AC-07 | Property lifecycle changes | Unpublish/sold/rented/archive preserves history; no cascade/delete; new creation fails safely when not `PUBLISHED` | Row counts, response behavior | Lifecycle integration test | 7.3/7.7 | BLOCKING | NOT EXECUTED |
| AC-08 | Account deactivation | Deactivated account cannot authenticate/send; sockets closed; history preserved; no transfer | Auth failure, socket closed event, unchanged rows | Integration + WebSocket test | 7.2/7.7 | BLOCKING | NOT EXECUTED |
| AC-09 | Unauthorized REST access | Anonymous, wrong role, other participant and ADMIN receive correct `401`/`403`/generic `404` | Status codes, error envelope, no-store header | MockMvc/security integration test | 7.3/7.7 | BLOCKING | NOT EXECUTED |
| AC-10 | Unauthorized WebSocket handshake | Unauthenticated `/ws` handshake rejected | Handshake failure status/log | WebSocket integration test | 7.2 | BLOCKING | NOT EXECUTED |
| AC-11 | Invalid origin | Handshake with non-allowlisted Origin/Referer rejected; audit recorded | Rejection log/audit, no session established | Exact-origin test matrix | 7.2 | BLOCKING | NOT EXECUTED |
| AC-12 | Invalid or missing applicable CSRF token | REST mutations and STOMP CONNECT with missing/invalid/stale session-bound CSRF token are rejected; bad Origin rejected; auth login/logout forced-refresh behavior preserved | `403`/CONNECT ERROR envelope, no mutation, socket/session state | Security + raw STOMP integration test | 7.2/7.3 | BLOCKING | NOT EXECUTED |
| AC-13 | Unauthorized STOMP subscription | Only three user queues allowed; topics/wildcards/conversation destinations rejected | ERROR frame + audit | Interceptor + broker integration test | 7.2 | BLOCKING | NOT EXECUTED |
| AC-14 | Unauthorized STOMP SEND | Non-participant, ADMIN and missing/invalid conversation ID rejected; no message row | ERROR frame, DB count unchanged | Interceptor + handler integration test | 7.2/7.4 | BLOCKING | NOT EXECUTED |
| AC-15 | Sender impersonation | Client `senderId`/role/login/passcode headers ignored; sender = session principal | Persisted sender ID equals principal | Frame spoofing test | 7.2/7.4 | BLOCKING | NOT EXECUTED |
| AC-16 | Cross-conversation disclosure | User queue delivery contains only messages for recipient’s own conversation; no cross-topic leak | Received frame destinations/content | Multi-participant WebSocket test | 7.4/7.7 | BLOCKING | NOT EXECUTED |
| AC-17 | Message persistence | TEXT message persisted with UUID ID, server `sentAt`, correct conversation and sender | DB row + API history response | Handler integration test | 7.4 | BLOCKING | NOT EXECUTED |
| AC-18 | Transaction rollback | Forced failure in same transaction rolls back message and audit/unread writes | DB counts unchanged | Fault-injection integration test | 7.4 | BLOCKING | NOT EXECUTED |
| AC-19 | Post-commit delivery | No user-queue delivery or application `PERSISTENCE_ACK` before commit; failed publish leaves persisted message recoverable; native `RECEIPT` is not relied on | Event ordering log, ack correlation by idempotency key | Handler + broker test | 7.4 | BLOCKING | NOT EXECUTED |
| AC-20 | Duplicate-message handling | Same mandatory idempotency key and content yields one row and the same message ID; same key with different content returns a safe conflict; concurrent duplicates yield one row, audit and presentation | DB count, response IDs, conflict error | Retry/concurrency test | 7.4/7.6 | BLOCKING | NOT EXECUTED |
| AC-21 | Concurrent messaging | N valid sends from participants persist in deterministic order with no lost/duplicate rows | DB count/order assertions | Concurrent STOMP/HTTP test | 7.4 | BLOCKING | NOT EXECUTED |
| AC-22 | Message ordering | History ordered by approved `(sent_at, id)` rule across pages and live events | Ordered ID list | Pagination + live delivery test | 7.4 | BLOCKING | NOT EXECUTED |
| AC-23 | Message-history pagination | Latest-first cursor pages with stable `nextCursor`/`hasMore`, participant-scoped; chronological client rendering; malformed cursor/limit rejected | Cursor responses, rendered order, validation errors | API + frontend integration test | 7.3/7.4 | BLOCKING | NOT EXECUTED |
| AC-24 | Read-state consistency | Own read state updates only for same-conversation message ID; repeats idempotent; other participant state not exposed | DB row, response, error cases | REST integration test | 7.3/7.6 | BLOCKING | NOT EXECUTED |
| AC-25 | Unread counts | Own unread count matches persisted state; no read receipt/other-user count exposed | Count assertions | API + UI test | 7.6 | BLOCKING | NOT EXECUTED |
| AC-26 | Multi-tab behavior | Tabs dedupe by message ID, clear private state on auth revision/logout, respect connection limits | DOM state, socket count | Browser/manual + unit test | 7.5/7.6 | BLOCKING | NOT EXECUTED |
| AC-27 | Offline recovery | Offline composer state shows failure; reconnect refreshes via REST; no automatic mutation replay | Network-throttle test evidence | Frontend test + manual | 7.6 | BLOCKING | NOT EXECUTED |
| AC-28 | Reconnection | Bounded backoff with jitter; resubscribe to three queues; stop on auth failure | Reconnect log/UI state | WebSocket client test | 7.6 | BLOCKING | NOT EXECUTED |
| AC-29 | Missed-message recovery | Messages sent while disconnected retrieved on REST refresh and deduped by ID | No duplicate DOM rows | Integration + frontend test | 7.6 | BLOCKING | NOT EXECUTED |
| AC-30 | Logout invalidation | Explicit logout closes active WebSocket(s) and subsequent SEND is rejected | Socket close event, rejection, no new row | F-3 verification test | 7.2 | BLOCKING | NOT EXECUTED |
| AC-31 | Session expiration | Expired session closes sockets and rejects further frames | Session expiry event, socket close | F-3 verification test | 7.2 | BLOCKING | NOT EXECUTED |
| AC-32 | Account-status invalidation | Deactivation/suspension closes sockets and rejects sends even if HTTP session exists | Status change + frame rejection | F-3 verification test | 7.2 | BLOCKING | NOT EXECUTED |
| AC-33 | Application restart recovery | Sessions and messages survive restart; reconnect restores history and authorization | DB/session assertions before and after restart | Restart integration test | 7.6/7.7 | BLOCKING | NOT EXECUTED |
| AC-34 | Message validation | Blank/whitespace-only rejected; over-length rejected; TEXT only; frame cap enforced | Error envelope/ERROR frame, no DB row | Unit + integration test | 7.4 | BLOCKING | NOT EXECUTED |
| AC-35 | Rate-limit enforcement | 30/min/user, 10/min/conversation per sender, 5 sockets/user, 20 attempts/IP/min, 32 KB frame, 2,000 chars enforced | `429`/ERROR, `Retry-After`, no mutation | Rate-limit integration test | 7.2/7.4 | BLOCKING | NOT EXECUTED |
| AC-36 | Private state clearing | Logout, auth revision change, `pagehide` and BFCache `pageshow` clear messages, CSRF memory and socket | DOM/memory state assertions | Frontend unit + manual test | 7.5/7.6 | BLOCKING | NOT EXECUTED |
| AC-37 | Safe message rendering | Message content rendered via `textContent`; HTML/script payloads inert; line breaks preserved | DOM snapshot, no executed script | XSS payload test | 7.5 | BLOCKING | NOT EXECUTED |
| AC-38 | Responsive and accessibility behavior | Mobile/desktop layout, 44px targets, keyboard access, `role="log"`, `aria-live`, reduced motion | Browser checks + DOM assertions | Manual + automated accessibility checks | 7.5/7.7 | BLOCKING | NOT EXECUTED |
| AC-39 | Existing authentication regression | Login/logout/session/CSRF/origin behavior unchanged | Existing auth suites pass | Targeted existing test suites | 7.7 | BLOCKING | NOT EXECUTED |
| AC-40 | Existing property-management regression | CRUD/status/assignment/image behavior unchanged | Existing property suites pass | Targeted existing test suites | 7.7 | BLOCKING | NOT EXECUTED |
| AC-41 | Existing discovery regression | Public property discovery/detail unchanged | Existing discovery suites pass | Targeted existing test suites | 7.7 | BLOCKING | NOT EXECUTED |
| AC-42 | Existing favorites regression | Buyer favorites behavior unchanged | Existing favorites suites pass | Targeted existing test suites | 7.7 | BLOCKING | NOT EXECUTED |
| AC-43 | Existing inquiry regression | Inquiry submission, lifecycle, uniqueness and admin/agent views unchanged | Existing inquiry suites pass | Targeted existing test suites | 7.7 | BLOCKING | NOT EXECUTED |
| AC-44 | Session identifier rotation | Login session migration/rotation closes or rebinds stale socket state; no stale principal | Session ID + socket registry assertions | F-3 verification test | 7.2 | BLOCKING | NOT EXECUTED |
| AC-45 | Authorization loss and role change | Role/status change revokes sockets and rejects subsequent frames | Revocation audit, frame rejection | F-3 verification test | 7.2 | BLOCKING | NOT EXECUTED |
| AC-46 | Idle connection handling | Idle socket without heartbeats closed; active heartbeat keeps session until configured limit | Heartbeat/close timestamps | WebSocket integration test | 7.2/7.6 | BLOCKING | NOT EXECUTED |
| AC-47 | ADMIN transcript denial | ADMIN cannot list, detail, read history, send, read or update conversations | `403`/generic `404`, no content | Authorization matrix + UI check | 7.3/7.5 | BLOCKING | NOT EXECUTED |
| AC-48 | F-7.1-01 persistence acknowledgement | Application `PERSISTENCE_ACK` is sent after commit to the sender only, correlated by mandatory idempotency key; safe errors use the same correlation; no recipient or read receipt; no exactly-once claim | Frame routing, correlation, ordering log | Raw STOMP + handler integration test | 7.4/7.5 | BLOCKING | NOT EXECUTED |
| AC-49 | F-7.1-02 authoritative session lifecycle | Handshake/CONNECT/frame/heartbeat validation uses authoritative session existence and expiry, not only Spring Session destruction events; bounded revalidation, logout/revocation integration, rotation handling, registry cleanup and concurrency are fail-closed | Session repository assertions, close events, audit | Session + WebSocket integration test | 7.2 | BLOCKING | NOT EXECUTED |
| AC-50 | F-7.1-03 outbound authorization | Participant/session/account/role authorization is rechecked immediately before every outbound send, covering revoked sessions, stale subscriptions, multiple sockets, logout versus account-wide revocation, deactivation/role change, concurrent logout/delivery and queued messages | No unauthorized delivery; registry/audit state | Multi-socket concurrency test | 7.2/7.4 | BLOCKING | NOT EXECUTED |
| AC-51 | F-7.1-04 deterministic history | Cursor retrieval is latest-first by `(sent_at, id)` with chronological rendering; concurrent inserts, reconnect recovery and timestamp/commit-order mismatch do not shift, duplicate or omit messages; V19 index used and no monotonic sequence column added | Cursor stability, DB index plan, rendered order | Cursor + concurrency + explain plan test | 7.3/7.4/7.6 | BLOCKING | NOT EXECUTED |
| AC-52 | F-7.1-05 idempotent messaging | Mandatory UUID key; same key/content returns existing message; same key/different content is a safe conflict; concurrent duplicates yield one row/audit/presentation; ack failure requires explicit user retry with no automatic replay | DB/audit counts, conflict code, retry trace | Idempotency + failure-injection test | 7.4/7.5/7.6 | BLOCKING | NOT EXECUTED |
| AC-53 | F-7.1-06 monotonic read state | Read position is transactionally protected, advances only for viewed messages in the same conversation, excludes own messages from unread counts, and highest valid position wins under concurrency; no read-receipt UI/events | Read-state rows, unread counts, DOM assertions | Concurrency + API + UI test | 7.3/7.5/7.6 | BLOCKING | NOT EXECUTED |
| AC-54 | F-7.1-07 eligibility split | Existing-conversation retrieval is distinguished from new creation; creation requires `PUBLISHED` plus active buyer/agent; historical history remains for authorized active participants; inactive participants cannot use protected chat; reassignment transfers no access | Response codes, authorization matrix, DB identity | Lifecycle + participant integration test | 7.3/7.5 | BLOCKING | NOT EXECUTED |
| AC-55 | F-7.1-08 STOMP CONNECT CSRF | CONNECT requires the session-bound token from the existing `X-CSRF-TOKEN` flow in a native header and exact Origin; missing, invalid, stale tokens and bad Origin are rejected; host-only cookies and HTTP CSRF are unchanged | CONNECT frames, rejection audit, cookie/header state | Raw STOMP + security integration test | 7.2 | BLOCKING | NOT EXECUTED |

---

## 8. Implementation sequence reconciliation

| Sub-stage | Scope | Reconciliation |
|---|---|---|
| 7.1 | Architecture assessment, decisions and contracts | Current stage. Documentation only; approved by owner. |
| 7.2 | WebSocket security foundation and F-3/F-8 remediation | Retained. Authoritative session validation, registry/revocation, session-bound CSRF on CONNECT, exact Origin, rate-limit scopes and safe WS audit correlation are mandatory before any message handler ships. |
| 7.3 | Conversation persistence, lifecycle and REST APIs | Retained. Include get-or-create, list/detail, eligibility split, monotonic read-state persistence, cursor history and the approved V19 idempotency/index changes. Do not implement status mutation. |
| 7.4 | Real-time messaging, persistence and delivery | Retained. Handler, mandatory idempotency, transaction, post-commit publish, sender-only persistence acknowledgement and error delivery depend on 7.2 and 7.3. |
| 7.5 | Conversation and chat frontend integration | Retained. Separate `Message agent` action; local STOMP client only after exact version/license/integrity verification; no framework/build system. |
| 7.6 | Unread counts, reconnection and resilience | Retained with no read receipts. Cursor refresh, ID dedupe, bounded backoff, explicit retry only and monotonic own read state apply. No acceptance item removed. |
| 7.7 | Consolidated verification, remediation and closure | Retained. Includes restart, cross-resource regression, exact deployment hosts and all outstanding acceptance evidence. |

No acceptance requirement is removed by this reconciliation. The ledger now contains 55 scenarios; AC-01–AC-47 remain present and none has been executed.

---

## 9. Approval record

- **Stage 7.1 formal approval:** GRANTED for documentation, architecture contracts and decision reconciliation.
- **Owner decisions:** P-01–P-13 approved. P-11 verification values and P-12 exact hostnames remain explicit implementation/deployment gates, not reopened decisions.
- **Finding resolutions:** F-7.1-01–F-7.1-08 resolved as recorded in §10.
- **Implementation authorization:** Stage 7.2 may proceed within its approved scope; no chat implementation is claimed by Stage 7.1.
- **Acceptance status:** All 55 ledger scenarios are `NOT EXECUTED`.
- **Commit authorization:** One documentation commit is authorized with message `docs(stage-7): approve chat architecture and security contracts`; no push or merge is authorized.

---

## 10. Resolved Stage 7.1 findings

### F-7.1-01 — Durable-send acknowledgement

- **Finding:** Native STOMP `RECEIPT` was treated as an application persistence acknowledgement. In the pinned Spring Framework 7.0.10, `StompSubProtocolHandler` auto-generates `RECEIPT` only for `DISCONNECT_ACK`, not for application SEND commits.
- **Resolution:** After transaction commit, publish a sender-only application frame `PERSISTENCE_ACK` on `/user/queue/messages`, correlated by the mandatory `idempotencyKey` and message ID. Message delivery frames to both participants remain separate. Persistence failures produce a safe error on `/user/queue/errors` carrying the same correlation key. No recipient receipt, read receipt or exactly-once network-delivery claim is introduced. Native `RECEIPT` is non-authoritative.
- **Acceptance:** AC-19, AC-48.

### F-7.1-02 — Authoritative session lifecycle

- **Finding:** Spring Session JDBC destruction events were treated as a sufficient socket-invalidation trigger. Pinned Spring Session 4.1.1 performs bulk expiry cleanup on a cron schedule and has no per-socket awareness.
- **Resolution:** At handshake, CONNECT, heartbeat and each protected frame, validate authoritative session existence/expiry through `SessionRepository.findById(sessionId)` and `Session.isExpired()`, with the trusted session ID obtained from the handshake request attributes. Use bounded revalidation caching; integrate explicit logout, password/reset and account-wide revocation; handle session-ID rotation by closing stale sockets; guard registry cleanup with concurrent structures and fail closed on missing, expired or mismatched state. Audit only actor, destination, reason and correlation ID.
- **Acceptance:** AC-30–AC-32, AC-44–AC-46, AC-49.

### F-7.1-03 — Outbound dispatch authorization

- **Finding:** Authorization at SEND time or subscription time alone does not prove that a queued outbound frame is still authorized when it is dispatched.
- **Resolution:** Revalidate recipient, conversation participant set, account status, role and session state immediately before every `convertAndSendToUser` call. Cover revoked sessions, stale subscriptions, multiple simultaneous sockets for an account, per-session logout versus account-wide revocation, deactivation/role changes, concurrent logout/delivery races and queued frames. A failed recheck suppresses the frame and produces a redacted security audit event without message content.
- **Acceptance:** AC-16, AC-30–AC-32, AC-45, AC-50.

### F-7.1-04 — Deterministic history under concurrency

- **Finding:** Offset pages and `sent_at` alone can shift or duplicate rows when messages are inserted concurrently or commit order differs from timestamp order.
- **Resolution:** Retrieve latest-first with a deterministic `(sent_at DESC, id DESC)` cursor and render the loaded window chronologically. Serialize inserts for a conversation with a per-conversation advisory transaction lock so generated `sent_at` follows commit order. Reconnect recovery uses the same cursor contract. V19 is limited to `(conversation_id, sender_id, idempotency_key)` uniqueness and supporting order/user-leading indexes; no monotonic sequence column or constraint is added. Materially expanding that schema scope requires new owner approval.
- **Acceptance:** AC-21–AC-23, AC-29, AC-51.

### F-7.1-05 — Idempotent retry and recovery

- **Finding:** An ambiguous send outcome could create a duplicate persisted row.
- **Resolution:** Require a client-generated UUID `idempotencyKey` on every `/app/chat.send` frame. The V19 unique key `(conversation_id, sender_id, idempotency_key)` is authoritative. Same key with identical content returns the existing message and ack; same key with different content returns a safe conflict; concurrent duplicates produce one row, one audit event and one presentation. After an ack failure, recovery is only through explicit user retry with the same key; automatic replay is prohibited. No exactly-once guarantee is claimed.
- **Acceptance:** AC-18–AC-20, AC-27, AC-52.

### F-7.1-06 — Monotonic read state

- **Finding:** Read-state updates needed precise semantics to avoid false read claims and privacy leakage.
- **Resolution:** Advance `conversation_read_states` only for messages the participant actually viewed, within the same conversation. Own messages are excluded from that participant’s unread count. Updates are transactionally protected and monotonic; under concurrency the highest valid viewed position wins. Only the requester’s own `unreadCount` is returned in conversation list/detail DTOs. No read-receipt UI, event or other-participant position is exposed.
- **Acceptance:** AC-24, AC-25, AC-53.

### F-7.1-07 — Creation eligibility versus historical access

- **Finding:** Retrieval of an existing conversation could be incorrectly blocked or allowed by rules intended only for new creation.
- **Resolution:** `POST /properties/{propertyId}/conversations` creates a new conversation only when the property is `PUBLISHED` and the buyer and current primary agent are active and eligible. If the permanent `(buyer_id, property_id)` row already exists, an authorized active original participant retrieves it regardless of later property status. Original identity is immutable; reassignment creates no new conversation for that buyer/property and transfers no access. When the original agent is inactive, history is preserved, buyer history remains readable, and protected messaging is disabled; no fallback or transfer is introduced.
- **Acceptance:** AC-01, AC-04–AC-08, AC-54.

### F-7.1-08 — CSRF on STOMP CONNECT

- **Finding:** CONNECT-level CSRF remained deferred although the pinned Spring Security 7.1.2 documentation supports a session-bound CSRF token in STOMP CONNECT headers.
- **Resolution:** The frontend reads the existing in-memory `X-CSRF-TOKEN` value and sends it as a STOMP CONNECT native header. The backend resolves the trusted session ID from handshake attributes, loads the session-bound token through Spring Session `SessionRepository.findById`/`Session.getAttribute`, requires exact equality, and independently validates the exact `Origin`. Missing, invalid, stale or mismatched tokens and disallowed origins are rejected before subscriptions. Host-only `EV_SESSION` cookies and HTTP REST CSRF behavior are unchanged; no token is placed in a URL, query parameter or cookie. No material incompatibility was found with Spring Security 7.1.2.
- **Acceptance:** AC-11, AC-12, AC-55.

---

## 11. Documentation reconciliation record

| File | Reconciled content |
|---|---|
| `docs/PRD.md` | Stage 7 product decisions recorded; formerly open contact, inactive-agent and admin-transcript questions marked resolved without changing PD-01–PD-11. |
| `docs/FRD.md` | FR-CHAT-001–014 retained and refined with approved eligibility, idempotency, cursor, read-state, acknowledgement, recovery and exclusion contracts. |
| `docs/SYSTEM_ARCHITECTURE.md` | Real-time message flow updated for application ack, idempotency, cursor history and session-bound CONNECT CSRF. |
| `docs/API_SPECIFICATION.md` | Creation eligibility, cursor history, mandatory idempotency key, sender-only ack, participant `unreadCount`, CONNECT CSRF and status deferral reconciled. |
| `docs/DATABASE_DESIGN.md` | V19 scope fixed to idempotency constraint and supporting indexes; ordering, monotonic read state and lifecycle decisions reconciled. |
| `docs/SECURITY_ARCHITECTURE.md` | SD-29/SD-40 approvals, authoritative session validation, outbound dispatch recheck, CSRF CONNECT, safe correlation and receipt distinctions reconciled. |
| `docs/UI_UX_SPECIFICATION.md` | Separate `Message agent` action, local client gate, cursor rendering, mandatory retry key, ack labels and resolved D-11/D-22/D-23/D-30/D-37 reconciled. |
| `docs/TESTING_STRATEGY.md` | Stage 7.1 acceptance planning points to the 55-scenario ledger; no scenario is reported as executed. |
| `docs/DEVELOPMENT_SETUP.md` | Stage-boundary history clarified and pinned framework evidence for CONNECT CSRF recorded. |
| `docs/validation/STAGE_7_1_COMPLETION_REPORT.md` | Single consolidated final report retained as an ignored validation artifact; no separate review or remediation report created. |

No backend, frontend, migration, dependency, deployment or Stage 8/9 implementation file was changed.

---

## 12. Final Stage 7.1 closure statement

All owner decisions P-01–P-13 are approved, all eight findings F-7.1-01–F-7.1-08 have documented resolutions, all formerly unresolved conflicts C-04–C-10 are closed, and C-11–C-18 record the finding reconciliations. The 47 original acceptance scenarios remain in the ledger, 8 scenarios were added, and all 55 remain `NOT EXECUTED`. Stage 7.1 is therefore approved and closed at the documentation and architecture-contract level. Stage 7.2 implementation, exact STOMP-client verification values, exact production hostnames, F-3 implementation, Stage 8/9 obligations and all acceptance evidence remain future gates.
