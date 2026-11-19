package com.egov.idgenerator.repository;

import com.egov.idgenerator.entity.City;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Data access for the cities (tenants) table. PostgreSQL constraints are the source of truth for uniqueness. */
@Repository
public class CityRepository {

    /** Default PostgreSQL names of the unique constraints created in V1. */
    public static final String TENANT_ID_CONSTRAINT = "cities_pkey";
    public static final String CITY_CODE_CONSTRAINT = "cities_city_code_key";

    public record Summary(long total, long active) {
    }

    private static final String COLS = "tenant_id, city_code, city_name, active, created_at, updated_at";

    private static final RowMapper<City> MAPPER = (rs, i) -> new City(
            rs.getString("tenant_id"), rs.getString("city_code"), rs.getString("city_name"),
            rs.getBoolean("active"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public CityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM cities", Long.class);
        return n == null ? 0 : n;
    }

    /**
     * Seeds initial data ONLY if the table is empty; existing (possibly UI-managed) tenants are never touched.
     * ON CONFLICT DO NOTHING keeps this safe if two instances start simultaneously.
     *
     * @return true if the seed was applied
     */
    @Transactional
    public boolean seedIfEmpty(List<City> cities) {
        if (count() > 0) {
            return false;
        }
        jdbc.batchUpdate("""
                INSERT INTO cities (tenant_id, city_code, city_name) VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                cities, cities.size(),
                (ps, c) -> {
                    ps.setString(1, c.tenantId());
                    ps.setString(2, c.cityCode());
                    ps.setString(3, c.cityName());
                });
        return true;
    }

    /** @param search case-insensitive substring over tenant id / code / name, or null; @param active filter or null */
    public List<City> search(String search, Boolean active) {
        StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM cities WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (search != null && !search.isBlank()) {
            String like = "%" + search.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            sql.append(" AND (tenant_id ILIKE ? OR city_code ILIKE ? OR city_name ILIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (active != null) {
            sql.append(" AND active = ?");
            args.add(active);
        }
        sql.append(" ORDER BY city_name, tenant_id");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public Optional<City> findByTenantId(String tenantId) {
        return jdbc.query("SELECT " + COLS + " FROM cities WHERE tenant_id = ?", MAPPER, tenantId).stream().findFirst();
    }

    /** Row-locks the tenant for the rest of the current transaction (serialises concurrent edits). */
    public Optional<City> findByTenantIdForUpdate(String tenantId) {
        return jdbc.query("SELECT " + COLS + " FROM cities WHERE tenant_id = ? FOR UPDATE", MAPPER, tenantId)
                .stream().findFirst();
    }

    /** Resolves a tenant_id OR a city_code (case-insensitive) with two indexed lookups. */
    public Optional<City> findByIdentifier(String identifier) {
        String raw = identifier.trim();
        return jdbc.query("SELECT " + COLS + " FROM cities WHERE tenant_id = ? OR city_code = ? LIMIT 1", MAPPER,
                raw.toLowerCase(Locale.ROOT), raw.toUpperCase(Locale.ROOT)).stream().findFirst();
    }

    public City insert(String tenantId, String cityCode, String cityName) {
        return jdbc.queryForObject("INSERT INTO cities (tenant_id, city_code, city_name) VALUES (?, ?, ?) RETURNING " + COLS,
                MAPPER, tenantId, cityCode, cityName);
    }

    public Optional<City> update(String tenantId, String cityCode, String cityName) {
        return jdbc.query("""
                UPDATE cities SET city_code = ?, city_name = ?, updated_at = now()
                WHERE tenant_id = ? RETURNING\s""" + COLS, MAPPER, cityCode, cityName, tenantId).stream().findFirst();
    }

    public Optional<City> setActive(String tenantId, boolean active) {
        return jdbc.query("UPDATE cities SET active = ?, updated_at = now() WHERE tenant_id = ? RETURNING " + COLS,
                MAPPER, active, tenantId).stream().findFirst();
    }

    public Summary summary() {
        return jdbc.queryForObject("SELECT count(*), count(*) FILTER (WHERE active) FROM cities",
                (rs, i) -> new Summary(rs.getLong(1), rs.getLong(2)));
    }

    public boolean hasIssuedIds(String tenantId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM issued_ids WHERE tenant_id = ?)", Boolean.class, tenantId));
    }

    public long countIssuedIds(String tenantId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM issued_ids WHERE tenant_id = ?", Long.class, tenantId);
        return n == null ? 0 : n;
    }
}
