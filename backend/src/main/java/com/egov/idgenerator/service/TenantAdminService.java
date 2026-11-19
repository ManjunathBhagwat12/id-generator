package com.egov.idgenerator.service;

import com.egov.idgenerator.dto.CreateTenantRequest;
import com.egov.idgenerator.dto.TenantDetailResponse;
import com.egov.idgenerator.dto.TenantSummaryResponse;
import com.egov.idgenerator.dto.UpdateTenantRequest;
import com.egov.idgenerator.entity.City;
import com.egov.idgenerator.exception.ApiException;
import com.egov.idgenerator.repository.CityRepository;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant management. Uniqueness is enforced by PostgreSQL constraints (so concurrent creates cannot both
 * succeed); the violated constraint name is mapped to a clear 409. There is deliberately no delete operation.
 */
@Service
public class TenantAdminService {

    private final CityRepository repository;

    public TenantAdminService(CityRepository repository) {
        this.repository = repository;
    }

    public List<City> list(String search, Boolean active) {
        return repository.search(search, active);
    }

    public TenantSummaryResponse summary() {
        CityRepository.Summary s = repository.summary();
        return new TenantSummaryResponse(s.total(), s.active(), s.total() - s.active());
    }

    public TenantDetailResponse detail(String tenantId) {
        City c = repository.findByTenantId(tenantId).orElseThrow(() -> ApiException.tenantNotFound(tenantId));
        long issued = repository.countIssuedIds(tenantId);
        return new TenantDetailResponse(c.tenantId(), c.cityCode(), c.cityName(), c.active(),
                c.createdAt(), c.updatedAt(), issued, issued > 0);
    }

    @Transactional
    public City create(CreateTenantRequest r) {
        try {
            return repository.insert(r.tenantId(), r.cityCode(), r.cityName());
        } catch (DuplicateKeyException e) {
            throw duplicate(e);
        }
    }

    @Transactional
    public City update(String tenantId, UpdateTenantRequest r) {
        City current = repository.findByTenantIdForUpdate(tenantId)   // row lock: serialises concurrent edits
                .orElseThrow(() -> ApiException.tenantNotFound(tenantId));
        // A code that already appears in issued IDs must stay reserved for this tenant; otherwise another
        // tenant could later take the freed code and collide with historical IDs.
        if (!current.cityCode().equals(r.cityCode()) && repository.hasIssuedIds(tenantId)) {
            throw ApiException.cityCodeLocked(tenantId);
        }
        try {
            return repository.update(tenantId, r.cityCode(), r.cityName())
                    .orElseThrow(() -> ApiException.tenantNotFound(tenantId));
        } catch (DuplicateKeyException e) {
            throw duplicate(e);
        }
    }

    @Transactional
    public City setActive(String tenantId, boolean active) {
        return repository.setActive(tenantId, active).orElseThrow(() -> ApiException.tenantNotFound(tenantId));
    }

    private ApiException duplicate(DuplicateKeyException e) {
        String msg = String.valueOf(e.getMostSpecificCause().getMessage());
        if (msg.contains(CityRepository.CITY_CODE_CONSTRAINT)) {
            return ApiException.duplicate("city code");
        }
        if (msg.contains(CityRepository.TENANT_ID_CONSTRAINT)) {
            return ApiException.duplicate("tenant ID");
        }
        return ApiException.duplicate("tenant ID or city code");
    }
}
