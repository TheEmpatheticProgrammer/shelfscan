"""Response cache for recommendations. Redis when REDIS_URL is set, otherwise off.

Recommendations are slow (a model call plus a catalog lookup per pick) and change rarely, so the finished,
verified response is stored for a few days. Three rules keep it safe:

  * Fail open. If Redis is missing, down or slow, requests behave exactly as they did without a cache.
    After an error the cache is skipped for a short cooldown, so a dead Redis never adds a timeout to every call.
  * The key covers everything that changes the answer: the books the model sees, the focus (author/genre),
    the limit, the model and the prompt version. A different shelf, so a different key.
  * Identical requests that arrive together share one computation (single-flight) instead of all calling the model.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import re
import time
from collections.abc import Iterable
from typing import Protocol

log = logging.getLogger("shelfscan.cache")

REDIS_URL = os.getenv("REDIS_URL", "")  # e.g. redis://localhost:6379/0 ; empty means no cache
TTL_S = int(os.getenv("RECS_CACHE_TTL_S", str(7 * 24 * 3600)))  # "doesn't change often": a week
# Bump to throw away every cached answer, e.g. after switching the model behind a gateway alias.
CACHE_VERSION = os.getenv("RECS_CACHE_VERSION", "1")
SOCKET_TIMEOUT_S = float(os.getenv("REDIS_TIMEOUT_S", "0.25"))
COOLDOWN_S = float(os.getenv("REDIS_COOLDOWN_S", "30"))


class Cache(Protocol):
    async def get(self, key: str) -> str | None: ...
    async def set(self, key: str, value: str, ttl_s: int) -> None: ...
    async def aclose(self) -> None: ...


class NullCache:
    """No cache: every get misses and every set is dropped."""

    async def get(self, key: str) -> str | None:
        return None

    async def set(self, key: str, value: str, ttl_s: int) -> None:
        return None

    async def aclose(self) -> None:
        return None


class RedisCache:
    def __init__(self, client, cooldown_s: float = COOLDOWN_S):
        self._r = client
        self._cooldown_s = cooldown_s
        self._down_until = 0.0

    def _available(self) -> bool:
        return time.monotonic() >= self._down_until

    def _failed(self, what: str, exc: Exception) -> None:
        if self._available():  # log once per outage, not once per request
            log.warning("Redis %s failed (%r); caching is off for %.0f s", what, exc, self._cooldown_s)
        self._down_until = time.monotonic() + self._cooldown_s

    async def get(self, key: str) -> str | None:
        if not self._available():
            return None
        try:
            value = await self._r.get(key)
        except Exception as exc:  # any Redis problem must never fail a request
            self._failed("get", exc)
            return None
        return value.decode() if isinstance(value, bytes) else value

    async def set(self, key: str, value: str, ttl_s: int) -> None:
        if not self._available():
            return
        try:
            await self._r.set(key, value, ex=ttl_s)
        except Exception as exc:
            self._failed("set", exc)

    async def aclose(self) -> None:
        try:
            await self._r.aclose()
        except Exception:  # shutting down anyway
            pass


def create_cache() -> Cache:
    if not REDIS_URL:
        log.info("REDIS_URL not set: recommendations are not cached")
        return NullCache()
    import redis.asyncio as redis

    client = redis.from_url(
        REDIS_URL, socket_connect_timeout=SOCKET_TIMEOUT_S, socket_timeout=SOCKET_TIMEOUT_S
    )
    log.info("Caching recommendations in Redis (ttl %.1f days)", TTL_S / 86400)
    return RedisCache(client)


def _norm(text: str | None) -> str:
    return re.sub(r"[^a-z0-9]", "", (text or "").lower())


def recommendations_key(
    books: Iterable[tuple[str, str]], *, author: str | None, genre: str | None, limit: int, model: str,
    prompt_version: str, similar_to: tuple[str, str] | None = None,
) -> str:
    """Stable key for one recommendation request. Order of the books does not matter."""
    shelf = sorted({(_norm(t), _norm(a)) for t, a in books})
    fields = {"v": CACHE_VERSION, "p": prompt_version, "m": model, "shelf": shelf,
              "author": _norm(author), "genre": _norm(genre), "limit": limit}
    if similar_to:  # "books like this one" (only added when set, so other keys stay as they were)
        fields["similar_to"] = [_norm(similar_to[0]), _norm(similar_to[1])]
    payload = json.dumps(fields, separators=(",", ":"))
    return "shelfscan:recs:" + hashlib.sha256(payload.encode()).hexdigest()


def series_key(books: Iterable[tuple[str, str]], *, model: str, prompt_version: str) -> str:
    """Stable key for one series-gaps request: the shelf (in any order), the model and the prompt."""
    shelf = sorted({(_norm(t), _norm(a)) for t, a in books})
    payload = json.dumps({"v": CACHE_VERSION, "p": prompt_version, "m": model, "shelf": shelf}, separators=(",", ":"))
    return "shelfscan:series:" + hashlib.sha256(payload.encode()).hexdigest()


class SingleFlight:
    """One lock per key, so concurrent identical requests wait for the first instead of repeating its work."""

    def __init__(self) -> None:
        self._locks: dict[str, tuple[asyncio.Lock, int]] = {}

    def lock(self, key: str) -> "_Held":
        return _Held(self, key)


class _Held:
    def __init__(self, owner: SingleFlight, key: str):
        self._owner, self._key = owner, key

    async def __aenter__(self):
        lock, users = self._owner._locks.get(self._key, (asyncio.Lock(), 0))
        self._owner._locks[self._key] = (lock, users + 1)
        self._lock = lock
        await lock.acquire()

    async def __aexit__(self, *exc):
        self._lock.release()
        lock, users = self._owner._locks[self._key]
        if users <= 1:
            del self._owner._locks[self._key]
        else:
            self._owner._locks[self._key] = (lock, users - 1)
