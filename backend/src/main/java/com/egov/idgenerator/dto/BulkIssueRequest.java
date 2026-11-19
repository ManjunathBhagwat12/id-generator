package com.egov.idgenerator.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** count range (1..max-bulk-count) is enforced in the service, where the configured limit lives. */
public record BulkIssueRequest(
        @NotBlank(message = "is required") String templateId,
        @NotBlank(message = "is required (tenant_id or city_code)") String city,
        @NotNull(message = "is required") Integer count) {
}
