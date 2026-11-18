"""Covers must be of the book on the shelf: same title, same language, not a guide or adaptation of it."""
import httpx
import pytest
import respx
from fastapi.testclient import TestClient

from app import main
from app.catalog import GOOGLE_BOOKS, _titles_match, guess_language
from app.openlibrary import SEARCH_URL


def vol(title, authors, cover, lang="en", isbn=None):
    info = {"title": title, "authors": authors, "language": lang, "imageLinks": {"thumbnail": cover}}
    if isbn:
        info["industryIdentifiers"] = [{"type": "ISBN_13", "identifier": isbn}]
    return {"volumeInfo": info}


def work(key, title, authors, work_cover, editions):
    """An Open Library search result: the work, and the edition it matched."""
    return {"key": key, "title": title, "author_name": authors, "cover_i": work_cover,
            "editions": {"numFound": len(editions), "docs": editions}}


def ed(title, cover, *langs):
    return {"key": "/books/E", "title": title, "cover_i": cover, "language": list(langs)}


class World:
    def __init__(self):
        self.ol_params = []

    def google(self, request: httpx.Request):
        q = request.url.params["q"]
        if q.startswith('intitle:"Dune"'):
            return httpx.Response(200, json={"items": [
                vol("Dune: The Graphic Novel", ["Frank Herbert", "Brian Herbert"], "http://g/graphic"),
                vol("Dune", ["Frank Herbert"], "http://g/dune-es", lang="es"),
                vol("Dune", ["Frank Herbert"], "http://g/dune-en")]})
        if q.startswith('intitle:"Cien años de soledad"'):
            return httpx.Response(200, json={"items": [
                vol("Cien años de soledad", ["Gabriel García Márquez"], "http://g/cien", lang="es")]})
        if q == "isbn:9788497593793":
            return httpx.Response(200, json={"items": [
                vol("El alquimista", ["Paulo Coelho"], "http://g/alq", lang="es", isbn="9788497593793")]})
        return httpx.Response(200, json={})

    def ol(self, request: httpx.Request):
        p = dict(request.url.params)
        self.ol_params.append(p)
        if p.get("title") == "Dune":
            # The work's own cover (900) is a Spanish edition's; the matched edition is in Spanish too.
            return httpx.Response(200, json={"docs": [work("/works/D", "Dune", ["Frank Herbert"], 900,
                                                           [ed("Dune", 901, "spa")])]})
        if p.get("title") == "The Alchemist":
            return httpx.Response(200, json={"docs": [work("/works/A", "The Alchemist", ["Paulo Coelho"], 700,
                                                           [ed("El alquimista", 701, "spa")])]})
        if p.get("title") == "Piranesi":
            return httpx.Response(200, json={"docs": [work("/works/P", "Piranesi", ["Susanna Clarke"], 600,
                                                           [ed("Piranesi", 601, "eng")])]})
        if p.get("title") == "Cien años de soledad":
            return httpx.Response(200, json={"docs": [work("/works/C", "Cien años de soledad", ["Gabriel García Márquez"], 500,
                                                           [ed("Cien años de soledad", 501, "spa")])]})
        if p.get("isbn") == "9788497593793":
            return httpx.Response(200, json={"docs": [work("/works/A", "The Alchemist", ["Paulo Coelho"], 700,
                                                           [ed("El alquimista", 777, "spa")])]})
        return httpx.Response(200, json={"docs": []})


@pytest.fixture
def world(monkeypatch):
    monkeypatch.setenv("SHELFSCAN_OPEN_LIBRARY", "1")
    return World()


@pytest.fixture
def api(world):
    with respx.mock(assert_all_called=False) as mock:
        mock.get(GOOGLE_BOOKS).mock(side_effect=world.google)
        mock.get(SEARCH_URL).mock(side_effect=world.ol)
        with TestClient(main.app) as c:
            yield c


def cover(api, title, author):
    return api.post("/covers", json={"books": [{"title": title, "author": author}]}).json()["covers"][0]["cover_url"]


def test_same_title_in_another_language_is_not_used(api, world):
    # Open Library only has a Spanish "Dune" cover, so use Google's English edition, not the work's cover.
    assert cover(api, "Dune", "Frank Herbert") == "https://g/dune-en"
    assert world.ol_params[0]["lang"] == "en"


def test_translation_is_not_used(api):
    # The matched edition is "El alquimista": a translation, so not the English book's cover.
    assert cover(api, "The Alchemist", "Paulo Coelho") is None


def test_matching_edition_in_the_right_language_is_used(api):
    assert cover(api, "Piranesi", "Susanna Clarke") == "https://covers.openlibrary.org/b/id/601-L.jpg"


def test_spanish_books_get_spanish_covers(api, world):
    assert cover(api, "Cien años de soledad", "Gabriel García Márquez") == "https://covers.openlibrary.org/b/id/501-L.jpg"
    assert world.ol_params[0]["lang"] == "es"


def test_barcode_gets_the_scanned_editions_cover(api):
    book = api.post("/lookup/isbn", json={"isbn": "9788497593793"}).json()["book"]
    assert book["title"] == "El alquimista"
    assert book["cover_url"] == "https://covers.openlibrary.org/b/id/777-L.jpg"   # not the work's 700


def test_guides_and_adaptations_are_not_the_book():
    assert not _titles_match("Dune", "Dune: The Graphic Novel")
    assert not _titles_match("The Great Gatsby", "The Great Gatsby (SparkNotes Literature Guide)")
    assert not _titles_match("Atomic Habits", "Atomic Habits Workbook")
    assert _titles_match("Dune", "Dune: Deluxe Edition")
    assert _titles_match("Dune: The Graphic Novel", "Dune: The Graphic Novel, Book 1")


def test_unknown_author_needs_the_exact_title():
    from app.catalog import _match
    assert _match("Piranesi", "Unknown", "Piranesi", [])
    assert not _match("The Hobbit", "Unknown", "The Hobbits", [])
    assert _match("The Hobbit", "J.R.R. Tolkien", "The Hobbit: Or There and Back Again", ["J. R. R. Tolkien"])


def test_guess_language():
    assert guess_language("The Name of the Wind") == "en"
    assert guess_language("Dune") == "en"
    assert guess_language("Cien años de soledad") == "es"
    assert guess_language("La sombra del viento") == "es"
    assert guess_language("El alquimista") == "es"
    assert guess_language("L'Étranger") is None
