"""Series gaps: owned volumes matched on the server, missing ones checked against the catalog."""
import asyncio
import json
from datetime import date

import httpx
import pytest
import respx
from fastapi.testclient import TestClient

from app import main
from app.catalog import GOOGLE_BOOKS
from app.llm import salvage_json_reply, series_prompt
from app.models import OwnedBook
from app.series import plan_gaps, verify_gaps


def vol(title, authors, date="2015-01-01"):
    return {"volumeInfo": {"title": title, "authors": authors, "publishedDate": date,
                           "imageLinks": {"thumbnail": "http://img/x&edge=curl"}}}


CATALOG = {
    "Oathbringer": vol("Oathbringer", ["Brandon Sanderson"], "2017-11-14"),
    "Wind and Truth": vol("Wind and Truth", ["Brandon Sanderson"], "2024-12-06"),
    "The Wise Man's Fear": vol("The Wise Man's Fear", ["Patrick Rothfuss"], "2011-03-01"),
    "Leviathan Falls": vol("Leviathan Falls", ["James S. A. Corey"], "2021-11-30"),
    "Caliban's War": vol("Caliban's War", ["James S. A. Corey"], "2012-06-26"),
    "Future Book": vol("Future Book", ["Brandon Sanderson"], "2099-01-01"),
}


class Catalog:
    down = False

    def __init__(self):
        self.queries = []

    def __call__(self, request: httpx.Request):
        if self.down:
            return httpx.Response(503)
        q = request.url.params["q"]
        self.queries.append(q)
        for title, item in CATALOG.items():
            if title in q:
                return httpx.Response(200, json={"items": [item]})
        return httpx.Response(200, json={})


STORMLIGHT = {"name": "The Stormlight Archive", "author": "Brandon Sanderson", "volumes": [
    {"number": 1, "title": "The Way of Kings"},
    {"number": 2, "title": "Words of Radiance"},
    {"number": 3, "title": "Oathbringer"},
    {"number": 4, "title": "Rhythm of War"},
    {"number": 5, "title": "Wind and Truth"},
]}
KINGKILLER = {"name": "The Kingkiller Chronicle", "author": "Patrick Rothfuss", "volumes": [
    {"number": 1, "title": "The Name of the Wind"},
    {"number": 2, "title": "The Wise Man's Fear"},
]}
EXPANSE = {"name": "The Expanse", "author": "James S. A. Corey", "volumes": [
    {"number": 1, "title": "Leviathan Wakes"},
    {"number": 2, "title": "Caliban's War"},
]}


class FakeLLM:
    def __init__(self, series=None, complete=True, fail=False):
        self.result, self.complete, self.fail, self.calls = series or [], complete, fail, 0

    async def series(self, owned):
        self.calls += 1
        if self.fail:
            raise httpx.ConnectError("gateway down")
        return self.result, self.complete


class MemoryCache:
    def __init__(self):
        self.store = {}

    async def get(self, key):
        return self.store.get(key)

    async def set(self, key, value, ttl):
        self.store[key] = value

    async def aclose(self):
        pass


@pytest.fixture
def catalog():
    return Catalog()


@pytest.fixture
def api(catalog, monkeypatch):
    cache = MemoryCache()
    monkeypatch.setattr(main, "create_cache", lambda: cache)

    def use(llm):
        main.state.llm = llm
        return llm
    with respx.mock(assert_all_called=False) as mock:
        mock.get(GOOGLE_BOOKS).mock(side_effect=catalog)
        with TestClient(main.app) as c:
            c.use = use
            c.cache = cache
            yield c


def shelf(*titles, author="Brandon Sanderson"):
    return [{"title": t, "author": author, "genres": ["Fantasy"]} for t in titles]


def missing(series):
    return [(v["number"], v["title"]) for v in series["volumes"] if not v["owned"]]


def test_hole_in_the_middle_is_reported_with_catalog_data(api):
    api.use(FakeLLM([STORMLIGHT]))
    # Scanned titles carry series decorations the model's titles don't.
    books = shelf("The Way of Kings (The Stormlight Archive, Book 1)", "Words of Radiance", "Rhythm of War")
    r = api.post("/series/gaps", json={"books": books})
    [s] = r.json()["series"]
    assert s["name"] == "The Stormlight Archive"
    assert [v["number"] for v in s["volumes"] if v["owned"]] == [1, 2, 4]
    assert missing(s) == [(3, "Oathbringer"), (5, "Wind and Truth")]
    gap = next(v for v in s["volumes"] if v["number"] == 3)
    assert gap["book"]["published_date"] == "2017-11-14" and gap["book"]["cover_url"]


