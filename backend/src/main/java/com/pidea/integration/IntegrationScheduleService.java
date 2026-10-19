package com.pidea.integration;

import com.pidea.audit.AuditService;
import com.pidea.common.ConflictException;
import com.pidea.common.NotFoundException;
import com.pidea.common.PageResponse;
import com.pidea.integration.ScheduleDtos.CreateScheduleRequest;
import com.pidea.integration.ScheduleDtos.ScheduleResponse;
import com.pidea.integration.ScheduleDtos.UpdateScheduleRequest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.pidea.jobs.EntityType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IntegrationScheduleService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public IntegrationScheduleService(JdbcTemplate jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public ScheduleResponse create(CreateScheduleRequest request, String actor) {
        Source source = jdbc.query("""
            SELECT source_type, status FROM integration.integration_source WHERE id = ?""",
            (rs, n) -> new Source(SourceType.valueOf(rs.getString(1)), SourceStatus.valueOf(rs.getString(2))),
            request.sourceId()).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Integration source", request.sourceId()));
        if (source.status() != SourceStatus.ACTIVE) throw new ConflictException("Integration source is disabled");
        if (source.type() != SourceType.HTTP_API) throw new ConflictException("Only HTTP_API sources can be scheduled");

        Instant now = Instant.now(clock);
        UUID id = UUID.randomUUID();
        Instant nextRun = now.plusSeconds(request.intervalSeconds());
        jdbc.update("""
            INSERT INTO integration.integration_schedule
              (id, source_id, entity_type, interval_seconds, enabled, next_run_at, created_at, created_by)
            VALUES (?, ?, ?, ?, true, ?, ?, ?)""",
            id, request.sourceId(), request.entityType().name(), request.intervalSeconds(),
            Timestamp.from(nextRun), Timestamp.from(now), actor);
        audit.record("SCHEDULE_CREATED", "INTEGRATION_SCHEDULE", id,
            Map.of("sourceId", request.sourceId(), "intervalSeconds", request.intervalSeconds()));
        return new ScheduleResponse(id, request.sourceId(), request.entityType(),
            request.intervalSeconds(), true, nextRun, now);
    }

    @Transactional(readOnly = true)
    public PageResponse<ScheduleResponse> list(UUID sourceId, int page, int size) {
        var pr = PageResponse.pageRequest(page, size, org.springframework.data.domain.Sort.unsorted());
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (sourceId != null) {
            where.append(" AND source_id = ?");
            args.add(sourceId);
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM integration.integration_schedule" + where,
            Long.class, args.toArray());
        args.add(pr.getPageSize());
        args.add(pr.getOffset());
        List<ScheduleResponse> schedules = jdbc.query("""
            SELECT id, source_id, entity_type, interval_seconds, enabled, next_run_at, created_at
            FROM integration.integration_schedule""" + where
                + " ORDER BY next_run_at, id LIMIT ? OFFSET ?",
            (rs, n) -> new ScheduleResponse(rs.getObject("id", UUID.class),
                rs.getObject("source_id", UUID.class), EntityType.valueOf(rs.getString("entity_type")),
                rs.getInt("interval_seconds"), rs.getBoolean("enabled"),
                rs.getTimestamp("next_run_at").toInstant(), rs.getTimestamp("created_at").toInstant()),
            args.toArray());
        long count = total == null ? 0 : total;
        return new PageResponse<>(schedules, pr.getPageNumber(), pr.getPageSize(), count,
            (int) Math.ceil(count / (double) pr.getPageSize()));
    }

    @Transactional
    public ScheduleResponse update(UUID id, UpdateScheduleRequest request) {
        int updated = jdbc.update("""
            UPDATE integration.integration_schedule SET enabled = ?, version = version + 1 WHERE id = ?""",
            request.enabled(), id);
        if (updated == 0) throw new NotFoundException("Integration schedule", id);
        audit.record("SCHEDULE_STATUS_CHANGED", "INTEGRATION_SCHEDULE", id, Map.of("enabled", request.enabled()));
        return jdbc.query("""
            SELECT id, source_id, entity_type, interval_seconds, enabled, next_run_at, created_at
            FROM integration.integration_schedule WHERE id = ?""",
            (rs, n) -> new ScheduleResponse(rs.getObject("id", UUID.class),
                rs.getObject("source_id", UUID.class), EntityType.valueOf(rs.getString("entity_type")),
                rs.getInt("interval_seconds"), rs.getBoolean("enabled"),
                rs.getTimestamp("next_run_at").toInstant(), rs.getTimestamp("created_at").toInstant()),
            id).getFirst();
    }

    private record Source(SourceType type, SourceStatus status) {}
}
