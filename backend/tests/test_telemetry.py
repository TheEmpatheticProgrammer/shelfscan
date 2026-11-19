import asyncio
import json
import logging

import httpx
import pytest
import respx
from fastapi import FastAPI, Response
from fastapi.testclient import TestClient
from opentelemetry.sdk.metrics.export import InMemoryMetricReader

from app import main
from app.catalog import GOOGLE_BOOKS
from app.telemetry import (
    JsonFormatter, MeteredLLM, MeteredTransport, Telemetry, TelemetryMiddleware, dependency_for, request_id_var,
)


def points(reader, name):
    out = []
    data = reader.get_metrics_data()
    for rm in data.resource_metrics if data else []:
        for sm in rm.scope_metrics:
            for m in sm.metrics:
                if m.name == name:
                    out.extend((dict(p.attributes), p) for p in m.data.data_points)
    return out


@pytest.fixture
def metered():
    reader = InMemoryMetricReader()
    telemetry = Telemetry(reader)
    app = FastAPI()
    app.add_middleware(TelemetryMiddleware, telemetry=telemetry)

    @app.get("/books/{book_id}")
    async def book(book_id: str, response: Response):
        response.headers["X-Cache"] = "HIT" if book_id == "cached" else "MISS"
        return {"id": book_id}

    @app.get("/boom")
    async def boom():
        raise RuntimeError("bug")

    with TestClient(app, raise_server_exceptions=False) as c:
        yield c, reader


def test_requests_are_timed_by_route_template_with_cache_results(metered):
    client, reader = metered
    for book_id in ("cached", "a1", "b2"):
        assert client.get(f"/books/{book_id}").headers["x-request-id"]
    client.get("/nothing/here/42")
    assert client.get("/boom").status_code == 500
    seen = {(a["http.route"], a["http.response.status_code"]): p.count
            for a, p in points(reader, "http.server.request.duration")}
    assert seen == {("/books/{book_id}", 200): 3, ("unmatched", 404): 1, ("/boom", 500): 1}
    cache = {a["result"]: p.value for a, p in points(reader, "shelfscan.cache.lookups")}
    assert cache == {"hit": 1, "miss": 2}


def test_dependencies_are_named_from_a_fixed_set():
    assert dependency_for("https://www.googleapis.com/books/v1/volumes") == "google_books"
    assert dependency_for("https://openlibrary.org/search.json") == "open_library"
    assert dependency_for("https://covers.openlibrary.org/b/id/1-L.jpg") == "open_library"
    assert dependency_for("https://somewhere.example/x") == "other"


def test_outbound_calls_are_timed_and_failures_counted_in_a_row():
    reader = InMemoryMetricReader()
    telemetry = Telemetry(reader)
    answers = iter([503, 503, 429, 500, 200])

    def handler(request):
        if "slow" in request.url.path:
            raise httpx.ReadTimeout("slow", request=request)
        return httpx.Response(next(answers))

    async def go():
        transport = MeteredTransport(telemetry, inner=httpx.MockTransport(handler))
        async with httpx.AsyncClient(transport=transport) as client:
            url = "https://www.googleapis.com/books/v1/volumes"
            await client.get(url)
            await client.get(url)
            assert telemetry.failures_in_a_row["google_books"] == 2
            await client.get(url)  # 429: busy, not down
            assert telemetry.failures_in_a_row["google_books"] == 0
            await client.get(url)
            with pytest.raises(httpx.ReadTimeout):
                await client.get("https://www.googleapis.com/slow")
            assert telemetry.failures_in_a_row["google_books"] == 2
            await client.get(url)
            assert telemetry.failures_in_a_row["google_books"] == 0

    asyncio.run(go())
    outcomes = {a["outcome"]: p.count for a, p in points(reader, "shelfscan.dependency.duration")}
    assert outcomes == {"503": 2, "429": 1, "500": 1, "timeout": 1, "200": 1}


