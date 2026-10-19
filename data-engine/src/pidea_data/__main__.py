"""Entry point: ``python -m pidea_data`` runs the worker loop. Configuration comes from the environment."""

import logging
import os
from pathlib import Path

from .worker import run_forever


def main() -> None:
    logging.basicConfig(level=logging.INFO, format='{"ts":"%(asctime)s","level":"%(levelname)s","msg":"%(message)s"}')
    dsn = os.environ["PIDEA_DB_DSN"]  # e.g. postgresql://pidea_worker:***@localhost:5432/pidea
    data_dir = Path(os.environ["PIDEA_DATA_DIR"]) if os.environ.get("PIDEA_DATA_DIR") else None
    run_forever(dsn, data_dir)


if __name__ == "__main__":
    main()
