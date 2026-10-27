# PIDEA: notes for explaining the project

Written for a developer who has to understand this system well enough to explain and defend it, not to recite it. Where
something is a judgement call, or a gap, it says so; saying "I haven't verified that" is a stronger answer than
guessing. Companion documents: `README.md` (how to run it), `docs/DEMO.md` (a live walkthrough),
`docs/PRODUCTION_READINESS.md` (what is and is not proven).

## 1. The one-paragraph explanation

PIDEA takes data from several places (a JSON API call, an uploaded file, a pull from another HTTP API, a pull from another
PostgreSQL table), checks it, cleans it, and loads it into reliable tables, while being honest about what it rejected and
why. The web API only *accepts and queues* work. A separate Python worker does the processing. PostgreSQL is both the queue
and the store, which keeps the system small and lets a job's result be committed atomically with the data it produced.

## 2. What each part does

| Part | Responsibility | Where |
| --- | --- | --- |
| API (Java, Spring Boot) | Authenticate, authorise by role, validate requests, deduplicate by idempotency key, write a `PENDING` job, serve status, errors, quarantine and metrics | `backend/` |
| Worker (Python) | Claim jobs, extract records, validate and transform them, load them, quarantine the bad ones, retry transient failures, report a heartbeat, purge expired data | `data-engine/` |
| PostgreSQL | Job queue (`FOR UPDATE SKIP LOCKED`), raw payloads, quarantine, checkpoints, audit, analytics tables | migrations in `backend/src/main/resources/db/migration/` |
| Connectors | "Given this source's config, return the next batch of records." Two exist: HTTP API and PostgreSQL table | `data-engine/src/pidea_data/connectors.py` |
| Console (React) | Sources, runs, quarantine, schedules; role-aware, never the security boundary | `frontend/` |
| Monitoring (optional) | Prometheus and Grafana over metrics the API derives from the database | `docker/observability/` |

## 3. How data flows

1. A request arrives; the API checks identity and role, then (for pushes) stores the records and a `PENDING` job in one
   transaction and answers `202`. It does no processing.
2. The worker claims one job with `FOR UPDATE SKIP LOCKED` (so two workers never take the same job) and opens an attempt row.
3. Extraction depends on the job type. Pull jobs go through a connector; database pulls also read a *checkpoint*.
4. Pydantic models validate and normalise each record; a per-source mapping renames fields. A bad record gets a reason code
   and goes to quarantine. The reason never contains the offending value.
5. Valid records are **upserted** by natural key. Loading, quarantine rows, counters, the checkpoint and the final status all
   commit in **one transaction**. If anything fails, none of it happened.
6. Transient errors (network, timeout, database blip) schedule a retry with exponential backoff and jitter. Permanent errors
   fail the job with a curated message. A `FAILED` job can be replayed once.

## 4. Decisions and the reasons for them

- **PostgreSQL as the queue, not Kafka or RabbitMQ.** The volume does not justify a broker, and the big win is
  transactional: the data, the quarantine, the checkpoint and the job status commit together. A broker would need a
  second mechanism for exactly that. Cost: the queue is polled (every 2 s) and scale is bounded by the database.
- **Accept fast, process later.** The API stays responsive and simple; failures in processing cannot lose accepted work.
- **Idempotent upserts plus at-least-once delivery**, instead of promising exactly-once. Retries and replays are then
  always safe. I do not claim exactly-once because the tests do not justify it.
- **Separate database roles.** Flyway runs as the owner; the API role cannot read the analytics schema; the worker role has
  only the data access it needs. A bug or injection in the API cannot read loaded customer data directly.
- **The password never enters PIDEA's database.** A source stores a `secretRef` (a variable *name*). The worker resolves it
  from its environment, and database sources require the `PIDEA_SRC_` prefix, so a config cannot make the worker read its own
  credentials. A host allow-list is deny-by-default and exact.
- **Connector abstraction kept tiny.** One method (`pull`) and a registry. The worker owns everything else, so a new
  connector cannot bypass validation, idempotency or quarantine. I stopped there on purpose rather than building a plugin
  framework nobody asked for.
- **Metrics from the database, not from the worker.** The worker has no HTTP endpoint; the database is the shared truth.
  Cost: the numbers are refreshed every 15 s and some queries scan a table.
- **Method-level authorisation.** Every endpoint carries `@PreAuthorize`, so a new endpoint without a rule is easy to spot.
  The console's role detection only decides what to *show*; the server enforces everything.

## 5. Problems met along the way (and what they taught)

Be ready to tell these as stories; they show how the system was actually tested.

- **Port 5432 already in use / API starting before the database.** Compose reported the database healthy, but its health
  check used the Unix socket, which also succeeds during first-time initialisation while TCP is not yet open. The fix is a TCP
  health check plus Flyway and connection-pool retries. *Honest caveat:* I did not reproduce the original failure on a fresh
  volume, so I treat that as a well-founded explanation, not a proven cause.
- **The console unreachable at `localhost:5173`.** Node resolved `localhost` to IPv6 only. The dev server now binds the IPv4
  loopback explicitly (not `0.0.0.0`, so it is not exposed to the network).
