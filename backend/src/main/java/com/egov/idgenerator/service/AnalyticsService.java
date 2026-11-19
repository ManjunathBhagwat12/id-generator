package com.egov.idgenerator.service;

import com.egov.idgenerator.config.IdGenProperties;
import com.egov.idgenerator.dto.AnalyticsResponse;
import com.egov.idgenerator.exception.ApiException;
import com.egov.idgenerator.repository.AnalyticsRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds dashboard analytics. Days are calendar days in the business zone (Asia/Kolkata), the same zone used for
 * FY/CY in IDs, so "today" here matches "today" in issued identifiers. Read-only; touches no counters.
 */
@Service
public class AnalyticsService {

    private final AnalyticsRepository repository;
    private final Clock clock;
    private final ZoneId zone;

    public AnalyticsService(AnalyticsRepository repository, Clock clock, IdGenProperties props) {
        this.repository = repository;
        this.clock = clock;
        this.zone = ZoneId.of(props.businessZone());
    }

    public static int days(String period) {
        return switch (period == null ? "7d" : period.trim().toLowerCase()) {
            case "7d" -> 7;
            case "30d" -> 30;
            default -> throw ApiException.invalid("period must be 7d or 30d");
        };
    }

    /** One consistent snapshot: all three aggregates read in the same read-only transaction. */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public AnalyticsResponse analytics(String period) {
        int days = days(period);
        LocalDate to = LocalDate.now(clock.withZone(zone));
        LocalDate from = to.minusDays(days - 1L);
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
        long[] totals = repository.totals(start, end);
        return new AnalyticsResponse(days + "d", zone.getId(), from, to, totals[0], totals[1],
                repository.daily(from, to, zone, start, end), repository.byTemplate(start, end));
    }
}
