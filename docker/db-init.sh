#!/bin/sh
# Runs once when the database volume is first created. Creates least-privilege runtime roles.
# The owner (POSTGRES_USER) runs Flyway migrations; the app and worker can only read/write rows.
set -e
psql -v ON_ERROR_STOP=1 \
  -v PIDEA_DB_PASSWORD="$PIDEA_DB_PASSWORD" \
  -v PIDEA_WORKER_DB_PASSWORD="$PIDEA_WORKER_DB_PASSWORD" \
  -v POSTGRES_USER="$POSTGRES_USER" \
  --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<SQL
CREATE ROLE pidea_app    LOGIN PASSWORD :'PIDEA_DB_PASSWORD';
CREATE ROLE pidea_worker LOGIN PASSWORD :'PIDEA_WORKER_DB_PASSWORD';
CREATE SCHEMA IF NOT EXISTS integration;
CREATE SCHEMA IF NOT EXISTS analytics;
GRANT USAGE ON SCHEMA integration TO pidea_app, pidea_worker;
GRANT USAGE ON SCHEMA analytics TO pidea_worker;
ALTER DEFAULT PRIVILEGES FOR ROLE :"POSTGRES_USER" IN SCHEMA integration
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO pidea_app, pidea_worker;
ALTER DEFAULT PRIVILEGES FOR ROLE :"POSTGRES_USER" IN SCHEMA analytics
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO pidea_worker;
SQL
