package com.egov.idgenerator.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** Business clock pinned to Asia/Kolkata. Injected so tests can substitute a fixed clock. */
    @Bean
    public Clock businessClock(IdGenProperties props) {
        return Clock.system(ZoneId.of(props.businessZone()));
    }
}
