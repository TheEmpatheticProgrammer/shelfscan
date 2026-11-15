import pytest

from app.identity import SNAP_MIN, merge_duplicates, same_book, snap
from app.models import Book


def B(title, author="Unknown", **kw):
    return Book(title=title, author=author, **kw)


def test_snap_fixes_a_misread_word():
    score, part = snap("Shadow of Wars", "StarCraft: Shadow Wars")
    assert score >= SNAP_MIN and part == "Shadow Wars"


@pytest.mark.parametrize("read,catalog", [
    ("Dune", "Dune Messiah"),
    ("Dune Messiah", "Dune"),
    ("Harry Potter and the Sorcerer's Stone", "Harry Potter and the Chamber of Secrets"),
    ("Mistborn: The Final Empire", "Mistborn: The Well of Ascension"),
    ("Book 1", "Book 2"),
    ("Halo 2", "Halo 3"),
])
def test_snap_never_turns_one_book_into_another(read, catalog):
    assert snap(read, catalog)[0] < SNAP_MIN


@pytest.mark.parametrize("a,b", [
    (B("Shadow Wars", "Christie Golden"), B("StarCraft: Shadow Wars", "Christie Golden")),
    (B("Shadow Wars", "Unknown"), B("Shadow Wars", "Christie Golden")),
    (B("Shadow Wars", "C. Golden"), B("Shadow Wars", "Christie Golden")),
    (B("The Hobbit", "J.R.R. Tolkien"), B("Hobbit", "Tolkien, J.R.R.")),
    (B("Sapiens", "Yuval Noah Harari"), B("Sapiens: A Brief History of Humankind", "Yuval Noah Harari")),
    (B("DUNE", "Frank Herbert"), B("dune", "frank herbert")),
    (B("Left Behind", "x", isbn="9780"), B("Totally Different", "y", isbn="9780")),
])
def test_same_book(a, b):
    assert same_book(a, b)


@pytest.mark.parametrize("a,b", [
    (B("Dune", "Frank Herbert"), B("Dune Messiah", "Frank Herbert")),
    (B("Halo 2", "Eric Nylund"), B("Halo 3", "Eric Nylund")),
    (B("Shadow Wars", "Christie Golden"), B("Shadow Wars", "Someone Else")),
    (B("Star Wars: Thrawn", "Timothy Zahn"), B("Star Wars: Thrawn Alliances", "Timothy Zahn")),
])
def test_different_books(a, b):
    assert not same_book(a, b)


def test_merge_is_idempotent_and_keeps_the_best_documented_read():
    reads = [
        B("Shadow Wars", "Unknown", genres=["Science Fiction"]),
        B("StarCraft: Shadow Wars", "Christie Golden", isbn="123", cover_url="http://c"),
        B("shadow wars", "C. Golden"),
        B("Dune", "Frank Herbert"),
    ]
    once = merge_duplicates(reads)
    assert [b.title for b in once] == ["StarCraft: Shadow Wars", "Dune"]
    assert once[0].author == "Christie Golden" and once[0].isbn == "123" and once[0].genres == ["Science Fiction"]
    assert merge_duplicates(once) == once
    assert merge_duplicates(once + once) == once
