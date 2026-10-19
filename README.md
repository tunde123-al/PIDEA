# PIDEA

PIDEA (Enterprise Application Integration & Data Engineering Platform) is an API-first integration and data-processing MVP. The current implementation uses a Java/Spring Boot API, PostgreSQL as the durable job queue and data store, and a Python worker for validation, transformation, quarantine, and idempotent loading. It does not include a web frontend.

## Current capabilities

- Register and manage integration-source metadata with role-checked REST endpoints.
- Submit up to 1,000 JSON records per request, receive a durable asynchronous job, and safely retry client submissions with an idempotency key.
- Upload private CSV/JSON files (5 MiB / 100,000-record limits) into durable file jobs consumed by the same worker pipeline.
- Poll job status, review paged job history, and inspect safe rejection diagnostics.
- Validate and normalize customer, order, and payment-event records; apply declarative field mappings; quarantine invalid records.
- Load business records with unique natural keys and PostgreSQL upserts.
- Trigger HTTPS `HTTP_API` pulls using a hostname allow-list, DNS-to-IP pinning, TLS hostname verification, bounded JSON pagination and response size, and the worker retry policy.
- Persist schedules for HTTP API pulls and replay failed jobs without discarding original attempts or source input.
- Retry transient worker/database failures with exponential backoff and jitter; recover stale worker claims without allowing an old attempt to overwrite a newer one.
- Record audit events, correlation IDs, execution attempts, and health/metrics endpoints.
- Run the API and worker with separate, least-privilege database roles using Docker Compose.

The REST adapter has deterministic mock-response and pinned-transport tests; it has not been verified against a live external service. `POSTGRES_TABLE` remains source metadata only. There is no OAuth client, webhook, frontend, or live CRM/ERP connector. HTTP pulls are read-only GETs; transient HTTP 429/5xx and network timeouts use the existing bounded worker retries, while other HTTP errors fail the job.

## Architecture and data flow

```text
Client --HTTP Basic--> Spring Boot API --Flyway/JDBC--> PostgreSQL
                                                    ^         |
                                                    |         | durable jobs/raw records
                                                    +-- Python worker
                                                        validate -> transform -> quarantine/upsert
```

- `backend/` contains the Java 21 / Spring Boot 3.5 API, Spring Security, JPA source metadata, JDBC job operations, Actuator, OpenAPI, and Flyway migrations.
- `data-engine/` contains the Python 3.12+ worker, Pydantic validation, file extractors, transformation, retry handling, and PostgreSQL loaders.
- Flyway owns DDL. Both services share the `integration` and `analytics` schemas but have separate runtime database roles. The API role has no `analytics` schema access; the worker role has the DML access needed to load analytics records.
- API uploads use a private shared Docker volume; the API writes generated filenames and the worker reads them without exposing the volume over HTTP.
- `backend/src/main/resources/db/migration/` is the authoritative schema history. Hibernate uses `ddl-auto: validate`; it does not create or modify schema objects.
- The existing integration tests use Testcontainers and apply the real Flyway schema to PostgreSQL 16.

## Prerequisites

- Docker Desktop or another Docker-compatible engine, running and available to Testcontainers.
- Java 21 for local backend development.
- Python 3.12 or newer for the worker and test tooling.
- Git and a terminal. Docker Compose v2 is used for the full local stack.

## Run the local stack

From the repository root in PowerShell:

```powershell
if (-not (Test-Path .env)) {
  Copy-Item .env.example .env
} else {
  Write-Output '.env already exists; keep it and edit only the settings you intend to change.'
}
```

Set unique, strong values for `POSTGRES_PASSWORD`, `PIDEA_DB_PASSWORD`, and `PIDEA_WORKER_DB_PASSWORD` in `.env`. Generate BCrypt password hashes from the repository root with:

```powershell
py -3.12 -m pip install -e '.\data-engine[dev]'
py -3.12 .\scripts\new_password_hash.py
```

The script prompts for a password without accepting it as a command-line argument. Put the resulting hash into `PIDEA_ADMIN_PASSWORD_HASH` (and optionally the operator/viewer hash settings) in `.env`. No account is created for a blank hash. Never commit `.env` or use production passwords in local development.

Start the services:

```powershell
docker compose up --build -d
docker compose ps
```

