package com.egov.idgenerator.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistent sequence counters. PostgreSQL is the single source of truth.
 *
 * <h3>Concurrency strategy</h3>
 * A range of {@code count} numbers is reserved with ONE atomic statement:
 * <pre>
 *   INSERT INTO counters (template_id, tenant_id, next_value) VALUES (?, ?, start + count)
 *   ON CONFLICT (template_id, tenant_id)
 *   DO UPDATE SET next_value = counters.next_value + count
 *   RETURNING next_value - count            -- first number of the reserved range
 * </pre>
 * <ul>
 *   <li><b>No read-modify-write in Java.</b> The increment happens inside PostgreSQL, under the row lock
 *       taken by the UPDATE. Concurrent callers queue on that lock and each observes the value committed
 *       by the previous one, so ranges {@code [start, start+count)} can never overlap.</li>
 *   <li><b>First-time creation is race-free.</b> INSERT ... ON CONFLICT relies on the UNIQUE
 *       (template_id, tenant_id) index: if two sessions both try to create the row, one inserts and the
 *       other blocks on the index entry, then takes the DO UPDATE path. No duplicate rows, no
 *       unique-violation error surfaces to the caller.</li>
 *   <li><b>Whole bulk range in one statement</b>, not N increments.</li>
 *   <li><b>Own short transaction (REQUIRES_NEW).</b> The counter commit is independent of whatever the
 *       caller does afterwards, so the row lock is held only for this one statement. If the caller fails
 *       later, the reserved numbers are simply never used: a gap, never a duplicate.</li>
 * </ul>
 */
@Repository
public class CounterRepository {

    private final JdbcTemplate jdbc;

    public CounterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Atomically reserves {@code count} consecutive numbers of an implicit [SEQ:n] counter.
     *
     * @param tenantId     tenant id, or "*" for a counter shared by all tenants
     * @param initialValue first number ever issued for a brand-new counter (1 for implicit counters)
     * @return the first reserved number; the range is {@code [result, result + count - 1]}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long allocate(String templateId, String tenantId, long initialValue, long count) {
        return reserve("""
                INSERT INTO counters (template_id, tenant_id, next_value)
                VALUES (?, ?, ?)
                ON CONFLICT (template_id, tenant_id)
                DO UPDATE SET next_value = counters.next_value + ?
                RETURNING next_value - ?
                """, templateId, tenantId, initialValue, count);
    }

    /**
     * Same atomic statement for a named counter [SEQ_NAME:n], keyed by (counter_name, tenant_id) and shared by all
     * templates that use that name. {@code initialValue} is the configured start; it only applies when the row is
     * first created, so reloading configuration never resets an existing counter.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long allocateNamed(String counterName, String tenantId, long initialValue, long count) {
        return reserve("""
                INSERT INTO named_counters (counter_name, tenant_id, next_value)
                VALUES (?, ?, ?)
                ON CONFLICT (counter_name, tenant_id)
                DO UPDATE SET next_value = named_counters.next_value + ?
                RETURNING next_value - ?
                """, counterName, tenantId, initialValue, count);
    }

    private long reserve(String sql, String key, String tenantId, long initialValue, long count) {
        Long start = jdbc.queryForObject(sql, Long.class, key, tenantId, initialValue + count, count, count);
        if (start == null) {
            throw new IllegalStateException("Counter allocation returned no row");
        }
        return start;
    }
}
