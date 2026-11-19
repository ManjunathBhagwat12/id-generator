package com.egov.idgenerator.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.egov.idgenerator.entity.City;
import com.egov.idgenerator.parser.Segment.Placeholder;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FinancialYearResolverTest {

    private final FinancialYearResolver fy = new FinancialYearResolver();

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "2026-04-01, 2026-27",   // first day of FY
            "2026-11-18, 2026-27",
            "2027-03-31, 2026-27",   // last day of FY
            "2026-03-31, 2025-26",   // day before FY starts
            "2026-01-01, 2025-26",
            "2027-04-01, 2027-28",
            "2027-02-15, 2026-27",   // brief example
            "2026-03-31, 2025-26",
            "2099-04-01, 2099-00",   // century rollover keeps two digits
            "2100-03-31, 2099-00"
    })
    void formatsIndianFinancialYear(String date, String expected) {
        assertEquals(expected, fy.format(LocalDate.parse(date), "yyyy-yy"));
    }

    @ParameterizedTest(name = "yy-yy {0} -> {1}")
    @CsvSource({"2026-11-18, 26-27", "2027-02-15, 26-27", "2027-03-31, 26-27", "2027-04-01, 27-28", "2026-03-31, 25-26",
            "2099-06-01, 99-00"})
    void shortPattern(String date, String expected) {
        assertEquals(expected, fy.format(LocalDate.parse(date), "yy-yy"));
    }

    @Test
    void unsupportedPatternRejected() {
        assertThrows(IllegalArgumentException.class, () -> fy.format(LocalDate.parse("2026-04-01"), "yyyy"));
    }

    /** 2026-03-31T19:00Z is already 1 April 00:30 in Kolkata, so it belongs to FY 2026-27, not 2025-26. */
    @Test
    void usesAsiaKolkataBusinessDate() {
        Instant instant = Instant.parse("2026-03-31T19:00:00Z");
        Clock kolkata = Clock.fixed(instant, ZoneId.of("Asia/Kolkata"));
        Clock utc = Clock.fixed(instant, ZoneId.of("UTC"));
        FyPlaceholderResolver resolver = new FyPlaceholderResolver();
        Placeholder p = new Placeholder("FY", "FY", null, "yyyy-yy");
        City city = new City("ka.bengaluru", "BLR", "Bengaluru");

        assertEquals("2026-27", resolver.resolve(p, new ResolutionContext(city, LocalDate.now(kolkata))));
        assertEquals("2025-26", resolver.resolve(p, new ResolutionContext(city, LocalDate.now(utc))));
    }
}
