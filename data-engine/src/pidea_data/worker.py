"""Job worker: claims jobs from PostgreSQL, runs the pipeline, records the outcome.

Java <-> Python contract (database-backed polling, no broker):
  * Java inserts a PENDING job (+ raw_record rows or a file_ref).
  * The worker claims it atomically with ``FOR UPDATE SKIP LOCKED`` so several workers never take the same job.
  * Loading, quarantine writes and the final counters are ONE transaction: a job is never half-applied.
  * Transient infrastructure errors -> RETRY_SCHEDULED with exponential backoff + full jitter, up to max_retries.
    Permanent errors (bad file, bad mapping, unexpected bug) -> FAILED, never retried.
  * Crash recovery: a job stuck in RUNNING longer than the timeout is re-queued (loads are idempotent upserts).
"""

from __future__ import annotations

import json
import logging
import random
import re
import uuid
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from decimal import Decimal
from pathlib import Path

import psycopg

from . import extractors
from .loaders import load_valid, record_rejects
from .rest_adapter import PermanentSourceError, TransientSourceError, read_records
from .transform import MappingError, transform

log = logging.getLogger("pidea.worker")

BACKOFF_BASE_SECONDS = 30
BACKOFF_CAP_SECONDS = 900
UPLOAD_RETENTION = timedelta(days=30)
_UPLOAD_REF = re.compile(
    r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.(csv|json)"
)


class PermanentJobError(Exception):
    """Job cannot succeed by retrying. The message is curated and safe to store."""


class JobClaimLost(Exception):
    """This worker no longer owns the current attempt for the job."""


class TransientJobError(Exception):
    """The job failed for a temporary source or infrastructure condition."""


@dataclass(frozen=True)
class Job:
    id: uuid.UUID
    source_id: uuid.UUID
    job_type: str
    entity_type: str
    file_ref: str | None
    retry_count: int
    max_retries: int
    correlation_id: str


def utcnow() -> datetime:
    return datetime.now(UTC)


def backoff_delay(retry_count: int, rand: Callable[[], float] = random.random) -> timedelta:
    """Exponential backoff with full jitter: uniform in [0, min(cap, base * 2**n)]."""
    ceiling = min(BACKOFF_CAP_SECONDS, BACKOFF_BASE_SECONDS * (2 ** retry_count))
    return timedelta(seconds=rand() * ceiling)


def claim_next(conn: psycopg.Connection, now: datetime) -> Job | None:
    """Atomically move one runnable job to RUNNING and open an attempt row."""
    with conn.transaction(), conn.cursor() as cur:
        cur.execute(
            """UPDATE integration.ingestion_job SET status='RUNNING', started_at=%s, completed_at=NULL,
                      error_summary=NULL, version=version+1
               WHERE id = (SELECT id FROM integration.ingestion_job
                           WHERE status IN ('PENDING','RETRY_SCHEDULED') AND next_attempt_at <= %s
                           ORDER BY next_attempt_at, id FOR UPDATE SKIP LOCKED LIMIT 1)
               RETURNING id, source_id, job_type, entity_type, file_ref, retry_count, max_retries, correlation_id""",
            (now, now),
        )
        row = cur.fetchone()
        if row is None:
            return None
        job = Job(*row)
        cur.execute(
            """INSERT INTO integration.job_attempt (id, job_id, attempt_no, outcome, started_at)
               VALUES (%s,%s,%s,'RUNNING',%s)""",
            (uuid.uuid4(), job.id, job.retry_count + 1, now),
        )
        return job


