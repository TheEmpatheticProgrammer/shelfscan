"""Focused recommendations: by author (catalog + model) and by genre, with catalog verification."""
import httpx
import pytest
import respx
from fastapi.testclient import TestClient

from app import main
from app.catalog import GOOGLE_BOOKS
from app.models import Book


def vol(title, authors, date="2010-01-01", subtitle=None):
    info = {"title": title, "authors": authors, "publishedDate": date,
            "description": f"About {title}", "imageLinks": {"thumbnail": "http://img/x&edge=curl"}}
    if subtitle:
        info["subtitle"] = subtitle
    return {"volumeInfo": info}


CATALOG = {
    "Hyperion": vol("Hyperion", ["Dan Simmons"]),
    "The Name of the Wind": vol("The Name of the Wind", ["Patrick Rothfuss"]),
    "The Way of Kings": vol("The Way of Kings", ["Brandon Sanderson"]),
    "Gideon the Ninth": vol("Gideon the Ninth", ["Tamsyn Muir"]),
}

SANDERSON = [
    vol("Mistborn: The Final Empire", ["Brandon Sanderson"]),          # owned
    vol("Mistborn: The Well of Ascension", ["Brandon Sanderson"]),     # same series, different book: keep
    vol("Elantris", ["Brandon Sanderson"]),
    vol("Elantris (Tenth Anniversary Edition)", ["Brandon Sanderson"]),  # edition of the above: merge
    vol("Summary of Elantris", ["Brandon Sanderson"]),                 # study guide: skip
    vol("Wind and Truth", ["Brandon Sanderson"], date="2099-12-06"),   # not out yet: skip
    vol("The Wheel of Time Companion", ["Robert Jordan"]),              # not his book: skip
    vol("Warbreaker", ["Brandon Sanderson"]),
]


class Catalog:
    down = False

    def __call__(self, request: httpx.Request):
        if self.down:
            return httpx.Response(503)
        q = request.url.params["q"]
        if q == 'inauthor:"Brandon Sanderson"':
            return httpx.Response(200, json={"items": SANDERSON})
        for title, item in CATALOG.items():
            if title in q:
                return httpx.Response(200, json={"items": [item]})
        return httpx.Response(200, json={})


class FakeLLM:
    def __init__(self, recs=None, fail=False):
        self.recs, self.fail, self.calls = recs or [], fail, []

    async def identify(self, images):
        return [], None

    async def recommend(self, owned, limit, author=None, genre=None):
        self.calls.append({"owned": owned, "limit": limit, "author": author, "genre": genre})
        if self.fail:
            raise httpx.ConnectError("gateway down")
        return [(Book(title=t, author=a), why) for t, a, why in self.recs]


@pytest.fixture
def catalog():
    return Catalog()


@pytest.fixture
def api(catalog):
    def make(llm):
        main.state.llm = llm
        return llm
    with respx.mock(assert_all_called=False) as mock:
        mock.get(GOOGLE_BOOKS).mock(side_effect=catalog)
        with TestClient(main.app) as c:
            c.use = make
            yield c


SHELF = [
    {"title": "Mistborn: The Final Empire", "author": "Brandon Sanderson", "genres": ["Fantasy"]},
    {"title": "Dune", "author": "Frank Herbert", "genres": ["Science Fiction"]},
    {"title": "The Hobbit", "author": "J.R.R. Tolkien", "genres": ["Fantasy"]},
]


def titles(resp, section=None):
    return [r["book"]["title"] for r in resp.json()["recommendations"] if section in (None, r["section"])]


def test_invented_titles_are_dropped(api):
    api.use(FakeLLM([
        ("Hyperion", "Dan Simmons", "Epic SF"),
        ("The Glass Citadel of Vorn", "A. Madeup", "Sounds great"),   # not in catalog
        ("Dune", "Frank Herbert", "owned"),                           # owned
    ]))
    r = api.post("/recommendations", json={"books": SHELF})
    assert titles(r) == ["Hyperion"]
    rec = r.json()["recommendations"][0]
    assert rec["section"] == "similar" and rec["reason"] == "Epic SF"
    assert rec["book"]["description"] == "About Hyperion"   # catalog data attached


def test_unreachable_catalog_keeps_model_picks(api, catalog):
    catalog.down = True
    api.use(FakeLLM([("The Glass Citadel of Vorn", "A. Madeup", "Sounds great")]))
    assert titles(api.post("/recommendations", json={"books": SHELF})) == ["The Glass Citadel of Vorn"]


def test_author_focus_has_both_sections(api):
    llm = api.use(FakeLLM([
        ("The Name of the Wind", "Patrick Rothfuss", "Deep magic system"),
        ("The Way of Kings", "Brandon Sanderson", "same author: belongs in the other section"),
    ]))
    r = api.post("/recommendations", json={"books": SHELF, "author": "Brandon Sanderson"})
    assert titles(r, "by_author") == ["Mistborn: The Well of Ascension", "Elantris", "Warbreaker"]
    assert titles(r, "similar") == ["The Name of the Wind"]
    call = llm.calls[0]
    assert call["author"] == "Brandon Sanderson" and call["genre"] is None
    assert call["owned"][0].author == "Brandon Sanderson"   # focus books lead the prompt


def test_author_focus_survives_model_failure(api):
    api.use(FakeLLM(fail=True))
    r = api.post("/recommendations", json={"books": SHELF, "author": "Brandon Sanderson"})
    assert r.status_code == 200
    assert titles(r, "by_author") and not titles(r, "similar")


def test_model_failure_without_catalog_results_is_502(api):
    api.use(FakeLLM(fail=True))
    assert api.post("/recommendations", json={"books": SHELF}).status_code == 502


def test_genre_focus(api):
    llm = api.use(FakeLLM([("Gideon the Ninth", "Tamsyn Muir", "Dark fantasy with wit")]))
    r = api.post("/recommendations", json={"books": SHELF, "genre": "fantasy"})
    assert titles(r) == ["Gideon the Ninth"]
    call = llm.calls[0]
    assert call["genre"] == "fantasy" and call["author"] is None
    assert {b.title for b in call["owned"][:2]} == {"Mistborn: The Final Empire", "The Hobbit"}


def test_prompt_wording_per_focus():
    from app.llm import recommend_prompt
    from app.models import OwnedBook
    shelf = [OwnedBook(title="Dune", author="Frank Herbert")]
    assert "OTHER authors" in recommend_prompt(shelf, 5, author="Frank Herbert")
    assert "5 Fantasy books" in recommend_prompt(shelf, 5, genre="Fantasy")
    general = recommend_prompt(shelf, 5)
    assert "- Dune by Frank Herbert" in general and "real, published" in general
