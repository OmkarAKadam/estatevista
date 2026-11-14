-- Stage 2.3 business schema: append-only audit history.
-- Source: DATABASE_DESIGN.md §13.1, §15.9, §17.1, §19 (V13).
--
-- actor_id becomes NULL when an account is eventually removed while the audit
-- record remains (§13.1).

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY,
    actor_id UUID,
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(80) NOT NULL,
    entity_id UUID,
    before_data JSONB,
    after_data JSONB,
    request_id VARCHAR(100),
    ip_address INET,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_audit_logs_actor FOREIGN KEY (actor_id)
        REFERENCES users (id) ON DELETE SET NULL
);
