package com.egov.idgenerator.service;

import com.egov.idgenerator.config.IdGenProperties;
import com.egov.idgenerator.repository.TemplateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seeds templates.json into an EMPTY templates table (never overwrites stored templates). Which seeded templates can
 * actually issue IDs is decided by the validator, not by configuration: today only PG_RECEIPT qualifies.
 */
@Component
public class TemplateSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TemplateSeeder.class);
    private static final String SYSTEM = "system (seed)";

    private final IdGenProperties props;
    private final TemplateRepository repository;
    private final TemplateValidator validator;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public TemplateSeeder(IdGenProperties props, TemplateRepository repository, TemplateValidator validator,
                          ObjectMapper mapper, TransactionTemplate tx) {
        this.props = props;
        this.repository = repository;
        this.validator = validator;
        this.mapper = mapper;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (repository.count() == 0) {
            JsonNode root = mapper.readTree(Path.of(props.templatesPath()).toFile());
            tx.executeWithoutResult(s -> {
                if (repository.count() > 0) {
                    return;
                }
                for (JsonNode t : root.path("templates")) {
                    String id = t.path("id").asText();
                    String name = t.path("name").asText();
                    String format = t.path("format").asText();
                    if (validator.validate(id, name, format).parsed() == null) {
                        throw new IllegalStateException("Malformed template format for " + id + ": " + format);
                    }
                    repository.insert(id, name, format, SYSTEM);
                }
            });
            log.info("Seeded templates from {}", props.templatesPath());
        }
        repository.validateReferences();
    }
}
