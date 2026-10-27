"""End-to-end worker tests against a real PostgreSQL (schema from the Flyway migrations)."""

import uuid
from datetime import timedelta
from decimal import Decimal
from threading import Event, Thread

import psycopg
import pytest

from pidea_data import connectors, rest_adapter, worker
from pidea_data.worker import backoff_delay, process_one, recover_stale, utcnow


def customer(i, **kw):
    return {"customer_id": f"C{i}", "full_name": f"Person {i}", "email": f"p{i}@example.test",
            "created_at": "2024-03-01T10:00:00Z", **kw}


def job_row(conn, jid):
    return conn.execute("""SELECT status, records_received, records_accepted, records_rejected, records_duplicate,
                           retry_count, error_summary, started_at, completed_at
                           FROM integration.ingestion_job WHERE id=%s""", (jid,)).fetchone()


def count(conn, sql, *args):
    return conn.execute(sql, args).fetchone()[0]


def test_happy_path_loads_records_and_marks_success(conn, make_job):
    jid = make_job("CUSTOMER", [customer(1), customer(2), customer(3)])
    assert process_one(conn) == "SUCCEEDED"
    status, recv, acc, rej, dup, retries, err, started, done = job_row(conn, jid)
    assert (status, recv, acc, rej, dup, retries, err) == ("SUCCEEDED", 3, 3, 0, 0, 0, None)
    assert started is not None and done is not None
    assert count(conn, "select count(*) from analytics.customer") == 3
    assert count(conn, "select count(*) from integration.job_attempt where job_id=%s and outcome='SUCCEEDED'", jid) == 1


def test_mixed_batch_is_partially_succeeded_and_quarantines_rejects(conn, make_job):
    jid = make_job("CUSTOMER", [customer(1), customer(1, full_name="dup"), customer(2, created_at="bad"),
                                {"full_name": "No id", "created_at": "2024-01-01"}])
    assert process_one(conn) == "PARTIALLY_SUCCEEDED"
    status, recv, acc, rej, dup, *_ = job_row(conn, jid)
    assert (recv, acc, rej, dup) == (4, 1, 2, 1) and recv == acc + rej + dup
    reasons = dict(conn.execute("select source_record_ref, reason_code from integration.rejected_record where job_id=%s",
                                (jid,)).fetchall())
    assert reasons == {"seq 2": "INVALID_DATE", "seq 3": "INVALID_IDENTIFIER"} or reasons == {
        "seq 2": "INVALID_DATE", "seq 3": "MISSING_REQUIRED_FIELD"}
    assert count(conn, "select count(*) from analytics.customer") == 1


def test_all_invalid_batch_fails_without_loading(conn, make_job):
    jid = make_job("ORDER", [{"order_id": "O1"}, {"junk": 1}])
    assert process_one(conn) == "FAILED"
    assert job_row(conn, jid)[0] == "FAILED"
    assert count(conn, "select count(*) from analytics.customer_order") == 0
    assert count(conn, "select count(*) from integration.rejected_record where job_id=%s", jid) == 2


def test_money_is_stored_exactly(conn, make_job):
    make_job("ORDER", [{"order_id": "O1", "customer_id": "C1", "amount": 19.99, "currency": "usd",
                        "created_at": "2024-01-01"}])
    assert process_one(conn) == "SUCCEEDED"
    assert conn.execute("select amount from analytics.customer_order").fetchone()[0] == Decimal("19.9900")


def test_reprocessing_same_records_does_not_duplicate_business_rows(conn, make_job):
    make_job("CUSTOMER", [customer(1), customer(2)])
    assert process_one(conn) == "SUCCEEDED"
    make_job("CUSTOMER", [customer(1, full_name="Renamed"), customer(2)])
    assert process_one(conn) == "SUCCEEDED"
    assert count(conn, "select count(*) from analytics.customer") == 2
    assert conn.execute("select full_name from analytics.customer where customer_id='C1'").fetchone()[0] == "Renamed"


def test_queue_empty_returns_none_and_future_jobs_are_not_claimed(conn, make_job):
    assert process_one(conn) is None
    jid = make_job("CUSTOMER", [customer(1)])
    conn.execute("update integration.ingestion_job set next_attempt_at = now() + interval '1 hour' where id=%s", (jid,))
    assert process_one(conn) is None


def test_cancelled_jobs_are_never_run(conn, make_job):
    jid = make_job("CUSTOMER", [customer(1)])
    conn.execute("update integration.ingestion_job set status='CANCELLED' where id=%s", (jid,))
    assert process_one(conn) is None
    assert count(conn, "select count(*) from analytics.customer") == 0


