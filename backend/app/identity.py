"""What makes two reads the same book, and how close a read is to a catalog title.

Used for two jobs: snapping a slightly misread title onto the catalog's real one ("Shadow of Wars" ->
"Shadow Wars"), and merging duplicates so one book appears once however many photos, spines or spellings
it was read from.
"""
from __future__ import annotations

import re
import unicodedata
from difflib import SequenceMatcher

from .models import Book

SNAP_MIN = 0.86  # similarity needed to treat a near-miss as the same book
_ARTICLES = ("the ", "a ", "an ")


def norm(s: str) -> str:
    s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode().lower().replace("&", " and ")
    s = s.replace("'", "").replace("’", "")
    return " ".join(re.sub(r"[^a-z0-9]+", " ", s).split())


def _strip_article(n: str) -> str:
    for a in _ARTICLES:
        if n.startswith(a) and len(n) > len(a) + 2:
            return n[len(a):]
    return n


def _digits(n: str) -> list[str]:
    return sorted(re.findall(r"\d+", n))


def title_parts(title: str) -> dict[str, str]:
    """Raw text of each form a title can take on a spine or in a catalog, keyed by normalized form."""
    raw = re.sub(r"\([^)]*\)|\[[^\]]*\]", " ", title)
    forms = [raw]
    if ":" in raw:
        before, after = raw.split(":", 1)
        forms += [before, after]
    out: dict[str, str] = {}
    for f in forms:
        n = _strip_article(norm(f))
        if len(n) >= 2:
            out.setdefault(n, f.strip(" -:–—"))
    return out


def snap(read: str, catalog_title: str) -> tuple[float, str]:
    """How close the title as read is to a catalog title, and which part of the catalog title it matches.
    Numbers must agree exactly: 'Book 2' is never 'Book 1'."""
    want = _strip_article(norm(read))
    if not want:
        return 0.0, catalog_title
    best, part = 0.0, catalog_title
    for n, text in title_parts(catalog_title).items():
        if _digits(want) != _digits(n):
            continue
        score = SequenceMatcher(None, want, n).ratio()
        if score > best:
            best, part = score, text
    return best, part


def _author_tokens(author: str) -> set[str]:
    return {w for w in norm(author).split() if len(w) > 2 and w != "unknown"}


def authors_compatible(a: str, b: str) -> bool:
    ta, tb = _author_tokens(a), _author_tokens(b)
    if not ta or not tb:  # unknown (or initials only) on one side: can't tell them apart
        return True
    return bool(ta & tb)


def same_book(a: Book, b: Book) -> bool:
    if a.isbn and a.isbn == b.isbn:
        return True
    if not authors_compatible(a.author, b.author):
        return False
    pa, pb = title_parts(a.title), title_parts(b.title)
    fa, fb = next(iter(pa), ""), next(iter(pb), "")
    if not fa or not fb:
        return False
    if _digits(fa) != _digits(fb):
        return False
    if fa == fb:
        return True
    # one title is the subtitle of the other ("StarCraft: Shadow Wars" ~ "Shadow Wars"), or the other's
    # part before the colon ("Sapiens" ~ "Sapiens: A Brief History")
    if fa in pb or fb in pa:
        return True
    return SequenceMatcher(None, fa, fb).ratio() >= 0.93


def _rank(b: Book) -> tuple:
    known = bool(b.author) and b.author.strip().lower() != "unknown"
    return (bool(b.isbn), bool(b.cover_url), known, b.confidence)


def merge_duplicates(books: list[Book]) -> list[Book]:
    """One entry per real book, keeping the best-documented read and filling gaps from the others.
    Order of first appearance is kept."""
    groups: list[list[Book]] = []
    for b in books:
        for g in groups:
            if any(same_book(b, other) for other in g):
                g.append(b)
                break
        else:
            groups.append([b])
    out = []
    for g in groups:
        best = max(g, key=_rank)
        merged = best.model_copy(deep=True)
        for other in g:
            if other is best:
                continue
            for field in ("isbn", "cover_url", "description", "published_date", "page_count"):
                if not getattr(merged, field) and getattr(other, field):
                    setattr(merged, field, getattr(other, field))
            if (not merged.author or merged.author.strip().lower() == "unknown") and other.author:
                merged.author = other.author
            merged.genres = list(dict.fromkeys([*merged.genres, *other.genres]))[:3]
        out.append(merged)
    return out
