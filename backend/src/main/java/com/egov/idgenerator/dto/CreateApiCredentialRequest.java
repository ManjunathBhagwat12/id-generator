package com.egov.idgenerator.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;

/** Inputs are normalised before validation (trimmed; tenant lower-case; template ids upper-case, de-duplicated). */
public record CreateApiCredentialRequest(
        @NotBlank(message = "is required")
        @Size(min = 3, max = 100, message = "must be 3 to 100 characters")
        @Pattern(regexp = "^[\\p{L}\\p{M}0-9][\\p{L}\\p{M}0-9 .,'()&/_-]*$", message = "contains unsupported characters")
        String appName,

        @Size(max = 500, message = "must be at most 500 characters")
        String description,

        @NotBlank(message = "is required")
        String tenantId,

        @NotEmpty(message = "must contain at least one template")
        @Size(max = 50, message = "must contain at most 50 templates")
        List<@NotBlank(message = "must not contain blanks") String> templateIds) {

    public CreateApiCredentialRequest {
        appName = appName == null ? null : appName.trim().replaceAll("\\s+", " ");
        description = description == null || description.isBlank() ? null : description.trim();
        tenantId = tenantId == null ? null : tenantId.trim().toLowerCase(Locale.ROOT);
        templateIds = templateIds == null ? null : templateIds.stream()
                .map(t -> t == null ? null : t.trim().toUpperCase(Locale.ROOT)).distinct().toList();
    }
}
