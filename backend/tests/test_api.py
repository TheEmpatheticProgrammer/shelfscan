import httpx
import pytest
import respx
from fastapi.testclient import TestClient

from app import main
from app.catalog import GOOGLE_BOOKS
from app.models import Book


class FakeLLM:
    async def identify(self, images):
        return [
            Book(title="Dune", author="Frank Herbert", genres=["Science Fiction"], confidence=0.95),
            Book(title="dune", author="Frank Herbert", genres=["Science Fiction"], confidence=0.6),  # dupe
            Book(title="Mystery Book", author="Nobody", genres=["Mystery"], confidence=0.4),
        ], "One spine was blurry"

    async def recommend(self, owned, limit, author=None, genre=None):
        return [
            (Book(title="Dune", author="Frank Herbert"), "already owned"),
            (Book(title="Hyperion", author="Dan Simmons", genres=["Science Fiction"]), "Epic SF like Dune"),
        ]


def gb(title, authors, date="1965-08-01", cats=("Fiction / Science Fiction / General",)):
    return {
        "volumeInfo": {
            "title": title,
            "authors": authors,
            "publishedDate": date,
            "categories": list(cats),
            "industryIdentifiers": [{"type": "ISBN_13", "identifier": "9780441013593"}],
            "imageLinks": {"thumbnail": "http://books.google.com/x?id=1&edge=curl"},
        }
    }


def route_books(request: httpx.Request):
    q = request.url.params["q"]
    if "Dune" in q:
        return httpx.Response(200, json={"items": [gb("Dune", ["Frank Herbert"])]})
    if "Hyperion" in q:
        return httpx.Response(200, json={"items": [gb("Hyperion", ["Dan Simmons"])]})
    if 'inauthor:"Brandon Sanderson"' in q:
        return httpx.Response(200, json={"items": [
            gb("Future Novel", ["Brandon Sanderson"], date="2099-03-01"),
            gb("Old Novel", ["Brandon Sanderson"], date="2010-01-01"),
            gb("Coauthored Future", ["Someone Else"], date="2099-01-01"),
            gb("Year Only Future", ["Brandon Sanderson"], date="2098"),
        ]})
    return httpx.Response(200, json={})


@pytest.fixture
def client():
    main.state.llm = FakeLLM()
    with respx.mock(assert_all_called=False) as mock:
        mock.get(GOOGLE_BOOKS).mock(side_effect=route_books)
        with TestClient(main.app) as c:
            yield c


def test_identify_enriches_and_dedupes(client):
    r = client.post("/identify", files=[("images", ("a.jpg", b"\xff\xd8fake", "image/jpeg"))])
    assert r.status_code == 200, r.text
    body = r.json()
    titles = [b["title"] for b in body["books"]]
    assert titles == ["Dune", "Mystery Book"]
    dune = body["books"][0]
    assert dune["isbn"] == "9780441013593"
    assert dune["genres"] == ["Fiction", "Science Fiction"]
    assert dune["cover_url"].startswith("https://") and "edge=curl" not in dune["cover_url"]
    assert body["books"][1]["genres"] == ["Mystery"]  # unmatched keeps LLM data
    assert body["notes"] == "One spine was blurry"


def test_match_rules():
    from app.catalog import _authors_match, _titles_match
    assert _titles_match("Dune", "Dune")
    assert _titles_match("The Hobbit", "The Hobbit, or There and Back Again")  # subtitle ignored
    assert _titles_match("Sapiens", "Sapiens: A Brief History of Humankind")
    assert _titles_match("Project Hail Mary", "Project Hail Mary.")
    assert not _titles_match("Dune", "Dune Messiah")
    assert not _titles_match("Dune Messiah", "Dune")
    assert _authors_match("Frank Herbert", ["Frank Herbert"])
    assert _authors_match("Terry Pratchett and Neil Gaiman", ["Neil Gaiman", "Terry Pratchett"])
    assert not _authors_match("Frank Herbert", ["Kevin J. Anderson"])


def test_wrong_catalog_hit_does_not_overwrite(client, monkeypatch):
    # Claude read "Dune Messiah"; the catalog only knows "Dune". Keep what Claude read.
    async def one(self, images):
        return [Book(title="Dune Messiah", author="Frank Herbert", genres=["Science Fiction"])], None
    monkeypatch.setattr(FakeLLM, "identify", one)
    r = client.post("/identify", files=[("images", ("a.jpg", b"\xff\xd8", "image/jpeg"))])
    b = r.json()["books"][0]
    assert b["title"] == "Dune Messiah" and b["isbn"] is None


def test_identify_rejects_bad_type(client):
    r = client.post("/identify", files=[("images", ("a.gif", b"GIF", "image/gif"))])
    assert r.status_code == 415


def test_recommendations_filter_owned(client):
    r = client.post("/recommendations", json={"books": [{"title": "Dune", "author": "Frank Herbert"}]})
    recs = r.json()["recommendations"]
    assert [x["book"]["title"] for x in recs] == ["Hyperion"]
    assert recs[0]["reason"] == "Epic SF like Dune"
    assert recs[0]["book"]["cover_url"]


def test_upcoming_only_future_by_author(client):
    r = client.post("/upcoming", json={"authors": ["Brandon Sanderson", "Unknown", ""]})
    titles = [b["title"] for b in r.json()["books"]]
    assert titles == ["Year Only Future", "Future Novel"]


def test_token_required_when_set(client, monkeypatch):
    monkeypatch.setattr(main, "APP_TOKEN", "s3cret")
    assert client.post("/upcoming", json={"authors": []}).status_code == 401
    assert client.post("/upcoming", json={"authors": []}, headers={"X-App-Token": "s3cret"}).status_code == 200
