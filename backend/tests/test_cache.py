"""Recommendation cache: keys, Redis behaviour (fake Redis), fail-open, and the endpoint."""
import asyncio

import pytest

from app import main
from app.cache import NullCache, RedisCache, SingleFlight, recommendations_key
from test_recommendations import SHELF, FakeLLM, api, catalog  # noqa: F401  (fixtures)

PICKS = [("Hyperion", "Dan Simmons", "Epic SF"), ("The Name of the Wind", "Patrick Rothfuss", "Deep magic")]


class MemoryCache(NullCache):
    def __init__(self):
        self.data, self.sets, self.ttls = {}, 0, []

    async def get(self, key):
        return self.data.get(key)

    async def set(self, key, value, ttl_s):
        self.data[key] = value
        self.sets += 1
        self.ttls.append(ttl_s)


def key(books=(("Dune", "Frank Herbert"), ("The Hobbit", "J.R.R. Tolkien")), **kw):
    args = dict(author=None, genre=None, limit=10, model="gateway:books-recommend", prompt_version="1")
    args.update(kw)
    return recommendations_key(books, **args)


# ---- keys ---------------------------------------------------------------------------------------
def test_key_ignores_order_case_and_punctuation():
    a = key((("Dune", "Frank Herbert"), ("The Hobbit", "J.R.R. Tolkien")))
    b = key((("the hobbit", "J R R Tolkien"), ("DUNE", "frank herbert")))
    assert a == b and a.startswith("shelfscan:recs:")


@pytest.mark.parametrize("change", [
    {"author": "Frank Herbert"}, {"genre": "Fantasy"}, {"limit": 5}, {"model": "gateway:other"},
    {"prompt_version": "2"}, {"books": (("Dune", "Frank Herbert"),)},
])
def test_key_changes_when_the_answer_could_change(change):
    assert key(**change) != key()


# ---- Redis behaviour ----------------------------------------------------------------------------
def test_redis_cache_roundtrip_with_expiry():
    fakeredis = pytest.importorskip("fakeredis")

    async def go():
        client = fakeredis.FakeAsyncRedis()
        cache = RedisCache(client)
        assert await cache.get("k") is None
        await cache.set("k", '{"a": 1}', 3600)
        assert await cache.get("k") == '{"a": 1}'
        assert 3500 < await client.ttl("k") <= 3600

    asyncio.run(go())


class DeadRedis:
    def __init__(self):
        self.calls = 0

    async def get(self, key):
        self.calls += 1
        raise ConnectionError("redis is down")

    async def set(self, *a, **k):
        self.calls += 1
        raise ConnectionError("redis is down")

    async def aclose(self):
        raise ConnectionError("still down")


def test_redis_failures_never_raise_and_pause_calls_for_the_cooldown():
    async def go():
        dead = DeadRedis()
        cache = RedisCache(dead, cooldown_s=60)
        assert await cache.get("k") is None          # fails open
        await cache.set("k", "v", 10)                # skipped: cooling down
        assert await cache.get("k") is None
        assert dead.calls == 1                       # only the first call touched the dead server
        await cache.aclose()                         # must not raise either

    asyncio.run(go())


def test_single_flight_runs_one_computation_for_concurrent_identical_requests():
    async def go():
        flight, store, computed = SingleFlight(), {}, 0

        async def request():
            nonlocal computed
            async with flight.lock("k"):
                if "k" in store:
                    return store["k"]
                computed += 1
                await asyncio.sleep(0.01)
                store["k"] = "answer"
                return "answer"

        results = await asyncio.gather(*(request() for _ in range(8)))
        assert results == ["answer"] * 8 and computed == 1
        assert flight._locks == {}                   # no lock left behind

    asyncio.run(go())


# ---- endpoint -----------------------------------------------------------------------------------
@pytest.fixture
def cached(api):  # noqa: F811
    main.state.cache = MemoryCache()
    yield api
    main.state.cache = NullCache()


def test_second_identical_request_is_served_from_cache(cached):
    llm = cached.use(FakeLLM(PICKS))
    first = cached.post("/recommendations", json={"books": SHELF})
    second = cached.post("/recommendations", json={"books": SHELF})
    assert first.headers["X-Cache"] == "MISS" and second.headers["X-Cache"] == "HIT"
    assert second.json() == first.json() and len(llm.calls) == 1
    assert main.state.cache.ttls == [7 * 24 * 3600]


def test_a_different_shelf_focus_or_limit_is_a_separate_entry(cached):
    llm = cached.use(FakeLLM(PICKS))
    cached.post("/recommendations", json={"books": SHELF})
    cached.post("/recommendations", json={"books": SHELF + [{"title": "Emma", "author": "Jane Austen", "genres": []}]})
    cached.post("/recommendations", json={"books": SHELF, "genre": "Fantasy"})
    cached.post("/recommendations", json={"books": SHELF, "limit": 3})
    assert len(llm.calls) == 4


def test_refresh_bypasses_the_cache_but_stores_the_new_answer(cached):
    llm = cached.use(FakeLLM(PICKS))
    cached.post("/recommendations", json={"books": SHELF})
    refreshed = cached.post("/recommendations", json={"books": SHELF, "refresh": True})
    assert refreshed.headers["X-Cache"] == "BYPASS" and len(llm.calls) == 2
    assert cached.post("/recommendations", json={"books": SHELF}).headers["X-Cache"] == "HIT"


def test_a_partial_answer_is_not_cached_when_the_model_failed(cached):
    cached.use(FakeLLM(fail=True))
    r = cached.post("/recommendations", json={"books": SHELF, "author": "Brandon Sanderson"})
    assert r.status_code == 200 and r.json()["recommendations"]      # catalog picks still shown
    assert main.state.cache.sets == 0                                # but not stored for a week
    llm = cached.use(FakeLLM(PICKS))                                 # model recovers: next request computes fresh
    r2 = cached.post("/recommendations", json={"books": SHELF, "author": "Brandon Sanderson"})
    assert r2.headers["X-Cache"] == "MISS" and len(llm.calls) == 1 and main.state.cache.sets == 1


def test_an_answer_with_unverified_picks_is_not_cached_when_the_catalog_is_down(cached, catalog):  # noqa: F811
    catalog.down = True
    cached.use(FakeLLM([("The Glass Citadel of Vorn", "A. Madeup", "Sounds great")]))
    r = cached.post("/recommendations", json={"books": SHELF})
    assert r.status_code == 200 and main.state.cache.sets == 0


def test_a_corrupt_cache_entry_is_recomputed_and_overwritten(cached):
    llm = cached.use(FakeLLM(PICKS))
    cached.post("/recommendations", json={"books": SHELF})
    (k,) = main.state.cache.data
    main.state.cache.data[k] = '{"not": "a response"}'
    r = cached.post("/recommendations", json={"books": SHELF})
    assert r.headers["X-Cache"] == "MISS" and len(llm.calls) == 2
    assert cached.post("/recommendations", json={"books": SHELF}).headers["X-Cache"] == "HIT"


def test_empty_shelf_is_answered_without_touching_the_cache(cached):
    assert cached.post("/recommendations", json={"books": []}).json() == {"recommendations": []}
    assert main.state.cache.sets == 0
