-- Stage 2.3 business schema: buyer inquiries.
-- Source: DATABASE_DESIGN.md §10.1, §15.5, §17, §19 (V9).
--
-- The inquiry status lifecycle is explicitly subject to later workflow approval
-- (§10.1, §21), so no status CHECK constraint is added here.

CREATE TABLE inquiries (
    id UUID PRIMARY KEY,
    buyer_id UUID NOT NULL,
    property_id UUID NOT NULL,
    assigned_agent_id UUID NOT NULL,
    subject VARCHAR(200),
    message TEXT NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    CONSTRAINT fk_inquiries_buyer FOREIGN KEY (buyer_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_inquiries_property FOREIGN KEY (property_id)
        REFERENCES properties (id) ON DELETE RESTRICT,
    CONSTRAINT fk_inquiries_assigned_agent FOREIGN KEY (assigned_agent_id)
        REFERENCES users (id) ON DELETE RESTRICT
);
