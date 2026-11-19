package com.egov.idgenerator.service;

import com.egov.idgenerator.entity.TemplateDefinition;
import com.egov.idgenerator.exception.ApiException;
import com.egov.idgenerator.parser.Template;
import com.egov.idgenerator.parser.TemplateParser;
import com.egov.idgenerator.repository.TemplateRepository;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Runtime template lookup for ID generation. Templates are stored in PostgreSQL and never change, so the parsed form
 * can be cached by template id for the life of the process.
 */
@Service
public class TemplateService {

    /** Public listing row. */
    public record PublicTemplate(String id, String name, String format, boolean enabled, String counterScope) {
    }

    private final TemplateRepository repository;
    private final TemplateParser parser;
    private final TemplateValidator validator;
    private final Map<String, Template> parsedCache = new ConcurrentHashMap<>();

    public TemplateService(TemplateRepository repository, TemplateParser parser, TemplateValidator validator) {
        this.repository = repository;
        this.parser = parser;
        this.validator = validator;
    }

    /** @throws ApiException 404 for an unknown template, 422 if its format cannot be issued yet */
    public Template getForIssuance(String templateId) {
        TemplateDefinition def = (templateId == null ? java.util.Optional.<TemplateDefinition>empty() : repository.findById(templateId))
                .orElseThrow(() -> ApiException.templateNotFound(templateId));
        Template t = parsed(def);
        TemplateValidator.Result r = validator.validate(t.id(), t.name(), t.format());
        if (!r.valid()) {
            throw ApiException.templateNotEnabled(templateId, r.errors());
        }
        return t;
    }

    public List<PublicTemplate> listPublic() {
        return repository.findAll().stream()
                .map(d -> {
                    Template t = parsed(d);
                    return new PublicTemplate(d.templateId(), d.name(), d.format(), validator.isIssuable(t),
                            SequenceService.describe(t));
                })
                .toList();
    }

    public Template parsed(TemplateDefinition d) {
        return parsedCache.computeIfAbsent(d.templateId(), k -> new Template(d.templateId(), d.name(), d.format(), parser.parse(d.format())));
    }
}
