package com.egov.idgenerator.parser;

import com.egov.idgenerator.parser.Segment.Literal;
import com.egov.idgenerator.parser.Segment.Placeholder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Parses a template format such as {@code PG-[CITY]-[FY:yyyy-yy]-[SEQ:6]} into segments.
 *
 * <p>Grammar (inferred from templates.json; BRIEF.md was not supplied):
 * <pre>
 *   placeholder := '[' NAME [ ':' ARG ] ']'      NAME := [A-Z][A-Z_]*
 * </pre>
 * The parser is deliberately generic: it understands the syntax of ALL placeholders (CY, SEQ_WS ...) but
 * knows nothing about their meaning. Meaning lives in the PlaceholderResolver beans, so new placeholder
 * types are added without touching the parser.
 */
@Component
public class TemplateParser {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\[([A-Z][A-Z_]*)(?::([^\\]\\[]*))?]");

    public List<Segment> parse(String format) {
        if (format == null || format.isBlank()) {
            throw new IllegalArgumentException("Template format must not be blank");
        }
        List<Segment> segments = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(format);
        int last = 0;
        while (m.find()) {
            addLiteral(segments, format.substring(last, m.start()), format);
            segments.add(placeholder(m.group(1), m.group(2), format));
            last = m.end();
        }
        addLiteral(segments, format.substring(last), format);
        return List.copyOf(segments);
    }

    private void addLiteral(List<Segment> segments, String text, String format) {
        if (text.isEmpty()) {
            return;
        }
        if (text.contains("[") || text.contains("]")) {
            throw new IllegalArgumentException("Malformed placeholder in template format: " + format);
        }
        segments.add(new Literal(text));
    }

    private Placeholder placeholder(String name, String arg, String format) {
        boolean seq = name.equals("SEQ") || name.startsWith("SEQ_");
        if (!seq) {
            return new Placeholder(name, name, null, arg);
        }
        // Fail fast on a SEQ without a usable width, e.g. [SEQ] or [SEQ:x].
        int width;
        try {
            width = Integer.parseInt(arg == null ? "" : arg);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("[" + name + "] needs a numeric width in template: " + format);
        }
        if (width < 1 || width > 18) {
            throw new IllegalArgumentException("[" + name + "] width must be 1..18 in template: " + format);
        }
        return new Placeholder(name, "SEQ", name.equals("SEQ") ? null : name, arg);
    }
}
