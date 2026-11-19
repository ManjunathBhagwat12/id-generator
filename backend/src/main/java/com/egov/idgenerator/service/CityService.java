package com.egov.idgenerator.service;

import com.egov.idgenerator.entity.City;
import com.egov.idgenerator.exception.ApiException;
import com.egov.idgenerator.repository.CityRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Tenant lookup for ID generation. Reads PostgreSQL on every call (two indexed lookups), so tenants created,
 * renamed or deactivated through the admin UI take effect immediately, with no cache to invalidate.
 */
@Service
public class CityService {

    private final CityRepository repository;

    public CityService(CityRepository repository) {
        this.repository = repository;
    }

    /** Active tenants only (public dropdown). */
    public List<City> findAllActive() {
        return repository.search(null, true);
    }

    /**
     * Resolves a tenant_id or city_code (case-insensitive) to an ACTIVE tenant.
     *
     * @throws ApiException 404 if unknown, 422 if the tenant is deactivated
     */
    public City resolve(String tenantIdOrCityCode) {
        if (tenantIdOrCityCode == null || tenantIdOrCityCode.isBlank()) {
            throw ApiException.cityNotFound(tenantIdOrCityCode);
        }
        City city = repository.findByIdentifier(tenantIdOrCityCode)
                .orElseThrow(() -> ApiException.cityNotFound(tenantIdOrCityCode));
        if (!city.active()) {
            throw ApiException.tenantInactive(city.tenantId());
        }
        return city;
    }
}
