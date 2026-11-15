"""Book metadata lookups (Google Books, with Open Library as a cover fallback)."""
from __future__ import annotations

import asyncio
import logging
import os
import re
from datetime import date

import httpx

from .models import Book
from .openlibrary import OpenLibrary

GOOGLE_BOOKS = "https://www.googleapis.com/books/v1/volumes"
API_KEY = os.getenv("GOOGLE_BOOKS_API_KEY")  # optional; raises quota limits
log = logging.getLogger("shelfscan.catalog")


def _norm(s: str) -> str:
    return re.sub(r"[^a-z0-9]", "", s.lower())


def _titles_match(want: str, got: str) -> bool:
    """Same title, allowing an edition suffix ("Dune" ~ "Dune (Deluxe Edition)"), but not a
    different book that shares a prefix ("Dune" !~ "Dune Messiah")."""
    a, b = _norm(want), _norm(got)
    if not a or not b:
        return False
    # Catalog titles often carry a subtitle the spine doesn't show.
    main_title = _norm(re.split(r"[:(;]|, or ", got, maxsplit=1)[0])
    if a in (b, main_title):
        return True
    short, long_ = sorted((a, b), key=len)
    return long_.startswith(short) and len(short) / len(long_) >= 0.75


def _authors_match(want: str, got: list[str]) -> bool:
    """At least one surname from the photo's author appears in the catalog's authors."""
    if not got:
        return False
    if want.strip().lower() in ("", "unknown"):
        return True
    catalog = " ".join(_norm(g) for g in got)
    surnames = [_norm(name.split()[-1]) for name in re.split(r",|&| and ", want) if name.split()]
    return any(len(s) > 1 and s in catalog for s in surnames)


# Catalog entries that list an author but aren't one of their books.
_NOT_A_NOVEL = re.compile(
    r"\b(summary|study guide|sparknotes|cliffsnotes|analysis of|workbook|box(ed)? set|collection set|"
    r"books? \d+\s*-\s*\d+|calendar|coloring book|quiz)\b", re.I)


def _edition_key(title: str) -> str:
    """Same key for editions of one book: 'Dune (Deluxe Edition)' ~ 'Dune'. Keeps series titles apart:
    'Mistborn: The Final Empire' and 'Mistborn: The Well of Ascension' stay different."""
    return _norm(re.sub(r"\([^)]*\)|\[[^\]]*\]", "", title))


def _parse_date(s: str | None) -> date | None:
    if not s:
        return None
    parts = s.split("-")
    try:
        y = int(parts[0])
        m = int(parts[1]) if len(parts) > 1 else 1
        d = int(parts[2]) if len(parts) > 2 else 1
        return date(y, m, d)
    except (ValueError, IndexError):
        return None


def _google_cover(links: dict) -> str | None:
    """Largest image Google Books offers for a volume (search results usually stop at 'thumbnail')."""
    for size in ("extraLarge", "large", "medium", "small", "thumbnail", "smallThumbnail"):
        if links.get(size):
            return links[size].replace("http://", "https://").replace("&edge=curl", "")
    return None


def _volume_to_book(item: dict, fallback: Book | None = None) -> Book:
    info = item.get("volumeInfo", {})
    isbn = None
    for ident in info.get("industryIdentifiers", []):
        if ident.get("type") == "ISBN_13":
            isbn = ident["identifier"]
            break
        if ident.get("type") == "ISBN_10" and not isbn:
            isbn = ident["identifier"]
    cover = _google_cover(info.get("imageLinks") or {})
    genres: list[str] = []
    for cat in info.get("categories", []):
        for piece in cat.split("/"):
            piece = piece.strip()
            if piece and piece not in genres and piece.lower() != "general":
                genres.append(piece)
    return Book(
        title=info.get("title") or (fallback.title if fallback else ""),
        author=", ".join(info.get("authors", [])) or (fallback.author if fallback else "Unknown"),
        genres=genres or (fallback.genres if fallback else []),
        isbn=isbn,
        # default=false makes Open Library answer 404 (so the app shows its own placeholder) instead of a blank image
        cover_url=cover or (f"https://covers.openlibrary.org/b/isbn/{isbn}-L.jpg?default=false" if isbn else None),
        description=info.get("description"),
        published_date=info.get("publishedDate"),
        page_count=info.get("pageCount"),
        confidence=fallback.confidence if fallback else 1.0,
    )


