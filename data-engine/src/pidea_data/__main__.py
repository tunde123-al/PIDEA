"""Entry point: ``python -m pidea_data`` runs the worker loop. Configuration comes from the environment."""

import os
import sys
from datetime import timedelta
from pathlib import Path

from . import logging_setup
from .worker import DEFAULT_RAW_RETENTION, run_forever


def raw_retention_from_env() -> timedelta | None:
    """PIDEA_RAW_RETENTION_DAYS: days to keep submitted raw payloads after a job finishes (default 30; 0 = forever)."""
    value = os.environ.get("PIDEA_RAW_RETENTION_DAYS", "").strip()
    if not value:
        return DEFAULT_RAW_RETENTION
    if not value.isdigit() or int(value) > 3650:
        sys.exit("PIDEA_RAW_RETENTION_DAYS must be a whole number of days between 0 and 3650 (0 keeps payloads forever)")
    return timedelta(days=int(value)) if int(value) > 0 else None


def main() -> None:
    logging_setup.configure()
    dsn = os.environ["PIDEA_DB_DSN"]  # e.g. postgresql://pidea_worker:***@localhost:5432/pidea
    data_dir = Path(os.environ["PIDEA_DATA_DIR"]) if os.environ.get("PIDEA_DATA_DIR") else None
    run_forever(dsn, data_dir, raw_retention=raw_retention_from_env())


if __name__ == "__main__":
    main()
