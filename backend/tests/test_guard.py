import pytest

from app.guard import suspicious_reason


@pytest.mark.parametrize("title,author", [
    ("Dune", "Frank Herbert"), ("Dune", "Unknown"), ("Emma", "Emma Donoghue"), ("Room", "Emma Donoghue"),
    ("Inferno", "Dan Brown"), ("The Brown Fairy Book", "Andrew Lang"), ("Good Omens", "Terry Pratchett and Neil Gaiman"),
])
def test_ordinary_reads_pass(title, author):
    assert suspicious_reason(title, author) is None


@pytest.mark.parametrize("title,author,why", [
    ("Brown", "Dan Brown", "surname"),
    ("Dan Brown", "Dan Brown", "name"),
    ("Gaiman", "Terry Pratchett and Neil Gaiman", "surname"),
    ("", "Dan Brown", "no title"),
    ("  ", "Unknown", "no title"),
    ("Unknown", "Unknown", "placeholder"),
    ("Untitled", "Dan Brown", "placeholder"),
])
def test_obvious_misreads_are_rejected(title, author, why):
    assert why in (suspicious_reason(title, author) or "")
