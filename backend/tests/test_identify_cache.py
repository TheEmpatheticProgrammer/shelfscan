"""/identify cache: the same photo is read once; degraded or empty reads are never stored."""
import pytest

from app import main
from app.cache import NullCache, identify_key
from app.llm import CUT_OFF_NOTE, FAILED_PHOTO_NOTE, identify_was_degraded
from app.models import Book
from test_api import client, gb  # noqa: F401  (fixtures and helpers)
from test_cache import MemoryCache

PHOTO_A, PHOTO_B = b"\xff\xd8photo-a", b"\xff\xd8photo-b"


def upload(client, *photos, **params):
    files = [("images", (f"p{i}.jpg", data, "image/jpeg")) for i, data in enumerate(photos)]
    return client.post("/identify", files=files, params=params)


class CountingLLM:
    def __init__(self, books=None, notes=None):
        self.books = [Book(title="Dune", author="Frank Herbert")] if books is None else books
        self.notes, self.calls = notes, 0

    async def identify(self, images):
        self.calls += 1
        return list(self.books), self.notes


@pytest.fixture
def cached(client):  # noqa: F811
    main.state.cache = MemoryCache()
    yield client
    main.state.cache = NullCache()


def test_same_photo_is_read_once(cached):
    main.state.llm = llm = CountingLLM()
    first, second = upload(cached, PHOTO_A), upload(cached, PHOTO_A)
    assert (first.headers["X-Cache"], second.headers["X-Cache"]) == ("MISS", "HIT")
    assert second.json() == first.json() and llm.calls == 1
    assert [b["title"] for b in second.json()["books"]] == ["Dune"]
    assert main.state.cache.ttls == [30 * 24 * 3600]


def test_a_different_photo_is_its_own_entry(cached):
    main.state.llm = llm = CountingLLM()
    upload(cached, PHOTO_A)
    assert upload(cached, PHOTO_B).headers["X-Cache"] == "MISS" and llm.calls == 2


def test_photo_order_does_not_matter(cached):
    main.state.llm = llm = CountingLLM()
    upload(cached, PHOTO_A, PHOTO_B)
    assert upload(cached, PHOTO_B, PHOTO_A).headers["X-Cache"] == "HIT" and llm.calls == 1


def test_refresh_skips_the_stored_answer_and_stores_the_new_one(cached):
    main.state.llm = llm = CountingLLM()
    upload(cached, PHOTO_A)
    again = upload(cached, PHOTO_A, refresh="true")
    assert again.headers["X-Cache"] == "BYPASS" and llm.calls == 2
    assert upload(cached, PHOTO_A).headers["X-Cache"] == "HIT" and llm.calls == 2


@pytest.mark.parametrize("notes", [CUT_OFF_NOTE, f"Photo 2 {FAILED_PHOTO_NOTE}", f"blurry. {CUT_OFF_NOTE}"])
def test_a_degraded_read_is_not_stored(cached, notes):
    main.state.llm = llm = CountingLLM(notes=notes)
    upload(cached, PHOTO_A)
    assert upload(cached, PHOTO_A).headers["X-Cache"] == "MISS" and llm.calls == 2
    assert main.state.cache.sets == 0


def test_an_empty_read_is_not_stored(cached):
    main.state.llm = llm = CountingLLM(books=[], notes="no books visible")
    upload(cached, PHOTO_A)
    assert upload(cached, PHOTO_A).headers["X-Cache"] == "MISS" and llm.calls == 2


def test_a_read_checked_while_the_catalog_was_down_is_not_stored(cached, monkeypatch):
    main.state.llm = CountingLLM()

    async def down(self, q, **params):
        self.failures += 1
        return None
    monkeypatch.setattr(main.Catalog, "_search", down)
    body = upload(cached, PHOTO_A).json()
    assert "couldn't be checked" in body["notes"] and main.state.cache.sets == 0


def test_a_corrupt_entry_is_recomputed_and_overwritten(cached):
    main.state.llm = llm = CountingLLM()
    upload(cached, PHOTO_A)
    (key,) = main.state.cache.data
    main.state.cache.data[key] = '{"books": "nonsense"}'
    again = upload(cached, PHOTO_A)
    assert again.headers["X-Cache"] == "MISS" and llm.calls == 2
    assert upload(cached, PHOTO_A).headers["X-Cache"] == "HIT"


def base(**kw):
    args = dict(images=[PHOTO_A], model="gateway:books-vision", prompt_version="1:abc", policy="drop")
    args.update(kw)
    return identify_key(**args)


def test_key_is_stable_and_order_free():
    assert base() == base() and base(images=[PHOTO_A, PHOTO_B]) == base(images=[PHOTO_B, PHOTO_A])


@pytest.mark.parametrize("change", [
    dict(images=[PHOTO_B]), dict(images=[PHOTO_A, PHOTO_B]), dict(model="gateway:other"),
    dict(prompt_version="2:abc"), dict(prompt_version="1:xyz"), dict(policy="keep"),
])
def test_key_changes_when_the_answer_could_change(change):
    assert base(**change) != base()


def test_degraded_note_detection():
    assert identify_was_degraded(CUT_OFF_NOTE) and identify_was_degraded(f"a. Photo 1 {FAILED_PHOTO_NOTE}")
    assert not identify_was_degraded(None) and not identify_was_degraded("One spine was blurry")
