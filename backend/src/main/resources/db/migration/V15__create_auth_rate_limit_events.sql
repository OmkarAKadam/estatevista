-- Stage 3.6 security hardening: shared, database-backed authentication rate limiting.
--
-- Each row is one counted attempt. Keys are SHA-256 digests of scope plus a
-- normalized identity; no raw email address, password, token, or client address
-- is stored here. PostgreSQL row locks and the advisory lock in RateLimitService
-- keep the counter correct across application instances.

CREATE TABLE auth_rate_limit_events (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    attempt_key VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_auth_rate_limit_key_time
    ON auth_rate_limit_events (attempt_key, occurred_at);

COMMENT ON TABLE auth_rate_limit_events IS
    'Shared authentication attempt counters for Stage 3.6 rate limiting; no credential material is stored.';
