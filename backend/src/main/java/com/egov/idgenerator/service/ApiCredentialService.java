package com.egov.idgenerator.service;

import com.egov.idgenerator.dto.ApiCredentialDetailResponse;
import com.egov.idgenerator.dto.ApiCredentialResponse;
import com.egov.idgenerator.dto.CreateApiCredentialRequest;
import com.egov.idgenerator.dto.IssuedApiKeyResponse;
import com.egov.idgenerator.exception.ApiException;
import com.egov.idgenerator.repository.ApiCredentialRepository;
import com.egov.idgenerator.repository.CityRepository;
import com.egov.idgenerator.repository.TemplateRepository;
import com.egov.idgenerator.security.ApiClient;
import com.egov.idgenerator.security.ApiKeyGenerator;
import com.egov.idgenerator.security.ApiKeyGenerator.GeneratedKey;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API credential lifecycle (create, rotate, revoke) and key authentication. The plaintext key exists only in the
 * create/rotate response; afterwards only its SHA-256 hash is known to the system.
 */
@Service
public class ApiCredentialService {

    private final ApiCredentialRepository repository;
    private final CityRepository cities;
    private final TemplateRepository templates;
    private final ApiKeyGenerator generator;

    public ApiCredentialService(ApiCredentialRepository repository, CityRepository cities, TemplateRepository templates,
                                ApiKeyGenerator generator) {
        this.repository = repository;
        this.cities = cities;
        this.templates = templates;
        this.generator = generator;
    }

    // ---------------------------------------------------------------- authentication

    /** The client for a valid, ACTIVE key; empty for anything else (malformed, unknown, revoked). */
    public Optional<ApiClient> authenticate(String key) {
        if (!ApiKeyGenerator.looksLikeKey(key)) {
            return Optional.empty();
        }
        return repository.findActiveClientByHash(ApiKeyGenerator.hash(key));
    }

    public void markUsed(UUID credentialId) {
        repository.touch(credentialId);
    }

    // ---------------------------------------------------------------- admin

    public List<ApiCredentialResponse> list() {
        return repository.findAll().stream().map(ApiCredentialResponse::from).toList();
    }

    public ApiCredentialDetailResponse detail(UUID id) {
        ApiCredentialResponse c = ApiCredentialResponse.from(repository.findById(id).orElseThrow(() -> notFound(id)));
        return new ApiCredentialDetailResponse(c, repository.findAudit(id).stream()
                .map(a -> new ApiCredentialDetailResponse.Audit(a.action(), a.actor(), a.occurredAt(), a.details())).toList());
    }

    @Transactional
    public IssuedApiKeyResponse create(CreateApiCredentialRequest req, String actor) {
        if (cities.findByTenantId(req.tenantId()).isEmpty()) {
            throw ApiException.invalid("tenantId: unknown tenant " + req.tenantId());
        }
        List<String> unknown = req.templateIds().stream().filter(t -> templates.findById(t).isEmpty()).toList();
        if (!unknown.isEmpty()) {
            throw ApiException.invalid("templateIds: unknown template(s) " + String.join(", ", unknown));
        }
        UUID id = UUID.randomUUID();
        GeneratedKey key = generator.generate();
        repository.insert(id, req.appName(), req.description(), req.tenantId(), key.displayPrefix(), key.hash(),
                req.templateIds(), actor);
        repository.audit(id, "CREATED", actor, "Key " + key.displayPrefix() + "... for tenant " + req.tenantId()
                + ", templates " + String.join(", ", req.templateIds()));
        return issued(id, key);
    }

    /** New key for the same credential; the old key stops working immediately (its hash is overwritten). */
    @Transactional
    public IssuedApiKeyResponse rotate(UUID id, String actor) {
        String status = repository.lockStatus(id).orElseThrow(() -> notFound(id));
        if (!"ACTIVE".equals(status)) {
            throw new ApiException(HttpStatus.CONFLICT, "Credential " + id + " is revoked and cannot be rotated");
        }
        String oldPrefix = repository.findById(id).orElseThrow().keyPrefix();
        GeneratedKey key = generator.generate();
        repository.replaceKey(id, key.displayPrefix(), key.hash());
        repository.audit(id, "ROTATED", actor, "Key " + oldPrefix + "... replaced by " + key.displayPrefix() + "...");
        return issued(id, key);
    }

    @Transactional
    public ApiCredentialResponse revoke(UUID id, String actor) {
        String status = repository.lockStatus(id).orElseThrow(() -> notFound(id));
        if (!"ACTIVE".equals(status)) {
            throw new ApiException(HttpStatus.CONFLICT, "Credential " + id + " is already revoked");
        }
        repository.revoke(id, actor);
        repository.audit(id, "REVOKED", actor, "Key can no longer be used");
        return ApiCredentialResponse.from(repository.findById(id).orElseThrow());
    }

    private IssuedApiKeyResponse issued(UUID id, GeneratedKey key) {
        return new IssuedApiKeyResponse(ApiCredentialResponse.from(repository.findById(id).orElseThrow()),
                key.plaintext(), IssuedApiKeyResponse.WARNING);
    }

    private static ApiException notFound(UUID id) {
        return new ApiException(HttpStatus.NOT_FOUND, "Unknown API credential " + id);
    }
}
