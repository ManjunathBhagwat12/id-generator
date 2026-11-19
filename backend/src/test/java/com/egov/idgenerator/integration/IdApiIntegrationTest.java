package com.egov.idgenerator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Full stack over HTTP against a real PostgreSQL (Testcontainers). */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FixedClockConfig.class)
class IdApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanState() {
        jdbc.execute("TRUNCATE counters, issued_ids");
    }

    private ResponseEntity<Map> post(String path, Map<String, Object> body) {
        return http.postForEntity(path, body, Map.class);
    }

    @Test
    void issuesSingleId() {
        ResponseEntity<Map> r = post("/api/v1/ids/issue", Map.of("templateId", "PG_RECEIPT", "city", "BLR"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("id", "PG-BLR-2026-27-000001");

        // Second call continues the sequence.
        assertThat(post("/api/v1/ids/issue", Map.of("templateId", "PG_RECEIPT", "city", "BLR")).getBody())
                .containsEntry("id", "PG-BLR-2026-27-000002");
    }

    @Test
    void tenantIdAndCityCodeShareOneCounter() {
        post("/api/v1/ids/issue", Map.of("templateId", "PG_RECEIPT", "city", "BLR"));
        ResponseEntity<Map> r = post("/api/v1/ids/issue", Map.of("templateId", "PG_RECEIPT", "city", "ka.bengaluru"));
        assertThat(r.getBody()).containsEntry("id", "PG-BLR-2026-27-000002");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM counters", Integer.class)).isEqualTo(1);
    }

    @Test
    void countersAreScopedPerCity() {
        post("/api/v1/ids/bulk", Map.of("templateId", "PG_RECEIPT", "city", "BLR", "count", 3));
        ResponseEntity<Map> r = post("/api/v1/ids/issue", Map.of("templateId", "PG_RECEIPT", "city", "PUN"));
        assertThat(r.getBody()).containsEntry("id", "PG-PUN-2026-27-000001");
    }

    @Test
    void issuesBulkIds() {
        ResponseEntity<Map> r = post("/api/v1/ids/bulk", Map.of("templateId", "PG_RECEIPT", "city", "BLR", "count", 5));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).containsEntry("count", 5);
        assertThat((List<String>) r.getBody().get("ids")).containsExactly(
                "PG-BLR-2026-27-000001", "PG-BLR-2026-27-000002", "PG-BLR-2026-27-000003",
                "PG-BLR-2026-27-000004", "PG-BLR-2026-27-000005");

        // Next allocation starts after the bulk range.
        assertThat(post("/api/v1/ids/issue", Map.of("templateId", "PG_RECEIPT", "city", "BLR")).getBody())
                .containsEntry("id", "PG-BLR-2026-27-000006");
    }

    @Test
    void unknownTemplateIs404() {
        ResponseEntity<Map> r = post("/api/v1/ids/issue", Map.of("templateId", "NOPE", "city", "BLR"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).containsEntry("status", 404);
    }

    @Test
    void templateWithUnsupportedPlaceholderIs422() {
        // Not creatable through the API (validation rejects it); inserted directly to prove the issuance-time guard.
        jdbc.update("INSERT INTO templates (template_id, name, format, created_by) VALUES "
                + "('BROKEN_T', 'Broken', 'B-[CITY]-[FOO]-[SEQ:3]', 'test') ON CONFLICT DO NOTHING");
        ResponseEntity<Map> r = post("/api/v1/ids/issue", Map.of("templateId", "BROKEN_T", "city", "BLR"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(r.getBody().get("message").toString()).contains("[FOO]");
    }

    @Test
    void unknownCityIs404AndConsumesNoSequence() {
        ResponseEntity<Map> r = post("/api/v1/ids/issue", Map.of("templateId", "PG_RECEIPT", "city", "ZZZ"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM counters", Integer.class)).isZero();
    }

    @Test
    void invalidCountIs400() {
        for (Object bad : new Object[] {0, -1, 1001, "abc", 2.5}) {
            ResponseEntity<Map> r = post("/api/v1/ids/bulk",
                    Map.of("templateId", "PG_RECEIPT", "city", "BLR", "count", bad));
            assertThat(r.getStatusCode()).as("count=%s", bad).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(post("/api/v1/ids/bulk", Map.of("templateId", "PG_RECEIPT", "city", "BLR")).getStatusCode())
                .as("missing count").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM counters", Integer.class)).isZero();
    }

    @Test
    void missingFieldsAre400() {
        assertThat(post("/api/v1/ids/issue", Map.of("templateId", "PG_RECEIPT")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void errorBodyNeverLeaksInternals() {
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<String> r = http.postForEntity("/api/v1/ids/issue",
                new org.springframework.http.HttpEntity<>("{not json", h), String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).doesNotContain("Exception").doesNotContain("at com.");
    }

    @Test
    void listsAllSixSuppliedTemplatesAsEnabled() {
        ResponseEntity<List> r = http.getForEntity("/api/v1/templates", List.class);
        List<Map<String, Object>> templates = r.getBody();
        assertThat(templates).filteredOn(t -> Boolean.TRUE.equals(t.get("enabled"))).extracting(t -> t.get("id"))
                .contains("PG_RECEIPT", "TL_LICENSE", "PGR_COMPLAINT", "WS_CONNECTION", "BPA_PERMIT", "EST_ALLOTMENT");
        assertThat(templates).allSatisfy(t -> assertThat(t).containsKeys("name", "format", "counterScope"));
    }

    @Test
    void listsCities() {
        ResponseEntity<List> r = http.getForEntity("/api/v1/cities", List.class);
        assertThat(r.getBody()).hasSize(10);
    }

    @Test
    void recentReturnsLatest20NewestFirst() {
        post("/api/v1/ids/bulk", Map.of("templateId", "PG_RECEIPT", "city", "BLR", "count", 25));
        ResponseEntity<List> r = http.getForEntity("/api/v1/ids/recent", List.class);
        List<Map<String, Object>> recent = r.getBody();
        assertThat(recent).hasSize(20);
        assertThat(recent.get(0)).containsEntry("id", "PG-BLR-2026-27-000025");
        assertThat(recent.get(19)).containsEntry("id", "PG-BLR-2026-27-000006");
    }

    @Test
    void uniqueConstraintRejectsDuplicateGeneratedId() {
        post("/api/v1/ids/issue", Map.of("templateId", "PG_RECEIPT", "city", "BLR"));
        // Simulate a corrupted/rewound counter: the DB must refuse to issue a duplicate, not hand one out.
        jdbc.update("UPDATE counters SET next_value = 1");
        ResponseEntity<Map> r = post("/api/v1/ids/issue", Map.of("templateId", "PG_RECEIPT", "city", "BLR"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM issued_ids", Integer.class)).isEqualTo(1);
    }
}
