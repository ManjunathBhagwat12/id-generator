-- Idempotent schema: safe to run on every startup, never drops or resets data.

CREATE TABLE IF NOT EXISTS cities (
    tenant_id  VARCHAR(100) PRIMARY KEY,
    city_code  VARCHAR(20)  NOT NULL UNIQUE,
    city_name  VARCHAR(200) NOT NULL
);

-- One row per (template, tenant). next_value is the NEXT sequence number to hand out.
CREATE TABLE IF NOT EXISTS counters (
    id          BIGSERIAL PRIMARY KEY,
    template_id VARCHAR(100) NOT NULL,
    tenant_id   VARCHAR(100) NOT NULL,
    next_value  BIGINT       NOT NULL,
    CONSTRAINT uq_counters_template_tenant UNIQUE (template_id, tenant_id)
);

-- Audit trail + last line of defence against duplicates (UNIQUE generated_id).
CREATE TABLE IF NOT EXISTS issued_ids (
    id             BIGSERIAL PRIMARY KEY,
    template_id    VARCHAR(100) NOT NULL,
    tenant_id      VARCHAR(100) NOT NULL,
    sequence_value BIGINT       NOT NULL,
    generated_id   VARCHAR(200) NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_issued_ids_generated_id UNIQUE (generated_id)
);

-- Serves "latest N issued IDs" with an index scan and no sort.
CREATE INDEX IF NOT EXISTS idx_issued_ids_recent ON issued_ids (created_at DESC, id DESC);
