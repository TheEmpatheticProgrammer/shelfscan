"""Metrics and logs for the backend, sent to Grafana Cloud (or any OpenTelemetry backend) over OTLP.

Nothing is sent unless OTEL_EXPORTER_OTLP_ENDPOINT is set, so tests and local runs need no account. Logs are sent only
with OTEL_LOGS_EXPORTER=otlp as well (see logs_exported). Configure with
the standard OpenTelemetry variables:

  OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp-gateway-<zone>.grafana.net/otlp
  OTEL_EXPORTER_OTLP_HEADERS=Authorization=Basic <base64 of instance-id:token>
  OTEL_RESOURCE_ATTRIBUTES=service.namespace=production
  OTEL_SERVICE_NAME=shelfscan          (the default)

Grafana Cloud labels every series job="<service.namespace>/<service.name>". Setup: docs/observability.md.

What is measured (names as they appear in Grafana / PromQL):

  http_server_request_duration_seconds   every request: route template, method, status code
  shelfscan_cache_lookups_total          answers served from cache or not, per route (from the X-Cache header)
  shelfscan_dependency_duration_seconds  every model call (llm) and catalog call (google_books, open_library), by outcome
"""
from __future__ import annotations

import contextvars
import json
import logging
import os
import sys
import time
import uuid
from urllib.parse import urlparse

import httpx
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import MetricReader, PeriodicExportingMetricReader
from opentelemetry.sdk.metrics.view import ExplicitBucketHistogramAggregation, View
from opentelemetry.sdk.resources import Resource

log = logging.getLogger("shelfscan.telemetry")

# A shelf photo can take minutes on a local model, so the default buckets (which stop at 10 s) would hide the tail.
LATENCY_BUCKETS_S = (0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2, 3, 5, 7.5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240, 300)

request_id_var: contextvars.ContextVar[str | None] = contextvars.ContextVar("request_id", default=None)


