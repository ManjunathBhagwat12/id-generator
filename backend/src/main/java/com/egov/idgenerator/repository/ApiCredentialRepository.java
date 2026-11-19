package com.egov.idgenerator.repository;

import com.egov.idgenerator.entity.ApiCredential;
import com.egov.idgenerator.security.ApiClient;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** API credentials. Reads never select key_hash except for the authentication lookup, which returns no hash. */
@Repository
public class ApiCredentialRepository {

    public record AuditEntry(String action, String actor, Instant occurredAt, String details) {
    }

    private static final String SELECT = """
            SELECT c.id, c.app_name, c.description, c.tenant_id, ci.city_name, c.key_prefix, c.status,
                   c.created_at, c.created_by, c.last_used_at, c.rotated_at, c.revoked_at, c.revoked_by,
                   COALESCE(array_agg(t.template_id ORDER BY t.template_id) FILTER (WHERE t.template_id IS NOT NULL), '{}') AS templates
            FROM api_credentials c
            JOIN cities ci ON ci.tenant_id = c.tenant_id
            LEFT JOIN api_credential_templates t ON t.credential_id = c.id
            """;
    private static final String GROUP = " GROUP BY c.id, ci.city_name";

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        OffsetDateTime o = rs.getObject(col, OffsetDateTime.class);
        return o == null ? null : o.toInstant();
    }

    private static List<String> strings(Array a) throws SQLException {
        return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
    }

    private static final RowMapper<ApiCredential> MAPPER = (rs, i) -> new ApiCredential(
            rs.getObject("id", UUID.class), rs.getString("app_name"), rs.getString("description"),
            rs.getString("tenant_id"), rs.getString("city_name"), strings(rs.getArray("templates")),
            rs.getString("key_prefix"), rs.getString("status"), ts(rs, "created_at"), rs.getString("created_by"),
            ts(rs, "last_used_at"), ts(rs, "rotated_at"), ts(rs, "revoked_at"), rs.getString("revoked_by"));

    private final JdbcTemplate jdbc;

    public ApiCredentialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ApiCredential> findAll() {
        return jdbc.query(SELECT + GROUP + " ORDER BY c.created_at DESC", MAPPER);
    }

    public Optional<ApiCredential> findById(UUID id) {
        return jdbc.query(SELECT + " WHERE c.id = ?" + GROUP, MAPPER, id).stream().findFirst();
    }

    /** Authentication lookup by hash (unique index). Only ACTIVE credentials are returned. */
    public Optional<ApiClient> findActiveClientByHash(String hash) {
        return jdbc.query("""
                SELECT c.id, c.app_name, c.tenant_id,
                       COALESCE(array_agg(t.template_id) FILTER (WHERE t.template_id IS NOT NULL), '{}') AS templates
                FROM api_credentials c
                LEFT JOIN api_credential_templates t ON t.credential_id = c.id
                WHERE c.key_hash = ? AND c.status = 'ACTIVE'
                GROUP BY c.id
                """,
                (rs, i) -> new ApiClient(rs.getObject("id", UUID.class), rs.getString("app_name"), rs.getString("tenant_id"),
                        new LinkedHashSet<>(strings(rs.getArray("templates")))),
                hash).stream().findFirst();
    }

    public void insert(UUID id, String appName, String description, String tenantId, String prefix, String hash,
                       List<String> templates, String actor) {
        jdbc.update("""
                INSERT INTO api_credentials (id, app_name, description, tenant_id, key_prefix, key_hash, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
                """, id, appName, description, tenantId, prefix, hash, actor);
        jdbc.batchUpdate("INSERT INTO api_credential_templates (credential_id, template_id) VALUES (?, ?)",
                templates, templates.size(), (ps, t) -> {
                    ps.setObject(1, id);
                    ps.setString(2, t);
                });
    }

    /** Row-locks the credential for the rest of the transaction. Empty if it does not exist. */
    public Optional<String> lockStatus(UUID id) {
        return jdbc.query("SELECT status FROM api_credentials WHERE id = ? FOR UPDATE", (rs, i) -> rs.getString(1), id)
                .stream().findFirst();
    }

    /** Replaces the key of an ACTIVE credential; the previous hash no longer exists anywhere. */
    public boolean replaceKey(UUID id, String prefix, String hash) {
        return jdbc.update("""
                UPDATE api_credentials SET key_prefix = ?, key_hash = ?, rotated_at = now()
                WHERE id = ? AND status = 'ACTIVE'
                """, prefix, hash, id) == 1;
    }

    public boolean revoke(UUID id, String actor) {
        return jdbc.update("""
                UPDATE api_credentials SET status = 'REVOKED', revoked_at = now(), revoked_by = ?
                WHERE id = ? AND status = 'ACTIVE'
                """, actor, id) == 1;
    }

    /**
     * Records use. Written at most once a minute per credential, so parallel requests with the same key do not all
     * queue on this row's lock (that would serialise otherwise independent issuance).
     */
    public void touch(UUID id) {
        jdbc.update("""
                UPDATE api_credentials SET last_used_at = now()
                WHERE id = ? AND (last_used_at IS NULL OR last_used_at < now() - interval '60 seconds')
                """, id);
    }

    public void audit(UUID id, String action, String actor, String details) {
        jdbc.update("INSERT INTO api_credential_audit (credential_id, action, actor, details) VALUES (?, ?, ?, ?)",
                id, action, actor, details);
    }

    public List<AuditEntry> findAudit(UUID id) {
        return jdbc.query("""
                SELECT action, actor, occurred_at, details FROM api_credential_audit WHERE credential_id = ? ORDER BY id DESC
                """, (rs, i) -> new AuditEntry(rs.getString(1), rs.getString(2), ts(rs, "occurred_at"), rs.getString(4)), id);
    }
}
