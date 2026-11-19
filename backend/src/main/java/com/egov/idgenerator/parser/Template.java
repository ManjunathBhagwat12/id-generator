package com.egov.idgenerator.parser;

import com.egov.idgenerator.parser.Segment.Placeholder;
import java.util.List;

/** A template definition from templates.json together with its parsed format. */
public record Template(String id, String name, String format, List<Segment> segments) {

    public List<Placeholder> placeholders() {
        return segments.stream()
                .filter(Placeholder.class::isInstance)
                .map(Placeholder.class::cast)
                .toList();
    }

    /** The (single) SEQ placeholder, or null if the template has none. */
    public Placeholder sequence() {
        return placeholders().stream().filter(Placeholder::isSequence).findFirst().orElse(null);
    }

    /** Digits in the sequence number, e.g. 6 for [SEQ:6]. */
    public int sequenceWidth() {
        Placeholder seq = sequence();
        return seq == null ? 0 : Integer.parseInt(seq.arg());
    }
}
