package com.pidea.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.pidea.audit.AuditService;
import com.pidea.common.ConflictException;
import com.pidea.common.CorrelationIdFilter;
import com.pidea.common.IdempotencyKeyReusedException;
import com.pidea.common.NotFoundException;
import com.pidea.common.PageResponse;
import com.pidea.common.ValidationException;
import com.pidea.ingestion.IngestionDtos.*;
import com.pidea.integration.SourceStatus;
import com.pidea.integration.SourceType;
import com.pidea.jobs.EntityType;
import com.pidea.jobs.JobStatus;
import com.pidea.jobs.JobType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Job submission and queries. Uses explicit SQL (JdbcTemplate) rather than JPA because the Python worker
 * updates the same rows concurrently; atomic conditional UPDATEs are clearer than entity versioning here.
 *
 * <p>Idempotency policy (see docs/api-guide.md): scope = authenticated username; key = Idempotency-Key header
 * (8-100 chars); retention 24 h; fingerprint = SHA-256 of the canonical request (sorted keys). Same key + same
 * fingerprint -> the original job is returned (replay). Same key + different fingerprint -> 422. Concurrent
 * duplicates are serialised by the UNIQUE(scope, key) index: the loser blocks until the winner commits.
 */
@Service
public class IngestionService {
    static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);
    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper canonicalMapper;
    private final AuditService audit;
    private final Clock clock;
    private final UploadFileValidator uploadValidator;
    private final Path uploadDirectory;

    public IngestionService(JdbcTemplate jdbc, ObjectMapper mapper, AuditService audit, Clock clock,
                            UploadFileValidator uploadValidator,
                            @Value("${pidea.ingestion.upload-dir}") String uploadDirectory) {
        this.jdbc = jdbc;
        this.canonicalMapper = mapper.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.audit = audit;
        this.clock = clock;
        this.uploadValidator = uploadValidator;
        this.uploadDirectory = Path.of(uploadDirectory).toAbsolutePath().normalize();
    }

    @Transactional(readOnly = true)
    public PageResponse<JobAttemptResponse> attempts(UUID jobId, int page, int size) {
        getJob(jobId);
        var pr = PageResponse.pageRequest(page, size, org.springframework.data.domain.Sort.unsorted());
        Long total = jdbc.queryForObject("SELECT count(*) FROM integration.job_attempt WHERE job_id = ?",
            Long.class, jobId);
        List<JobAttemptResponse> items = jdbc.query("""
            SELECT attempt_no, outcome, error, started_at, finished_at
            FROM integration.job_attempt WHERE job_id = ? ORDER BY attempt_no LIMIT ? OFFSET ?""",
            (rs, n) -> new JobAttemptResponse(rs.getInt("attempt_no"), rs.getString("outcome"),
                rs.getString("error"), rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toInstant()),
            jobId, pr.getPageSize(), pr.getOffset());
        long count = total == null ? 0 : total;
        return new PageResponse<>(items, pr.getPageNumber(), pr.getPageSize(), count,
            (int) Math.ceil(count / (double) pr.getPageSize()));
    }

    @Transactional
    public JobResponse replay(UUID id, String principal) {
        ReplaySource original = jdbc.query("""
            SELECT source_id, job_type, entity_type, status, file_ref, records_received
            FROM integration.ingestion_job WHERE id = ? FOR UPDATE""",
            (rs, n) -> new ReplaySource(rs.getObject("source_id", UUID.class), rs.getString("job_type"),
                rs.getString("entity_type"), rs.getString("status"), rs.getString("file_ref"),
                rs.getInt("records_received")), id).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Job", id));
        UUID existing = jdbc.query("SELECT id FROM integration.ingestion_job WHERE replay_of = ?",
            (rs, n) -> rs.getObject(1, UUID.class), id).stream().findFirst().orElse(null);
        if (existing != null) return getJob(existing);
        if (!"FAILED".equals(original.status())) {
            throw new ConflictException("Only FAILED jobs can be replayed");
        }
        SourceStatus sourceStatus = jdbc.queryForObject(
            "SELECT status FROM integration.integration_source WHERE id = ?",
            (rs, n) -> SourceStatus.valueOf(rs.getString(1)), original.sourceId());
        if (sourceStatus != SourceStatus.ACTIVE) {
            throw new ConflictException("Integration source is disabled");
        }
        if (("FILE_CSV".equals(original.jobType()) || "FILE_JSON".equals(original.jobType()))
                && !isManagedUploadAvailable(original.fileRef())) {
            throw new ConflictException("The uploaded file is no longer available for replay");
        }

        UUID replayId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("""
            INSERT INTO integration.ingestion_job
              (id, source_id, job_type, entity_type, status, correlation_id, file_ref, records_received,
               next_attempt_at, submitted_at, submitted_by, replay_of)
            VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?)""",
            replayId, original.sourceId(), original.jobType(), original.entityType(), correlationId(),
            original.fileRef(), original.recordsReceived(), now, now, principal, id);
        jdbc.update("""
            INSERT INTO integration.raw_record (job_id, seq, payload)
            SELECT ?, seq, payload FROM integration.raw_record WHERE job_id = ?""", replayId, id);
        audit.record("JOB_REPLAYED", "INGESTION_JOB", replayId, Map.of("replayOf", id));
        return getJob(replayId);
    }

    public record Submission(JobResponse job, boolean replayed) {}

    @Transactional
    public Submission submit(SubmitRecordsRequest req, String idempotencyKey, String principal) {
        SourceInfo src = jdbc.query("SELECT status, source_type, version FROM integration.integration_source WHERE id = ?",
            (rs, n) -> new SourceInfo(SourceStatus.valueOf(rs.getString(1)), SourceType.valueOf(rs.getString(2)),
                rs.getLong(3)),
            req.sourceId()).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Integration source", req.sourceId()));
        if (src.status() != SourceStatus.ACTIVE) {
            throw new ConflictException("Integration source is disabled");
        }
        if (src.type() != SourceType.REST_PUSH) {
            throw new ConflictException("Source type " + src.type() + " does not accept pushed records");
        }

        Instant now = Instant.now(clock);
        UUID jobId = UUID.randomUUID();
        String fingerprint = null;

        if (idempotencyKey != null) {
            fingerprint = fingerprint(req);
            Submission replay = claimIdempotency(principal, idempotencyKey, fingerprint, now);
            if (replay != null) return replay;
        }

        insertJob(jobId, req, principal, now);
        if (idempotencyKey != null) {
            jdbc.update("UPDATE integration.idempotency_record SET job_id = ? WHERE scope = ? AND idempotency_key = ?",
                jobId, principal, idempotencyKey);
        }
        audit.record("JOB_SUBMITTED", "INGESTION_JOB", jobId,
            Map.of("sourceId", req.sourceId(), "entityType", req.entityType(), "records", req.records().size()));
        return new Submission(getJob(jobId), false);
    }

    @Transactional
    public Submission submitFile(UUID sourceId, EntityType entityType, MultipartFile file,
                                String idempotencyKey, String principal) {
        SourceInfo src = jdbc.query("SELECT status, source_type, version FROM integration.integration_source WHERE id = ?",
            (rs, n) -> new SourceInfo(SourceStatus.valueOf(rs.getString(1)), SourceType.valueOf(rs.getString(2)),
                rs.getLong(3)),
            sourceId).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Integration source", sourceId));
        if (src.status() != SourceStatus.ACTIVE) {
            throw new ConflictException("Integration source is disabled");
        }
        UploadFileValidator.ValidatedFile upload = uploadValidator.validate(file, src.type());
        String fingerprint = fingerprint(sourceId, entityType, upload);
        Instant now = Instant.now(clock);
        Submission replay = claimIdempotency(principal, idempotencyKey, fingerprint, now);
        if (replay != null) return replay;

        UUID jobId = UUID.randomUUID();
        String fileRef = jobId + "." + upload.extension();
        Path stored = persistUpload(fileRef, upload.content());
        registerRollbackCleanup(stored);
        Timestamp ts = Timestamp.from(now);
        jdbc.update("""
            INSERT INTO integration.ingestion_job
              (id, source_id, job_type, entity_type, status, correlation_id, file_ref, records_received,
               next_attempt_at, submitted_at, submitted_by)
            VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?)""",
            jobId, sourceId, upload.extension().equals("csv") ? JobType.FILE_CSV.name() : JobType.FILE_JSON.name(),
            entityType.name(), correlationId(), fileRef, upload.recordCount(), ts, ts, principal);
        jdbc.update("UPDATE integration.idempotency_record SET job_id = ? WHERE scope = ? AND idempotency_key = ?",
            jobId, principal, idempotencyKey);
        audit.record("FILE_JOB_SUBMITTED", "INGESTION_JOB", jobId,
            Map.of("sourceId", sourceId, "entityType", entityType, "records", upload.recordCount(),
                "format", upload.extension()));
        return new Submission(getJob(jobId), false);
    }

    @Transactional
    public Submission submitPull(UUID sourceId, EntityType entityType, String idempotencyKey, String principal) {
        SourceInfo source = jdbc.query(
            "SELECT status, source_type, version FROM integration.integration_source WHERE id = ?",
            (rs, n) -> new SourceInfo(SourceStatus.valueOf(rs.getString(1)), SourceType.valueOf(rs.getString(2)),
                rs.getLong(3)), sourceId).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Integration source", sourceId));
        if (source.status() != SourceStatus.ACTIVE) {
            throw new ConflictException("Integration source is disabled");
        }
        if (source.type() != SourceType.HTTP_API) {
            throw new ConflictException("Only HTTP_API sources can be triggered");
        }

        Instant now = Instant.now(clock);
        String fingerprint = fingerprint(sourceId + ":" + entityType.name() + ":" + source.version());
        Submission replay = claimIdempotency(principal, idempotencyKey, fingerprint, now);
        if (replay != null) return replay;

        UUID jobId = UUID.randomUUID();
        Timestamp ts = Timestamp.from(now);
        jdbc.update("""
            INSERT INTO integration.ingestion_job
              (id, source_id, job_type, entity_type, status, correlation_id, records_received,
               next_attempt_at, submitted_at, submitted_by)
            VALUES (?, ?, 'HTTP_PULL', ?, 'PENDING', ?, 0, ?, ?, ?)""",
            jobId, sourceId, entityType.name(), correlationId(), ts, ts, principal);
        jdbc.update("UPDATE integration.idempotency_record SET job_id = ? WHERE scope = ? AND idempotency_key = ?",
            jobId, principal, idempotencyKey);
        audit.record("HTTP_PULL_SUBMITTED", "INGESTION_JOB", jobId,
            Map.of("sourceId", sourceId, "entityType", entityType));
        return new Submission(getJob(jobId), false);
    }

    private Submission claimIdempotency(String principal, String key, String fingerprint, Instant now) {
        jdbc.update("DELETE FROM integration.idempotency_record WHERE scope = ? AND idempotency_key = ? AND expires_at < ?",
            principal, key, Timestamp.from(now));
        int claimed = jdbc.update("""
            INSERT INTO integration.idempotency_record (id, scope, idempotency_key, request_fingerprint, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (scope, idempotency_key) DO NOTHING""",
            UUID.randomUUID(), principal, key, fingerprint, Timestamp.from(now),
            Timestamp.from(now.plus(IDEMPOTENCY_RETENTION)));
        return claimed == 0 ? replay(principal, key, fingerprint) : null;
    }

    private Path persistUpload(String fileRef, byte[] content) {
        Path temp = null;
        try {
            Files.createDirectories(uploadDirectory);
            temp = Files.createTempFile(uploadDirectory, "pidea-upload-", ".part");
            setSharedFilePermissions(temp);
            Files.write(temp, content);
            Path target = uploadDirectory.resolve(fileRef).normalize();
            if (!target.getParent().equals(uploadDirectory)) {
                throw new IllegalStateException("Generated upload path escaped its storage directory");
            }
            return Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.io.IOException e) {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (java.io.IOException cleanupError) {
                    e.addSuppressed(cleanupError);
                }
            }
            throw new IllegalStateException("Could not store uploaded file", e);
        }
    }

    private static void setSharedFilePermissions(Path file) throws java.io.IOException {
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX local filesystems use their existing ACLs.
        }
    }

    private boolean isManagedUploadAvailable(String fileRef) {
        if (fileRef == null || !fileRef.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(csv|json)")) {
            return false;
        }
        Path path = uploadDirectory.resolve(fileRef).normalize();
        return path.getParent().equals(uploadDirectory)
            && Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    private static void registerRollbackCleanup(Path stored) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try {
                        Files.deleteIfExists(stored);
                    } catch (java.io.IOException e) {
                        log.error("Could not remove an upload after its database transaction rolled back");
                    }
                }
            }
        });
    }

    private Submission replay(String principal, String key, String fingerprint) {
        Map<String, Object> row = jdbc.queryForMap(
            "SELECT request_fingerprint, job_id FROM integration.idempotency_record WHERE scope = ? AND idempotency_key = ?",
            principal, key);
        if (!fingerprint.equals(row.get("request_fingerprint").toString().trim())) {
            throw new IdempotencyKeyReusedException();
        }
        return new Submission(getJob((UUID) row.get("job_id")), true);
    }

    private void insertJob(UUID jobId, SubmitRecordsRequest req, String principal, Instant now) {
        Timestamp ts = Timestamp.from(now);
        jdbc.update("""
            INSERT INTO integration.ingestion_job
              (id, source_id, job_type, entity_type, status, correlation_id, records_received,
               next_attempt_at, submitted_at, submitted_by)
            VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?)""",
            jobId, req.sourceId(), JobType.RECORDS.name(), req.entityType().name(), correlationId(),
            req.records().size(), ts, ts, principal);
        List<Object[]> batch = new ArrayList<>();
        int seq = 0;
        for (Map<String, Object> rec : req.records()) {
            try {
                batch.add(new Object[] {jobId, seq++, canonicalMapper.writeValueAsString(rec)});
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Record cannot be serialised", e);
            }
        }
        jdbc.batchUpdate("INSERT INTO integration.raw_record (job_id, seq, payload) VALUES (?, ?, ?::jsonb)", batch);
    }

    @Transactional(readOnly = true)
    public JobResponse getJob(UUID id) {
        return jdbc.query(JOB_SELECT + " WHERE id = ?", JOB_MAPPER, id).stream().findFirst()
            .orElseThrow(() -> new NotFoundException("Job", id));
    }

    @Transactional(readOnly = true)
    public PageResponse<JobResponse> listJobs(UUID sourceId, JobStatus status, Instant submittedFrom,
                                              Instant submittedTo, int page, int size) {
        if (submittedFrom != null && submittedTo != null && submittedFrom.isAfter(submittedTo)) {
            throw new ValidationException("submittedFrom must not be after submittedTo");
        }
        var pr = PageResponse.pageRequest(page, size, org.springframework.data.domain.Sort.unsorted());
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (sourceId != null) { where.append(" AND source_id = ?"); args.add(sourceId); }
        if (status != null) { where.append(" AND status = ?"); args.add(status.name()); }
        if (submittedFrom != null) { where.append(" AND submitted_at >= ?"); args.add(Timestamp.from(submittedFrom)); }
        if (submittedTo != null) { where.append(" AND submitted_at <= ?"); args.add(Timestamp.from(submittedTo)); }
        Long total = jdbc.queryForObject("SELECT count(*) FROM integration.ingestion_job" + where, Long.class, args.toArray());
        args.add(pr.getPageSize());
        args.add(pr.getOffset());
        List<JobResponse> items = jdbc.query(JOB_SELECT + where + " ORDER BY submitted_at DESC, id LIMIT ? OFFSET ?",
            JOB_MAPPER, args.toArray());
        long t = total == null ? 0 : total;
        return new PageResponse<>(items, pr.getPageNumber(), pr.getPageSize(), t, (int) Math.ceil(t / (double) pr.getPageSize()));
    }

    @Transactional(readOnly = true)
    public PageResponse<RejectedRecordResponse> rejected(UUID jobId, int page, int size) {
        getJob(jobId); // 404 if the job does not exist
        var pr = PageResponse.pageRequest(page, size, org.springframework.data.domain.Sort.unsorted());
        Long total = jdbc.queryForObject("SELECT count(*) FROM integration.rejected_record WHERE job_id = ?", Long.class, jobId);
        List<RejectedRecordResponse> items = jdbc.query("""
            SELECT id, source_record_ref, reason_code, details, processed_at FROM integration.rejected_record
            WHERE job_id = ? ORDER BY processed_at, source_record_ref LIMIT ? OFFSET ?""",
            (rs, n) -> new RejectedRecordResponse(rs.getObject("id", UUID.class), rs.getString("source_record_ref"),
                rs.getString("reason_code"), rs.getString("details"), rs.getTimestamp("processed_at").toInstant()),
            jobId, pr.getPageSize(), pr.getOffset());
        long t = total == null ? 0 : total;
        return new PageResponse<>(items, pr.getPageNumber(), pr.getPageSize(), t, (int) Math.ceil(t / (double) pr.getPageSize()));
    }

    /** Atomic: only a job that has not started (or is waiting for retry) can be cancelled. */
    @Transactional
    public JobResponse cancel(UUID id) {
        int n = jdbc.update("""
            UPDATE integration.ingestion_job SET status = 'CANCELLED', completed_at = ?, version = version + 1
            WHERE id = ? AND status IN ('PENDING','RETRY_SCHEDULED')""", Timestamp.from(Instant.now(clock)), id);
        if (n == 0) {
            JobResponse job = getJob(id); // 404 if missing
            throw new ConflictException("Job in status " + job.status() + " cannot be cancelled");
        }
        audit.record("JOB_CANCELLED", "INGESTION_JOB", id, Map.of());
        return getJob(id);
    }

    private String fingerprint(SubmitRecordsRequest req) {
        try {
            String canonical = canonicalMapper.writeValueAsString(req);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot fingerprint request", e);
        }
    }

    private String fingerprint(UUID sourceId, EntityType entityType, UploadFileValidator.ValidatedFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(sourceId.toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(entityType.name().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(file.extension().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(file.content()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot fingerprint upload", e);
        }
    }

    private static String correlationId() {
        String id = MDC.get(CorrelationIdFilter.MDC_KEY);
        return id != null ? id : UUID.randomUUID().toString();
    }

    private String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot fingerprint request", e);
        }
    }

    private record SourceInfo(SourceStatus status, SourceType type, long version) {}
    private record ReplaySource(UUID sourceId, String jobType, String entityType, String status,
                                String fileRef, int recordsReceived) {}

    private static final String JOB_SELECT = """
        SELECT id, source_id, job_type, entity_type, status, correlation_id, records_received, records_accepted,
               records_rejected, records_duplicate, error_summary, retry_count, max_retries, submitted_at,
               started_at, completed_at, replay_of, schedule_id, scheduled_for FROM integration.ingestion_job""";

    private static final RowMapper<JobResponse> JOB_MAPPER = (rs, n) -> {
        Timestamp started = rs.getTimestamp("started_at");
        Timestamp done = rs.getTimestamp("completed_at");
        return new JobResponse(rs.getObject("id", UUID.class), rs.getObject("source_id", UUID.class),
            JobType.valueOf(rs.getString("job_type")), EntityType.valueOf(rs.getString("entity_type")),
            rs.getString("status"), rs.getString("correlation_id"), rs.getInt("records_received"),
            rs.getInt("records_accepted"), rs.getInt("records_rejected"), rs.getInt("records_duplicate"),
            rs.getString("error_summary"), rs.getInt("retry_count"), rs.getInt("max_retries"),
            rs.getTimestamp("submitted_at").toInstant(), started == null ? null : started.toInstant(),
            done == null ? null : done.toInstant(),
            started != null && done != null ? Duration.between(started.toInstant(), done.toInstant()).toMillis() : null,
            rs.getObject("replay_of", UUID.class), rs.getObject("schedule_id", UUID.class),
            rs.getTimestamp("scheduled_for") == null ? null : rs.getTimestamp("scheduled_for").toInstant());
    };
}
