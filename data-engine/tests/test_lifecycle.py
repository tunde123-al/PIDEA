"""Worker lifecycle: bounded data retention, the stop flag, and a real SIGTERM against the real process."""

from __future__ import annotations

import os
import signal
import subprocess
import sys
import threading
import time
import uuid
from datetime import timedelta

import pytest

from pidea_data import worker
from pidea_data.worker import purge_expired, run_loop, utcnow


def customer(i):
    return {"customer_id": f"C{i}", "full_name": f"Person {i}", "email": f"p{i}@example.test",
            "created_at": "2024-03-01T10:00:00Z"}


def count(conn, sql, *args):
    return conn.execute(sql, args).fetchone()[0]


# ------------------------------------------------------------------ retention

def add_idempotency(conn, n, expired):
    for _ in range(n):
        conn.execute(
            """INSERT INTO integration.idempotency_record (id, scope, idempotency_key, request_fingerprint, created_at, expires_at)
               VALUES (%s, 'scope', %s, %s, now(), now() + %s::interval)""",
            (uuid.uuid4(), uuid.uuid4().hex, "0" * 64, "-1 hour" if expired else "+1 hour"))


def test_expired_idempotency_records_are_purged_in_bounded_batches(conn):
    conn.execute("DELETE FROM integration.idempotency_record")
    add_idempotency(conn, 5, expired=True)
    add_idempotency(conn, 2, expired=False)
    removed = purge_expired(conn, utcnow(), None, batch=2)    # 5 expired rows need three batches of 2
    assert removed["idempotency_records"] == 5
    assert count(conn, "SELECT count(*) FROM integration.idempotency_record") == 2   # the live keys survive


def finished_job_with_payload(conn, source_id, status, completed_ago, records=3):
    jid = uuid.uuid4()
    completed = f"-{completed_ago}" if completed_ago else None
    received = records if status == "SUCCEEDED" else 0   # the table requires received = accepted + rejected + duplicate
    conn.execute(
        """INSERT INTO integration.ingestion_job (id, source_id, job_type, entity_type, status, correlation_id,
             records_received, records_accepted, next_attempt_at, submitted_at, completed_at, submitted_by)
           VALUES (%s,%s,'RECORDS','CUSTOMER',%s,'c',%s,%s,now(),now(), now() + %s::interval,'t')""",
        (jid, source_id, status, received, received, completed or "0 seconds"))
    if completed is None:
        conn.execute("UPDATE integration.ingestion_job SET completed_at = NULL WHERE id=%s", (jid,))
    for seq in range(records):
        conn.execute("INSERT INTO integration.raw_record (job_id, seq, payload) VALUES (%s,%s,%s::jsonb)",
                     (jid, seq, f'{{"customer_id": "C{seq}"}}'))
    return jid


def raw_count(conn, jid):
    return count(conn, "SELECT count(*) FROM integration.raw_record WHERE job_id=%s", jid)


def test_raw_payloads_are_purged_only_for_old_finished_jobs(conn, source_id):
    old_ok = finished_job_with_payload(conn, source_id, "SUCCEEDED", "40 days")
    old_failed = finished_job_with_payload(conn, source_id, "FAILED", "40 days")
    old_cancelled = finished_job_with_payload(conn, source_id, "CANCELLED", "40 days")
    recent = finished_job_with_payload(conn, source_id, "SUCCEEDED", "2 days")
    waiting = finished_job_with_payload(conn, source_id, "PENDING", None)
    running = finished_job_with_payload(conn, source_id, "RUNNING", None)
    retry = finished_job_with_payload(conn, source_id, "RETRY_SCHEDULED", None)

    removed = purge_expired(conn, utcnow(), timedelta(days=30), batch=2)

    assert removed["raw_records"] == 9                                      # 3 old jobs x 3 payloads, over 5 batches
    assert [raw_count(conn, j) for j in (old_ok, old_failed, old_cancelled)] == [0, 0, 0]
    assert [raw_count(conn, j) for j in (recent, waiting, running, retry)] == [3, 3, 3, 3]
    # only the payload goes: the job row (and its history) stays
    assert count(conn, "SELECT count(*) FROM integration.ingestion_job WHERE id=%s", old_ok) == 1


def test_retention_can_be_disabled(conn, source_id):
    jid = finished_job_with_payload(conn, source_id, "SUCCEEDED", "400 days")
    assert purge_expired(conn, utcnow(), None)["raw_records"] == 0
    assert raw_count(conn, jid) == 3


