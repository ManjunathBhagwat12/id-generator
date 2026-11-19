package com.egov.idgenerator.repository;

import com.egov.idgenerator.entity.TemplateDefinition;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Templates are insert-only: this class has no update or delete, and PostgreSQL triggers reject them as well.
 * Counters are keyed by (template_id, tenant_id) and never touched here, so template changes cannot reset them.
 */
@Repository
public class TemplateRepository {

    /** Name of the unique constraint on templates.format (created in V4). */
    public static final String FORMAT_CONSTRAINT = "uq_templates_format";

    private static final String COLS = "template_id, name, format, created_at, created_by";

    private static final RowMapper<TemplateDefinition> MAPPER = (rs, i) -> new TemplateDefinition(
            rs.getString("template_id"), rs.getString("name"), rs.getString("format"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getString("created_by"));

    private final JdbcTemplate jdbc;

    public TemplateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM templates", Long.class);
        return n == null ? 0 : n;
    }

    public List<TemplateDefinition> findAll() {
        return jdbc.query("SELECT " + COLS + " FROM templates ORDER BY seq", MAPPER);
    }

    public Optional<TemplateDefinition> findById(String templateId) {
        return jdbc.query("SELECT " + COLS + " FROM templates WHERE template_id = ?", MAPPER, templateId)
                .stream().findFirst();
    }

    public TemplateDefinition insert(String templateId, String name, String format, String actor) {
        return jdbc.queryForObject("INSERT INTO templates (template_id, name, format, created_by) VALUES (?, ?, ?, ?) RETURNING "
                + COLS, MAPPER, templateId, name, format, actor);
    }

    public long countIssuedIds(String templateId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM issued_ids WHERE template_id = ?", Long.class, templateId);
        return n == null ? 0 : n;
    }

    /**
     * Validates the NOT VALID foreign keys from issued_ids and counters to templates (V4). Cheap no-op once valid.
     * Needed only for databases that held IDs before the templates table existed: templates are seeded first.
     */
    public void validateReferences() {
        for (String c : jdbc.queryForList("""
                SELECT conname FROM pg_constraint
                WHERE conname IN ('fk_issued_ids_template', 'fk_counters_template') AND NOT convalidated
                """, String.class)) {
            jdbc.execute("ALTER TABLE " + (c.equals("fk_issued_ids_template") ? "issued_ids" : "counters")
                    + " VALIDATE CONSTRAINT " + c);
        }
    }
}
