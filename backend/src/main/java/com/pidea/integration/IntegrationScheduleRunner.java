package com.pidea.integration;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class IntegrationScheduleRunner {
    private static final int MAX_SCHEDULES_PER_TICK = 50;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public IntegrationScheduleRunner(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${pidea.scheduler.poll-interval-ms:2000}")
    @Transactional
    public void enqueueDueSchedules() {
        for (int i = 0; i < MAX_SCHEDULES_PER_TICK; i++) {
            Instant now = Instant.now(clock);
            DueSchedule due = jdbc.query("""
                SELECT id, source_id, entity_type, interval_seconds, next_run_at
                FROM integration.integration_schedule
                WHERE enabled = true AND next_run_at <= ?
                ORDER BY next_run_at, id FOR UPDATE SKIP LOCKED LIMIT 1""",
                (rs, n) -> new DueSchedule(rs.getObject("id", UUID.class),
                    rs.getObject("source_id", UUID.class), rs.getString("entity_type"),
                    rs.getInt("interval_seconds"), rs.getTimestamp("next_run_at").toInstant()),
                Timestamp.from(now)).stream().findFirst().orElse(null);
            if (due == null) return;

            jdbc.update("""
                UPDATE integration.integration_schedule
                SET next_run_at = ?, version = version + 1
                WHERE id = ?""",
                Timestamp.from(now.plusSeconds(due.intervalSeconds())), due.id());
            Boolean sourceActive = jdbc.queryForObject("""
                SELECT status = 'ACTIVE' AND source_type = 'HTTP_API'
                FROM integration.integration_source WHERE id = ?""", Boolean.class, due.sourceId());
            if (!Boolean.TRUE.equals(sourceActive)) continue;

            UUID jobId = UUID.randomUUID();
            Timestamp scheduledAt = Timestamp.from(due.scheduledFor());
            jdbc.update("""
                INSERT INTO integration.ingestion_job
                  (id, source_id, job_type, entity_type, status, correlation_id, records_received,
                   next_attempt_at, submitted_at, submitted_by, schedule_id, scheduled_for)
                VALUES (?, ?, 'HTTP_PULL', ?, 'PENDING', ?, 0, ?, ?, 'scheduler', ?, ?)
                ON CONFLICT (schedule_id, scheduled_for) WHERE schedule_id IS NOT NULL DO NOTHING""",
                jobId, due.sourceId(), due.entityType(), UUID.randomUUID().toString(),
                Timestamp.from(now), Timestamp.from(now), due.id(), scheduledAt);
        }
    }

    private record DueSchedule(UUID id, UUID sourceId, String entityType, int intervalSeconds,
                               Instant scheduledFor) {}
}
