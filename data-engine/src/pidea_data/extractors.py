"""Extractors yield ``(source_record_ref, record)`` pairs. They never interpret business rules.

File extractors only read files inside a configured base directory (path traversal is refused) and
enforce a size limit. Row-level problems are left for the validator; file-level problems raise
``ExtractionError`` which fails the job permanently (a corrupt file will not fix itself on retry).
"""

from __future__ import annotations

import csv
import json
from collections.abc import Iterator
from decimal import Decimal
from pathlib import Path
from typing import Any

MAX_FILE_BYTES = 5 * 1024 * 1024
MAX_RECORDS = 100_000


class ExtractionError(Exception):
    """File-level problem (missing, too large, unparseable). Permanent: never retried."""


def resolve_safe(base_dir: Path, file_ref: str) -> Path:
    base = base_dir.resolve()
    target = (base / file_ref).resolve()
    if not target.is_relative_to(base):
        raise ExtractionError("file reference escapes the data directory")
    if not target.is_file():
        raise ExtractionError("file not found")
    if target.stat().st_size > MAX_FILE_BYTES:
        raise ExtractionError("file exceeds the size limit")
    return target


def read_csv(path: Path) -> Iterator[tuple[str, dict[str, Any]]]:
    try:
        with path.open(newline="", encoding="utf-8-sig") as fh:
            reader = csv.DictReader(fh)
            if not reader.fieldnames:
                raise ExtractionError("CSV has no header row")
            for n, row in enumerate(reader, start=1):
                if n > MAX_RECORDS:
                    raise ExtractionError("too many records")
                row.pop(None, None)  # extra cells beyond the header are ignored
                yield f"row {n}", {k: (v if v != "" else None) for k, v in row.items()}
    except (UnicodeDecodeError, csv.Error) as e:
        raise ExtractionError(f"CSV cannot be parsed ({type(e).__name__})") from None


def read_json(path: Path) -> Iterator[tuple[str, Any]]:
    try:
        # parse_float=Decimal keeps money exact (no binary floating point)
        data = json.loads(path.read_text(encoding="utf-8-sig"), parse_float=Decimal)
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise ExtractionError("JSON cannot be parsed") from None
    if isinstance(data, dict) and isinstance(data.get("records"), list):
        data = data["records"]
    if not isinstance(data, list):
        raise ExtractionError("JSON must be an array of objects or {\"records\": [...]}")
    if len(data) > MAX_RECORDS:
        raise ExtractionError("too many records")
    for i, rec in enumerate(data):
        yield f"index {i}", rec
