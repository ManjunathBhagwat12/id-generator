package com.egov.idgenerator.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Issuance analytics for the admin dashboard. Every number is aggregated from issued_ids; nothing is estimated.
 *
 * @param period         "7d" or "30d"
 * @param timezone       zone used to assign IDs to calendar days (the business zone, Asia/Kolkata)
 * @param from           first day of the period (inclusive)
 * @param to             last day of the period (inclusive, today)
 * @param totalIssued    all IDs ever issued
 * @param issuedInPeriod IDs issued between from and to
 * @param daily          one entry per day of the period, oldest first, zero-filled
 * @param byTemplate     every template with its count in the period, highest first
 */
public record AnalyticsResponse(String period, String timezone, LocalDate from, LocalDate to,
                                long totalIssued, long issuedInPeriod,
                                List<Day> daily, List<TemplateCount> byTemplate) {

    public record Day(LocalDate date, long count) {
    }

    public record TemplateCount(String templateId, String name, long count) {
    }
}
