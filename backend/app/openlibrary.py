"""Open Library lookups: large covers, and a work description when Google Books has none.

Covers are addressed by Open Library's cover id (https://covers.openlibrary.org/b/id/<id>-L.jpg). Unlike
ISBN-addressed covers, id-addressed ones aren't rate limited, and the id comes from a search that also
confirms the title and author match, so a wrong cover is unlikely.
"""
from __future__ import annotations

import asyncio
import logging
import os
from dataclasses import dataclass

import httpx

log = logging.getLogger("shelfscan.openlibrary")

SEARCH_URL = "https://openlibrary.org/search.json"
WORK_URL = "https://openlibrary.org{key}.json"
COVER_URL = "https://covers.openlibrary.org/b/id/{id}-{size}.jpg"
# Open Library asks API clients to identify themselves.
HEADERS = {"User-Agent": "Shelfsight/0.1 (personal book-library app)"}


# The edition Open Library matched within each work (with an ISBN query, that ISBN's edition).
EDITION_FIELDS = "editions,editions.key,editions.title,editions.cover_i,editions.language"
# Open Library lists edition languages as MARC codes.
MARC = {"en": "eng", "es": "spa", "fr": "fre", "de": "ger", "it": "ita", "pt": "por", "nl": "dut", "ca": "cat"}


def _edition_cover(doc: dict, variant: str | None, author: str, lang: str | None) -> int | None:
    """Cover id for this book from a search result: the matched edition's cover, if that edition has the
    title (and language) we're after. Falls back to the work's cover only when the response carries no
    edition at all (older API behaviour), and then only for a title in English or of unknown language."""
    from .catalog import _match

    editions = doc.get("editions")
    if not isinstance(editions, dict):
        return doc.get("cover_i") if lang in (None, "en") else None
    for ed in editions.get("docs") or []:
        if not ed.get("cover_i"):
            continue
        if variant is None:  # ISBN query: this is the scanned edition itself
            return ed["cover_i"]
        if not _match(variant, author, ed.get("title", ""), doc.get("author_name", [])):
            continue  # a translation ("El alquimista" for "The Alchemist")
        langs = ed.get("language") or []
        if lang and langs and MARC.get(lang, lang) not in langs:
            continue  # same title, other language ("Dune" in Spanish)
        return ed["cover_i"]
    return None


def enabled() -> bool:
    return os.getenv("SHELFSCAN_OPEN_LIBRARY", "1") != "0"


@dataclass(frozen=True)
class Work:
    key: str | None  # e.g. "/works/OL893415W"
    cover_id: int | None
    first_publish_year: int | None

    def cover_url(self, size: str = "L") -> str | None:
        return COVER_URL.format(id=self.cover_id, size=size) if self.cover_id else None


