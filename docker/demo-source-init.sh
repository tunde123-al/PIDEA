#!/bin/sh
# Seeds the LOCAL DEMO source database (compose profile "demo"). Synthetic data only.
# Creates a SELECT-only role, the same shape a production source user should have.
set -e
psql -v ON_ERROR_STOP=1 -v READER_PASSWORD="$POSTGRES_PASSWORD" \
  --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
CREATE ROLE pidea_reader LOGIN PASSWORD :'READER_PASSWORD';
CREATE SCHEMA crm;
CREATE TABLE crm.customers (
    id          bigserial   PRIMARY KEY,           -- UNIQUE NOT NULL, ever-increasing: a valid keyColumn
    customer_id text        NOT NULL,
    full_name   text        NOT NULL,
    email       text,
    created_at  timestamptz NOT NULL DEFAULT now()
);
INSERT INTO crm.customers (customer_id, full_name, email, created_at) VALUES
    ('D1001', 'Ada Example',     'ada@example.test',     '2025-01-05T09:00:00Z'),
    ('D1002', 'Grace Sample',    'grace@example.test',   '2025-01-06T09:30:00Z'),
    ('D1003', 'Linus Demo',      'linus@example.test',   '2025-01-07T10:00:00Z'),
    ('D1004', 'Margaret Test',   'margaret@example.test','2025-01-08T11:15:00Z'),
    ('D1005', 'Broken Email',    'not-an-email',         '2025-01-09T12:00:00Z'),  -- rejected -> quarantine
    ('D1006', 'Alan Fixture',    'alan@example.test',    '2025-01-10T08:45:00Z'),
    ('D1007', 'Barbara Mock',    'barbara@example.test', '2025-01-11T14:20:00Z'),
    ('D1008', 'Edsger Stub',     'edsger@example.test',  '2025-01-12T16:05:00Z');
GRANT USAGE ON SCHEMA crm TO pidea_reader;
GRANT SELECT ON crm.customers TO pidea_reader;   -- nothing else
SQL
