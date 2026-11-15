"""Cover reliability (title variants, unknown authors, candidates) and barcode ISBN lookup."""
import httpx
import pytest
import respx
from fastapi.testclient import TestClient

from app import main
from app.catalog import GOOGLE_BOOKS, title_variants
from app.openlibrary import SEARCH_URL


def test_title_variants():
    assert title_variants("The Way of Kings (The Stormlight Archive, #1)") == ["The Way of Kings (The Stormlight Archive, #1)", "The Way of Kings"]
    assert title_variants("Mistborn Book 1: The Final Empire") == ["Mistborn Book 1: The Final Empire", "The Final Empire"]
    # Never the part before the colon: that would be a different book in the same series.
    assert "Mistborn" not in title_variants("Mistborn: The Well of Ascension")
    assert title_variants("Dune") == ["Dune"]


def vol(title, authors, links=None, isbn=None):
    info = {"title": title, "authors": authors, "publishedDate": "2010"}
    if links:
        info["imageLinks"] = links
    if isbn:
        info["industryIdentifiers"] = [{"type": "ISBN_13", "identifier": isbn}]
    return {"volumeInfo": info}


class World:
    def __init__(self):
        self.google_queries, self.ol_queries = [], []

    def google(self, request: httpx.Request):
        q = request.url.params["q"]
        self.google_queries.append(q)
        if q == "isbn:9780765326355":
            return httpx.Response(200, json={"items": [vol("The Way of Kings", ["Brandon Sanderson"], isbn="9780765326355")]})
        if q == 'intitle:"The Way of Kings" inauthor:"Brandon Sanderson"':
            return httpx.Response(200, json={"items": [
                vol("The Way of Kings", ["Brandon Sanderson"], {"thumbnail": "http://g/wok1"}),
                vol("The Way of Kings", ["Brandon Sanderson"], {"thumbnail": "http://g/wok2"}),
                vol("Words of Radiance", ["Brandon Sanderson"], {"thumbnail": "http://g/wor"})]})
        if q == 'intitle:"Piranesi"':
            return httpx.Response(200, json={"items": [vol("Piranesi", ["Susanna Clarke"], {"thumbnail": "http://g/pir"})]})
        return httpx.Response(200, json={})

    def ol(self, request: httpx.Request):
        p = dict(request.url.params)
        self.ol_queries.append(p)
        if p.get("isbn") == "9780000000002":
            return httpx.Response(200, json={"docs": [{"title": "Obscure Book", "author_name": ["Ann Writer"], "cover_i": 5,
                                                       "first_publish_year": 1999, "number_of_pages_median": 210}]})
        if p.get("isbn"):
            return httpx.Response(200, json={"docs": []})
        if p.get("title") == "The Way of Kings":
            return httpx.Response(200, json={"docs": [
                {"key": "/works/W1", "title": "The Way of Kings", "author_name": ["Brandon Sanderson"], "cover_i": 101},
                {"key": "/works/W2", "title": "The Way of Kings", "author_name": ["Brandon Sanderson"], "cover_i": 102},
                {"key": "/works/W3", "title": "The Way of Kings", "author_name": ["Someone Else"], "cover_i": 999}]})
        if p.get("title") == "Piranesi" and "author" not in p:
            return httpx.Response(200, json={"docs": [{"key": "/works/P", "title": "Piranesi", "author_name": ["Susanna Clarke"], "cover_i": 7}]})
        return httpx.Response(200, json={"docs": []})


class NoLLM:
    async def identify(self, images):
        return [], None


@pytest.fixture
def world(monkeypatch):
    monkeypatch.setenv("SHELFSCAN_OPEN_LIBRARY", "1")
    return World()


@pytest.fixture
def api(world):
    with respx.mock(assert_all_called=False) as mock:
        mock.get(GOOGLE_BOOKS).mock(side_effect=world.google)
        mock.get(SEARCH_URL).mock(side_effect=world.ol)
        main.state.llm = NoLLM()
        with TestClient(main.app) as c:
            yield c


def test_series_suffix_still_finds_the_cover(api):
    r = api.post("/covers", json={"books": [{"title": "The Way of Kings (The Stormlight Archive, #1)", "author": "Brandon Sanderson"}]})
    assert r.json()["covers"][0]["cover_url"] == "https://covers.openlibrary.org/b/id/101-L.jpg"


def test_unknown_author_searches_by_title_alone(api, world):
    r = api.post("/covers", json={"books": [{"title": "Piranesi", "author": "Unknown"}]})
    assert r.json()["covers"][0]["cover_url"] == "https://covers.openlibrary.org/b/id/7-L.jpg"
    assert all("author" not in q for q in world.ol_queries)


def test_cover_candidates_are_matching_editions_only(api):
    r = api.post("/covers/candidates", json={"title": "The Way of Kings", "author": "Brandon Sanderson"})
    assert r.json()["candidates"] == [
        "https://covers.openlibrary.org/b/id/101-L.jpg", "https://covers.openlibrary.org/b/id/102-L.jpg",
        "https://g/wok1", "https://g/wok2",
    ]   # not Someone Else's book, not "Words of Radiance"


def test_isbn_lookup_google(api):
    b = api.post("/lookup/isbn", json={"isbn": "978-0-7653-2635-5"}).json()["book"]
    assert b["title"] == "The Way of Kings" and b["isbn"] == "9780765326355"
    assert b["cover_url"] == "https://covers.openlibrary.org/b/id/101-L.jpg"


def test_isbn_lookup_falls_back_to_open_library(api):
    b = api.post("/lookup/isbn", json={"isbn": "9780000000002"}).json()["book"]
    assert (b["title"], b["author"], b["page_count"], b["published_date"]) == ("Obscure Book", "Ann Writer", 210, "1999")
    assert b["cover_url"].endswith("/5-L.jpg")


def test_isbn_lookup_unknown_and_invalid(api):
    assert api.post("/lookup/isbn", json={"isbn": "9781111111111"}).json()["book"] is None
    assert api.post("/lookup/isbn", json={"isbn": "12345"}).status_code == 400
