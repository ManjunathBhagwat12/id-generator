package com.egov.idgenerator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
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

/**
 * Analytics endpoint against PostgreSQL. Fixed clock: 2026-11-18 10:00 IST, so 7d = 12..18 Nov, 30d = 20 Oct..18 Nov.
 * Rows are inserted with explicit created_at so day bucketing (incl. the IST midnight boundary) is checked exactly.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "idgen.admin.username=testadmin", "idgen.admin.password=S3cret-pass!"})
@Import(FixedClockConfig.class)
class AnalyticsIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    AdminClient admin;
    int seq;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE counters, named_counters, issued_ids");
        admin = new AdminClient("http://localhost:" + port);
        assertThat(admin.login("testadmin", "S3cret-pass!").status()).isEqualTo(200);
    }

    /** Inserts n issued IDs for a template at an exact instant (UTC ISO string). */
    void issued(String template, int n, String utcInstant) {
        for (int i = 0; i < n; i++) {
            seq++;
            jdbc.update("INSERT INTO issued_ids (template_id, tenant_id, sequence_value, generated_id, created_at) "
                    + "VALUES (?, 'ka.bengaluru', ?, ?, ?::timestamptz)", template, seq, "T-" + seq, utcInstant);
        }
    }

    JsonNode get(String q) {
        AdminClient.Resp r = admin.get("/api/v1/admin/analytics" + q);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return r.json();
    }

    @Test
    void requiresAdminLogin() {
        AdminClient anon = new AdminClient("http://localhost:" + port);
        assertThat(anon.get("/api/v1/admin/analytics").status()).isEqualTo(401);
        assertThat(anon.get("/api/v1/admin/analytics?period=30d").status()).isEqualTo(401);
    }

    @Test
    void invalidPeriodIs400() {
        for (String p : List.of("1d", "90d", "x", "7")) {
            assertThat(admin.get("/api/v1/admin/analytics?period=" + p).status()).as(p).isEqualTo(400);
        }
    }

    @Test
    void emptyDatasetReturnsZeroFilledSeries() {
        JsonNode a = get("?period=7d");
        assertThat(a.get("totalIssued").asLong()).isZero();
        assertThat(a.get("issuedInPeriod").asLong()).isZero();
        assertThat(a.get("daily")).hasSize(7);
        a.get("daily").forEach(d -> assertThat(d.get("count").asLong()).isZero());
        assertThat(a.get("byTemplate").size()).isGreaterThanOrEqualTo(6);
        a.get("byTemplate").forEach(t -> assertThat(t.get("count").asLong()).isZero());
        assertThat(get("?period=30d").get("daily")).hasSize(30);
    }

    @Test
    void periodBoundsAndDefault() {
        JsonNode a = get("");
        assertThat(a.get("period").asText()).isEqualTo("7d");
        assertThat(a.get("timezone").asText()).isEqualTo("Asia/Kolkata");
        assertThat(a.get("from").asText()).isEqualTo("2026-11-12");
        assertThat(a.get("to").asText()).isEqualTo("2026-11-18");
        assertThat(a.get("daily").get(0).get("date").asText()).isEqualTo("2026-11-12");
        assertThat(a.get("daily").get(6).get("date").asText()).isEqualTo("2026-11-18");
        JsonNode m = get("?period=30d");
        assertThat(m.get("from").asText()).isEqualTo("2026-10-20");
    }

    @Test
    void countsPerDayInBusinessTimezoneAndPerTemplate() {
        issued("PG_RECEIPT", 3, "2026-11-18T03:00:00Z");      // 18 Nov 08:30 IST
        issued("PG_RECEIPT", 2, "2026-11-17T19:00:00Z");      // 18 Nov 00:30 IST -> counts on the 18th, not the 17th
        issued("WS_CONNECTION", 4, "2026-11-17T18:00:00Z");   // 17 Nov 23:30 IST -> the 17th
        issued("TL_LICENSE", 1, "2026-11-12T00:00:00Z");      // 12 Nov 05:30 IST -> first day of 7d
        issued("TL_LICENSE", 5, "2026-11-11T18:29:59Z");      // 11 Nov 23:59:59 IST -> outside 7d, inside 30d
        issued("EST_ALLOTMENT", 7, "2026-10-01T06:00:00Z");   // outside both periods, still in the all-time total

        JsonNode a = get("?period=7d");
        assertThat(a.get("totalIssued").asLong()).isEqualTo(22);
        assertThat(a.get("issuedInPeriod").asLong()).isEqualTo(10);
        List<Long> counts = new ArrayList<>();
        a.get("daily").forEach(d -> counts.add(d.get("count").asLong()));
        assertThat(counts).containsExactly(1L, 0L, 0L, 0L, 0L, 4L, 5L);   // 12,13,14,15,16,17,18 Nov

        JsonNode bt = a.get("byTemplate");
        assertThat(bt.get(0).get("templateId").asText()).isEqualTo("PG_RECEIPT");
        assertThat(bt.get(0).get("count").asLong()).isEqualTo(5);
        assertThat(bt.get(0).get("name").asText()).isEqualTo("Payment receipt number");
        assertThat(bt.get(1).get("templateId").asText()).isEqualTo("WS_CONNECTION");
        assertThat(bt.get(2).get("templateId").asText()).isEqualTo("TL_LICENSE");
        assertThat(bt.get(2).get("count").asLong()).isEqualTo(1);
        long previous = Long.MAX_VALUE;
        for (JsonNode t : bt) {   // sorted highest first, every template present
            assertThat(t.get("count").asLong()).isLessThanOrEqualTo(previous);
            previous = t.get("count").asLong();
        }
        assertThat(bt.findValuesAsText("templateId")).contains("EST_ALLOTMENT", "BPA_PERMIT", "PGR_COMPLAINT");

        JsonNode m = get("?period=30d");
        assertThat(m.get("issuedInPeriod").asLong()).isEqualTo(15);       // + the 5 on 11 Nov
        assertThat(m.get("daily").get(22).get("date").asText()).isEqualTo("2026-11-11");
        assertThat(m.get("daily").get(22).get("count").asLong()).isEqualTo(5);
        assertThat(m.get("byTemplate").get(0).get("templateId").asText()).isEqualTo("TL_LICENSE"); // 6 in 30d
    }

    @Test
    void analyticsNeverChangesCounters() {
        issued("PG_RECEIPT", 2, "2026-11-18T03:00:00Z");
        Integer before = jdbc.queryForObject("SELECT count(*) FROM counters", Integer.class)
                + jdbc.queryForObject("SELECT count(*) FROM named_counters", Integer.class);
        get("?period=7d");
        get("?period=30d");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM counters", Integer.class)
                + jdbc.queryForObject("SELECT count(*) FROM named_counters", Integer.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM issued_ids", Integer.class)).isEqualTo(2);
    }
}
