package com.egov.idgenerator.dto;

import jakarta.validation.constraints.NotBlank;

public record IssueRequest(
        @NotBlank(message = "is required") String templateId,
        @NotBlank(message = "is required (tenant_id or city_code)") String city) {
}
