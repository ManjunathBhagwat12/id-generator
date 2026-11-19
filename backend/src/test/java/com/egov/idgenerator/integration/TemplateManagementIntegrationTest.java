package com.egov.idgenerator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Simple immutable templates: one id, one format, create + view only. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "idgen.admin.username=testadmin", "idgen.admin.password=S3cret-pass!"})
@Import(FixedClockConfig.class)
class TemplateManagementIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static final ObjectMapper JSON = new ObjectMapper();
    static final String BASE = "/api/v1/admin/templates";
    static final AtomicInteger UNIQUE = new AtomicInteger();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    AdminClient admin;
    AdminClient anonymous;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE counters, issued_ids");
        String base = "http://localhost:" + port;
        anonymous = new AdminClient(base);
        admin = new AdminClient(base);
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

    /** Unique id + unique format prefix per call (identical formats are rejected). */
    record Names(String id, String format) {
    }

    static Names names() {
        int n = UNIQUE.incrementAndGet();
        return new Names("TST_" + n, "TS" + n + "-[CITY]-[FY:yyyy-yy]-[SEQ:4]");
    }

    AdminClient.Resp create(Names n) {
        return admin.json("POST", BASE, body("templateId", n.id(), "name", "Test template " + n.id().replace("_", " "), "format", n.format()));
    }

    AdminClient.Resp issue(String templateId, String city, int count) {
        return anonymous.jsonWithoutCsrf("POST", "/api/v1/ids/bulk", body("templateId", templateId, "city", city, "count", count));
    }

    // ---------------------------------------------------------------- seeding / viewing

    @Test
    void seedsTheSixSuppliedTemplatesAllIssuable() {
        JsonNode list = admin.get(BASE).json();
        assertThat(list.findValuesAsText("templateId")).contains("PG_RECEIPT", "TL_LICENSE", "PGR_COMPLAINT",
                "WS_CONNECTION", "BPA_PERMIT", "EST_ALLOTMENT");
        List<String> issuable = new ArrayList<>();
        list.forEach(t -> {
            if (t.get("issuable").asBoolean() && !t.get("templateId").asText().startsWith("TST_")) {
                issuable.add(t.get("templateId").asText());
            }
        });
        assertThat(issuable).containsExactlyInAnyOrder("PG_RECEIPT", "TL_LICENSE", "PGR_COMPLAINT", "WS_CONNECTION", "BPA_PERMIT", "EST_ALLOTMENT");
        JsonNode pg = admin.get(BASE + "/PG_RECEIPT").json();
        assertThat(pg.get("format").asText()).isEqualTo("PG-[CITY]-[FY:yyyy-yy]-[SEQ:6]");
        assertThat(pg.has("version") || pg.has("versions") || pg.has("status")).as("no versioning fields").isFalse();
    }

    @Test
    void viewShowsIssuedIdCountAndUnknownTemplateIs404() {
        issue("PG_RECEIPT", "BLR", 4);
        assertThat(admin.get(BASE + "/PG_RECEIPT").json().get("issuedIdCount").asLong()).isEqualTo(4);
        assertThat(admin.get(BASE + "/pg_receipt").status()).as("case-insensitive").isEqualTo(200);
        assertThat(admin.get(BASE + "/NOPE_X").status()).isEqualTo(404);
    }

    @Test
    void everyTemplateAdminEndpointRequiresLogin() {
        assertThat(anonymous.get(BASE).status()).isEqualTo(401);
        assertThat(anonymous.get(BASE + "/PG_RECEIPT").status()).isEqualTo(401);
        assertThat(anonymous.json("POST", BASE, body("templateId", "NEW_ONE", "name", "New one", "format", "N-[CITY]-[SEQ:4]")).status()).isEqualTo(401);
    }

    // ---------------------------------------------------------------- creating

    @Test
    void createdTemplateIsImmediatelyUsableAndItsCounterStartsAtOne() {
        Names n = names();
        AdminClient.Resp r = create(n);
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.json().get("issuable").asBoolean()).isTrue();
        assertThat(r.headers().firstValue("location")).hasValue(BASE + "/" + n.id());
        assertThat(issue(n.id(), "BLR", 2).json().get("ids").toString())
                .contains(n.format().substring(0, n.format().indexOf('-')) + "-BLR-2026-27-0001")
                .contains("-0002");
        assertThat(anonymous.get("/api/v1/templates").json().findValuesAsText("id")).contains(n.id());
    }

    @Test
    void duplicateTemplateIdIs409EvenWithADifferentFormat() {
        Names n = names();
        assertThat(create(n).status()).isEqualTo(201);
        Names other = names();
        AdminClient.Resp dup = admin.json("POST", BASE, body("templateId", n.id(), "name", "Another name", "format", other.format()));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.json().get("message").asText()).contains("already exists");
        // the stored format is still the original one
        assertThat(admin.get(BASE + "/" + n.id()).json().get("format").asText()).isEqualTo(n.format());
    }

    @Test
    void identicalFormatUnderAnotherIdIsRejected() {
        Names n = names();
        create(n);
        AdminClient.Resp dup = admin.json("POST", BASE, body("templateId", "TST_COPY" + UNIQUE.incrementAndGet(), "name", "Copy cat", "format", n.format()));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.json().get("message").asText()).contains("exactly this format");
        assertThat(admin.json("POST", BASE, body("templateId", "TST_COPY2", "name", "Copy cat", "format", "PG-[CITY]-[FY:yyyy-yy]-[SEQ:6]")).status())
                .as("same as PG_RECEIPT").isEqualTo(409);
    }

    @Test
    void invalidInputIsRejectedWith400() {
        Map<String, String> badFormats = new LinkedHashMap<>();
        badFormats.put("PG-[CITY]-[SEQ:6", "Malformed");
        badFormats.put("X-[CITY]", "exactly one sequence");
        badFormats.put("X-[CITY]-[SEQ:3]-[SEQ_WS:4]", "exactly one sequence");
        badFormats.put("X-[CITY]-[FOO]-[SEQ:6]", "[FOO] is not supported");
        badFormats.put("X-[CITY]-[CY:qq]-[SEQ:6]", "invalid date pattern");
        badFormats.put("X-[CITY]-[CY:MMMM]-[SEQ:6]", "invalid date pattern");
        badFormats.put("X-[CITY]-[CY:HH]-[SEQ:6]", "invalid date pattern");
        badFormats.put("X-[CITY]-[FY:yyyy]-[SEQ:6]", "financial-year pattern");
        badFormats.put("X-[CITY]-[SEQ_WS:3]", "does not fit in 3 digits");
        badFormats.put("X <b>-[CITY]-[SEQ:6]", "may contain only");
        badFormats.put("X".repeat(201), "at most 200");
        int i = 0;
        for (var e : badFormats.entrySet()) {
            AdminClient.Resp r = admin.json("POST", BASE, body("templateId", "BADT_" + UNIQUE.incrementAndGet() + i++, "name", "Bad one", "format", e.getKey()));
            assertThat(r.status()).as(e.getKey()).isEqualTo(400);
            assertThat(r.json().get("message").asText()).as(e.getKey()).contains(e.getValue());
        }
        for (String badId : List.of("ab", "1ABC", "has space", "A".repeat(51))) {
            assertThat(admin.json("POST", BASE, body("templateId", badId, "name", "Good name", "format", names().format())).status())
                    .as(badId).isEqualTo(400);
        }
        assertThat(admin.json("POST", BASE, "{}").status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM templates WHERE template_id LIKE 'BADT\\_%'", Integer.class)).isZero();
    }

    // ---------------------------------------------------------------- no edit / delete / versions

    @Test
    void thereIsNoEditDeleteOrVersionApi() {
        for (String method : List.of("PUT", "PATCH", "DELETE")) {
            assertThat(admin.json(method, BASE + "/PG_RECEIPT", body("format", "X-[CITY]-[SEQ:6]", "name", "x")).status())
                    .as(method).isEqualTo(405);
        }
        for (String path : List.of("/versions", "/versions/1", "/versions/1/publish", "/audit", "/preview")) {
            int status = admin.json("POST", BASE + "/PG_RECEIPT" + path, "{}").status();
            assertThat(status).as("POST " + path).isIn(404, 405);
            assertThat(admin.get(BASE + "/PG_RECEIPT" + path).status()).as("GET " + path).isIn(404, 405);
        }
        assertThat(admin.get(BASE + "/PG_RECEIPT").json().get("format").asText()).isEqualTo("PG-[CITY]-[FY:yyyy-yy]-[SEQ:6]");
    }

    @Test
    void databaseRefusesToChangeOrDeleteTemplatesEvenViaSql() {
        Names n = names();
        create(n);
        assertThatThrownBy(() -> jdbc.update("UPDATE templates SET format = 'HACK-[CITY]-[SEQ:6]' WHERE template_id = ?", n.id()))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("UPDATE templates SET name = 'renamed' WHERE template_id = ?", n.id()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM templates WHERE template_id = ?", n.id()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE templates CASCADE")).isInstanceOf(DataAccessException.class);
        assertThat(admin.get(BASE + "/" + n.id()).json().get("format").asText()).isEqualTo(n.format());
    }

    @Test
    void thereAreNoVersioningTablesOrColumns() {
        assertThat(jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='public'", String.class))
                .doesNotContain("template_versions", "template_audit_log");
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_name='issued_ids'", String.class))
                .doesNotContain("template_version");
    }

    // ---------------------------------------------------------------- issued IDs and counters

    @Test
    void issuedIdsAndCountersMustReferenceAnExistingTemplate() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO issued_ids (template_id, tenant_id, sequence_value, generated_id) VALUES ('GHOST', 'ka.bengaluru', 1, 'GHOST-1')"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO counters (template_id, tenant_id, next_value) VALUES ('GHOST', 'ka.bengaluru', 1)"))
                .isInstanceOf(DataAccessException.class);
        issue("PG_RECEIPT", "BLR", 1);
        assertThat(jdbc.queryForObject("SELECT template_id FROM issued_ids LIMIT 1", String.class)).isEqualTo("PG_RECEIPT");
    }

    @Test
    void creatingTemplatesNeverChangesExistingCounters() {
        issue("PG_RECEIPT", "BLR", 7);
        issue("PG_RECEIPT", "PUN", 3);
        List<Map<String, Object>> before = jdbc.queryForList("SELECT template_id, tenant_id, next_value FROM counters ORDER BY 1, 2");
        assertThat(before).hasSize(2);

        assertThat(create(names()).status()).isEqualTo(201);
        assertThat(create(names()).status()).isEqualTo(201);
        admin.json("POST", BASE, body("templateId", "PG_RECEIPT", "name", "Payment receipt number", "format", names().format())); // rejected

        assertThat(jdbc.queryForList("SELECT template_id, tenant_id, next_value FROM counters WHERE template_id='PG_RECEIPT' ORDER BY 1, 2"))
                .isEqualTo(before);
        assertThat(issue("PG_RECEIPT", "BLR", 1).json().get("ids").get(0).asText()).isEqualTo("PG-BLR-2026-27-000008");
    }
}
