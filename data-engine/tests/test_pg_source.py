"""POSTGRES_TABLE connector tests.

A second, throwaway PostgreSQL container plays the *source* database (read-only role, synthetic data). The adapter
tests need no PIDEA schema; the worker tests (bottom) use the real Flyway schema through the ``conn`` fixture.
"""

from __future__ import annotations

import json
import socket
import uuid
from decimal import Decimal
from types import SimpleNamespace

import psycopg
import pytest
from testcontainers.community.postgres import PostgresContainer

from pidea_data import pg_source
from pidea_data.rest_adapter import PermanentSourceError, TransientSourceError
from pidea_data.worker import process_one

PASSWORD_VARIABLE = "PIDEA_SRC_CRM_PASSWORD"
READER_PASSWORD = "reader-test-password"


@pytest.fixture(scope="session")
def source_db():
    with PostgresContainer("postgres:16-alpine", driver=None) as pg:
        admin = SimpleNamespace(host=pg.get_container_host_ip(), port=int(pg.get_exposed_port(5432)),
                                dbname=pg.dbname, user=pg.username, password=pg.password)
        with psycopg.connect(pg.get_connection_url(), autocommit=True) as c:
            c.execute("CREATE SCHEMA crm")
            c.execute("""CREATE TABLE crm.customers (
                           id bigint PRIMARY KEY, customer_id text NOT NULL, full_name text, email text,
                           created_at timestamptz)""")
            c.execute("CREATE TABLE crm.no_key (customer_id text, full_name text)")
            c.execute("CREATE TABLE crm.dup_key (id int NOT NULL, customer_id text)")
            c.execute("CREATE TABLE crm.nullable_unique (id int UNIQUE, customer_id text)")
            c.execute("CREATE TABLE crm.text_key (code text PRIMARY KEY, customer_id text)")
            c.execute("CREATE TABLE crm.blobs (id int PRIMARY KEY, payload bytea)")
            c.execute("INSERT INTO crm.blobs VALUES (1, '\\x00ff')")
            c.execute("""CREATE TABLE crm.typed (id numeric PRIMARY KEY, ref uuid, amount numeric(12,2),
                           happened timestamptz, born date, attrs jsonb)""")
            c.execute("""INSERT INTO crm.typed VALUES (1, '5b3f7c2e-3c0a-4d1e-9a47-1c0f2b7d9e11', 12.50,
                           '2024-03-01T10:00:00Z', '1990-02-03', '{"a": 1}')""")
            c.execute(f"CREATE ROLE reader LOGIN PASSWORD '{READER_PASSWORD}'")
            c.execute("GRANT USAGE ON SCHEMA crm TO reader")
            c.execute("GRANT SELECT ON ALL TABLES IN SCHEMA crm TO reader")
        yield admin


@pytest.fixture()
def customers(source_db):
    """Five synthetic customers with ids 1..5, recreated for each test."""
    with psycopg.connect(host=source_db.host, port=source_db.port, dbname=source_db.dbname,
                         user=source_db.user, password=source_db.password, autocommit=True) as c:
        c.execute("TRUNCATE crm.customers")
        for i in range(1, 6):
            c.execute("INSERT INTO crm.customers VALUES (%s,%s,%s,%s,%s)",
                      (i, f"C{i}", f"Person {i}", f"p{i}@example.test", "2024-03-01T10:00:00Z"))
    return source_db


@pytest.fixture()
def env(source_db):
    return {pg_source.ALLOWED_HOSTS_VARIABLE: f"{source_db.host}:{source_db.port}",
            PASSWORD_VARIABLE: READER_PASSWORD}


@pytest.fixture()
def config(source_db):
    def _config(**overrides):
        base = {"host": source_db.host, "port": source_db.port, "database": source_db.dbname, "user": "reader",
                "sslmode": "disable", "schema": "crm", "table": "customers", "keyColumn": "id",
                "batchSize": 2, "maxRows": 100, "timeoutSeconds": 10}
        return {**base, **overrides}
    return _config


def pull(config, env, cursor=None, secret_ref=PASSWORD_VARIABLE):
    return pg_source.read_records(config, secret_ref, cursor, environment=env)


# ---------------------------------------------------------------- extraction

