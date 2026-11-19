package com.egov.idgenerator.dto;

import com.egov.idgenerator.entity.City;
import java.time.Instant;

public record TenantResponse(String tenantId, String cityCode, String cityName, boolean active,
                             Instant createdAt, Instant updatedAt) {

    public static TenantResponse from(City c) {
        return new TenantResponse(c.tenantId(), c.cityCode(), c.cityName(), c.active(), c.createdAt(), c.updatedAt());
    }
}
