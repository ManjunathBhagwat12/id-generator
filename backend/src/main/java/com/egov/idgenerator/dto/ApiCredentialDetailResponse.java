package com.egov.idgenerator.dto;

import java.time.Instant;
import java.util.List;

public record ApiCredentialDetailResponse(ApiCredentialResponse credential, List<Audit> audit) {

    public record Audit(String action, String actor, Instant occurredAt, String details) {
    }
}
