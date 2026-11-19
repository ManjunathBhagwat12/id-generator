package com.egov.idgenerator.dto;

import java.time.Instant;

public record TemplateAdminDetailResponse(String templateId, String name, String format, boolean issuable,
                                          Instant createdAt, String createdBy, long issuedIdCount) {
}