def test_reads_rows_in_key_order_with_cursor(customers, config, env):
    result = pull(config(), env)
    assert [r["id"] for _, r in result.records] == [1, 2, 3, 4, 5]
    assert result.records[0][1]["customer_id"] == "C1"
    assert (result.cursor_key, result.cursor_value) == ("crm.customers.id", "5")


def test_max_rows_bounds_the_batch_and_cursor_resumes(customers, config, env):
    first = pull(config(maxRows=2), env)
    assert [r["id"] for _, r in first.records] == [1, 2] and first.cursor_value == "2"
    second = pull(config(maxRows=2), env, first.cursor_value)
    assert [r["id"] for _, r in second.records] == [3, 4] and second.cursor_value == "4"
    third = pull(config(maxRows=2), env, second.cursor_value)
    assert [r["id"] for _, r in third.records] == [5]
    done = pull(config(maxRows=2), env, third.cursor_value)
    assert done.records == [] and done.cursor_value is None


def test_columns_projection_always_includes_the_key(customers, config, env):
    result = pull(config(columns="customer_id, email"), env)
    assert set(result.records[0][1]) == {"customer_id", "email", "id"}


def test_values_are_json_friendly(source_db, config, env):
    result = pull(config(table="typed"), env)
    row = result.records[0][1]
    assert row["ref"] == "5b3f7c2e-3c0a-4d1e-9a47-1c0f2b7d9e11"
    assert row["amount"] == Decimal("12.50")
    assert row["happened"].startswith("2024-03-01T10:00:00") and row["born"] == "1990-02-03"
    assert row["attrs"] == {"a": 1}
    json.dumps(row, default=str)  # serialisable for audit/rejection diagnostics
    assert result.cursor_value == "1"


# ------------------------------------------------------------------ security

def test_host_must_be_allow_listed(customers, config, env):
    with pytest.raises(PermanentSourceError, match="allow-list"):
        pull(config(), {**env, pg_source.ALLOWED_HOSTS_VARIABLE: "other.example:5432"})
    with pytest.raises(PermanentSourceError, match="allow-list"):
        pull(config(), {PASSWORD_VARIABLE: READER_PASSWORD})  # nothing configured => deny by default


def test_allow_list_requires_the_exact_port_and_ignores_malformed_entries(source_db):
    env = {pg_source.ALLOWED_HOSTS_VARIABLE: f"*.example.com, bad host, {source_db.host}:{source_db.port + 1}"}
    assert (source_db.host, source_db.port) not in pg_source.allowed_endpoints(env)
    assert pg_source.allowed_endpoints({pg_source.ALLOWED_HOSTS_VARIABLE: "db.internal"}) == {("db.internal", 5432)}


@pytest.mark.parametrize("secret_ref", [None, "", "DATABASE_PASSWORD", "PIDEA_DB_DSN", "pidea_src_lower"])
def test_secret_ref_must_use_the_source_prefix(customers, config, env, secret_ref):
    env = {**env, "PIDEA_DB_DSN": "postgresql://worker:never-read@db/x", "DATABASE_PASSWORD": "x"}
    with pytest.raises(PermanentSourceError, match=pg_source.SECRET_PREFIX):
        pull(config(), env, secret_ref=secret_ref)


def test_missing_credential_is_a_permanent_curated_error(customers, config, env):
    with pytest.raises(PermanentSourceError, match="not configured"):
        pull(config(), {pg_source.ALLOWED_HOSTS_VARIABLE: env[pg_source.ALLOWED_HOSTS_VARIABLE]})


@pytest.mark.parametrize("override", [
    {"table": "customers; DROP TABLE crm.customers"},
    {"table": 'customers"'},
    {"schema": "crm.customers"},
    {"keyColumn": "id; --"},
    {"columns": "customer_id, email) FROM crm.customers; --"},
    {"user": "reader; DROP ROLE x"},
    {"host": "db host"},
    {"sslmode": "allow-anything"},
    {"batchSize": 0}, {"maxRows": 100001}, {"port": 70000}, {"timeoutSeconds": "30"},
    {"unknownSetting": "x"},
])
def test_unsafe_or_invalid_config_is_rejected_before_connecting(customers, config, env, override):
    with pytest.raises(PermanentSourceError):
        pull(config(**override), env)
    # the source table is untouched
    with psycopg.connect(host=customers.host, port=customers.port, dbname=customers.dbname, user=customers.user,
                         password=customers.password) as c:
        assert c.execute("SELECT count(*) FROM crm.customers").fetchone()[0] == 5