On first startup, PostgreSQL creates the runtime roles and the API applies Flyway migrations. The worker waits for the API health check before starting. Compose binds API and database ports to localhost only. Check health at `http://localhost:8080/actuator/health` and interactive API documentation at `http://localhost:8080/swagger-ui/index.html`.

If the default host ports are already occupied, set `PIDEA_DB_PORT` and/or `PIDEA_API_PORT` in `.env` to unused local ports before startup. The named `pidea-db` and `pidea-uploads` volumes are scoped to the Compose project name.

The named database volume persists across restarts. Do not remove it as a routine troubleshooting step; doing so deletes local database contents.

To pause the services without removing containers or persistent data:

```powershell
docker compose stop
```

Resume with `docker compose start`. To remove the containers and Compose network while retaining the named data volumes, use `docker compose down` without `-v`. Do not use `docker compose down -v` unless you intentionally want to delete the local database and uploads.

## Main API workflow

All `/api/**` endpoints require HTTP Basic authentication and role authorization. The `admin`, `operator`, and `viewer` usernames and example calls below are development/demo conventions only; use unique passwords and TLS, and do not reuse them in production.

1. Create a REST-push source as an administrator:

```sh
curl -u admin:'<password>' -H 'Content-Type: application/json' \
  -d '{"name":"demo-customers","type":"REST_PUSH","config":{}}' \
  http://localhost:8080/api/v1/integration-sources
```

Save the returned `id` as `SOURCE_ID`.

Example source response:

```json
{
  "id": "4c956e25-6678-4c50-8abe-3d33faed3813",
  "name": "demo-customers",
  "type": "REST_PUSH",
  "status": "ACTIVE",
  "config": {},
  "secretConfigured": false,
  "createdAt": "2025-01-01T00:00:00Z",
  "updatedAt": "2025-01-01T00:00:00Z"
}
```

2. Submit records as an operator. Use a new stable key for each logical submission; a retry with the same key and body returns the original job.

```sh
curl -u operator:'<password>' -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: customer-batch-0001' \
  -d '{"sourceId":"<SOURCE_ID>","entityType":"CUSTOMER","records":[{"customer_id":"C100","full_name":"Example Customer","email":"customer@example.test","created_at":"2025-01-01T00:00:00Z"}]}' \
  http://localhost:8080/api/v1/ingestion/jobs
```

The first response is `202 Accepted`: the job is queued, not yet complete. Poll `GET /api/v1/ingestion/jobs/{jobId}` until it reaches a terminal status. `GET /api/v1/ingestion/jobs` returns paged history; `GET /api/v1/ingestion/jobs/{jobId}/errors` returns paged reason codes and safe diagnostics, not raw source payloads. Reusing a key with a different body returns `422`.

Example initial job response:

```json
{
  "id": "8d5cf3ab-a395-4d68-ad15-1105d39b1dd7",
  "sourceId": "4c956e25-6678-4c50-8abe-3d33faed3813",
  "jobType": "RECORDS",
  "entityType": "CUSTOMER",
  "status": "PENDING",
  "correlationId": "a0844889-a632-4c50-b539-f75afc5e6b75",
  "recordsReceived": 1,
  "recordsAccepted": 0,
  "recordsRejected": 0,
  "recordsDuplicate": 0,
  "errorSummary": null,
  "retryCount": 0,
  "maxRetries": 3,
  "submittedAt": "2025-01-01T00:00:00Z",
  "startedAt": null,
  "completedAt": null,
  "durationMs": null
}
```

The REST contracts, validation schemas, authentication scheme, and error responses are also published by the OpenAPI UI. Health is public; actuator exposes only configured health/info/metrics endpoints.

### Upload and execute a file job

Register a `CSV_FILE` or `JSON_FILE` source, then upload a UTF-8 file with a unique idempotency key:

```sh
curl -u operator:'<password>' -H 'Idempotency-Key: customers-file-0001' \
  -F "sourceId=<SOURCE_ID>" -F "entityType=CUSTOMER" \
  -F "file=@customers.csv;type=text/csv" \
  http://localhost:8080/api/v1/ingestion/jobs/files
```