def test_invented_volume_is_dropped(api):
    invented = {**STORMLIGHT, "volumes": STORMLIGHT["volumes"][:2] + [
        {"number": 3, "title": "The Glass Citadel of Roshar"},   # catalog has never heard of it
        {"number": 4, "title": "Rhythm of War"},
    ]}
    api.use(FakeLLM([invented]))
    r = api.post("/series/gaps", json={"books": shelf("The Way of Kings", "Rhythm of War")})
    assert r.json()["series"] == []   # the only gap was made up, so nothing is shown


def test_unreleased_volume_is_left_out(api):
    s = {**STORMLIGHT, "volumes": STORMLIGHT["volumes"][:3] + [{"number": 4, "title": "Future Book"}]}
    api.use(FakeLLM([s]))
    r = api.post("/series/gaps", json={"books": shelf("The Way of Kings", "Words of Radiance")})
    [got] = r.json()["series"]
    assert missing(got) == [(3, "Oathbringer")]


def test_only_book_one_owned_is_not_a_gap(api):
    api.use(FakeLLM([EXPANSE]))
    r = api.post("/series/gaps", json={"books": shelf("Leviathan Wakes", author="James S. A. Corey")})
    assert r.json()["series"] == []


def test_two_owned_and_next_one_out_is_shown(api):
    s = {**EXPANSE, "volumes": EXPANSE["volumes"] + [{"number": 3, "title": "Abaddon's Gate"},
                                                     {"number": 9, "title": "Leviathan Falls"}]}
    api.use(FakeLLM([s]))
    books = shelf("Leviathan Wakes", "Caliban's War", author="James S. A. Corey")
    [got] = api.post("/series/gaps", json={"books": books}).json()["series"]
    assert missing(got) == [(9, "Leviathan Falls")]   # Abaddon's Gate isn't in the test catalog: dropped


def test_complete_set_and_unowned_series_are_skipped(api):
    api.use(FakeLLM([KINGKILLER, STORMLIGHT]))
    books = shelf("The Name of the Wind", "The Wise Man's Fear", author="Patrick Rothfuss")
    assert api.post("/series/gaps", json={"books": books}).json()["series"] == []


def test_series_with_gaps_come_first(api):
    api.use(FakeLLM([EXPANSE | {"volumes": EXPANSE["volumes"] + [{"number": 9, "title": "Leviathan Falls"}]},
                     STORMLIGHT]))
    books = (shelf("Leviathan Wakes", "Caliban's War", author="James S. A. Corey")
             + shelf("The Way of Kings", "Rhythm of War"))
    names = [s["name"] for s in api.post("/series/gaps", json={"books": books}).json()["series"]]
    assert names == ["The Stormlight Archive", "The Expanse"]


def test_answer_is_cached_and_refresh_recomputes(api):
    llm = api.use(FakeLLM([STORMLIGHT]))
    books = shelf("The Way of Kings", "Rhythm of War")
    first = api.post("/series/gaps", json={"books": books})
    second = api.post("/series/gaps", json={"books": list(reversed(books))})   # order doesn't matter
    assert (first.headers["X-Cache"], second.headers["X-Cache"]) == ("MISS", "HIT")
    assert second.json() == first.json() and llm.calls == 1
    assert api.post("/series/gaps", json={"books": books, "refresh": True}).headers["X-Cache"] == "BYPASS"
    assert llm.calls == 2


def test_degraded_answers_are_not_cached(api, catalog):
    books = shelf("The Way of Kings", "Rhythm of War")
    api.use(FakeLLM([STORMLIGHT], complete=False))   # the reply was cut off
    api.post("/series/gaps", json={"books": books})
    assert api.cache.store == {}
    catalog.down = True   # missing volumes went unchecked
    api.use(FakeLLM([STORMLIGHT]))
    r = api.post("/series/gaps", json={"books": books})
    assert missing(r.json()["series"][0])[0] == (2, "Words of Radiance")   # kept as is, like recommendations
    assert api.cache.store == {}


def test_model_failure_is_a_502(api):
    api.use(FakeLLM(fail=True))
    assert api.post("/series/gaps", json={"books": shelf("Dune")}).status_code == 502


def test_empty_shelf_needs_no_model(api):
    llm = api.use(FakeLLM([STORMLIGHT]))
    assert api.post("/series/gaps", json={"books": []}).json() == {"series": []}
    assert llm.calls == 0