def test_file_jobs_csv_and_json(conn, make_job, tmp_path):
    (tmp_path / "c.csv").write_text("customer_id,full_name,email,created_at\nC1,Ada,a@x.io,2024-01-01\nC2,,b@x.io,2024-01-01\n")
    (tmp_path / "o.json").write_text('[{"order_id":"O1","customer_id":"C1","amount":5.5,"currency":"EUR","created_at":"2024-01-01"}]')
    j1 = make_job("CUSTOMER", job_type="FILE_CSV", file_ref="c.csv")
    j2 = make_job("ORDER", job_type="FILE_JSON", file_ref="o.json")
    assert process_one(conn, data_dir=tmp_path) == "PARTIALLY_SUCCEEDED"
    assert process_one(conn, data_dir=tmp_path) == "SUCCEEDED"
    assert job_row(conn, j1)[1:5] == (2, 1, 1, 0)
    assert job_row(conn, j2)[1:5] == (1, 1, 0, 0)
    ref = conn.execute("select source_record_ref from integration.rejected_record where job_id=%s", (j1,)).fetchone()[0]
    assert ref == "row 2"


def test_uploaded_csv_is_quarantined_and_cleaned_after_partial_success(conn, make_job, tmp_path):
    file_ref = f"{uuid.uuid4()}.csv"
    (tmp_path / file_ref).write_text(
        "customer_id,full_name,created_at\nC1,Ada,2024-01-01\nC2,,invalid-date\n",
        encoding="utf-8",
    )
    jid = make_job("CUSTOMER", job_type="FILE_CSV", file_ref=file_ref)
    assert process_one(conn, data_dir=tmp_path) == "PARTIALLY_SUCCEEDED"
    assert job_row(conn, jid)[1:5] == (2, 1, 1, 0)
    assert count(conn, "select count(*) from integration.rejected_record where job_id=%s", jid) == 1
    assert not (tmp_path / file_ref).exists()


def test_failed_uploaded_file_is_retained_for_replay(conn, make_job, tmp_path):
    file_ref = f"{uuid.uuid4()}.json"
    (tmp_path / file_ref).write_text("{not json", encoding="utf-8")
    jid = make_job("CUSTOMER", job_type="FILE_JSON", file_ref=file_ref)
    assert process_one(conn, data_dir=tmp_path) == "FAILED"
    assert (tmp_path / file_ref).is_file()
    assert "cannot be parsed" in job_row(conn, jid)[6]


def test_failed_upload_is_retained_for_replay_window_then_expired(conn, make_job, tmp_path):
    file_ref = f"{uuid.uuid4()}.json"
    file_path = tmp_path / file_ref
    file_path.write_text("{bad", encoding="utf-8")
    jid = make_job("CUSTOMER", job_type="FILE_JSON", file_ref=file_ref)
    assert process_one(conn, data_dir=tmp_path) == "FAILED"
    assert file_path.is_file()

    submitted = conn.execute(
        "SELECT submitted_at FROM integration.ingestion_job WHERE id=%s", (jid,)
    ).fetchone()[0]
    conn.execute("UPDATE integration.ingestion_job SET submitted_at=%s WHERE id=%s",
                 (submitted - timedelta(days=31), jid))
    assert worker.cleanup_uploads(conn, tmp_path, utcnow()) == 1
    assert not file_path.exists()


def test_http_pull_uses_existing_transform_and_upsert_path(conn, make_job, monkeypatch):
    conn.execute("""
        UPDATE integration.integration_source
        SET source_type='HTTP_API', config='{"baseUrl":"https://api.example.test/customers"}'::jsonb,
            secret_ref='PIDEA_HTTP_API_BEARER_TOKEN'
        WHERE id=(SELECT id FROM integration.integration_source LIMIT 1)""")
    jid = make_job("CUSTOMER", job_type="HTTP_PULL")
    calls = []

    def mock_read(config, secret_ref):
        calls.append((config, secret_ref))
        return [("page 1 record 0", customer(77))]

    monkeypatch.setattr(connectors, "read_records", mock_read)
    assert process_one(conn) == "SUCCEEDED"
    assert calls[0][0]["baseUrl"] == "https://api.example.test/customers"
    assert calls[0][1] == "PIDEA_HTTP_API_BEARER_TOKEN"
    assert conn.execute("select customer_id from analytics.customer").fetchone()[0] == "C77"
    assert job_row(conn, jid)[2:5] == (1, 0, 0)


