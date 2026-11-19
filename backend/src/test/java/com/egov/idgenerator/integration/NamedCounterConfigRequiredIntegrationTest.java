package com.egov.idgenerator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Strict policy (the brief's reading): a template whose named counter has no configuration fails clearly. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "idgen.named-counters.require-config=true")
@Import(FixedClockConfig.class)
class NamedCounterConfigRequiredIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort int port;

    @Test
    void unconfiguredNamedCounterIsRejectedWithClearErrorOthersStillWork() {
        AdminClient c = new AdminClient("http://localhost:" + port);
        AdminClient.Resp r = c.jsonWithoutCsrf("POST", "/api/v1/ids/issue", "{\"templateId\":\"EST_ALLOTMENT\",\"city\":\"BLR\"}");
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.json().get("message").asText()).contains("SEQ_EST_ALLOT").contains("no configuration");
        assertThat(r.body()).doesNotContain("Exception");

        for (JsonNode t : c.get("/api/v1/templates").json()) {
            assertThat(t.get("enabled").asBoolean()).as(t.get("id").asText()).isEqualTo(!t.get("id").asText().equals("EST_ALLOTMENT"));
        }
        assertThat(c.jsonWithoutCsrf("POST", "/api/v1/ids/issue", "{\"templateId\":\"WS_CONNECTION\",\"city\":\"BLR\"}").json().get("id").asText())
                .isEqualTo("WS-BLR-1000-11-2026");
    }
}
