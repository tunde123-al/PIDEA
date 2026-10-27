"""Pull connectors: the one extension point for sources the worker reads from on its own.

A pull job (HTTP_PULL, POSTGRES_PULL, ...) differs from a push (RECORDS) or file job only in *where the records come
from*. Everything after that is shared and must stay shared: mapping, validation, quarantine, idempotent loading,
job status and counters, retries/backoff, replay, audit. This module therefore only answers one question:

    "given this source's stored config, give me the next batch of raw records"

and the worker (``worker._extract_pull``) does everything around it: it loads and checks the source row, locks the
checkpoint (when the connector uses one), maps ``TransientSourceError``/``PermanentSourceError`` onto the job retry
policy, and later advances the checkpoint in the same transaction as the load.

Adding a connector (for example an OAuth REST API, a webhook drain, or another database):
  1. Implement ``PullConnector`` below: ``pull`` returns a ``PullBatch`` and raises ``TransientSourceError`` for
     "try again later" and ``PermanentSourceError`` for "retrying cannot help". Messages must be safe to store and
     show: never include credentials, hosts you resolved, SQL, or raw payloads.
  2. Register an instance in ``CONNECTORS`` under its job type.
  3. Platform side: add the value to ``SourceType`` / ``JobType`` (Java), widen the two CHECK constraints in a new
     Flyway migration, add config validation to ``SourceConfigValidator``, and map the source type to the job type
     in ``IngestionService.submitPull``. No ingestion, loader or status code needs to change.
  4. Resolve credentials from worker environment variables named by the source's ``secretRef`` (never from the
     config), allow-list outbound destinations deny-by-default, and add tests next to ``test_pg_source.py``.

Checkpointing is opt-in (``uses_checkpoint``). A connector that sets it receives the stored ``Checkpoint`` and may
return a new one in its batch; the worker persists it only if the job ends SUCCEEDED/PARTIALLY_SUCCEEDED, atomically
with the load. Delivery is at-least-once; loads are upserts, so re-reading the same records is harmless.
"""

from __future__ import annotations

import logging
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any, Protocol

from . import pg_source

# imported by name so tests can substitute the HTTP transport
from .rest_adapter import read_records

log = logging.getLogger("pidea.connectors")


@dataclass(frozen=True)
class Checkpoint:
    """Opaque resume position stored per (source, entity type). ``key`` says what the value refers to."""

    key: str | None = None
    value: str | None = None


@dataclass(frozen=True)
class PullBatch:
    """Records in the order they were read, plus the checkpoint to store if (and only if) the job succeeds."""

    records: list[tuple[str, Any]]
    checkpoint: Checkpoint | None = None


class PullConnector(Protocol):
    job_type: str        # value of ingestion_job.job_type this connector serves
    source_type: str     # value of integration_source.source_type it requires
    uses_checkpoint: bool

    def pull(self, config: Mapping[str, Any], secret_ref: str | None, checkpoint: Checkpoint) -> PullBatch: ...


class HttpApiConnector:
    """HTTPS JSON API pulled page by page. Stateless: every pull re-reads the whole (bounded) result."""

    job_type = "HTTP_PULL"
    source_type = "HTTP_API"
    uses_checkpoint = False

    def pull(self, config: Mapping[str, Any], secret_ref: str | None, checkpoint: Checkpoint) -> PullBatch:
        return PullBatch(read_records(dict(config), secret_ref))


class PostgresTableConnector:
    """Rows of one PostgreSQL table, resumed with a keyset checkpoint (see ``pg_source`` for the guarantees)."""

    job_type = "POSTGRES_PULL"
    source_type = "POSTGRES_TABLE"
    uses_checkpoint = True

    def pull(self, config: Mapping[str, Any], secret_ref: str | None, checkpoint: Checkpoint) -> PullBatch:
        value = checkpoint.value
        expected_key = pg_source.parse_settings(config).cursor_key
        if value is not None and checkpoint.key != expected_key:
            # The table or key column was reconfigured: the old cursor means nothing. Re-reading from the start is
            # safe because loads are idempotent upserts.
            log.warning("checkpoint belongs to a different table/key; restarting extraction")
            value = None
        result = pg_source.read_records(config, secret_ref, value)
        return PullBatch(result.records, Checkpoint(result.cursor_key, result.cursor_value))


CONNECTORS: dict[str, PullConnector] = {
    c.job_type: c for c in (HttpApiConnector(), PostgresTableConnector())
}
