package com.egov.idgenerator.entity;

import java.time.Instant;

/**
 * A tenant / city row. Seeded from cities.csv when the table is empty, then managed through the admin API.
 * Inactive tenants are kept (never deleted) but cannot issue IDs.
 */
public record City(String tenantId, String cityCode, String cityName, boolean active,
                   Instant createdAt, Instant updatedAt) {

    /** Convenience for an active tenant without timestamps (CSV parsing, tests). */
    public City(String tenantId, String cityCode, String cityName) {
        this(tenantId, cityCode, cityName, true, null, null);
    }
}
