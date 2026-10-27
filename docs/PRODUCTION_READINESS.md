# PIDEA production-readiness assessment

**Verdict: not production-ready.** PIDEA is a well-tested integration and data-engineering platform suitable for local
development, demonstrations and an internal pilot with synthetic or low-risk data. The gaps that block production use
are listed below; most are about the environment around the application (TLS, identity, backups, monitoring) rather
than the ingestion logic, but several are application gaps (authentication model, personal-data erasure).

Evidence below names the test or script that demonstrates each claim. "Verified" means it was run and observed to pass
in this repository's development environment (Windows + Docker Desktop, Linux containers). **None of the GitHub
Actions jobs have been executed yet**, so every CI claim is "configured and syntax-checked", not "proven green".

## 1. Implemented and verified

| Capability | Evidence |
| --- | --- |
| Clean first start, including when host port 5432 is occupied | `scripts/dev-up.ps1` run on a machine where another container held 5432; TCP-based database health check; compose config validated |
| Recovery from a database restart | Database container restarted while the stack ran: API stayed healthy, worker backed off (2 s, 4 s) and resumed |
| Role-based access control enforced on the server | Every one of the 21 `/api/v1` endpoints carries `@PreAuthorize` (code review); 403/401 cases in `IngestionApiTest`, `IntegrationSourceApiTest`, `WorkloadMetricsTest`; real-stack 403s in the Playwright suite |
| Ingestion pipeline: validation, mapping, quarantine, idempotent upserts, retries with backoff, stale-claim recovery, replay | 183 worker tests against a real PostgreSQL built from the real Flyway migrations; 46 backend tests |
| Idempotent submission and duplicate handling | `IngestionApiTest` (same key and body returns the original job; different body returns 422); worker duplicate counters |
| HTTP API pull transport | `test_http_stub_server.py`: real TLS handshake with hostname verification, timeouts, truncated/oversized/malformed bodies, redirects not followed, no hidden retries; mutation-checked (turning off redirects-disabled, TLS verification or `max_retries=0` makes a test fail) |
| HTTP API address safety (HTTPS 443 only, allow-list, public-IP-only, DNS pinning) | `test_rest_adapter.py` with a mocked resolver. Not exercised against a live external service |
| PostgreSQL table connector: read-only session, host allow-list (deny by default), `PIDEA_SRC_*` secret references, unique-key checkpoint, quarantine, replay, safe failures | 40 tests in `test_pg_source.py` plus `scripts/verify-pg-connector.sh` on a real isolated stack |
| Database migrations V1 to V7 apply to a database that already holds data | V4 to V7 applied on top of a populated V1 to V3 database (1,000 jobs); V7 indexes built on 300,000 jobs |
| Job-listing query performance | Measured on 300,000 jobs: default job-list page 105 ms to 0.16 ms, status-filtered list 47 ms to 0.15 ms, stale-job check 52 ms to 0.13 ms after migration V7 |
| Metrics reflect real state; access restricted to ADMIN/VIEWER | `WorkloadMetricsTest`, and `scripts/verify-observability.sh` (counts after real jobs, worker heartbeat, Prometheus scrape and rules, every dashboard query, Grafana provisioning) |
| Correlation id traceable API response to API log line to worker log line | `scripts/verify-observability.sh` step 7; `AccessLogTest` (no query strings, no forged ids) |
| Graceful worker shutdown | SIGTERM sent to the real `python -m pidea_data` process exits 0 in seconds; a job in progress finishes first (`test_lifecycle.py`); `docker compose stop worker` timed in `scripts/verify-observability.sh` step 8 |
| Bounded data retention | `test_lifecycle.py`: expired idempotency keys and old raw payloads purged in bounded batches; recent, running and waiting jobs untouched; replay of a purged job refused with 409 (`IngestionApiTest`) |
| Console: quarantine viewer, role-aware UI, loading/empty/error states | 43 unit tests; 18 Playwright tests against the real stack |
| No serious automated accessibility violations (WCAG 2.0/2.1 A and AA rules axe can check) | Playwright axe scans of every page for the relevant roles; the scanner was confirmed to detect planted violations |
| Supply-chain pinning and scanning | Actions pinned to commit SHAs, base images to digests; Trivy run locally on both images (API clean after dependency patches; worker has only unfixable OS-package findings) |

## 2. Implemented but insufficiently verified

- **All GitHub Actions workflows.** Syntax-checked with actionlint only. In particular the `e2e`, `pg-connector`,
  `observability` and `security-scan` jobs have never run on a hosted runner (Playwright browser install, Docker
  build times and Trivy database downloads are the likely friction points).
- **Linux and macOS developer paths.** `scripts/dev-up.ps1` is Windows PowerShell; the bash scripts were run under Git
  Bash on Windows and in Linux containers, not on a native Linux or macOS host.
- **The PostgreSQL connector against a real remote database.** Only a local PostgreSQL 16 container without TLS was
  used; `sslmode=verify-full` against a managed service is untested.
- **The HTTP API connector against any live service.**
- **Concurrency at scale.** Claims use `FOR UPDATE SKIP LOCKED`, row-locked checkpoints and unique indexes, with unit
  tests for races; there is no multi-worker or load test, and no soak test of the 15-minute stale-claim recovery
  under real worker crashes.
- **Resource sizing.** Memory limits (API 640 MB, worker 256 MB, Prometheus 512 MB, Grafana 384 MB) are untested under
  load. A worker holds up to 100,000 records of one pull in memory.
