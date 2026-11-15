"""Covers (Open Library first), the book page's synopsis, and 'similar to this book'."""
import httpx
import pytest
import respx
from fastapi.testclient import TestClient

from app import main
from app.catalog import GOOGLE_BOOKS, _google_cover
from app.models import Book
from app.openlibrary import SEARCH_URL

LONG = (
    "<p>On the desert planet Arrakis, young Paul Atreides is thrust into a deadly struggle when his family takes "
    "control of the only source of the most valuable substance in the universe.</p><p>Betrayed, he flees into the "
    "deep desert, where the Fremen may hold the key to his destiny. A stunning blend of adventure and mysticism. "
    "&quot;The greatest science fiction novel ever written&quot; says everyone. Over 20 million copies sold.</p>"
)


def gb(title, authors, description=None, links=None):
    info = {"title": title, "authors": authors, "publishedDate": "1965-08-01", "pageCount": 412,
            "industryIdentifiers": [{"type": "ISBN_13", "identifier": "9780441013593"}]}
    if description:
        info["description"] = description
    if links:
        info["imageLinks"] = links
    return {"volumeInfo": info}


class World:
    """Google Books + Open Library, as small as the tests need."""
    def __init__(self):
        self.ol_up = True
        self.ol_calls = 0

    def google(self, request: httpx.Request):
        q = request.url.params["q"]
        if "Dune" in q:
            return httpx.Response(200, json={"items": [gb("Dune", ["Frank Herbert"], LONG,
                                                          {"thumbnail": "http://books.google.com/t&edge=curl"})]})
        if "Short Book" in q:
            return httpx.Response(200, json={"items": [gb("Short Book", ["Ann Writer"], "A short, plain description.")]})
        if "Bare Book" in q:
            return httpx.Response(200, json={"items": [gb("Bare Book", ["Ann Writer"])]})
        if "Hyperion" in q:
            return httpx.Response(200, json={"items": [gb("Hyperion", ["Dan Simmons"])]})
        return httpx.Response(200, json={})

    def ol_search(self, request: httpx.Request):
        self.ol_calls += 1
        if not self.ol_up:
            return httpx.Response(503)
        p = request.url.params
        title = p.get("title", "")
        if title == "Dune":
            return httpx.Response(200, json={"docs": [
                {"key": "/works/OL1W", "title": "Dune Messiah", "author_name": ["Frank Herbert"], "cover_i": 999},
                {"key": "/works/OL2W", "title": "Dune", "author_name": ["Frank Herbert"], "cover_i": 12345}]})
        if title == "Bare Book":
            return httpx.Response(200, json={"docs": [
                {"key": "/works/OL9W", "title": "Bare Book", "author_name": ["Ann Writer"]}]})
        if title == "Hyperion":
            return httpx.Response(200, json={"docs": [
                {"key": "/works/OL3W", "title": "Hyperion", "author_name": ["Dan Simmons"], "cover_i": 777}]})
        return httpx.Response(200, json={"docs": []})


class FakeLLM:
    def __init__(self, synopsis="Paul Atreides must survive on a desert world after his family is betrayed.",
                 fail=False, recs=()):
        self.synopsis, self.fail, self.recs = synopsis, fail, list(recs)
        self.summarized, self.rec_calls = [], []

    async def identify(self, images):
        return [], None

    async def summarize(self, title, author, source):
        self.summarized.append(source)
        if self.fail:
            raise httpx.ReadTimeout("slow model")
        return self.synopsis

    async def recommend(self, owned, limit, author=None, genre=None, similar_to=None):
        self.rec_calls.append({"similar_to": similar_to, "owned": owned})
        return [(Book(title=t, author=a), why) for t, a, why in self.recs]


@pytest.fixture
def world(monkeypatch):
    monkeypatch.setenv("SHELFSCAN_OPEN_LIBRARY", "1")
    return World()


@pytest.fixture
def api(world):
    with respx.mock(assert_all_called=False) as mock:
        mock.get(GOOGLE_BOOKS).mock(side_effect=world.google)
        mock.get(SEARCH_URL).mock(side_effect=world.ol_search)
        mock.get("https://openlibrary.org/works/OL9W.json").mock(
            return_value=httpx.Response(200, json={"description": {"type": "/type/text", "value": "Plain OL text."}}))
        with TestClient(main.app) as c:
            def use(llm):
                main.state.llm = llm
                return llm
            c.use = use
            yield c


def test_google_cover_prefers_the_largest():
    assert _google_cover({"thumbnail": "http://a/t&edge=curl", "medium": "http://a/m"}) == "https://a/m"
    assert _google_cover({"thumbnail": "http://a/t&edge=curl"}) == "https://a/t"
    assert _google_cover({}) is None


