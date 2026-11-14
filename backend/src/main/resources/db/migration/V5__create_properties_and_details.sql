-- Stage 2.3 business schema: properties and category-specific detail tables.
-- Source: DATABASE_DESIGN.md §6.3, §6.4, §6.5, §14, §15.2, §23.1(A), §19 (V5).

CREATE TABLE properties (
    id UUID PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    slug VARCHAR(220),
    description TEXT NOT NULL,
    category_id UUID NOT NULL,
    subtype_id UUID NOT NULL,
    listing_type VARCHAR(20) NOT NULL,
    price NUMERIC(14, 2) NOT NULL,
    currency_code CHAR(3) NOT NULL,
    area_value NUMERIC(12, 2),
    area_unit VARCHAR(20),
    address_line VARCHAR(255),
    locality VARCHAR(120),
    city VARCHAR(100) NOT NULL,
    state VARCHAR(100) NOT NULL,
    country VARCHAR(100) NOT NULL,
    postal_code VARCHAR(20),
    latitude NUMERIC(10, 7),
    longitude NUMERIC(10, 7),
    status VARCHAR(30) NOT NULL,
    primary_agent_id UUID NOT NULL,
    created_by UUID NOT NULL,
    updated_by UUID,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    archived_at TIMESTAMPTZ,
    CONSTRAINT uk_properties_slug UNIQUE (slug),
    CONSTRAINT fk_properties_category FOREIGN KEY (category_id)
        REFERENCES property_categories (id) ON DELETE RESTRICT,
    -- The subtype must belong to the selected category (§6.2, §23.1 A).
    CONSTRAINT fk_properties_subtype FOREIGN KEY (category_id, subtype_id)
        REFERENCES property_subtypes (category_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_properties_primary_agent FOREIGN KEY (primary_agent_id)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_properties_created_by FOREIGN KEY (created_by)
        REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_properties_updated_by FOREIGN KEY (updated_by)
        REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_properties_listing_type CHECK (
        listing_type IN ('SALE', 'RENT')
    ),
    CONSTRAINT ck_properties_status CHECK (
        status IN ('DRAFT', 'PUBLISHED', 'UNAVAILABLE', 'SOLD', 'RENTED', 'ARCHIVED')
    ),
    CONSTRAINT ck_properties_price_positive CHECK (price > 0),
    CONSTRAINT ck_properties_latitude_range CHECK (
        latitude IS NULL OR (latitude >= -90 AND latitude <= 90)
    ),
    CONSTRAINT ck_properties_longitude_range CHECK (
        longitude IS NULL OR (longitude >= -180 AND longitude <= 180)
    )
);

CREATE TABLE property_residential_details (
    property_id UUID PRIMARY KEY,
    bedrooms SMALLINT,
    bathrooms SMALLINT,
    balconies SMALLINT,
    furnishing_status VARCHAR(30),
    floor_number SMALLINT,
    total_floors SMALLINT,
    parking_spaces SMALLINT,
    CONSTRAINT fk_property_residential_details_property FOREIGN KEY (property_id)
        REFERENCES properties (id) ON DELETE CASCADE
);

CREATE TABLE property_commercial_details (
    property_id UUID PRIMARY KEY,
    parking_spaces SMALLINT,
    floor_number SMALLINT,
    total_floors SMALLINT,
    washrooms SMALLINT,
    power_backup BOOLEAN,
    suitable_for VARCHAR(255),
    CONSTRAINT fk_property_commercial_details_property FOREIGN KEY (property_id)
        REFERENCES properties (id) ON DELETE CASCADE
);
