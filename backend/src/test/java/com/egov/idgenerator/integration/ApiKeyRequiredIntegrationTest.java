package com.egov.idgenerator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Production mode: issuance requires an API key (or an admin session); the public demo access is switched off. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "idgen.security.require-api-key=true", "idgen.admin.username=testadmin", "idgen.admin.password=S3cret-pass!"})
class ApiKeyRequiredIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort int port;

    @Test
    void anonymousIssuanceIsRejectedKeyAndAdminSessionAreAccepted() {
        String base = "http://localhost:" + port;
        String req = "{\"templateId\":\"PG_RECEIPT\",\"city\":\"BLR\"}";
        AdminClient anon = new AdminClient(base);
        assertThat(anon.jsonWithoutCsrf("POST", "/api/v1/ids/issue", req).status()).isEqualTo(401);
        assertThat(anon.get("/api/v1/templates").status()).as("read-only public endpoints unchanged").isEqualTo(200);

        AdminClient admin = new AdminClient(base);
        assertThat(admin.login("testadmin", "S3cret-pass!").status()).isEqualTo(200);
        assertThat(admin.jsonWithoutCsrf("POST", "/api/v1/ids/issue", req).status()).isEqualTo(200);
        String key = admin.json("POST", "/api/v1/admin/api-credentials",
                "{\"appName\":\"Portal\",\"tenantId\":\"ka.bengaluru\",\"templateIds\":[\"PG_RECEIPT\"]}").json().get("apiKey").asText();

        AdminClient app = new AdminClient(base);
        assertThat(app.withHeader("POST", "/api/v1/ids/issue", req, "X-API-Key", key).status()).isEqualTo(200);
    }
}
