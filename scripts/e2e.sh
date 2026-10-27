#!/usr/bin/env bash
# Run the Playwright e2e suite against a REAL but isolated PIDEA stack (db + api + worker).
#
#   scripts/e2e.sh                 # from the repo root (Git Bash / Linux / macOS / CI)
#
# Isolation: its own uniquely named Compose project (pidea-e2e-<random>: separate containers, network, volumes), its own host
# ports (API 18080, DB 15432), throwaway random passwords, and an env file in a temp directory. It never
# reads or writes the developer's .env, and on exit it removes only that project (including its
# volumes), never the normal "pidea" project's data.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
PROJECT="pidea-e2e-$(od -An -N6 -tx1 /dev/urandom | tr -d '[:space:]')"  # unique per run: cleanup can only remove what this run created
API_PORT="${PIDEA_E2E_API_PORT:-18080}"
DB_PORT="${PIDEA_E2E_DB_PORT:-15432}"
TMP="$(mktemp -d)"
ENV_FILE="$TMP/e2e.env"

ENV_FILE_NATIVE="$(cygpath -m "$ENV_FILE" 2>/dev/null || echo "$ENV_FILE")"  # Git Bash -> Windows path for docker.exe
compose() { docker compose -p "$PROJECT" --env-file "$ENV_FILE_NATIVE" "$@"; }

cleanup() {
  status=$?
  if [ "$status" -ne 0 ]; then
    echo "--- e2e failed (exit $status); recent container logs ---" >&2
    compose logs --tail 80 api worker db >&2 2>/dev/null || true
  fi
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$TMP"
  exit "$status"
}
trap cleanup EXIT

rand() { head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24; }
bcrypt_hash() { # $1 = plaintext. BCrypt cost 12, computed in a throwaway container so no local Python is needed.
  MSYS_NO_PATHCONV=1 docker run --rm -e PW="$1" python:3.12-slim sh -c \
    "pip install -q --disable-pip-version-check bcrypt >/dev/null 2>&1 && python -c \"import bcrypt,os;print(bcrypt.hashpw(os.environ['PW'].encode(),bcrypt.gensalt(12)).decode())\""
}

export PIDEA_E2E_ADMIN_PASSWORD="$(rand)"
export PIDEA_E2E_OPERATOR_PASSWORD="$(rand)"
export PIDEA_E2E_VIEWER_PASSWORD="$(rand)"

# Single quotes keep the '$' in BCrypt hashes literal when Compose reads the env file.
{
  echo "POSTGRES_PASSWORD=$(rand)"
  echo "PIDEA_DB_PASSWORD=$(rand)"
  echo "PIDEA_WORKER_DB_PASSWORD=$(rand)"
  echo "PIDEA_API_PORT=$API_PORT"
  echo "PIDEA_DB_PORT=$DB_PORT"
  echo "PIDEA_ADMIN_PASSWORD_HASH='$(bcrypt_hash "$PIDEA_E2E_ADMIN_PASSWORD")'"
  echo "PIDEA_OPERATOR_PASSWORD_HASH='$(bcrypt_hash "$PIDEA_E2E_OPERATOR_PASSWORD")'"
  echo "PIDEA_VIEWER_PASSWORD_HASH='$(bcrypt_hash "$PIDEA_E2E_VIEWER_PASSWORD")'"
} > "$ENV_FILE"

echo "Starting isolated stack '$PROJECT' (API on 127.0.0.1:$API_PORT)..."
compose up --build -d --wait --wait-timeout 300

export PIDEA_API_TARGET="http://127.0.0.1:$API_PORT"
cd frontend
npm run test:e2e
