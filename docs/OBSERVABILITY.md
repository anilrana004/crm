# Observability (Phase 3 Module 5)

Prometheus metrics, alert rules and Grafana dashboards for the SecureTravels CRM.

## Status

| Part | State |
| --- | --- |
| Metric instrumentation in the application | **Implemented and tested** (see below) |
| Alert rules | **Syntax-validated** with `promtool check rules` (13 rules) |
| Prometheus scrape config | **Syntax-validated** with `promtool check config` |
| Grafana dashboards | JSON validated as well-formed; **not rendered** |
| Prometheus + Grafana containers | **Never started** — no Docker daemon in the dev environment |

The last row is the honest limit of this work: the configuration is valid, but
nobody has watched a graph draw. See "What is not verified".

## What the application publishes

`micrometer-registry-prometheus` was added to `backend/pom.xml`. That single
dependency is what makes the rest possible: without a registry on the classpath
Spring Boot has nowhere to put the metrics it already collects, and
`/actuator/prometheus` does not exist at all.

Three groups of series, all captured from a real scrape of this application
rather than assumed:

**Automatic (no application code):**

| Metric | Covers |
| --- | --- |
| `http_server_requests_seconds_{count,sum,max,bucket}` | request rate, error rate and latency per endpoint |
| `hikaricp_connections_{active,idle,pending,min,max}` | database connection pool utilisation |
| `jvm_memory_used_bytes`, `jvm_threads_live_threads`, `process_cpu_usage` | JVM health |

**Added by `observability/RabbitQueueMetrics.java`:**

| Metric | Covers |
| --- | --- |
| `securetravels_rabbitmq_queue_depth{queue}` | dispatch and dead-letter depth |
| `securetravels_rabbitmq_queue_consumers{queue}` | active consumers per queue |
| `securetravels_rabbitmq_queue_metrics_up` | whether the last read of the broker succeeded |
| `securetravels_rabbitmq_queue_metrics_stale_seconds` | age of the last successful read |

## Three design decisions worth knowing

**A scrape never talks to RabbitMQ.** `getQueueInfo()` is a passive
queue-declare — a network round trip to the broker. Doing it inside a gauge
supplier would make Prometheus scrape latency and broker load a function of the
scrape interval. Instead the meters are registered once and a
`@Scheduled` sweep overwrites their values
(`app.observability.queue-sweep-millis`, default 15s).

**Stale is never rendered as zero.** If the broker becomes unreadable the sweep
retains the last known depth, because a depth that drops to 0 on a connection
failure is indistinguishable from a healthy empty queue — and that is exactly
the failure this module exists to prevent. `..._metrics_up` goes to 0 and
`..._metrics_stale_seconds` climbs, and an alert fires. The "no data yet" state
uses a finite sentinel (`1e9`, ~31 years) rather than `Double.MAX_VALUE` so it
stays legible in a dashboard.

**INLINE mode never opens a connection.** The sweep returns early unless
`app.messaging.mode=BROKER`, preserving ADR 0005. The metrics are still
registered in INLINE mode, because a panel that disappears when the queue does
is impossible to alert on, and a silent flip back to INLINE would otherwise look
perfectly healthy.

## Metric labels are bounded cardinality

`http_server_requests` carries a `uri` label. If that were the raw request path,
a handful of 404 probes against random URLs would create unbounded series and
can take a metrics backend down. It is the route pattern.

Verified against a running server, not assumed: requests to
`/actuator/health` produced `uri="/actuator/health"`, and a request to a random
unrouted path produced a single `uri="UNKNOWN"` series rather than a new series
per path. `PrometheusEndpointIT` asserts that a concrete path never becomes a
label value.

## HTTP timings are a histogram, not a summary

Micrometer publishes a *summary* for HTTP timings by default, which yields only
`_max` and a client-computable quantile. Prometheus's `histogram_quantile()`
requires `_bucket` series. Without `management.metrics.distribution.percentiles-histogram.http.server.requests=true`
the p95 latency alert has no data and **can never fire** while every other test
stays green — a silent failure that looks exactly like success.

This was caught by checking the scrape output for `_bucket` rather than trusting
the configuration, and is now pinned by
`PrometheusEndpointIT.httpTimingHistogramIsPublished`.

