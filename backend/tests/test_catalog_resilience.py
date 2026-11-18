"""Catalog lookups when Google Books rate-limits: retry, pause, Open Library fallback, and the lookup cache."""
import asyncio
import json

import httpx
import pytest
import respx
from fastapi.testclient import TestClient

from app import catalog as catmod
from app import main
from app.cache import LOOKUP_MISS_TTL_S, LOOKUP_TTL_S, lookup_key
from app.catalog import GOOGLE_BOOKS, Catalog
from app.models import Book
from test_cache import MemoryCache

OL = "https://openlibrary.org/search.json"


def vol(title, authors):
    return {"volumeInfo": {"title": title, "authors": authors, "publishedDate": "2020"}}


def run(make):
    """Run `make(catalog)` (an async callable) against a fresh Catalog; returns (result, catalog)."""
    async def go():
        async with httpx.AsyncClient() as client:
            cat = Catalog(client)
            return await make(cat), cat
    return asyncio.run(go())


@pytest.fixture
def open_library_on(monkeypatch):
    monkeypatch.setenv("SHELFSCAN_OPEN_LIBRARY", "1")


def ol_docs(*docs):
    return httpx.Response(200, json={"docs": list(docs)})


# ---- retry and pause -------------------------------------------------------------------------------
def test_a_429_is_retried_after_the_wait_the_server_asks_for(monkeypatch):
    waits = []

    async def record(s):
        waits.append(s)
    monkeypatch.setattr(catmod, "_sleep", record)
    with respx.mock() as m:
        m.get(GOOGLE_BOOKS).mock(side_effect=[
            httpx.Response(429, headers={"retry-after": "2"}), httpx.Response(200, json={"items": [vol("Dune", ["Frank Herbert"])]}),
        ])
        items, cat = run(lambda c: c._search("dune"))
    assert items and waits == [2.0] and cat.failures == 0


def test_a_long_retry_after_is_capped(monkeypatch):
    waits = []

    async def record(s):
        waits.append(s)
    monkeypatch.setattr(catmod, "_sleep", record)
    with respx.mock() as m:
        m.get(GOOGLE_BOOKS).mock(side_effect=[httpx.Response(429, headers={"retry-after": "600"}), httpx.Response(200, json={})])
        run(lambda c: c._search("x"))
    assert waits == [3.0]


def test_repeated_refusals_pause_google_requests_instead_of_hammering_it():
    with respx.mock() as m:
        route = m.get(GOOGLE_BOOKS).mock(return_value=httpx.Response(429))

        async def go(cat):
            for _ in range(3):
                assert await cat._search("x") is None
            sent = route.call_count
            assert await cat._search("y") is None  # paused: no request is made
            return sent, route.call_count
        (sent, after), cat = run(go)
    assert sent == 9 and after == 9  # 3 lookups x (1 try + 2 retries), then nothing
    assert cat.failures == 4


def test_a_malformed_query_is_not_a_refusal():
    with respx.mock() as m:
        route = m.get(GOOGLE_BOOKS).mock(return_value=httpx.Response(400))

        async def go(cat):
            return [await cat._search("x") for _ in range(5)]
        results, cat = run(go)
    assert results == [None] * 5 and route.call_count == 5  # still asking: a 400 never starts the pause
    assert cat._google_down_until == 0.0


# ---- Open Library fallback --------------------------------------------------------------------------
def test_open_library_verifies_a_book_while_google_is_refusing(open_library_on):
    with respx.mock() as m:
        m.get(GOOGLE_BOOKS).mock(return_value=httpx.Response(429))
        m.get(OL).mock(return_value=ol_docs({"title": "Rebel Witch", "author_name": ["Kristen Ciccarelli"], "first_publish_year": 2024}))
        (item, reachable), _ = run(lambda c: c.lookup(Book(title="Rebel Witch", author="Kristen Ciccarelli")))
    assert reachable and item["volumeInfo"]["title"] == "Rebel Witch"


def test_a_book_neither_catalog_knows_is_unverified_not_unchecked(open_library_on):
    with respx.mock() as m:
        m.get(GOOGLE_BOOKS).mock(return_value=httpx.Response(429))
        m.get(OL).mock(return_value=ol_docs({"title": "Something Else", "author_name": ["Someone"]}))
        (item, reachable), _ = run(lambda c: c.lookup(Book(title="Daisy Hearts", author="Unknown")))
        (status,) = [await_status for await_status in [run(lambda c: c.check(Book(title="Daisy Hearts", author="Unknown")))[0][1]]]
    assert item is None and reachable is True and status == "unverified"


