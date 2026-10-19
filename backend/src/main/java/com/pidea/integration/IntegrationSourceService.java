package com.pidea.integration;

import com.pidea.audit.AuditService;
import com.pidea.common.ConflictException;
import com.pidea.common.NotFoundException;
import com.pidea.common.PageResponse;
import com.pidea.integration.SourceDtos.*;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IntegrationSourceService {
    private final IntegrationSourceRepository repo;
    private final SourceConfigValidator validator;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public IntegrationSourceService(IntegrationSourceRepository repo, SourceConfigValidator validator,
                                    AuditService audit, JdbcTemplate jdbc, Clock clock) {
        this.repo = repo;
        this.validator = validator;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** @Transactional: the source row and its audit row commit or roll back together. */
    @Transactional
    public SourceResponse create(CreateSourceRequest req) {
        if (repo.existsByName(req.name())) {
            throw new ConflictException("An integration source named '" + req.name() + "' already exists");
        }
        IntegrationSource s = new IntegrationSource(UUID.randomUUID(), req.name(), req.type(),
            validator.validate(req.type(), req.config()), req.secretRef(), Instant.now(clock));
        try {
            repo.saveAndFlush(s);   // flush now so a unique-constraint race surfaces here as a 409
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("An integration source named '" + req.name() + "' already exists");
        }
        audit.record("SOURCE_CREATED", "INTEGRATION_SOURCE", s.getId(), Map.of("name", s.getName(), "type", s.getSourceType()));
        return SourceResponse.from(s);
    }

    @Transactional(readOnly = true)
    public PageResponse<SourceResponse> list(int page, int size) {
        return PageResponse.of(repo.findAll(PageResponse.pageRequest(page, size, Sort.by("name"))), SourceResponse::from);
    }

    @Transactional(readOnly = true)
    public SourceResponse get(UUID id) {
        return SourceResponse.from(find(id));
    }

    @Transactional
    public SourceResponse update(UUID id, UpdateSourceRequest req) {
        IntegrationSource s = find(id);
        Map<String, Object> config = req.config() != null ? validator.validate(s.getSourceType(), req.config()) : s.getConfig();
        String secretRef = req.secretRef() != null ? req.secretRef() : s.getSecretRef();
        s.updateConfig(config, secretRef, Instant.now(clock));
        repo.saveAndFlush(s);
        audit.record("SOURCE_UPDATED", "INTEGRATION_SOURCE", id, Map.of("configChanged", req.config() != null,
            "secretRefChanged", req.secretRef() != null));
        return SourceResponse.from(s);
    }

    @Transactional
    public SourceResponse changeStatus(UUID id, SourceStatus status) {
        IntegrationSource s = find(id);
        SourceStatus before = s.getStatus();
        s.changeStatus(status, Instant.now(clock));
        repo.saveAndFlush(s);
        audit.record("SOURCE_STATUS_CHANGED", "INTEGRATION_SOURCE", id, Map.of("from", before, "to", status));
        return SourceResponse.from(s);
    }

    @Transactional(readOnly = true)
    public SourceHealthResponse health(UUID id) {
        IntegrationSource s = find(id);
        Timestamp since = Timestamp.from(Instant.now(clock).minus(Duration.ofHours(24)));
        SourceMetrics metrics = jdbc.queryForObject("""
            SELECT count(*) FILTER (WHERE status = 'FAILED'),
                   count(*) FILTER (WHERE status IN ('SUCCEEDED','PARTIALLY_SUCCEEDED','FAILED','CANCELLED')),
                   coalesce(sum(records_accepted), 0), coalesce(sum(records_rejected), 0)
            FROM integration.ingestion_job WHERE source_id = ? AND submitted_at >= ?""",
            (rs, n) -> new SourceMetrics(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)),
            id, since);
        List<SourceHealthResponse> last = jdbc.query("""
            SELECT id, status, submitted_at, completed_at FROM integration.ingestion_job
            WHERE source_id = ? ORDER BY submitted_at DESC LIMIT 1""",
            (rs, n) -> new SourceHealthResponse(id, s.getStatus(), rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getTimestamp("submitted_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
                metrics.failed(), metrics.completed(), metrics.accepted(), metrics.rejected()), id);
        return last.isEmpty()
            ? new SourceHealthResponse(id, s.getStatus(), null, null, null, null,
                metrics.failed(), metrics.completed(), metrics.accepted(), metrics.rejected())
            : last.get(0);
    }

    private record SourceMetrics(long failed, long completed, long accepted, long rejected) {}

    IntegrationSource find(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("Integration source", id));
    }
}
