-- The job list (newest first, optionally filtered by status) and the status-count queries behind the metrics were
-- full table scans. Measured on 300,000 jobs: the default list page took ~105 ms (parallel sequential scan) and the
-- cost grew linearly with history. These two indexes serve the ORDER BY directly and the status filters/counts.
-- Additive only.
CREATE INDEX ix_job_submitted        ON integration.ingestion_job (submitted_at DESC, id);
CREATE INDEX ix_job_status_submitted ON integration.ingestion_job (status, submitted_at DESC, id);
