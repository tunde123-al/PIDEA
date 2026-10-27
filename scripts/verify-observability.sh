#!/usr/bin/env bash
# Verify observability end to end on a REAL but isolated stack (API + worker + PostgreSQL + Prometheus + Grafana):
#   * /actuator/prometheus is access-controlled and shows real workload after jobs have run
#   * the worker heartbeat is visible as pidea_workers_alive
#   * Prometheus scrapes the API, loads the alert rules, and every PromQL expression on the dashboard is valid
#   * Grafana has the provisioned datasource and dashboard
#   * one correlation id can be followed from the API response -> API log line -> worker log line
# Synthetic data and throwaway generated passwords only. Removes only its own uniquely named Compose project.
#
#   scripts/verify-observability.sh      # Git Bash / Linux / macOS; needs Docker and Node 20+
set -euo pipefail
shopt -s inherit_errexit 2>/dev/null || true

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
PROJECT="pidea-obs-$(od -An -N6 -tx1 /dev/urandom | tr -d '[:space:]')"
API_PORT="${PIDEA_VERIFY_API_PORT:-18082}"
PROM_PORT="${PIDEA_VERIFY_PROM_PORT:-19090}"
GRAFANA_PORT="${PIDEA_VERIFY_GRAFANA_PORT:-13000}"
DB_PORT="${PIDEA_VERIFY_DB_PORT:-15434}"
API="http://127.0.0.1:$API_PORT"; PROM="http://127.0.0.1:$PROM_PORT"; GRAFANA="http://127.0.0.1:$GRAFANA_PORT"
TMP="$(mktemp -d)"
ENV_FILE="$TMP/verify.env"
ENV_FILE_NATIVE="$(cygpath -m "$ENV_FILE" 2>/dev/null || echo "$ENV_FILE")"
compose() { docker compose -p "$PROJECT" --env-file "$ENV_FILE_NATIVE" --profile observability "$@"; }

cleanup() {
  status=$?
  if [ "$status" -ne 0 ]; then
    echo "--- verification FAILED (exit $status); recent logs ---" >&2
    compose logs --tail 40 api worker prometheus grafana >&2 2>/dev/null || true
  fi
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$TMP"
  exit "$status"
}
trap cleanup EXIT

rand() { head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24; }
bcrypt_hash() {
  MSYS_NO_PATHCONV=1 docker run --rm -e PW="$1" python:3.12-slim sh -c \
    "pip install -q --disable-pip-version-check bcrypt >/dev/null 2>&1 && python -c \"import bcrypt,os;print(bcrypt.hashpw(os.environ['PW'].encode(),bcrypt.gensalt(12)).decode())\""
}
ADMIN_PW="$(rand)"; OPERATOR_PW="$(rand)"; VIEWER_PW="$(rand)"; GRAFANA_PW="$(rand)"
{
  echo "POSTGRES_PASSWORD=$(rand)"
  echo "PIDEA_DB_PASSWORD=$(rand)"
  echo "PIDEA_WORKER_DB_PASSWORD=$(rand)"
  echo "PIDEA_API_PORT=$API_PORT"
  echo "PIDEA_DB_PORT=$DB_PORT"
  echo "PIDEA_PROMETHEUS_PORT=$PROM_PORT"
  echo "PIDEA_GRAFANA_PORT=$GRAFANA_PORT"
  echo "PIDEA_METRICS_PASSWORD=$VIEWER_PW"
  echo "GRAFANA_ADMIN_PASSWORD=$GRAFANA_PW"
  echo "PIDEA_ADMIN_PASSWORD_HASH='$(bcrypt_hash "$ADMIN_PW")'"
  echo "PIDEA_OPERATOR_PASSWORD_HASH='$(bcrypt_hash "$OPERATOR_PW")'"
  echo "PIDEA_VIEWER_PASSWORD_HASH='$(bcrypt_hash "$VIEWER_PW")'"
} > "$ENV_FILE"

json() { node -e 'const j=JSON.parse(require("fs").readFileSync(0,"utf8"));console.log(eval("j"+process.argv[1]))' "$1"; }
admin() { curl -sS -f -u "admin:$ADMIN_PW" -H 'Content-Type: application/json' "$@"; }
operator() { curl -sS -f -u "operator:$OPERATOR_PW" -H 'Content-Type: application/json' "$@"; }
check() { if [ "$2" = "$3" ]; then echo "  ok   $1 ($2)"; else echo "  FAIL $1: expected '$3' got '$2'" >&2; exit 1; fi; }
ok_if() { if eval "$2"; then echo "  ok   $1"; else echo "  FAIL $1" >&2; exit 1; fi; }
http_code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
prom_value() { # promql -> first sample value, or empty
  curl -sS -G "$PROM/api/v1/query" --data-urlencode "query=$1" | node -e '
    const j=JSON.parse(require("fs").readFileSync(0,"utf8")); const r=j.data&&j.data.result||[];
    console.log(r.length ? r[0].value[1] : "")'
}
wait_prom() { # promql, expected value -> waits up to 120 s
  for _ in $(seq 1 60); do [ "$(prom_value "$1")" = "$2" ] && return 0; sleep 2; done
  echo "Prometheus never reported $1 = $2 (last: '$(prom_value "$1")')" >&2; return 1
}

