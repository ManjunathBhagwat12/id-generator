package com.egov.idgenerator.service;

import java.time.LocalDate;
import java.time.Month;

/**
 * Pure Indian financial-year calculation (1 April .. 31 March). No Spring, no clock, no time zone:
 * the caller supplies the business-local date, which makes this trivially unit-testable.
 */
public class FinancialYearResolver {

    /** The calendar year in which the financial year containing {@code date} STARTS. */
    public int startYear(LocalDate date) {
        return date.getMonth().getValue() >= Month.APRIL.getValue() ? date.getYear() : date.getYear() - 1;
    }

    public boolean supports(String pattern) {
        return "yyyy-yy".equals(pattern) || "yy-yy".equals(pattern);
    }

    /**
     * @param pattern "yyyy-yy" (2026-27) or "yy-yy" (26-27)
     */
    public String format(LocalDate date, String pattern) {
        int start = startYear(date);
        int end = start + 1;
        return switch (pattern) {
            case "yyyy-yy" -> start + "-" + twoDigits(end);
            case "yy-yy" -> twoDigits(start) + "-" + twoDigits(end);
            default -> throw new IllegalArgumentException("Unsupported FY pattern: " + pattern);
        };
    }

    private static String twoDigits(int year) {
        return String.format("%02d", year % 100);
    }
}
