package com.egov.idgenerator.security;

import java.util.Set;
import java.util.UUID;

/** The authenticated external application behind a valid X-API-Key: what it may do, never the key itself. */
public record ApiClient(UUID credentialId, String appName, String tenantId, Set<String> templates) {

    public boolean mayUseTemplate(String templateId) {
        return templateId != null && templates.contains(templateId);
    }
}
