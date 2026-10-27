#!/usr/bin/env bash
# Verify the POSTGRES_TABLE connector end-to-end on a REAL but isolated stack: API + worker + PIDEA database +
# the seeded demo source (compose profile "demo"). Synthetic data and throwaway generated passwords only.
#
#   scripts/verify-pg-connector.sh        # Git Bash / Linux / macOS; needs Docker and Node 20+
#
# Flow: create source -> pull batch 1 (5 rows: 4 loaded, 1 quarantined) -> pull batch 2 (3 rows) -> nothing new ->
# new source row appears -> pull picks up only it -> break the config -> job FAILS with a safe message -> fix -> replay.
# Uses its own uniquely named Compose project and temp env file; removes only that project on exit.
set -euo pipefail
shopt -s inherit_errexit 2>/dev/null || true   # make failures inside $(...) abort the script

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
PROJECT="pidea-pgverify-$(od -An -N6 -tx1 /dev/urandom | tr -d '[:space:]')"
API_PORT="${PIDEA_VERIFY_API_PORT:-18081}"
DB_PORT="${PIDEA_VERIFY_DB_PORT:-15433}"
API="http://127.0.0.1:$API_PORT"
TMP="$(mktemp -d)"
ENV_FILE="$TMP/verify.env"
ENV_FILE_NATIVE="$(cygpath -m "$ENV_FILE" 2>/dev/null || echo "$ENV_FILE")"
compose() { docker compose -p "$PROJECT" --env-file "$ENV_FILE_NATIVE" --profile demo "$@"; }

cleanup() {
  status=$?
  if [ "$status" -ne 0 ]; then
    echo "--- verification FAILED (exit $status); recent logs ---" >&2
    compose logs --tail 60 api worker demo-source >&2 2>/dev/null || true
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
ADMIN_PW="$(rand)"; OPERATOR_PW="$(rand)"
{
  echo "POSTGRES_PASSWORD=$(rand)"
  echo "PIDEA_DB_PASSWORD=$(rand)"
  echo "PIDEA_WORKER_DB_PASSWORD=$(rand)"
  echo "PIDEA_API_PORT=$API_PORT"
  echo "PIDEA_DB_PORT=$DB_PORT"
  echo "PIDEA_DEMO_SOURCE_PASSWORD=$(rand)"
  echo "PIDEA_PG_SOURCE_ALLOWED_HOSTS=demo-source:5432"
  echo "PIDEA_ADMIN_PASSWORD_HASH='$(bcrypt_hash "$ADMIN_PW")'"
  echo "PIDEA_OPERATOR_PASSWORD_HASH='$(bcrypt_hash "$OPERATOR_PW")'"
} > "$ENV_FILE"

json() { node -e 'const j=JSON.parse(require("fs").readFileSync(0,"utf8"));console.log(eval("j"+process.argv[1]))' "$1"; }
admin() { curl -sS -f -u "admin:$ADMIN_PW" -H 'Content-Type: application/json' "$@"; }
operator() { curl -sS -f -u "operator:$OPERATOR_PW" -H 'Content-Type: application/json' "$@"; }
check() { # description actual expected
  if [ "$2" = "$3" ]; then echo "  ok   $1 ($2)"; else echo "  FAIL $1: expected '$3' got '$2'" >&2; exit 1; fi
}
wait_job() { # job id -> prints final job JSON
  for _ in $(seq 1 60); do
    out="$(operator "$API/api/v1/ingestion/jobs/$1")"
    case "$(echo "$out" | json .status)" in PENDING|RUNNING|RETRY_SCHEDULED) sleep 2 ;; *) echo "$out"; return 0 ;; esac
  done
  echo "job $1 did not finish in time" >&2; return 1
}
pull() { # idempotency-key -> job id
  operator -X POST -H "Idempotency-Key: $1" "$API/api/v1/ingestion/sources/$SOURCE_ID/jobs?entityType=CUSTOMER" | json .id
}

echo "Starting isolated stack '$PROJECT' with the demo source..."
compose up --build -d --wait --wait-timeout 300 >/dev/null

