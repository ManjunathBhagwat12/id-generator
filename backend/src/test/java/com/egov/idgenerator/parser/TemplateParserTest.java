package com.egov.idgenerator.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.egov.idgenerator.parser.Segment.Literal;
import com.egov.idgenerator.parser.Segment.Placeholder;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TemplateParserTest {

    private final TemplateParser parser = new TemplateParser();

    @Test
    void parsesPgReceipt() {
        assertEquals(List.of(
                new Literal("PG-"),
                new Placeholder("CITY", "CITY", null, null),
                new Literal("-"),
                new Placeholder("FY", "FY", null, "yyyy-yy"),
                new Literal("-"),
                new Placeholder("SEQ", "SEQ", null, "6")),
                parser.parse("PG-[CITY]-[FY:yyyy-yy]-[SEQ:6]"));
    }

    @Test
    void parsesNamedCounterAndFutureTypes() {
        List<Segment> s = parser.parse("WS-[CITY]-[SEQ_WS:4]-[CY:MM-yyyy]");
        assertEquals(new Placeholder("SEQ_WS", "SEQ", "SEQ_WS", "4"), s.get(3));
        assertEquals(new Placeholder("CY", "CY", null, "MM-yyyy"), s.get(5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "PG-[SEQ]", "PG-[SEQ:x]", "PG-[SEQ:0]", "PG-[CITY", "PG-CITY]-[SEQ:6]", "PG-[city]"})
    void rejectsMalformedFormats(String format) {
        assertThrows(IllegalArgumentException.class, () -> parser.parse(format));
    }
}