def test_http_pull_transient_failures_use_job_retry_policy(conn, make_job, monkeypatch):
    conn.execute("""
        UPDATE integration.integration_source SET source_type='HTTP_API',
            config='{"baseUrl":"https://api.example.test/customers"}'::jsonb
        WHERE id=(SELECT id FROM integration.integration_source LIMIT 1)""")
    jid = make_job("CUSTOMER", job_type="HTTP_PULL")

    def unavailable(*args, **kwargs):
        raise rest_adapter.TransientSourceError("HTTP_API request timed out")

    monkeypatch.setattr(connectors, "read_records", unavailable)
    assert process_one(conn, rand=lambda: 0.5) == "RETRY_SCHEDULED"
    assert job_row(conn, jid)[5] == 1
    assert job_row(conn, jid)[6] == "HTTP_API request timed out"


def test_bad_file_fails_permanently_without_retry(conn, make_job, tmp_path):
    (tmp_path / "bad.json").write_text("{nope")
    jid = make_job("CUSTOMER", job_type="FILE_JSON", file_ref="bad.json")
    assert process_one(conn, data_dir=tmp_path) == "FAILED"
    status, *_, retries, err, _s, _d = job_row(conn, jid)
    assert retries == 0 and "cannot be parsed" in err


def test_path_traversal_in_file_ref_is_refused(conn, make_job, tmp_path):
    base = tmp_path / "data"
    base.mkdir()
    (tmp_path / "outside.json").write_text("[]")
    jid = make_job("CUSTOMER", job_type="FILE_JSON", file_ref="../outside.json")
    assert process_one(conn, data_dir=base) == "FAILED"
    assert "escapes" in job_row(conn, jid)[6]


def test_invalid_mapping_fails_job_and_valid_mapping_applies(conn, make_job, source_id):
    jid = make_job("CUSTOMER", [{"client_ref": "C1", "full_name": "Ada", "registered_on": "2024-01-01"}])
    conn.execute("""INSERT INTO integration.mapping_definition (id, source_id, entity_type, version, field_mappings, created_at, created_by)
                    VALUES (gen_random_uuid(), %s, 'CUSTOMER', 1, '{"client_ref":"customer_id","registered_on":"created_at"}', now(), 't')""",
                 (source_id,))
    assert process_one(conn) == "SUCCEEDED"
    assert conn.execute("select mapping_version from integration.ingestion_job where id=%s", (jid,)).fetchone()[0] == 1
    jid2 = make_job("CUSTOMER", [customer(5)])
    conn.execute("""INSERT INTO integration.mapping_definition (id, source_id, entity_type, version, field_mappings, created_at, created_by)
                    VALUES (gen_random_uuid(), %s, 'CUSTOMER', 2, '{"a":"nonexistent"}', now(), 't')""", (source_id,))
    assert process_one(conn) == "FAILED"
    assert "invalid mapping" in job_row(conn, jid2)[6]


def test_failure_mid_load_rolls_back_everything(conn, make_job, monkeypatch):
    jid = make_job("CUSTOMER", [customer(1), customer(2)])

    def boom(*a, **k):
        raise RuntimeError("secret internal detail")

    monkeypatch.setattr(worker, "record_rejects", boom)   # fails AFTER customers were upserted in the same tx
    assert process_one(conn) == "FAILED"
    assert count(conn, "select count(*) from analytics.customer") == 0          # rolled back, not half-loaded
    err = job_row(conn, jid)[6]
    assert "RuntimeError" in err and "secret internal detail" not in err         # no leaked details


def test_transient_error_schedules_retry_with_backoff_then_gives_up(conn, make_job, monkeypatch):
    jid = make_job("CUSTOMER", [customer(1)], max_retries=2)

    def flaky(*a, **k):
        raise psycopg.OperationalError("connection lost")

    monkeypatch.setattr(worker, "load_valid", flaky)
    t0 = utcnow()
    assert process_one(conn, rand=lambda: 1.0) == "RETRY_SCHEDULED"
    status, *_, retries, err, _s, done = job_row(conn, jid)
    assert (retries, done) == (1, None)
    nxt = conn.execute("select next_attempt_at from integration.ingestion_job where id=%s", (jid,)).fetchone()[0]
    assert t0 + timedelta(seconds=29) < nxt < t0 + timedelta(seconds=40)        # base 30 s * 2**0, full jitter = 1.0
    assert process_one(conn) is None                                              # not due yet
    conn.execute("update integration.ingestion_job set next_attempt_at = %s where id=%s",
                 (utcnow() - timedelta(seconds=1), jid))
    assert process_one(conn, rand=lambda: 0.0) == "RETRY_SCHEDULED"
    conn.execute("update integration.ingestion_job set next_attempt_at = %s where id=%s",
                 (utcnow() - timedelta(seconds=1), jid))
    assert process_one(conn) == "FAILED"                                          # retries exhausted
    assert job_row(conn, jid)[5] == 2
    assert count(conn, "select count(*) from integration.job_attempt where job_id=%s", jid) == 3


