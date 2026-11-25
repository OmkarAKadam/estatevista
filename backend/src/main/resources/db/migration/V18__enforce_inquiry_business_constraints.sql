-- Stage 6.4: enforce approved inquiry business constraints.
-- Source: DATABASE_DESIGN.md §24.3, STAGE_6_DECISION_REGISTER.md §8, API_SPECIFICATION.md §10.2.
--
-- Additive only. V1-V17 remain unchanged. Existing inquiry rows are preserved;
-- incompatible pre-existing data must be reported and remediated with separate
-- owner approval before applying this migration.

ALTER TABLE inquiries
    ADD CONSTRAINT ck_inquiries_status
    CHECK (status IN ('NEW', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'));

CREATE UNIQUE INDEX uk_inquiries_active_buyer_property
    ON inquiries (buyer_id, property_id)
    WHERE status IN ('NEW', 'IN_PROGRESS');