class Catalog:
    def __init__(self, client: httpx.AsyncClient):
        self.client = client
        self.failures = 0  # lookups that couldn't reach Google Books; lets callers avoid caching degraded answers
        self.ol = OpenLibrary(client)

    async def with_best_cover(self, book: Book) -> Book:
        """Swap in Open Library's large cover when it has one for this exact title and author."""
        work = await self.ol.find(book.title, book.author, book.isbn)
        if work and work.cover_id:
            book.cover_url = work.cover_url("L")
        return book

    async def best_covers(self, books: list[Book]) -> list[Book]:
        return list(await asyncio.gather(*(self.with_best_cover(b) for b in books)))

    async def _search(self, q: str, **params) -> list[dict] | None:
        """Matching volumes, [] when there are none, or None when the catalog couldn't be reached."""
        p = {"q": q, "maxResults": params.pop("maxResults", 5), "printType": "books", **params}
        if API_KEY:
            p["key"] = API_KEY
        try:
            r = await self.client.get(GOOGLE_BOOKS, params=p, timeout=10)
            r.raise_for_status()
            return r.json().get("items", [])
        except (httpx.HTTPError, ValueError) as e:
            self.failures += 1
            log.warning("Google Books lookup failed for %r: %s", q, e)
            return None

    async def lookup(self, book: Book) -> tuple[dict | None, bool]:
        """Find the catalog record for a title + author. Returns (volume or None, catalog_reachable)."""
        reachable = False
        for q in (f'intitle:"{book.title}" inauthor:"{book.author}"', f"{book.title} {book.author}"):
            items = await self._search(q)
            if items is None:
                continue
            reachable = True
            for item in items:
                info = item.get("volumeInfo", {})
                if _titles_match(book.title, info.get("title", "")) and _authors_match(book.author, info.get("authors", [])):
                    return item, True
        return None, reachable

    async def enrich(self, book: Book) -> Book:
        """Match an LLM-identified book to a real catalog record. Keeps LLM data if no match."""
        item, _ = await self.lookup(book)
        if item is None:
            log.info("no catalog match for %r by %r; keeping LLM data", book.title, book.author)
            return await self.with_best_cover(book)
        enriched = _volume_to_book(item, fallback=book)
        # Keep the names as read from the photo; the catalog only adds metadata.
        enriched.title, enriched.author = book.title, book.author
        log.info("matched %r by %r -> isbn=%s", book.title, book.author, enriched.isbn)
        return await self.with_best_cover(enriched)

    async def verify(self, book: Book) -> Book | None:
        """For recommendations: the catalog's record if the book exists, None if the catalog says it
        doesn't (a likely invented title). If the catalog can't be reached, the book is kept as is."""
        item, reachable = await self.lookup(book)
        if item is not None:
            return await self.with_best_cover(_volume_to_book(item, fallback=book))
        if not reachable:
            return book
        log.info("dropping recommendation not found in catalog: %r by %r", book.title, book.author)
        return None

    async def verify_all(self, books: list[Book]) -> list[Book | None]:
        return list(await asyncio.gather(*(self.verify(b) for b in books)))

    async def books_by_author(self, author: str, owned_titles: list[str], limit: int = 8,
                              today: date | None = None) -> list[Book]:
        """Already-published books by this author, one per title (editions merged), skipping titles
        the user owns and study guides or box sets that list the author."""
        today = today or date.today()
        items = await self._search(f'inauthor:"{author}"', maxResults=40, orderBy="relevance") or []
        want = _norm(author)
        seen: set[str] = set()
        out: list[Book] = []
        for item in items:
            info = item.get("volumeInfo", {})
            title = info.get("title") or ""
            key = _edition_key(title)
            pub = _parse_date(info.get("publishedDate"))
            if (
                not key or key in seen
                or any(_titles_match(t, title) or _titles_match(title, t) for t in owned_titles)
                or want not in [_norm(a) for a in info.get("authors", [])]
                or (pub and pub > today)
                or _NOT_A_NOVEL.search(f"{title} {info.get('subtitle', '')}")
            ):
                continue
            seen.add(key)
            out.append(_volume_to_book(item))
            if len(out) == limit:
                break
        return await self.best_covers(out)

    async def enrich_all(self, books: list[Book]) -> list[Book]:
        return list(await asyncio.gather(*(self.enrich(b) for b in books)))

    async def upcoming_for_author(self, author: str, today: date | None = None) -> list[Book]:
        today = today or date.today()
        items = await self._search(f'inauthor:"{author}"', orderBy="newest", maxResults=20) or []
        out: list[Book] = []
        for item in items:
            info = item.get("volumeInfo", {})
            pub = _parse_date(info.get("publishedDate"))
            authors = [_norm(a) for a in info.get("authors", [])]
            if pub and pub > today and _norm(author) in authors:
                out.append(_volume_to_book(item))
        return out

    async def upcoming(self, authors: list[str]) -> list[Book]:
        results = await asyncio.gather(*(self.upcoming_for_author(a) for a in authors))
        seen: set[str] = set()
        books: list[Book] = []
        for group in results:
            for b in group:
                key = _norm(b.title) + _norm(b.author)
                if key not in seen:
                    seen.add(key)
                    books.append(b)
        books.sort(key=lambda b: b.published_date or "")
        return await self.best_covers(books)
