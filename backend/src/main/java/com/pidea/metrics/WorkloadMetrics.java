package com.pidea.metrics;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes the platform's real workload as metrics.
 *
 * <p>The Python worker is a separate process with no HTTP endpoint; PostgreSQL is the shared source of truth for the
 * job queue, so the API reads the state from there. Nothing here is estimated or synthetic: every value is a query
 * over {@code integration.*} tables. The queries run on a timer (not per scrape) and the meters read the latest
 * snapshot, so scraping never loads the database. If the database becomes unreachable the snapshot stops updating
 * and {@code pidea_metrics_snapshot_age_seconds} grows, which is itself alertable.
 *
 * <p>Counters ({@code *_total}) are cumulative sums over stored jobs, so they only decrease if jobs are deleted
 * (there is no retention job today). Use {@code rate()}/{@code increase()} on them in Prometheus.
 */
@Component
public class WorkloadMetrics {
    private static final Logger log = LoggerFactory.getLogger(WorkloadMetrics.class);
    private static final List<String> STATUSES = List.of(
        "PENDING", "RUNNING", "RETRY_SCHEDULED", "SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED", "CANCELLED");
    /** Matches the worker's default stale-claim timeout (worker.run_forever stale_after = 15 minutes). */
    private static final String STALE_RUNNING_INTERVAL = "15 minutes";
    private static final String WORKER_ALIVE_INTERVAL = "60 seconds";

    record Snapshot(Instant takenAt, Map<String, Long> jobsByStatus, long queueDepth, long waiting,
                    double oldestRunnableAgeSeconds, long staleRunning, long recordsAccepted, long recordsRejected,
                    long recordsDuplicate, long retries, long completedJobs, double durationSecondsTotal,
                    Map<String, Long> quarantinedByReason, long workersAlive, double lastHeartbeatAgeSeconds) {
        static Snapshot empty(Instant now) {
            return new Snapshot(now, Map.of(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of(), 0, -1);
        }
    }

    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;
    private final Clock clock;
    private final AtomicReference<Snapshot> snapshot;
    private final MultiGauge quarantined;

    public WorkloadMetrics(JdbcTemplate jdbc, MeterRegistry registry, Clock clock) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.clock = clock;
        this.snapshot = new AtomicReference<>(Snapshot.empty(Instant.now(clock)));
        this.quarantined = MultiGauge.builder("pidea.quarantined.records")
            .description("Quarantined (rejected) records currently stored, by reason code").register(registry);
    }

    @PostConstruct
    void register() {
        for (String status : STATUSES) {
            Gauge.builder("pidea.jobs", snapshot, s -> s.get().jobsByStatus().getOrDefault(status, 0L))
                .description("Jobs currently in each status").tag("status", status).register(registry);
        }
        Gauge.builder("pidea.queue.depth", snapshot, s -> s.get().queueDepth())
            .description("Jobs ready to run now (PENDING or RETRY_SCHEDULED with next_attempt_at in the past)")
            .register(registry);
        Gauge.builder("pidea.queue.waiting", snapshot, s -> s.get().waiting())
            .description("PENDING/RETRY_SCHEDULED jobs including those backing off until a later time")
            .register(registry);
        Gauge.builder("pidea.queue.oldest.age", snapshot, s -> s.get().oldestRunnableAgeSeconds())
            .description("Age of the longest-waiting runnable job").baseUnit("seconds").register(registry);
        Gauge.builder("pidea.jobs.stale.running", snapshot, s -> s.get().staleRunning())
            .description("Jobs RUNNING longer than the worker stale-claim timeout (worker likely died mid-job)")
            .register(registry);
        Gauge.builder("pidea.workers.alive", snapshot, s -> s.get().workersAlive())
            .description("Workers with a heartbeat in the last " + WORKER_ALIVE_INTERVAL).register(registry);
        Gauge.builder("pidea.worker.heartbeat.age", snapshot, s -> s.get().lastHeartbeatAgeSeconds())
            .description("Seconds since the most recent worker heartbeat; -1 if no worker has ever reported")
            .baseUnit("seconds").register(registry);
        Gauge.builder("pidea.metrics.snapshot.age", snapshot,
                s -> Duration.between(s.get().takenAt(), Instant.now(clock)).toMillis() / 1000.0)
            .description("Age of the database snapshot behind these metrics; grows if the database is unreachable")
            .baseUnit("seconds").register(registry);

        counter("pidea.records.accepted", "Records loaded into analytics tables", s -> s.recordsAccepted());
        counter("pidea.records.rejected", "Records rejected and quarantined", s -> s.recordsRejected());
        counter("pidea.records.duplicate", "Records skipped as duplicates", s -> s.recordsDuplicate());
        counter("pidea.job.retries", "Retries scheduled across all jobs", s -> s.retries());
        counter("pidea.jobs.completed", "Jobs that reached a terminal state with a completion time",
            s -> s.completedJobs());
        FunctionCounter.builder("pidea.jobs.duration", snapshot, s -> s.get().durationSecondsTotal())
            .description("Total processing time of completed jobs (completed_at - started_at)")
            .baseUnit("seconds").register(registry);
        refresh();
    }

