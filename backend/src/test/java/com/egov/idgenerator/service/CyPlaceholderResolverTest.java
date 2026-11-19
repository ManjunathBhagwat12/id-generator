package com.egov.idgenerator.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.egov.idgenerator.entity.City;
import com.egov.idgenerator.parser.Segment.Placeholder;
import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CyPlaceholderResolverTest {

    private final CyPlaceholderResolver cy = new CyPlaceholderResolver();
    private final City blr = new City("ka.bengaluru", "BLR", "Bengaluru");

    private static Placeholder p(String arg) {
        return new Placeholder("CY", "CY", null, arg);
    }

    @ParameterizedTest(name = "[CY:{1}] on {0} -> {2}")
    @CsvSource({
            "2026-11-18, yyyy, 2026",
            "2026-11-18, MM-yyyy, 11-2026",
            "2027-02-15, MM-yyyy, 02-2027",
            "2027-01-01, yyyy, 2027",       // CY changes on 1 Jan (unlike FY)
            "2026-12-31, yyyy, 2026",
            "2026-11-05, dd/MM/yy, 05/11/26",
            "2026-11-05, yyyyMMdd, 20261105"
    })
    void formatsCalendarDate(String date, String pattern, String expected) {
        assertNull(cy.problem(p(pattern)));
        assertEquals(expected, cy.resolve(p(pattern), new ResolutionContext(blr, LocalDate.parse(date))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"qq", "MMMM", "MMM", "HH", "yyyy HH:mm", "EEE", "yyyy'x'", "", "y-", "y", "yyy", "yyyyy", "ddd", "yyyy-", "-yyyy", "MM--yyyy"})
    void rejectsInvalidOrNonNumericPatterns(String pattern) {
        assertNotNull(cy.problem(p(pattern)), pattern);
    }

    @org.junit.jupiter.api.Test
    void rejectsMissingPattern() {
        assertNotNull(cy.problem(p(null)));
    }
}
