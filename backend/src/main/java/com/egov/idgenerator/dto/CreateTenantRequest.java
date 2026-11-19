package com.egov.idgenerator.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Inputs are normalised in the constructor (trim; tenant id lower-case; city code upper-case) BEFORE bean
 * validation runs, so "  TN.Coimbatore " and "cbe" are accepted as canonical values and everything else is rejected.
 * A tenant id must contain a dot, which guarantees it can never equal a city code (letters/digits only).
 */
public record CreateTenantRequest(
        @NotBlank(message = "is required")
        @Size(max = 100, message = "must be at most 100 characters")
        @Pattern(regexp = TenantRules.TENANT_ID, message = TenantRules.TENANT_ID_MSG)
        String tenantId,

        @NotBlank(message = "is required")
        @Pattern(regexp = TenantRules.CITY_CODE, message = TenantRules.CITY_CODE_MSG)
        String cityCode,

        @NotBlank(message = "is required")
        @Size(min = 2, max = 200, message = "must be 2 to 200 characters")
        @Pattern(regexp = TenantRules.CITY_NAME, message = TenantRules.CITY_NAME_MSG)
        String cityName) {

    public CreateTenantRequest {
        tenantId = TenantRules.tenantId(tenantId);
        cityCode = TenantRules.cityCode(cityCode);
        cityName = TenantRules.cityName(cityName);
    }
}