def test_stale_recovery_does_not_take_a_live_workers_locked_job(conn, pg_url, make_job, monkeypatch):
    jid = make_job("CUSTOMER", [customer(1)])
    job = worker.claim_next(conn, utcnow())
    assert job is not None
    conn.execute("update integration.ingestion_job set started_at=now() - interval '2 hours' where id=%s", (jid,))

    entered = Event()
    release = Event()
    original_extract = worker._extract

    def slow_extract(*args, **kwargs):
        entered.set()
        assert release.wait(timeout=10)
        return original_extract(*args, **kwargs)

    monkeypatch.setattr(worker, "_extract", slow_extract)
    result = []
    with psycopg.connect(pg_url, autocommit=True) as active:
        thread = Thread(target=lambda: result.append(worker.run_job(active, job, None)))
        thread.start()
        try:
            assert entered.wait(timeout=10)
            with psycopg.connect(pg_url, autocommit=True) as recovering:
                assert recover_stale(recovering, timedelta(minutes=15), utcnow()) == 0
        finally:
            release.set()
            thread.join(timeout=10)
    assert not thread.is_alive()
    assert result == ["SUCCEEDED"]


def test_recovered_attempt_cannot_overwrite_a_new_attempt(conn, make_job):
    jid = make_job("CUSTOMER", [customer(1)])
    old_attempt = worker.claim_next(conn, utcnow())
    assert old_attempt is not None
    conn.execute("update integration.ingestion_job set started_at=now() - interval '2 hours' where id=%s", (jid,))
    assert recover_stale(conn, timedelta(minutes=15), utcnow(), rand=lambda: 0.0) == 1

    new_attempt = worker.claim_next(conn, utcnow() + timedelta(minutes=1))
    assert new_attempt is not None
    with pytest.raises(worker.JobClaimLost):
        worker.run_job(conn, old_attempt, None)

    assert worker.run_job(conn, new_attempt, None) == "SUCCEEDED"
    assert job_row(conn, jid)[0] == "SUCCEEDED"


def test_retry_after_transient_failure_succeeds_without_duplicates(conn, make_job, monkeypatch):
    jid = make_job("CUSTOMER", [customer(1), customer(2)])
    real = worker.load_valid
    monkeypatch.setattr(worker, "load_valid", lambda *a, **k: (_ for _ in ()).throw(psycopg.OperationalError("x")))
    assert process_one(conn, rand=lambda: 0.0) == "RETRY_SCHEDULED"
    monkeypatch.setattr(worker, "load_valid", real)
    assert process_one(conn) == "SUCCEEDED"
    assert count(conn, "select count(*) from analytics.customer") == 2
    assert job_row(conn, jid)[0] == "SUCCEEDED"


def test_stale_running_job_is_recovered(conn, make_job):
    jid = make_job("CUSTOMER", [customer(1)])
    conn.execute("""update integration.ingestion_job set status='RUNNING', started_at = now() - interval '2 hours'
                    where id=%s""", (jid,))
    assert recover_stale(conn, timedelta(minutes=15), utcnow(), rand=lambda: 0.0) == 1
    assert job_row(conn, jid)[0] == "RETRY_SCHEDULED"
    assert process_one(conn, now_fn=lambda: utcnow() + timedelta(minutes=1)) == "SUCCEEDED"
    # fresh RUNNING jobs are left alone
    jid2 = make_job("CUSTOMER", [customer(9)])
    conn.execute("update integration.ingestion_job set status='RUNNING', started_at = now() where id=%s", (jid2,))
    assert recover_stale(conn, timedelta(minutes=15), utcnow()) == 0


def test_two_workers_never_claim_the_same_job(conn, pg_url, make_job):
    make_job("CUSTOMER", [customer(1)])
    with psycopg.connect(pg_url, autocommit=True) as other:
        first = worker.claim_next(conn, utcnow())
        second = worker.claim_next(other, utcnow())
    assert first is not None and second is None


def test_backoff_is_bounded_and_grows():
    assert backoff_delay(0, rand=lambda: 1.0) == timedelta(seconds=30)
    assert backoff_delay(3, rand=lambda: 1.0) == timedelta(seconds=240)
    assert backoff_delay(20, rand=lambda: 1.0) == timedelta(seconds=900)       # capped
    assert backoff_delay(5, rand=lambda: 0.0) == timedelta(0)                   # full jitter lower bound


def test_counter_constraint_is_enforced_by_database(conn, make_job):
    jid = make_job("CUSTOMER", [customer(1)])
    with pytest.raises(psycopg.errors.CheckViolation):
        conn.execute("update integration.ingestion_job set status='SUCCEEDED', records_received=5 where id=%s", (jid,))
