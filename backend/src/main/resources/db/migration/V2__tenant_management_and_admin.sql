-- Tenant management: extend the existing cities table (no duplicate tenant table).
-- Additive and idempotent: never touches rows in counters or issued_ids.
ALTER TABLE cities ADD COLUMN IF NOT EXISTS active     BOOLEAN     NOT NULL DEFAULT TRUE;
ALTER TABLE cities ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE cities ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
-- Uniqueness of tenant_id (cities_pkey) and city_code (cities_city_code_key) already exists since V1.

-- Tenants that have issued IDs can never be deleted: deactivate instead.
CREATE INDEX IF NOT EXISTS idx_issued_ids_tenant ON issued_ids (tenant_id);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_issued_ids_tenant') THEN
        ALTER TABLE issued_ids
            ADD CONSTRAINT fk_issued_ids_tenant
            FOREIGN KEY (tenant_id) REFERENCES cities (tenant_id) ON DELETE RESTRICT;
    END IF;
END $$;

-- Administrators (passwords are stored only as BCrypt hashes).
CREATE TABLE IF NOT EXISTS admin_users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_admin_users_username UNIQUE (username)
);
