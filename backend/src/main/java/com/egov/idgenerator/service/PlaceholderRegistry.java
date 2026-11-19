package com.egov.idgenerator.service;

import com.egov.idgenerator.config.NamedCounterConfig;
import com.egov.idgenerator.entity.City;
import com.egov.idgenerator.parser.Segment.Placeholder;
import com.egov.idgenerator.parser.Template;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Lookup of PlaceholderResolver beans: which placeholders can be issued, and their per-request values. */
@Component
public class PlaceholderRegistry {

    private final Map<String, PlaceholderResolver> resolvers;
    private final NamedCounterConfig namedCounters;

    public PlaceholderRegistry(List<PlaceholderResolver> beans, NamedCounterConfig namedCounters) {
        this.resolvers = beans.stream().collect(Collectors.toMap(PlaceholderResolver::kind, Function.identity()));
        this.namedCounters = namedCounters;
    }

    /** Why the placeholder cannot be issued, or null if it can. */
    public String problem(Placeholder p) {
        if (p.isSequence()) {
            if (p.namedCounter() == null) {
                return null;
            }
            OptionalLong start = namedCounters.startFor(p.namedCounter());
            if (start.isEmpty()) {
                return "Named counter [" + p.namedCounter() + "] has no configuration in the counters section of templates.json";
            }
            int width = Integer.parseInt(p.arg());
            if (start.getAsLong() > SequenceService.maxValue(width)) {
                return "Named counter [" + p.namedCounter() + "] starts at " + start.getAsLong() + ", which does not fit in "
                        + width + " digits";
            }
            return null;
        }
        PlaceholderResolver r = resolvers.get(p.kind());
        if (r == null) {
            return "Placeholder [" + p.name() + (p.arg() == null ? "" : ":" + p.arg()) + "] is not supported";
        }
        return r.problem(p);
    }

    public boolean supports(Placeholder p) {
        return problem(p) == null;
    }

    /** Values of the non-SEQ placeholders; identical for every ID of a request, so resolved once. */
    public Map<Placeholder, String> resolveStatics(Template template, City city, LocalDate businessDate) {
        ResolutionContext ctx = new ResolutionContext(city, businessDate);
        Map<Placeholder, String> values = new HashMap<>();
        for (Placeholder p : template.placeholders()) {
            if (!p.isSequence()) {
                values.put(p, resolvers.get(p.kind()).resolve(p, ctx));
            }
        }
        return values;
    }
}