class OpenLibrary:
    def __init__(self, client: httpx.AsyncClient, concurrency: int = 4):
        self.client = client
        self._cache: dict[str, Work | None] = {}
        self._limit = asyncio.Semaphore(concurrency)  # be polite: a scan can look up dozens of books

    async def _get(self, url: str, params: dict | None = None) -> dict | None:
        try:
            async with self._limit:
                r = await self.client.get(url, params=params, headers=HEADERS, timeout=10, follow_redirects=True)
            r.raise_for_status()
            return r.json()
        except (httpx.HTTPError, ValueError) as e:
            log.warning("Open Library request failed (%s): %s", url, e)
            return None

    async def find(self, title: str, author: str, isbn: str | None = None, lang: str | None = None) -> Work | None:
        """The matching work, or None. Results (including misses) are cached for the server's lifetime.
        Tries cleaner forms of the title, and searches by title alone when the author is unknown.

        The cover is an *edition's* cover: a work groups every edition and translation of a book, and its own
        cover is often a foreign one with the same title. With an ISBN it's that edition's cover; otherwise the
        best-matching edition whose title matches and whose language is lang (ISO 639-1, e.g. "en") or unknown.
        A work with no such edition comes back without a cover, so callers keep the cover they have."""
        if not enabled():
            return None
        from .catalog import _match, is_unknown, title_variants  # catalog imports us

        cache_key = f"{title.lower()}|{author.lower()}|{isbn or ''}|{lang or ''}"
        if cache_key in self._cache:
            return self._cache[cache_key]
        fields = "key,title,author_name,cover_i,first_publish_year," + EDITION_FIELDS
        queries: list[tuple[str | None, dict]] = []
        if isbn:
            queries.append((None, {"isbn": isbn, "fields": fields, "limit": 1}))
        for variant in title_variants(title):
            params = {"title": variant, "fields": fields, "limit": 5}
            if not is_unknown(author):
                params["author"] = author
            if lang:
                params["lang"] = lang  # asks Open Library to pick editions in this language
            queries.append((variant, params))
        first: Work | None = None  # first matching work, even without a usable cover (for its description)
        found: Work | None = None
        for variant, params in queries:
            data = await self._get(SEARCH_URL, params)
            if data is None:
                return None  # unreachable: don't cache, try again next time
            for doc in data.get("docs", []):
                if variant is not None and not _match(variant, author, doc.get("title", ""), doc.get("author_name", [])):
                    continue
                work = Work(doc.get("key"), _edition_cover(doc, variant, author, lang), doc.get("first_publish_year"))
                first = first or work
                if work.cover_id:
                    found = work
                    break
            if found:
                break
        found = found or first
        self._cache[cache_key] = found
        return found

    async def search(self, query: str, limit: int = 15, lang: str | None = None) -> list[dict]:
        """Free-text search; only results with a title. cover_i is the matched edition's cover."""
        if not enabled():
            return []
        params = {"q": query, "limit": limit, "fields": "title,author_name,cover_i,first_publish_year,isbn," + EDITION_FIELDS}
        if lang:
            params["lang"] = lang
        docs = [d for d in ((await self._get(SEARCH_URL, params)) or {}).get("docs", []) if d.get("title")]
        return [{**d, "cover_i": _edition_cover(d, d["title"], ", ".join(d.get("author_name") or []), lang)} for d in docs]

    async def by_isbn(self, isbn: str) -> dict | None:
        """Raw search result for one ISBN (title, authors, cover id, first year, typical page count)."""
        if not enabled():
            return None
        data = await self._get(SEARCH_URL, {
            "isbn": isbn, "limit": 1,
            "fields": "key,title,author_name,cover_i,first_publish_year,number_of_pages_median," + EDITION_FIELDS,
        })
        docs = (data or {}).get("docs") or []
        if not docs:
            return None
        # The cover of the scanned edition, not the work's (which may be another language's edition).
        return {**docs[0], "cover_i": _edition_cover(docs[0], None, "", None)}

    async def cover_candidates(self, title: str, author: str, isbn: str | None = None) -> list[str]:
        """Cover URLs of every matching work Open Library knows (often several editions' artwork)."""
        if not enabled():
            return []
        from .catalog import _match, is_unknown, title_variants

        urls: list[str] = []
        if isbn and (doc := await self.by_isbn(isbn)) and doc.get("cover_i"):
            urls.append(COVER_URL.format(id=doc["cover_i"], size="L"))
        for variant in title_variants(title)[:2]:
            params = {"title": variant, "fields": "title,author_name,cover_i", "limit": 10}
            if not is_unknown(author):
                params["author"] = author
            for doc in ((await self._get(SEARCH_URL, params)) or {}).get("docs", []):
                if doc.get("cover_i") and _match(variant, author, doc.get("title", ""), doc.get("author_name", [])):
                    urls.append(COVER_URL.format(id=doc["cover_i"], size="L"))
        return list(dict.fromkeys(urls))

    async def description(self, work: Work) -> str | None:
        """The work's description (often a plainer summary than the publisher's copy)."""
        if not work.key:
            return None
        data = await self._get(WORK_URL.format(key=work.key))
        if not data:
            return None
        desc = data.get("description")
        if isinstance(desc, dict):
            desc = desc.get("value")
        return desc.strip() if isinstance(desc, str) and desc.strip() else None
