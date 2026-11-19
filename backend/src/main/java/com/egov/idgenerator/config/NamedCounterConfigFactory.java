package com.egov.idgenerator.config;

import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NamedCounterConfigFactory {

    @Bean
    NamedCounterConfig namedCounterConfig(IdGenProperties props) {
        IdGenProperties.NamedCounters nc = props.namedCounters();
        return NamedCounterConfig.load(Path.of(props.templatesPath()),
                nc != null && nc.requireConfig(), nc == null ? 1 : nc.defaultStart());
    }
}
