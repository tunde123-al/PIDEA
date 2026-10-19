from decimal import Decimal

import pytest

from pidea_data import extractors as ex


def test_csv_extraction_with_bom_blank_cells_and_extra_columns(tmp_path):
    f = tmp_path / "c.csv"
    f.write_bytes("﻿customer_id,full_name,email\nC1,Ada,\nC2,Bob,b@x.io,EXTRA\n".encode())
    rows = list(ex.read_csv(f))
    assert rows[0] == ("row 1", {"customer_id": "C1", "full_name": "Ada", "email": None})
    assert rows[1][0] == "row 2" and rows[1][1]["email"] == "b@x.io"


def test_csv_without_header_is_a_file_error(tmp_path):
    f = tmp_path / "empty.csv"
    f.write_text("")
    with pytest.raises(ex.ExtractionError):
        list(ex.read_csv(f))


def test_json_array_and_wrapped_forms_and_exact_decimals(tmp_path):
    f = tmp_path / "a.json"
    f.write_text('[{"amount": 0.1}, {"amount": 12.30}]')
    rows = list(ex.read_json(f))
    assert rows[0] == ("index 0", {"amount": Decimal("0.1")})
    assert rows[1][1]["amount"] == Decimal("12.30")
    g = tmp_path / "b.json"
    g.write_text('{"records": [{"a": 1}]}')
    assert len(list(ex.read_json(g))) == 1


@pytest.mark.parametrize("content", ["{not json", '"string"', '{"records": 5}'])
def test_bad_json_is_a_file_error(tmp_path, content):
    f = tmp_path / "x.json"
    f.write_text(content)
    with pytest.raises(ex.ExtractionError):
        list(ex.read_json(f))


def test_path_traversal_and_missing_files_are_refused(tmp_path):
    (tmp_path / "data").mkdir()
    (tmp_path / "secret.txt").write_text("x")
    base = tmp_path / "data"
    with pytest.raises(ex.ExtractionError):
        ex.resolve_safe(base, "../secret.txt")
    with pytest.raises(ex.ExtractionError):
        ex.resolve_safe(base, "/etc/passwd")
    with pytest.raises(ex.ExtractionError):
        ex.resolve_safe(base, "missing.csv")


def test_oversized_file_is_refused(tmp_path, monkeypatch):
    monkeypatch.setattr(ex, "MAX_FILE_BYTES", 10)
    (tmp_path / "big.csv").write_text("a,b\n" + "1,2\n" * 10)
    with pytest.raises(ex.ExtractionError):
        ex.resolve_safe(tmp_path, "big.csv")
