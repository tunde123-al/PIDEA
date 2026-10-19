"""Database loading. All statements are parameterised; loads are idempotent upserts keyed by
(source_id, business key), so reprocessing a job can never create duplicate business rows."""

from __future__ import annotations

import uuid
from datetime import datetime
from typing import Any

from .transform import Reject

_UPSERTS = {
    "CUSTOMER": (
        """INSERT INTO analytics.customer
             (id, source_id, customer_id, full_name, email, created_at, job_id, loaded_at)
           VALUES (%s,%s,%s,%s,%s,%s,%s,%s)
           ON CONFLICT (source_id, customer_id) DO UPDATE SET
             full_name=EXCLUDED.full_name, email=EXCLUDED.email, created_at=EXCLUDED.created_at,
             job_id=EXCLUDED.job_id, loaded_at=EXCLUDED.loaded_at""",
        lambda m: (m.customer_id, m.full_name, m.email, m.created_at),
    ),
    "ORDER": (
        """INSERT INTO analytics.customer_order
             (id, source_id, order_id, customer_id, amount, currency, created_at, job_id, loaded_at)
           VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)
           ON CONFLICT (source_id, order_id) DO UPDATE SET
             customer_id=EXCLUDED.customer_id, amount=EXCLUDED.amount, currency=EXCLUDED.currency,
             created_at=EXCLUDED.created_at, job_id=EXCLUDED.job_id, loaded_at=EXCLUDED.loaded_at""",
        lambda m: (m.order_id, m.customer_id, m.amount, m.currency, m.created_at),
    ),
    "PAYMENT_EVENT": (
        """INSERT INTO analytics.payment_event
             (id, source_id, transaction_id, order_id, amount, currency, event_type, created_at, job_id, loaded_at)
           VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
           ON CONFLICT (source_id, transaction_id) DO UPDATE SET
             order_id=EXCLUDED.order_id, amount=EXCLUDED.amount, currency=EXCLUDED.currency,
             event_type=EXCLUDED.event_type, created_at=EXCLUDED.created_at,
             job_id=EXCLUDED.job_id, loaded_at=EXCLUDED.loaded_at""",
        lambda m: (m.transaction_id, m.order_id, m.amount, m.currency, m.event_type, m.created_at),
    ),
}


def load_valid(cur: Any, entity: str, source_id: uuid.UUID, job_id: uuid.UUID,
               valid: list[tuple[str, Any]], now: datetime) -> None:
    sql, project = _UPSERTS[entity]
    rows = [(uuid.uuid4(), source_id, *project(m), job_id, now) for _ref, m in valid]
    if rows:
        cur.executemany(sql, rows)


def record_rejects(cur: Any, entity: str, source_id: uuid.UUID, job_id: uuid.UUID,
                   rejects: list[Reject], now: datetime) -> None:
    # Delete first so a re-run of the same job replaces, rather than duplicates, its quarantine rows.
    cur.execute("DELETE FROM integration.rejected_record WHERE job_id = %s", (job_id,))
    if rejects:
        cur.executemany(
            """INSERT INTO integration.rejected_record
                 (id, job_id, source_id, entity_type, source_record_ref, reason_code, details, processed_at)
               VALUES (%s,%s,%s,%s,%s,%s,%s,%s)""",
            [(uuid.uuid4(), job_id, source_id, entity, r.ref, r.reason_code, r.details, now) for r in rejects],
        )
