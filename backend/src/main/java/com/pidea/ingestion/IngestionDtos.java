package com.pidea.ingestion;

import com.pidea.jobs.EntityType;
import com.pidea.jobs.JobType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class IngestionDtos {
    private IngestionDtos() {}

    @Schema(description = "Submit records for asynchronous processing. Per-record validation happens in the worker; "
        + "invalid records are quarantined, not rejected here.")
    public record SubmitRecordsRequest(
        @NotNull UUID sourceId,
        @NotNull EntityType entityType,
        @NotEmpty @Size(max = 1000, message = "at most 1000 records per request")
        List<Map<String, Object>> records) {}

    @Schema(description = "A job. Receiving this response means the job was ACCEPTED for processing, "
        + "not that processing succeeded: poll GET /jobs/{id} for the outcome.")
    public record JobResponse(UUID id, UUID sourceId, JobType jobType, EntityType entityType, String status,
                              String correlationId, int recordsReceived, int recordsAccepted, int recordsRejected,
                              int recordsDuplicate, String errorSummary, int retryCount, int maxRetries,
                              Instant submittedAt, Instant startedAt, Instant completedAt, Long durationMs,
                              UUID replayOf, UUID scheduleId, Instant scheduledFor) {}

    public record RejectedRecordResponse(UUID id, String sourceRecordRef, String reasonCode, String details,
                                         Instant processedAt) {}

    /** A quarantined record across jobs. Reason code and safe diagnostics only; the raw payload is never stored here. */
    public record QuarantinedRecordResponse(UUID id, UUID jobId, UUID sourceId, String entityType,
                                            String sourceRecordRef, String reasonCode, String details,
                                            Instant processedAt) {}

    public record ReasonCount(String reasonCode, long count) {}

    public record JobAttemptResponse(int attemptNo, String outcome, String error,
                                     Instant startedAt, Instant finishedAt) {}
}
