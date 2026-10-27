"""Bounded, read-only PostgreSQL table adapter for POSTGRES_TABLE sources.

Security model (every layer is independent; none relies on the API having validated the config):
  * Deny by default: the source host:port must appear in the worker's ``PIDEA_PG_SOURCE_ALLOWED_HOSTS``.
  * The password is never stored in the database. The source's ``secretRef`` names a worker environment variable,
    which must start with ``PIDEA_SRC_`` so a source config can never make the worker read its own credentials.
  * Identifiers (schema, table, columns, key) are restricted to plain names and always sent as quoted SQL
    identifiers via ``psycopg.sql``; values are bound parameters. Nothing is concatenated into SQL text.
  * The session is read-only (``default_transaction_read_only`` and a READ ONLY transaction) with connect and
    statement timeouts. Grant the source user SELECT on the one table and nothing else.

Extraction model: keyset pagination on a single UNIQUE NOT NULL key column. A checkpoint (the highest key already
loaded) lets repeated jobs resume where the last successful job stopped. Delivery is at-least-once with idempotent
upserts downstream; this is NOT exactly-once. Updates to rows with a key at or below the checkpoint are not seen
(use an ever-increasing key such as a serial id or an insert timestamp).
"""

from __future__ import annotations

import os
import re
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import date, datetime, time
from decimal import Decimal
from typing import Any
from uuid import UUID

import psycopg
from psycopg import sql

from .rest_adapter import PermanentSourceError, TransientSourceError

SECRET_PREFIX = "PIDEA_SRC_"
ALLOWED_HOSTS_VARIABLE = "PIDEA_PG_SOURCE_ALLOWED_HOSTS"

_IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]{0,62}")
_HOST = re.compile(r"[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?")
_SECRET_REF = re.compile(r"PIDEA_SRC_[A-Z0-9_]{1,89}")
_SSL_MODES = {"disable", "prefer", "require", "verify-ca", "verify-full"}
_KEYS = {"host", "port", "database", "user", "sslmode", "schema", "table", "keyColumn", "columns",
         "batchSize", "maxRows", "timeoutSeconds"}
# pg_type.typname of the key column -> SQL type used to cast the checkpoint parameter (fixed whitelist).
_KEY_TYPES = {"int2": "smallint", "int4": "integer", "int8": "bigint", "numeric": "numeric", "date": "date",
              "timestamp": "timestamp", "timestamptz": "timestamptz"}

_KEY_COLUMN_INFO = """
SELECT t.typname, a.attnotnull,
       EXISTS (SELECT 1 FROM pg_index i
               WHERE i.indrelid = c.oid AND i.indisunique AND i.indisvalid AND i.indpred IS NULL
                 AND i.indnatts = 1 AND i.indkey[0] = a.attnum)
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = %s AND NOT a.attisdropped AND a.attnum > 0
JOIN pg_type t ON t.oid = a.atttypid
WHERE n.nspname = %s AND c.relname = %s AND c.relkind IN ('r', 'p')
"""


@dataclass(frozen=True)
class Settings:
    host: str
    port: int
    database: str
    user: str
    sslmode: str
    schema: str
    table: str
    key_column: str
    columns: tuple[str, ...]
    batch_size: int
    max_rows: int
    timeout_seconds: int

    @property
    def cursor_key(self) -> str:
        return f"{self.schema}.{self.table}.{self.key_column}"


@dataclass(frozen=True)
class PullResult:
    records: list[tuple[str, dict[str, Any]]]
    cursor_key: str
    cursor_value: str | None  # new checkpoint, or None when the pull returned no rows


def _int(config: Mapping[str, Any], key: str, default: int, low: int, high: int) -> int:
    value = config.get(key, default)
    if isinstance(value, bool) or not isinstance(value, int) or not low <= value <= high:
        raise PermanentSourceError(f"POSTGRES_TABLE {key} must be an integer between {low} and {high}")
    return value


