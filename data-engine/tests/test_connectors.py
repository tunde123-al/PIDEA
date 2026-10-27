"""The pull-connector extension point (connectors.py).

The central claim: a new connector needs only to implement ``PullConnector``; checkpointing, source checks, error
mapping, retries, counters and loading come from the shared worker code. The stub connector below proves it by
plugging into the pipeline without any ingestion code of its own.
"""

from __future__ import annotations

import json

import pytest

from pidea_data import connectors
from pidea_data.connectors import CONNECTORS, Checkpoint, PullBatch
from pidea_data.rest_adapter import PermanentSourceError, TransientSourceError
from pidea_data.worker import process_one


def customer(i):
    return {"customer_id": f"C{i}", "full_name": f"Person {i}", "email": f"p{i}@example.test",
            "created_at": "2024-03-01T10:00:00Z"}


class StubConnector:
    """A connector written from scratch against the Protocol only: it reads 'rows' from a list in its config."""

    job_type = "HTTP_PULL"      # reuses an existing, DB-allowed job type; a real connector adds its own via migration
    source_type = "HTTP_API"

    def __init__(self, uses_checkpoint):
        self.uses_checkpoint = uses_checkpoint
        self.calls = []
        self.error = None

    def pull(self, config, secret_ref, checkpoint):
        self.calls.append((dict(config), secret_ref, checkpoint))
        if self.error:
            raise self.error
        start = int(checkpoint.value or 0)
        ids = [i for i in config["ids"] if i > start][: config.get("limit", 2)]
        records = [(f"item {i}", customer(i)) for i in ids]
        return PullBatch(records, Checkpoint("stub.ids", str(ids[-1])) if ids else None)


@pytest.fixture()
def http_source(conn):
    conn.execute("""UPDATE integration.integration_source SET source_type='HTTP_API', secret_ref='STUB_SECRET',
                    config=%s::jsonb WHERE id=(SELECT id FROM integration.integration_source LIMIT 1)""",
                 (json.dumps({"ids": [1, 2, 3, 4, 5], "limit": 2}),))


def checkpoint_rows(conn):
    return conn.execute("SELECT cursor_key, cursor_value FROM integration.source_checkpoint").fetchall()


def test_registry_matches_what_the_database_accepts(conn, source_id):
    """Guards drift between the Python registry and the Flyway CHECK constraint on ingestion_job.job_type."""
    assert set(CONNECTORS) == {"HTTP_PULL", "POSTGRES_PULL"}
    for job_type, connector in CONNECTORS.items():
        assert connector.job_type == job_type
        conn.execute(
            """INSERT INTO integration.ingestion_job (id, source_id, job_type, entity_type, status, correlation_id,
                 records_received, next_attempt_at, submitted_at, submitted_by)
               VALUES (gen_random_uuid(), %s, %s, 'CUSTOMER', 'PENDING', 'c', 0, now(), now(), 't')""",
            (source_id, job_type))
    assert connectors.HttpApiConnector.uses_checkpoint is False
    assert connectors.PostgresTableConnector.uses_checkpoint is True


def test_new_connector_gets_checkpointing_loading_and_status_for_free(conn, make_job, http_source, monkeypatch):
    stub = StubConnector(uses_checkpoint=True)
    monkeypatch.setitem(CONNECTORS, "HTTP_PULL", stub)

    make_job("CUSTOMER", job_type="HTTP_PULL")
    assert process_one(conn) == "SUCCEEDED"
    config, secret_ref, first = stub.calls[0]
    assert config["ids"] == [1, 2, 3, 4, 5] and secret_ref == "STUB_SECRET"   # stored config + secretRef name
    assert first == Checkpoint(None, None)                                     # nothing stored yet
    assert checkpoint_rows(conn) == [("stub.ids", "2")]                        # persisted by shared code

    make_job("CUSTOMER", job_type="HTTP_PULL")
    assert process_one(conn) == "SUCCEEDED"
    assert stub.calls[1][2] == Checkpoint("stub.ids", "2")                     # resumed from the stored checkpoint
    assert checkpoint_rows(conn) == [("stub.ids", "4")]
    assert conn.execute("SELECT count(*) FROM analytics.customer").fetchone()[0] == 4   # loaded by shared code


def test_stateless_connector_never_touches_the_checkpoint_table(conn, make_job, http_source, monkeypatch):
    stub = StubConnector(uses_checkpoint=False)
    monkeypatch.setitem(CONNECTORS, "HTTP_PULL", stub)
    make_job("CUSTOMER", job_type="HTTP_PULL")
    assert process_one(conn) == "SUCCEEDED"
    assert stub.calls[0][2] == Checkpoint(None, None) and checkpoint_rows(conn) == []


def test_connector_errors_follow_the_shared_retry_policy_and_keep_the_checkpoint(conn, make_job, http_source,
                                                                                monkeypatch):
    stub = StubConnector(uses_checkpoint=True)
    monkeypatch.setitem(CONNECTORS, "HTTP_PULL", stub)
    make_job("CUSTOMER", job_type="HTTP_PULL")
    assert process_one(conn) == "SUCCEEDED"

    stub.error = TransientSourceError("upstream busy")
    jid = make_job("CUSTOMER", job_type="HTTP_PULL")
    assert process_one(conn, rand=lambda: 0.5) == "RETRY_SCHEDULED"
    stub.error = PermanentSourceError("bad credentials")
    jid2 = make_job("CUSTOMER", job_type="HTTP_PULL")
    assert process_one(conn) == "FAILED"
    summaries = dict(conn.execute("SELECT id, error_summary FROM integration.ingestion_job WHERE id = ANY(%s)",
                                  ([jid, jid2],)).fetchall())
    assert summaries[jid] == "upstream busy" and summaries[jid2] == "bad credentials"
    assert checkpoint_rows(conn) == [("stub.ids", "2")]   # failures never moved it


def test_source_type_must_match_the_connector(conn, make_job, monkeypatch):
    monkeypatch.setitem(CONNECTORS, "HTTP_PULL", StubConnector(uses_checkpoint=False))
    jid = make_job("CUSTOMER", job_type="HTTP_PULL")  # the fixture source is REST_PUSH, not HTTP_API
    assert process_one(conn) == "FAILED"
    assert conn.execute("SELECT error_summary FROM integration.ingestion_job WHERE id=%s",
                        (jid,)).fetchone()[0] == "HTTP_API source is unavailable or disabled"
