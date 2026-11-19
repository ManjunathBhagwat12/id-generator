package com.egov.idgenerator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves counter state lives in PostgreSQL, not in the JVM: start the application, issue IDs, shut it
 * down completely, start a brand-new application instance on the same database, and check the sequence
 * continues instead of restarting at 1.
 */
@Testcontainers
class CounterPersistenceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(com.egov.idgenerator.IdGeneratorApplication.class)
                .run("--server.port=0",
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword());
    }

    private String post(ConfigurableApplicationContext ctx, String path, String json) throws Exception {
        String port = ctx.getEnvironment().getProperty("local.server.port");
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        return r.body();
    }

    @Test
    void counterSurvivesApplicationRestart() throws Exception {
        String first;
        try (ConfigurableApplicationContext ctx = start()) {
            first = post(ctx, "/api/v1/ids/bulk", "{\"templateId\":\"PG_RECEIPT\",\"city\":\"MYS\",\"count\":3}");
        }
        assertThat(first).contains("-000001\"").contains("-000003\"");

        String ws;
        String pgr;
        try (ConfigurableApplicationContext ctx = start()) { // new JVM-level context, same database
            String second = post(ctx, "/api/v1/ids/issue", "{\"templateId\":\"PG_RECEIPT\",\"city\":\"MYS\"}");
            assertThat(second).contains("PG-MYS-").contains("-000004\"");
            ws = post(ctx, "/api/v1/ids/bulk", "{\"templateId\":\"WS_CONNECTION\",\"city\":\"MYS\",\"count\":2}");
            pgr = post(ctx, "/api/v1/ids/bulk", "{\"templateId\":\"PGR_COMPLAINT\",\"city\":\"MYS\",\"count\":2}");
        }
        assertThat(ws).contains("WS-MYS-1000-").contains("WS-MYS-1001-");
        assertThat(pgr).contains("PGR-00000001").contains("PGR-00000002");

        try (ConfigurableApplicationContext ctx = start()) { // named and shared counters survive a restart too
            assertThat(post(ctx, "/api/v1/ids/issue", "{\"templateId\":\"WS_CONNECTION\",\"city\":\"MYS\"}")).contains("WS-MYS-1002-");
            assertThat(post(ctx, "/api/v1/ids/issue", "{\"templateId\":\"PGR_COMPLAINT\",\"city\":\"PUN\"}")).contains("PGR-00000003");
            assertThat(post(ctx, "/api/v1/ids/issue", "{\"templateId\":\"EST_ALLOTMENT\",\"city\":\"MYS\"}")).contains("EST-MYS-00001");
        }
    }
}