def parse_settings(config: Mapping[str, Any]) -> Settings:
    """Validate the stored source config. Raises PermanentSourceError with a curated message."""
    unknown = set(config) - _KEYS
    if unknown:
        raise PermanentSourceError("POSTGRES_TABLE config contains unsupported settings")
    for key in ("host", "database", "user", "schema", "table", "keyColumn"):
        if not isinstance(config.get(key), str) or not config[key].strip():
            raise PermanentSourceError(f"POSTGRES_TABLE config is missing {key}")
    host = config["host"].strip().lower().rstrip(".")
    if _HOST.fullmatch(host) is None:
        raise PermanentSourceError("POSTGRES_TABLE host is invalid")
    for key in ("database", "user", "schema", "table", "keyColumn"):
        if _IDENTIFIER.fullmatch(config[key]) is None:
            raise PermanentSourceError(f"POSTGRES_TABLE {key} must be a plain identifier")
    sslmode = config.get("sslmode", "require")
    if sslmode not in _SSL_MODES:
        raise PermanentSourceError("POSTGRES_TABLE sslmode is invalid")
    columns: tuple[str, ...] = ()
    if config.get("columns") is not None:
        if not isinstance(config["columns"], str):
            raise PermanentSourceError("POSTGRES_TABLE columns must be a comma-separated list")
        names = [c.strip() for c in config["columns"].split(",")]
        if not names or len(names) > 50 or any(_IDENTIFIER.fullmatch(n) is None for n in names):
            raise PermanentSourceError("POSTGRES_TABLE columns must be plain identifiers (at most 50)")
        columns = tuple(dict.fromkeys(names))
    return Settings(
        host=host,
        port=_int(config, "port", 5432, 1, 65535),
        database=config["database"], user=config["user"], sslmode=sslmode,
        schema=config["schema"], table=config["table"], key_column=config["keyColumn"], columns=columns,
        batch_size=_int(config, "batchSize", 500, 1, 5000),
        max_rows=_int(config, "maxRows", 10000, 1, 100000),
        timeout_seconds=_int(config, "timeoutSeconds", 30, 1, 120),
    )


def allowed_endpoints(environment: Mapping[str, str]) -> set[tuple[str, int]]:
    """Parse ``host`` or ``host:port`` entries (exact match, no wildcards). Empty means nothing is allowed."""
    allowed: set[tuple[str, int]] = set()
    for entry in environment.get(ALLOWED_HOSTS_VARIABLE, "").split(","):
        entry = entry.strip().lower().rstrip(".")
        if not entry:
            continue
        host, _, port = entry.partition(":")
        if _HOST.fullmatch(host) is None or (port and not port.isdigit()):
            continue  # malformed entries never widen the allow-list
        allowed.add((host, int(port) if port else 5432))
    return allowed


def _resolve_password(secret_ref: str | None, environment: Mapping[str, str]) -> str:
    if not secret_ref or _SECRET_REF.fullmatch(secret_ref) is None:
        raise PermanentSourceError(f"POSTGRES_TABLE secretRef must name an environment variable starting with {SECRET_PREFIX}")
    password = environment.get(secret_ref)
    if not password:
        raise PermanentSourceError("POSTGRES_TABLE credential is not configured in the worker environment")
    return password


# psycopg does not set ``sqlstate`` for failures that happen while connecting, so the server's (stable, libpq)
# message text is the only signal. These all mean "retrying will not help"; anything else is treated as transient.
_REJECTED_CONNECTION_MARKERS = (
    "authentication failed", "no pg_hba.conf entry", "does not exist", "not permitted to log in",
    "password authentication", "permission denied", "ssl is required", "server does not support ssl",
)


def _classify_connect_error(error: psycopg.OperationalError) -> Exception:
    state = getattr(error, "sqlstate", None) or ""
    text = str(error).lower()  # inspected only; never copied into the raised message (may name hosts/users)
    if state.startswith("28") or state == "3D000" or any(m in text for m in _REJECTED_CONNECTION_MARKERS):
        return PermanentSourceError("POSTGRES_TABLE source rejected the connection (credentials, database or TLS)")
    return TransientSourceError("POSTGRES_TABLE source is unreachable")


def connect(settings: Settings, password: str) -> psycopg.Connection:
    """Open a read-only, time-limited session. Overridable in tests."""
    timeout_ms = settings.timeout_seconds * 1000
    try:
        conn = psycopg.connect(
            host=settings.host, port=settings.port, dbname=settings.database, user=settings.user,
            password=password, sslmode=settings.sslmode, connect_timeout=settings.timeout_seconds,
            application_name="pidea-worker",
            options=f"-c default_transaction_read_only=on -c statement_timeout={timeout_ms} "
                    f"-c idle_in_transaction_session_timeout={timeout_ms}",
        )
    except psycopg.OperationalError as e:
        raise _classify_connect_error(e) from None
    conn.read_only = True
    return conn


def _jsonable(column: str, value: Any) -> Any:
    if value is None or isinstance(value, (str, int, float, bool, Decimal)):
        return value
    if isinstance(value, (datetime, date, time)):
        return value.isoformat()
    if isinstance(value, UUID):
        return str(value)
    if isinstance(value, (bytes, bytearray, memoryview)):
        raise PermanentSourceError(f"POSTGRES_TABLE column {column} has an unsupported binary type")
    if isinstance(value, (dict, list)):
        return value
    return str(value)


