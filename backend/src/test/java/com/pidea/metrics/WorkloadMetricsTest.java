package com.pidea.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pidea.AbstractPostgresTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The metrics must reflect REAL database state. The shared test database may hold rows from other test classes, so
 * every assertion compares the value before and after inserting known rows.
 */
@AutoConfigureMockMvc
@AutoConfigureObservability   // Spring Boot disables metric exporters (incl. /actuator/prometheus) in tests by default
class WorkloadMetricsTest extends AbstractPostgresTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry registry;
    @Autowired WorkloadMetrics metrics;

    private double gauge(String name, String... tags) {
        var search = registry.get(name);
        for (int i = 0; i < tags.length; i += 2) search = search.tag(tags[i], tags[i + 1]);
        return search.gauge().value();
    }

    private double counter(String name) {
        return registry.get(name).functionCounter().count();
    }

    private UUID source() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO integration.integration_source (id, name, source_type, status, config, created_at, updated_at)
            VALUES (?, ?, 'REST_PUSH', 'ACTIVE', '{}'::jsonb, now(), now())""", id, "metrics-" + id);
        return id;
    }

    private UUID job(UUID source, String status, String nextAttempt, String started, String completed,
                     int received, int accepted, int rejected, int duplicate, int retries) {   // null interval = NULL timestamp
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO integration.ingestion_job
              (id, source_id, job_type, entity_type, status, correlation_id, records_received, records_accepted,
               records_rejected, records_duplicate, retry_count, next_attempt_at, submitted_at, started_at,
               completed_at, submitted_by)
            VALUES (?, ?, 'RECORDS', 'CUSTOMER', ?, 'metrics-test', ?, ?, ?, ?, ?,
                    now() + ?::interval, now(), now() + ?::interval, now() + ?::interval, 'test')""",
            id, source, status, received, accepted, rejected, duplicate, retries, nextAttempt, started, completed);
        return id;
    }

    @Test
    void metricsReflectTheQueueTheCountersAndTheWorkers() {
        metrics.refresh();
        double depth = gauge("pidea.queue.depth"), waiting = gauge("pidea.queue.waiting");
        double pending = gauge("pidea.jobs", "status", "PENDING"), retry = gauge("pidea.jobs", "status", "RETRY_SCHEDULED");
        double failed = gauge("pidea.jobs", "status", "FAILED"), stale = gauge("pidea.jobs.stale.running");
        double accepted = counter("pidea.records.accepted"), rejected = counter("pidea.records.rejected");
        double duplicate = counter("pidea.records.duplicate"), retries = counter("pidea.job.retries");
        double completed = counter("pidea.jobs.completed"), seconds = counter("pidea.jobs.duration");
        double workers = gauge("pidea.workers.alive");

        UUID src = source();
        job(src, "PENDING", "-5 minutes", null, null, 0, 0, 0, 0, 0);            // runnable, 5 min old
        job(src, "PENDING", "+1 hour", null, null, 0, 0, 0, 0, 0);               // backing off: waiting only
        job(src, "RETRY_SCHEDULED", "-1 minute", null, null, 0, 0, 0, 0, 2);     // runnable, 2 retries
        job(src, "RUNNING", "-2 hours", "-1 hour", null, 0, 0, 0, 0, 0);                // stale claim
        job(src, "SUCCEEDED", "-1 hour", "-10 seconds", "0 seconds", 7, 5, 1, 1, 0);           // took 10 s
        job(src, "FAILED", "-1 hour", "-2 seconds", "0 seconds", 0, 0, 0, 0, 3);               // took 2 s, 3 retries
        UUID rejectedJob = job(src, "PARTIALLY_SUCCEEDED", "-1 hour", "-1 second", "0 seconds", 3, 1, 2, 0, 0);
        for (int i = 0; i < 3; i++) {
            jdbc.update("""
                INSERT INTO integration.rejected_record
                  (id, job_id, source_id, entity_type, source_record_ref, reason_code, details, processed_at)
                VALUES (?, ?, ?, 'CUSTOMER', ?, 'INVALID_EMAIL', 'x', now())""",
                UUID.randomUUID(), rejectedJob, src, "seq " + i);
        }
        jdbc.update("INSERT INTO integration.worker_heartbeat VALUES ('alive-worker', now(), now())");
        jdbc.update("INSERT INTO integration.worker_heartbeat VALUES ('dead-worker', now() - interval '1 day', "
            + "now() - interval '10 minutes')");
        double quarantinedBefore = 0;
        try {
            quarantinedBefore = gauge("pidea.quarantined.records", "reason", "INVALID_EMAIL");
        } catch (io.micrometer.core.instrument.search.MeterNotFoundException ignored) { }

        metrics.refresh();

        assertThat(gauge("pidea.queue.depth")).isEqualTo(depth + 2);
        assertThat(gauge("pidea.queue.waiting")).isEqualTo(waiting + 3);
        assertThat(gauge("pidea.jobs", "status", "PENDING")).isEqualTo(pending + 2);
        assertThat(gauge("pidea.jobs", "status", "RETRY_SCHEDULED")).isEqualTo(retry + 1);
        assertThat(gauge("pidea.jobs", "status", "FAILED")).isEqualTo(failed + 1);
        assertThat(gauge("pidea.queue.oldest.age")).isGreaterThanOrEqualTo(299);   // the 5-minute-old runnable job
        assertThat(gauge("pidea.jobs.stale.running")).isEqualTo(stale + 1);
        assertThat(counter("pidea.records.accepted")).isEqualTo(accepted + 6);      // 5 + 1
        assertThat(counter("pidea.records.rejected")).isEqualTo(rejected + 3);      // 1 + 2
        assertThat(counter("pidea.records.duplicate")).isEqualTo(duplicate + 1);
        assertThat(counter("pidea.job.retries")).isEqualTo(retries + 5);            // 2 + 3
        assertThat(counter("pidea.jobs.completed")).isEqualTo(completed + 3);
        assertThat(counter("pidea.jobs.duration")).isCloseTo(seconds + 13, within(0.5));   // 10 + 2 + 1
        assertThat(gauge("pidea.quarantined.records", "reason", "INVALID_EMAIL")).isEqualTo(quarantinedBefore + 3);
        assertThat(gauge("pidea.workers.alive")).isEqualTo(workers + 1);            // the day-old heartbeat is not alive
        assertThat(gauge("pidea.worker.heartbeat.age")).isBetween(0.0, 60.0);
        assertThat(gauge("pidea.metrics.snapshot.age")).isLessThan(30);
    }

    @Test
    void prometheusEndpointExposesTheMetricsToReadOnlyAndAdminUsersOnly() throws Exception {
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/prometheus").with(user("op").roles("OPERATOR"))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics").with(user("op").roles("OPERATOR"))).andExpect(status().isForbidden());

        String body = mvc.perform(get("/actuator/prometheus").with(user("vw").roles("VIEWER")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("pidea_queue_depth", "pidea_queue_oldest_age_seconds", "pidea_workers_alive",
            "pidea_records_accepted_total", "pidea_jobs_duration_seconds_total", "pidea_metrics_snapshot_age_seconds",
            "http_server_requests_seconds_bucket", "jvm_memory_used_bytes", "application=\"pidea-api\"");
        assertThat(body).containsPattern("pidea_jobs\\{[^}]*status=\"FAILED\"");
        mvc.perform(get("/actuator/prometheus").with(user("adm").roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());   // liveness stays public
    }
}
