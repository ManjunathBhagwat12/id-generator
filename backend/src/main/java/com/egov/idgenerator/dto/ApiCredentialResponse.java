package com.egov.idgenerator.dto;

import com.egov.idgenerator.entity.ApiCredential;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Everything about a credential EXCEPT the key: only the short display prefix (e.g. egid_live_Ab3dEf9h). */
public record ApiCredentialResponse(UUID id, String appName, String description, String tenantId, String cityName,
                                    List<String> templates, String keyPrefix, String status,
                                    Instant createdAt, String createdBy, Instant lastUsedAt, Instant rotatedAt,
                                    Instant revokedAt, String revokedBy) {

    public static ApiCredentialResponse from(ApiCredential c) {
        return new ApiCredentialResponse(c.id(), c.appName(), c.description(), c.tenantId(), c.cityName(), c.templates(),
                c.keyPrefix(), c.status(), c.createdAt(), c.createdBy(), c.lastUsedAt(), c.rotatedAt(), c.revokedAt(),
                c.revokedBy());
    }
}