    private void counter(String name, String description, java.util.function.ToDoubleFunction<Snapshot> value) {
        FunctionCounter.builder(name, snapshot, s -> value.applyAsDouble(s.get()))
            .description(description).register(registry);
    }

    @Scheduled(fixedDelayString = "${pidea.metrics.refresh-interval-ms:15000}",
               initialDelayString = "${pidea.metrics.refresh-interval-ms:15000}")
    public void refresh() {
        try {
            snapshot.set(query());
            Snapshot s = snapshot.get();
            quarantined.register(s.quarantinedByReason().entrySet().stream()
                .map(e -> MultiGauge.Row.of(Tags.of("reason", e.getKey()), e.getValue())).toList(), true);
        } catch (RuntimeException e) {
            // Keep the previous snapshot; its growing age is the signal. Never include connection details.
            log.warn("workload metrics refresh failed: {}", e.getClass().getSimpleName());
        }
    }

    private Snapshot query() {
        Map<String, Long> byStatus = new TreeMap<>();
        jdbc.query("SELECT status, count(*) FROM integration.ingestion_job GROUP BY status",
            rs -> { byStatus.put(rs.getString(1), rs.getLong(2)); });

        long[] queue = new long[2];
        double[] oldest = new double[1];
        jdbc.query("""
            SELECT count(*) FILTER (WHERE next_attempt_at <= now()),
                   count(*),
                   COALESCE(EXTRACT(EPOCH FROM now() - min(next_attempt_at) FILTER (WHERE next_attempt_at <= now())), 0)
            FROM integration.ingestion_job WHERE status IN ('PENDING','RETRY_SCHEDULED')""",
            rs -> { queue[0] = rs.getLong(1); queue[1] = rs.getLong(2); oldest[0] = rs.getDouble(3); });

        Long stale = jdbc.queryForObject("""
            SELECT count(*) FROM integration.ingestion_job
            WHERE status = 'RUNNING' AND started_at < now() - interval '%s'""".formatted(STALE_RUNNING_INTERVAL),
            Long.class);

        long[] totals = new long[5];
        double[] duration = new double[1];
        jdbc.query("""
            SELECT COALESCE(sum(records_accepted), 0), COALESCE(sum(records_rejected), 0),
                   COALESCE(sum(records_duplicate), 0), COALESCE(sum(retry_count), 0),
                   count(*) FILTER (WHERE completed_at IS NOT NULL AND started_at IS NOT NULL),
                   COALESCE(sum(EXTRACT(EPOCH FROM completed_at - started_at))
                            FILTER (WHERE completed_at IS NOT NULL AND started_at IS NOT NULL), 0)
            FROM integration.ingestion_job""",
            rs -> { for (int i = 0; i < 5; i++) totals[i] = rs.getLong(i + 1); duration[0] = rs.getDouble(6); });

        Map<String, Long> reasons = new TreeMap<>();
        jdbc.query("SELECT reason_code, count(*) FROM integration.rejected_record GROUP BY reason_code",
            rs -> { reasons.put(rs.getString(1), rs.getLong(2)); });

        long[] workers = new long[1];
        double[] heartbeatAge = {-1};
        jdbc.query("""
            SELECT count(*) FILTER (WHERE last_seen > now() - interval '%s'),
                   COALESCE(EXTRACT(EPOCH FROM now() - max(last_seen)), -1)
            FROM integration.worker_heartbeat""".formatted(WORKER_ALIVE_INTERVAL),
            rs -> { workers[0] = rs.getLong(1); heartbeatAge[0] = rs.getDouble(2); });

        return new Snapshot(Instant.now(clock), byStatus, queue[0], queue[1], oldest[0], stale == null ? 0 : stale,
            totals[0], totals[1], totals[2], totals[3], totals[4], duration[0], reasons, workers[0], heartbeatAge[0]);
    }
}
