package com.egov.idgenerator.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NamedCounterConfigTest {

    private static final Path SUPPLIED = Path.of("../data/templates.json");

    @Test
    void readsStartValuesFromTheSuppliedFile() {
        NamedCounterConfig c = NamedCounterConfig.load(SUPPLIED, false, 1);
        assertEquals(1000, c.configuredStart("SEQ_WS").getAsLong());
        assertEquals(1, c.configuredStart("SEQ_BPA_PERMIT").getAsLong());
        assertTrue(c.configuredStart("SEQ_EST_ALLOT").isEmpty(), "SEQ_EST_ALLOT is not configured in templates.json");
    }

    @Test
    void unconfiguredCounterUsesDefaultUnlessConfigIsRequired() {
        assertEquals(1, NamedCounterConfig.load(SUPPLIED, false, 1).startFor("SEQ_EST_ALLOT").getAsLong());
        assertTrue(NamedCounterConfig.load(SUPPLIED, true, 1).startFor("SEQ_EST_ALLOT").isEmpty());
        assertEquals(1000, NamedCounterConfig.load(SUPPLIED, true, 1).startFor("SEQ_WS").getAsLong());
    }

    @Test
    void malformedConfigurationFailsFast(@TempDir Path dir) throws Exception {
        for (String bad : new String[] {
                "{\"counters\":{\"SEQ_X\":{\"start\":0}}}",
                "{\"counters\":{\"SEQ_X\":{\"start\":\"10\"}}}",
                "{\"counters\":{\"SEQ_X\":{\"start\":1.5}}}",
                "{\"counters\":{\"SEQ_X\":{}}}",
                "{\"counters\":{\"bad name\":{\"start\":1}}}"}) {
            Path f = dir.resolve("t.json");
            Files.writeString(f, bad);
            assertThrows(IllegalStateException.class, () -> NamedCounterConfig.load(f, false, 1), bad);
        }
    }
}