def exporting() -> bool:
    return bool(os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT") or os.getenv("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT"))


def _resource() -> Resource:
    return Resource.create({"service.name": os.getenv("OTEL_SERVICE_NAME", "shelfscan")})  # + OTEL_RESOURCE_ATTRIBUTES


def dependency_for(url: httpx.URL | str) -> str:
    """A small fixed set of names, never the raw host, so a new host can't create unbounded series."""
    host = (url.host if isinstance(url, httpx.URL) else urlparse(str(url)).hostname) or ""
    if host.endswith("googleapis.com"):
        return "google_books"
    if host.endswith("openlibrary.org"):
        return "open_library"
    return "other"


class Telemetry:
    def __init__(self, reader: MetricReader | None = None):
        """`reader` is for tests (an InMemoryMetricReader). Otherwise the OTLP exporter is used when configured."""
        readers: list[MetricReader] = []
        if reader is not None:
            readers.append(reader)
        elif exporting():
            from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter

            interval_ms = int(os.getenv("OTEL_METRIC_EXPORT_INTERVAL", "15000"))
            readers.append(PeriodicExportingMetricReader(OTLPMetricExporter(), export_interval_millis=interval_ms))
        buckets = ExplicitBucketHistogramAggregation(LATENCY_BUCKETS_S)
        self.provider = MeterProvider(
            resource=_resource(),
            metric_readers=readers,
            views=[
                View(instrument_name="http.server.request.duration", aggregation=buckets),
                View(instrument_name="shelfscan.dependency.duration", aggregation=buckets),
            ],
        )
        meter = self.provider.get_meter("shelfscan")
        self.http_duration = meter.create_histogram(
            "http.server.request.duration", unit="s", description="Request time, by route template"
        )
        self.cache_lookups = meter.create_counter(
            "shelfscan.cache.lookups", description="Answers served from cache (HIT) or computed (MISS, BYPASS)"
        )
        self.dependency_duration = meter.create_histogram(
            "shelfscan.dependency.duration", unit="s", description="Outbound call time, by dependency and outcome"
        )
        # Failures in a row per dependency (see record_dependency). Any other answer resets it.
        self.failures_in_a_row: dict[str, int] = {}

    def record_http(self, route: str, method: str, status: int, seconds: float, cache: str | None) -> None:
        self.http_duration.record(
            seconds, {"http.route": route, "http.request.method": method, "http.response.status_code": status}
        )
        if cache:
            self.cache_lookups.add(1, {"http.route": route, "result": cache.lower()})

    def record_dependency(self, dependency: str, outcome: str, seconds: float) -> None:
        """outcome: an HTTP status code as text, or "timeout" / "connect_error" / "error"."""
        self.dependency_duration.record(seconds, {"dependency": dependency, "outcome": outcome})
        # 5xx, timeouts and refused connections are failures, and so are 401/403 (a revoked or wrong key is an outage).
        # 429 means busy, not down: it has its own panel, and must not fail /ready.
        failed = not outcome.isdigit() or outcome.startswith("5") or outcome in ("401", "403")
        self.failures_in_a_row[dependency] = self.failures_in_a_row.get(dependency, 0) + 1 if failed else 0

    def shutdown(self) -> None:
        try:
            self.provider.shutdown()
        except Exception:  # noqa: BLE001  exporting on the way out is best effort
            log.warning("metrics exporter did not shut down cleanly", exc_info=True)


class MeteredTransport(httpx.AsyncBaseTransport):
    """Wraps an httpx transport and times every outbound call, including ones that time out or can't connect."""

    def __init__(self, telemetry: Telemetry, inner: httpx.AsyncBaseTransport | None = None):
        self.telemetry = telemetry
        self.inner = inner or httpx.AsyncHTTPTransport()

    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        dep = dependency_for(request.url)
        started = time.perf_counter()
        try:
            response = await self.inner.handle_async_request(request)
        except httpx.TimeoutException:
            self.telemetry.record_dependency(dep, "timeout", time.perf_counter() - started)
            raise
        except httpx.ConnectError:
            self.telemetry.record_dependency(dep, "connect_error", time.perf_counter() - started)
            raise
        except Exception:
            self.telemetry.record_dependency(dep, "error", time.perf_counter() - started)
            raise
        # Time to the response headers: for these JSON APIs that is nearly all of it.
        self.telemetry.record_dependency(dep, str(response.status_code), time.perf_counter() - started)
        return response

    async def aclose(self) -> None:
        await self.inner.aclose()


def llm_outcome(exc: BaseException) -> str:
    """The same outcome labels as HTTP calls, for errors from either model client (Anthropic SDK or httpx)."""
    status = getattr(exc, "status_code", None) or getattr(getattr(exc, "response", None), "status_code", None)
    if isinstance(status, int):
        return str(status)
    name = type(exc).__name__.lower()
    if "timeout" in name:
        return "timeout"
    if "connect" in name or "connection" in name:
        return "connect_error"
    return "error"


class MeteredLLM:
    """Times each model call (identify, recommend, summarize, series) as the "llm" dependency.

    Wraps the model object rather than its HTTP client: the Anthropic SDK has its own HTTP stack (httpx2), and timing
    the whole call includes retries and the wait for a concurrency slot, which is what a user actually waits for.
    """

    METHODS = {"identify", "recommend", "summarize", "series"}

    def __init__(self, inner, telemetry: Telemetry):
        self._inner = inner
        self._telemetry = telemetry

    def __getattr__(self, name):
        attr = getattr(self._inner, name)
        if name not in self.METHODS:
            return attr

        async def timed(*args, **kwargs):
            started = time.perf_counter()
            try:
                result = await attr(*args, **kwargs)
            except Exception as exc:
                self._telemetry.record_dependency("llm", llm_outcome(exc), time.perf_counter() - started)
                raise
            self._telemetry.record_dependency("llm", "200", time.perf_counter() - started)
            return result

        return timed


class TelemetryMiddleware:
    """Times every request by route template, gives it a request id (x-request-id), and writes one access-log line.

    Pure ASGI rather than BaseHTTPMiddleware so the route template the router matched is visible afterwards.
    """

    def __init__(self, app, telemetry: Telemetry):
        self.app = app
        self.telemetry = telemetry
        self.access = logging.getLogger("shelfscan.access")

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        rid = str(uuid.uuid4())
        token = request_id_var.set(rid)
        status, cache = 500, None
        started = time.perf_counter()

        async def send_wrapper(message):
            nonlocal status, cache
            if message["type"] == "http.response.start":
                status = message["status"]
                headers = list(message.get("headers") or [])
                cache = next((v.decode("latin-1") for k, v in headers if k.lower() == b"x-cache"), None)
                message["headers"] = headers + [(b"x-request-id", rid.encode())]
            await send(message)

        try:
            await self.app(scope, receive, send_wrapper)
        finally:
            seconds = time.perf_counter() - started
            route = getattr(scope.get("route"), "path", None) or "unmatched"  # never the raw path: unbounded labels
            method = scope.get("method", "")
            self.telemetry.record_http(route, method, status, seconds, cache)
            self.access.info(
                "%s %s %s %.0fms%s", method, route, status, seconds * 1000, f" cache={cache}" if cache else "",
                extra={"http_route": route, "http_method": method, "http_status": status,
                       "duration_ms": round(seconds * 1000, 1), "cache": cache},
            )
            request_id_var.reset(token)


# ---- logs -----------------------------------------------------------------------------------------
_RESERVED = set(logging.makeLogRecord({}).__dict__) | {"message", "asctime"}


class JsonFormatter(logging.Formatter):
    """One JSON object per line, with the request id, so a request's log lines can be found together."""

    def format(self, record: logging.LogRecord) -> str:
        out = {
            "ts": time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(record.created)) + f".{int(record.msecs):03d}Z",
            "level": record.levelname.lower(),
            "logger": record.name,
            "msg": record.getMessage(),
        }
        rid = request_id_var.get()
        if rid:
            out["request_id"] = rid
        for key, value in record.__dict__.items():
            if key not in _RESERVED and not key.startswith("_") and value is not None:
                out[key] = value
        if record.exc_info:
            out["exc"] = self.formatException(record.exc_info)
        return json.dumps(out, default=str)


