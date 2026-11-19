package com.egov.idgenerator.controller;

import com.egov.idgenerator.dto.CreateTemplateRequest;
import com.egov.idgenerator.dto.TemplateAdminDetailResponse;
import com.egov.idgenerator.dto.TemplateAdminResponse;
import com.egov.idgenerator.service.TemplateAdminService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only (SecurityConfig: /api/v1/admin/** requires ROLE_ADMIN).
 * Deliberately has NO PUT, PATCH or DELETE mapping: templates are immutable.
 */
@RestController
@RequestMapping("/api/v1/admin/templates")
public class AdminTemplateController {

    private final TemplateAdminService service;

    public AdminTemplateController(TemplateAdminService service) {
        this.service = service;
    }

    @GetMapping
    public List<TemplateAdminResponse> list() {
        return service.list();
    }

    @GetMapping("/{templateId}")
    public TemplateAdminDetailResponse get(@PathVariable String templateId) {
        return service.get(templateId.trim().toUpperCase(Locale.ROOT));
    }

    @PostMapping
    public ResponseEntity<TemplateAdminResponse> create(@Valid @RequestBody CreateTemplateRequest req, Authentication auth) {
        TemplateAdminResponse created = service.create(req, auth.getName());
        return ResponseEntity.created(URI.create("/api/v1/admin/templates/" + created.templateId())).body(created);
    }
}
