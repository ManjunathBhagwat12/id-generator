package com.egov.idgenerator.service;

import com.egov.idgenerator.config.IdGenProperties;
import com.egov.idgenerator.entity.City;
import com.egov.idgenerator.entity.IssuedId;
import com.egov.idgenerator.exception.ApiException;
import com.egov.idgenerator.parser.Segment.Placeholder;
import com.egov.idgenerator.parser.Template;
import com.egov.idgenerator.repository.IssuedIdRepository;
import com.egov.idgenerator.security.ApiClient;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Orchestrates one issuance request:
 * validate -> resolve city -> resolve static placeholders once -> atomically reserve a sequence range
 * -> render IDs -> persist them -> return.
 *
 * Intentionally not @Transactional (see CounterRepository): reservation commits first and independently;
 * persisting the issued IDs is a separate all-or-nothing transaction. A failure in between loses the
 * reserved numbers (a gap) but can never produce a duplicate.
 */
@Service
public class IdGenerationService {

    private final TemplateService templates;
    private final CityService cities;
    private final SequenceService sequences;
    private final IdFormatter formatter;
    private final IssuedIdRepository issuedIds;
    private final Clock clock;
    private final IdGenProperties props;
    private final PlaceholderRegistry registry;
    private final ApiCredentialService apiCredentials;

    public IdGenerationService(TemplateService templates, CityService cities, SequenceService sequences,
                               IdFormatter formatter, IssuedIdRepository issuedIds, Clock clock,
                               IdGenProperties props, PlaceholderRegistry registry,
                               ApiCredentialService apiCredentials) {
        this.templates = templates;
        this.cities = cities;
        this.sequences = sequences;
        this.formatter = formatter;
        this.issuedIds = issuedIds;
        this.clock = clock;
        this.props = props;
        this.registry = registry;
        this.apiCredentials = apiCredentials;
    }

    public List<String> issue(String templateId, String cityInput, int count) {
        return issue(templateId, cityInput, count, null);
    }

    /**
     * @param client the external application when the request was authenticated with an API key, else null. Its
     *               template and tenant restrictions are checked BEFORE any counter is touched (403, nothing consumed).
     */
    public List<String> issue(String templateId, String cityInput, int count, ApiClient client) {
        if (client != null && !client.mayUseTemplate(templateId)) {
            // Checked before the lookup, so a key cannot probe which templates exist.
            throw ApiException.forbidden("This API credential is not permitted to use template " + templateId);
        }
        Template template = templates.getForIssuance(templateId);
        if (count < 1 || count > props.maxBulkCount()) {
            throw ApiException.invalid("count must be between 1 and " + props.maxBulkCount());
        }
        City city = cities.resolve(cityInput);
        if (client != null && !client.tenantId().equals(city.tenantId())) {
            throw ApiException.forbidden("This API credential is not permitted to issue IDs for tenant " + city.tenantId());
        }

        // Values identical for every ID in this request, captured once.
        Map<Placeholder, String> staticValues = registry.resolveStatics(template, city, LocalDate.now(clock));

        // Counter scope is decided by SequenceService (implicit/named counter, per tenant or shared).
        long start = sequences.allocate(template, city, count);

        List<IssuedId> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long seq = start + i;
            rows.add(new IssuedId(template.id(), city.tenantId(), seq,
                    formatter.format(template, staticValues, seq), null));
        }
        issuedIds.saveAll(rows);
        if (client != null) {
            apiCredentials.markUsed(client.credentialId());
        }
        return rows.stream().map(IssuedId::generatedId).toList();
    }

    public List<IssuedId> recent(int limit) {
        return issuedIds.findRecent(limit);
    }
}
