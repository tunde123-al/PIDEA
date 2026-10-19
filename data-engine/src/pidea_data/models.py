"""Validation models for the three business entities.

Pydantic validates and normalises one record. Rules:
* ids are trimmed strings of [A-Za-z0-9_-], 1-64 chars (ints are accepted and stringified);
* money is ``Decimal`` (never float), at most 4 decimal places, finite;
* timestamps are ISO-8601, naive values are treated as UTC, all output is UTC;
* currency is a 3-letter code, upper-cased.

Failures never include the offending *value*, only the field name and a reason code, so quarantine
rows cannot leak sensitive data.
"""

from __future__ import annotations

import re
from datetime import UTC, datetime
from decimal import Decimal, InvalidOperation
from typing import Any

from pydantic import BaseModel, ConfigDict, ValidationError, field_validator

ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
EMAIL_RE = re.compile(r"^[^@\s]{1,64}@[^@\s]{1,189}\.[^@\s]{2,}$")
CURRENCY_RE = re.compile(r"^[A-Z]{3}$")
EVENT_TYPES = {"AUTHORISED", "CAPTURED", "REFUNDED", "FAILED"}


def _id(v: Any) -> str:
    if isinstance(v, bool) or not isinstance(v, (str, int)):
        raise ValueError("INVALID_IDENTIFIER")
    s = str(v).strip()
    if not s:
        raise ValueError("MISSING_REQUIRED_FIELD")
    if not ID_RE.match(s):
        raise ValueError("INVALID_IDENTIFIER")
    return s


def _timestamp(v: Any) -> datetime:
    if isinstance(v, datetime):
        dt = v
    elif isinstance(v, str):
        try:
            dt = datetime.fromisoformat(v.strip().replace("Z", "+00:00"))
        except ValueError:
            raise ValueError("INVALID_DATE") from None
    else:
        raise ValueError("INVALID_DATE")
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=UTC)
    return dt.astimezone(UTC)


def _amount(v: Any) -> Decimal:
    if v is None or isinstance(v, bool):
        raise ValueError("INVALID_AMOUNT")
    try:
        d = Decimal(str(v).strip())
    except InvalidOperation:
        raise ValueError("INVALID_AMOUNT") from None
    if not d.is_finite() or d.as_tuple().exponent < -4:
        raise ValueError("INVALID_AMOUNT")
    return d


def _currency(v: Any) -> str:
    s = str(v).strip().upper() if isinstance(v, str) else ""
    if not CURRENCY_RE.match(s):
        raise ValueError("INVALID_CURRENCY")
    return s


class _Base(BaseModel):
    model_config = ConfigDict(extra="ignore")


class Customer(_Base):
    customer_id: str
    full_name: str
    email: str | None = None
    created_at: datetime

    _v_id = field_validator("customer_id", mode="before")(_id)
    _v_ts = field_validator("created_at", mode="before")(_timestamp)

    @field_validator("full_name", mode="before")
    @classmethod
    def _name(cls, v: Any) -> str:
        if not isinstance(v, str) or not v.strip():
            raise ValueError("MISSING_REQUIRED_FIELD")
        if len(v.strip()) > 200:
            raise ValueError("INVALID_LENGTH")
        return v.strip()

    @field_validator("email", mode="before")
    @classmethod
    def _email(cls, v: Any) -> str | None:
        if v is None or (isinstance(v, str) and not v.strip()):
            return None
        if not isinstance(v, str) or not EMAIL_RE.match(v.strip()) or len(v.strip()) > 254:
            raise ValueError("INVALID_EMAIL")
        return v.strip().lower()


class Order(_Base):
    order_id: str
    customer_id: str
    amount: Decimal
    currency: str
    created_at: datetime

    _v_oid = field_validator("order_id", "customer_id", mode="before")(_id)
    _v_ts = field_validator("created_at", mode="before")(_timestamp)
    _v_cur = field_validator("currency", mode="before")(_currency)

    @field_validator("amount", mode="before")
    @classmethod
    def _amt(cls, v: Any) -> Decimal:
        d = _amount(v)
        if d < 0:
            raise ValueError("INVALID_AMOUNT")
        return d


class PaymentEvent(_Base):
    transaction_id: str
    order_id: str | None = None
    amount: Decimal
    currency: str
    event_type: str
    created_at: datetime

    _v_tid = field_validator("transaction_id", mode="before")(_id)
    _v_ts = field_validator("created_at", mode="before")(_timestamp)
    _v_cur = field_validator("currency", mode="before")(_currency)

    @field_validator("order_id", mode="before")
    @classmethod
    def _oid(cls, v: Any) -> str | None:
        return None if v is None or v == "" else _id(v)

    @field_validator("amount", mode="before")
    @classmethod
    def _amt(cls, v: Any) -> Decimal:
        d = _amount(v)
        if d <= 0:
            raise ValueError("INVALID_AMOUNT")
        return d

    @field_validator("event_type", mode="before")
    @classmethod
    def _evt(cls, v: Any) -> str:
        s = v.strip().upper() if isinstance(v, str) else ""
        if s not in EVENT_TYPES:
            raise ValueError("INVALID_EVENT_TYPE")
        return s


ENTITY_MODELS: dict[str, type[_Base]] = {
    "CUSTOMER": Customer,
    "ORDER": Order,
    "PAYMENT_EVENT": PaymentEvent,
}

# Business key field per entity: (source_id, key) is unique in the target table.
ENTITY_KEYS = {"CUSTOMER": "customer_id", "ORDER": "order_id", "PAYMENT_EVENT": "transaction_id"}


def reason_for(err: ValidationError) -> tuple[str, str]:
    """Return (reason_code, safe_details) for a failed validation. Never includes values."""
    errors = err.errors()
    first = errors[0]
    if first["type"] == "missing":
        code = "MISSING_REQUIRED_FIELD"
    elif first["type"] == "value_error":
        code = str(first["ctx"]["error"])
    else:
        code = "INVALID_VALUE"
    fields = ",".join(dict.fromkeys(str(e["loc"][0]) if e["loc"] else "?" for e in errors))
    return code, f"fields: {fields}"
