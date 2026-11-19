package com.egov.idgenerator.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * Named-counter configuration from the "counters" section of templates.json, read at every startup (it is
 * configuration, not state). {@code start} is the FIRST number issued by a brand-new counter. It only matters when a
 * counter row is first created: existing counters are never reset or changed by (re)loading this file.
 */
public final class NamedCounterConfig {

    private static final Pattern NAME = Pattern.compile("^SEQ_[A-Z0-9_]+$");

    private final Map<String, Long> starts;
    private final boolean requireConfig;
    private final long defaultStart;

    public NamedCounterConfig(Map<String, Long> starts, boolean requireConfig, long defaultStart) {
        if (defaultStart < 1) {
            throw new IllegalStateException("idgen.named-counters.default-start must be >= 1");
        }
        this.starts = Map.copyOf(starts);
        this.requireConfig = requireConfig;
        this.defaultStart = defaultStart;
    }

    /** Fails fast on a malformed counters section. */
    public static NamedCounterConfig load(Path templatesJson, boolean requireConfig, long defaultStart) {
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(templatesJson.toFile());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + templatesJson, e);
        }
        Map<String, Long> starts = new LinkedHashMap<>();
        root.path("counters").fields().forEachRemaining(e -> {
            String name = e.getKey();
            JsonNode start = e.getValue().path("start");
            if (!NAME.matcher(name).matches()) {
                throw new IllegalStateException("Invalid named counter '" + name + "' in " + templatesJson);
            }
            if (!start.canConvertToLong() || !start.isIntegralNumber() || start.asLong() < 1) {
                throw new IllegalStateException("Named counter " + name + " needs an integer 'start' >= 1 in " + templatesJson);
            }
            starts.put(name, start.asLong());
        });
        return new NamedCounterConfig(starts, requireConfig, defaultStart);
    }

    public OptionalLong configuredStart(String counterName) {
        Long s = starts.get(counterName);
        return s == null ? OptionalLong.empty() : OptionalLong.of(s);
    }

    /** Start value, or empty if the counter is unconfigured and configuration is required. */
    public OptionalLong startFor(String counterName) {
        OptionalLong configured = configuredStart(counterName);
        if (configured.isPresent() || requireConfig) {
            return configured;
        }
        return OptionalLong.of(defaultStart);
    }

    public Map<String, Long> configured() {
        return starts;
    }
}
