from decimal import Decimal

import pytest

from pidea_data.transform import MappingError, apply_mapping, transform, validate_mapping

GOOD_CUSTOMER = {"customer_id": "C1", "full_name": "Ada Test", "email": "ADA@Example.com",
                 "created_at": "2024-03-01T10:00:00Z"}


def recs(*items):
    return [(f"seq {i}", r) for i, r in enumerate(items)]


def test_valid_customer_is_normalised():
    r = transform("CUSTOMER", recs(GOOD_CUSTOMER))
    assert r.accepted == 1 and not r.rejects
    c = r.valid[0][1]
    assert c.email == "ada@example.com"
    assert c.created_at.utcoffset().total_seconds() == 0


def test_naive_timestamp_is_treated_as_utc():
    r = transform("CUSTOMER", recs({**GOOD_CUSTOMER, "created_at": "2024-03-01 10:00:00"}))
    assert r.valid[0][1].created_at.isoformat() == "2024-03-01T10:00:00+00:00"


@pytest.mark.parametrize("patch,code", [
    ({"customer_id": None}, "INVALID_IDENTIFIER"),
    ({"customer_id": "bad id!"}, "INVALID_IDENTIFIER"),
    ({"full_name": "  "}, "MISSING_REQUIRED_FIELD"),
    ({"created_at": "2024-13-45"}, "INVALID_DATE"),
    ({"created_at": 12345}, "INVALID_DATE"),
    ({"email": "not-an-email"}, "INVALID_EMAIL"),
])
def test_invalid_customers_are_rejected_with_reason(patch, code):
    r = transform("CUSTOMER", recs({**GOOD_CUSTOMER, **patch}))
    assert r.accepted == 0
    assert r.rejects[0].reason_code == code


def test_missing_required_field():
    bad = {k: v for k, v in GOOD_CUSTOMER.items() if k != "created_at"}
    r = transform("CUSTOMER", recs(bad))
    assert r.rejects[0].reason_code == "MISSING_REQUIRED_FIELD"
    assert "created_at" in r.rejects[0].details


def test_rejection_details_never_contain_values():
    r = transform("CUSTOMER", recs({**GOOD_CUSTOMER, "email": "secret-value-xyz"}))
    assert "secret-value-xyz" not in r.rejects[0].details


@pytest.mark.parametrize("amount,ok", [
    ("10.50", True), (10, True), (Decimal("0"), True), ("-1", False), ("abc", False),
    ("NaN", False), ("Infinity", False), ("1.23456", False), (True, False), (None, False),
])
def test_order_amount_rules(amount, ok):
    order = {"order_id": "O1", "customer_id": "C1", "amount": amount, "currency": "gbp",
             "created_at": "2024-01-01"}
    r = transform("ORDER", recs(order))
    assert (r.accepted == 1) is ok
    if ok:
        assert r.valid[0][1].currency == "GBP"
        assert isinstance(r.valid[0][1].amount, Decimal)


def test_payment_amount_must_be_positive_and_event_type_known():
    base = {"transaction_id": "T1", "amount": "5.00", "currency": "USD", "event_type": "captured",
            "created_at": "2024-01-01T00:00:00Z"}
    assert transform("PAYMENT_EVENT", recs(base)).valid[0][1].event_type == "CAPTURED"
    assert transform("PAYMENT_EVENT", recs({**base, "amount": "0"})).rejects[0].reason_code == "INVALID_AMOUNT"
    assert transform("PAYMENT_EVENT", recs({**base, "event_type": "MAGIC"})).rejects[0].reason_code == "INVALID_EVENT_TYPE"


def test_decimal_precision_is_exact():
    order = {"order_id": "O1", "customer_id": "C1", "amount": "0.1", "currency": "USD", "created_at": "2024-01-01"}
    total = sum((transform("ORDER", recs(order)).valid[0][1].amount for _ in range(3)), Decimal(0))
    assert total == Decimal("0.3")  # would be 0.30000000000000004 with floats


def test_duplicates_first_wins_and_counts_reconcile():
    dup = {**GOOD_CUSTOMER, "full_name": "Second Copy"}
    invalid_dup = {**GOOD_CUSTOMER, "created_at": "nope"}
    r = transform("CUSTOMER", recs(GOOD_CUSTOMER, dup, invalid_dup, "not-a-dict"))
    assert (r.received, r.accepted, r.duplicates, len(r.rejects)) == (4, 1, 1, 2)
    assert r.valid[0][1].full_name == "Ada Test"
    # an invalid record with a duplicate key is REJECTED, not counted as a duplicate
    assert {x.reason_code for x in r.rejects} == {"INVALID_DATE", "NOT_AN_OBJECT"}


def test_mapping_renames_fields():
    mapping = {"client_ref": "customer_id", "registered_on": "created_at"}
    src = {"client_ref": "C9", "full_name": "Grace", "registered_on": "2024-02-02"}
    assert apply_mapping(src, mapping)["customer_id"] == "C9"
    assert transform("CUSTOMER", recs(src), mapping).accepted == 1


def test_mapping_validation():
    validate_mapping("CUSTOMER", {"client_ref": "customer_id"})
    with pytest.raises(MappingError):
        validate_mapping("CUSTOMER", {"a": "customer_id", "b": "customer_id"})
    with pytest.raises(MappingError):
        validate_mapping("CUSTOMER", {"a": "__class__"})
    with pytest.raises(MappingError):
        transform("CUSTOMER", recs(GOOD_CUSTOMER), {"x": "not_a_field"})
