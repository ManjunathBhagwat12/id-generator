package com.egov.idgenerator.controller;

import com.egov.idgenerator.dto.TemplateResponse;
import com.egov.idgenerator.service.TemplateService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/templates")
public class TemplateController {

    private final TemplateService templates;

    public TemplateController(TemplateService templates) {
        this.templates = templates;
    }

    @GetMapping
    public List<TemplateResponse> list() {
        return templates.listPublic().stream()
                .map(t -> new TemplateResponse(t.id(), t.name(), t.format(), t.enabled(), t.counterScope()))
                .toList();
    }
}