def _extract(conn: psycopg.Connection, job: Job, data_dir: Path | None):
    if job.job_type == "RECORDS":
        # payload::text + Decimal float parsing keeps amounts exact
        with conn.cursor() as cur:
            cur.execute("SELECT seq, payload::text FROM integration.raw_record WHERE job_id=%s ORDER BY seq", (job.id,))
            rows = cur.fetchall()
        return [(f"seq {seq}", json.loads(text, parse_float=Decimal)) for seq, text in rows]
    if job.job_type in ("FILE_CSV", "FILE_JSON"):
        if data_dir is None or not job.file_ref:
            raise PermanentJobError("file ingestion is not configured")
        try:
            path = extractors.resolve_safe(data_dir, job.file_ref)
            reader = extractors.read_csv if job.job_type == "FILE_CSV" else extractors.read_json
            return list(reader(path))
        except extractors.ExtractionError as e:
            raise PermanentJobError(str(e)) from None
    if job.job_type == "HTTP_PULL":
        with conn.cursor() as cur:
            cur.execute(
                "SELECT status, source_type, config::text, secret_ref FROM integration.integration_source WHERE id=%s",
                (job.source_id,),
            )
            source = cur.fetchone()
        if source is None or source[0] != "ACTIVE" or source[1] != "HTTP_API":
            raise PermanentJobError("HTTP_API source is unavailable or disabled")
        try:
            return read_records(json.loads(source[2]), source[3])
        except TransientSourceError as e:
            raise TransientJobError(str(e)) from None
        except PermanentSourceError as e:
            raise PermanentJobError(str(e)) from None
    raise PermanentJobError(f"unsupported job type {job.job_type}")


def _cleanup_uploaded_file(job: Job, data_dir: Path | None) -> None:
    if data_dir is None or job.job_type not in ("FILE_CSV", "FILE_JSON") or not job.file_ref:
        return
    if _UPLOAD_REF.fullmatch(job.file_ref) is None:
        return
    try:
        path = extractors.resolve_safe(data_dir, job.file_ref)
        path.unlink(missing_ok=True)
    except (extractors.ExtractionError, OSError):
        log.exception("could not clean up completed uploaded file", extra={"job_id": str(job.id)})


def cleanup_uploads(conn: psycopg.Connection, data_dir: Path | None, now: datetime | None = None) -> int:
    """Remove completed or expired uploads and old interrupted-write files without touching sample files."""
    if data_dir is None or not data_dir.is_dir():
        return 0
    now = now or utcnow()
    cutoff = now - UPLOAD_RETENTION
    with conn.cursor() as cur:
        cur.execute(
            """SELECT DISTINCT finished.file_ref
               FROM integration.ingestion_job AS finished
               WHERE finished.file_ref IS NOT NULL
                 AND finished.job_type IN ('FILE_CSV','FILE_JSON')
                 AND (finished.status IN ('SUCCEEDED','PARTIALLY_SUCCEEDED') OR finished.submitted_at < %s)
                 AND NOT EXISTS (
                     SELECT 1 FROM integration.ingestion_job AS active
                     WHERE active.file_ref = finished.file_ref
                       AND active.status IN ('PENDING','RUNNING','RETRY_SCHEDULED'))
                 AND NOT EXISTS (
                     SELECT 1 FROM integration.ingestion_job AS later_failure
                     WHERE later_failure.file_ref = finished.file_ref
                       AND later_failure.status = 'FAILED'
                       AND later_failure.submitted_at > finished.submitted_at)""",
            (cutoff,),
        )
        safe_refs = {row[0] for row in cur.fetchall() if _UPLOAD_REF.fullmatch(row[0])}
        cur.execute("SELECT DISTINCT file_ref FROM integration.ingestion_job WHERE file_ref IS NOT NULL")
        referenced = {row[0] for row in cur.fetchall()}

    removed = 0
    for path in data_dir.iterdir():
        if not path.is_file() or path.is_symlink():
            continue
        try:
            if path.name in safe_refs:
                _unlink_managed_file(data_dir, path.name)
                removed += 1
            elif path.name not in referenced and (
                    _UPLOAD_REF.fullmatch(path.name)
                    or path.name.startswith("pidea-upload-") and path.name.endswith(".part")):
                if path.stat().st_mtime < cutoff.timestamp():
                    path.unlink()
                    removed += 1
        except OSError:
            log.exception("could not remove stale uploaded file")
    return removed


