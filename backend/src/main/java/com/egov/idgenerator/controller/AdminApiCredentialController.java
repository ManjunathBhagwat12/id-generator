package com.egov.idgenerator.controller;

import com.egov.idgenerator.dto.ApiCredentialDetailResponse;
import com.egov.idgenerator.dto.ApiCredentialResponse;
import com.egov.idgenerator.dto.CreateApiCredentialRequest;
import com.egov.idgenerator.dto.IssuedApiKeyResponse;
import com.egov.idgenerator.service.ApiCredentialService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only (SecurityConfig: /api/v1/admin/** requires ROLE_ADMIN; an API key is ROLE_API_CLIENT and gets 403).
 * Only create and rotate ever return a full key, with Cache-Control: no-store.
 */
@RestController
@RequestMapping("/api/v1/admin/api-credentials")
public class AdminApiCredentialController {

    private final ApiCredentialService service;

    public AdminApiCredentialController(ApiCredentialService service) {
        this.service = service;
    }

    @GetMapping
    public List<ApiCredentialResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public ApiCredentialDetailResponse get(@PathVariable UUID id) {
        return service.detail(id);
    }

    @PostMapping
    public ResponseEntity<IssuedApiKeyResponse> create(@Valid @RequestBody CreateApiCredentialRequest req, Authentication auth) {
        IssuedApiKeyResponse r = service.create(req, auth.getName());
        return ResponseEntity.created(URI.create("/api/v1/admin/api-credentials/" + r.credential().id()))
                .cacheControl(CacheControl.noStore()).body(r);
    }

    @PostMapping("/{id}/rotate")
    public ResponseEntity<IssuedApiKeyResponse> rotate(@PathVariable UUID id, Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.rotate(id, auth.getName()));
    }

    @PostMapping("/{id}/revoke")
    public ApiCredentialResponse revoke(@PathVariable UUID id, Authentication auth) {
        return service.revoke(id, auth.getName());
    }
}
