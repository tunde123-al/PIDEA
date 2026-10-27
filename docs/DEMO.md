# PIDEA demonstration (about 15 minutes)

Written for a technical interview or walkthrough. It uses **test data only**: the sample records below and the
seeded demo database are synthetic. Never put real customer data or production credentials into this stack, and do
not show `.env` on screen.

Run the `curl` blocks in **Git Bash** (or any POSIX shell). They read passwords with `read -rs`, so nothing secret lands
in your shell history or on screen.

## Before you start (5 minutes, once)

1. Docker Desktop is running. Node 20+ is installed.
2. Create the BCrypt hashes for two accounts and put them in `.env` (the file is created by the next step if missing):
   ```powershell
   py -3.12 -m pip install -e '.\data-engine[dev]'
   py -3.12 .\scripts\new_password_hash.py      # run twice: once for admin, once for operator
   ```
   Set `PIDEA_ADMIN_PASSWORD_HASH` and `PIDEA_OPERATOR_PASSWORD_HASH` in `.env`. Choose throwaway passwords.
3. For the database-connector part, also add to `.env`:
   ```text
   PIDEA_DEMO_SOURCE_PASSWORD=<any throwaway value>
   PIDEA_PG_SOURCE_ALLOWED_HOSTS=demo-source:5432
   ```
4. Do the slow first start **before** the audience arrives (image builds take several minutes the first time):
   ```powershell
   .\scripts\dev-up.ps1
   docker compose --profile demo up -d --build
   ```
   If you want the monitoring part, also set `PIDEA_METRICS_PASSWORD` (the plaintext viewer password; add a viewer hash
   too) and `GRAFANA_ADMIN_PASSWORD`, then `docker compose --profile observability up -d`. Grafana's first start can
   take several minutes, so start it early.

**No-setup fallback.** `bash scripts/verify-pg-connector.sh` runs the whole connector scenario below automatically on a
throwaway stack with generated passwords, and prints a pass/fail line per step. `bash scripts/verify-observability.sh`
does the same for monitoring. Use them if the live demo misbehaves.

## 1. Start and health (1 minute)

Say: "One command starts the database, the API and the worker. It checks the tools, creates `.env` only if missing,
picks a free database port if 5432 is taken, and waits for real health checks."

```powershell
.\scripts\dev-up.ps1          # already done; run it again to show it is idempotent and reports the URLs
docker compose ps             # db, api, worker all healthy / running
```
```sh
curl -s http://127.0.0.1:8080/actuator/health        # {"status":"UP",...}
```
Point out: the API only starts after the database health check passes (a TCP check, not just "container running"),
Flyway applies the migrations on start, and the worker waits for the API.

## 2. The architecture, as built (2 minutes)

Open the diagram in `README.md` ("Architecture and data flow"). Walk the six numbered steps: intake returns `202` and
only queues; the worker claims with `SKIP LOCKED`; extraction, validation, loading, the checkpoint and the job status
commit in **one transaction**. Mention the separate database roles: the API role cannot read the analytics schema.

## 3. A real ingestion with a bad record (3 minutes)

Sign in to the console at **http://127.0.0.1:5173** as the operator (or use curl as below). Create a source as admin first.

```sh
read -rs ADMIN_PW; read -rs OPERATOR_PW       # type each password, press Enter
API=http://127.0.0.1:8080

SOURCE_ID=$(curl -s -u "admin:$ADMIN_PW" -H 'Content-Type: application/json' \
  -d '{"name":"demo-push","type":"REST_PUSH","config":{}}' $API/api/v1/integration-sources | node -e 'process.stdin.on("data",d=>console.log(JSON.parse(d).id))')

curl -s -u "operator:$OPERATOR_PW" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-batch-0001' -H 'X-Correlation-Id: demo-trace-0001' \
  -d "{\"sourceId\":\"$SOURCE_ID\",\"entityType\":\"CUSTOMER\",\"records\":[
    {\"customer_id\":\"D1\",\"full_name\":\"Ada Example\",\"email\":\"ada@example.test\",\"created_at\":\"2025-01-01T00:00:00Z\"},
    {\"customer_id\":\"D2\",\"full_name\":\"Grace Sample\",\"email\":\"grace@example.test\",\"created_at\":\"2025-01-02T00:00:00Z\"},
    {\"customer_id\":\"D3\",\"full_name\":\"Broken Email\",\"email\":\"nope\",\"created_at\":\"2025-01-03T00:00:00Z\"}]}" \
  $API/api/v1/ingestion/jobs
```
Show: the `202` with status `PENDING` (queued, not processed). Re-send the **same** command: you get the original job back
with `200` (idempotency). In the console open **Runs**, then the run: status `PARTIALLY SUCCEEDED`, 2 accepted, 1 rejected.
Open **Quarantine**: `INVALID_EMAIL` for that record, with a safe detail and no raw value. Mention that viewers cannot see
this page and the server answers 403 even if the UI were bypassed.

