package com.egov.idgenerator.service;

import com.egov.idgenerator.config.NamedCounterConfig;
import com.egov.idgenerator.entity.City;
import com.egov.idgenerator.exception.ApiException;
import com.egov.idgenerator.parser.Segment.Placeholder;
import com.egov.idgenerator.parser.Template;
import com.egov.idgenerator.repository.CounterRepository;
import org.springframework.stereotype.Service;

/**
 * Chooses the counter for a template and reserves a contiguous range from it. Deliberately NOT @Transactional: the
 * atomic reservation commits in its own transaction inside CounterRepository (see the concurrency notes there).
 *
 * <h3>Counter scope</h3>
 * <ul>
 *   <li>[SEQ:n] - implicit counter, key = (template_id, tenant), starts at 1.</li>
 *   <li>[SEQ_NAME:n] - named counter, key = (counter name, tenant), shared by every template using that name;
 *       starts at the configured {@code start} (templates.json) or the configured default.</li>
 *   <li>tenant = the requesting tenant if the format contains [CITY]; otherwise {@value #GLOBAL_SCOPE} (one counter
 *       shared by all tenants). Reason: identifier strings must be globally unique, and a format without the city code
 *       (PGR-[SEQ:8]) would otherwise give every tenant the same strings.</li>
 * </ul>
 */
@Service
public class SequenceService {

    /** First number of a brand-new implicit [SEQ:n] counter. */
    public static final long INITIAL_VALUE = 1;
    /** Tenant key of counters shared by all tenants (formats without [CITY]). */
    public static final String GLOBAL_SCOPE = "*";

    /** Which counter a template draws from for a given tenant. */
    public record Scope(String counter, boolean named, String tenant) {
    }

    private final CounterRepository counters;
    private final NamedCounterConfig namedCounters;

    public SequenceService(CounterRepository counters, NamedCounterConfig namedCounters) {
        this.counters = counters;
        this.namedCounters = namedCounters;
    }

    public static boolean perTenant(Template t) {
        return t.placeholders().stream().anyMatch(p -> p.kind().equals("CITY"));
    }

    public static Scope scope(Template t, String tenantId) {
        Placeholder seq = t.sequence();
        String tenant = perTenant(t) ? tenantId : GLOBAL_SCOPE;
        return seq.namedCounter() == null ? new Scope(t.id(), false, tenant) : new Scope(seq.namedCounter(), true, tenant);
    }

    /** Human-readable scope, e.g. "SEQ_WS per city (shared with other templates using SEQ_WS)". */
    public static String describe(Template t) {
        Placeholder seq = t.sequence();
        if (seq == null) {
            return "none";
        }
        String who = perTenant(t) ? "per city" : "shared by all cities (format has no [CITY])";
        return seq.namedCounter() == null ? "own counter, " + who
                : "named counter " + seq.namedCounter() + ", " + who + ", shared by templates using it";
    }

    /** @return first number of the reserved range {@code [start, start + count - 1]} */
    public long allocate(Template template, City city, int count) {
        Scope s = scope(template, city.tenantId());
        long start;
        if (s.named()) {
            long initial = namedCounters.startFor(s.counter())
                    .orElseThrow(() -> ApiException.templateNotEnabled(template.id(),
                            java.util.List.of("Named counter [" + s.counter() + "] has no configuration")));
            start = counters.allocateNamed(s.counter(), s.tenant(), initial, count);
        } else {
            start = counters.allocate(s.counter(), s.tenant(), INITIAL_VALUE, count);
        }
        if (start + count - 1 > maxValue(template.sequenceWidth())) {
            // The counter has already advanced; that only produces a gap, and every later call fails the same way.
            throw ApiException.sequenceExhausted(s.counter(), s.tenant());
        }
        return start;
    }

    static long maxValue(int width) {
        long max = 1;
        for (int i = 0; i < width; i++) {
            max *= 10;
        }
        return max - 1;
    }
}
