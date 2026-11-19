package com.egov.idgenerator.controller;

import com.egov.idgenerator.dto.CreateTenantRequest;
import com.egov.idgenerator.dto.TenantDetailResponse;
import com.egov.idgenerator.dto.TenantResponse;
import com.egov.idgenerator.dto.TenantStatusRequest;
import com.egov.idgenerator.dto.TenantSummaryResponse;
import com.egov.idgenerator.dto.UpdateTenantRequest;
import com.egov.idgenerator.dto.TenantRules;
import com.egov.idgenerator.exception.ApiException;
import com.egov.idgenerator.service.TenantAdminService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only (enforced in SecurityConfig: /api/v1/admin/** requires ROLE_ADMIN). */
@RestController
@RequestMapping("/api/v1/admin/tenants")
public class AdminTenantController {

    private final TenantAdminService service;

    public AdminTenantController(TenantAdminService service) {
        this.service = service;
    }

    /** @param status all (default) | active | inactive */
    @GetMapping
    public List<TenantResponse> list(@RequestParam(required = false) String search,
                                     @RequestParam(defaultValue = "all") String status) {
        Boolean active = switch (status.toLowerCase()) {
            case "all" -> null;
            case "active" -> true;
            case "inactive" -> false;
            default -> throw ApiException.invalid("status must be all, active or inactive");
        };
        return service.list(search, active).stream().map(TenantResponse::from).toList();
    }

    @GetMapping("/summary")
    public TenantSummaryResponse summary() {
        return service.summary();
    }

    @GetMapping("/{tenantId}")
    public TenantDetailResponse get(@PathVariable String tenantId) {
        return service.detail(TenantRules.tenantId(tenantId));
    }

    @PostMapping
    public ResponseEntity<TenantResponse> create(@Valid @RequestBody CreateTenantRequest req) {
        TenantResponse created = TenantResponse.from(service.create(req));
        return ResponseEntity.created(URI.create("/api/v1/admin/tenants/" + created.tenantId())).body(created);
    }

    @PutMapping("/{tenantId}")
    public TenantResponse update(@PathVariable String tenantId, @Valid @RequestBody UpdateTenantRequest req) {
        return TenantResponse.from(service.update(TenantRules.tenantId(tenantId), req));
    }

    @PatchMapping("/{tenantId}/status")
    public TenantResponse setStatus(@PathVariable String tenantId, @Valid @RequestBody TenantStatusRequest req) {
        return TenantResponse.from(service.setActive(TenantRules.tenantId(tenantId), req.active()));
    }
}
