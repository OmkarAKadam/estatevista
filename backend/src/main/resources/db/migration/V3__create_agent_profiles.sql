-- Stage 2.3 business schema: agent profiles.
-- Source: DATABASE_DESIGN.md §5.3, §14, §19 (V3).

CREATE TABLE agent_profiles (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    agency_name VARCHAR(150),
    license_number VARCHAR(100),
    bio TEXT,
    profile_image_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_agent_profiles_user_id UNIQUE (user_id),
    CONSTRAINT fk_agent_profiles_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE RESTRICT
);
