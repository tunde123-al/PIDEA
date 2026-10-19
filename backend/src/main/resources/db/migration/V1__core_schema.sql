-- PIDEA core schema.
-- Ownership: Java (Flyway) owns DDL. The Python worker only reads/writes rows.
-- All timestamps are timestamptz and are written in UTC by both components.
CREATE SCHEMA IF NOT EXISTS integration;  -- operational data: sources, jobs, raw, rejects, audit
CREATE SCHEMA IF NOT EXISTS analytics;    -- validated business records and reports

CREATE TABLE integration.integration_source (
    id           uuid PRIMARY KEY,
    name         varchar(100) NOT NULL UNIQUE,
    source_type  varchar(30)  NOT NULL CHECK (source_type IN ('REST_PUSH','CSV_FILE','JSON_FILE','HTTP_API','POSTGRES_TABLE')),
    status       varchar(20)  NOT NULL CHECK (status IN ('ACTIVE','DISABLED')),
    config       jsonb        NOT NULL DEFAULT '{}'::jsonb,   -- non-secret settings only
    secret_ref   varchar(200),                                 -- NAME of an env var / secret, never the secret itself
    created_at   timestamptz  NOT NULL,
    updated_at   timestamptz  NOT NULL,
    version      bigint       NOT NULL DEFAULT 0
);

-- Versioned, declarative field mappings (no executable expressions).
CREATE TABLE integration.mapping_definition (
    id              uuid PRIMARY KEY,
    source_id       uuid        NOT NULL REFERENCES integration.integration_source(id),
    entity_type     varchar(20) NOT NULL CHECK (entity_type IN ('CUSTOMER','ORDER','PAYMENT_EVENT')),
    version         integer     NOT NULL CHECK (version > 0),
    field_mappings  jsonb       NOT NULL,   -- {"client_ref": "customer_id", ...}
    created_at      timestamptz NOT NULL,
    created_by      varchar(100) NOT NULL,
    UNIQUE (source_id, entity_type, version)
);

CREATE TABLE integration.ingestion_job (
    id                uuid PRIMARY KEY,
    source_id         uuid        NOT NULL REFERENCES integration.integration_source(id),
    job_type          varchar(20) NOT NULL CHECK (job_type IN ('RECORDS','FILE_CSV','FILE_JSON','HTTP_PULL')),
    entity_type       varchar(20) NOT NULL CHECK (entity_type IN ('CUSTOMER','ORDER','PAYMENT_EVENT')),
    status            varchar(30) NOT NULL CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','PARTIALLY_SUCCEEDED','FAILED','RETRY_SCHEDULED','CANCELLED')),
    correlation_id    varchar(64) NOT NULL,
    file_ref          varchar(300),               -- safe, server-generated storage key (never a client path)
    mapping_version   integer,
    records_received  integer     NOT NULL DEFAULT 0 CHECK (records_received >= 0),
    records_accepted  integer     NOT NULL DEFAULT 0 CHECK (records_accepted >= 0),
    records_rejected  integer     NOT NULL DEFAULT 0 CHECK (records_rejected >= 0),
    records_duplicate integer     NOT NULL DEFAULT 0 CHECK (records_duplicate >= 0),
    error_summary     varchar(1000),
    retry_count       integer     NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    max_retries       integer     NOT NULL DEFAULT 3 CHECK (max_retries >= 0),
    next_attempt_at   timestamptz NOT NULL,
    submitted_at      timestamptz NOT NULL,
    started_at        timestamptz,
    completed_at      timestamptz,
    submitted_by      varchar(100) NOT NULL,
    version           bigint      NOT NULL DEFAULT 0,
    -- counters must reconcile once a job has finished: received = accepted + rejected + duplicate
    CONSTRAINT ck_job_counts CHECK (
        status NOT IN ('SUCCEEDED','PARTIALLY_SUCCEEDED')
        OR records_received = records_accepted + records_rejected + records_duplicate)
);
-- the worker's claim query: oldest runnable job first
CREATE INDEX ix_job_claim ON integration.ingestion_job (next_attempt_at) WHERE status IN ('PENDING','RETRY_SCHEDULED');
CREATE INDEX ix_job_source ON integration.ingestion_job (source_id, submitted_at DESC);

CREATE TABLE integration.job_attempt (
    id           uuid PRIMARY KEY,
    job_id       uuid        NOT NULL REFERENCES integration.ingestion_job(id),
    attempt_no   integer     NOT NULL,
    outcome      varchar(20) NOT NULL CHECK (outcome IN ('RUNNING','SUCCEEDED','FAILED')),
    error        varchar(1000),
    started_at   timestamptz NOT NULL,
    finished_at  timestamptz,
    UNIQUE (job_id, attempt_no)
);

