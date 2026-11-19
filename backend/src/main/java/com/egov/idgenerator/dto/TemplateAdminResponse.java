package com.egov.idgenerator.dto;

import java.time.Instant;

/** @param issuable whether IDs can be generated from it (all its placeholders are supported) */
public record TemplateAdminResponse(String templateId, String name, String format, boolean issuable,
                                    Instant createdAt, String createdBy) {
}
