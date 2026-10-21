# Runway monitoring bundle

Drop-in Prometheus + Grafana assets for a Runway deployment. They read the metrics Runway exposes at
`/actuator/prometheus` — the JVM/HTTP metrics Actuator ships, the route-level `http_server_requests_*`, the
Runway platform gauges, and the **per-site** request timer `runway_http_server_requests_seconds_*` (tagged
`site` and `outcome`).

## 1. Expose the endpoint to your monitoring system

`/actuator/prometheus` is **not public** — by default only an authenticated application superadmin/admin may read
it. For an unattended scraper, set a **scrape token** in the external `application.yml`:

```yaml
runway:
  metrics:
    scrape-token: "<a long random string>"
```

Then Prometheus authenticates with a bearer token — nothing else about the endpoint changes, and the token
grants access to **`/actuator/prometheus` only** (not the rest of `/actuator`). Example `prometheus.yml` job:

```yaml
scrape_configs:
  - job_name: runway
    metrics_path: /actuator/prometheus
    scheme: https            # or http behind a TLS-terminating proxy
    authorization:
      type: Bearer
      credentials: "<the same scrape-token value>"
    static_configs:
      - targets: ["runway.example.com:8443"]
```

> Leave `scrape-token` blank to keep the endpoint operator-only (no token auth). Treat the token as a secret —
> it is read-only, but it reveals per-site traffic. Keep the external `application.yml` `chmod 600`.

### Behind a reverse proxy — a scrape that 503s while `curl` gets 200

If Runway sits behind a reverse proxy that **routes by an exact `Host` match**, the scraper can get **503** while
`curl` from the same host with the same token gets **200**. Two causes produce that identical symptom:

