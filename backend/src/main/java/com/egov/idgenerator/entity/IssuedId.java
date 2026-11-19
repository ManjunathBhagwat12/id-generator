package com.egov.idgenerator.entity;

import java.time.Instant;

/** A row of issued_ids. createdAt is null before the row is persisted. */
public record IssuedId(String templateId, String tenantId, long sequenceValue, String generatedId, Instant createdAt) {
}
