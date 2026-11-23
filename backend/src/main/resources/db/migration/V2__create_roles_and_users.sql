-- Stage 2.3 business schema: roles and users.
-- Source: DATABASE_DESIGN.md §5.1, §5.2, §14, §15.1, §23.1(E), §19 (V2).

CREATE TABLE roles (
    id UUID PRIMARY KEY,
    name VARCHAR(30) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_roles_name UNIQUE (name)
);

CREATE TABLE users (
    id UUID PRIMARY KEY,
    role_id UUID NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    phone VARCHAR(20),
    account_status VARCHAR(30) NOT NULL,
    email_verified_at TIMESTAMPTZ,
    last_login_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_users_role FOREIGN KEY (role_id)
        REFERENCES roles (id) ON DELETE RESTRICT,
    CONSTRAINT ck_users_account_status CHECK (
        account_status IN ('ACTIVE', 'INACTIVE', 'SUSPENDED')
    )
);

-- Case-insensitive uniqueness on the normalized email value (§5.2, §23.1 E).
CREATE UNIQUE INDEX uk_users_email ON users (LOWER(email));

-- Approved initial roles (§5.1). Identifiers are fixed so the seed is
-- deterministic across fresh databases.
INSERT INTO roles (id, name, description) VALUES
    ('00000000-0000-0000-0000-000000000001', 'BUYER', NULL),
    ('00000000-0000-0000-0000-000000000002', 'AGENT', NULL),
    ('00000000-0000-0000-0000-000000000003', 'ADMIN', NULL);
