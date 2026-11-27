-- Stage 7.3/7.4: mandatory message idempotency key and chat indexes.
-- Source: DATABASE_DESIGN.md §11/§25; STAGE_7_DECISION_REGISTER.md P-05,
-- F-7.1-04 and F-7.1-06.
--
-- Additive and data-preserving. V1-V18 remain byte-for-byte unchanged. Existing
-- messages are backfilled with freshly generated UUIDs so the key becomes
-- mandatory without changing message identity, content, sender or ordering.
-- No sequence column, participant table, outbox table, lifecycle constraint or
-- unrelated schema change is introduced.

ALTER TABLE messages
    ADD COLUMN idempotency_key UUID;

UPDATE messages
    SET idempotency_key = gen_random_uuid()
    WHERE idempotency_key IS NULL;

ALTER TABLE messages
    ALTER COLUMN idempotency_key SET NOT NULL;

ALTER TABLE messages
    ADD CONSTRAINT uk_messages_conversation_sender_idempotency
    UNIQUE (conversation_id, sender_id, idempotency_key);

CREATE INDEX idx_messages_conversation_order
    ON messages (conversation_id, sent_at, id);

CREATE INDEX idx_conversation_read_states_user
    ON conversation_read_states (user_id, conversation_id);
