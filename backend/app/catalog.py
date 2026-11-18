"""Book metadata lookups (Google Books, with Open Library as a cover fallback)."""
from __future__ import annotations

import asyncio
import json
import logging
import os
import random
import re
import time
from datetime import date

import httpx

from .cache import LOOKUP_MISS_TTL_S, LOOKUP_TTL_S, Cache, NullCache, lookup_key
from .identity import SNAP_MIN, snap
from .models import Book
from .openlibrary import SEARCH_URL, OpenLibrary, enabled as open_library_enabled

GOOGLE_BOOKS = "https://www.googleapis.com/books/v1/volumes"
API_KEY = os.getenv("GOOGLE_BOOKS_API_KEY")  # optional; raises quota limits
log = logging.getLogger("shelfscan.catalog")

# Google Books answers 429 when asked too much too fast (and, without an API key, quickly runs out of quota).
GOOGLE_RETRIES = int(os.getenv("GOOGLE_BOOKS_RETRIES", "2"))  # extra attempts after a 429, 5xx or timeout
GOOGLE_COOLDOWN_S = float(os.getenv("GOOGLE_BOOKS_COOLDOWN_S", "60"))  # how long to stop asking after repeated refusals
GOOGLE_TRIP_AFTER = 3  # consecutive failed lookups that start the cooldown
_RETRY_STATUS = {429, 500, 502, 503, 504}
_sleep = asyncio.sleep  # replaced in tests


def _norm(s: str) -> str:
    return re.sub(r"[^a-z0-9]", "", s.lower())


# Books *about* a book, or other formats of it, whose catalog titles start with the book's own title
# ("Dune: The Graphic Novel", "The Great Gatsby (SparkNotes Literature Guide)"). Their covers are wrong.
_DERIVATIVE = re.compile(
    r"\b(summary|study guide|sparknotes|cliffsnotes|cliff'?s notes|literature guide|reader'?s guide|"
    r"teacher'?s guide|analysis|workbook|graphic novel|manga|comic|coloring|colouring|companion|quiz|"
    r"trivia|screenplay|movie tie-?in|the official guide|notes on|abridged|audio ?book|box(ed)? set|"
    r"boxset|collection|omnibus|calendar|journal|resumen|gu[ií]a de estudio|novela gr[aá]fica)\b", re.I)


def _titles_match(want: str, got: str, strict: bool = False) -> bool:
    """Same title, allowing an edition suffix ("Dune" ~ "Dune (Deluxe Edition)"), but not a
    different book that shares a prefix ("Dune" !~ "Dune Messiah"), nor a guide, summary or
    graphic-novel version of it. strict: no prefix allowance (used when there's no author to confirm)."""
    a, b = _norm(want), _norm(got)
    if not a or not b:
        return False
    for m in _DERIVATIVE.finditer(got):
        if _norm(m.group(0)) not in a:
            return False
    # Catalog titles often carry a subtitle the spine doesn't show.
    main_title = _norm(re.split(r"[:(;]|, or ", got, maxsplit=1)[0])
    if a in (b, main_title):
        return True
    if strict:
        return False
    short, long_ = sorted((a, b), key=len)
    return long_.startswith(short) and len(short) / len(long_) >= 0.75


_SPANISH_WORDS = {"el", "la", "los", "las", "del", "y", "un", "una", "que", "por", "para", "con", "sin", "sobre",
                  "entre", "hacia", "cien", "años", "historia", "vida", "amor", "noche", "mundo", "como"}
_ENGLISH_WORDS = {"the", "of", "and", "a", "an", "to", "in", "on", "for", "with", "my", "your", "how", "what"}


def guess_language(title: str) -> str | None:
    """ISO 639-1 code of the language a title is written in, when it's clear: 'es' for Spanish, 'en' for
    English, else None. Used to pick a cover in the same language as the copy on the shelf."""
    words = re.findall(r"[^\W\d_]+", title.lower())
    if not words:
        return None
    if re.search(r"[ñ¿¡]", title.lower()) or sum(w in _SPANISH_WORDS for w in words) > sum(w in _ENGLISH_WORDS for w in words):
        return "es"
    if title.isascii():
        return "en"
    return None


