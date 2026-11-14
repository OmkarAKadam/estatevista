-- Stage 2.3 business schema: property categories and subtypes.
-- Source: DATABASE_DESIGN.md §6.1, §6.2, §14, §23.1(A), §19 (V4).

CREATE TABLE property_categories (
    id UUID PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_property_categories_name UNIQUE (name)
);

CREATE TABLE property_subtypes (
    id UUID PRIMARY KEY,
    category_id UUID NOT NULL,
    name VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_property_subtypes_category FOREIGN KEY (category_id)
        REFERENCES property_categories (id) ON DELETE RESTRICT,
    CONSTRAINT uk_property_subtypes_category_name UNIQUE (category_id, name),
    -- Required as the target of the properties composite foreign key (§23.1 A).
    CONSTRAINT uk_property_subtypes_category_id UNIQUE (category_id, id)
);

-- Approved initial categories (§6.1). Child subtypes are reference data created
-- at runtime; the design lists them only as examples, so none are seeded.
INSERT INTO property_categories (id, name) VALUES
    ('00000000-0000-0000-0000-000000000101', 'RESIDENTIAL'),
    ('00000000-0000-0000-0000-000000000102', 'COMMERCIAL');
