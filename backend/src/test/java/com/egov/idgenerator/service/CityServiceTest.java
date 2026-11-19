package com.egov.idgenerator.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.egov.idgenerator.entity.City;
import com.egov.idgenerator.exception.ApiException;
import com.egov.idgenerator.repository.CityRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Resolution rules with a stubbed repository; real SQL behaviour is covered by the integration tests. */
class CityServiceTest {

    private final City blr = new City("ka.bengaluru", "BLR", "Bengaluru", true, Instant.EPOCH, Instant.EPOCH);
    private CityRepository repo;
    private CityService service;

    @BeforeEach
    void setUp() {
        repo = mock(CityRepository.class);
        service = new CityService(repo);
    }

    @Test
    void suppliedCsvStillHasTenCities() {
        assertEquals(10, CityCsvParser.parse(Path.of("../data/cities.csv")).size());
    }

    @Test
    void resolvesByCityCodeAndTenantIdToTheSameRecord() {
        when(repo.findByIdentifier("BLR")).thenReturn(Optional.of(blr));
        when(repo.findByIdentifier("ka.bengaluru")).thenReturn(Optional.of(blr));
        assertEquals(service.resolve("BLR"), service.resolve("ka.bengaluru"));
    }

    @Test
    void unknownCityIs404() {
        when(repo.findByIdentifier("XXX")).thenReturn(Optional.empty());
        ApiException e = assertThrows(ApiException.class, () -> service.resolve("XXX"));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
    }

    @Test
    void blankOrNullIs404() {
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () -> service.resolve(" ")).getStatus());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () -> service.resolve(null)).getStatus());
    }

    @Test
    void inactiveTenantIsRejectedWith422() {
        City off = new City("ka.bengaluru", "BLR", "Bengaluru", false, Instant.EPOCH, Instant.EPOCH);
        when(repo.findByIdentifier("BLR")).thenReturn(Optional.of(off));
        ApiException e = assertThrows(ApiException.class, () -> service.resolve("BLR"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, e.getStatus());
    }
}