def volume_language(item: dict | None) -> str | None:
    lang = ((item or {}).get("volumeInfo") or {}).get("language")
    return lang.split("-")[0].lower() if isinstance(lang, str) and lang else None


def _match(want_title: str, want_author: str, got_title: str, got_authors: list[str]) -> bool:
    """A catalog record is this book: the title matches and, when the author is known, so does the author.
    Without an author, only the exact title counts."""
    if is_unknown(want_author):
        return _titles_match(want_title, got_title, strict=True)
    return _titles_match(want_title, got_title) and _authors_match(want_author, got_authors)


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


# "The Way of Kings (The Stormlight Archive, #1)"  /  "Mistborn Book 1: The Final Empire"
_SERIES_SUFFIX = re.compile(r"\s*[(\[][^)\]]*?(?:#|\bbook|\bvol\.?|\bvolume|\bpart)\s*\d+[^)\]]*[)\]]\s*$", re.I)
_SERIES_PREFIX = re.compile(r"^[^:]{0,60}?\b(?:book|vol\.?|volume|part|#)\s*\d+\s*[:\-\u2013\u2014]\s*", re.I)


def title_variants(title: str) -> list[str]:
    """The title as read, then cleaner forms a catalog is more likely to know: without a series suffix,
    without a "Series Book 1:" prefix, and the part after a colon. Never the part before a colon:
    'Mistborn: The Well of Ascension' must not turn into 'Mistborn', which is a different book."""
    out: list[str] = []

    def add(t: str) -> None:
        t = t.strip(" -:\u2013\u2014")
        if len(t) >= 2 and t.lower() not in (o.lower() for o in out):
            out.append(t)

    add(title)
    no_suffix = _SERIES_SUFFIX.sub("", title)
    add(no_suffix)
    add(_SERIES_PREFIX.sub("", no_suffix))
    if ":" in no_suffix:
        add(no_suffix.split(":", 1)[1])
    return out


def is_unknown(author: str | None) -> bool:
    return not author or not author.strip() or author.strip().lower() == "unknown"


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


def _nearest(pool: list[dict], book: Book) -> dict | None:
    """The catalog record whose title is nearly the book's as read ("Shadow of Wars" for "Shadow Wars"), by an
    author who agrees and with the same volume number. The record carries `_snap_title`, the title to use."""
    best, best_item = 0.0, None
    for item in pool:
        info = item.get("volumeInfo", {})
        if not _authors_match(book.author, info.get("authors", [])):
            continue
        if _DERIVATIVE.search(info.get("title", "")) and not _DERIVATIVE.search(book.title):
            continue  # a guide or adaptation of the book, not the book
        score, part = snap(book.title, info.get("title", ""))
        if score > best:
            best, best_item = score, {**item, "_snap_title": part}
    if best_item is not None and best >= SNAP_MIN:
        log.info("snapped %r -> %r (similarity %.2f)", book.title, best_item["_snap_title"], best)
        return best_item
    return None


def _open_library_item(doc: dict) -> dict:
    """An Open Library search result in the shape of a Google Books volume."""
    info = {"title": doc["title"], "authors": doc.get("author_name") or []}
    if doc.get("first_publish_year"):
        info["publishedDate"] = str(doc["first_publish_year"])
    isbns = [i for i in doc.get("isbn") or [] if len(i) == 13] or list(doc.get("isbn") or [])[:1]
    if isbns:
        info["industryIdentifiers"] = [{"type": "ISBN_13" if len(isbns[0]) == 13 else "ISBN_10", "identifier": isbns[0]}]
    return {"volumeInfo": info}


def _backoff(response: "httpx.Response | None", attempt: int) -> float:
    """Seconds to wait before retrying: the server's Retry-After (capped), else a short jittered exponential."""
    if response is not None:
        try:
            return min(float(response.headers.get("retry-after", "")), 3.0)
        except ValueError:
            pass
    return 0.4 * (2 ** attempt) + random.uniform(0, 0.2)