The endpoint validates extension/source type, encoding, CSV structure or JSON array shape, record count, and byte size before accepting a job. Worker-side record schema validation remains the same as for JSON record submissions. Invalid records are quarantined; file-level parse errors fail the job. Successful/partial files are deleted after processing. Failed files remain available for replay for up to 30 days; older failed uploads and abandoned temporary files are cleaned by the worker.

### Pull from an allow-listed HTTPS JSON endpoint

Create an `HTTP_API` source with a `baseUrl`. Optional config keys are `pageSize` (1-1000), `maxPages` (1-1000), `timeoutSeconds` (1-60), `pageParam`, and `pageSizeParam`. The endpoint returns a JSON array or `{"records":[...],"hasMore":false}`. Pages use query parameters (default `page` and `size`) and each response is limited to 5 MiB. `hasMore` controls continuation; without it, a short page ends the pull.

Set the worker's exact outbound hostname allow-list in `PIDEA_HTTP_API_ALLOWED_HOSTS`. Every DNS answer must be a public unicast address; HTTPS on port 443 is required. The adapter connects directly to one validated IP while preserving the configured hostname for TLS SNI, certificate hostname verification, and the HTTP `Host` header. It disables environment proxies and redirects, rejects mixed public/prohibited DNS answers, and fails closed on DNS errors or its bounded DNS timeout. Connection/read timeouts, streamed 5 MiB page limits, bounded pagination, and capped worker retries apply. Store bearer credentials only in `PIDEA_HTTP_API_BEARER_TOKEN` (or another dedicated worker environment variable) and set source `secretRef` to that variable name. Authorization is sent only to the allow-listed source host and is never forwarded through redirects.

Queue one pull:

```sh
curl -u operator:'<password>' -H 'Idempotency-Key: customers-pull-0001' -X POST \
  'http://localhost:8080/api/v1/ingestion/sources/<HTTP_SOURCE_ID>/jobs?entityType=CUSTOMER'
```

Create a durable schedule as an administrator with `POST /api/v1/integration-schedules` and body `{"sourceId":"<HTTP_SOURCE_ID>","entityType":"CUSTOMER","intervalSeconds":3600}`. Schedules accept intervals from 60 seconds to 30 days. The scheduler stores the next tick in PostgreSQL and uses row locks, so multiple API instances do not enqueue the same schedule tick. On restart, due work is recovered from the database; missed intervals are coalesced into one run rather than replayed as a backlog.

### Inspect and replay jobs

Filter the paginated list with `sourceId`, `status`, `submittedFrom`, and `submittedTo` ISO-8601 timestamps. `GET /api/v1/ingestion/jobs/{jobId}/attempts` returns paginated safe attempt diagnostics. Source health includes accepted/rejected counts and recent failed/completed job counts.

An `ADMIN` or `OPERATOR` may `POST /api/v1/ingestion/jobs/{jobId}/replay` for a `FAILED` job. The replay is a new job linked with `replayOf`, copies the original raw request or reuses the private uploaded file, and preserves the original attempt history. A unique database constraint makes repeated replay requests return the same replay job; only failed jobs can be replayed. Natural-key upserts prevent duplicate analytics rows. Partially successful jobs are not replayed automatically, avoiding reapplication of accepted financial records.

## Configuration

See [.env.example](./.env.example) for the supported local Compose variables. Important settings:

| Variable | Purpose |
| --- | --- |
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | Local database and schema-owner credentials |
| `PIDEA_DB_PASSWORD` | API runtime database password |
| `PIDEA_WORKER_DB_PASSWORD` | Worker runtime database password |
| `PIDEA_ADMIN_PASSWORD_HASH`, `PIDEA_OPERATOR_PASSWORD_HASH`, `PIDEA_VIEWER_PASSWORD_HASH` | BCrypt hashes for optional API users |
| `PIDEA_DB_URL`, `PIDEA_DB_USER`, `PIDEA_DB_PASSWORD` | API datasource settings when running outside Compose |
| `PIDEA_FLYWAY_USER`, `PIDEA_FLYWAY_PASSWORD` | Schema-owner credentials for Flyway |
| `PIDEA_DB_DSN`, `PIDEA_DATA_DIR` | Worker PostgreSQL DSN and optional safe file-ingestion directory |
| `PIDEA_UPLOAD_DIR` | API path to the private upload volume |
| `PIDEA_DB_PORT`, `PIDEA_API_PORT` | Optional local host ports |
| `PIDEA_HTTP_API_ALLOWED_HOSTS` | Exact comma-separated hostnames approved for outbound worker requests |
| `PIDEA_HTTP_API_BEARER_TOKEN` | Optional worker-only bearer token, referenced by name from a source |

