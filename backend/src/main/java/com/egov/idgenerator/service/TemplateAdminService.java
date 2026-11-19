package com.egov.idgenerator.service;

import com.egov.idgenerator.dto.CreateTemplateRequest;
import com.egov.idgenerator.dto.TemplateAdminDetailResponse;
import com.egov.idgenerator.dto.TemplateAdminResponse;
import com.egov.idgenerator.entity.TemplateDefinition;
import com.egov.idgenerator.exception.ApiException;
import com.egov.idgenerator.repository.TemplateRepository;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * View and create templates. There is intentionally no update or delete: a template has one immutable format, and a
 * different format means a new template id. PostgreSQL triggers reject changes and deletions even if this class
 * were bypassed. Counters are never touched, so creating a template cannot reset or alter any counter.
 */
@Service
public class TemplateAdminService {

    private final TemplateRepository repository;
    private final TemplateService templates;
    private final TemplateValidator validator;

    public TemplateAdminService(TemplateRepository repository, TemplateService templates, TemplateValidator validator) {
        this.repository = repository;
        this.templates = templates;
        this.validator = validator;
    }

    public List<TemplateAdminResponse> list() {
        return repository.findAll().stream().map(this::summary).toList();
    }

    public TemplateAdminDetailResponse get(String templateId) {
        TemplateDefinition d = repository.findById(templateId).orElseThrow(() -> ApiException.templateNotFound(templateId));
        return new TemplateAdminDetailResponse(d.templateId(), d.name(), d.format(), issuable(d), d.createdAt(),
                d.createdBy(), repository.countIssuedIds(templateId));
    }

    @Transactional
    public TemplateAdminResponse create(CreateTemplateRequest req, String actor) {
        TemplateValidator.Result r = validator.validate(req.templateId(), req.name(), req.format());
        if (!r.valid()) {
            throw ApiException.invalid("Invalid template format: " + String.join("; ", r.errors()));
        }
        try {
            return summary(repository.insert(req.templateId(), req.name(), req.format(), actor));
        } catch (DuplicateKeyException e) {
            String msg = String.valueOf(e.getMostSpecificCause().getMessage());
            throw msg.contains(TemplateRepository.FORMAT_CONSTRAINT)
                    ? ApiException.duplicateFormat() : ApiException.duplicateTemplate(req.templateId());
        }
    }

    private boolean issuable(TemplateDefinition d) {
        return validator.isIssuable(templates.parsed(d));
    }

    private TemplateAdminResponse summary(TemplateDefinition d) {
        return new TemplateAdminResponse(d.templateId(), d.name(), d.format(), issuable(d), d.createdAt(), d.createdBy());
    }
}
