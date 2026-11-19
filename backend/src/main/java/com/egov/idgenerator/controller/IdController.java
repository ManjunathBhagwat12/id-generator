package com.egov.idgenerator.controller;

import com.egov.idgenerator.dto.BulkIssueRequest;
import com.egov.idgenerator.dto.BulkIssueResponse;
import com.egov.idgenerator.dto.IssueRequest;
import com.egov.idgenerator.dto.IssueResponse;
import com.egov.idgenerator.dto.RecentIdResponse;
import com.egov.idgenerator.service.IdGenerationService;
import com.egov.idgenerator.security.ApiClient;
import com.egov.idgenerator.security.ApiKeyAuthentication;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ids")
public class IdController {

    private static final int RECENT_LIMIT = 20;

    private final IdGenerationService service;

    public IdController(IdGenerationService service) {
        this.service = service;
    }

    @PostMapping("/issue")
    public IssueResponse issue(@Valid @RequestBody IssueRequest req, Authentication auth) {
        return new IssueResponse(service.issue(req.templateId(), req.city(), 1, apiClient(auth)).get(0));
    }

    @PostMapping("/bulk")
    public BulkIssueResponse bulk(@Valid @RequestBody BulkIssueRequest req, Authentication auth) {
        List<String> ids = service.issue(req.templateId(), req.city(), req.count(), apiClient(auth));
        return new BulkIssueResponse(ids.size(), ids);
    }

    /** The external application if this request carries a valid X-API-Key; null for the public/admin web flow. */
    private static ApiClient apiClient(Authentication auth) {
        return auth instanceof ApiKeyAuthentication a ? a.client() : null;
    }

    @GetMapping("/recent")
    public List<RecentIdResponse> recent() {
        return service.recent(RECENT_LIMIT).stream()
                .map(i -> new RecentIdResponse(i.generatedId(), i.templateId(), i.tenantId(),
                        i.sequenceValue(), i.createdAt()))
                .toList();
    }
}
