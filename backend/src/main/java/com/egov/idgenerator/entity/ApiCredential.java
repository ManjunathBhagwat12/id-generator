package com.egov.idgenerator.entity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An API credential as stored, WITHOUT any key material except the short display prefix. */
public record ApiCredential(UUID id, String appName, String description, String tenantId, String cityName,
                            List<String> templates, String keyPrefix, String status,
                            Instant createdAt, String createdBy, Instant lastUsedAt, Instant rotatedAt,
                            Instant revokedAt, String revokedBy) {
}