CONFIG='{"host":"demo-source","port":5432,"database":"crm","user":"pidea_reader","sslmode":"disable","schema":"crm","table":"customers","keyColumn":"id","batchSize":2,"maxRows":5,"timeoutSeconds":10}'
SOURCE_ID="$(admin -X POST "$API/api/v1/integration-sources" \
  -d "{\"name\":\"demo-crm\",\"type\":\"POSTGRES_TABLE\",\"config\":$CONFIG,\"secretRef\":\"PIDEA_SRC_DEMO_PASSWORD\"}" | json .id)"
echo "Source created: $SOURCE_ID (password is referenced by variable name only)"

echo "1) first pull (maxRows=5 of 8 rows; one row has an invalid e-mail)"
J1="$(wait_job "$(pull verify-pull-1)")"
check "job type" "$(echo "$J1" | json .jobType)" POSTGRES_PULL
check "status" "$(echo "$J1" | json .status)" PARTIALLY_SUCCEEDED
check "received/accepted/rejected" "$(echo "$J1" | json '.recordsReceived+","+j.recordsAccepted+","+j.recordsRejected')" "5,4,1"
REJ="$(operator "$API/api/v1/ingestion/jobs/$(echo "$J1" | json .id)/errors" | json '.items[0].reasonCode')"
check "quarantined reason" "$REJ" INVALID_EMAIL

echo "2) second pull resumes after the checkpoint"
J2="$(wait_job "$(pull verify-pull-2)")"
check "status" "$(echo "$J2" | json .status)" SUCCEEDED
check "received/accepted" "$(echo "$J2" | json '.recordsReceived+","+j.recordsAccepted')" "3,3"

echo "3) nothing new: zero records, still succeeds"
J3="$(wait_job "$(pull verify-pull-3)")"
check "received" "$(echo "$J3" | json .recordsReceived)" 0

echo "4) a new row appears in the source; only it is pulled"
OWNER_PW="$(grep '^PIDEA_DEMO_SOURCE_PASSWORD=' "$ENV_FILE" | cut -d= -f2)"
compose exec -T -e PGPASSWORD="$OWNER_PW" demo-source psql -q -U demo_owner -d crm -c \
  "INSERT INTO crm.customers (customer_id, full_name, email, created_at) VALUES ('D1009','Late Arrival','late@example.test', now())"
J4="$(wait_job "$(pull verify-pull-4)")"
check "received/accepted" "$(echo "$J4" | json '.recordsReceived+","+j.recordsAccepted')" "1,1"

echo "5) failure handling: a host outside the worker allow-list fails safely"
admin -X PATCH "$API/api/v1/integration-sources/$SOURCE_ID" -d "{\"config\":$(echo "$CONFIG" | sed 's/demo-source/blocked-host.example/')}" >/dev/null
J5="$(wait_job "$(pull verify-pull-5)")"
check "status" "$(echo "$J5" | json .status)" FAILED
SUMMARY="$(echo "$J5" | json .errorSummary)"
case "$SUMMARY" in *allow-list*) echo "  ok   curated error: $SUMMARY" ;; *) echo "  FAIL unexpected error: $SUMMARY" >&2; exit 1 ;; esac
case "$SUMMARY" in *"$OWNER_PW"*) echo "  FAIL password leaked into the error" >&2; exit 1 ;; esac

echo "6) fix the config and replay the failed job"
admin -X PATCH "$API/api/v1/integration-sources/$SOURCE_ID" -d "{\"config\":$CONFIG}" >/dev/null
J6="$(wait_job "$(operator -X POST "$API/api/v1/ingestion/jobs/$(echo "$J5" | json .id)/replay" | json .id)")"
check "replay status" "$(echo "$J6" | json .status)" SUCCEEDED

TOTAL="$(compose exec -T db sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" psql -At -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT count(*) FROM analytics.customer"' | tr -d '\r')"
check "customers loaded into analytics.customer (7 + 1 new; 1 quarantined)" "$TOTAL" 8
echo "PostgreSQL connector verified."
