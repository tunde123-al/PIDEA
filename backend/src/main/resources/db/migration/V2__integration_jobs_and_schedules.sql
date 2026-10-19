ALTER TABLE integration.ingestion_job
    ADD COLUMN replay_of uuid REFERENCES integration.ingestion_job(id),
    ADD COLUMN schedule_id uuid,
    ADD COLUMN scheduled_for timestamptz;

CREATE UNIQUE INDEX ux_ingestion_job_replay_of
    ON integration.ingestion_job (replay_of)
    WHERE replay_of IS NOT NULL;

CREATE UNIQUE INDEX ux_ingestion_job_schedule_tick
    ON integration.ingestion_job (schedule_id, scheduled_for)
    WHERE schedule_id IS NOT NULL;

CREATE TABLE integration.integration_schedule (
    id               uuid PRIMARY KEY,
    source_id        uuid NOT NULL REFERENCES integration.integration_source(id),
    entity_type      varchar(20) NOT NULL CHECK (entity_type IN ('CUSTOMER','ORDER','PAYMENT_EVENT')),
    interval_seconds integer NOT NULL CHECK (interval_seconds BETWEEN 60 AND 2592000),
    enabled          boolean NOT NULL DEFAULT true,
    next_run_at      timestamptz NOT NULL,
    created_at       timestamptz NOT NULL,
    created_by       varchar(100) NOT NULL,
    version          bigint NOT NULL DEFAULT 0
);

CREATE INDEX ix_schedule_due
    ON integration.integration_schedule (next_run_at, id)
    WHERE enabled;

ALTER TABLE integration.ingestion_job
    ADD CONSTRAINT fk_ingestion_job_schedule
        FOREIGN KEY (schedule_id) REFERENCES integration.integration_schedule(id),
    ADD CONSTRAINT ck_ingestion_job_schedule_origin
        CHECK ((schedule_id IS NULL) = (scheduled_for IS NULL));
