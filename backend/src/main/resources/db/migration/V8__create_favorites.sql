-- Stage 2.3 business schema: buyer favorites.
-- Source: DATABASE_DESIGN.md §9.1, §14, §15.4, §19 (V8).

CREATE TABLE favorites (
    id UUID PRIMARY KEY,
    buyer_id UUID NOT NULL,
    property_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_favorites_buyer FOREIGN KEY (buyer_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_favorites_property FOREIGN KEY (property_id)
        REFERENCES properties (id) ON DELETE CASCADE,
    CONSTRAINT uk_favorites_buyer_property UNIQUE (buyer_id, property_id)
);
