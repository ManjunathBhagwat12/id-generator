package com.egov.idgenerator.repository;

import com.egov.idgenerator.entity.IssuedId;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class IssuedIdRepository {

    private final JdbcTemplate jdbc;

    public IssuedIdRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Persists a batch atomically (all or nothing). UNIQUE(generated_id) makes a duplicate impossible
     * even if the counter were ever tampered with: the whole batch fails instead of issuing a duplicate.
     */
    @Transactional
    public void saveAll(List<IssuedId> ids) {
        jdbc.batchUpdate("""
                INSERT INTO issued_ids (template_id, tenant_id, sequence_value, generated_id)
                VALUES (?, ?, ?, ?)
                """,
                ids, ids.size(),
                (ps, id) -> {
                    ps.setString(1, id.templateId());
                    ps.setString(2, id.tenantId());
                    ps.setLong(3, id.sequenceValue());
                    ps.setString(4, id.generatedId());
                });
    }

    /** Latest issued IDs, newest first. id DESC breaks created_at ties deterministically. */
    public List<IssuedId> findRecent(int limit) {
        return jdbc.query("""
                SELECT template_id, tenant_id, sequence_value, generated_id, created_at
                FROM issued_ids ORDER BY created_at DESC, id DESC LIMIT ?
                """,
                (rs, i) -> {
                    Timestamp ts = rs.getTimestamp(5);
                    return new IssuedId(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4),
                            ts.toInstant());
                },
                limit);
    }
}
