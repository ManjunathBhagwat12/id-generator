-- API credentials for external applications. The plaintext key is NEVER stored: only its SHA-256 hash and a short
-- display prefix. CHECK constraints make it impossible to store anything else in those columns.
CREATE TABLE IF NOT EXISTS api_credentials (
    id           UUID          PRIMARY KEY,
    app_name     VARCHAR(100)  NOT NULL,
    description  VARCHAR(500),
    tenant_id    VARCHAR(100)  NOT NULL REFERENCES cities (tenant_id) ON DELETE RESTRICT,
    key_prefix   VARCHAR(18)   NOT NULL CHECK (key_prefix ~ '^egid_live_[A-Za-z0-9_-]{8}$'),
    key_hash     CHAR(64)      NOT NULL CHECK (key_hash ~ '^[0-9a-f]{64}$'),
    status       VARCHAR(10)   NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by   VARCHAR(100)  NOT NULL,
    last_used_at TIMESTAMPTZ,
    rotated_at   TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ,
    revoked_by   VARCHAR(100),
    CONSTRAINT uq_api_credentials_key_hash UNIQUE (key_hash),
    CONSTRAINT chk_api_credentials_revoked CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL))
);

-- Templates a credential may issue from (at least one; enforced by the application).
CREATE TABLE IF NOT EXISTS api_credential_templates (
    credential_id UUID        NOT NULL REFERENCES api_credentials (id) ON DELETE RESTRICT,
    template_id   VARCHAR(50) NOT NULL REFERENCES templates (template_id) ON DELETE RESTRICT,
    PRIMARY KEY (credential_id, template_id)
);

-- Administrative history: who created, rotated or revoked a credential, and when.
CREATE TABLE IF NOT EXISTS api_credential_audit (
    id            BIGSERIAL    PRIMARY KEY,
    credential_id UUID         NOT NULL REFERENCES api_credentials (id) ON DELETE RESTRICT,
    action        VARCHAR(20)  NOT NULL CHECK (action IN ('CREATED', 'ROTATED', 'REVOKED')),
    actor         VARCHAR(100) NOT NULL,
    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    details       VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_api_credential_audit_credential ON api_credential_audit (credential_id, id);
