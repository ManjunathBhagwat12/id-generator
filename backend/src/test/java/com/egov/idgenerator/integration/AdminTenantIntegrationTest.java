package com.egov.idgenerator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Admin authentication, tenant management and the tenant/ID-generation integration, over real HTTP. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "idgen.admin.username=testadmin", "idgen.admin.password=S3cret-pass!"})
@Import(FixedClockConfig.class)
class AdminTenantIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    AdminClient admin;      // logged in
    AdminClient anonymous;  // not logged in

    static final String NEW_TENANT = "{\"tenantId\":\"tn.coimbatore\",\"cityCode\":\"CBE\",\"cityName\":\"Coimbatore\"}";

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE counters, issued_ids");
        jdbc.update("DELETE FROM cities WHERE tenant_id = 'tn.coimbatore' OR tenant_id = 'tn.salem'");
        jdbc.update("UPDATE cities SET active = TRUE, city_name = initcap(substr(tenant_id, position('.' in tenant_id) + 1))");
        jdbc.update("UPDATE cities SET city_code = 'BLR' WHERE tenant_id = 'ka.bengaluru'");
        String base = "http://localhost:" + port;
        anonymous = new AdminClient(base);
        admin = new AdminClient(base);
        assertThat(admin.login("testadmin", "S3cret-pass!").status()).isEqualTo(200);
    }

    // ---------- authentication ----------

    @Test
    void successfulLoginSetsHardenedSessionCookie() {
        AdminClient c = new AdminClient("http://localhost:" + port);
        AdminClient.Resp r = c.login("testadmin", "S3cret-pass!");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("username").asText()).isEqualTo("testadmin");
        String session = r.headers().allValues("set-cookie").stream()
                .filter(h -> h.startsWith("JSESSIONID=")).findFirst().orElseThrow();
        assertThat(session).containsIgnoringCase("HttpOnly").containsIgnoringCase("SameSite=Lax");
        assertThat(c.get("/api/v1/auth/me").json().get("authenticated").asBoolean()).isTrue();
    }

    @Test
    void invalidCredentialsAreRejectedWithoutLeakingWhichPartWasWrong() {
        AdminClient.Resp wrongPass = new AdminClient("http://localhost:" + port).login("testadmin", "nope");
        AdminClient.Resp wrongUser = new AdminClient("http://localhost:" + port).login("ghost", "nope");
        assertThat(wrongPass.status()).isEqualTo(401);
        assertThat(wrongUser.status()).isEqualTo(401);
        assertThat(wrongPass.json().get("message").asText()).isEqualTo(wrongUser.json().get("message").asText());
        assertThat(wrongPass.headers().allValues("set-cookie")).noneMatch(h -> h.startsWith("JSESSIONID="));
    }

    @Test
    void passwordIsStoredAsBcryptHashOnly() {
        String hash = jdbc.queryForObject("SELECT password_hash FROM admin_users WHERE username='testadmin'", String.class);
        assertThat(hash).startsWith("$2").doesNotContain("S3cret-pass!");
    }

    @Test
    void logoutEndsTheSession() {
        assertThat(admin.get("/api/v1/admin/tenants").status()).isEqualTo(200);
        assertThat(admin.logout().status()).isEqualTo(204);
        assertThat(admin.get("/api/v1/admin/tenants").status()).isEqualTo(401);
    }

    // ---------- protection ----------

    @Test
    void unauthenticatedCannotUseAnyAdminEndpoint() {
        assertThat(anonymous.get("/api/v1/admin/tenants").status()).isEqualTo(401);
        assertThat(anonymous.get("/api/v1/admin/tenants/summary").status()).isEqualTo(401);
        assertThat(anonymous.get("/api/v1/admin/tenants/ka.bengaluru").status()).isEqualTo(401);
        assertThat(anonymous.json("POST", "/api/v1/admin/tenants", NEW_TENANT).status()).isEqualTo(401);
        assertThat(anonymous.json("PUT", "/api/v1/admin/tenants/ka.bengaluru", "{\"cityCode\":\"BLR\",\"cityName\":\"X\"}").status()).isEqualTo(401);
        assertThat(anonymous.json("PATCH", "/api/v1/admin/tenants/ka.bengaluru/status", "{\"active\":false}").status()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cities WHERE tenant_id='tn.coimbatore'", Integer.class)).isZero();
    }

    @Test
    void stateChangingRequestWithoutCsrfTokenIsRejected() {
        assertThat(admin.jsonWithoutCsrf("POST", "/api/v1/admin/tenants", NEW_TENANT).status()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cities WHERE tenant_id='tn.coimbatore'", Integer.class)).isZero();
    }

    @Test
    void publicGeneratorEndpointsStayOpen() {
        assertThat(anonymous.get("/api/v1/cities").status()).isEqualTo(200);
        assertThat(anonymous.get("/api/v1/templates").status()).isEqualTo(200);
        assertThat(anonymous.get("/api/v1/ids/recent").status()).isEqualTo(200);
        assertThat(anonymous.jsonWithoutCsrf("POST", "/api/v1/ids/issue", "{\"templateId\":\"PG_RECEIPT\",\"city\":\"BLR\"}")
                .status()).isEqualTo(200);
    }

    // ---------- tenant management ----------

    @Test
    void seedDataIsLoadedOnceFromCsv() {
        AdminClient.Resp r = admin.get("/api/v1/admin/tenants");
        assertThat(r.json()).hasSize(10);
        assertThat(admin.get("/api/v1/admin/tenants/summary").json().toString())
                .contains("\"total\":10").contains("\"active\":10").contains("\"inactive\":0");
    }

    @Test
    void createsTenantAndItIsImmediatelyUsableForIds() {
        AdminClient.Resp r = admin.json("POST", "/api/v1/admin/tenants", NEW_TENANT);
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.headers().firstValue("location")).hasValue("/api/v1/admin/tenants/tn.coimbatore");
        assertThat(r.json().get("active").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cities", Integer.class)).isEqualTo(11);

        // by city code and by tenant id: same counter
        assertThat(anonymous.json("POST", "/api/v1/ids/issue", "{\"templateId\":\"PG_RECEIPT\",\"city\":\"CBE\"}").json().get("id").asText())
                .isEqualTo("PG-CBE-2026-27-000001");
        assertThat(anonymous.json("POST", "/api/v1/ids/issue", "{\"templateId\":\"PG_RECEIPT\",\"city\":\"tn.coimbatore\"}").json().get("id").asText())
                .isEqualTo("PG-CBE-2026-27-000002");
        assertThat(anonymous.get("/api/v1/cities").json().toString()).contains("tn.coimbatore");
    }

    @Test
    void normalisesCaseAndWhitespace() {
        AdminClient.Resp r = admin.json("POST", "/api/v1/admin/tenants",
                "{\"tenantId\":\"  TN.Coimbatore \",\"cityCode\":\" cbe\",\"cityName\":\"  Coimbatore   City \"}");
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.json().get("tenantId").asText()).isEqualTo("tn.coimbatore");
        assertThat(r.json().get("cityCode").asText()).isEqualTo("CBE");
        assertThat(r.json().get("cityName").asText()).isEqualTo("Coimbatore City");
    }

    @Test
    void duplicateTenantIdIs409() {
        assertThat(admin.json("POST", "/api/v1/admin/tenants", NEW_TENANT).status()).isEqualTo(201);
        AdminClient.Resp dup = admin.json("POST", "/api/v1/admin/tenants",
                "{\"tenantId\":\"tn.coimbatore\",\"cityCode\":\"CBX\",\"cityName\":\"Other\"}");
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.json().get("message").asText()).contains("tenant ID");
    }

    @Test
    void duplicateCityCodeIs409() {
        AdminClient.Resp dup = admin.json("POST", "/api/v1/admin/tenants",
                "{\"tenantId\":\"tn.salem\",\"cityCode\":\"blr\",\"cityName\":\"Salem\"}");
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.json().get("message").asText()).contains("city code");
    }

    @Test
    void malformedInputIs400() {
        List<String> bad = List.of(
                "{}",
                "{\"tenantId\":\"\",\"cityCode\":\"CBE\",\"cityName\":\"Coimbatore\"}",
                "{\"tenantId\":\"nodot\",\"cityCode\":\"CBE\",\"cityName\":\"Coimbatore\"}",
                "{\"tenantId\":\"tn.cbe\",\"cityCode\":\"C\",\"cityName\":\"Coimbatore\"}",
                "{\"tenantId\":\"tn.cbe\",\"cityCode\":\"C-BE\",\"cityName\":\"Coimbatore\"}",
                "{\"tenantId\":\"tn.cbe\",\"cityCode\":\"CBE\",\"cityName\":\"<script>alert(1)</script>\"}",
                "{\"tenantId\":\"tn.cbe\",\"cityCode\":\"CBE\",\"cityName\":\" \"}",
                "{\"tenantId\":\"tn cbe\",\"cityCode\":\"CBE\",\"cityName\":\"Coimbatore\"}",
                "{not json");
        for (String body : bad) {
            assertThat(admin.json("POST", "/api/v1/admin/tenants", body).status()).as(body).isEqualTo(400);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cities", Integer.class)).isEqualTo(10);
    }

    @Test
    void updatesNameAndCodeOfTenantWithoutIssuedIds() {
        admin.json("POST", "/api/v1/admin/tenants", NEW_TENANT);
        AdminClient.Resp r = admin.json("PUT", "/api/v1/admin/tenants/tn.coimbatore",
                "{\"cityCode\":\"CJB\",\"cityName\":\"Coimbatore City\"}");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("cityCode").asText()).isEqualTo("CJB");
        assertThat(r.json().get("cityName").asText()).isEqualTo("Coimbatore City");
        assertThat(r.json().get("updatedAt").asText()).isNotEqualTo(r.json().get("createdAt").asText());
    }

    @Test
    void updateRejectsDuplicateCodeAndUnknownTenant() {
        assertThat(admin.json("PUT", "/api/v1/admin/tenants/ka.mysuru", "{\"cityCode\":\"BLR\",\"cityName\":\"Mysuru\"}").status())
                .isEqualTo(409);
        assertThat(admin.json("PUT", "/api/v1/admin/tenants/zz.nothing", "{\"cityCode\":\"ZZZ\",\"cityName\":\"Nothing\"}").status())
                .isEqualTo(404);
    }

    @Test
    void cityCodeIsLockedOnceIdsWereIssuedButRenamingStillWorks() {
        anonymous.json("POST", "/api/v1/ids/issue", "{\"templateId\":\"PG_RECEIPT\",\"city\":\"BLR\"}");
        assertThat(admin.json("PUT", "/api/v1/admin/tenants/ka.bengaluru", "{\"cityCode\":\"BNG\",\"cityName\":\"Bengaluru\"}").status())
                .isEqualTo(409);
        assertThat(admin.json("PUT", "/api/v1/admin/tenants/ka.bengaluru", "{\"cityCode\":\"BLR\",\"cityName\":\"Bengaluru City\"}").status())
                .isEqualTo(200);
        AdminClient.Resp detail = admin.get("/api/v1/admin/tenants/ka.bengaluru");
        assertThat(detail.json().get("issuedIdCount").asLong()).isEqualTo(1);
        assertThat(detail.json().get("cityCodeLocked").asBoolean()).isTrue();
    }

    @Test
    void deactivatedTenantCannotIssueIdsAndIsHiddenFromGeneratorList() {
        assertThat(admin.json("PATCH", "/api/v1/admin/tenants/ka.bengaluru/status", "{\"active\":false}").json().get("active").asBoolean())
                .isFalse();

        for (String key : new String[] {"BLR", "ka.bengaluru"}) {
            AdminClient.Resp r = anonymous.json("POST", "/api/v1/ids/issue", "{\"templateId\":\"PG_RECEIPT\",\"city\":\"" + key + "\"}");
            assertThat(r.status()).as(key).isEqualTo(422);
            assertThat(r.json().get("message").asText()).contains("inactive");
        }
        assertThat(anonymous.json("POST", "/api/v1/ids/bulk", "{\"templateId\":\"PG_RECEIPT\",\"city\":\"BLR\",\"count\":3}").status())
                .isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM counters", Integer.class)).as("no sequence consumed").isZero();
        assertThat(anonymous.get("/api/v1/cities").json()).hasSize(9);
        assertThat(admin.get("/api/v1/admin/tenants").json()).hasSize(10); // admin still sees it

        // reactivation restores issuance, continuing the same counter
        admin.json("PATCH", "/api/v1/admin/tenants/ka.bengaluru/status", "{\"active\":true}");
        assertThat(anonymous.json("POST", "/api/v1/ids/issue", "{\"templateId\":\"PG_RECEIPT\",\"city\":\"BLR\"}").status()).isEqualTo(200);
    }

    @Test
    void statusRequestIsValidated() {
        assertThat(admin.json("PATCH", "/api/v1/admin/tenants/ka.bengaluru/status", "{}").status()).isEqualTo(400);
        assertThat(admin.json("PATCH", "/api/v1/admin/tenants/ka.bengaluru/status", "{\"active\":\"maybe\"}").status()).isEqualTo(400);
        assertThat(admin.json("PATCH", "/api/v1/admin/tenants/zz.nothing/status", "{\"active\":false}").status()).isEqualTo(404);
    }

    @Test
    void searchAndStatusFilter() {
        admin.json("PATCH", "/api/v1/admin/tenants/pb.patiala/status", "{\"active\":false}");
        assertThat(admin.get("/api/v1/admin/tenants?search=pune").json()).hasSize(1);
        assertThat(admin.get("/api/v1/admin/tenants?search=MH.").json()).hasSize(2);
        assertThat(admin.get("/api/v1/admin/tenants?search=ldh").json()).hasSize(1);
        assertThat(admin.get("/api/v1/admin/tenants?status=inactive").json()).hasSize(1);
        assertThat(admin.get("/api/v1/admin/tenants?status=active").json()).hasSize(9);
        assertThat(admin.get("/api/v1/admin/tenants?search=%25").json()).as("wildcards are escaped").isEmpty();
        assertThat(admin.get("/api/v1/admin/tenants?status=bogus").status()).isEqualTo(400);
        assertThat(admin.get("/api/v1/admin/tenants/summary").json().toString()).contains("\"inactive\":1");
    }

    @Test
    void tenantsWithIssuedIdsCannotBeDeletedAtDatabaseLevel() {
        anonymous.json("POST", "/api/v1/ids/issue", "{\"templateId\":\"PG_RECEIPT\",\"city\":\"BLR\"}");
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("DELETE FROM cities WHERE tenant_id = 'ka.bengaluru'"));
    }

    @Test
    void errorResponsesContainNoStackTraces() {
        AdminClient.Resp r = admin.json("POST", "/api/v1/admin/tenants", "{\"tenantId\":\"tn.cbe\"}");
        assertThat(r.body()).doesNotContain("Exception").doesNotContain("at com.");
        assertThat(anonymous.get("/api/v1/admin/tenants").body()).doesNotContain("Exception").doesNotContain("at org.");
    }
}
