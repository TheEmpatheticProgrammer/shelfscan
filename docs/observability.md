# Production observability (Grafana Cloud)

The backend sends metrics and logs to Grafana Cloud over OpenTelemetry (OTLP), the same way the LLM gateway does.
One Grafana Cloud stack holds both; the gateway's guide (`docs/observability.md` in the llm-gateway repo) covers
creating the stack and its token. Nothing is sent unless `OTEL_EXPORTER_OTLP_ENDPOINT` is set, so local runs and
tests need no account.

| Question | Where |
|---|---|
| Is it up? | Synthetic Monitoring check on `/ready` (step 4), plus the Availability tile |
| Requests per second per endpoint, errors, p50/p95/p99 | `http_server_request_duration_seconds` |
| How often is the cache saving a model call? | `shelfscan_cache_lookups_total` |
| Is the model, Google Books or Open Library slow or failing? | `shelfscan_dependency_duration_seconds` |
| What happened to one request? | The server's own logs, searched by `request_id` (also the `x-request-id` response header); in Loki too once log export is on (below) |

## 1. Connect the backend

Use the same OTLP endpoint and token as the gateway. Set these wherever the backend runs:

```
OTEL_EXPORTER_OTLP_ENDPOINT=https://otlp-gateway-<zone>.grafana.net/otlp
OTEL_EXPORTER_OTLP_HEADERS=Authorization=Basic <value from Grafana Cloud>
OTEL_RESOURCE_ATTRIBUTES=service.namespace=production
LOG_FORMAT=json
```

Logs stay on the server unless you also set `OTEL_LOGS_EXPORTER=otlp`. They include the titles read from photos, and
the privacy policy (`docs/privacy-policy.md`) currently says server logs stay with the server and names Google Books and
Open Library as the only services the server talks to. Before turning log export on, update the policy to name
Grafana Labs as a processor of server logs, and check that your Grafana Cloud log retention fits the
policy's 30 days. Metrics carry no titles, photos or identifiers, only timings and counts per endpoint and dependency.

Series are labelled `job="production/shelfscan"` (`staging/shelfscan` with `service.namespace=staging`). Check it in
**Explore**: `shelfscan_dependency_duration_seconds_count` on the `...-prom` data source, and `{service_name="shelfscan"}`
on the `...-logs` data source (once log export is on).

## 2. Import the dashboard

**Dashboards > New > Import**, upload `observability/grafana-dashboard.json`. Panels: availability, requests/s,
/identify p95 and cache-hit tiles; requests/s, errors and p50/p95/p99 by endpoint; cache hits and misses; outbound
calls, p95 and failure share by dependency; warning and error log lines.

## 3. Load the alert rules

`observability/alert-rules.yml`: 5xx above 2%, model calls failing above 10% (page), catalog failing above 25%, Google
Books rate-limiting, /identify p95 above 90 s, and metrics going missing. Load them with mimirtool as in the gateway
guide:

```
mimirtool rules load observability/alert-rules.yml --address=<prometheus url without /api/prom> --id=<user> --key=<token>
```

## 4. Uptime check

**Testing & synthetics > Synthetics > Add check > HTTP**, URL `https://<backend host>/ready`, every 60 s from three or
more locations, expect 200, alert when it fails from 2+ locations.

- `/health` is liveness: the process answers. It checks nothing else.
- `/ready` returns 503 when the model has failed `READY_FAILURE_THRESHOLD` (default 3) calls in a row: 5xx, 401/403,
  timeouts or refused connections. Google Books, Open Library and Redis are listed in the body too, but the app works
  without them (Open Library stands in for Google Books; without Redis nothing is cached), so they make the status
  "degraded" without failing the check. Everything is judged from real traffic; the check never calls the model.
  A 429 counts as busy, not down.

## What is measured

| Metric (as in PromQL) | Labels |
|---|---|
| `http_server_request_duration_seconds` (histogram) | `http_route` (the template; unknown paths are `unmatched`), `http_request_method`, `http_response_status_code` |
| `shelfscan_cache_lookups_total` | `http_route`, `result` (`hit`, `miss`, `bypass`), from the `X-Cache` header |
| `shelfscan_dependency_duration_seconds` (histogram) | `dependency` (`llm`, `google_books`, `open_library`, `other`), `outcome` (HTTP status, or `timeout`, `connect_error`, `error`) |

Model calls are timed per call to `identify`, `recommend`, `summarize` and `series`, including retries and the wait for
a concurrency slot. Catalog calls are timed per HTTP request, so each retry counts. Latency buckets run from 5 ms to
300 s. Photos, book lists and prompts are never in metrics.

Logs: one JSON object per line on stdout (`LOG_FORMAT=json`), and, with `OTEL_LOGS_EXPORTER=otlp`, the same records
over OTLP with `request_id`. Each
request writes one `shelfscan.access` line with route, status, duration and cache result. The Dockerfile turns off
uvicorn's own access log.
