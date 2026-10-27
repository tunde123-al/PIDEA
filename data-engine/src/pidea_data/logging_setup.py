"""One JSON object per log line, always valid JSON, with the job and correlation ids that tie a worker log line to
the API request and the ``ingestion_job`` row that produced it.

Only an allow-list of ``extra`` fields is emitted, so a stray ``extra={"password": ...}`` or payload cannot leak into
the logs by accident. Exceptions are logged as type plus stack trace; messages must already be curated (the worker
never puts record values, credentials or SQL into exception messages).
"""

from __future__ import annotations

import json
import logging
from datetime import UTC, datetime

# Fields a log call may attach with ``extra=``. Everything else is dropped.
ALLOWED_EXTRA = (
    "worker_id", "job_id", "correlation_id", "job_type", "status", "duration_ms", "retry_count",
    "error_category", "attempt", "delay_seconds",
)


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        entry: dict[str, object] = {
            "ts": datetime.fromtimestamp(record.created, UTC).isoformat(timespec="milliseconds"),
            "level": record.levelname,
            "logger": record.name,
            "msg": record.getMessage(),
        }
        for field in ALLOWED_EXTRA:
            value = getattr(record, field, None)
            if value is not None:
                entry[field] = value
        if record.exc_info and record.exc_info[0] is not None:
            entry["exc_type"] = record.exc_info[0].__name__
            entry["exception"] = self.formatException(record.exc_info)
        return json.dumps(entry, default=str)


def configure(level: int = logging.INFO) -> None:
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()
    root.handlers[:] = [handler]
    root.setLevel(level)
