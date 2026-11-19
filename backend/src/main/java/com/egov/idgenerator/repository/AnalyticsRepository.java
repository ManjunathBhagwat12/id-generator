package com.egov.idgenerator.repository;

import com.egov.idgenerator.dto.AnalyticsResponse.Day;
import com.egov.idgenerator.dto.AnalyticsResponse.TemplateCount;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Read-only aggregations over issued_ids. Each query is a single GROUP BY in PostgreSQL bounded by
 * [start, end) on created_at, which the existing idx_issued_ids_recent (created_at DESC, id DESC) index serves.
 */
@Repository
public class AnalyticsRepository {

    private final JdbcTemplate jdbc;

    public AnalyticsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static OffsetDateTime utc(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }

    /** One row per calendar day in {@code zone} from..to inclusive; days without IDs are 0 (generate_series). */
    public List<Day> daily(LocalDate from, LocalDate to, ZoneId zone, Instant start, Instant end) {
        return jdbc.query("""
                SELECT g.d::date AS day, COALESCE(c.n, 0) AS n
                FROM generate_series(?::date, ?::date, interval '1 day') AS g(d)
                LEFT JOIN (
                    SELECT (created_at AT TIME ZONE ?)::date AS day, count(*) AS n
                    FROM issued_ids
                    WHERE created_at >= ? AND created_at < ?
                    GROUP BY 1
                ) c ON c.day = g.d::date
                ORDER BY 1
                """,
                (rs, i) -> new Day(rs.getObject(1, LocalDate.class), rs.getLong(2)),
                from, to, zone.getId(), utc(start), utc(end));
    }

    /** Every template (including those with no IDs in the period), highest count first. */
    public List<TemplateCount> byTemplate(Instant start, Instant end) {
        return jdbc.query("""
                SELECT t.template_id, t.name, COALESCE(c.n, 0) AS n
                FROM templates t
                LEFT JOIN (
                    SELECT template_id, count(*) AS n
                    FROM issued_ids
                    WHERE created_at >= ? AND created_at < ?
                    GROUP BY template_id
                ) c ON c.template_id = t.template_id
                ORDER BY n DESC, t.template_id
                """,
                (rs, i) -> new TemplateCount(rs.getString(1), rs.getString(2), rs.getLong(3)),
                utc(start), utc(end));
    }

    /** [all-time total, total within [start, end)] in one pass. */
    public long[] totals(Instant start, Instant end) {
        return jdbc.queryForObject("""
                SELECT count(*), count(*) FILTER (WHERE created_at >= ? AND created_at < ?) FROM issued_ids
                """,
                (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)},
                utc(start), utc(end));
    }
}
