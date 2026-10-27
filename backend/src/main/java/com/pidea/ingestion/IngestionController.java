package com.pidea.ingestion;

import com.pidea.common.PageResponse;
import com.pidea.common.ValidationException;
import com.pidea.ingestion.IngestionDtos.*;
import com.pidea.jobs.EntityType;
import com.pidea.jobs.JobStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.security.Principal;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/ingestion")
@Tag(name = "Ingestion")
@SecurityRequirement(name = "basicAuth")
public class IngestionController {
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,100}$");
    private final IngestionService service;

    public IngestionController(IngestionService service) {
        this.service = service;
    }

    @PostMapping("/jobs")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Submit records for processing (ADMIN, OPERATOR)",
        description = """
            Returns **202 Accepted** with a PENDING job: submission is not execution. Poll GET /jobs/{id}.
            Send an `Idempotency-Key` header (8-100 chars) to make retries safe: the same key and body within 24 h
            returns the original job with **200** and `Idempotent-Replayed: true`; the same key with a different body
            returns **422**.""",
        responses = {
            @ApiResponse(responseCode = "202", description = "Job accepted"),
            @ApiResponse(responseCode = "200", description = "Idempotent replay of an earlier request"),
            @ApiResponse(responseCode = "409", description = "Source disabled or not a REST_PUSH source"),
            @ApiResponse(responseCode = "422", description = "Idempotency-Key reused with a different body")})
    public ResponseEntity<JobResponse> submit(
            @Valid @RequestBody SubmitRecordsRequest req,
            @Parameter(description = "Client-generated unique key, e.g. a UUID")
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            Principal principal) {
        if (key != null && !KEY.matcher(key).matches()) {
            throw new ValidationException("Idempotency-Key must be 8-100 characters of A-Z a-z 0-9 . _ : -");
        }
        IngestionService.Submission s = service.submit(req, key, principal.getName());
        return ResponseEntity.status(s.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
            .header("Idempotent-Replayed", String.valueOf(s.replayed()))
            .body(s.job());
    }

    @PostMapping(value = "/jobs/files", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Upload a CSV or JSON file for asynchronous processing",
        description = "Requires an Idempotency-Key. Uploads are limited to 5 MiB and 100000 records.")
    public ResponseEntity<JobResponse> submitFile(
            @RequestParam UUID sourceId,
            @RequestParam EntityType entityType,
            @RequestPart("file") MultipartFile file,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            Principal principal) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new ValidationException("Idempotency-Key must be 8-100 characters of A-Z a-z 0-9 . _ : -");
        }
        IngestionService.Submission submission =
            service.submitFile(sourceId, entityType, file, key, principal.getName());
        return ResponseEntity.status(submission.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
            .header("Idempotent-Replayed", String.valueOf(submission.replayed()))
            .body(submission.job());
    }

    @PostMapping("/sources/{sourceId}/jobs")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Queue an HTTP_API pull job")
    public ResponseEntity<JobResponse> submitPull(
            @PathVariable UUID sourceId,
            @RequestParam EntityType entityType,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            Principal principal) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new ValidationException("Idempotency-Key must be 8-100 characters of A-Z a-z 0-9 . _ : -");
        }
        IngestionService.Submission submission =
            service.submitPull(sourceId, entityType, key, principal.getName());
        return ResponseEntity.status(submission.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
            .header("Idempotent-Replayed", String.valueOf(submission.replayed()))
            .body(submission.job());
    }

    @GetMapping("/jobs")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public PageResponse<JobResponse> list(@RequestParam(required = false) UUID sourceId,
                                          @RequestParam(required = false) JobStatus status,
                                          @RequestParam(required = false) Instant submittedFrom,
                                          @RequestParam(required = false) Instant submittedTo,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return service.listJobs(sourceId, status, submittedFrom, submittedTo, page, size);
    }

    @GetMapping("/jobs/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public JobResponse get(@PathVariable UUID id) {
        return service.getJob(id);
    }

    @GetMapping("/jobs/{id}/errors")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Rejected (quarantined) records of a job (ADMIN, OPERATOR; not VIEWER)",
        description = "Returns reason codes and safe diagnostics only, never the raw source payload.")
    public PageResponse<RejectedRecordResponse> errors(@PathVariable UUID id,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "50") int size) {
        return service.rejected(id, page, size);
    }

    @GetMapping("/rejected-records")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Quarantined records across jobs (ADMIN, OPERATOR; not VIEWER)",
        description = "Newest first, filterable by source, job, entity type, reason code and time. Returns reason "
            + "codes and safe diagnostics only, never the raw source payload. Page size is capped at 100.")
    public PageResponse<QuarantinedRecordResponse> quarantine(@RequestParam(required = false) UUID sourceId,
                                                              @RequestParam(required = false) UUID jobId,
                                                              @RequestParam(required = false) EntityType entityType,
                                                              @RequestParam(required = false) String reasonCode,
                                                              @RequestParam(required = false) Instant processedFrom,
                                                              @RequestParam(required = false) Instant processedTo,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "50") int size) {
        return service.quarantine(sourceId, jobId, entityType, reasonCode, processedFrom, processedTo, page, size);
    }

    @GetMapping("/rejected-records/reasons")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Distinct quarantine reason codes with counts (ADMIN, OPERATOR; not VIEWER)")
    public java.util.List<ReasonCount> quarantineReasons(@RequestParam(required = false) UUID sourceId) {
        return service.quarantineReasons(sourceId);
    }

    @GetMapping("/jobs/{id}/attempts")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public PageResponse<JobAttemptResponse> attempts(@PathVariable UUID id,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return service.attempts(id, page, size);
    }

    @PostMapping("/jobs/{id}/replay")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Safely replay a failed job once")
    public JobResponse replay(@PathVariable UUID id, Principal principal) {
        return service.replay(id, principal.getName());
    }

    @PostMapping("/jobs/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Cancel a PENDING or RETRY_SCHEDULED job; 409 otherwise")
    public JobResponse cancel(@PathVariable UUID id) {
        return service.cancel(id);
    }
}
