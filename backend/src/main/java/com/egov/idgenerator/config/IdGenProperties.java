package com.egov.idgenerator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalised application configuration.
 *
 * @param templatesPath    path to templates.json (read-only configuration, never modified)
 * @param citiesPath       path to cities.csv (read-only configuration, never modified)
 * @param businessZone     time zone used for all date-based placeholders (Asia/Kolkata)
 * @param maxBulkCount     upper bound for one bulk request
 * @param admin            initial administrator bootstrap settings
 * @param namedCounters    policy for [SEQ_NAME:n] counters without configuration
 */
@ConfigurationProperties(prefix = "idgen")
public record IdGenProperties(
        String templatesPath,
        String citiesPath,
        String businessZone,
        int maxBulkCount,
        Admin admin,
        NamedCounters namedCounters) {

    public record NamedCounters(boolean requireConfig, long defaultStart) {
    }

    /** Initial admin account (from environment). toString never reveals the password. */
    public record Admin(String username, String password) {
        @Override
        public String toString() {
            return "Admin[username=" + username + ", password=***]";
        }
    }
}
