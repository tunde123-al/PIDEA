"""Database fixtures. The schema is built by applying the REAL Flyway migrations from the Java project,
so Python tests run against exactly the schema the Java side owns."""

from __future__ import annotations

import uuid
from pathlib import Path

import psycopg
import pytest
from testcontainers.community.postgres import PostgresContainer

MIGRATIONS = Path(__file__).resolve().parents[2] / "backend" / "src" / "main" / "resources" / "db" / "migration"


@pytest.fixture(scope="session")
def pg_url():
    with PostgresContainer("postgres:16-alpine", driver=None).with_command(
            "postgres -c fsync=off -c synchronous_commit=off") as pg:
        url = pg.get_connection_url()
        with psycopg.connect(url, autocommit=True) as conn:
            for sql in sorted(MIGRATIONS.glob("V*__*.sql"), key=lambda p: int(p.name[1:].split("__")[0])):
                conn.execute(sql.read_text(encoding="utf-8"))
        yield url


@pytest.fixture()
def conn(pg_url):
    with psycopg.connect(pg_url, autocommit=True) as c:
        c.execute("""TRUNCATE integration.rejected_record, integration.raw_record, integration.job_attempt,
                     integration.idempotency_record, integration.stg_payment_event, integration.mapping_definition,
                     integration.source_watermark, analytics.customer, analytics.customer_order,
                     analytics.payment_event, integration.ingestion_job, integration.audit_event,
                     integration.integration_source CASCADE""")
        yield c


@pytest.fixture()
def source_id(conn):
    sid = uuid.uuid4()
    conn.execute("""INSERT INTO integration.integration_source (id,name,source_type,status,config,created_at,updated_at)
                    VALUES (%s,%s,'REST_PUSH','ACTIVE','{}',now(),now())""", (sid, f"src-{sid}"))
    return sid


@pytest.fixture()
def make_job(conn, source_id):
    def _make(entity="CUSTOMER", records=(), job_type="RECORDS", file_ref=None, max_retries=3):
        import json
        jid = uuid.uuid4()
        conn.execute(
            """INSERT INTO integration.ingestion_job (id, source_id, job_type, entity_type, status, correlation_id,
                 records_received, next_attempt_at, submitted_at, submitted_by, file_ref, max_retries)
               VALUES (%s,%s,%s,%s,'PENDING','corr-test',%s,now() - interval '1 minute',now(),'test',%s,%s)""",
            (jid, source_id, job_type, entity, len(records), file_ref, max_retries))
        for i, rec in enumerate(records):
            conn.execute("INSERT INTO integration.raw_record (job_id, seq, payload) VALUES (%s,%s,%s::jsonb)",
                         (jid, i, json.dumps(rec)))
        return jid
    return _make
