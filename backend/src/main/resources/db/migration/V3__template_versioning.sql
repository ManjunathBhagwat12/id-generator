-- Immutable, versioned templates with an audit trail.

CREATE TABLE IF NOT EXISTS templates (
    seq         BIGSERIAL    NOT NULL UNIQUE,          -- stable display order
    template_id VARCHAR(50)  PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by  VARCHAR(100) NOT NULL
);

CREATE TABLE IF NOT EXISTS template_versions (
    id           BIGSERIAL    PRIMARY KEY,
    template_id  VARCHAR(50)  NOT NULL REFERENCES templates (template_id) ON DELETE RESTRICT,
    version      INT          NOT NULL CHECK (version >= 1),
    format       VARCHAR(200) NOT NULL,
    status       VARCHAR(10)  NOT NULL CHECK (status IN ('DRAFT', 'ACTIVE', 'RETIRED')),
    change_note  VARCHAR(500),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by   VARCHAR(100) NOT NULL,
    published_at TIMESTAMPTZ,
    published_by VARCHAR(100),
    retired_at   TIMESTAMPTZ,
    retired_by   VARCHAR(100),
    CONSTRAINT uq_template_versions UNIQUE (template_id, version),
    CONSTRAINT chk_template_version_lifecycle CHECK (
        (status = 'DRAFT'   AND published_at IS NULL AND retired_at IS NULL)
     OR (status = 'ACTIVE'  AND published_at IS NOT NULL AND published_by IS NOT NULL AND retired_at IS NULL)
     OR (status = 'RETIRED' AND published_at IS NOT NULL AND retired_at IS NOT NULL AND retired_by IS NOT NULL))
);

-- At most ONE active version per template, enforced by the database.
CREATE UNIQUE INDEX IF NOT EXISTS uq_one_active_version ON template_versions (template_id) WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS template_audit_log (
    id          BIGSERIAL    PRIMARY KEY,
    template_id VARCHAR(50)  NOT NULL REFERENCES templates (template_id) ON DELETE RESTRICT,
    version     INT,
    action      VARCHAR(30)  NOT NULL,
    actor       VARCHAR(100) NOT NULL,
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    details     VARCHAR(1000)
);
CREATE INDEX IF NOT EXISTS idx_template_audit_template ON template_audit_log (template_id, id);

-- Every issued ID remembers which template version produced it. Nullable only for rows issued before
-- versioning existed (back-filled to version 1 by the application at startup); the NOT VALID check makes
-- the column mandatory for every NEW row.
ALTER TABLE issued_ids ADD COLUMN IF NOT EXISTS template_version INT;
ALTER TABLE issued_ids ADD CONSTRAINT chk_issued_ids_template_version CHECK (template_version IS NOT NULL) NOT VALID;
ALTER TABLE issued_ids ADD CONSTRAINT fk_issued_ids_template_version
    FOREIGN KEY (template_id, template_version) REFERENCES template_versions (template_id, version) ON DELETE RESTRICT;

-- ---------------------------------------------------------------------------------------------
-- Immutability enforced IN THE DATABASE, independent of the application.
-- ---------------------------------------------------------------------------------------------

-- templates and the audit log: append-only (no UPDATE, DELETE or TRUNCATE ever).
CREATE OR REPLACE FUNCTION guard_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% on % is not permitted: the table is append-only', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END $$;

DROP TRIGGER IF EXISTS trg_templates_append_only ON templates;
CREATE TRIGGER trg_templates_append_only BEFORE UPDATE OR DELETE ON templates
    FOR EACH ROW EXECUTE FUNCTION guard_append_only();
DROP TRIGGER IF EXISTS trg_templates_no_truncate ON templates;
CREATE TRIGGER trg_templates_no_truncate BEFORE TRUNCATE ON templates
    FOR EACH STATEMENT EXECUTE FUNCTION guard_append_only();

DROP TRIGGER IF EXISTS trg_audit_append_only ON template_audit_log;
CREATE TRIGGER trg_audit_append_only BEFORE UPDATE OR DELETE ON template_audit_log
    FOR EACH ROW EXECUTE FUNCTION guard_append_only();
DROP TRIGGER IF EXISTS trg_audit_no_truncate ON template_audit_log;
CREATE TRIGGER trg_audit_no_truncate BEFORE TRUNCATE ON template_audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION guard_append_only();

-- template_versions: content never changes; only the lifecycle moves DRAFT -> ACTIVE -> RETIRED.
CREATE OR REPLACE FUNCTION guard_template_versions() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'template versions can never be deleted' USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' OR NEW.published_at IS NOT NULL OR NEW.retired_at IS NOT NULL THEN
            RAISE EXCEPTION 'a new template version must be created as DRAFT' USING ERRCODE = 'integrity_constraint_violation';
        END IF;
        RETURN NEW;
    END IF;

    -- UPDATE: identity and content are frozen for every status, including DRAFT.
    IF NEW.id           IS DISTINCT FROM OLD.id
    OR NEW.template_id  IS DISTINCT FROM OLD.template_id
    OR NEW.version      IS DISTINCT FROM OLD.version
    OR NEW.format       IS DISTINCT FROM OLD.format
    OR NEW.change_note  IS DISTINCT FROM OLD.change_note
    OR NEW.created_at   IS DISTINCT FROM OLD.created_at
    OR NEW.created_by   IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'template version content is immutable' USING ERRCODE = 'integrity_constraint_violation';
    END IF;

    IF OLD.status = 'DRAFT' AND NEW.status = 'ACTIVE' THEN
        IF NEW.retired_at IS NOT NULL OR NEW.retired_by IS NOT NULL THEN
            RAISE EXCEPTION 'invalid publication data' USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSIF OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED' THEN
        IF NEW.published_at IS DISTINCT FROM OLD.published_at OR NEW.published_by IS DISTINCT FROM OLD.published_by THEN
            RAISE EXCEPTION 'publication audit data is immutable' USING ERRCODE = 'integrity_constraint_violation';
        END IF;
    ELSE
        RAISE EXCEPTION 'illegal template version transition % -> %', OLD.status, NEW.status
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS trg_template_versions_guard ON template_versions;
CREATE TRIGGER trg_template_versions_guard BEFORE INSERT OR UPDATE OR DELETE ON template_versions
    FOR EACH ROW EXECUTE FUNCTION guard_template_versions();
DROP TRIGGER IF EXISTS trg_template_versions_no_truncate ON template_versions;
CREATE TRIGGER trg_template_versions_no_truncate BEFORE TRUNCATE ON template_versions
    FOR EACH STATEMENT EXECUTE FUNCTION guard_append_only();
