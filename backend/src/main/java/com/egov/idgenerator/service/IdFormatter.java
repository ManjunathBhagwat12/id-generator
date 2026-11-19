package com.egov.idgenerator.service;

import com.egov.idgenerator.parser.Segment;
import com.egov.idgenerator.parser.Segment.Literal;
import com.egov.idgenerator.parser.Segment.Placeholder;
import com.egov.idgenerator.parser.Template;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Pure rendering of a template into an ID string. No I/O, fully unit-testable. */
@Component
public class IdFormatter {

    /** Zero-pads to {@code width} digits: pad(431, 6) -> "000431". */
    public String padSequence(long value, int width) {
        String digits = Long.toString(value);
        if (digits.length() > width) {
            throw new IllegalArgumentException("Sequence " + value + " does not fit in " + width + " digits");
        }
        return "0".repeat(width - digits.length()) + digits;
    }

    /**
     * @param staticValues pre-resolved values of the non-SEQ placeholders (same for all IDs of a request)
     * @param sequence     sequence number for this ID
     */
    public String format(Template template, Map<Placeholder, String> staticValues, long sequence) {
        StringBuilder sb = new StringBuilder();
        for (Segment s : template.segments()) {
            switch (s) {
                case Literal l -> sb.append(l.text());
                case Placeholder p when p.isSequence() ->
                        sb.append(padSequence(sequence, Integer.parseInt(p.arg())));
                case Placeholder p -> {
                    String v = staticValues.get(p);
                    if (v == null) {
                        throw new IllegalStateException("No value resolved for placeholder " + p.name());
                    }
                    sb.append(v);
                }
            }
        }
        return sb.toString();
    }
}