def _unlink_managed_file(data_dir: Path, file_ref: str) -> None:
    if _UPLOAD_REF.fullmatch(file_ref) is None:
        return
    try:
        path = extractors.resolve_safe(data_dir, file_ref)
        path.unlink(missing_ok=True)
    except extractors.ExtractionError:
        return


def _latest_mapping(conn: psycopg.Connection, job: Job) -> tuple[dict[str, str] | None, int | None]:
    with conn.cursor() as cur:
        cur.execute(
            """SELECT version, field_mappings::text FROM integration.mapping_definition
               WHERE source_id=%s AND entity_type=%s ORDER BY version DESC LIMIT 1""",
            (job.source_id, job.entity_type),
        )
        row = cur.fetchone()
    return (json.loads(row[1]), row[0]) if row else (None, None)


def run_job(conn: psycopg.Connection, job: Job, data_dir: Path | None, now_fn: Callable[[], datetime] = utcnow) -> str:
    """Process one claimed job inside a single transaction and return its final status."""
    with conn.transaction():
        with conn.cursor() as cur:
            cur.execute(
                "SELECT status, retry_count FROM integration.ingestion_job WHERE id=%s FOR UPDATE",
                (job.id,),
            )
            claim = cur.fetchone()
        if claim is None or claim[0] != "RUNNING" or claim[1] != job.retry_count:
            raise JobClaimLost(f"worker no longer owns job {job.id}")
        records = _extract(conn, job, data_dir)
        mapping, mapping_version = _latest_mapping(conn, job)
        try:
            result = transform(job.entity_type, records, mapping)
        except MappingError as e:
            raise PermanentJobError(f"invalid mapping definition: {e}") from None
        now = now_fn()
        with conn.cursor() as cur:
            source_id = job.source_id
            load_valid(cur, job.entity_type, source_id, job.id, result.valid, now)
            record_rejects(cur, job.entity_type, source_id, job.id, result.rejects, now)
            rejected = len(result.rejects)
            good = result.accepted + result.duplicates
            if rejected == 0:
                status, summary = "SUCCEEDED", None
            elif good > 0:
                status, summary = "PARTIALLY_SUCCEEDED", f"{rejected} of {result.received} records rejected"
            else:
                status, summary = "FAILED", "all records were rejected"
            cur.execute(
                """UPDATE integration.ingestion_job SET status=%s, completed_at=%s, records_received=%s,
                          records_accepted=%s, records_rejected=%s, records_duplicate=%s, error_summary=%s,
                          mapping_version=%s, version=version+1 WHERE id=%s""",
                (status, now, result.received, result.accepted, rejected, result.duplicates, summary,
                 mapping_version, job.id),
            )
            cur.execute(
                "UPDATE integration.job_attempt SET outcome=%s, finished_at=%s, error=%s WHERE job_id=%s AND attempt_no=%s",
                ("SUCCEEDED" if status != "FAILED" else "FAILED", now, summary, job.id, job.retry_count + 1),
            )
    return status


def _fail_or_retry(conn: psycopg.Connection, job: Job, transient: bool, message: str, now: datetime,
                   rand: Callable[[], float]) -> str:
    retry = transient and job.retry_count < job.max_retries
    with conn.transaction(), conn.cursor() as cur:
        if retry:
            status = "RETRY_SCHEDULED"
            cur.execute(
                """UPDATE integration.ingestion_job SET status=%s, retry_count=retry_count+1, next_attempt_at=%s,
                          error_summary=%s, version=version+1
                   WHERE id=%s AND status='RUNNING' AND retry_count=%s""",
                (status, now + backoff_delay(job.retry_count, rand), message, job.id, job.retry_count),
            )
        else:
            status = "FAILED"
            cur.execute(
                """UPDATE integration.ingestion_job SET status=%s, completed_at=%s, error_summary=%s,
                          version=version+1
                   WHERE id=%s AND status='RUNNING' AND retry_count=%s""",
                (status, now, message, job.id, job.retry_count),
            )
        if cur.rowcount:
            cur.execute(
                "UPDATE integration.job_attempt SET outcome='FAILED', finished_at=%s, error=%s WHERE job_id=%s AND attempt_no=%s",
                (now, message, job.id, job.retry_count + 1),
            )
        else:
            return "SUPERSEDED"
    return status