Similarly, the error-rate rules key on `status=~"5.."` and not on the `error`
label: on this application Micrometer reports `error="none"` for 4xx *and* for a
500 that produced no exception, so an error rate built on `error` would read a
permanent zero.

## Running it

```
docker compose --profile monitoring up -d prometheus grafana
```

- Prometheus: <http://localhost:9090>
- Grafana: <http://localhost:3001> (anonymous Viewer, or `admin` / the value of `GRAFANA_ADMIN_PASSWORD`)

The `monitoring` profile exists so the default `docker compose up` is unchanged
by this module.

## Production: the scrape endpoint must be blocked

`/actuator/prometheus` is in the security config's `PERMITTED` list, so it
answers **without a JWT**. That is deliberate — Prometheus scrapes without
credentials by default, and requiring a token means the module silently scrapes
nothing. It is the same treatment `/actuator/health` already receives.

The cost is that the endpoint has no authentication of its own and it discloses
queue names, connection pool sizes and every route path. It must therefore be
denied at the reverse proxy before the app is exposed publicly:

```nginx
location /actuator/ {
    # Health is polled by the load balancer on the app port; nothing else in
    # /actuator is for the internet.
    allow 127.0.0.1;
    deny  all;
}
```

This is an **operational requirement, not something the code enforces.** Until
that rule is in place, do not put port 8080 on a public interface.
`PrometheusEndpointIT.endpointIsUnauthenticatedByDesign` asserts the current
behaviour deliberately, so that changing it forces a decision rather than
slipping through.

## Alert rules

`ops/observability/alerts.yml`, 13 rules in five groups. The three conditions
named in the Phase 3 brief:

| Condition | Rule |
| --- | --- |
| Growing dead-letter queue | `SecureTravelsDeadLetterQueueGrowing` |
| Error-rate spike | `SecureTravelsHighServerErrorRate` |
| Connection pool exhausted | `SecureTravelsConnectionPoolExhausted` |

Every `for:` duration is non-zero. Each of these conditions has a benign cause
at least once a day — a deploy, a burst, a slow query — and a paging alert that
fires on it teaches people to ignore alerts.

Two rules exist purely to catch *blindness* rather than faults:
`SecureTravelsQueueMetricsStale` and
`SecureTravelsQueueMetricsStaleWhileBrokerMode`. Because the depth gauges cache,
a broker outage would otherwise leave the dashboards showing confident, stale
numbers.

Validate after editing:

```
promtool check rules ops/observability/alerts.yml
promtool check config ops/observability/prometheus.yml
```

Note that `check config` resolves `rule_files` relative to the config file's own
directory, so running it straight from the repo reports
`etc/prometheus/alerts.yml` missing. Inside the container the mount puts the
file at the absolute path the config names, and the same command succeeds.

## Grafana dashboards

`ops/observability/grafana/dashboards/`, provisioned automatically:

- **HTTP Overview** — request rate, 5xx ratio and latency percentiles per route,
  plus a "slowest endpoints" panel.
- **RabbitMQ Messaging** — DLQ depth and dispatch depth as headline stats, queue
  depth over time, and the freshness of the reading.
- **Runtime and Connection Pool** — pool utilisation, pending connections, heap,
  threads, CPU.

The messaging dashboard is flat in local development because `MESSAGING_MODE`
defaults to `INLINE` and RabbitMQ runs on the Windows host rather than in this
compose stack. Switch the backend to `BROKER` mode and point it at a reachable
broker to see the queue panels populate.

## What is not verified

- **No Prometheus or Grafana container has ever been started here.** The Docker
  daemon is not running in the development environment, so while
  `promtool` accepts the configuration and the dashboards are well-formed JSON,
  no graph has been rendered and no alert has fired against real data.
- **No alert has been proven to fire.** PromQL parses, but an alert can be
  syntactically valid and still never match (for example a label mismatch). The
  dashboard panels and the rules should be confirmed against a live Prometheus
  before an on-call rotation depends on them.
- **The Nginx rule above is not deployed anywhere.** Branch protection and the
  production VPS remain unverified external gaps.
- **No external alert destination is wired.** The rules are evaluated by
  Prometheus and visible in its UI; nothing pages anyone yet. Email/Slack/PagerDuty
  needs an Alertmanager, which is not part of this module.
