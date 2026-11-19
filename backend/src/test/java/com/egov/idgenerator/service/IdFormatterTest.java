package com.egov.idgenerator.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.egov.idgenerator.parser.Segment.Placeholder;
import com.egov.idgenerator.parser.Template;
import com.egov.idgenerator.parser.TemplateParser;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class IdFormatterTest {

    private final IdFormatter formatter = new IdFormatter();
    private final TemplateParser parser = new TemplateParser();

    @ParameterizedTest
    @CsvSource({"1,6,000001", "2,6,000002", "431,6,000431", "999999,6,999999", "7,3,007", "1,5,00001", "1,8,00000001", "1000,4,1000"})
    void zeroPadsSequence(long value, int width, String expected) {
        assertEquals(expected, formatter.padSequence(value, width));
    }

    @Test
    void overflowingSequenceRejected() {
        assertThrows(IllegalArgumentException.class, () -> formatter.padSequence(1_000_000, 6));
    }

    @Test
    void formatsPgReceipt() {
        String format = "PG-[CITY]-[FY:yyyy-yy]-[SEQ:6]";
        Template t = new Template("PG_RECEIPT", "Payment receipt number", format, parser.parse(format));
        Map<Placeholder, String> values = Map.of(
                new Placeholder("CITY", "CITY", null, null), "BLR",
                new Placeholder("FY", "FY", null, "yyyy-yy"), "2026-27");

        assertEquals("PG-BLR-2026-27-000001", formatter.format(t, values, 1));
        assertEquals("PG-BLR-2026-27-000431", formatter.format(t, values, 431));
    }

    @Test
    void missingStaticValueIsAnError() {
        String format = "PG-[CITY]-[SEQ:6]";
        Template t = new Template("X", "x", format, parser.parse(format));
        assertThrows(IllegalStateException.class, () -> formatter.format(t, Map.of(), 1));
    }
}