## 4. The PostgreSQL connector (4 minutes)

Say: "A source can also be a table in another database. The password never enters PIDEA's database: the source stores only
the *name* of an environment variable. The worker may only connect to hosts on an allow-list, uses a read-only session,
and resumes from a checkpoint."

```sh
curl -s -u "admin:$ADMIN_PW" -H 'Content-Type: application/json' -d '{
  "name":"demo-crm","type":"POSTGRES_TABLE","secretRef":"PIDEA_SRC_DEMO_PASSWORD",
  "config":{"host":"demo-source","port":5432,"database":"crm","user":"pidea_reader","sslmode":"disable",
            "schema":"crm","table":"customers","keyColumn":"id","batchSize":2,"maxRows":5}}' \
  $API/api/v1/integration-sources
```
(`sslmode=disable` is only because the throwaway demo database has no TLS; the default is `require`.)
Trigger a pull from the source's page in the console, or:
```sh
PG_ID=<id from the response above>
curl -s -u "operator:$OPERATOR_PW" -X POST -H 'Idempotency-Key: demo-pull-0001' \
  "$API/api/v1/ingestion/sources/$PG_ID/jobs?entityType=CUSTOMER"
```
The demo table has 8 customers, one with a bad e-mail. First pull: 5 rows read, 4 loaded, 1 quarantined. Pull again with a
new key: the remaining 3. Pull a third time: 0 new rows. Add a row at the source and pull once more to see only it:
```sh
read -rs DEMO_PW      # the PIDEA_DEMO_SOURCE_PASSWORD value from .env
docker compose exec -T -e PGPASSWORD="$DEMO_PW" demo-source psql -q -U demo_owner -d crm -c \
  "INSERT INTO crm.customers (customer_id, full_name, email, created_at) VALUES ('D9','Late Arrival','late@example.test', now())"
```
Say what it guarantees: **at-least-once
with idempotent upserts**, not exactly-once, and updates to rows below the checkpoint are not seen.

## 5. A safe failure and a replay (2 minutes)

Edit the source (admin) so its host is not on the worker's allow-list, trigger a pull, and show the job `FAILED` with the
message "host is not in the worker allow-list" and no password, host or SQL in it:
```sh
curl -s -u "admin:$ADMIN_PW" -X PATCH -H 'Content-Type: application/json' -d '{"config":{"host":"blocked-host.example","port":5432,"database":"crm","user":"pidea_reader","sslmode":"disable","schema":"crm","table":"customers","keyColumn":"id"}}' $API/api/v1/integration-sources/$PG_ID
```
Trigger a pull (new idempotency key), then restore the config with the original body from step 4, then **Replay** the
failed run from its page (or `POST /api/v1/ingestion/jobs/{id}/replay`). It succeeds, and the failed attempt stays in the
history. Mention that the checkpoint never moves on a failed job.

## 6. Observability (1 minute, optional)

Open Grafana at http://127.0.0.1:3000 (user `admin`, the password you set) and the **PIDEA overview** dashboard: queue depth,
workers alive, records per minute, quarantine rate. Then follow one id end to end:
```sh
docker compose logs --no-log-prefix api worker | grep demo-trace-0001
```
The same correlation id appears in the API's request log and the worker's "job finished" line, and on the job itself.

## 7. Tests and limits (2 minutes)

Say what verifies it, and be straightforward about the gaps:
- 46 backend tests, 183 worker tests, 43 console tests, and 18 browser tests against the real stack (including automated
  accessibility checks); two scripts that verify the connector and the monitoring on throwaway stacks.
- CI workflows are written but **have not yet run on GitHub**.
- It is **not production-ready**: HTTP Basic auth with static users, no TLS in Compose, no erasure path for loaded customer
  data, no load or failover testing, connectors only tested against local PostgreSQL and a local TLS stub. The full list
  is in `docs/PRODUCTION_READINESS.md`.

## Clean-up

```powershell
.\scripts\dev-down.ps1                     # stops everything and keeps the database
docker compose --profile demo --profile observability stop
```
Do **not** use `docker compose down -v` unless you intend to delete the local database.

## If something goes wrong

| Problem | Do this |
| --- | --- |
| Sign-in fails | The matching `PIDEA_*_PASSWORD_HASH` is empty or wrong; regenerate it and restart the API |
| Pull fails with "allow-list" | `PIDEA_PG_SOURCE_ALLOWED_HOSTS=demo-source:5432` must be in `.env`; restart the worker |
| Pull fails with "credential is not configured" | `PIDEA_DEMO_SOURCE_PASSWORD` is missing in `.env`; restart the worker |
| Grafana blank | First start is slow; wait, or skip step 6 |
| Anything else | Run the matching `scripts/verify-*.sh` and show that instead |