def test_retention_setting_is_validated(monkeypatch):
    from pidea_data.__main__ import raw_retention_from_env
    monkeypatch.delenv("PIDEA_RAW_RETENTION_DAYS", raising=False)
    assert raw_retention_from_env() == timedelta(days=30)
    monkeypatch.setenv("PIDEA_RAW_RETENTION_DAYS", "7")
    assert raw_retention_from_env() == timedelta(days=7)
    monkeypatch.setenv("PIDEA_RAW_RETENTION_DAYS", "0")
    assert raw_retention_from_env() is None                                  # 0 = keep forever
    for bad in ("-1", "abc", "1.5", "99999"):
        monkeypatch.setenv("PIDEA_RAW_RETENTION_DAYS", bad)
        with pytest.raises(SystemExit):
            raw_retention_from_env()


# --------------------------------------------------------------- stop flag

def wait_for(predicate, timeout=20.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(0.1)
    return False


def test_loop_processes_jobs_reports_a_heartbeat_and_stops_when_asked(pg_url, conn, make_job):
    conn.execute("DELETE FROM integration.worker_heartbeat")
    jid = make_job("CUSTOMER", [customer(1)])
    stop = threading.Event()
    thread = threading.Thread(target=run_loop, args=(pg_url, None, stop, 0.1), daemon=True)
    thread.start()
    try:
        assert wait_for(lambda: conn.execute("SELECT status FROM integration.ingestion_job WHERE id=%s",
                                             (jid,)).fetchone()[0] == "SUCCEEDED"), "the loop never ran the job"
        assert wait_for(lambda: count(conn, "SELECT count(*) FROM integration.worker_heartbeat") == 1)
    finally:
        stop.set()
        thread.join(timeout=10)
    assert not thread.is_alive(), "the loop must exit promptly after stop is set"


def test_a_job_in_progress_finishes_before_the_loop_exits(pg_url, conn, make_job, monkeypatch):
    jid = make_job("CUSTOMER", [customer(1), customer(2)])
    stop = threading.Event()
    real_load = worker.load_valid

    def stop_while_loading(*args, **kwargs):
        stop.set()                      # shutdown arrives in the middle of the job
        time.sleep(0.3)
        return real_load(*args, **kwargs)

    monkeypatch.setattr(worker, "load_valid", stop_while_loading)
    thread = threading.Thread(target=run_loop, args=(pg_url, None, stop, 0.1), daemon=True)
    thread.start()
    thread.join(timeout=20)
    assert not thread.is_alive()
    assert conn.execute("SELECT status, records_accepted FROM integration.ingestion_job WHERE id=%s",
                        (jid,)).fetchone() == ("SUCCEEDED", 2)             # completed, not abandoned


def test_the_loop_stops_even_while_the_database_is_unreachable():
    stop = threading.Event()
    thread = threading.Thread(target=run_loop, args=("postgresql://x:y@127.0.0.1:1/none", None, stop, 0.1),
                              daemon=True)
    thread.start()
    time.sleep(1.0)                    # first connection attempt fails; the loop is now backing off
    stop.set()
    thread.join(timeout=5)
    assert not thread.is_alive()       # the backoff wait is interruptible


# ------------------------------------------------------------ real SIGTERM

@pytest.mark.skipif(sys.platform == "win32", reason="POSIX signals")
def test_sigterm_stops_the_real_worker_process_cleanly_and_quickly(pg_url, conn):
    conn.execute("DELETE FROM integration.worker_heartbeat")
    env = {**os.environ, "PIDEA_DB_DSN": pg_url}
    proc = subprocess.Popen([sys.executable, "-m", "pidea_data"], env=env, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, text=True)
    try:
        assert wait_for(lambda: count(conn, "SELECT count(*) FROM integration.worker_heartbeat") == 1, 30), \
            "the worker process never reported a heartbeat"
        started = time.monotonic()
        proc.send_signal(signal.SIGTERM)
        out, err = proc.communicate(timeout=15)
        elapsed = time.monotonic() - started
    finally:
        if proc.poll() is None:
            proc.kill()
    assert proc.returncode == 0, err
    assert elapsed < 8                                          # well inside docker's stop grace period
    assert '"msg": "worker stopped"' in err + out
