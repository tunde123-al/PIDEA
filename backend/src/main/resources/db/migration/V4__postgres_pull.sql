-- POSTGRES_TABLE source connector: a new pull job type and a per-source extraction checkpoint.
-- Additive only: existing rows and the HTTP_PULL path are unaffected.

ALTER TABLE integration.ingestion_job DROP CONSTRAINT IF EXISTS ingestion_job_job_type_check;
ALTER TABLE integration.ingestion_job
    ADD CONSTRAINT ingestion_job_job_type_check
    CHECK (job_type IN ('RECORDS','FILE_CSV','FILE_JSON','HTTP_PULL','POSTGRES_PULL'));

-- Highest key value already loaded from a source table (text form of the key; the worker casts it back to the
-- key column's type). Advanced by the worker in the SAME transaction as the load, so a failed job never moves it.
-- A NULL cursor_value means "nothing loaded yet". Rows are locked (FOR UPDATE) by a running pull so two
-- concurrent jobs for one source/entity are serialised and the checkpoint can never move backwards.
CREATE TABLE integration.source_checkpoint (
    source_id     uuid         NOT NULL REFERENCES integration.integration_source(id),
    entity_type   varchar(20)  NOT NULL CHECK (entity_type IN ('CUSTOMER','ORDER','PAYMENT_EVENT')),
    cursor_key    varchar(200),   -- "schema.table.keyColumn" the cursor belongs to; a config change resets the cursor
    cursor_value  text,
    last_job_id   uuid,
    updated_at    timestamptz  NOT NULL,
    PRIMARY KEY (source_id, entity_type)
);
