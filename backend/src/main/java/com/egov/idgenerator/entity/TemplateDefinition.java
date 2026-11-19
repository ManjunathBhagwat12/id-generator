package com.egov.idgenerator.entity;

import java.time.Instant;

/** A template: one id, one format, immutable. A different format means a different template id. */
public record TemplateDefinition(String templateId, String name, String format, Instant createdAt, String createdBy) {
}
