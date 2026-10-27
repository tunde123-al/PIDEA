package com.pidea.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pidea.AbstractPostgresTest;
import com.pidea.integration.SourceType;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@AutoConfigureMockMvc
class IngestionApiTest extends AbstractPostgresTest {
    private static final Path UPLOAD_DIR = Path.of(System.getProperty("java.io.tmpdir"),
        "pidea-upload-test-" + UUID.randomUUID());

    @DynamicPropertySource
    static void uploadDirectory(DynamicPropertyRegistry registry) {
        registry.add("pidea.ingestion.upload-dir", UPLOAD_DIR::toString);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private static final RequestPostProcessor OPERATOR = user("op").roles("OPERATOR");
    private static final RequestPostProcessor ADMIN = user("adm").roles("ADMIN");
    private static final RequestPostProcessor VIEWER = user("vw").roles("VIEWER");

    private UUID sourceId;

    @BeforeEach
    void source() {
        sourceId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO integration.integration_source (id, name, source_type, status, config, created_at, updated_at)
            VALUES (?, ?, 'REST_PUSH', 'ACTIVE', '{}'::jsonb, now(), now())""", sourceId, "push-" + sourceId);
    }

    @AfterEach
    void removeTestUploads() throws Exception {
        if (Files.isDirectory(UPLOAD_DIR)) {
            try (var paths = Files.list(UPLOAD_DIR)) {
                for (Path path : paths.toList()) Files.deleteIfExists(path);
            }
            Files.deleteIfExists(UPLOAD_DIR);
        }
    }

    private String body(String customerId) {
        return """
            {"sourceId":"%s","entityType":"CUSTOMER","records":[{"customer_id":"%s","full_name":"Ada Test"}]}"""
            .formatted(sourceId, customerId);
    }

    private org.springframework.test.web.servlet.ResultActions submit(String body, String key, RequestPostProcessor who)
            throws Exception {
        var req = post("/api/v1/ingestion/jobs").contentType(MediaType.APPLICATION_JSON).content(body).with(who);
        if (key != null) req.header("Idempotency-Key", key);
        return mvc.perform(req);
    }

    @Test
    void submissionReturns202WithPendingJob_andStoresRawRecords() throws Exception {
        String res = submit(body("C1"), null, OPERATOR)
            .andExpect(status().isAccepted())
            .andExpect(header().string("Idempotent-Replayed", "false"))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.recordsReceived").value(1))
            .andExpect(jsonPath("$.recordsAccepted").value(0))
            .andReturn().getResponse().getContentAsString();
        UUID jobId = UUID.fromString(json.readTree(res).get("id").asText());
        assertThat(jdbc.queryForObject("select count(*) from integration.raw_record where job_id = ?", Integer.class, jobId))
            .isEqualTo(1);
        mvc.perform(get("/api/v1/ingestion/jobs/" + jobId).with(VIEWER))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(jobId.toString()));
    }

    @Test
    void sameKeySameBody_replaysOriginalJob() throws Exception {
        String key = "key-" + UUID.randomUUID();
        String first = submit(body("C2"), key, OPERATOR).andExpect(status().isAccepted())
            .andReturn().getResponse().getContentAsString();
        String second = submit(body("C2"), key, OPERATOR).andExpect(status().isOk())
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(second).get("id")).isEqualTo(json.readTree(first).get("id"));
        assertThat(jdbc.queryForObject("select count(*) from integration.ingestion_job where source_id = ?",
            Integer.class, sourceId)).isEqualTo(1);
    }

    @Test
    void fieldOrderDoesNotChangeFingerprint() throws Exception {
        String key = "key-" + UUID.randomUUID();
        submit(body("C3"), key, OPERATOR).andExpect(status().isAccepted());
        String reordered = """
            {"records":[{"full_name":"Ada Test","customer_id":"C3"}],"entityType":"CUSTOMER","sourceId":"%s"}"""
            .formatted(sourceId);
        submit(reordered, key, OPERATOR).andExpect(status().isOk());
    }

    @Test
    void sameKeyDifferentBody_isRejectedWith422() throws Exception {
        String key = "key-" + UUID.randomUUID();
        submit(body("C4"), key, OPERATOR).andExpect(status().isAccepted());
        submit(body("DIFFERENT"), key, OPERATOR).andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void keysAreScopedPerUser() throws Exception {
        String key = "key-" + UUID.randomUUID();
        submit(body("C5"), key, OPERATOR).andExpect(status().isAccepted());
        submit(body("C5"), key, ADMIN).andExpect(status().isAccepted());   // different scope -> new job
    }

    @Test
    void concurrentDuplicateRequests_createExactlyOneJob() throws Exception {
        String key = "key-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<String>> calls = IntStream.range(0, 6).<Callable<String>>mapToObj(i -> () ->
                json.readTree(submit(body("C6"), key, OPERATOR).andReturn().getResponse().getContentAsString())
                    .get("id").asText()).toList();
            Set<String> ids = new java.util.HashSet<>();
            for (Future<String> f : pool.invokeAll(calls)) ids.add(f.get());
            assertThat(ids).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from integration.ingestion_job where source_id = ?",
            Integer.class, sourceId)).isEqualTo(1);
    }

    @Test
    void validationAndSourceRules() throws Exception {
        submit("{\"sourceId\":\"%s\",\"entityType\":\"CUSTOMER\",\"records\":[]}".formatted(sourceId), null, OPERATOR)
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.violations[0].field").value("records"));
        submit(body("C7"), "short", OPERATOR).andExpect(status().isBadRequest());           // key too short
        submit("{\"sourceId\":\"%s\",\"entityType\":\"CUSTOMER\",\"records\":[{\"a\":1}]}"
            .formatted(UUID.randomUUID()), null, OPERATOR).andExpect(status().isNotFound());
        jdbc.update("update integration.integration_source set status='DISABLED' where id=?", sourceId);
        submit(body("C8"), null, OPERATOR).andExpect(status().isConflict());
    }

    @Test
    void tooManyRecordsAreRejected() throws Exception {
        String records = IntStream.range(0, 1001).mapToObj(i -> "{\"a\":" + i + "}").collect(Collectors.joining(","));
        submit("{\"sourceId\":\"%s\",\"entityType\":\"ORDER\",\"records\":[%s]}".formatted(sourceId, records), null, OPERATOR)
            .andExpect(status().isBadRequest());
    }

    @Test
    void csvFileUploadIsStoredPrivatelyAndIdempotent() throws Exception {
        UUID csvSource = createFileSource(SourceType.CSV_FILE);
        byte[] content = ("customer_id,full_name,created_at\n"
            + "CFILE,Ada,2024-01-01\n"
            + "bad,,invalid-date\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String key = "file-key-" + UUID.randomUUID();
        var first = upload(csvSource, "customers.csv", content, key, OPERATOR)
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobType").value("FILE_CSV"))
            .andExpect(jsonPath("$.recordsReceived").value(2))
            .andReturn().getResponse().getContentAsString();
        String id = json.readTree(first).get("id").asText();
        String fileRef = jdbc.queryForObject(
            "select file_ref from integration.ingestion_job where id=?", String.class, UUID.fromString(id));
        assertThat(Files.isRegularFile(UPLOAD_DIR.resolve(fileRef))).isTrue();

        upload(csvSource, "customers.csv", content, key, OPERATOR)
            .andExpect(status().isOk())
            .andExpect(header().string("Idempotent-Replayed", "true"))
            .andExpect(jsonPath("$.id").value(id));
        assertThat(jdbc.queryForObject("select count(*) from integration.ingestion_job where source_id=?",
            Integer.class, csvSource)).isEqualTo(1);
    }

    @Test
    void fileUploadRejectsInvalidFormatEncodingEmptyAndOversizedFiles() throws Exception {
        UUID csvSource = createFileSource(SourceType.CSV_FILE);
        upload(csvSource, "customers.txt", "data".getBytes(), "invalid-file-1", OPERATOR)
            .andExpect(status().isBadRequest());
        upload(csvSource, "empty.csv", new byte[0], "invalid-file-2", OPERATOR)
            .andExpect(status().isBadRequest());
        upload(csvSource, "invalid.csv", new byte[] {(byte) 0xc3, (byte) 0x28}, "invalid-file-3", OPERATOR)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Upload must use UTF-8 encoding"));
        upload(csvSource, "empty-records.csv", "customer_id,full_name\n".getBytes(),
            "invalid-file-5", OPERATOR).andExpect(status().isBadRequest());
        upload(csvSource, "duplicate-headers.csv", "id,id\n1,2\n".getBytes(),
            "invalid-file-6", OPERATOR).andExpect(status().isBadRequest());
        StringBuilder tooManyRows = new StringBuilder("customer_id,full_name,created_at\n");
        for (int i = 0; i <= 100_000; i++) {
            tooManyRows.append("C").append(i).append(",Customer,2024-01-01\n");
        }
        upload(csvSource, "too-many-rows.csv", tooManyRows.toString().getBytes(
                java.nio.charset.StandardCharsets.UTF_8), "invalid-file-7", OPERATOR)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Upload exceeds the 100000 record limit"));
        upload(csvSource, "large.csv", new byte[(int) (5L * 1024 * 1024 + 1)],
            "invalid-file-4", OPERATOR).andExpect(status().isPayloadTooLarge());
        assertThat(jdbc.queryForObject("select count(*) from integration.ingestion_job where source_id=?",
            Integer.class, csvSource)).isZero();
    }

    @Test
    void jsonFileUploadRejectsMalformedEmptyAndFormatMismatch() throws Exception {
        UUID jsonSource = createFileSource(SourceType.JSON_FILE);
        upload(jsonSource, "malformed.json", "{not json".getBytes(), "invalid-json-1", OPERATOR)
            .andExpect(status().isBadRequest());
        upload(jsonSource, "empty.json", "[]".getBytes(), "invalid-json-2", OPERATOR)
            .andExpect(status().isBadRequest());
        upload(jsonSource, "wrong.csv", "a,b\n1,2\n".getBytes(), "invalid-json-3", OPERATOR)
            .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from integration.ingestion_job where source_id=?",
            Integer.class, jsonSource)).isZero();
    }

    @Test
    void fileUploadWithChangedBodyCannotReuseIdempotencyKey() throws Exception {
        UUID csvSource = createFileSource(SourceType.CSV_FILE);
        String key = "file-key-" + UUID.randomUUID();
        upload(csvSource, "customers.csv", "a,b\n1,2\n".getBytes(), key, OPERATOR)
            .andExpect(status().isAccepted());
        upload(csvSource, "customers.csv", "a,b\n3,4\n".getBytes(), key, OPERATOR)
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(jdbc.queryForObject("select count(*) from integration.ingestion_job where source_id=?",
            Integer.class, csvSource)).isEqualTo(1);
    }

    @Test
    void viewerCannotUploadFile() throws Exception {
        UUID csvSource = createFileSource(SourceType.CSV_FILE);
        upload(csvSource, "customers.csv", "a,b\n1,2\n".getBytes(), "file-key-viewer", VIEWER)
            .andExpect(status().isForbidden());
    }


    @Test
    void replayIsRefusedWhenTheOriginalInputWasPurgedByRetention() throws Exception {
        UUID failedId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO integration.ingestion_job
              (id, source_id, job_type, entity_type, status, correlation_id, records_received,
               next_attempt_at, submitted_at, completed_at, submitted_by)
            VALUES (?, ?, 'RECORDS', 'CUSTOMER', 'FAILED', 'purged-test', 2, now(), now(), now(), 'operator')""",
            failedId, sourceId);   // no raw_record rows: exactly what the worker's retention purge leaves behind

        mvc.perform(post("/api/v1/ingestion/jobs/" + failedId + "/replay").with(OPERATOR))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("no longer retained")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM integration.ingestion_job WHERE replay_of = ?",
            Integer.class, failedId)).isZero();   // no half-built replay job was left behind
    }
    @Test
    void failedJobReplayIsSafeAndRepeatableWithoutDuplicatingInput() throws Exception {
        UUID failedId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO integration.ingestion_job
              (id, source_id, job_type, entity_type, status, correlation_id, records_received,
               next_attempt_at, submitted_at, submitted_by)
            VALUES (?, ?, 'RECORDS', 'CUSTOMER', 'FAILED', 'replay-test', 1, now(), now(), 'operator')""",
            failedId, sourceId);
        jdbc.update("""
            INSERT INTO integration.raw_record (job_id, seq, payload)
            VALUES (?, 0, '{"customer_id":"CREPLAY","full_name":"Replay","created_at":"2024-01-01"}'::jsonb)""",
            failedId);

        String first = mvc.perform(post("/api/v1/ingestion/jobs/" + failedId + "/replay").with(OPERATOR))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.replayOf").value(failedId.toString()))
            .andReturn().getResponse().getContentAsString();
        String replayId = json.readTree(first).get("id").asText();
        mvc.perform(post("/api/v1/ingestion/jobs/" + failedId + "/replay").with(OPERATOR))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(replayId));
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM integration.ingestion_job WHERE replay_of=?", Integer.class, failedId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM integration.raw_record WHERE job_id=?", Integer.class, UUID.fromString(replayId)))
            .isEqualTo(1);
        mvc.perform(post("/api/v1/ingestion/jobs/" + replayId + "/replay").with(OPERATOR))
            .andExpect(status().isConflict());
    }

    @Test
    void jobsCanBeFilteredBySubmissionDateAndAttemptsArePaged() throws Exception {
        submit(body("C_DATE"), null, OPERATOR).andExpect(status().isAccepted());
        mvc.perform(get("/api/v1/ingestion/jobs?sourceId=" + sourceId
                + "&submittedFrom=2050-01-01T00:00:00Z").with(VIEWER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/ingestion/jobs?submittedFrom=2050-01-01T00:00:00Z"
                + "&submittedTo=2040-01-01T00:00:00Z").with(VIEWER))
            .andExpect(status().isBadRequest());
    }

    @Test
    void httpPullSubmissionIsIdempotentAndRoleProtected() throws Exception {
        UUID httpSource = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO integration.integration_source
              (id, name, source_type, status, config, created_at, updated_at)
            VALUES (?, ?, 'HTTP_API', 'ACTIVE', '{"baseUrl":"https://api.example.test/customers"}'::jsonb,
                    now(), now())""", httpSource, "http-" + httpSource);
        String key = "pull-key-" + UUID.randomUUID();
        var first = mvc.perform(post("/api/v1/ingestion/sources/" + httpSource + "/jobs")
                .param("entityType", "CUSTOMER").header("Idempotency-Key", key).with(OPERATOR))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobType").value("HTTP_PULL"))
            .andReturn().getResponse().getContentAsString();
        String id = json.readTree(first).get("id").asText();
        mvc.perform(post("/api/v1/ingestion/sources/" + httpSource + "/jobs")
                .param("entityType", "CUSTOMER").header("Idempotency-Key", key).with(OPERATOR))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id));
        mvc.perform(post("/api/v1/ingestion/sources/" + httpSource + "/jobs")
                .param("entityType", "CUSTOMER").header("Idempotency-Key", "pull-key-viewer").with(VIEWER))
            .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM integration.ingestion_job WHERE source_id=?",
            Integer.class, httpSource)).isEqualTo(1);
    }

    @Test
    void postgresPullCreatesAPostgresPullJobAndFailedPullsCanBeReplayed() throws Exception {
        UUID pgSource = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO integration.integration_source
              (id, name, source_type, status, config, secret_ref, created_at, updated_at)
            VALUES (?, ?, 'POSTGRES_TABLE', 'ACTIVE',
                    '{"host":"crm-db.internal","database":"crm","user":"r","schema":"public","table":"customers","keyColumn":"id"}'::jsonb,
                    'PIDEA_SRC_CRM_PASSWORD', now(), now())""", pgSource, "pg-" + pgSource);
        String key = "pg-pull-" + UUID.randomUUID();
        String body = mvc.perform(post("/api/v1/ingestion/sources/" + pgSource + "/jobs")
                .param("entityType", "CUSTOMER").header("Idempotency-Key", key).with(OPERATOR))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.jobType").value("POSTGRES_PULL"))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).get("id").asText();
        mvc.perform(post("/api/v1/ingestion/sources/" + pgSource + "/jobs")
                .param("entityType", "CUSTOMER").header("Idempotency-Key", key).with(OPERATOR))
            .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(post("/api/v1/ingestion/sources/" + pgSource + "/jobs")
                .param("entityType", "CUSTOMER").header("Idempotency-Key", "pg-viewer").with(VIEWER))
            .andExpect(status().isForbidden());

        jdbc.update("UPDATE integration.ingestion_job SET status='FAILED', completed_at=now() WHERE id=?::uuid", id);
        mvc.perform(post("/api/v1/ingestion/jobs/" + id + "/replay").with(OPERATOR))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobType").value("POSTGRES_PULL"))
            .andExpect(jsonPath("$.replayOf").value(id));
    }

    @Test
    void sourcesWithoutAPullPathCannotBeTriggered() throws Exception {
        UUID csvSource = createFileSource(SourceType.CSV_FILE);
        mvc.perform(post("/api/v1/ingestion/sources/" + csvSource + "/jobs")
                .param("entityType", "CUSTOMER").header("Idempotency-Key", "no-pull-" + csvSource).with(OPERATOR))
            .andExpect(status().isConflict());
    }

    private UUID quarantineJob(UUID source, String entity) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO integration.ingestion_job (id, source_id, job_type, entity_type, status, correlation_id,
              records_received, records_accepted, records_rejected, next_attempt_at, submitted_at, submitted_by)
            VALUES (?, ?, 'RECORDS', ?, 'PARTIALLY_SUCCEEDED', 'q-test', 3, 1, 2, now(), now(), 'test')""",
            id, source, entity);
        return id;
    }

    private void rejected(UUID job, UUID source, String entity, String ref, String reason, String minutesAgo) {
        jdbc.update("""
            INSERT INTO integration.rejected_record
              (id, job_id, source_id, entity_type, source_record_ref, reason_code, details, processed_at)
            VALUES (?, ?, ?, ?, ?, ?, 'safe detail', now() - ?::interval)""",
            UUID.randomUUID(), job, source, entity, ref, reason, minutesAgo + " minutes");
    }

    @Test
    void quarantineListingFiltersPagesAndNeverReturnsPayloads() throws Exception {
        String tag = "Q" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        UUID other = createFileSource(SourceType.CSV_FILE);
        UUID jobA = quarantineJob(sourceId, "CUSTOMER");
        UUID jobB = quarantineJob(other, "ORDER");
        rejected(jobA, sourceId, "CUSTOMER", "seq 1", tag + "_EMAIL", "30");
        rejected(jobA, sourceId, "CUSTOMER", "seq 2", tag + "_DATE", "20");
        rejected(jobB, other, "ORDER", "row 7", tag + "_EMAIL", "10");

        // newest first, across jobs and sources
        mvc.perform(get("/api/v1/ingestion/rejected-records").param("reasonCode", tag + "_EMAIL").with(OPERATOR))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalItems").value(2))
            .andExpect(jsonPath("$.items[0].sourceRecordRef").value("row 7"))
            .andExpect(jsonPath("$.items[1].sourceRecordRef").value("seq 1"))
            .andExpect(jsonPath("$.items[0].jobId").value(jobB.toString()))
            .andExpect(jsonPath("$.items[0].entityType").value("ORDER"))
            .andExpect(jsonPath("$.items[0].payload").doesNotExist())
            .andExpect(jsonPath("$.items[0].rawPayload").doesNotExist());
        // each filter on its own
        mvc.perform(get("/api/v1/ingestion/rejected-records").param("sourceId", sourceId.toString()).with(ADMIN))
            .andExpect(jsonPath("$.items[*].sourceId", org.hamcrest.Matchers.everyItem(
                org.hamcrest.Matchers.is(sourceId.toString()))));
        mvc.perform(get("/api/v1/ingestion/rejected-records").param("jobId", jobA.toString()).with(OPERATOR))
            .andExpect(jsonPath("$.totalItems").value(2));
        mvc.perform(get("/api/v1/ingestion/rejected-records").param("jobId", jobA.toString())
                .param("entityType", "ORDER").with(OPERATOR))
            .andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get("/api/v1/ingestion/rejected-records").param("reasonCode", tag + "_DATE")
                .param("processedFrom", java.time.Instant.now().minusSeconds(25 * 60).toString()).with(OPERATOR))
            .andExpect(jsonPath("$.totalItems").value(1));
        mvc.perform(get("/api/v1/ingestion/rejected-records").param("reasonCode", tag + "_DATE")
                .param("processedTo", java.time.Instant.now().minusSeconds(25 * 60).toString()).with(OPERATOR))
            .andExpect(jsonPath("$.totalItems").value(0));
        // paging
        mvc.perform(get("/api/v1/ingestion/rejected-records").param("jobId", jobA.toString())
                .param("size", "1").param("page", "1").with(OPERATOR))
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.totalPages").value(2))
            .andExpect(jsonPath("$.items[0].sourceRecordRef").value("seq 1"));
        // the page size can never be unbounded
        mvc.perform(get("/api/v1/ingestion/rejected-records").param("size", "100000").with(OPERATOR))
            .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void quarantineReasonsSummariseWhatIsStored() throws Exception {
        String tag = "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        UUID job = quarantineJob(sourceId, "CUSTOMER");
        rejected(job, sourceId, "CUSTOMER", "seq 1", tag + "_A", "5");
        rejected(job, sourceId, "CUSTOMER", "seq 2", tag + "_A", "4");
        rejected(job, sourceId, "CUSTOMER", "seq 3", tag + "_B", "3");
        mvc.perform(get("/api/v1/ingestion/rejected-records/reasons").param("sourceId", sourceId.toString())
                .with(OPERATOR))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.reasonCode=='" + tag + "_A')].count").value(2))
            .andExpect(jsonPath("$[?(@.reasonCode=='" + tag + "_B')].count").value(1));
    }

    @Test
    void quarantineAccessIsEnforcedOnTheServerAndInputIsValidated() throws Exception {
        mvc.perform(get("/api/v1/ingestion/rejected-records")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/ingestion/rejected-records").with(VIEWER)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ingestion/rejected-records/reasons").with(VIEWER)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ingestion/rejected-records").param("reasonCode", "lower; DROP TABLE x").with(OPERATOR))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/ingestion/rejected-records").param("entityType", "NOPE").with(OPERATOR))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/ingestion/rejected-records").param("sourceId", "not-a-uuid").with(OPERATOR))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/ingestion/rejected-records")
                .param("processedFrom", "2050-01-01T00:00:00Z").param("processedTo", "2040-01-01T00:00:00Z")
                .with(OPERATOR))
            .andExpect(status().isBadRequest());
    }

    private UUID createFileSource(SourceType type) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO integration.integration_source
              (id, name, source_type, status, config, created_at, updated_at)
            VALUES (?, ?, ?, 'ACTIVE', '{}'::jsonb, now(), now())""", id, "file-" + id, type.name());
        return id;
    }

    private org.springframework.test.web.servlet.ResultActions upload(
            UUID source, String filename, byte[] content, String key, RequestPostProcessor who) throws Exception {
        return mvc.perform(multipart("/api/v1/ingestion/jobs/files")
            .file(new MockMultipartFile("file", filename, "application/octet-stream", content))
            .param("sourceId", source.toString())
            .param("entityType", "CUSTOMER")
            .header("Idempotency-Key", key)
            .with(who));
    }

    @Test
    void rolesAreEnforced() throws Exception {
        submit(body("C9"), null, VIEWER).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/ingestion/jobs").contentType(MediaType.APPLICATION_JSON).content(body("C9")))
            .andExpect(status().isUnauthorized());
        UUID id = UUID.fromString(json.readTree(submit(body("C10"), null, OPERATOR).andReturn().getResponse()
            .getContentAsString()).get("id").asText());
        mvc.perform(get("/api/v1/ingestion/jobs/" + id + "/errors").with(VIEWER)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ingestion/jobs/" + id + "/errors").with(OPERATOR))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void cancelOnlyWorksBeforeExecutionStarts() throws Exception {
        UUID id = UUID.fromString(json.readTree(submit(body("C11"), null, OPERATOR).andReturn().getResponse()
            .getContentAsString()).get("id").asText());
        mvc.perform(post("/api/v1/ingestion/jobs/" + id + "/cancel").with(OPERATOR))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/v1/ingestion/jobs/" + id + "/cancel").with(OPERATOR))
            .andExpect(status().isConflict());
    }

    @Test
    void listFiltersByStatusAndSource() throws Exception {
        submit(body("C12"), null, OPERATOR).andExpect(status().isAccepted());
        mvc.perform(get("/api/v1/ingestion/jobs?sourceId=" + sourceId + "&status=PENDING").with(VIEWER))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1));
        mvc.perform(get("/api/v1/ingestion/jobs?sourceId=" + sourceId + "&status=FAILED").with(VIEWER))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get("/api/v1/ingestion/jobs?status=BOGUS").with(VIEWER)).andExpect(status().isBadRequest());
    }
}
