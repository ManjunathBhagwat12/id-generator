-- Named counters ([SEQ_NAME:n]) are shared by every template that uses the same name, per tenant.
-- Same atomic allocation pattern as counters. tenant_id '*' = shared by all tenants (format without [CITY]).
CREATE TABLE IF NOT EXISTS named_counters (
    id           BIGSERIAL PRIMARY KEY,
    counter_name VARCHAR(100) NOT NULL,
    tenant_id    VARCHAR(100) NOT NULL,
    next_value   BIGINT       NOT NULL CHECK (next_value >= 1),
    CONSTRAINT uq_named_counters_name_tenant UNIQUE (counter_name, tenant_id)
);

COMMENT ON COLUMN counters.tenant_id IS
    'Tenant the counter belongs to, or ''*'' when the template format has no [CITY] (counter shared by all tenants)';
COMMENT ON COLUMN named_counters.tenant_id IS
    'Tenant the counter belongs to, or ''*'' when the template format has no [CITY] (counter shared by all tenants)';
COMMENT ON CONSTRAINT uq_issued_ids_generated_id ON issued_ids IS
    'Identifier strings are globally unique across all templates and tenants';
