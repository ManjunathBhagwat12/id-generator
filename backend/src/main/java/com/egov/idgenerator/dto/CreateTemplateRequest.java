package com.egov.idgenerator.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Creates a template. It is immediately usable and can never be changed afterwards. */
public record CreateTemplateRequest(
        @NotBlank(message = "is required")
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{2,49}$", message = "must be 3 to 50 characters: capital letters, digits or _, starting with a letter")
        String templateId,

        @NotBlank(message = "is required")
        @Size(min = 3, max = 200, message = "must be 3 to 200 characters")
        @Pattern(regexp = TenantRules.CITY_NAME, message = TenantRules.CITY_NAME_MSG)
        String name,

        @NotBlank(message = "is required")
        @Size(max = 200, message = "must be at most 200 characters")
        String format) {

    public CreateTemplateRequest {
        templateId = templateId == null ? null : templateId.trim().toUpperCase(java.util.Locale.ROOT);
        name = TenantRules.cityName(name);
        format = format == null ? null : format.trim();
    }
}