def test_open_library_fallback_also_corrects_a_near_miss(open_library_on):
    with respx.mock() as m:
        m.get(GOOGLE_BOOKS).mock(return_value=httpx.Response(429))
        m.get(OL).mock(return_value=ol_docs({"title": "Shadow Wars", "author_name": ["Christie Golden"]}))
        (book_status, _) = run(lambda c: c.check(Book(title="Shadow of Wars", author="Christie Golden")))
    book, status = book_status
    assert status == "verified" and book.title == "Shadow Wars"


def test_when_both_catalogs_are_down_the_book_is_unchecked(open_library_on):
    with respx.mock() as m:
        m.get(GOOGLE_BOOKS).mock(return_value=httpx.Response(429))
        m.get(OL).mock(return_value=httpx.Response(500))
        (item, reachable), _ = run(lambda c: c.lookup(Book(title="Dune", author="Frank Herbert")))
    assert item is None and reachable is False


# ---- lookup cache -----------------------------------------------------------------------------------
def test_a_found_book_is_looked_up_once():
    cache = MemoryCache()
    with respx.mock() as m:
        route = m.get(GOOGLE_BOOKS).mock(return_value=httpx.Response(200, json={"items": [vol("Dune", ["Frank Herbert"])]}))

        async def go(cat):
            cat.cache = cache
            first = await cat.lookup(Book(title="Dune", author="Frank Herbert"))
            second = await cat.lookup(Book(title="dune", author="FRANK HERBERT"))  # same book, other spelling
            return first, second
        (first, second), _ = run(go)
    assert first == second and route.call_count == 1 and cache.ttls == [LOOKUP_TTL_S]


def test_not_found_is_remembered_but_only_briefly():
    cache = MemoryCache()
    with respx.mock() as m:
        route = m.get(GOOGLE_BOOKS).mock(return_value=httpx.Response(200, json={}))

        async def go(cat):
            cat.cache = cache
            return [await cat.lookup(Book(title="Not A Book", author="Nobody")) for _ in range(2)]
        results, _ = run(go)
    assert results == [(None, True)] * 2 and cache.ttls == [LOOKUP_MISS_TTL_S] and LOOKUP_MISS_TTL_S < LOOKUP_TTL_S
    assert route.call_count == 2 * 3 - 3  # one failed search sequence, then served from the cache


def test_an_unreachable_catalog_is_never_cached():
    cache = MemoryCache()
    with respx.mock() as m:
        m.get(GOOGLE_BOOKS).mock(return_value=httpx.Response(503))

        async def go(cat):
            cat.cache = cache
            return await cat.lookup(Book(title="Dune", author="Frank Herbert"))
        (item, reachable), _ = run(go)
    assert (item, reachable) == (None, False) and cache.sets == 0


def test_a_corrupt_cache_entry_is_looked_up_again():
    cache = MemoryCache()
    cache.data[lookup_key("Dune", "Frank Herbert")] = "not json"
    with respx.mock() as m:
        m.get(GOOGLE_BOOKS).mock(return_value=httpx.Response(200, json={"items": [vol("Dune", ["Frank Herbert"])]}))

        async def go(cat):
            cat.cache = cache
            return await cat.lookup(Book(title="Dune", author="Frank Herbert"))
        (item, reachable), _ = run(go)
    assert reachable and item and json.loads(cache.data[lookup_key("Dune", "Frank Herbert")])["item"]


# ---- end to end: a scan while Google refuses everything -----------------------------------------------
def test_identify_still_verifies_books_when_google_books_is_rate_limiting(open_library_on, monkeypatch):
    class LLM:
        async def identify(self, images):
            return [Book(title="Dune", author="Frank Herbert"), Book(title="Mystery Book", author="Nobody")], None

    def ol(request):
        if "Dune" in request.url.params.get("title", ""):
            return ol_docs({"title": "Dune", "author_name": ["Frank Herbert"], "first_publish_year": 1965})
        return ol_docs()

    main.state.llm = LLM()
    with respx.mock(assert_all_called=False) as m:
        m.get(GOOGLE_BOOKS).mock(return_value=httpx.Response(429))
        m.get(OL).mock(side_effect=ol)
        with TestClient(main.app) as c:
            body = c.post("/identify", files=[("images", ("a.jpg", b"\xff\xd8", "image/jpeg"))]).json()
    assert [b["title"] for b in body["books"]] == ["Dune"]  # verified through Open Library
    assert "Mystery Book" in body["notes"] and "couldn't be checked" not in body["notes"]
