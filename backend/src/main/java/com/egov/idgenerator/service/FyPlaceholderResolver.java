package com.egov.idgenerator.service;

import com.egov.idgenerator.parser.Segment.Placeholder;
import org.springframework.stereotype.Component;

@Component
public class FyPlaceholderResolver implements PlaceholderResolver {

    private final FinancialYearResolver fy = new FinancialYearResolver();

    @Override
    public String kind() {
        return "FY";
    }

    @Override
    public String problem(Placeholder p) {
        return p.arg() != null && fy.supports(p.arg()) ? null
                : "[FY" + (p.arg() == null ? "" : ":" + p.arg()) + "] is not a supported financial-year pattern; use [FY:yyyy-yy] or [FY:yy-yy]";
    }

    @Override
    public String resolve(Placeholder placeholder, ResolutionContext ctx) {
        return fy.format(ctx.businessDate(), placeholder.arg());
    }
}
