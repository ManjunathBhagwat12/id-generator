-- Simplification: remove template versioning. A template has ONE immutable format.
-- (V3 was already applied on existing databases, so it is superseded here rather than edited.)

-- 1. Issued IDs reference the template only (no version).
ALTER TABLE issued_ids DROP CONSTRAINT IF EXISTS fk_issued_ids_template_version;
ALTER TABLE issued_ids DROP CONSTRAINT IF EXISTS chk_issued_ids_template_version;
ALTER TABLE issued_ids DROP COLUMN IF EXISTS template_version;

-- 2. The template carries its format. Existing databases: keep the format that was ACTIVE, else the first one.
DROP TRIGGER IF EXISTS trg_templates_append_only ON templates;
DROP TRIGGER IF EXISTS trg_templates_no_truncate ON templates;
ALTER TABLE templates ADD COLUMN IF NOT EXISTS format VARCHAR(200);
UPDATE templates t SET format = COALESCE(
        (SELECT v.format FROM template_versions v WHERE v.template_id = t.template_id AND v.status = 'ACTIVE'),
        (SELECT v.format FROM template_versions v WHERE v.template_id = t.template_id ORDER BY v.version LIMIT 1))
 WHERE t.format IS NULL;
ALTER TABLE templates ALTER COLUMN format SET NOT NULL;
-- Two templates with the same format would generate colliding IDs.
ALTER TABLE templates ADD CONSTRAINT uq_templates_format UNIQUE (format);

-- 3. Drop everything version-related.
DROP TABLE IF EXISTS template_audit_log;
DROP TABLE IF EXISTS template_versions;
DROP FUNCTION IF EXISTS guard_template_versions();

-- 4. Templates stay immutable and undeletable, enforced by the database.
CREATE TRIGGER trg_templates_append_only BEFORE UPDATE OR DELETE ON templates
    FOR EACH ROW EXECUTE FUNCTION guard_append_only();
CREATE TRIGGER trg_templates_no_truncate BEFORE TRUNCATE ON templates
    FOR EACH STATEMENT EXECUTE FUNCTION guard_append_only();

-- 5. Issued IDs and counters must belong to an existing template, which can never be removed or altered.
--    NOT VALID: enforced for every new row immediately; legacy rows are validated by the application at startup
--    (after the templates have been seeded on a database that predates the templates table).
ALTER TABLE issued_ids ADD CONSTRAINT fk_issued_ids_template
    FOREIGN KEY (template_id) REFERENCES templates (template_id) ON DELETE RESTRICT NOT VALID;
ALTER TABLE counters ADD CONSTRAINT fk_counters_template
    FOREIGN KEY (template_id) REFERENCES templates (template_id) ON DELETE RESTRICT NOT VALID;