class RequestIdFilter(logging.Filter):
    """Puts the request id on the record so the OTLP log exporter sends it as an attribute."""

    def filter(self, record: logging.LogRecord) -> bool:
        rid = request_id_var.get()
        if rid and not hasattr(record, "request_id"):
            record.request_id = rid
        return True


_log_provider = None


def logs_exported() -> bool:
    """Log lines include book titles read from photos, and the privacy policy says server logs stay with the server.
    So logs are only sent to Grafana Cloud when asked for explicitly (OTEL_LOGS_EXPORTER=otlp), after the policy
    names Grafana Labs. Metrics carry no user content and are sent whenever an endpoint is set."""
    return os.getenv("OTEL_LOGS_EXPORTER", "none").strip().lower() == "otlp" and (
        exporting() or bool(os.getenv("OTEL_EXPORTER_OTLP_LOGS_ENDPOINT"))
    )


def setup_logging(fmt: str = "text", level: str = "INFO") -> None:
    """Configure the shelfscan.* loggers: JSON or text to stdout, plus OTLP export when configured. Idempotent."""
    global _log_provider
    root = logging.getLogger("shelfscan")
    if getattr(root, "_configured", False):
        return
    root._configured = True  # type: ignore[attr-defined]
    root.setLevel(level.upper())
    root.propagate = False
    stream = logging.StreamHandler(sys.stdout)
    stream.setFormatter(
        JsonFormatter() if fmt == "json" else logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s")
    )
    root.addHandler(stream)
    if logs_exported():
        from opentelemetry.exporter.otlp.proto.http._log_exporter import OTLPLogExporter
        from opentelemetry.sdk._logs import LoggerProvider, LoggingHandler
        from opentelemetry.sdk._logs.export import BatchLogRecordProcessor

        _log_provider = LoggerProvider(resource=_resource())
        _log_provider.add_log_record_processor(BatchLogRecordProcessor(OTLPLogExporter()))
        handler = LoggingHandler(logger_provider=_log_provider)
        handler.addFilter(RequestIdFilter())
        root.addHandler(handler)


def shutdown_logging() -> None:
    if _log_provider is not None:
        try:
            _log_provider.shutdown()
        except Exception:  # noqa: BLE001
            pass