**1. A `Host`-header port mismatch (the common one).** A Prometheus / VictoriaMetrics scraper is an
**HTTP/1.1** client (VictoriaMetrics uses `fasthttp`, which is HTTP/1.1-only) and, when its target is written
`host:443`, it sends **`Host: runway.example.com:443` — with the port**. A proxy rule that matches the host
*exactly* (e.g. HAProxy `acl … hdr(host) -i runway.example.com`) then **misses it**, because **HTTP/2 normalizes
the `:authority` port away but HTTP/1.1 does not** — so the request falls through to the proxy's *default* /
*other* backend and 503s (HAProxy's *"No server is available to handle this request"* if that backend is down).
`curl` works because it sends `Host` without the default `:443` (or negotiates HTTP/2, where the port is
normalized). This is why an otherwise-identical `curl` — same token, same headers — gets 200.

**2. TLS-fingerprint / bot filtering (less common).** A **WAF** that fingerprints clients (JA3/JA4) or filters
by `User-Agent` (`vm_promscrape`) / "non-browser client" can route the Go client to a deny/tarpit/dead backend
for the same *"scraper 503s, curl 200"* symptom — Go's TLS `ClientHello` differs from a browser's or `curl`'s.

**Diagnose at the proxy**, not in Runway (`/actuator/health` is `UP` throughout — Runway is not down): log which
**backend** the scraper matched and the **`Host`** it sent. HAProxy `httplog` shows the backend as e.g.
`runway/…` (good) vs `servers/<NOSRV>` (fell through to a down default). **Fix at the proxy** — a deployment
gotcha, not a Runway bug:

- **make the host rule port-tolerant** — match `runway.example.com` *and* `runway.example.com:443`, or strip the
  port before matching (HAProxy: `hdr(host),field(1,:)`); or
- **allowlist the scraper's source IP** / **exempt `/actuator/prometheus`** — least-privilege, IP- and/or
  path-scoped, and the endpoint stays protected by its bearer token; or
- point the scraper at Runway's **internal** address (behind the proxy, e.g. over the VPN / on `:8080`) so it
  never crosses the proxy at all — or simply set the Prometheus target to the **bare host without `:443`**.

## 2. Alert rules

`runway-prometheus-alerts.yml` — reference it from `prometheus.yml`:

```yaml
rule_files:
  - /etc/prometheus/rules/runway-prometheus-alerts.yml
```

The rules cover target-down, per-site 5xx rate, per-site latency, dropped log lines, suspended sites, and high
heap. Thresholds and `for:` windows are conservative starting points — tune them to your traffic.

## 3. Grafana dashboard

`runway-grafana-dashboard.json` — import it in Grafana (Dashboards → New → Import → Upload JSON), and pick your
Prometheus data source when prompted. It has an **overview row** (sites / suspended / maintenance / plugins
loaded / log lines dropped) and per-site **request rate**, **5xx rate**, **latency**, plus **JVM heap**, with a
`site` template variable to focus one tenant.

## Metrics reference (the ones these assets use)

| Metric | Type | Meaning |
|--------|------|---------|
| `runway_sites` | gauge | configured sites |
| `runway_sites_suspended` | gauge | operator-suspended sites |
| `runway_sites_maintenance` | gauge | sites in tenant maintenance mode |
| `runway_plugins_loaded` | gauge | plugins currently loaded |
| `runway_logs_dropped` | gauge | access/audit log lines dropped under load since startup |
| `runway_edge_rejections_total` | counter | requests rejected at the protection edge, tagged `status` (403/429) and `reason` (`rate-limit`, `blocklist-manual`, `blocklist-auto`, `blocklist-cidr`, `unspecified`) |
| `runway_http_server_requests_seconds_count` / `_sum` | timer | **per-site** request count / total latency, tagged `site`, `outcome` |
| `http_server_requests_seconds_*` | timer | route-level request timing (Actuator built-in), tagged `uri`, `status`, `method`, `outcome` |
| `jvm_memory_used_bytes` / `jvm_memory_max_bytes` | gauge | JVM memory (Actuator built-in) |

### A metric family whose names come from class names

Spring Security registers one meter per filter in a security chain, **named after the filter class**:

```
spring_security_filterchains_LoginProtectionFilter_{before,after}_total
spring_security_filterchains_PrometheusScrapeAuthFilter_{before,after}_total
```

These are not names Runway chose, and they move when the class moves. **Renaming a security filter class
renames these production metrics**, which reads to a disappearance detector as an outage. If you alert on
series vanishing, exclude the `spring_security_filterchains_*` family, or accept that it will fire on an
ordinary refactor.

On the Runway side the names are pinned by a test (`SecurityFilterMetricNamesTest`) so a rename becomes a
deliberate decision rather than an accident — but the coupling is inherent to how Spring Security names these
meters, so treat them as fragile identifiers regardless.

### Reading `runway_edge_rejections_total`

This is the only signal for traffic the edge turned away. Those requests short-circuit at
`FilterOrder.CORE_PROTECTION` **without calling the chain**, so they never reach the access log at the other
end of the ladder — before this counter existed they were invisible everywhere.

The `reason` tag is what makes it actionable, because the status alone is not:

| `reason` | what it means | what to do about it |
|---|---|---|
| `rate-limit` | the per-IP token bucket throttled the request (`429`) | Usually benign in small numbers. Sustained volume from one source means either an abusive client or a threshold set too tight for a legitimate crawler. |
| `blocklist-manual` | an operator banned this address (`403`) | Expected. Nothing to investigate unless you did not expect it. |
| `blocklist-auto` | the limiter escalated repeated throttling into a temporary ban (`429` + `Retry-After` with the time remaining; `403` only for an indefinite one) | Worth reading. A rise here means clients are hitting the limit hard enough to be escalated — check whether a search crawler is being caught. |
| `blocklist-cidr` | a blocked range matched | Expected if you block ranges. |
| `unspecified` | the installed guard rejected without stating a reason | Only from a custom or older guard. |

**A rise in `blocklist-auto` or `rate-limit` for search-crawler traffic is an indexing-loss scenario**, not just
an abuse signal: a crawler that is throttled or banned stops fetching, and nothing else in the product reports
that. Pair this counter with the `runway.protect` log, which carries the client IP and the (untrusted, spoofable)
User-Agent for the same rejections.

Counts are exact even when log lines are throttled — see `OPERATIONS.md`.

**The series exist at `0` from startup.** Every `(status, reason)` pair the installed guard can produce is
registered when the process starts, so this metric is in the scrape from the first second of a clean boot.
That matters more than it looks:

- A panel shows `0`, not "No data". On a quiet night those two read very differently: one says *the guard is
  running and turning nobody away*, the other says *I don't know whether the guard is running*.
- The series can take part in **disappearance detection**. Previously it was absent until the first rejection
  — which on a healthy host can be days — so an `absent()`-style rule fired on a restart. The tempting
  workaround, suppressing that rule around restarts, is exactly wrong: **a rename ships inside a release and a
  release arrives at a restart**, so the suppression window is precisely when a real rename lands.
- `rate()` and `increase()` behave across the window containing first use, instead of starting mid-window.

Only combinations that can actually occur are registered — a `429` tagged `blocklist-manual` is never
published, because a permanent zero for something no code path can produce reads as evidence rather than as
silence. If you install a custom guard, it declares its own outcomes (`EdgeGuard#declaredOutcomes`); one that
declares nothing behaves as before, appearing on first use.


Health (`/actuator/health`, including the `storage` indicator) is a separate **probe**, not a scraped metric;
point your uptime check or systemd/HAProxy health check at it. The `up` metric Prometheus records for the scrape
target drives the `RunwayInstanceDown` alert.

## Per-site liveness (`/__health`)

The scrape endpoint above is **platform-wide**. To check that an individual tenant is serving, probe that site's
own host — per-site metrics cannot do this, because a site with no traffic and a site that is down look identical.

```yaml
# prometheus.yml — blackbox_exporter, one target per tenant host
- job_name: runway-sites
  metrics_path: /probe
  params:
    module: [http_2xx]
  static_configs:
    - targets:
        - https://acme.example.com/__health
        - https://nocturne.example.com/__health
  relabel_configs:
    - source_labels: [__address__]
      target_label: __param_target
    - source_labels: [__param_target]
      target_label: instance
    - target_label: __address__
      replacement: blackbox:9115
```

`200` = serving (`status` is `up` or `degraded`); `503` = not serving; `404` = the Host matches no site (check the
probe URL before assuming an outage). Alert on `down`, ticket on `degraded`. The probe is excluded from tenant
bandwidth accounting and from the per-site request timer, so polling it costs the tenant nothing.

For the cause (`suspended` / `maintenance` / `over_quota` / `platform_degraded`), send
`Authorization: Bearer $RUNWAY_HEALTH_TOKEN`; without it the body carries status only, on purpose.

## ⚠ The `outcome` label has DIFFERENT CASE in the two request-timer families

Both are exposed, under the same job, and a selector written in the wrong case renders **empty rather than
erroring** — the worst way for a monitoring query to be wrong. Someone who has just written a working query
against one family will get silence from the other and reasonably conclude there is no traffic.

| Meter | Tagged by | `outcome` values |
|---|---|---|
| `http_server_requests_seconds_*` (Micrometer's own) | route/URI | `SUCCESS`, `CLIENT_ERROR`, `SERVER_ERROR`, `REDIRECTION`, `INFORMATIONAL` — **UPPER** |
| `runway_http_server_requests_seconds_*` (Runway's per-site) | `site` | `success`, `client_error`, `server_error`, `redirection`, `informational` — **lower** |

This is a collision of two reasonable conventions, not a defect: the first follows Micrometer's `Outcome` enum,
the second is Runway's own per-tenant meter. Copy-pasteable, correct for each:

```promql
# per-SITE error rate (Runway's meter — lowercase)
sum by (site) (rate(runway_http_server_requests_seconds_count{outcome="server_error"}[5m]))

# per-ROUTE error rate (Micrometer's meter — UPPERCASE)
sum by (uri) (rate(http_server_requests_seconds_count{outcome="SERVER_ERROR"}[5m]))
```

If you want to be immune to the distinction, match case-insensitively — `outcome=~"(?i)server_error"` — at the
cost of a slower selector.

*The shipped dashboard and alert rules in this directory use the lowercase per-site form throughout, so they are
correct as-is. Normalising the two onto one convention would be a **breaking** change to every existing query,
so it is deliberately deferred to a future major rather than done quietly.*