def process_one(conn: psycopg.Connection, data_dir: Path | None = None,
                now_fn: Callable[[], datetime] = utcnow, rand: Callable[[], float] = random.random) -> str | None:
    """Claim and run one job. Returns the final status, or None when the queue is empty."""
    job = claim_next(conn, now_fn())
    if job is None:
        return None
    log.info("job claimed", extra={"job_id": str(job.id), "correlation_id": job.correlation_id})
    try:
        status = run_job(conn, job, data_dir, now_fn)
    except JobClaimLost:
        status = "SUPERSEDED"
    except PermanentJobError as e:
        status = _fail_or_retry(conn, job, False, str(e)[:500], now_fn(), rand)
    except TransientJobError as e:
        status = _fail_or_retry(conn, job, True, str(e)[:500], now_fn(), rand)
    except (psycopg.OperationalError, psycopg.InterfaceError):
        # Database/network trouble: the transaction rolled back, the load is idempotent, so retry is safe.
        status = _fail_or_retry(conn, job, True, "transient database error", now_fn(), rand)
    except Exception as e:  # noqa: BLE001 - unknown bug: fail safely, never retry blindly, never leak details
        log.exception("unexpected error", extra={"job_id": str(job.id)})
        status = _fail_or_retry(conn, job, False, f"unexpected error ({type(e).__name__})", now_fn(), rand)
    if status == "SUPERSEDED":
        log.info("job attempt superseded", extra={"job_id": str(job.id), "correlation_id": job.correlation_id})
        return status
    if status in ("SUCCEEDED", "PARTIALLY_SUCCEEDED"):
        _cleanup_uploaded_file(job, data_dir)
    log.info("job finished", extra={"job_id": str(job.id), "status": status, "correlation_id": job.correlation_id})
    return status


def recover_stale(conn: psycopg.Connection, timeout: timedelta, now: datetime,
                  rand: Callable[[], float] = random.random) -> int:
    """Re-queue jobs whose worker died mid-run (RUNNING for longer than ``timeout``)."""
    with conn.transaction():
        with conn.cursor() as cur:
            cur.execute(
                """SELECT id, source_id, job_type, entity_type, file_ref, retry_count, max_retries, correlation_id
                   FROM integration.ingestion_job
                   WHERE status='RUNNING' AND started_at < %s
                   FOR UPDATE SKIP LOCKED""",
                (now - timeout,),
            )
            stale = [Job(*r) for r in cur.fetchall()]
        for job in stale:
            _fail_or_retry(conn, job, True, "worker stopped before completing the job", now, rand)
    return len(stale)


def run_forever(dsn: str, data_dir: Path | None, poll_seconds: float = 2.0,
                stale_after: timedelta = timedelta(minutes=15)) -> None:  # pragma: no cover - loop wrapper
    import time

    next_upload_cleanup = 0.0
    while True:
        try:
            with psycopg.connect(dsn, autocommit=True) as conn:
                while True:
                    recover_stale(conn, stale_after, utcnow())
                    if time.monotonic() >= next_upload_cleanup:
                        cleanup_uploads(conn, data_dir)
                        next_upload_cleanup = time.monotonic() + 60
                    if process_one(conn, data_dir) is None:
                        time.sleep(poll_seconds)
        except psycopg.OperationalError:
            log.warning("database unavailable, retrying in 5s")
            time.sleep(5)
