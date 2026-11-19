package com.egov.idgenerator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * All six supplied templates over HTTP against PostgreSQL. Fixed clock: 2026-11-18 10:00 IST
 * (FY 2026-27 / 26-27, CY 2026 / 11-2026). Default policy: unconfigured named counters start at 1.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "idgen.admin.username=testadmin", "idgen.admin.password=S3cret-pass!"})
@Import(FixedClockConfig.class)
class AllTemplatesIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    AdminClient http;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE counters, named_counters, issued_ids");
        jdbc.update("UPDATE cities SET active = TRUE");
        http = new AdminClient("http://localhost:" + port);
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

    AdminClient.Resp issueOne(String template, String city) {
        return http.jsonWithoutCsrf("POST", "/api/v1/ids/issue", body("templateId", template, "city", city));
    }

    String one(String template, String city) {
        AdminClient.Resp r = issueOne(template, city);
        assertThat(r.status()).as(template + "/" + city + ": " + r.body()).isEqualTo(200);
        return r.json().get("id").asText();
    }

    List<String> bulk(String template, String city, int count) {
        AdminClient.Resp r = http.jsonWithoutCsrf("POST", "/api/v1/ids/bulk", body("templateId", template, "city", city, "count", count));
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.json().get("count").asInt()).isEqualTo(count);
        List<String> ids = new ArrayList<>();
        r.json().get("ids").forEach(i -> ids.add(i.asText()));
        return ids;
    }

    // ------------------------------------------------------------ first IDs, CITY / FY / CY / padding / start values

    @ParameterizedTest(name = "{0} -> {2}")
    @CsvSource({
            "PG_RECEIPT,    BLR,          PG-BLR-2026-27-000001",
            "TL_LICENSE,    BLR,          TL/BLR/2026/00001",
            "PGR_COMPLAINT, BLR,          PGR-00000001",
            "WS_CONNECTION, BLR,          WS-BLR-1000-11-2026",       // named counter SEQ_WS starts at 1000
            "BPA_PERMIT,    BLR,          BPA-BLR-26-27-000001",      // FY:yy-yy, SEQ_BPA_PERMIT starts at 1
            "EST_ALLOTMENT, BLR,          EST-BLR-00001",             // SEQ_EST_ALLOT unconfigured -> default 1
            "TL_LICENSE,    pb.amritsar,  TL/AMR/2026/00001",         // tenant id -> city code
            "WS_CONNECTION, mh.pune,      WS-PUN-1000-11-2026"
    })
    void firstIdOfEveryTemplate(String template, String city, String expected) {
        assertThat(one(template, city)).isEqualTo(expected);
    }

    // ------------------------------------------------------------ bulk + ordering

    @Test
    void bulkReturnsContiguousOrderedRangesForEveryTemplate() {
        assertThat(bulk("PG_RECEIPT", "BLR", 3)).containsExactly("PG-BLR-2026-27-000001", "PG-BLR-2026-27-000002", "PG-BLR-2026-27-000003");
        assertThat(bulk("TL_LICENSE", "BLR", 3)).containsExactly("TL/BLR/2026/00001", "TL/BLR/2026/00002", "TL/BLR/2026/00003");
        assertThat(bulk("PGR_COMPLAINT", "BLR", 3)).containsExactly("PGR-00000001", "PGR-00000002", "PGR-00000003");
        assertThat(bulk("WS_CONNECTION", "BLR", 3)).containsExactly("WS-BLR-1000-11-2026", "WS-BLR-1001-11-2026", "WS-BLR-1002-11-2026");
        assertThat(bulk("BPA_PERMIT", "BLR", 3)).containsExactly("BPA-BLR-26-27-000001", "BPA-BLR-26-27-000002", "BPA-BLR-26-27-000003");
        assertThat(bulk("EST_ALLOTMENT", "BLR", 3)).containsExactly("EST-BLR-00001", "EST-BLR-00002", "EST-BLR-00003");
        // next request continues after the bulk range
        assertThat(one("WS_CONNECTION", "BLR")).isEqualTo("WS-BLR-1003-11-2026");
        assertThat(one("EST_ALLOTMENT", "ka.bengaluru")).isEqualTo("EST-BLR-00004");
    }

    // ------------------------------------------------------------ isolation

    @Test
    void countersAreIsolatedBetweenTenants() {
        bulk("WS_CONNECTION", "BLR", 5);
        bulk("TL_LICENSE", "BLR", 5);
        bulk("BPA_PERMIT", "BLR", 5);
        assertThat(one("WS_CONNECTION", "CHN")).isEqualTo("WS-CHN-1000-11-2026");
        assertThat(one("TL_LICENSE", "CHN")).isEqualTo("TL/CHN/2026/00001");
        assertThat(one("BPA_PERMIT", "CHN")).isEqualTo("BPA-CHN-26-27-000001");
        assertThat(one("WS_CONNECTION", "BLR")).isEqualTo("WS-BLR-1005-11-2026");
    }

    @Test
    void countersAreIsolatedBetweenTemplates() {
        bulk("PG_RECEIPT", "BLR", 4);
        assertThat(one("TL_LICENSE", "BLR")).endsWith("/00001");
        assertThat(one("EST_ALLOTMENT", "BLR")).isEqualTo("EST-BLR-00001");
        assertThat(one("BPA_PERMIT", "BLR")).isEqualTo("BPA-BLR-26-27-000001");
        assertThat(jdbc.queryForList("SELECT template_id || '/' || tenant_id || '=' || next_value FROM counters ORDER BY 1", String.class))
                .containsExactly("PG_RECEIPT/ka.bengaluru=5", "TL_LICENSE/ka.bengaluru=2");
        assertThat(jdbc.queryForList("SELECT counter_name || '/' || tenant_id || '=' || next_value FROM named_counters ORDER BY 1", String.class))
                .containsExactly("SEQ_BPA_PERMIT/ka.bengaluru=2", "SEQ_EST_ALLOT/ka.bengaluru=2");
    }

    @Test
    void namedCounterIsSharedByTemplatesUsingTheSameName() {
        AdminClient admin = new AdminClient("http://localhost:" + port);
        assertThat(admin.login("testadmin", "S3cret-pass!").status()).isEqualTo(200);
        AdminClient.Resp c = admin.json("POST", "/api/v1/admin/templates",
                body("templateId", "WP_REGISTER", "name", "Water point register", "format", "WP-[CITY]-[SEQ_WS:8]-[CY:MM-yyyy]"));
        assertThat(c.status()).as(c.body()).isIn(201, 409); // 409 if a previous test run in this context created it
        assertThat(one("WS_CONNECTION", "BLR")).isEqualTo("WS-BLR-1000-11-2026");
        assertThat(one("WP_REGISTER", "BLR")).isEqualTo("WP-BLR-00001001-11-2026");
        assertThat(one("WS_CONNECTION", "BLR")).isEqualTo("WS-BLR-1002-11-2026");
    }

    @Test
    void pgrComplaintUsesOneCounterSharedByAllTenantsSoStringsStayUnique() {
        assertThat(one("PGR_COMPLAINT", "BLR")).isEqualTo("PGR-00000001");
        assertThat(one("PGR_COMPLAINT", "PUN")).isEqualTo("PGR-00000002");
        assertThat(one("PGR_COMPLAINT", "tn.chennai")).isEqualTo("PGR-00000003");
        assertThat(jdbc.queryForList("SELECT tenant_id FROM counters WHERE template_id='PGR_COMPLAINT'", String.class)).containsExactly("*");
        // each issued ID still records the tenant that requested it
        assertThat(jdbc.queryForList("SELECT tenant_id FROM issued_ids WHERE template_id='PGR_COMPLAINT' ORDER BY sequence_value", String.class))
                .containsExactly("ka.bengaluru", "mh.pune", "tn.chennai");
    }

    // ------------------------------------------------------------ errors

    @Test
    void errorsAreMeaningful() {
        assertThat(issueOne("NOPE", "BLR").status()).isEqualTo(404);
        assertThat(issueOne("WS_CONNECTION", "ZZZ").status()).isEqualTo(404);
        jdbc.update("UPDATE cities SET active = FALSE WHERE tenant_id = 'ka.mysuru'");
        AdminClient.Resp inactive = issueOne("BPA_PERMIT", "MYS");
        assertThat(inactive.status()).isEqualTo(422);
        assertThat(inactive.json().get("message").asText()).contains("inactive");
        for (Object bad : new Object[] {0, 1001, -3, "x"}) {
            assertThat(http.jsonWithoutCsrf("POST", "/api/v1/ids/bulk", body("templateId", "TL_LICENSE", "city", "BLR", "count", bad)).status())
                    .as("count " + bad).isEqualTo(400);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM counters", Integer.class) + jdbc.queryForObject("SELECT count(*) FROM named_counters", Integer.class))
                .as("failed requests consumed no counters").isZero();
    }

    @Test
    void exhaustedSequenceIs409AndIssuesNothing() {
        jdbc.update("INSERT INTO named_counters (counter_name, tenant_id, next_value) VALUES ('SEQ_WS', 'tn.madurai', 9999)");
        assertThat(one("WS_CONNECTION", "MDU")).isEqualTo("WS-MDU-9999-11-2026");
        AdminClient.Resp r = issueOne("WS_CONNECTION", "MDU");
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.json().get("message").asText()).contains("SEQ_WS");
    }

    @Test
    void recentIdsIncludeAllTemplates() {
        for (String t : List.of("PG_RECEIPT", "TL_LICENSE", "PGR_COMPLAINT", "WS_CONNECTION", "BPA_PERMIT", "EST_ALLOTMENT")) {
            one(t, "BLR");
        }
        JsonNode recent = http.get("/api/v1/ids/recent").json();
        assertThat(recent.findValuesAsText("templateId")).containsExactly(
                "EST_ALLOTMENT", "BPA_PERMIT", "WS_CONNECTION", "PGR_COMPLAINT", "TL_LICENSE", "PG_RECEIPT");
    }
}
