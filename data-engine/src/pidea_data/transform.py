"""Pure (database-free) transformation: map -> validate -> deduplicate.

Count semantics (every received record lands in exactly ONE bucket):
  1. invalid          -> rejected   (validation runs first, so an invalid duplicate is *rejected*)
  2. valid, key seen earlier in this job -> duplicate (first occurrence wins)
  3. valid, first occurrence of its key  -> accepted (loaded with upsert, so re-runs never create rows twice)
  received == accepted + rejected + duplicate, always.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass, field
from typing import Any

from pydantic import ValidationError

from .models import ENTITY_KEYS, ENTITY_MODELS, reason_for


class MappingError(ValueError):
    """The mapping definition itself is invalid (a configuration problem, not a data problem)."""


def validate_mapping(entity: str, mapping: dict[str, str]) -> None:
    """Mappings are declarative renames {source_field: target_field}; nothing is ever evaluated."""
    allowed = set(ENTITY_MODELS[entity].model_fields)
    targets = list(mapping.values())
    if len(set(targets)) != len(targets):
        raise MappingError("two source fields map to the same target field")
    for src, dst in mapping.items():
        if not isinstance(src, str) or not isinstance(dst, str) or not src:
            raise MappingError("mapping keys and values must be non-empty strings")
        if dst not in allowed:
            raise MappingError(f"unknown target field for {entity}: {dst}")


def apply_mapping(record: dict[str, Any], mapping: dict[str, str] | None) -> dict[str, Any]:
    if not mapping:
        return record
    out = {k: v for k, v in record.items() if k not in mapping}
    for src, dst in mapping.items():
        if src in record:
            out[dst] = record[src]
    return out


@dataclass(frozen=True)
class Reject:
    ref: str
    reason_code: str
    details: str


@dataclass
class TransformResult:
    received: int = 0
    valid: list[tuple[str, Any]] = field(default_factory=list)   # (ref, validated model)
    rejects: list[Reject] = field(default_factory=list)
    duplicates: int = 0

    @property
    def accepted(self) -> int:
        return len(self.valid)

    def check_invariant(self) -> None:
        assert self.received == self.accepted + len(self.rejects) + self.duplicates


def transform(entity: str, records: Iterable[tuple[str, Any]],
              mapping: dict[str, str] | None = None) -> TransformResult:
    model = ENTITY_MODELS[entity]
    key_field = ENTITY_KEYS[entity]
    if mapping:
        validate_mapping(entity, mapping)
    result = TransformResult()
    seen: set[str] = set()
    for ref, raw in records:
        result.received += 1
        if not isinstance(raw, dict):
            result.rejects.append(Reject(ref, "NOT_AN_OBJECT", "record is not a JSON object"))
            continue
        try:
            obj = model.model_validate(apply_mapping(raw, mapping))
        except ValidationError as e:
            code, details = reason_for(e)
            result.rejects.append(Reject(ref, code, details))
            continue
        key = getattr(obj, key_field)
        if key in seen:
            result.duplicates += 1
            continue
        seen.add(key)
        result.valid.append((ref, obj))
    result.check_invariant()
    return result
