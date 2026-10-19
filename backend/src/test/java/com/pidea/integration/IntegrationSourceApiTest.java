package com.pidea.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pidea.AbstractPostgresTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class IntegrationSourceApiTest extends AbstractPostgresTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired IntegrationScheduleService schedules;
    @Autowired IntegrationScheduleRunner scheduleRunner;

    private String createBody(String name) {
        return """
            {"name":"%s","type":"CSV_FILE","config":{"delimiter":","},"secretRef":"CRM_API_KEY"}""".formatted(name);
    }

    private String create(String name) throws Exception {
        String body = mvc.perform(post("/api/v1/integration-sources").contentType(MediaType.APPLICATION_JSON)
                .content(createBody(name)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    @Test
    void anonymousIsUnauthorised() throws Exception {
        mvc.perform(get("/api/v1/integration-sources"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
            .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCreatesSource_responseHidesSecretReference_andAuditIsWritten() throws Exception {
        mvc.perform(post("/api/v1/integration-sources").contentType(MediaType.APPLICATION_JSON)
                .content(createBody("crm-customers")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name").value("crm-customers"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.secretConfigured").value(true))
            .andExpect(jsonPath("$.secretRef").doesNotExist())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(not(containsString("CRM_API_KEY"))));
        Integer audits = jdbc.queryForObject(
            "select count(*) from integration.audit_event where action='SOURCE_CREATED' and actor='user'", Integer.class);
        assertThat(audits).isGreaterThanOrEqualTo(1);
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotCreate_butCanList() throws Exception {
        mvc.perform(post("/api/v1/integration-sources").contentType(MediaType.APPLICATION_JSON)
                .content(createBody("viewer-attempt")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/integration-sources")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void invalidRequestsReturnStructuredErrors() throws Exception {
        mvc.perform(post("/api/v1/integration-sources").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Bad Name!\",\"type\":\"CSV_FILE\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.violations[0].field").value("name"));
        mvc.perform(post("/api/v1/integration-sources").contentType(MediaType.APPLICATION_JSON).content("{not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mvc.perform(post("/api/v1/integration-sources").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"leaky-source\",\"type\":\"CSV_FILE\",\"config\":{\"apiKey\":\"x\"}}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(containsString("looks like a credential")));
        mvc.perform(post("/api/v1/integration-sources").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"http-source\",\"type\":\"HTTP_API\",\"config\":{\"baseUrl\":\"http://169.254.169.254\"}}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void duplicateNameIsConflict() throws Exception {
        create("dup-source");
        mvc.perform(post("/api/v1/integration-sources").contentType(MediaType.APPLICATION_JSON)
                .content(createBody("dup-source")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void unknownIdIsNotFound_andBadIdIsBadRequest() throws Exception {
        mvc.perform(get("/api/v1/integration-sources/" + UUID.randomUUID())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/integration-sources/not-a-uuid")).andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorCanDisableButNotUpdateConfig() throws Exception {
        String id = createAsAdmin("op-source");
        mvc.perform(patch("/api/v1/integration-sources/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DISABLED\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"));
        mvc.perform(patch("/api/v1/integration-sources/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"config\":{\"a\":\"b\"}}"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/integration-sources/" + id + "/health"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.lastJobId").doesNotExist())
            .andExpect(jsonPath("$.failedJobsLast24h").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void httpApiScheduleIsPersistentAndConcurrentTicksEnqueueOnlyOnce() throws Exception {
        UUID sourceId = createHttpSource("scheduled-api");
        String response = mvc.perform(post("/api/v1/integration-schedules")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sourceId":"%s","entityType":"CUSTOMER","intervalSeconds":60}
                    """.formatted(sourceId)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.enabled").value(true))
            .andReturn().getResponse().getContentAsString();
        UUID scheduleId = UUID.fromString(json.readTree(response).get("id").asText());
        jdbc.update("UPDATE integration.integration_schedule SET next_run_at=? WHERE id=?",
            java.sql.Timestamp.from(Instant.now().minusSeconds(1)), scheduleId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { start.await(); scheduleRunner.enqueueDueSchedules(); return null; });
            var second = pool.submit(() -> { start.await(); scheduleRunner.enqueueDueSchedules(); return null; });
            start.countDown();
            first.get();
            second.get();
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM integration.ingestion_job WHERE schedule_id=?", Integer.class, scheduleId))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT job_type FROM integration.ingestion_job WHERE schedule_id=?", String.class, scheduleId))
            .isEqualTo("HTTP_PULL");
        assertThat(schedules.list(sourceId, 0, 10).items()).hasSize(1);
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCannotCreateSchedule() throws Exception {
        UUID sourceId = createHttpSource("schedule-viewer");
        mvc.perform(post("/api/v1/integration-schedules").contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"sourceId":"%s","entityType":"CUSTOMER","intervalSeconds":60}
                    """.formatted(sourceId)))
            .andExpect(status().isForbidden());
    }

    private UUID createHttpSource(String name) throws Exception {
        String body = """
            {"name":"%s","type":"HTTP_API","config":{"baseUrl":"https://api.example.test/customers"}}
            """.formatted(name);
        String response = mvc.perform(post("/api/v1/integration-sources")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(response).get("id").asText());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void listIsPagedAndSizeIsCapped() throws Exception {
        create("page-a");
        create("page-b");
        mvc.perform(get("/api/v1/integration-sources?page=0&size=1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.totalItems").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
        mvc.perform(get("/api/v1/integration-sources?size=100000"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(100));
    }

    private String createAsAdmin(String name) throws Exception {
        String body = mvc.perform(post("/api/v1/integration-sources").contentType(MediaType.APPLICATION_JSON)
                .content(createBody(name)).with(org.springframework.security.test.web.servlet.request
                    .SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode n = json.readTree(body);
        return n.get("id").asText();
    }
}
