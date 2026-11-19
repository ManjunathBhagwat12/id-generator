package com.egov.idgenerator.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.egov.idgenerator.config.NamedCounterConfig;
import com.egov.idgenerator.parser.TemplateParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** All six SUPPLIED formats (read from data/templates.json, not duplicated here) validate, under both policies. */
class TemplateValidatorTest {

    private static TemplateValidator validator(boolean requireConfig) {
        NamedCounterConfig cfg = NamedCounterConfig.load(Path.of("../data/templates.json"), requireConfig, 1);
        PlaceholderRegistry registry = new PlaceholderRegistry(
                List.of(new CityPlaceholderResolver(), new FyPlaceholderResolver(), new CyPlaceholderResolver()), cfg);
        return new TemplateValidator(new TemplateParser(), registry);
    }

    @Test
    void allSixSuppliedTemplatesAreValid() throws Exception {
        TemplateValidator v = validator(false);
        JsonNode root = new ObjectMapper().readTree(Path.of("../data/templates.json").toFile());
        int n = 0;
        for (JsonNode t : root.path("templates")) {
            TemplateValidator.Result r = v.validate(t.path("id").asText(), t.path("name").asText(), t.path("format").asText());
            assertTrue(r.valid(), t.path("id").asText() + " " + r.errors());
            n++;
        }
        assertEquals(6, n);
    }

    @Test
    void missingNamedCounterConfigIsRejectedWhenConfigIsRequired() {
        TemplateValidator.Result r = validator(true).validate("EST_ALLOTMENT", "Estate", "EST-[CITY]-[SEQ_EST_ALLOT:5]");
        assertEquals(List.of("Named counter [SEQ_EST_ALLOT] has no configuration in the counters section of templates.json"),
                r.errors());
        assertTrue(validator(true).validate("WS_CONNECTION", "WS", "WS-[CITY]-[SEQ_WS:4]-[CY:MM-yyyy]").valid());
    }

    @Test
    void sequenceCountAndCityAreChecked() {
        assertTrue(validator(false).validate("X", "x", "PGR-[SEQ:8]").valid(), "city-less formats are allowed");
        assertEquals(1, validator(false).validate("X", "x", "A-[CITY]").errors().size());
    }
}
