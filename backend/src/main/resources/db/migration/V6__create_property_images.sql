-- Stage 2.3 business schema: property image references.
-- Source: DATABASE_DESIGN.md §7.1, §15.3, §19 (V6).

CREATE TABLE property_images (
    id UUID PRIMARY KEY,
    property_id UUID NOT NULL,
    storage_provider VARCHAR(30) NOT NULL,
    storage_public_id VARCHAR(255),
    image_url TEXT NOT NULL,
    alt_text VARCHAR(255),
    sort_order INTEGER NOT NULL,
    is_primary BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_property_images_property FOREIGN KEY (property_id)
        REFERENCES properties (id) ON DELETE RESTRICT
);

-- At most one primary image per property (§7.1).
CREATE UNIQUE INDEX uk_property_images_primary
    ON property_images (property_id)
    WHERE is_primary = TRUE;
