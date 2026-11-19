package com.egov.idgenerator.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Tenant id is taken from the URL and is immutable. */
public record UpdateTenantRequest(
        @NotBlank(message = "is required")
        @Pattern(regexp = TenantRules.CITY_CODE, message = TenantRules.CITY_CODE_MSG)
        String cityCode,

        @NotBlank(message = "is required")
        @Size(min = 2, max = 200, message = "must be 2 to 200 characters")
        @Pattern(regexp = TenantRules.CITY_NAME, message = TenantRules.CITY_NAME_MSG)
        String cityName) {

    public UpdateTenantRequest {
        cityCode = TenantRules.cityCode(cityCode);
        cityName = TenantRules.cityName(cityName);
    }
}