def test_covers_use_open_library_for_the_exact_title(api):
    api.use(FakeLLM())
    r = api.post("/covers", json={"books": [
        {"title": "Dune", "author": "Frank Herbert"},          # OL match (not "Dune Messiah")
        {"title": "Bare Book", "author": "Ann Writer"},         # OL has no cover, Google has none either
        {"title": "Nothing Known", "author": "Nobody"},
    ]})
    covers = r.json()["covers"]
    assert covers[0]["cover_url"] == "https://covers.openlibrary.org/b/id/12345-L.jpg"
    assert covers[1]["cover_url"] == "https://covers.openlibrary.org/b/isbn/9780441013593-L.jpg?default=false"
    assert covers[2]["cover_url"] is None


def test_open_library_outage_falls_back_to_google(api, world):
    world.ol_up = False
    api.use(FakeLLM())
    r = api.post("/covers", json={"books": [{"title": "Dune", "author": "Frank Herbert"}]})
    assert r.json()["covers"][0]["cover_url"] == "https://books.google.com/t"


def test_open_library_results_are_cached(api, world):
    api.use(FakeLLM())
    for _ in range(3):
        api.post("/covers", json={"books": [{"title": "Hyperion", "author": "Dan Simmons"}]})
    assert world.ol_calls == 1


def test_details_condenses_a_long_description(api):
    llm = api.use(FakeLLM())
    d = api.post("/book/details", json={"title": "Dune", "author": "Frank Herbert"}).json()
    assert d["synopsis"].startswith("Paul Atreides must survive")
    assert d["cover_url"].endswith("/12345-L.jpg")
    assert d["page_count"] == 412
    # The model only saw the description, cleaned of HTML.
    assert "<p>" not in llm.summarized[0] and "Arrakis" in llm.summarized[0] and '"The greatest' in llm.summarized[0]
    assert "<p>" not in d["description"]


def test_short_description_is_used_as_is(api):
    llm = api.use(FakeLLM())
    d = api.post("/book/details", json={"title": "Short Book", "author": "Ann Writer"}).json()
    assert d["synopsis"] == "A short, plain description."
    assert llm.summarized == []


def test_open_library_description_when_google_has_none(api):
    api.use(FakeLLM())
    d = api.post("/book/details", json={"title": "Bare Book", "author": "Ann Writer"}).json()
    assert d["description"] == "Plain OL text." and d["synopsis"] == "Plain OL text."


def test_synopsis_falls_back_to_opening_sentences(api):
    api.use(FakeLLM(fail=True))
    d = api.post("/book/details", json={"title": "Dune", "author": "Frank Herbert"}).json()
    assert d["synopsis"].startswith("On the desert planet Arrakis")
    assert "million copies" not in d["synopsis"]


def test_no_description_means_no_synopsis(api):
    llm = api.use(FakeLLM())
    d = api.post("/book/details", json={"title": "Nothing Known", "author": "Nobody"}).json()
    assert d["synopsis"] is None and llm.summarized == []


def test_similar_to_a_book(api):
    llm = api.use(FakeLLM(recs=[
        ("Hyperion", "Dan Simmons", "Epic, political SF"),
        ("Dune", "Frank Herbert", "the seed itself"),
        ("A Book That Does Not Exist", "Nobody Real", "invented"),
    ]))
    shelf = [{"title": "Dune", "author": "Frank Herbert", "genres": []},
             {"title": "Emma", "author": "Jane Austen", "genres": []}]
    r = api.post("/recommendations", json={"books": shelf, "similar_to": shelf[0]})
    recs = r.json()["recommendations"]
    assert [x["book"]["title"] for x in recs] == ["Hyperion"]
    assert recs[0]["book"]["cover_url"].endswith("/777-L.jpg")
    assert llm.rec_calls[0]["similar_to"].title == "Dune"


def test_similar_prompt_wording():
    from app.llm import recommend_prompt
    from app.models import OwnedBook
    p = recommend_prompt([OwnedBook(title="Emma", author="Jane Austen")], 6,
                         similar_to=OwnedBook(title="Dune", author="Frank Herbert"))
    assert "similar to Dune by Frank Herbert" in p and "it shares with Dune" in p


def test_synopsis_cleanup():
    from app.llm import clean_synopsis
    assert clean_synopsis('<think>x</think>Synopsis: "A boy on a desert world."') == "A boy on a desert world."


def test_similar_to_has_its_own_cache_key():
    from app.cache import recommendations_key
    shelf = [("Dune", "Frank Herbert"), ("Emma", "Jane Austen")]
    base = dict(author=None, genre=None, limit=8, model="m", prompt_version="2")
    general = recommendations_key(shelf, **base)
    dune = recommendations_key(shelf, **base, similar_to=("Dune", "Frank Herbert"))
    emma = recommendations_key(shelf, **base, similar_to=("Emma", "Jane Austen"))
    assert len({general, dune, emma}) == 3
    assert recommendations_key(shelf, **base, similar_to=None) == general  # unchanged for existing callers