- **Backup and restore**, and restoring from a backup into a newer schema version.
- **Prometheus alert rules.** They load and evaluate, but no failure scenario was staged to see an alert fire, and no
  Alertmanager is bundled.
- **Accessibility beyond automation.** No manual screen-reader, keyboard-only, zoom/reflow or colour-vision audit.
- **Security review depth.** The SSRF, DNS-pinning, upload-validation and redirect code was covered by its existing
  tests and the new transport tests but did not receive a fresh line-by-line review in this work.

## 3. Known limitations

**Security and identity**
- Authentication is HTTP Basic against users defined in environment variables (BCrypt hashes). No SSO, no MFA, no
  password rotation without a restart, no account lockout or rate limiting on failed logins. The console keeps the
  credential in browser memory and re-sends it on every request.
- The Swagger UI and OpenAPI document are public (they expose API shape, not data).
- No TLS anywhere in Compose; ports are bound to loopback.
- Two CRITICAL `spring-webmvc` CVEs (CVE-2026-47884, CVE-2026-47890) have no fix in Spring Framework 6.2.x. They are
  accepted in `.trivyignore.yaml` (expiring 2026-12-31) on the grounds that the API has no view layer, XsltView or SSE;
  that is a code-search judgement, not an exploit attempt.
- Dev-tool advisories (3 moderate, `npm audit`) remain in the frontend toolchain; production dependencies are clean.

**Data and privacy**
- Customer data (name, email) is stored in `analytics.*` indefinitely. There is no erasure or export API, so a
  right-to-erasure request would need manual SQL.
- Raw submitted payloads are kept 30 days by default (`PIDEA_RAW_RETENTION_DAYS`). Job rows, attempts, rejected-record
  diagnostics and audit events are kept forever.
- Quarantine diagnostics are reason codes and short safe details, never raw values; this also means a rejected record
  cannot be corrected and re-ingested from quarantine alone.

**Processing semantics**
- Delivery is at-least-once with idempotent upserts, not exactly-once.
- The PostgreSQL connector sees only rows whose key is above its checkpoint: updates to old rows and deletes are not
  detected; it handles one table with a unique, not-null, single-column integer, numeric, date or timestamp key.
- Schedules exist for HTTP API sources only.
- Partial jobs are not replayable; only whole `FAILED` jobs, once.
- A job's records are processed in one transaction held in memory (at most 100,000 records).

**Operations**
- Job-list pagination uses OFFSET (slow for very deep pages). The per-status count in the metrics is a table scan
  (about 65 ms at 300,000 jobs, run every 15 s).
- Prometheus counters are cumulative sums over stored jobs and only decrease if jobs are deleted; there is no job
  retention.
- Failure categories exist in worker logs but not as metrics.
- Compose with a single database container is a development topology.

## 4. Recommended improvements, by risk and value

1. **Run CI on GitHub and fix what it finds** (every claim in section 2 depends on it).
2. **Replace Basic auth with OIDC/JWT behind TLS**, keep the three roles, add login throttling at the proxy, and put
   credentials in a secret manager. The `@PreAuthorize` rules would not need to change.
3. **Personal-data lifecycle**: a documented retention period for `analytics.*`, an erasure/export procedure or
   endpoint, and encryption of data at rest at the database/volume layer.
4. **Backup, restore and upgrade runbook**, tested, including restoring into a newer migration version.
5. **Load and failure testing**: multiple workers, worker crash mid-job, database failover, large tables through the
   connector, 100,000-record jobs under the memory limit.
6. **Real alert delivery** (Alertmanager or the organisation's monitoring) with staged failure drills for the five
   shipped alerts.
7. **Job and audit retention policy** and keyset pagination for the job list.
8. **Connector hardening**: verified TLS against a managed database, update/delete capture (a modified-at cursor or CDC),
   and scheduling for database sources.
9. **Manual accessibility audit** with a screen reader and keyboard only, then lift the limitation note.
10. **Dependency upkeep**: Spring Boot 4 migration (clears the accepted CVEs), toolchain advisory cleanup, SBOM
    generation.

## 5. What a production deployment needs

- **Network and identity:** TLS termination in a reverse proxy or ingress with HSTS; OIDC provider; the API and
  database not reachable from the internet; egress firewall for the worker (the HTTP and database allow-lists are
  application-level and assume the network enforces the same).
- **Database:** managed PostgreSQL 16+ with automated backups and point-in-time recovery, TLS enforced
  (`sslmode=verify-full` for sources), separate owner / API / worker roles as already designed, and a sensible
  `max_connections` against the API pool (`PIDEA_DB_POOL_SIZE`, default 10) plus the worker connection(s).
- **Secrets:** a secret manager injecting `PIDEA_*` password hashes, database passwords, `PIDEA_SRC_*` source
  passwords and the metrics password; rotation procedure.
- **Runtime:** an orchestrator that restarts and reschedules (Compose's `restart: unless-stopped` covers only a single
  host), at least two API replicas (stateless; the schedule runner and metrics are safe to run concurrently) and two
  workers (job claiming is `SKIP LOCKED`), resource requests and limits from load testing.
- **Observability:** metrics scraped by the organisation's Prometheus, logs shipped from JSON stdout (API logs are JSON
  in Compose; the worker always logs JSON) to central storage with retention, alert routing for the five shipped rules.
- **Governance:** data-protection review of the stored fields, retention settings agreed with the data owner, an
  access-review process for the three roles, and image/dependency scanning enforced as a required CI check.
