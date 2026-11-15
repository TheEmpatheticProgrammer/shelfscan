"""Adding a book by typing: /search."""
import httpx
import pytest
import respx
from fastapi.testclient import TestClient

from app import main
from app.catalog import GOOGLE_BOOKS
from app.openlibrary import SEARCH_URL


def vol(title, authors, subtitle=None, links=None):
    info = {"title": title, "authors": authors, "publishedDate": "2014"}
    if subtitle:
        info["subtitle"] = subtitle
    if links:
        info["imageLinks"] = links
    return {"volumeInfo": info}


class World:
    google_up = True

    def google(self, request: httpx.Request):
        if not self.google_up:
            return httpx.Response(503)
        if request.url.params["q"] == "station eleven":
            return httpx.Response(200, json={"items": [
                vol("Station Eleven", ["Emily St. John Mandel"], links={"thumbnail": "http://g/se"}),
                vol("Station Eleven", ["Emily St. John Mandel"]),                   # another edition: merged
                vol("Summary of Station Eleven", ["Quick Reads"]),                  # study guide: skipped
                vol("Station Eleven: A Novel", ["Emily St. John Mandel"], subtitle="Study Guide"),  # skipped
                vol("Untitled", []),                                                # no author: skipped
                vol("Sea of Tranquility", ["Emily St. John Mandel"]),
            ]})
        return httpx.Response(200, json={})

    def ol(self, request: httpx.Request):
        if request.url.params.get("q") == "obscure title":
            return httpx.Response(200, json={"docs": [
                {"title": "Obscure Title", "author_name": ["Ann Writer"], "cover_i": 3, "first_publish_year": 1987,
                 "isbn": ["9780000000001"]},
                {"author_name": ["No Title"]}]})
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


def test_search_merges_editions_and_skips_guides(api):
    books = api.post("/search", json={"query": "station eleven"}).json()["books"]
    assert [(b["title"], b["author"]) for b in books] == [
        ("Station Eleven", "Emily St. John Mandel"), ("Sea of Tranquility", "Emily St. John Mandel")]
    assert books[0]["cover_url"] == "https://g/se"


def test_search_falls_back_to_open_library(api, world):
    world.google_up = False
    books = api.post("/search", json={"query": "obscure title"}).json()["books"]
    assert [(b["title"], b["author"], b["isbn"], b["published_date"]) for b in books] == [
        ("Obscure Title", "Ann Writer", "9780000000001", "1987")]
    assert books[0]["cover_url"].endswith("/3-L.jpg")


def test_search_needs_a_real_query(api):
    assert api.post("/search", json={"query": "a"}).status_code == 422
    assert api.post("/search", json={"query": "nothing matches"}).json()["books"] == []
