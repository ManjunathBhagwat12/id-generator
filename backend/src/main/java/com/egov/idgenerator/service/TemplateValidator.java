package com.egov.idgenerator.service;

import com.egov.idgenerator.parser.Segment;
import com.egov.idgenerator.parser.Segment.Literal;
import com.egov.idgenerator.parser.Segment.Placeholder;
import com.egov.idgenerator.parser.Template;
import com.egov.idgenerator.parser.TemplateParser;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Decides whether a format can be used to issue IDs. Pure: no database access.
 * A format without [CITY] is allowed: its counter is then shared by all tenants (see SequenceService).
 */
@Component
public class TemplateValidator {

    public static final int MAX_FORMAT_LENGTH = 200;
    private static final Pattern LITERAL = Pattern.compile("^[A-Za-z0-9/_.\\-]+$");

    /** @param parsed null when the format could not be parsed */
    public record Result(List<String> errors, Template parsed) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    private final TemplateParser parser;
    private final PlaceholderRegistry registry;

    public TemplateValidator(TemplateParser parser, PlaceholderRegistry registry) {
        this.parser = parser;
        this.registry = registry;
    }

    public Result validate(String templateId, String name, String format) {
        if (format == null || format.isBlank()) {
            return new Result(List.of("Format is required"), null);
        }
        if (format.length() > MAX_FORMAT_LENGTH) {
            return new Result(List.of("Format must be at most " + MAX_FORMAT_LENGTH + " characters"), null);
        }
        List<Segment> segments;
        try {
            segments = parser.parse(format);
        } catch (IllegalArgumentException e) {
            return new Result(List.of(e.getMessage()), null);
        }
        Template template = new Template(templateId, name, format, segments);
        List<String> errors = new ArrayList<>();

        for (Segment s : segments) {
            if (s instanceof Literal l && !LITERAL.matcher(l.text()).matches()) {
                errors.add("Literal text \"" + l.text() + "\" may contain only letters, digits and / _ . -");
            }
        }
        for (Placeholder p : template.placeholders()) {
            String problem = registry.problem(p);
            if (problem != null) {
                errors.add(problem);
            }
        }
        if (template.placeholders().stream().filter(Placeholder::isSequence).count() != 1) {
            errors.add("Format must contain exactly one sequence placeholder ([SEQ:n] or [SEQ_NAME:n])");
        }
        return new Result(errors, template);
    }

    public boolean isIssuable(Template template) {
        return validate(template.id(), template.name(), template.format()).valid();
    }
}
