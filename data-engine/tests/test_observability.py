"""Structured logs, the worker heartbeat and the per-job log fields that tie worker output to API requests."""

from __future__ import annotations

import io
import json
import logging
from datetime import timedelta

import psycopg
import pytest

from pidea_data import logging_setup, worker
from pidea_data.worker import heartbeat, process_one, try_heartbeat, utcnow


@pytest.fixture()
def json_logs():
    """Capture what the real JSON formatter would print."""
    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    handler.setFormatter(logging_setup.JsonFormatter())
    logger = logging.getLogger("pidea.worker")
    old_level = logger.level
    logger.addHandler(handler)
    logger.setLevel(logging.DEBUG)
    yield lambda: [json.loads(line) for line in stream.getvalue().splitlines()]
    logger.removeHandler(handler)
    logger.setLevel(old_level)


def customer(i):
    return {"customer_id": f"C{i}", "full_name": f"Person {i}", "email": f"p{i}@example.test",
            "created_at": "2024-03-01T10:00:00Z"}


# ------------------------------------------------------------------ formatter

def test_every_line_is_valid_json_even_with_quotes_and_newlines():
    record = logging.LogRecord("pidea.worker", logging.INFO, __file__, 1, 'said "hi"\nsecond line', (), None)
    entry = json.loads(logging_setup.JsonFormatter().format(record))
    assert entry["msg"] == 'said "hi"\nsecond line' and entry["level"] == "INFO" and entry["ts"].endswith("+00:00")


def test_only_allow_listed_extra_fields_are_emitted():
    record = logging.LogRecord("pidea.worker", logging.INFO, __file__, 1, "x", (), None)
    record.job_id = "j-1"
    record.correlation_id = "c-1"
    record.password = "hunter2"           # must never reach the log
    record.payload = {"email": "a@b.test"}
    line = logging_setup.JsonFormatter().format(record)
    entry = json.loads(line)
    assert entry["job_id"] == "j-1" and entry["correlation_id"] == "c-1"
    assert "password" not in entry and "payload" not in entry and "hunter2" not in line


def test_exceptions_are_logged_with_type_and_trace():
    try:
        raise ValueError("boom")
    except ValueError:
        import sys
        record = logging.LogRecord("pidea.worker", logging.ERROR, __file__, 1, "failed", (), sys.exc_info())
    entry = json.loads(logging_setup.JsonFormatter().format(record))
    assert entry["exc_type"] == "ValueError" and "Traceback" in entry["exception"]


# ------------------------------------------------------------------ job logs

def test_job_logs_carry_ids_duration_and_status(conn, make_job, json_logs):
    jid = make_job("CUSTOMER", [customer(1)])
    conn.execute("UPDATE integration.ingestion_job SET correlation_id='req-abc-123' WHERE id=%s", (jid,))
    assert process_one(conn) == "SUCCEEDED"
    claimed, finished = json_logs()
    assert claimed["msg"] == "job claimed" and finished["msg"] == "job finished"
    for entry in (claimed, finished):
        assert entry["job_id"] == str(jid) and entry["correlation_id"] == "req-abc-123"
        assert entry["job_type"] == "RECORDS"
    assert finished["status"] == "SUCCEEDED" and finished["duration_ms"] >= 0 and "error_category" not in finished
    assert finished["level"] == "INFO"


def test_failed_jobs_log_a_category_at_warning_level(conn, make_job, json_logs):
    make_job("ORDER", [{"junk": 1}], file_ref=None)   # every record rejected -> FAILED without an exception
    assert process_one(conn) == "FAILED"
    assert "error_category" not in json_logs()[-1]     # all-rejected is a data outcome, not an execution error

    bad = make_job("CUSTOMER", job_type="FILE_JSON", file_ref="missing.json")
    assert process_one(conn) == "FAILED"
    finished = json_logs()[-1]
    assert finished["job_id"] == str(bad) and finished["error_category"] == "permanent"
    assert finished["level"] == "WARNING"


def test_transient_failures_are_categorised(conn, make_job, json_logs, monkeypatch):
    make_job("CUSTOMER", [customer(1)])
    monkeypatch.setattr(worker, "load_valid", lambda *a, **k: (_ for _ in ()).throw(psycopg.OperationalError("x")))
    assert process_one(conn, rand=lambda: 0.5) == "RETRY_SCHEDULED"
    finished = json_logs()[-1]
    assert finished["error_category"] == "database" and finished["status"] == "RETRY_SCHEDULED"


# ------------------------------------------------------------------ heartbeat

def heartbeats(conn):
    return dict(conn.execute("SELECT worker_id, last_seen FROM integration.worker_heartbeat").fetchall())


def test_heartbeat_upserts_one_row_per_worker_and_prunes_dead_ones(conn):
    conn.execute("DELETE FROM integration.worker_heartbeat")
    now = utcnow()
    conn.execute("INSERT INTO integration.worker_heartbeat VALUES ('ancient', %s, %s)",
                 (now - timedelta(days=30), now - timedelta(days=30)))
    heartbeat(conn, "worker-a", now - timedelta(minutes=5), now - timedelta(seconds=20))
    heartbeat(conn, "worker-a", now - timedelta(minutes=5), now)
    heartbeat(conn, "worker-b", now, now)
    rows = heartbeats(conn)
    assert set(rows) == {"worker-a", "worker-b"}          # one row each; the 30-day-old worker was pruned
    assert rows["worker-a"] == now


def test_heartbeat_problems_other_than_connection_loss_never_stop_the_worker(conn, monkeypatch, json_logs):
    def denied(*args, **kwargs):
        raise psycopg.errors.InsufficientPrivilege("permission denied for table worker_heartbeat")

    monkeypatch.setattr(worker, "heartbeat", denied)
    try_heartbeat(conn, "w", utcnow())                    # swallowed and logged
    assert json_logs()[-1]["error_category"] == "InsufficientPrivilege"

    def lost(*args, **kwargs):
        raise psycopg.OperationalError("connection lost")

    monkeypatch.setattr(worker, "heartbeat", lost)
    with pytest.raises(psycopg.OperationalError):          # connection loss must reach the reconnect loop
        try_heartbeat(conn, "w", utcnow())