@pytest.mark.parametrize("table", ["dup_key", "nullable_unique"])
def test_key_column_must_be_unique_and_not_null(source_db, config, env, table):
    with pytest.raises(PermanentSourceError, match="UNIQUE NOT NULL"):
        pull(config(table=table, keyColumn="id"), env)


def test_key_column_type_and_existence_are_checked(source_db, config, env):
    with pytest.raises(PermanentSourceError, match="integer, numeric, date or timestamp"):
        pull(config(table="text_key", keyColumn="code"), env)
    with pytest.raises(PermanentSourceError, match="not found"):
        pull(config(table="nope"), env)
    with pytest.raises(PermanentSourceError, match="not found"):
        pull(config(keyColumn="missing"), env)


def test_binary_columns_are_refused(source_db, config, env):
    with pytest.raises(PermanentSourceError, match="binary"):
        pull(config(table="blobs"), env)


def test_session_is_read_only_even_for_a_user_with_write_access(customers, config):
    admin_config = pg_source.parse_settings(config(user=customers.user, database=customers.dbname))
    conn = pg_source.connect(admin_config, customers.password)  # superuser: privileges alone would allow writes
    try:
        with pytest.raises(psycopg.errors.ReadOnlySqlTransaction):
            conn.execute("INSERT INTO crm.customers VALUES (99, 'X', 'x', 'x', now())")
    finally:
        conn.close()


def test_wrong_password_is_permanent_and_never_echoed(customers, config, env):
    secret = "definitely-wrong-password"
    with pytest.raises(PermanentSourceError) as excinfo:
        pull(config(), {**env, PASSWORD_VARIABLE: secret})
    assert secret not in str(excinfo.value)


def test_tls_is_required_by_default(customers, config, env):
    unsafe = {k: v for k, v in config().items() if k != "sslmode"}  # default sslmode is "require"
    with pytest.raises(PermanentSourceError, match="rejected the connection"):
        pull(unsafe, env)  # the test source has no TLS, so the plaintext fallback must not happen


def test_unreachable_source_is_transient(config):
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        closed_port = s.getsockname()[1]
    env = {pg_source.ALLOWED_HOSTS_VARIABLE: f"127.0.0.1:{closed_port}", PASSWORD_VARIABLE: "x"}
    with pytest.raises(TransientSourceError):
        pull(config(host="127.0.0.1", port=closed_port, timeoutSeconds=2), env)


# -------------------------------------------------- worker integration (PIDEA DB)

@pytest.fixture()
def pg_source_job(conn, source_db, config, env, monkeypatch):
    for key, value in env.items():
        monkeypatch.setenv(key, value)

    def _make(entity="CUSTOMER", secret_ref=PASSWORD_VARIABLE, **overrides):
        sid = uuid.uuid4()
        conn.execute(
            """INSERT INTO integration.integration_source (id,name,source_type,status,config,secret_ref,created_at,updated_at)
               VALUES (%s,%s,'POSTGRES_TABLE','ACTIVE',%s::jsonb,%s,now(),now())""",
            (sid, f"pg-{sid}", json.dumps(config(**overrides)), secret_ref))
        return sid, new_job(conn, sid, entity)
    return _make


def new_job(conn, sid, entity="CUSTOMER"):
    jid = uuid.uuid4()
    conn.execute(
        """INSERT INTO integration.ingestion_job (id, source_id, job_type, entity_type, status, correlation_id,
             records_received, next_attempt_at, submitted_at, submitted_by, max_retries)
           VALUES (%s,%s,'POSTGRES_PULL',%s,'PENDING','corr-test',0,now() - interval '1 minute',now(),'test',3)""",
        (jid, sid, entity))
    return jid


def checkpoint(conn, sid, entity="CUSTOMER"):
    return conn.execute("SELECT cursor_key, cursor_value FROM integration.source_checkpoint "
                        "WHERE source_id=%s AND entity_type=%s", (sid, entity)).fetchone()


