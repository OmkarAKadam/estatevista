-- Approved deletion-outbox ADR: durable provider cleanup after committed
-- property image metadata deletion. No active image row is removed until the
-- cleanup task is durable in the same transaction.

CREATE TABLE property_image_deletion_outbox (
    id UUID PRIMARY KEY,
    property_id UUID NOT NULL,
    image_id UUID NOT NULL,
    storage_provider VARCHAR(30) NOT NULL,
    storage_public_id VARCHAR(255),
    actor_id UUID,
    status VARCHAR(20) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_attempt_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    locked_until TIMESTAMPTZ,
    last_error VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_property_image_deletion_outbox_property
        FOREIGN KEY (property_id) REFERENCES properties (id) ON DELETE RESTRICT,
    CONSTRAINT uq_property_image_deletion_outbox_asset
        UNIQUE (storage_provider, storage_public_id),
    CONSTRAINT ck_property_image_deletion_outbox_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'DEAD_LETTER')),
    CONSTRAINT ck_property_image_deletion_outbox_attempts
        CHECK (attempts >= 0)
);

COMMENT ON TABLE property_image_deletion_outbox IS
    'Durable provider-deletion tasks committed atomically with active image metadata removal. Identifiers only; no bytes, secrets, signatures, or tokens.';

CREATE INDEX idx_property_image_deletion_outbox_due
    ON property_image_deletion_outbox (status, next_attempt_at);

CREATE INDEX idx_property_image_deletion_outbox_lease
    ON property_image_deletion_outbox (status, locked_until);