class Catalog:
    def __init__(self, client: httpx.AsyncClient):
        self.client = client
        self.failures = 0  # lookups that couldn't reach Google Books; lets callers avoid caching degraded answers
        self.ol = OpenLibrary(client)
        self.cache: Cache = NullCache()  # set by the app: remembers lookup results (Redis, when configured)
        self._google_slots = asyncio.Semaphore(3)  # never more than a few Google requests at once
        self._google_strikes = 0
        self._google_down_until = 0.0

    async def with_best_cover(self, book: Book, lang: str | None = None) -> Book:
        """Swap in Open Library's large cover when it has one for an edition with this title and author, in
        the book's language (lang, else guessed from the title). Otherwise the cover stays as it is."""
        work = await self.ol.find(book.title, book.author, book.isbn, lang=lang or guess_language(book.title))
        if work and work.cover_id:
            book.cover_url = work.cover_url("L")
        return book

    async def best_covers(self, books: list[Book]) -> list[Book]:
        return list(await asyncio.gather(*(self.with_best_cover(b) for b in books)))

    async def _search(self, q: str, **params) -> list[dict] | None:
        """Matching volumes, [] when there are none, or None when the catalog couldn't be reached.

        A 429, a server error or a timeout is retried with a short backoff (honouring Retry-After). After
        several failed lookups in a row Google Books is left alone for a cooldown, so one scan of 50 books
        doesn't fire hundreds of doomed requests; callers then fall back to Open Library."""
        p = {"q": q, "maxResults": params.pop("maxResults", 5), "printType": "books", **params}
        if API_KEY:
            p["key"] = API_KEY
        if time.monotonic() < self._google_down_until:
            self.failures += 1
            return None
        error: Exception | str = "unknown"
        for attempt in range(GOOGLE_RETRIES + 1):
            try:
                async with self._google_slots:
                    r = await self.client.get(GOOGLE_BOOKS, params=p, timeout=10)
                if r.status_code in _RETRY_STATUS and attempt < GOOGLE_RETRIES:
                    await _sleep(_backoff(r, attempt))
                    continue
                r.raise_for_status()
                self._google_strikes = 0
                return r.json().get("items", [])
            except httpx.TransportError as e:  # timeouts and connection problems
                error = e
                if attempt < GOOGLE_RETRIES:
                    await _sleep(_backoff(None, attempt))
                    continue
            except httpx.HTTPStatusError as e:
                error = e
                if e.response.status_code not in _RETRY_STATUS | {403}:  # a bad query, not a refusal: don't count it
                    self.failures += 1
                    log.warning("Google Books lookup failed for %r: %s", q, e)
                    return None
            except ValueError as e:
                error = e
            break
        self.failures += 1
        self._google_strikes += 1
        if self._google_strikes >= GOOGLE_TRIP_AFTER and time.monotonic() >= self._google_down_until:
            self._google_down_until = time.monotonic() + GOOGLE_COOLDOWN_S
            log.warning("Google Books keeps refusing (%s); using Open Library for %.0f s. "
                        "Set GOOGLE_BOOKS_API_KEY to raise the quota.", error, GOOGLE_COOLDOWN_S)
        else:
            log.warning("Google Books lookup failed for %r: %s", q, error)
        return None

    async def lookup(self, book: Book) -> tuple[dict | None, bool]:
        """Find the catalog record for a title + author. Returns (volume or None, catalog_reachable).
        Answers seen before come from the cache; otherwise Google Books is asked, and Open Library when
        Google can't be reached (rate limited, down), so a throttled Google no longer leaves books unchecked."""
        key = lookup_key(book.title, book.author)
        if (raw := await self.cache.get(key)):
            try:
                return json.loads(raw)["item"], True
            except (ValueError, KeyError, TypeError):
                pass  # unreadable entry: look it up again and overwrite it
        item, reachable = await self._lookup_google(book)
        if item is None and not reachable:
            item, reachable = await self._lookup_open_library(book)
        if reachable:  # only definite answers are remembered, never "couldn't reach anyone"
            await self.cache.set(key, json.dumps({"item": item}), LOOKUP_TTL_S if item else LOOKUP_MISS_TTL_S)
        return item, reachable

    async def _lookup_open_library(self, book: Book) -> tuple[dict | None, bool]:
        """The same title+author check against Open Library, with its records shaped like Google's so the
        matching and near-miss correction are shared."""
        if not open_library_enabled():
            return None, False
        unknown = is_unknown(book.author)
        reachable = False
        pool: list[dict] = []
        for title in title_variants(book.title):
            params = {"title": title, "fields": "key,title,author_name,first_publish_year,isbn", "limit": 5}
            if not unknown:
                params["author"] = book.author
            data = await self.ol._get(SEARCH_URL, params)
            if data is None:
                continue
            reachable = True
            items = [_open_library_item(d) for d in data.get("docs", []) if d.get("title")]
            pool += items
            for item in items:
                info = item["volumeInfo"]
                if _match(title, book.author, info["title"], info["authors"]):
                    return item, True
        return (None if unknown else _nearest(pool, book)), reachable

    async def _lookup_google(self, book: Book) -> tuple[dict | None, bool]:
        """Find the catalog record for a title + author. Returns (volume or None, catalog_reachable).
        Tries cleaner forms of the title when the one read off the spine finds nothing, and searches by
        title alone when the author couldn't be read."""
        reachable = False
        unknown = is_unknown(book.author)
        lang = guess_language(book.title)
        pool: list[dict] = []  # everything the catalog returned for this book, for the near-miss pass below
        queries_run: set[str] = set()
        for title in title_variants(book.title):
            queries = [f'intitle:"{title}"'] if unknown else [f'intitle:"{title}" inauthor:"{book.author}"', f"{title} {book.author}"]
            for q in queries:
                queries_run.add(q)
                items = await self._search(q)
                if items is None:
                    continue
                reachable = True
                pool += items
                # Editions in the title's language first, so the cover and details match the copy on the shelf.
                if lang:
                    items = sorted(items, key=lambda it: volume_language(it) not in (None, lang))
                for item in items:
                    info = item.get("volumeInfo", {})
                    if _match(title, book.author, info.get("title", ""), info.get("authors", [])):
                        return item, True
        # No exact match. The model may have misread a word or two ("Shadow of Wars" for "Shadow Wars"), so
        # accept a catalog title that is nearly the same, but only with a readable author that agrees, and
        # with the same volume number. The caller then uses the catalog's title instead of the misread one.
        if not unknown:
            near = f'{book.title} inauthor:"{book.author}"'
            if near not in queries_run:
                items = await self._search(near)
                if items is not None:
                    reachable = True
                    pool += items
            best_item = _nearest(pool, book)
            if best_item is not None:
                return best_item, True
        return None, reachable

    async def search(self, query: str, limit: int = 15) -> list[Book]:
        """Books matching what someone typed (title, author or both), one per title, best match first.
        Google Books first; Open Library when Google is unreachable or finds nothing."""
        items = await self._search(query, maxResults=30, orderBy="relevance") or []
        lang = guess_language(query)
        if lang:  # editions in the language of the query first ("Dune" in English before "Dune" in Spanish)
            items = sorted(items, key=lambda it: volume_language(it) not in (None, lang))
        seen: set[str] = set()
        books: list[Book] = []
        for item in items:
            info = item.get("volumeInfo", {})
            title, authors = info.get("title") or "", info.get("authors") or []
            if not title or not authors or _NOT_A_NOVEL.search(f"{title} {info.get('subtitle', '')}"):
                continue
            key = _edition_key(title) + "|" + _norm(authors[0])
            if key in seen:
                continue
            seen.add(key)
            books.append(_volume_to_book(item))
        if not books:
            for doc in await self.ol.search(query, limit=limit, lang=lang):
                key = _edition_key(doc["title"]) + "|" + _norm((doc.get("author_name") or [""])[0])
                if key in seen:
                    continue
                seen.add(key)
                cover = doc.get("cover_i")
                year = doc.get("first_publish_year")
                books.append(Book(
                    title=doc["title"], author=", ".join(doc.get("author_name") or []) or "Unknown",
                    isbn=(doc.get("isbn") or [None])[0],
                    cover_url=f"https://covers.openlibrary.org/b/id/{cover}-L.jpg" if cover else None,
                    published_date=str(year) if year else None,
                ))
        return books[:limit]

    async def by_isbn(self, isbn: str) -> Book | None:
        """The book with this ISBN (from a barcode): Google Books first, then Open Library."""
        items = await self._search(f"isbn:{isbn}", maxResults=1)
        if items:
            book = _volume_to_book(items[0])
            book.isbn = isbn  # the scanned edition, so the cover is that edition's
            return await self.with_best_cover(book, volume_language(items[0]))
        doc = await self.ol.by_isbn(isbn)
        if not doc or not doc.get("title"):
            return None
        work_cover = doc.get("cover_i")
        year = doc.get("first_publish_year")
        return Book(
            title=doc["title"],
            author=", ".join(doc.get("author_name") or []) or "Unknown",
            isbn=isbn,
            cover_url=f"https://covers.openlibrary.org/b/id/{work_cover}-L.jpg" if work_cover else None,
            published_date=str(year) if year else None,
            page_count=doc.get("number_of_pages_median"),
        )

    async def cover_candidates(self, book: Book, limit: int = 9) -> list[str]:
        """Several plausible covers for one book, for the user to choose from: Open Library first
        (large and clean), then Google Books. Different editions of the same title are fine here."""
        urls: list[str] = []
        urls += await self.ol.cover_candidates(book.title, book.author, book.isbn)
        unknown = is_unknown(book.author)
        for title in title_variants(book.title)[:2]:
            q = f'intitle:"{title}"' if unknown else f'intitle:"{title}" inauthor:"{book.author}"'
            for item in await self._search(q, maxResults=10) or []:
                info = item.get("volumeInfo", {})
                if _match(title, book.author, info.get("title", ""), info.get("authors", [])):
                    if url := _google_cover(info.get("imageLinks") or {}):
                        urls.append(url)
        return list(dict.fromkeys(urls))[:limit]

    async def check(self, book: Book) -> tuple[Book, str]:
        """Match an LLM-identified book to a real catalog record.

        Returns (book, status): "verified" when a record with the same title and author exists (the book is
        then filled in with the record's cover, ISBN and so on), "unverified" when the catalog answered but
        has no such book (likely a misread or invented one; returned as read, without a cover lookup), and
        "unchecked" when the catalog couldn't be reached (returned as read).
        """
        item, reachable = await self.lookup(book)
        if item is None:
            status = "unverified" if reachable else "unchecked"
            log.info("%s: %r by %r", status, book.title, book.author)
            return (book if reachable else await self.with_best_cover(book)), status
        enriched = _volume_to_book(item, fallback=book)
        # Keep the names as read from the photo; the catalog only adds metadata.
        enriched.title, enriched.author = item.get("_snap_title") or book.title, book.author
        log.info("matched %r by %r -> isbn=%s", book.title, book.author, enriched.isbn)
        return await self.with_best_cover(enriched, volume_language(item)), "verified"

    async def check_all(self, books: list[Book], concurrency: int = 6) -> list[tuple[Book, str]]:
        """check() for a whole shelf. Bounded, because 60 books x 2 queries at once gets rate-limited, and a
        rate-limited lookup comes back "unchecked", which is exactly when guardrails matter most."""
        sem = asyncio.Semaphore(concurrency)

        async def one(b: Book):
            async with sem:
                return await self.check(b)

        return list(await asyncio.gather(*(one(b) for b in books)))

    async def enrich(self, book: Book) -> Book:
        """Like check(), but always returns the book (as read when there is no match)."""
        book, status = await self.check(book)
        return await self.with_best_cover(book) if status == "unverified" else book

    async def verify(self, book: Book) -> Book | None:
        """For recommendations: the catalog's record if the book exists, None if the catalog says it
        doesn't (a likely invented title). If the catalog can't be reached, the book is kept as is."""
        item, reachable = await self.lookup(book)
        if item is not None:
            return await self.with_best_cover(_volume_to_book(item, fallback=book), volume_language(item))
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
