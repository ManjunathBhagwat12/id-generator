package com.egov.idgenerator.integration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Test configuration: a fixed clock (2026-11-18 10:00 IST => FY 2026-27) so assertions are deterministic. */
@TestConfiguration
class FixedClockConfig {
    @Bean
    @Primary
    Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-11-18T04:30:00Z"), ZoneId.of("Asia/Kolkata"));
    }
}