-- Records submitted through the JSON API, kept raw until the worker processes them.
CREATE TABLE integration.raw_record (
    job_id   uuid    NOT NULL REFERENCES integration.ingestion_job(id),
    seq      integer NOT NULL,
    payload  jsonb   NOT NULL,
    PRIMARY KEY (job_id, seq)
);

-- Idempotency: unique per (principal scope, key). The fingerprint is a SHA-256 of the canonical request.
CREATE TABLE integration.idempotency_record (
    id                   uuid PRIMARY KEY,
    scope                varchar(100) NOT NULL,
    idempotency_key      varchar(100) NOT NULL,
    request_fingerprint  char(64)     NOT NULL,
    job_id               uuid REFERENCES integration.ingestion_job(id),
    created_at           timestamptz  NOT NULL,
    expires_at           timestamptz  NOT NULL,
    UNIQUE (scope, idempotency_key)
);

CREATE TABLE integration.rejected_record (
    id                  uuid PRIMARY KEY,
    job_id              uuid        NOT NULL REFERENCES integration.ingestion_job(id),
    source_id           uuid        NOT NULL REFERENCES integration.integration_source(id),
    entity_type         varchar(20) NOT NULL,
    source_record_ref   varchar(200) NOT NULL,   -- row number / array index / source key
    reason_code         varchar(50) NOT NULL,
    details             varchar(500),            -- safe diagnostics only; never the raw payload
    processed_at        timestamptz NOT NULL
);
CREATE INDEX ix_rejected_job ON integration.rejected_record (job_id);

CREATE TABLE integration.audit_event (
    id              uuid PRIMARY KEY,
    actor           varchar(100) NOT NULL,
    action          varchar(60)  NOT NULL,
    entity_type     varchar(40)  NOT NULL,
    entity_id       varchar(100),
    details         jsonb,
    correlation_id  varchar(64),
    created_at      timestamptz  NOT NULL
);
CREATE INDEX ix_audit_created ON integration.audit_event (created_at DESC);

-- Business records. Natural key = (source_id, external id) makes loads idempotent via upsert.
CREATE TABLE analytics.customer (
    id           uuid PRIMARY KEY,
    source_id    uuid         NOT NULL REFERENCES integration.integration_source(id),
    customer_id  varchar(64)  NOT NULL,
    full_name    varchar(200) NOT NULL,
    email        varchar(254),
    created_at   timestamptz  NOT NULL,   -- business "registered on" time
    job_id       uuid         NOT NULL REFERENCES integration.ingestion_job(id),
    loaded_at    timestamptz  NOT NULL,
    UNIQUE (source_id, customer_id)
);

CREATE TABLE analytics.customer_order (
    id           uuid PRIMARY KEY,
    source_id    uuid          NOT NULL REFERENCES integration.integration_source(id),
    order_id     varchar(64)   NOT NULL,
    customer_id  varchar(64)   NOT NULL,
    amount       numeric(19,4) NOT NULL CHECK (amount >= 0),
    currency     char(3)       NOT NULL,
    created_at   timestamptz   NOT NULL,
    job_id       uuid          NOT NULL REFERENCES integration.ingestion_job(id),
    loaded_at    timestamptz   NOT NULL,
    UNIQUE (source_id, order_id)
);

CREATE TABLE analytics.payment_event (
    id              uuid PRIMARY KEY,
    source_id       uuid          NOT NULL REFERENCES integration.integration_source(id),
    transaction_id  varchar(64)   NOT NULL,
    order_id        varchar(64),
    amount          numeric(19,4) NOT NULL CHECK (amount > 0),
    currency        char(3)       NOT NULL,
    event_type      varchar(20)   NOT NULL CHECK (event_type IN ('AUTHORISED','CAPTURED','REFUNDED','FAILED')),
    created_at      timestamptz   NOT NULL,
    job_id          uuid          NOT NULL REFERENCES integration.ingestion_job(id),
    loaded_at       timestamptz   NOT NULL,
    UNIQUE (source_id, transaction_id)
);

-- ELT staging: loaded as text first, typed and validated by SQL inside PostgreSQL.
CREATE TABLE integration.stg_payment_event (
    job_id          uuid        NOT NULL REFERENCES integration.ingestion_job(id),
    seq             integer     NOT NULL,
    transaction_id  text,
    order_id        text,
    amount          text,
    currency        text,
    event_type      text,
    created_at      text,
    PRIMARY KEY (job_id, seq)
);

-- Per-source incremental watermark.
CREATE TABLE integration.source_watermark (
    source_id     uuid        NOT NULL REFERENCES integration.integration_source(id),
    entity_type   varchar(20) NOT NULL,
    watermark     timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    PRIMARY KEY (source_id, entity_type)
);
