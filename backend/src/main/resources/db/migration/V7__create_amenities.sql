-- Stage 2.3 business schema: amenities and the property-amenity join table.
-- Source: DATABASE_DESIGN.md §8.1, §8.2, §14, §19 (V7).

CREATE TABLE amenities (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_amenities_name UNIQUE (name)
);

CREATE TABLE property_amenities (
    property_id UUID NOT NULL,
    amenity_id UUID NOT NULL,
    CONSTRAINT pk_property_amenities PRIMARY KEY (property_id, amenity_id),
    CONSTRAINT fk_property_amenities_property FOREIGN KEY (property_id)
        REFERENCES properties (id) ON DELETE CASCADE,
    CONSTRAINT fk_property_amenities_amenity FOREIGN KEY (amenity_id)
        REFERENCES amenities (id) ON DELETE RESTRICT
);
