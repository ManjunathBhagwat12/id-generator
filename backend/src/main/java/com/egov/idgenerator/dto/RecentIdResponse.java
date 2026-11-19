package com.egov.idgenerator.dto;

import java.time.Instant;

public record RecentIdResponse(String id, String templateId, String tenantId, long sequence, Instant createdAt) {
}
