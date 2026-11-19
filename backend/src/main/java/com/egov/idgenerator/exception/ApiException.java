package com.egov.idgenerator.exception;

import org.springframework.http.HttpStatus;

/** Base class for expected, client-facing errors. The message is safe to show to API consumers. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static ApiException templateNotFound(String id) {
        return new ApiException(HttpStatus.NOT_FOUND, "Unknown template: " + id);
    }

    public static ApiException cityNotFound(String city) {
        return new ApiException(HttpStatus.NOT_FOUND, "Unknown city (tenant_id or city_code): " + city);
    }

    public static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException templateNotEnabled(String id, java.util.List<String> reasons) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Template " + id + " cannot issue IDs: " + String.join("; ", reasons));
    }

    public static ApiException templateNotEnabled(String id) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Template " + id + " exists but is not enabled for issuance yet");
    }

    public static ApiException sequenceExhausted(String counter, String tenantId) {
        return new ApiException(HttpStatus.CONFLICT,
                "Sequence space exhausted for counter " + counter + " and tenant " + tenantId);
    }

    public static ApiException tenantInactive(String tenantId) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Tenant " + tenantId + " is inactive and cannot issue IDs");
    }

    public static ApiException tenantNotFound(String tenantId) {
        return new ApiException(HttpStatus.NOT_FOUND, "Unknown tenant: " + tenantId);
    }

    public static ApiException duplicate(String field) {
        return new ApiException(HttpStatus.CONFLICT, "A tenant with this " + field + " already exists");
    }

    public static ApiException cityCodeLocked(String tenantId) {
        return new ApiException(HttpStatus.CONFLICT,
                "City code cannot be changed: tenant " + tenantId + " has already issued IDs. Rename the city or deactivate the tenant instead");
    }

    public static ApiException duplicateTemplate(String templateId) {
        return new ApiException(HttpStatus.CONFLICT, "Template " + templateId + " already exists");
    }

    public static ApiException duplicateFormat() {
        return new ApiException(HttpStatus.CONFLICT,
                "Another template already uses exactly this format, which would produce duplicate IDs");
    }

    public static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, message);
    }
}
