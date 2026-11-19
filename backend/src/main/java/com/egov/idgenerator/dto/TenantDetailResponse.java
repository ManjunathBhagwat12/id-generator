package com.egov.idgenerator.dto;

import java.time.Instant;

/** @param cityCodeLocked true once the tenant has issued IDs (city code can then no longer change) */
public record TenantDetailResponse(String tenantId, String cityCode, String cityName, boolean active,
                                   Instant createdAt, Instant updatedAt,
                                   long issuedIdCount, boolean cityCodeLocked) {
}
