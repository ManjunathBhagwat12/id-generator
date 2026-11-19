package com.egov.idgenerator.service;

import com.egov.idgenerator.parser.Segment.Placeholder;
import org.springframework.stereotype.Component;

@Component
public class CityPlaceholderResolver implements PlaceholderResolver {

    @Override
    public String kind() {
        return "CITY";
    }

    @Override
    public String resolve(Placeholder placeholder, ResolutionContext ctx) {
        return ctx.city().cityCode();
    }
}