- **A wrong database password was retried as if transient.** A test I wrote for the connector showed the worker would retry a
  rejected login pointlessly. psycopg gives no error code for connection-time failures, so classification uses the stable
  message text and the raised message never echoes it.
- **The worker ignored `docker stop`.** As PID 1, Python ignores SIGTERM without a handler, so a job could be stranded for 15
  minutes. It now finishes the job in hand and exits 0, and a test sends a real SIGTERM to the real process.
- **Data that never expired.** Reviewing growth showed idempotency keys were deleted only if reused, and raw customer payloads
  were kept forever. Both are now purged in bounded batches, and that exposed a latent bug: replaying a job whose input had
  been purged would have produced a job that could never reconcile. Replay now answers 409.
- **Slow queries nobody had measured.** On 300,000 jobs the default list took 105 ms (a full scan); two indexes brought it to
  0.16 ms. I measured before and after instead of guessing.
- **Security scan findings.** Trivy found fixable HIGH/CRITICAL issues in the API image (Tomcat, Jackson, the PostgreSQL
  driver), which I patched and re-scanned. Two Spring CVEs have no fix in the 6.2 line; they are accepted in writing with an
  expiry because the code has no view layer or SSE (a code-search judgement, not an exploit attempt).
- **Tests that could not fail.** The first accessibility scan found nothing, so I fed the scanner a deliberately broken page
  to prove it can fail. Likewise I mutated the HTTP adapter (follow redirects, disable TLS verification, add a retry) to
  prove the stub-server tests catch each.
- **My own mistakes.** An early version of my end-to-end script reused a fixed Compose project name and, on cleanup,
  deleted an old test volume I had not inspected; it was the dedicated e2e project, not the main data, but it is why the
  script now uses a unique name per run. Another script failed because `grep -q` closes a pipe early under `pipefail`.
  Mentioning these is fine and credible.
- **Tooling surprises.** Spring Boot disables the Prometheus endpoint in tests by default; an IDE Java server locks
  `target/` and breaks Maven; shell scripts break if Windows checks them out with CRLF (now pinned by `.gitattributes`).

## 6. Which tests prove what

| Claim | Evidence |
| --- | --- |
| Roles are enforced on the server | `IngestionApiTest`, `IntegrationSourceApiTest`, `WorkloadMetricsTest`; the Playwright viewer/operator tests assert real 403s |
| A job is never half-applied; retries are safe | `test_worker_db.py` (rollback after a partial load, transient vs permanent, stale-claim races) |
| Database connector is safe and correct | `test_pg_source.py` (injection attempts, allow-list, read-only session, key checks, checkpoint, failure paths) and `scripts/verify-pg-connector.sh` |
| HTTP transport behaves under failure | `test_http_stub_server.py` (real TLS, timeouts, truncation, redirects, no hidden retries) |
| A new connector needs no ingestion code | `test_connectors.py` (a stub connector written against the interface gets checkpointing, loading and status) |
| Metrics are real and access-controlled | `WorkloadMetricsTest`, `scripts/verify-observability.sh` |
| Graceful shutdown and retention work | `test_lifecycle.py`, `scripts/verify-observability.sh` step 8 |
| The console works with the real backend | 18 Playwright tests via `scripts/e2e.sh`, including axe accessibility scans |

## 7. Limitations to state plainly

Not production-ready (see `docs/PRODUCTION_READINESS.md`): HTTP Basic authentication with static users and no TLS in Compose; no
erasure path for loaded customer data; at-least-once rather than exactly-once; the database connector sees only new
higher-key rows (no updates or deletes); schedules for HTTP sources only; no load, failover or backup/restore testing; the
GitHub Actions workflows have never run; connectors tested only against local PostgreSQL and a local TLS stub; accessibility is
checked by automation only.

## 8. What I would do next

In order: run CI and fix what it finds; replace Basic auth with OIDC behind TLS; define the personal-data lifecycle (retention and
erasure for loaded records); test backup/restore and upgrades; load- and failure-test with several workers; wire real alert delivery;
extend connectors (change capture, webhooks, OAuth) behind the same interface.

## 9. Questions you should expect

- **"Why not a message broker?"** Section 4. The transactional guarantee is the reason; the volume is the justification.
- **"What happens if the worker dies mid-job?"** The transaction rolls back, so nothing is half-loaded. The job stays `RUNNING`
  until the 15-minute stale-claim recovery re-queues it. A graceful stop finishes the job first.
- **"Can the same record be loaded twice?"** Delivery is at-least-once but loads are upserts by natural key, and idempotency keys
  protect client retries, so repeats are harmless.
- **"How do you stop a source from becoming an SSRF hole?"** HTTP: HTTPS 443 only, exact host allow-list, public-IP-only answers,
  the validated address pinned for the connection, redirects not followed, TLS verified. Database: exact `host:port` allow-list,
  deny by default, read-only session, prefixed secret names. The network egress policy must back this up in production.
- **"Is it secure?"** I would not say that. Specific controls are tested; the identity model, TLS, data lifecycle and egress
  controls are not production-grade yet.
- **"What would break first at scale?"** The polling queue and single-table scans (job counts, OFFSET pagination), then the
  in-memory batch per job. Each has a known remedy in the production-readiness document.
