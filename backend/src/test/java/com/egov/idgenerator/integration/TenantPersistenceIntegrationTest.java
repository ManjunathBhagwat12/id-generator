package com.egov.idgenerator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tenants managed through the admin API live in PostgreSQL: after a full application restart they are
 * still there, UI edits are NOT overwritten by the cities.csv seed, and counters/admin account survive.
 */
@Testcontainers
class TenantPersistenceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private ConfigurableApplicationContext start(String adminPassword) {
        return new SpringApplicationBuilder(com.egov.idgenerator.IdGeneratorApplication.class)
                .run("--server.port=0",
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--idgen.admin.username=boot-admin",
                        "--idgen.admin.password=" + adminPassword);
    }

    private AdminClient client(ConfigurableApplicationContext ctx) {
        return new AdminClient("http://localhost:" + ctx.getEnvironment().getProperty("local.server.port"));
    }

    @Test
    void managedTenantsSurviveRestartAndSeedNeverOverwrites() {
        try (ConfigurableApplicationContext ctx = start("first-password-1")) {
            AdminClient admin = client(ctx);
            assertThat(admin.login("boot-admin", "first-password-1").status()).isEqualTo(200);
            assertThat(admin.json("POST", "/api/v1/admin/tenants",
                    "{\"tenantId\":\"tn.coimbatore\",\"cityCode\":\"CBE\",\"cityName\":\"Coimbatore\"}").status()).isEqualTo(201);
            // edit a seeded tenant, deactivate another
            assertThat(admin.json("PUT", "/api/v1/admin/tenants/ka.bengaluru", "{\"cityCode\":\"BLR\",\"cityName\":\"Bengaluru (edited)\"}").status()).isEqualTo(200);
            assertThat(admin.json("PATCH", "/api/v1/admin/tenants/pb.patiala/status", "{\"active\":false}").status()).isEqualTo(200);
            assertThat(admin.json("POST", "/api/v1/ids/bulk", "{\"templateId\":\"PG_RECEIPT\",\"city\":\"CBE\",\"count\":3}").status()).isEqualTo(200);
        }

        // brand-new application instance, same database, different ADMIN_PASSWORD env value
        try (ConfigurableApplicationContext ctx = start("second-password-2")) {
            AdminClient admin = client(ctx);
            assertThat(admin.login("boot-admin", "second-password-2").status())
                    .as("existing admin must not be modified by env change").isEqualTo(401);
            assertThat(admin.login("boot-admin", "first-password-1").status()).isEqualTo(200);

            assertThat(admin.get("/api/v1/admin/tenants").json()).hasSize(11); // 10 seeded once + 1 created
            assertThat(admin.get("/api/v1/admin/tenants/tn.coimbatore").json().get("cityName").asText()).isEqualTo("Coimbatore");
            assertThat(admin.get("/api/v1/admin/tenants/ka.bengaluru").json().get("cityName").asText())
                    .as("UI edit not overwritten by CSV re-seed").isEqualTo("Bengaluru (edited)");
            assertThat(admin.get("/api/v1/admin/tenants/pb.patiala").json().get("active").asBoolean()).isFalse();
            assertThat(admin.json("POST", "/api/v1/ids/issue", "{\"templateId\":\"PG_RECEIPT\",\"city\":\"tn.coimbatore\"}")
                    .json().get("id").asText()).endsWith("-000004");
            assertThat(admin.json("POST", "/api/v1/ids/issue", "{\"templateId\":\"PG_RECEIPT\",\"city\":\"PTA\"}").status()).isEqualTo(422);
        }
    }
}