def _cursor_text(value: Any) -> str:
    return value.isoformat() if isinstance(value, (datetime, date)) else str(value)


def read_records(
    config: Mapping[str, Any],
    secret_ref: str | None,
    cursor_value: str | None,
    *,
    environment: Mapping[str, str] | None = None,
    connector: Any = None,
) -> PullResult:
    """Read the next bounded batch (at most ``maxRows``) of rows with key > ``cursor_value``."""
    environment = os.environ if environment is None else environment
    settings = parse_settings(config)
    if (settings.host, settings.port) not in allowed_endpoints(environment):
        raise PermanentSourceError(
            f"POSTGRES_TABLE host is not in the worker allow-list ({ALLOWED_HOSTS_VARIABLE})")
    password = _resolve_password(secret_ref, environment)
    conn = (connector or connect)(settings, password)
    try:
        return _pull(conn, settings, cursor_value)
    except PermanentSourceError:
        raise
    except psycopg.errors.UndefinedTable:
        raise PermanentSourceError("POSTGRES_TABLE table does not exist or is not visible to the source user") from None
    except psycopg.errors.UndefinedColumn:
        raise PermanentSourceError("POSTGRES_TABLE a configured column does not exist") from None
    except psycopg.errors.InsufficientPrivilege:
        raise PermanentSourceError("POSTGRES_TABLE source user lacks SELECT permission on the table") from None
    except (psycopg.errors.InvalidTextRepresentation, psycopg.errors.DatatypeMismatch,
            psycopg.errors.InvalidDatetimeFormat):
        raise PermanentSourceError("POSTGRES_TABLE stored checkpoint no longer matches the key column type") from None
    except (psycopg.errors.QueryCanceled, psycopg.OperationalError, psycopg.InterfaceError):
        raise TransientSourceError("POSTGRES_TABLE source query was interrupted or timed out") from None
    except psycopg.Error:
        raise PermanentSourceError("POSTGRES_TABLE source query failed") from None
    finally:
        try:
            conn.close()
        except psycopg.Error:
            pass


def _pull(conn: psycopg.Connection, settings: Settings, cursor_value: str | None) -> PullResult:
    with conn.cursor() as cur:
        cur.execute(_KEY_COLUMN_INFO, (settings.key_column, settings.schema, settings.table))
        info = cur.fetchone()
    if info is None:
        raise PermanentSourceError("POSTGRES_TABLE table or keyColumn was not found")
    type_name, not_null, unique = info
    if type_name not in _KEY_TYPES:
        raise PermanentSourceError("POSTGRES_TABLE keyColumn must be an integer, numeric, date or timestamp column")
    if not not_null or not unique:
        raise PermanentSourceError(
            "POSTGRES_TABLE keyColumn must be a single-column UNIQUE NOT NULL column (e.g. the primary key)")

    selected = list(settings.columns) if settings.columns else None
    if selected is not None and settings.key_column not in selected:
        selected.append(settings.key_column)
    select_list = (sql.SQL(", ").join(sql.Identifier(c) for c in selected) if selected else sql.SQL("*"))
    key = sql.Identifier(settings.key_column)
    params: list[Any] = []
    where = sql.SQL("")
    if cursor_value is not None:
        where = sql.SQL(" WHERE {key} > %s::{type}").format(key=key, type=sql.SQL(_KEY_TYPES[type_name]))
        params.append(cursor_value)
    query = sql.SQL("SELECT {cols} FROM {schema}.{table}{where} ORDER BY {key} LIMIT %s").format(
        cols=select_list, schema=sql.Identifier(settings.schema), table=sql.Identifier(settings.table),
        where=where, key=key)
    params.append(settings.max_rows)

    records: list[tuple[str, dict[str, Any]]] = []
    last_key: Any = None
    with conn.cursor(name="pidea_pull") as cur:  # server-side cursor: rows stream in batchSize chunks
        cur.itersize = settings.batch_size
        cur.execute(query, params)
        names: list[str] | None = None
        while True:
            rows = cur.fetchmany(settings.batch_size)
            if not rows:
                break
            if names is None:
                names = [d.name for d in cur.description]
                key_index = names.index(settings.key_column)
            for row in rows:
                records.append((f"row {len(records) + 1}",
                                {n: _jsonable(n, v) for n, v in zip(names, row, strict=True)}))
                last_key = row[key_index]
    return PullResult(records, settings.cursor_key, _cursor_text(last_key) if last_key is not None else None)