echo "Starting isolated stack '$PROJECT' with Prometheus and Grafana..."
compose up --build -d --wait --wait-timeout 300 >/dev/null

echo "1) metrics endpoint access control"
check "unauthenticated" "$(http_code "$API/actuator/prometheus")" 401
check "operator (job submitter)" "$(http_code -u "operator:$OPERATOR_PW" "$API/actuator/prometheus")" 403
check "viewer" "$(http_code -u "viewer:$VIEWER_PW" "$API/actuator/prometheus")" 200
check "health stays public" "$(http_code "$API/actuator/health")" 200

echo "2) run real jobs (2 valid records + 1 invalid) and trace one correlation id"
SOURCE_ID="$(admin -X POST "$API/api/v1/integration-sources" -d '{"name":"obs-demo","type":"REST_PUSH","config":{}}' | json .id)"
CORR="obs-trace-$(rand | head -c 8)"
JOB="$(operator -X POST -H "Idempotency-Key: verify-obs-1" -H "X-Correlation-Id: $CORR" "$API/api/v1/ingestion/jobs" -d "{\"sourceId\":\"$SOURCE_ID\",\"entityType\":\"CUSTOMER\",\"records\":[
  {\"customer_id\":\"O1\",\"full_name\":\"Obs One\",\"email\":\"o1@example.test\",\"created_at\":\"2025-01-01T00:00:00Z\"},
  {\"customer_id\":\"O2\",\"full_name\":\"Obs Two\",\"email\":\"o2@example.test\",\"created_at\":\"2025-01-02T00:00:00Z\"},
  {\"customer_id\":\"O3\",\"full_name\":\"Bad Email\",\"email\":\"nope\",\"created_at\":\"2025-01-03T00:00:00Z\"}]}")"
JOB_ID="$(echo "$JOB" | json .id)"
check "API echoes the correlation id on the job" "$(echo "$JOB" | json .correlationId)" "$CORR"
for _ in $(seq 1 40); do
  STATUS="$(operator "$API/api/v1/ingestion/jobs/$JOB_ID" | json .status)"
  case "$STATUS" in PENDING|RUNNING|RETRY_SCHEDULED) sleep 2 ;; *) break ;; esac
done
check "job status" "$STATUS" PARTIALLY_SUCCEEDED

echo "3) API metrics show the real workload"
for _ in $(seq 1 30); do   # the snapshot refreshes every 15 s
  BODY="$(curl -sS -u "viewer:$VIEWER_PW" "$API/actuator/prometheus")"
  grep -q '^pidea_records_rejected_total.* 1.0' <<< "$BODY" && break; sleep 2
done
metric() { grep "^$1" <<< "$BODY" | head -1 | awk '{print $NF}'; }
check "records accepted" "$(metric pidea_records_accepted_total)" 2.0
check "records rejected (quarantined)" "$(metric pidea_records_rejected_total)" 1.0
grep "pidea_quarantined" <<< "$BODY" | sed "s/^/    metric: /"
ok_if "quarantine reason is labelled" 'grep -q "pidea_quarantined_records{.*reason=\"INVALID_EMAIL\".*} 1.0" <<< "$BODY"'
check "a live worker heartbeat" "$(metric pidea_workers_alive)" 1.0
ok_if "queue is drained" "[ \"\$(metric pidea_queue_depth)\" = '0.0' ]"
ok_if "job duration is recorded" 'grep -q "^pidea_jobs_duration_seconds_total" <<< "$BODY"'

echo "4) Prometheus scrapes the API and loads the alert rules"
wait_prom 'up{job="pidea-api"}' 1
wait_prom 'pidea_workers_alive' 1
wait_prom 'pidea_records_rejected_total' 1
RULES="$(curl -sS "$PROM/api/v1/rules" | node -e 'const j=JSON.parse(require("fs").readFileSync(0,"utf8"));console.log(j.data.groups.flatMap(g=>g.rules.map(r=>r.name)).sort().join(","))')"
check "alert rules" "$RULES" "PideaApiDown,PideaMetricsSnapshotStale,PideaNoWorkerAlive,PideaQueueStuck,PideaStaleRunningJobs"
alert_count() { curl -sS "$PROM/api/v1/alerts" | json .data.alerts.length; }
for _ in $(seq 1 30); do [ "$(alert_count)" = "0" ] && break; sleep 2; done   # allow transient start-up states to clear
curl -sS "$PROM/api/v1/alerts" | node -e 'const j=JSON.parse(require("fs").readFileSync(0,"utf8"));for(const a of j.data.alerts)console.log("    alert:",a.labels.alertname,a.state,JSON.stringify(a.annotations.summary))'
check "no alert pending or firing on a healthy stack" "$(alert_count)" 0

echo "5) every PromQL expression on the dashboard is valid"
EXPRS="$(node -e 'const d=JSON.parse(require("fs").readFileSync("docker/observability/grafana/dashboards/pidea.json","utf8"));for(const p of d.panels)for(const t of p.targets)console.log(t.expr)')"
N=0
while IFS= read -r expr; do
  S="$(curl -sS -G "$PROM/api/v1/query" --data-urlencode "query=$expr" | json .status)"
  [ "$S" = "success" ] || { echo "  FAIL invalid PromQL: $expr" >&2; exit 1; }
  N=$((N+1))
done <<< "$EXPRS"
echo "  ok   $N expressions evaluate without error"

echo "6) Grafana is provisioned"
for _ in $(seq 1 300); do curl -sf "$GRAFANA/api/health" >/dev/null 2>&1 && break; sleep 2; done   # first start runs many DB migrations and can take several minutes; no compose healthcheck
check "Grafana health" "$(curl -sS "$GRAFANA/api/health" | json .database)" ok
check "anonymous access is off" "$(http_code "$GRAFANA/api/dashboards/uid/pidea-overview")" 401
for _ in $(seq 1 60); do [ "$(http_code -u "admin:$GRAFANA_PW" "$GRAFANA/api/dashboards/uid/pidea-overview")" = 200 ] && break; sleep 2; done   # provisioning finishes after the HTTP port opens
check "dashboard" "$(curl -sS -u "admin:$GRAFANA_PW" "$GRAFANA/api/dashboards/uid/pidea-overview" | json .dashboard.title)" "PIDEA overview"
check "datasource reaches Prometheus" "$(curl -sS -u "admin:$GRAFANA_PW" "$GRAFANA/api/datasources/uid/pidea-prometheus/health" | json .status)" OK
check "query through Grafana" "$(curl -sS -G -u "admin:$GRAFANA_PW" "$GRAFANA/api/datasources/proxy/uid/pidea-prometheus/api/v1/query" --data-urlencode 'query=pidea_workers_alive' | json .status)" success

echo "7) one correlation id across API log -> worker log"
API_LOG="$(compose logs --no-log-prefix api 2>/dev/null | grep -F "$CORR" | head -1 || true)"
ok_if "an API log line carries the correlation id" "[ -n \"\$API_LOG\" ]"
ok_if "API log lines are JSON" "echo \"\$API_LOG\" | node -e 'JSON.parse(require(\"fs\").readFileSync(0,\"utf8\"))'"
WORKER_LINE="$(compose logs --no-log-prefix worker 2>/dev/null | grep -F '"msg": "job finished"' | grep -F "$JOB_ID" | head -1 || true)"
ok_if "the worker logged the finished job" "[ -n \"\$WORKER_LINE\" ]"
check "worker log correlation id" "$(echo "$WORKER_LINE" | json .correlation_id)" "$CORR"
check "worker log status" "$(echo "$WORKER_LINE" | json .status)" PARTIALLY_SUCCEEDED
ok_if "worker log has a duration" "[ \"\$(echo \"\$WORKER_LINE\" | json .duration_ms)\" -ge 0 ]"

echo "8) operations: restart policies and graceful worker shutdown"
for svc in db api worker; do
  check "$svc restart policy" "$(docker inspect -f '{{.HostConfig.RestartPolicy.Name}}' "$(compose ps -q "$svc")")" unless-stopped
done
STOP_BEGIN=$(date +%s)
compose stop worker > /dev/null 2>&1
STOP_SECONDS=$(( $(date +%s) - STOP_BEGIN ))
ok_if "worker stopped inside its grace period (${STOP_SECONDS}s, docker would have waited 60s before SIGKILL)" "[ $STOP_SECONDS -lt 20 ]"
check "worker exit code (0 = clean shutdown, 137 = killed)" "$(docker inspect -f '{{.State.ExitCode}}' "$(compose ps -aq worker)")" 0
WORKER_LOG="$(compose logs --no-log-prefix worker 2>/dev/null)"
ok_if "worker logged a clean shutdown" 'grep -q "\"msg\": \"worker stopped\"" <<< "$WORKER_LOG"'

echo "Observability verified."