def job_state(conn, jid):
    return conn.execute("SELECT status, records_received, records_accepted, records_rejected, error_summary "
                        "FROM integration.ingestion_job WHERE id=%s", (jid,)).fetchone()


def test_pull_loads_batches_and_advances_checkpoint(conn, customers, pg_source_job):
    sid, first = pg_source_job(maxRows=3)
    assert process_one(conn) == "SUCCEEDED"
    assert job_state(conn, first)[:4] == ("SUCCEEDED", 3, 3, 0)
    assert checkpoint(conn, sid) == ("crm.customers.id", "3")

    second = new_job(conn, sid)
    assert process_one(conn) == "SUCCEEDED"
    assert job_state(conn, second)[:4] == ("SUCCEEDED", 2, 2, 0)
    assert checkpoint(conn, sid) == ("crm.customers.id", "5")

    third = new_job(conn, sid)  # nothing new: succeeds with zero records and keeps the checkpoint
    assert process_one(conn) == "SUCCEEDED"
    assert job_state(conn, third)[:4] == ("SUCCEEDED", 0, 0, 0)
    assert checkpoint(conn, sid) == ("crm.customers.id", "5")
    assert conn.execute("SELECT count(*) FROM analytics.customer").fetchone()[0] == 5


def test_rerun_after_reset_is_idempotent(conn, customers, pg_source_job):
    sid, _ = pg_source_job()
    assert process_one(conn) == "SUCCEEDED"
    conn.execute("UPDATE integration.source_checkpoint SET cursor_value=NULL WHERE source_id=%s", (sid,))
    again = new_job(conn, sid)
    assert process_one(conn) == "SUCCEEDED"
    assert job_state(conn, again)[1] == 5
    assert conn.execute("SELECT count(*) FROM analytics.customer").fetchone()[0] == 5  # upserts, no duplicates


def test_failed_job_does_not_move_the_checkpoint(conn, customers, pg_source_job):
    sid, jid = pg_source_job(entity="ORDER")  # customer rows are not valid orders: every record is rejected
    assert process_one(conn) == "FAILED"
    assert job_state(conn, jid)[3] == 5
    assert checkpoint(conn, sid, "ORDER") == (None, None)


def test_reconfigured_key_restarts_instead_of_reusing_a_stale_cursor(conn, customers, pg_source_job):
    sid, _ = pg_source_job()
    assert process_one(conn) == "SUCCEEDED"
    conn.execute("""UPDATE integration.source_checkpoint SET cursor_key='crm.other_table.id', cursor_value='999'
                    WHERE source_id=%s""", (sid,))
    jid = new_job(conn, sid)
    assert process_one(conn) == "SUCCEEDED"
    assert job_state(conn, jid)[1] == 5  # re-read from the start, not "after 999"


def test_unreachable_source_schedules_a_retry(conn, config, pg_source_job, monkeypatch):
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        closed_port = s.getsockname()[1]
    monkeypatch.setenv(pg_source.ALLOWED_HOSTS_VARIABLE, f"127.0.0.1:{closed_port}")
    sid, jid = pg_source_job(host="127.0.0.1", port=closed_port, timeoutSeconds=2)
    assert process_one(conn) == "RETRY_SCHEDULED"
    assert "unreachable" in job_state(conn, jid)[4]
    assert checkpoint(conn, sid) is None  # the attempt's transaction rolled back, including the checkpoint insert


def test_misconfigured_source_fails_with_a_curated_message(conn, customers, pg_source_job, monkeypatch):
    monkeypatch.setenv(pg_source.ALLOWED_HOSTS_VARIABLE, "somewhere.else:5432")
    _, jid = pg_source_job()
    assert process_one(conn) == "FAILED"
    summary = job_state(conn, jid)[4]
    assert "allow-list" in summary and READER_PASSWORD not in summary


def test_disabled_source_is_not_pulled(conn, customers, pg_source_job):
    sid, jid = pg_source_job()
    conn.execute("UPDATE integration.integration_source SET status='DISABLED' WHERE id=%s", (sid,))
    assert process_one(conn) == "FAILED"
    assert "unavailable or disabled" in job_state(conn, jid)[4]
