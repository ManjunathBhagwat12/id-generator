package com.egov.idgenerator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/** API credentials: lifecycle, key secrecy, X-API-Key authentication and its tenant/template restrictions. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "idgen.admin.username=testadmin", "idgen.admin.password=S3cret-pass!"})
@Import(FixedClockConfig.class)
class ApiCredentialIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static final ObjectMapper JSON = new ObjectMapper();
    static final String BASE = "/api/v1/admin/api-credentials";

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    AdminClient admin;
    AdminClient app; // an external application: no session, only X-API-Key

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE counters, named_counters, issued_ids");
        jdbc.update("UPDATE cities SET active = TRUE");
        admin = new AdminClient("http://localhost:" + port);
        app = new AdminClient("http://localhost:" + port);
        assertThat(admin.login("testadmin", "S3cret-pass!").status()).isEqualTo(200);
    }

    static String body(Object... kv) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) {
                m.put((String) kv[i], kv[i + 1]);
            }
            return JSON.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Creates a credential for Bengaluru limited to PG_RECEIPT and WS_CONNECTION; returns the response JSON. */
    JsonNode createBlrCredential() {
        AdminClient.Resp r = admin.json("POST", BASE, body("appName", "Revenue portal", "description", "Receipts",
                "tenantId", "ka.bengaluru", "templateIds", List.of("PG_RECEIPT", "ws_connection")));
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        assertThat(r.headers().firstValue("cache-control").orElse("")).contains("no-store");
        return r.json();
    }

    AdminClient.Resp issueWithKey(String key, String template, String city) {
        return app.withHeader("POST", "/api/v1/ids/issue", body("templateId", template, "city", city), "X-API-Key", key);
    }

    int countersUsed() {
        return jdbc.queryForObject("SELECT (SELECT count(*) FROM counters) + (SELECT count(*) FROM named_counters)", Integer.class);
    }

    // ---------------------------------------------------------------- key generation and secrecy

    @Test
    void createdKeyHasPrefixAndFullEntropyAndIsShownOnce() {
        JsonNode r = createBlrCredential();
        String key = r.get("apiKey").asText();
        assertThat(key).startsWith("egid_live_").hasSize(53).matches("egid_live_[A-Za-z0-9_-]{43}");
        assertThat(r.get("warning").asText()).contains("only once");
        JsonNode c = r.get("credential");
        assertThat(c.get("keyPrefix").asText()).isEqualTo(key.substring(0, 18));
        assertThat(c.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(c.get("templates").toString()).isEqualTo("[\"PG_RECEIPT\",\"WS_CONNECTION\"]");
        assertThat(c.get("lastUsedAt").isNull()).isTrue();

        String id = c.get("id").asText();
        String list = admin.get(BASE).body();
        String detail = admin.get(BASE + "/" + id).body();
        assertThat(list).contains(id).doesNotContain(key).doesNotContain(key.substring(18));
        assertThat(detail).doesNotContain(key).doesNotContain(key.substring(18)).doesNotContain("apiKey").doesNotContain("hash");
    }

    @Test
    void plaintextKeyIsNeverStoredOnlyItsSha256() throws Exception {
        String key = createBlrCredential().get("apiKey").asText();
        String everything = String.join("\n", jdbc.queryForList(
                "SELECT row_to_json(c)::text FROM api_credentials c UNION ALL SELECT row_to_json(a)::text FROM api_credential_audit a",
                String.class));
        assertThat(everything).doesNotContain(key).doesNotContain(key.substring(18));
        String stored = jdbc.queryForObject("SELECT key_hash FROM api_credentials WHERE key_prefix = ?", String.class, key.substring(0, 18));
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes()));
        assertThat(stored).isEqualTo(sha);
    }

    @Test
    void databaseRejectsAPlaintextKeyInTheHashColumn() {
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () ->
                jdbc.update("INSERT INTO api_credentials (id, app_name, tenant_id, key_prefix, key_hash, status, created_by) "
                        + "VALUES (gen_random_uuid(), 'x', 'ka.bengaluru', 'egid_live_abcdefgh', 'egid_live_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKL', 'ACTIVE', 't')"));
    }

    // ---------------------------------------------------------------- authentication and restrictions

    @Test
    void validKeyIssuesIdsForItsTenantAndTemplatesAndRecordsLastUse() {
        JsonNode r = createBlrCredential();
        String key = r.get("apiKey").asText();
        AdminClient.Resp ok = issueWithKey(key, "PG_RECEIPT", "BLR");
        assertThat(ok.status()).as(ok.body()).isEqualTo(200);
        assertThat(ok.json().get("id").asText()).isEqualTo("PG-BLR-2026-27-000001");
        assertThat(issueWithKey(key, "WS_CONNECTION", "ka.bengaluru").json().get("id").asText()).isEqualTo("WS-BLR-1000-11-2026");
        AdminClient.Resp bulk = app.withHeader("POST", "/api/v1/ids/bulk",
                body("templateId", "PG_RECEIPT", "city", "BLR", "count", 3), "X-API-Key", key);
        assertThat(bulk.json().get("count").asInt()).isEqualTo(3);
        JsonNode c = admin.get(BASE + "/" + r.get("credential").get("id").asText()).json().get("credential");
        assertThat(c.get("lastUsedAt").isNull()).isFalse();
        assertThat(app.get("/api/v1/auth/me").json().get("authenticated").asBoolean()).isFalse();
    }

    @Test
    void invalidKeyIsRejectedAndNeverFallsBackToPublicAccess() {
        for (String bad : List.of("nope", "", "egid_live_", "egid_live_" + "A".repeat(43), "Bearer egid_live_x")) {
            AdminClient.Resp r = issueWithKey(bad, "PG_RECEIPT", "BLR");
            assertThat(r.status()).as("key '" + bad + "'").isEqualTo(401);
            assertThat(r.json().get("message").asText()).isEqualTo("Invalid or revoked API key");
        }
        assertThat(countersUsed()).isZero();
        // without the header the public demo flow still works
        assertThat(app.jsonWithoutCsrf("POST", "/api/v1/ids/issue", body("templateId", "PG_RECEIPT", "city", "BLR")).status()).isEqualTo(200);
    }

    @Test
    void wrongTenantAndUnauthorisedTemplateAre403AndConsumeNothing() {
        String key = createBlrCredential().get("apiKey").asText();
        AdminClient.Resp tenant = issueWithKey(key, "PG_RECEIPT", "PUN");
        assertThat(tenant.status()).isEqualTo(403);
        assertThat(tenant.json().get("message").asText()).contains("tenant mh.pune");
        AdminClient.Resp template = issueWithKey(key, "TL_LICENSE", "BLR");
        assertThat(template.status()).isEqualTo(403);
        assertThat(template.json().get("message").asText()).contains("template TL_LICENSE");
        assertThat(issueWithKey(key, "NO_SUCH_TEMPLATE", "BLR").status()).as("no template probing").isEqualTo(403);
        assertThat(countersUsed()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM issued_ids", Integer.class)).isZero();
    }

    @Test
    void revokedKeyIsRejected() {
        JsonNode r = createBlrCredential();
        String key = r.get("apiKey").asText();
        String id = r.get("credential").get("id").asText();
        assertThat(issueWithKey(key, "PG_RECEIPT", "BLR").status()).isEqualTo(200);
        AdminClient.Resp rev = admin.json("POST", BASE + "/" + id + "/revoke", "{}");
        assertThat(rev.status()).isEqualTo(200);
        assertThat(rev.json().get("status").asText()).isEqualTo("REVOKED");
        assertThat(rev.json().get("revokedBy").asText()).isEqualTo("testadmin");
        assertThat(issueWithKey(key, "PG_RECEIPT", "BLR").status()).isEqualTo(401);
        assertThat(admin.json("POST", BASE + "/" + id + "/revoke", "{}").status()).isEqualTo(409);
        assertThat(admin.json("POST", BASE + "/" + id + "/rotate", "{}").status()).isEqualTo(409);
    }

    @Test
    void rotationIssuesANewKeyAndInvalidatesTheOldOneImmediately() {
        JsonNode r = createBlrCredential();
        String oldKey = r.get("apiKey").asText();
        String id = r.get("credential").get("id").asText();
        AdminClient.Resp rot = admin.json("POST", BASE + "/" + id + "/rotate", "{}");
        assertThat(rot.status()).isEqualTo(200);
        String newKey = rot.json().get("apiKey").asText();
        assertThat(newKey).startsWith("egid_live_").isNotEqualTo(oldKey);
        assertThat(rot.json().get("credential").get("id").asText()).isEqualTo(id);
        assertThat(rot.json().get("credential").get("keyPrefix").asText()).isEqualTo(newKey.substring(0, 18));
        assertThat(rot.json().get("credential").get("rotatedAt").isNull()).isFalse();

        assertThat(issueWithKey(oldKey, "PG_RECEIPT", "BLR").status()).isEqualTo(401);
        assertThat(issueWithKey(newKey, "PG_RECEIPT", "BLR").status()).isEqualTo(200);
        assertThat(admin.get(BASE + "/" + id).body()).doesNotContain(newKey).doesNotContain(oldKey);
        JsonNode audit = admin.get(BASE + "/" + id).json().get("audit");
        assertThat(audit.findValuesAsText("action")).containsExactly("ROTATED", "CREATED");
    }

    @Test
    void apiKeysCannotUseAdminEndpoints() {
        String key = createBlrCredential().get("apiKey").asText();
        for (String path : List.of("/api/v1/admin/tenants", "/api/v1/admin/templates", BASE, "/api/v1/admin/analytics")) {
            assertThat(app.withHeader("GET", path, null, "X-API-Key", key).status()).as(path).isEqualTo(403);
        }
        assertThat(app.withHeader("POST", BASE, body("appName", "Evil", "tenantId", "ka.bengaluru", "templateIds", List.of("PG_RECEIPT")),
                "X-API-Key", key).status()).isEqualTo(403);
        // a valid key on a request that also carries an admin session cookie does not gain admin rights
        assertThat(admin.withHeader("GET", "/api/v1/admin/tenants", null, "X-API-Key", key).status()).isEqualTo(403);
        assertThat(admin.get("/api/v1/admin/tenants").status()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- admin API

    @Test
    void managementRequiresAdminAndValidatesInput() {
        assertThat(app.get(BASE).status()).isEqualTo(401);
        assertThat(app.json("POST", BASE, body("appName", "x", "tenantId", "ka.bengaluru", "templateIds", List.of("PG_RECEIPT"))).status())
                .isEqualTo(401);
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put(body("appName", "", "tenantId", "ka.bengaluru", "templateIds", List.of("PG_RECEIPT")), "appName");
        bad.put(body("appName", "App one", "tenantId", "zz.nowhere", "templateIds", List.of("PG_RECEIPT")), "unknown tenant");
        bad.put(body("appName", "App one", "tenantId", "ka.bengaluru", "templateIds", List.of()), "templateIds");
        bad.put(body("appName", "App one", "tenantId", "ka.bengaluru", "templateIds", List.of("NOPE_T")), "unknown template");
        for (var e : bad.entrySet()) {
            AdminClient.Resp r = admin.json("POST", BASE, e.getKey());
            assertThat(r.status()).as(e.getKey()).isEqualTo(400);
            assertThat(r.json().get("message").asText()).contains(e.getValue());
        }
        assertThat(admin.get(BASE + "/00000000-0000-0000-0000-000000000000").status()).isEqualTo(404);
        assertThat(admin.get(BASE + "/not-a-uuid").status()).isEqualTo(400);
    }
}
