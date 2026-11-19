package com.egov.idgenerator.dto;

import jakarta.validation.constraints.NotNull;

public record TenantStatusRequest(@NotNull(message = "is required (true or false)") Boolean active) {
}