def test_malformed_volumes_are_ignored():
    raw = [{"name": "S", "author": "A. Writer", "volumes": [
        {"number": 1, "title": "One"}, {"number": "2", "title": "Two"}, {"number": 2.5, "title": "Novella"},
        {"number": 2, "title": "Duplicate number"}, {"number": "x", "title": "Bad"}, {"title": "No number"},
        {"number": 3, "title": ""}, {"number": 4, "title": "Four"},
    ]}, "not a series", {"name": "", "volumes": []}]
    owned = [OwnedBook(title="One", author="A. Writer"), OwnedBook(title="Four", author="A. Writer")]
    [plan] = plan_gaps(raw, owned)
    assert [(v.number, v.title, v.owned) for v in plan.volumes] == [(1, "One", True), (2, "Two", False), (4, "Four", True)]


def test_same_title_by_another_author_is_not_owned():
    raw = [{"name": "S", "author": "A. Writer", "volumes": [{"number": 1, "title": "Legacy"}, {"number": 2, "title": "Two"}]}]
    assert plan_gaps(raw, [OwnedBook(title="Legacy", author="Someone Else")]) == []


def test_lookups_are_capped(monkeypatch):
    from app import series as series_mod
    monkeypatch.setattr(series_mod, "MAX_LOOKUPS", 3)

    class Counting:
        calls = 0

        async def verify(self, book):
            Counting.calls += 1
            return book

    raw = [{"name": "Long", "author": "A. Writer",
            "volumes": [{"number": n, "title": f"Book {chr(64 + n)}"} for n in range(1, 12)]}]
    plans = plan_gaps(raw, [OwnedBook(title="Book K", author="A. Writer")])
    out = asyncio.run(verify_gaps(plans, Counting(), today=date(2026, 1, 1)))
    assert Counting.calls == 3 and [v.number for v in out[0].volumes if not v.owned] == [1, 2, 3]


def test_prompt_lists_the_shelf_and_cut_off_replies_keep_whole_series():
    prompt = series_prompt([OwnedBook(title="Dune", author="Frank Herbert")])
    assert "- Dune by Frank Herbert" in prompt and "already out" in prompt
    cut = '{"series": [{"name": "A", "author": "B", "volumes": [{"number": 1, "title": "X"}]}, {"name": "C", "auth'
    assert salvage_json_reply(cut) == {"series": [{"name": "A", "author": "B", "volumes": [{"number": 1, "title": "X"}]}]}


GATEWAY = "http://gateway.local:8080/v1"


def chat_reply(text, finish="stop"):
    return httpx.Response(200, json={"choices": [{"message": {"role": "assistant", "content": text}, "finish_reason": finish}]})


@respx.mock
def test_openai_compat_series_uses_the_text_model_and_a_bigger_budget():
    from app.llm import OpenAICompatLLM
    route = respx.post(GATEWAY + "/chat/completions").mock(return_value=chat_reply(json.dumps({"series": [STORMLIGHT]})))
    llm = OpenAICompatLLM(base_url=GATEWAY, model="vision", text_model="text")
    series, complete = asyncio.run(llm.series([OwnedBook(title="The Way of Kings", author="Brandon Sanderson")]))
    sent = json.loads(route.calls[0].request.content)
    assert sent["model"] == "text" and sent["max_tokens"] >= 4096
    assert "The Way of Kings by Brandon Sanderson" in sent["messages"][0]["content"]
    assert series == [STORMLIGHT] and complete


@respx.mock
def test_openai_compat_series_cut_off_keeps_whole_series_but_is_incomplete():
    from app.llm import OpenAICompatLLM
    cut = '{"series": [' + json.dumps(KINGKILLER) + ', {"name": "Half'
    respx.post(GATEWAY + "/chat/completions").mock(return_value=chat_reply(cut, finish="length"))
    series, complete = asyncio.run(OpenAICompatLLM(base_url=GATEWAY, model="m").series([OwnedBook(title="x", author="y")]))
    assert series == [KINGKILLER] and not complete


def test_claude_series_calls_the_tool():
    from types import SimpleNamespace
    from app.llm import ClaudeLLM

    class Messages:
        def __init__(self):
            self.kwargs = None

        async def create(self, **kwargs):
            self.kwargs = kwargs
            return SimpleNamespace(content=[SimpleNamespace(type="tool_use", input={"series": [STORMLIGHT]})])

    client = SimpleNamespace(messages=Messages())
    series, complete = asyncio.run(ClaudeLLM(client).series([OwnedBook(title="Dune", author="Frank Herbert")]))
    assert series == [STORMLIGHT] and complete
    assert client.messages.kwargs["tool_choice"] == {"type": "tool", "name": "report_series"}
    assert client.messages.kwargs["max_tokens"] == 8192
