"""Cheap sanity checks on what the model says it read, before the catalog is asked.

The catalog (Google Books) is the real gate: a book is only kept when a record with the same title and
author exists. These rules catch reads that are wrong on their face, so they never cost a lookup and can
never be rescued by a fuzzy catalog hit. Typical case: a spine shows "DAN BROWN" and the model reports a
book titled "Brown".
"""
from __future__ import annotations

import re

_PLACEHOLDERS = {"unknown", "untitled", "title", "book", "none", "n/a", "na", "null", "unreadable", "illegible"}


def _words(s: str) -> list[str]:
    return [w for w in re.split(r"[^a-z0-9]+", s.lower()) if w]


def _author_names(author: str) -> list[list[str]]:
    return [w for w in (_words(a) for a in re.split(r",|&|;| and ", author)) if w]


def suspicious_reason(title: str, author: str) -> str | None:
    """Why this read can't be a real book entry, or None when nothing is obviously wrong."""
    words = _words(title)
    if not words:
        return "no title"
    if " ".join(words) in _PLACEHOLDERS:
        return "placeholder title"
    for name in _author_names(author):
        if words == name:
            return "title is the author's name"
        if len(words) == 1 and len(name) > 1 and words[0] == name[-1] and len(words[0]) >= 3:
            return "title is the author's surname"
    return None
