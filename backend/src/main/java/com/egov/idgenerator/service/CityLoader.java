package com.egov.idgenerator.service;

import com.egov.idgenerator.config.IdGenProperties;
import com.egov.idgenerator.entity.City;
import com.egov.idgenerator.repository.CityRepository;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds cities.csv into an EMPTY cities table, once. If any tenant already exists (e.g. created or edited
 * through the admin UI) the file is not even read, so managed data is never overwritten or reset.
 */
@Component
public class CityLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CityLoader.class);

    private final IdGenProperties props;
    private final CityRepository repository;

    public CityLoader(IdGenProperties props, CityRepository repository) {
        this.props = props;
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (repository.count() > 0) {
            log.info("Tenant table already populated; skipping {} seed", props.citiesPath());
            return;
        }
        List<City> cities = CityCsvParser.parse(Path.of(props.citiesPath()));
        boolean seeded = repository.seedIfEmpty(cities);
        log.info(seeded ? "Seeded {} tenants from {}" : "Another instance seeded the tenants first ({} rows in file)",
                cities.size(), props.citiesPath());
    }
}
