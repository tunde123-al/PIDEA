-- Liveness signal for the Python worker(s): each worker upserts its own row every ~10 seconds. The API exposes
-- "workers alive" and "seconds since the last heartbeat" as metrics, so a growing queue with no live worker is visible.
-- A row is per worker process (hostname); rows of workers that stopped long ago are pruned by the workers themselves.
CREATE TABLE integration.worker_heartbeat (
    worker_id  varchar(100) PRIMARY KEY,
    started_at timestamptz  NOT NULL,
    last_seen  timestamptz  NOT NULL
);