def test_model_calls_are_timed_with_the_same_outcomes_whichever_client_failed():
    reader = InMemoryMetricReader()
    telemetry = Telemetry(reader)
    request = httpx.Request("POST", "http://gateway/v1/chat/completions")

    class Model:
        model_name = "m"

        def __init__(self):
            self.errors = [
                httpx.HTTPStatusError("bad", request=request, response=httpx.Response(502, request=request)),
                httpx.ReadTimeout("slow", request=request),
                None,
            ]

        async def identify(self, images):
            err = self.errors.pop(0)
            if err:
                raise err
            return [], None

    llm = MeteredLLM(Model(), telemetry)

    async def go():
        for _ in range(2):
            with pytest.raises(httpx.HTTPError):
                await llm.identify([])
        assert telemetry.failures_in_a_row["llm"] == 2
        assert await llm.identify([]) == ([], None)
        assert telemetry.failures_in_a_row["llm"] == 0

    asyncio.run(go())
    assert llm.model_name == "m"  # anything else passes straight through
    outcomes = {a["outcome"]: p.count for a, p in points(reader, "shelfscan.dependency.duration")
                if a["dependency"] == "llm"}
    assert outcomes == {"502": 1, "timeout": 1, "200": 1}


def test_the_default_model_client_starts(monkeypatch):
    """The real create_llm() (Anthropic by default), wrapped for metrics: the app must start with it."""
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test")
    monkeypatch.delattr(main.State, "llm", raising=False)
    monkeypatch.delattr(main.state, "llm", raising=False)
    with TestClient(main.app) as c:
        assert isinstance(main.state.llm, MeteredLLM)
        assert c.get("/health").status_code == 200


@pytest.fixture
def app_client(monkeypatch):
    monkeypatch.setattr(main.telemetry, "failures_in_a_row", {})
    with respx.mock(assert_all_called=False) as mock:
        mock.get(GOOGLE_BOOKS).mock(return_value=httpx.Response(503))
        with TestClient(main.app) as c:
            yield c


def test_ready_fails_only_when_the_model_is_failing(app_client):
    r = app_client.get("/ready")
    assert r.status_code == 200 and r.json() == {
        "status": "ok", "checks": {"llm": "ok", "google_books": "ok", "open_library": "ok", "cache": "disabled"}}

    # Google Books down: real calls through the app's client are counted; the app still works, so still 200.
    app_client.post("/search", json={"query": "Dune"})
    r = app_client.get("/ready")
    assert r.status_code == 200 and r.json()["status"] == "degraded"
    assert r.json()["checks"]["google_books"].startswith("failing")

    main.telemetry.failures_in_a_row["llm"] = main.READY_FAILURE_THRESHOLD
    r = app_client.get("/ready")
    assert r.status_code == 503 and r.json()["status"] == "down"
    assert app_client.get("/health").status_code == 200  # liveness is unaffected


def test_ready_reports_a_cache_that_does_not_answer(app_client, monkeypatch):
    class DeadRedis:
        async def get(self, key):
            return None

        async def set(self, key, value, ttl_s):
            return None

        async def ping(self):
            raise ConnectionError("down")

        async def aclose(self):
            return None

    monkeypatch.setattr(main.state, "cache", DeadRedis())
    r = app_client.get("/ready")
    assert r.status_code == 200 and r.json()["checks"]["cache"] == "failing: ConnectionError"


def test_json_log_lines_carry_the_request_id():
    rec = logging.LogRecord("shelfscan", logging.WARNING, __file__, 1, "kept %d", (3,), None)
    token = request_id_var.set("abc")
    try:
        line = json.loads(JsonFormatter().format(rec))
    finally:
        request_id_var.reset(token)
    assert line["msg"] == "kept 3" and line["request_id"] == "abc" and line["level"] == "warning"


def test_logs_are_not_exported_unless_asked_for(monkeypatch):
    from app.telemetry import logs_exported

    monkeypatch.setenv("OTEL_EXPORTER_OTLP_ENDPOINT", "https://otlp.example/otlp")
    monkeypatch.delenv("OTEL_LOGS_EXPORTER", raising=False)
    assert not logs_exported()  # log lines carry book titles; see docs/observability.md
    monkeypatch.setenv("OTEL_LOGS_EXPORTER", "otlp")
    assert logs_exported()