Credentials are read from environment variables; source configuration stores only a validated credential reference, never the credential value. The default CORS allow-list is empty.

## Build, lint, and test

Backend (from the repository root):

```powershell
.\backend\mvnw.cmd -f backend\pom.xml -B test
```

Worker (from the repository root):

```powershell
py -3.12 -m pip install -e '.\data-engine[dev]'
Push-Location .\data-engine
py -3.12 -m ruff check src tests
py -3.12 -m pytest -q
Pop-Location
```

To run only the DNS/SSRF adapter tests (mocked DNS and HTTP; no live external requests), use
`py -3.12 -m pytest -q tests\test_rest_adapter.py` from `data-engine`.

The Java API and Python worker database tests use Testcontainers and require a running Docker engine. The pure Python transformation/extractor tests do not need a database. CI runs both test suites on GitHub-hosted Ubuntu runners with Docker available.

## Database and worker operations

- On a new database volume, `docker/db-init.sh` creates the API and worker roles with schema-scoped grants. The API container runs Flyway migrations before its health check succeeds. Migration V3 also revokes older API-role grants on the analytics schema and its default privileges without changing data.
- To inspect logs without printing secrets, use `docker compose logs --tail=100 api worker`.
- Worker jobs are claimed with `FOR UPDATE SKIP LOCKED`; writes, rejection rows, and final job counters are committed together. A failed or invalid record is quarantined with a reason code, not silently dropped.
- Job counters reconcile as `received = accepted + rejected + duplicate` for completed jobs. Reprocessing uses `(source_id, business key)` upserts.
- Failed uploads are retained up to 30 days for safe replay; cleanup checks managed UUID filenames and does not delete arbitrary sample files.
- HTTP_PULL job rows and attempt history are shared with the existing transactional worker. Scheduled due ticks are persisted and uniquely indexed.

## Security notes

- HTTP Basic is stateless and credentials are BCrypt-hashed. Configure non-empty unique hashes before enabling users; use TLS outside local development.
- The database runtime roles are distinct from the Flyway owner. Keep the `.env` file local and rotate any value that has been disclosed.
- Outbound HTTP pulls use an exact worker-wide hostname allow-list and pin each connection to a validated public IP; TLS certificate validation remains enabled. The system resolver has a bounded wait, but a timed-out OS resolver call may continue in a bounded daemon thread. Compose does not include an egress firewall, so operators must add network-level egress controls before production use. Do not let untrusted users control source registration or the worker allow-list.
- Sample payloads and test fixtures are synthetic. Do not use production credentials, endpoints, or financial records in tests.

## Known limitations and next steps

- No UI; the product surface is REST API and Swagger UI.
- No live CRM/ERP/database connectors, OAuth client, webhook receiver/signature verification, or API for creating mapping definitions.
- The REST adapter has deterministic tests for DNS/address validation and pinning, TLS hostname configuration, redirects, timeouts, interrupted and oversized responses; tests do not contact live external systems. Configure outbound network policy/egress firewall rules before production use; application-level checks are not a substitute for infrastructure egress controls.
- Python dependencies use bounded version ranges without a resolved lockfile, and container base images / CI actions use moving version tags. A future install or rebuild can resolve different compatible versions; create and maintain lockfiles and pin image/action digests before requiring bit-for-bit release reproducibility.
- No user lifecycle/access-management endpoints; users are configured from environment-provided password hashes.
- Operational dashboards, reconciliation reports, dead-letter queue tooling, and replay of partial jobs remain future work.
- Compose uses a local Docker volume and is not a production deployment topology. Production needs managed secrets, TLS termination, backup/restore, monitoring/alerting, and an explicit scaling/retention policy.

## Repository hygiene

The repository currently has no initial Git commit; check `git status` before preparing a commit. `.env`, local demo credentials, Python environments/caches, Java build output, and log files are ignored. Review all changes before committing and do not add local credentials or crash logs.
