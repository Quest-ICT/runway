# Runway — Operations Runbook

> **Audience:** operators running Runway in production.
> **What this is:** day-2 operations — health checks, troubleshooting, tuning, backup/restore,
> and incident recovery. Installation and configuration are in
> [ADMINISTRATION.md](ADMINISTRATION.md).

> **Doc status.** These sections cover built behaviour and are written against the current release.
> `/actuator/health` (with a `storage` indicator) and `/actuator/prometheus` (JVM/HTTP + Runway platform and
> per-site meters) both ship, with a bundled Grafana dashboard + Prometheus alerts under `monitoring/` (**§2**).

---

## 1. Purpose & scope

This runbook is for whoever keeps a **live Runway deployment** running — reading its logs,
diagnosing failures, tuning it, taking and restoring backups, and recovering from incidents. It
assumes the platform is already installed and configured.

**In scope:** where logs go and what to grep for (**§3**); a symptom→cause→fix catalog for the
failures that actually happen (**§4**); JVM/heap sizing and the caches that matter (**§5**);
diagnosing plugin load and drift problems (**§6**); the operational backup/restore procedures
(**§7**); recovering from interrupted restores and lost configuration (**§8**); and upgrading and
rolling back safely (**§9**).

**Out of scope — see the sibling manuals:**

| You want to… | Read |
|---|---|
| Install / upgrade the platform, understand the config keys and deployment layout | [Administration](ADMINISTRATION.md) §3, §5, §8, §15 |
| Run an individual **site** (pages, users, that site's backups) | [Site Manager & Designer Guide](SITE-MANAGER-GUIDE.md) |
| Author a plugin | [Plugin Development](PLUGIN-DEVELOPMENT.md) |

Runway runs as **one JVM**, filesystem/YAML-backed (there is no database to operate). Everything
it writes lives under the version-independent data dir `/opt/runway-data/` (`external/`, `logs/`,
`backups/`). Keep that fact in mind — nearly every recovery step below is a file operation there,
not a database operation.

## 2. Health checks & readiness

Runway exposes Spring Boot's **`/actuator/health`** endpoint, reachable **without authentication** in
both normal and wizard mode (`/actuator/health` and `/actuator/health/**` are explicitly permitted; the
rest of `/actuator/**` is operator-only). It is your first readiness probe — wire it into the process
supervisor / load balancer:

```bash
curl -s http://127.0.0.1:8080/actuator/health    # {"status":"UP"} once the context is ready
```

`{"status":"UP"}` means the Spring application context started cleanly and the server is serving. Health is
more than liveness: a **`storage`** indicator checks that the external data directory (Runway's only
persistence — it is DB-less) is present and writable, so a lost or read-only data mount flips the status to
`DOWN` and your supervisor can act before writes start failing. The public probe shows only the composite
`UP`/`DOWN`; the per-indicator details (including the resolved path) are shown only to an authenticated
operator (`curl -u <app-admin> http://127.0.0.1:8080/actuator/health`).

For metrics, Runway also exposes a **Prometheus scrape endpoint at `/actuator/prometheus`** — unlike
health it is **operator-only** (an app superadmin/admin session; the rest of `/actuator/**` is gated the
same way). Alongside the JVM/HTTP metrics it carries the Runway platform meters (`runway_sites`,
`runway_sites_suspended`, `runway_sites_maintenance`, `runway_plugins_loaded`, `runway_logs_dropped`) and a
**per-site request timer**, `runway_http_server_requests_seconds_*`, tagged `site` and `outcome`
(`success` / `client_error` / `server_error` / …) — so request volume, latency and error rate can be read
per tenant. Route-level timing (`http_server_requests_seconds_*`, tagged `uri`/`status`) comes from
Actuator. Per-site data-transfer figures live on the bandwidth screens (`/__admin/bandwidth`, and the
all-sites `/__app/bandwidth`).

**Scraping with a token (unattended monitoring).** Rather than an operator session, set a bearer token so a
Prometheus server can authenticate non-interactively:

```yaml
# external application.yml (chmod 600 — the token is a secret)
runway:
  metrics:
    scrape-token: "<a long random string>"
```

The token grants **`/actuator/prometheus` only** (not the rest of `/actuator`) and is compared
constant-time; blank (the default) keeps the endpoint operator-only. The matching `prometheus.yml` job uses
`authorization: { type: Bearer, credentials: "<token>" }`. The release ships a **`monitoring/`** directory —
a Grafana dashboard (`runway-grafana-dashboard.json`), Prometheus alert rules
(`runway-prometheus-alerts.yml`), and a `README.md` with the full scrape snippet. Import the dashboard, point
`rule_files:` at the alerts, and tune the thresholds to your traffic.

> **Behind a reverse proxy — a scrape that 503s while `curl` gets 200.** If the proxy routes by an **exact
> `Host` match**, a Prometheus/VictoriaMetrics scraper can 503 while `curl` (same token) gets 200. Most often
> it's a **`Host`-header port mismatch**: the scraper is an **HTTP/1.1** client (VictoriaMetrics uses `fasthttp`)
> and, with a `host:443` target, sends `Host: runway.example.com:443` **with the port** — which an exact host
> rule (HAProxy `hdr(host) -i runway.example.com`) misses, because HTTP/2 normalizes the port away but HTTP/1.1
> does not, so it falls through to the proxy's default/other backend and 503s (HAProxy's `503 "No server is
> available"` if that backend is down). Less commonly, a **WAF** doing TLS-fingerprint (JA3/JA4) or bot
> filtering does the same to the Go client. Diagnose at the proxy (log the **backend matched** + the **`Host`
> sent**; `/actuator/health` is `UP` throughout). Fix **at the proxy**: make the host rule port-tolerant (match
> `host` *and* `host:443`, or `hdr(host),field(1,:)`), or allowlist the scraper IP / exempt
> `/actuator/prometheus`, or set the Prometheus target to the bare host without `:443`. A deployment-topology
> gotcha, not a Runway bug. See `monitoring/README.md`.

Backstops when you want more signal:

```bash
systemctl status runway            # "active (running)", not activating/failed/auto-restart-looping
journalctl -u runway -e            # the startup banner shows the bound port; no ERROR after it
curl -sI http://127.0.0.1:8080/    # a page responds (200/redirect/404 = the JVM is serving)
```

A `404` from that last `curl` still proves the JVM is **up and serving** — it only means no site is
bound to `Host: 127.0.0.1` and there is no default site (see **§4**). A refused connection, a `503` on
every host, or a `systemctl` state of `activating (auto-restart)` are the unhealthy signals.

> **Scope note:** health carries the composite status plus a **`storage`** indicator (external-dir
> presence/writability), and the release bundles a Grafana dashboard + Prometheus alert rules under
> `monitoring/`. Health is a **probe**, not a scraped metric — the `up` series Prometheus records for the
> scrape target is what the `RunwayInstanceDown` alert watches. See [KNOWN-LIMITATIONS.md](KNOWN-LIMITATIONS.md).

### 2.1 Per-site health (`/__health`)

`/actuator/health` is **platform-wide** — it answers "is the JVM up and is storage writable", and cannot tell you
whether one tenant is serving. For that, each site answers on **its own host**:

```bash
curl -sS https://<tenant-host>/__health
{"status":"up","time":"2026-08-19T10:00:00Z"}
```

`status` is one of **`up`**, **`degraded`** (serving, but something needs attention) or **`down`** (not serving
public traffic). HTTP status mirrors it: `200` for up/degraded, `503` for down, `404` if the Host matches no site.

**Why the probe still answers when the site is off.** It runs *outer* of the filters that block a tenant
(suspended / maintenance / over-quota / degraded platform floor), so it **reports** those states instead of being
short-circuited by them. That is the whole point: a health check that goes down with the thing it monitors is
worse than none.

**Detail (operator-only).** Set `runway.health.token` in the external `application.yml` (it is a secret; that file
is already `600`) and pass it to see the cause:

```bash
curl -sS -H "Authorization: Bearer $TOKEN" https://<tenant-host>/__health
{"status":"down","state":"suspended","site":"acme","checks":{"content":"skipped","admin":"ok"},"time":"..."}
```

`state` is `active | maintenance | suspended | over_quota | platform_degraded`. It is withheld from the public
response on purpose: "suspended" is an operator's business decision about a tenant, and Runway should not publish
a stable, scrapeable field asserting it. **A wrong or absent token returns the public shape — never a `401`** — so
the probe can neither become a credential oracle nor turn a token typo into a false outage. The HTTP status is
identical either way, so your alerting never depends on whether the token was accepted.

**Three things worth knowing before you alert on it:**

- **`degraded` never means "down".** It fires when the tenant's own console is quarantined (a broken `roles.yml`
  blocks `/__admin` only — visitors are unaffected) or when a template looks missing. Page on `down`; open a
  ticket on `degraded`.
- **Two facts lag, by design.** `over_quota` is recomputed on the bandwidth metering cadence (default 60 s), so it
  can trail by about that. `admin: quarantined` is a **start-up** snapshot — a repaired `roles.yml` keeps
  reporting quarantined until the next restart.
- **The probe is unmetered.** It is excluded from the tenant's bandwidth accounting and from the per-site request
  timer, so your own monitoring can never consume a tenant's quota or distort its traffic graph.

> **Editing `site.yml` by hand does not take effect until restart — and it fails *silently*.** Site records are
> read through a bounded cache that is invalidated when the platform itself writes a site (console actions,
> backup restore, site creation). A file edited **out of band** is not noticed, so setting `maintenance: true` or
> `status: SUSPENDED` directly in `sites/<id>/site.yml` appears to do nothing: the site keeps serving and
> `/__health` keeps reporting `up`, with no error anywhere. This applies to **every** field in the record — hosts,
> locales, status, maintenance — not just the ones above.
>
> **The fix: reload the site.** Edit the file, then:
>
> ```bash
> curl -sS -X POST -H "Authorization: Bearer $RUNWAY_RELOAD_TOKEN" \
>      http://127.0.0.1:8080/__reload/site/<id>
> {"reloaded":"<id>","time":"…"}
> ```
>
> This re-reads **everything** for that site — the site record, per-site plugin config, icons and the web
> manifest, templates, pages, i18n, SEO config, the user/role/grant stores and the content catalog — and in a
> cluster it repeats to peer nodes. `POST /__reload/sites` does every site. A restart still works and is
> equivalent; the endpoint just saves you one.
>
> **Verifying a reload — two ways to get a false result.** Both look like a clean answer:
>
> 1. **Assert the change is NOT yet visible *before* you reload.** A test that only checks the value *after*
>    reloading cannot distinguish "the reload worked" from "the value was already there" — it passes just as
>    happily against a broken reload. Fetch the artefact first, confirm it is stale, then reload, then fetch
>    again.
> 2. **The token is required, and omitting it fails quietly in the shape of a successful test.** A missing or
>    wrong token returns `{"error":"invalid token"}` and the change is **not** applied — so a verification that
>    then looks for the edited value correctly finds it absent and reports a *regression in the product*, when
>    the real fault was the missing header. Check the response body of the POST, not just the artefact
>    afterwards.
>
> Verify against a **generated artefact** — `/robots.txt`, the web manifest, a rendered page — rather than an
> in-memory value. The stale value a reload clears lives in a cache *in front of* the config object, so a
> test that asserts on the object itself can pass while the site still serves the old value.
>
> Enable it by setting `runway.reload.token` in the external `application.yml` (see `ADMINISTRATION.md` §12.2).
> Arming it costs **one** restart; after that, edits are restart-free. It is **loopback-only** — a non-loopback
> caller gets a `404`, so there is no firewall rule to add and no exposure decision to make — and it is **absent
> entirely** until a token is set.

**A cold probe from one IP can still be rate-limited.** The DoS floor runs before the site is even resolved and
throttles by IP alone, so a very aggressive poll may see `429`. Poll at a sane interval, or allowlist the
monitoring IP in the protection plugin.

> **Do not probe with `curl -I`.** A `HEAD` is answered correctly here, but for the *global* endpoints a HEAD can
> mask state-dependent behaviour (a HEAD creates no session). When a header depends on request state, verify with
> the request shape that creates that state.

## 3. Logs

Runway logs through SLF4J/Logback with **Spring Boot's defaults** — no `logging.file.*` keys are
set, so **everything goes to the console (stdout/stderr)**. Where that console output ends up
depends on how the process was started:

| Started by | Log destination | Follow it with |
|---|---|---|
| **systemd** (the normal case, `runway.sh -f`) | **journald** | `journalctl -u runway -f` |
| **launcher, detached** (`runway.sh` by hand) | `logs/runway.out` in the deployment root | `tail -f /opt/runway-data/logs/runway.out` |
| **launcher, foreground** (`runway.sh -f` by hand) | your terminal | — |

Under systemd the console stream is the source of truth; `journalctl` is where you look. The
deployment-root `logs/` directory (`/opt/runway-data/logs/`, symlinked in as `/opt/runway/logs`)
is written **only in detached launcher mode** — the supervisor appends the JVM's stdout/stderr to
`logs/runway.out` and prints its own relaunch lines there. Under systemd `logs/runway.out` is
usually empty; do not go looking for application logs there when running as a service.

**The public access log.** One line is emitted per **public** request under the logger
`runway.access`, from the innermost filter (so the status is final):

```
203.0.113.7 GET /about -> 200 (12ms)
   client-ip  method  path  status  elapsed
```

It is **public traffic only** — it deliberately **skips** the internal/operator surfaces: any path
under `/__…` (the `/__app` operator console, `/__admin` site console, `/__plugin/**` assets,
preview, CSP-report), plus `/app/…` and `/actuator`. So an operator signing in or a plugin asset
fetch will **not** appear here; that is by design (this is a traffic log, not an audit trail). The
client IP is the gated one (from a trusted forwarded header only — see Administration §7), never a
raw spoofable header.

**What to grep for** (systemd, hence `journalctl`):

```bash
# access log — public traffic, errors, one client's activity, slow requests
journalctl -u runway | grep 'runway.access'
journalctl -u runway | grep 'runway.access' | grep -E '\-> (4|5)[0-9][0-9] '
journalctl -u runway | grep 'runway.access' | grep '203.0.113.7'
journalctl -u runway | grep 'runway.access' | grep -oE '\([0-9]+ms\)' | sort -t'(' -k2 -n | tail

# startup mode: which realm layer came up
journalctl -u runway | grep -iE 'WIZARD|Started .*Application|Tomcat|Jetty .* started|LiveReload'

# problems
journalctl -u runway -p err              # ERROR level only
journalctl -u runway | grep -iE 'plugin'                         # plugin load / block / skip
journalctl -u runway | grep -iE 'restore|backup|recovered'       # backup/restore + roll-forward
journalctl -u runway | grep -iE 'app-master|superadmin|realm .*refus'  # realm integrity refusal
```

(For a detached hand-run instance, replace `journalctl -u runway` with
`cat /opt/runway-data/logs/runway.out`.)

**Retention.** journald retention is the host's journald config (`journalctl --disk-usage`,
`--vacuum-*`). `logs/runway.out` is **not** rotated by Runway — if you run detached long-term, put
it under `logrotate` yourself.

**Writing a log file as well.** You may set Spring Boot's own keys in the external `application.yml` (restart to
apply); the console output keeps going to journald either way:

```yaml
logging:
  file:
    name: /opt/runway-data/logs/runway.log   # this exact file; or instead:
    # path: /opt/runway-data/logs            # a file named spring.log in this folder
  logback:
    rollingpolicy:
      max-file-size: 10MB     # start a new file at this size (Boot's default)
      max-history: 7          # keep this many days of old files (Boot's default)
```

Set `name` **or** `path`; with both, `name` wins. Old files are rotated and compressed by Spring Boot according to
`logging.logback.rollingpolicy.*`. Under the installed systemd unit the service can write only inside
`/opt/runway-data` (Administration §6), so put the file there: a path elsewhere is read-only to the service, unless you add it to
`ReadWritePaths` in a drop-in.

**More detail from one part of the server.** The quickest way is the console: **Activity → Logs → Log levels**
(`/__app/logs/levels`, platform administrators) changes a logger's level at once, keeps it across restarts and
records the change in the activity log; *Default* puts it back. Or raise a single logger with
`logging.level.<logger>` in the same file (restart to apply; a level set there wins over the console, which then shows
it locked), or for one run with `RUNWAY_OPTS=--logging.level.<logger>=DEBUG`:

```yaml
logging:
  level:
    mx.quest.runway.head: DEBUG     # the public-page edge (CSP stamping); any mx.quest.runway.* package works
    runway.access: WARN             # silence the per-request access lines
```

Runway's named loggers are `runway.access` (one line per public request, below) and `runway.protect` (edge
rejections, §3.1); everything else logs under its package, `mx.quest.runway.*`. Put the level back afterwards:
`DEBUG` on a busy server writes a great deal.

### 3.1 Seeing what the edge rejected

A request the protection edge turns away is rejected at `FilterOrder.CORE_PROTECTION` — the outermost slot —
and the chain is never called. The access log lives at the innermost slot. So a `403`/`429` from the edge
**appears in no other log**, and without a log of its own a site manager would have no way to tell that a
crawler was being throttled.

Rejections therefore write to the `runway.protect` logger:

```
edge reject 429 203.0.113.9 GET /products reason=rate-limit ua=Mozilla/5.0 (compatible; Googlebot/2.1; …)
edge reject 429 198.51.100.4 GET /login reason=blocklist-auto ua=- (+142 suppressed in the last 60s)
```

- **`reason`** is the diagnostic field. It distinguishes *you banned this address* (`blocklist-manual`) from
  *the limiter escalated it after repeated throttling* (`blocklist-auto`) — two situations that call for
  opposite responses.

#### What each rejection answers on the wire

| Reason | Status | `Retry-After` | Why |
|---|---|---|---|
| `rate-limit` | `429` | `1` | The token bucket is empty. Slow down and continue. |
| `blocklist-auto` | `429` | seconds remaining on the block | An auto-block **is** the rate limiter escalating — back-pressure, not a policy decision — so it says exactly how long is left. |
| `blocklist-manual` | `403` | — | An operator ban genuinely *is* "forbidden". |
| `blocklist-cidr` | `403` | — | Same: a range ban is a decision, not back-pressure. |

> **Why an auto-block is not a `403`.** A `403` with no `Retry-After` reads to a crawler as *forbidden,
> plausibly permanently* — for what is by default a **fifteen-minute** time-out. A crawler that concludes it is
> banned stops coming back, and pages quietly leave the index.
- **`ua`** is the declared User-Agent, sanitised and truncated. It is **untrusted, spoofable client input**:
  useful for telling a crawler from a browser, never evidence of identity.
- **`(+N suppressed …)`** — repeat lines for the same *(IP, reason)* pair are throttled to one per window
  (`runway.protect.log-rejection-window-seconds`, default 60). Logging every rejection would make the guard an
  amplifier of the flood it is stopping. **The `runway_edge_rejections_total` metric counts every rejection
  regardless**, so the totals stay exact even when the lines do not.
- **The counter reads `0`, it does not disappear.** Every rejection the active guard can produce is registered
  at zero when the process starts, so `runway_edge_rejections_total` is present in a scrape from the first
  second — a dashboard shows `0` rather than "No data", and the series can take part in disappearance
  detection.

> **Never read line counts as volume — the ratio is far larger than "throttled" suggests.** Measured from two
> independent clients on different networks:
>
> | rejections | log lines | ratio |
> |---|---|---|
> | 44 | 1 | 44 : 1 |
> | 86 | 2 | 43 : 1 |
>
> Both came from *trivial* 200–250 request tests. The suppression is per *(IP, reason)* pair, so the ratio grows
> with the size of the flood — **it undercounts hardest exactly when volume is highest**, which is when you are
> most likely to be reading the log. Read `runway_edge_rejections_total`; it reconciled to the unit against both
> clients' own request counts.

**How long does an auto-block last, and does it survive a restart?** An auto-block (`reason=blocklist-auto`)
is applied when one address breaches the rate limit `breach-threshold` times inside `window-seconds`
(defaults: **60 breaches in 60 s**), and it lasts `block-seconds` — **default 900 s (15 minutes)**.

**Auto-blocks are in-memory only and are cleared by a restart.** Only *manual* operator blocks are persisted
(to `external/blocklist.yml`); an auto-block is ephemeral by design and is simply re-earned by an address that
is still abusive. So restarting the service does release an auto-blocked visitor — but it releases every other
one too, and a genuinely abusive client re-earns its block within seconds.

**Blocking an address by hand.** The **Block** form on `/__app/dos` (the superadmin, a wildcard admin, or an
operator granted **Configure protection**) takes:

- **IP or CIDR** — one address (`203.0.113.7`) or a range (`198.51.100.0/24`). Type it carefully: the form does not
  check that it is a valid address or range.
- **Seconds** — how long the block lasts; `900` (15 minutes) if left out. `0` or a negative number blocks
  **indefinitely**, until you press *Unblock*.
- **Reason** — free text shown in the list; blank records `manual`.

The form refuses **your own current address**, typed exactly, and any address the protection allowlist exempts
(loopback included); an allowlisted address is never blocked. It does **not** check whether a range contains your
own address, and the block applies to the console too: a range that covers you locks you out of `/__app` until it
ends, someone else unblocks it, or you remove it from `external/blocklist.yml` and restart. A blocked address gets
`403` with no `Retry-After` (`blocklist-manual` for one address, `blocklist-cidr` for a range, in the table above).
Manual blocks are written to `external/blocklist.yml` and survive a restart. A single address's block stops
applying when its time runs out. **A range's block keeps applying after its time has run out** until the blocklist
is next shown on `/__app/dos` (opening the screen removes every ended block, from the file too) or the server
restarts. *Unblock* removes a block at once.

**"Nothing is blocked" is not the same as "nothing was blocked."** An auto-block lasts fifteen minutes, so an
operator investigating a slowdown from earlier in the day arrives to an empty blocklist — the evidence expires
at exactly the rate that makes it hardest to catch. `/__app/dos` therefore shows **Recently ended blocks**:
every block that has expired or been lifted since this node started, with its address, its reason and when it
ended. Newest 100, **in memory, per node, cleared on restart** — a diagnostic, not an audit trail. For a
durable record use the `runway.protect` log.

**A rejection carries no site id.** It happens before `CORE_SITE_CONTEXT`, so no tenant has been resolved yet
and it cannot appear in a per-site log — only the platform log. That is a consequence of the ordering that
makes the protection floor absolute, not an oversight.

**What is still not answerable.** The edge sees the request before the application does. If you need to know
why a *served* request produced a given status, that is the access log's job — and the access log records the
User-Agent only when `runway.logs.access-log-user-agent` is enabled (off by default, because it is one line per
public request).

## 4. Common failures & fixes

A symptom → cause → fix catalog for what actually goes wrong. Confirm each fix with the check shown.

### 4.1 Service won't start — `status=203/EXEC` (SELinux mislabel)

**Symptom.** `systemctl status runway` shows `activating (auto-restart)` looping, and
`journalctl -u runway` shows `... runway.sh ... status=203/EXEC` — a "Permission denied" on exec
even though `ls -l` shows the launcher is `0755`.

**Cause.** The self-extracting `.bin` unpacks into a *user-temp* dir; a plain copy carries the
`user_tmp_t` SELinux context onto the install dir, and an **enforcing** RHEL-family host then
refuses to let systemd exec the launcher. This is silent and baffling — the file looks perfectly
runnable.

**Fix.** Relabel the release and data dirs, then restart:

```bash
sudo restorecon -RF /opt/runway-<version> /opt/runway-data
sudo systemctl restart runway
```

The installer already runs `restorecon` for you; you only hit this if you laid or moved files by
hand. **Confirm:** `systemctl status runway` → `active (running)`; `sestatus` shows the mode. (See
Administration §16 for the full hardening context.)

### 4.2 Service won't start — "Multiple candidate jars" / "Jar not found"

**Symptom.** The launcher aborts with
`[runway] Multiple candidate jars under /opt/runway — set RUNWAY_JAR explicitly:` and two paths, or
with `[runway] Jar not found:`.

**Cause.** The launcher auto-selects the **single** `runway-app-*-exec.jar` under `bin/` (the
runnable Spring-Boot jar; the plain `runway-app-*.jar` is the thin library jar, not runnable). More
than one `-exec` jar in `bin/` (e.g. a leftover from a hand-copied upgrade) is ambiguous and it
refuses to guess; zero means none was found.

**Fix.** Leave exactly **one** `runway-app-*-exec.jar` in `bin/` — delete the stale one:

```bash
ls -1 /opt/runway/bin/runway-app-*-exec.jar     # expect exactly one line
sudo rm /opt/runway/bin/runway-app-<old>-exec.jar
sudo systemctl restart runway
```

(Or pin it explicitly with `RUNWAY_JAR=` in the unit's `Environment=`.) The clean way to upgrade is
to **re-run the installer** (**§9**), which lays each version in its own dir and never leaves two
jars side by side. **Confirm:** the startup log prints one `[runway] RUNWAY_JAR=...` line and the
service is `active`.

### 4.3 A tenant host 404s (no site bound and no default site)

**Symptom.** A browser (or `curl`) hitting a hostname returns **404** for every path, but the JVM
is up and other hosts work.

**Cause.** Host resolution found **no site bound** to that `Host` header **and** there is **no
default (catch-all) site** configured — so the request resolves to "no site", which is a 404. This
is the deliberate pre-default behaviour: an unmatched host with no catch-all is a 404, not a leak
into some other tenant.

**Fix.** Either bind the hostname to the intended site, or set a **default site** so unmatched hosts
fall back to it. Set the default on the operator console **Settings** screen (`/__app/settings`) —
see Administration §8.1 and §10. Note the edge cases: an explicitly-bound host always wins over the
default; a **suspended** default stops catching unbound hosts (they 404 again) until it resumes;
deleting the default clears the setting. **Confirm:** re-request the host — it now serves the bound
or default site's page. (The wizard-seeded first site is marked default automatically, so a
fresh, properly-set-up deployment does not hit this.)

### 4.4 Stuck in WIZARD mode after setup (missing `app-master-id` pin)

**Symptom.** After first-run setup, every path still redirects to `/setup` and the log shows the
platform came up in **WIZARD** mode — the operator console `/app/login` is unreachable.

**Cause.** WIZARD vs ACTIVE is **derived** from one fact: whether `runway.security.app-master-id`
is set in `external/application.yml`. Blank/absent ⇒ WIZARD. If that file is missing, unreadable by
the service user, or the pin key was lost (a bad hand-edit, an `external/` restored from a
snapshot that predates setup, or the wizard's write never landed), the platform boots straight back
into WIZARD.

**Fix.** Confirm the file and the pin:

```bash
ls -l /opt/runway-data/external/application.yml     # must exist, -rw------- runway runway
sudo -u runway grep app-master-id /opt/runway-data/external/application.yml
```

- If the file/pin is genuinely **absent**, just **re-run the wizard** — reach any URL (it redirects
  to `/setup`) and create the administrators again; it re-writes `application.yml` with the pin and
  relaunches into ACTIVE.
- If the file exists but is owned/moded wrong so the service user can't read it, fix perms
  (`sudo chown runway:runway …; sudo chmod 600 …`) and restart.
- If you have a good copy of the pin from a backup of `external/`, restore that `application.yml`
  (see **§8.2**) and restart.

**Confirm:** `journalctl -u runway -e` shows a startup **without** WIZARD, and `/app/login` renders.
(See Administration §4 and §8.)

### 4.5 A plugin shows **Blocked** (unmet `requires:` / a `conflicts:` rival)

**Symptom.** On the **Plugins** screen (`/__app/plugins`) a plugin row carries a **Blocked** badge
and its feature is absent, even though the plugin is enabled and well-formed.

**Cause.** The plugin's declared requirements aren't satisfied: an unmet `requires:` (the target is
missing, disabled, the wrong version, or itself Blocked), or a `conflicts:` rival is installed and
enabled. Blocking **cascades** — anything that requires a Blocked plugin is Blocked too. The exact
reason is shown on the row.

**Fix.** Read the row's reason, then satisfy it — install/enable/upgrade the required target, or
remove the conflicting plugin — and **restart** to apply. See **§6** for the full plugin
troubleshooting flow. **Confirm:** after the restart the badge reads **Loaded**.

### 4.6 An essential plugin is missing/broken — tenant hosts serve `503`

**Symptom.** Every **tenant** host returns a plain-text `503` ("This site is temporarily
unavailable."), but the **operator console** (`/__app`, `/app/login`, and its password recovery
`/app/forgot-password`) is still reachable.

**Cause.** An **essential** (security-floor) plugin failed to load or arm. The kernel refuses to
serve sites *without* their security floor — "unavailable over insecure" — so it fails **closed**
for tenant traffic while deliberately keeping the app realm reachable so you can fix it. Today the
essential set is exactly `protection` (Administration §11, §12).

**Fix.** See **§6** — the log line and the plugin's row on the still-reachable **Plugins** screen say why
and what to do (usually: put back the protection copy bundled with the running release), then restart. **Confirm:** tenant hosts serve pages again (no `503`).

### 4.7 Port-binding problems

**Symptom.** Startup fails with an "address already in use" bind error, or `EACCES`/permission
denied binding the port.

**Cause.** Another process holds the port, or Runway was pointed at a **privileged** port
(<1024) without the capability to bind it — the sandboxed unit does not grant `CAP_NET_BIND_SERVICE`
by default.

**Fix.**

```bash
sudo ss -ltnp | grep ':8080'        # who holds it (default port is 8080)
```

- **In use:** stop the other process or move Runway with `RUNWAY_OPTS=--server.port=N` in the unit's
  `Environment=`.
- **Privileged port:** the default is a **high port behind a proxy**. If Runway must bind
  80/443 itself, give it `CAP_NET_BIND_SERVICE` in a drop-in (`runway.service.d/40-net-bind.conf`, Administration
  §6), then `systemctl daemon-reload && systemctl restart runway`. Prefer the drop-in to uncommenting the lines in
  `/etc/systemd/system/runway.service`: the installer rewrites that file on every upgrade. It does keep the
  capability when the unit it replaces grants it, but any other edit you made there is lost.

**Confirm:** `ss -ltnp | grep runway` shows it listening on the intended port; `curl -sI` returns a
response.

### 4.8 Self-managed TLS won't start (keystore problems)

**Symptom.** With `runway.tls.enabled: true`, startup fails immediately with an
`IllegalStateException` naming `runway.tls.keystore` / `runway.tls.keystore-password`, or — a step
later — Jetty fails opening the keystore.

**Cause.** The fail-fast validation caught an inconsistent TLS enablement: the keystore path is
empty, the file is missing/unreadable **by the service user**, or the password is blank. If those
pass but Jetty still fails, the password is wrong or the alias (`runway.tls.key-alias`, default
`runway`) doesn't match the keystore entry.

**Fix.** All TLS settings live in the external `application.yml` (chmod 600). Check in order:

```bash
sudo -u runway test -r /path/to/runway.p12 && echo readable    # as the SERVICE user
keytool -list -keystore /path/to/runway.p12 -storetype PKCS12  # password + alias check
```

Fix the path/ownership/password/alias and restart. TLS is optional — if you're stuck, set
`runway.tls.enabled: false` and fall back to the reverse-proxy topology (Administration §7) while
you sort the keystore out. A related trip-wire: `runway.tls.redirect-http.enabled: true` **without**
`runway.tls.enabled: true` also fails fast (the redirect would point at an HTTPS listener that
doesn't exist).

**Confirm:** the startup log prints `TLS terminates HERE: HTTPS on port …`, and
`curl -skI https://<host>:<port>/ | head -1` answers.

### 4.9 The operator console answers 404 (`/app/login`, `/__app`)

**Symptom.** `/app/login`, `/__app` or the console's password recovery answers **404** (the site's "not found"
page), while the site's own pages and `/__admin` on the same host name work. Often started after you gave a site
a new host name or changed the default site.

**Cause.** The console answers only on the default site's host names and on host names that serve
no site, or, when `runway.console.hosts` is set, only on the names listed (Administration §8.6). The host name you
used serves another site, or is not in the list. The log says which: one `INFO` line per refused host name,
*"Operator console refused on host '…' (it serves the site '…' …)"*:

```bash
journalctl -u runway | grep 'Operator console refused'
```

**Fix.** Sign in on a host name that works: the server's IP address (`http://<server-ip>:8080/app/login` from
inside your network), `localhost` through an SSH tunnel (`ssh -L 8080:127.0.0.1:8080 <server>`, then
`http://localhost:8080/app/login`), or the default site's host name. To use a particular name, list it in
`external/application.yml` and restart:

```yaml
runway:
  console:
    hosts: [ops.example.com, 10.0.0.5]
```

Once that key is set, **only** the listed names work, so include every name you sign in with. If a wrong list locks
you out, the file is the way back: correct or remove the key and restart. Behind a proxy, check it passes the
visitor's host name on (Administration §7): Runway can only compare the name it receives. **Confirm:**
`curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: <name>' http://127.0.0.1:8080/app/login` prints `200`.

## 5. Performance & tuning

Runway is a single JVM and light on CPU; the dimension worth thinking about is **heap vs the
password-hashing memory interlock**.

### 5.1 Heap sizing and the Argon2id interlock

The shipped heap is `-Xms256m -Xmx1g` (`JAVA_OPTS` in the unit / launcher default), which suits a
small VM. The **hard floor** comes from password hashing: Runway hashes passwords with **Argon2id**,
whose memory cost is **19 MiB** (`STANDARD`) or **32 MiB** (`HIGH`) *per concurrent hash*
(`runway.security.password-strength`, Administration §8/§8.1). To stop a login flood from
exhausting heap, concurrent hashes are **bounded** by `runway.security.max-concurrent-hashes`
(default `8`); a request that can't get a slot within `runway.security.acquire-timeout-millis`
(default `2000`) fails "busy" rather than piling on memory.

The interlock to respect:

```
peak hashing memory ≈ max-concurrent-hashes × per-hash-cost
                     = 8 × 19 MiB  = 152 MiB   (STANDARD)
                     = 8 × 32 MiB  = 256 MiB   (HIGH)
```

Keep `-Xmx` **comfortably above** that figure plus normal working set. The shipped `1g` heap
absorbs the `256 MiB` HIGH ceiling with room to spare. If you **raise** `max-concurrent-hashes` for
a busier login surface, or switch to `HIGH`, raise `-Xmx` in lockstep — do **not** lower
`max-concurrent-hashes` to save memory on a login-heavy host without also accepting more "busy"
rejections. This is a floor you tune *with*, not away.

**Tune** with the installer's `--heap` (it writes the drop-in `runway.service.d/20-heap.conf`), or set
`Environment="JAVA_OPTS=..."` in a drop-in of your own — not in the main unit, which the installer rewrites on every
upgrade (Administration §15, *Memory tuning*). Keep the whole assignment quoted — systemd splits on whitespace, so an
unquoted multi-flag value drops flags — then `systemctl daemon-reload && systemctl restart runway`. On a very small host, `-Xmx512m` is viable at
`STANDARD` with the default cap. **Confirm** the flags took: `journalctl -u runway | grep -i Xmx`
(the JVM banner), or `ps -o args= -C java`.

### 5.2 The i18n and template caches

Two caches shape latency and reload behaviour:

- **Per-site i18n bundles** (the site's and every plugin's `messages*.properties`) are cached, and
  every public page request first checks whether any of those files changed on disk (one `stat` per
  file, like the template check below; a changed file is re-read once). So a message edit shows on the
  next page view, under a new ETag. Other lookups (error pages, a plugin's background text) re-check at
  most every `runway.i18n.cache-seconds` (default `5`; `0` = on every lookup). *Why per request:* the
  page ETag describes the bundles, and a bundle cache that lagged behind it — as the old 5-second
  re-read did — served the old text under the new ETag, which browsers and CDNs then kept.
- **The Thymeleaf template cache is ON** (in every mode — there is no cache-off dev profile). A
  template changed **through the console** is picked up by targeted, event-driven eviction rather than
  a global re-parse, so the change applies on the next request. A template file changed **on disk**
  (edited on the server, copied in by a script) applies on the next request too: a cached parse is
  used only while its `.html` and `.th.xml` are unchanged (size, modification time and inode, checked
  on every use; a file modified in the last few seconds is also compared by content, because two quick
  same-size edits can share a timestamp). The same holds for a page's `.meta.yml`, for the site's masters
  and fragments (`_nav`, `_head`, …, which every page's ETag folds too) and for plugin
  templates. *Why:* the page ETag folds those files' bytes on every request, so it moves the moment
  a file changes — a cached parse that did not move with it would put the old body under the new ETag,
  and browsers and CDNs would keep that old body. A site reload (Administration §12.2) and **Clear
  template cache** (Administration §8.1) remain for everything else a reload refreshes. This is a
  deliberate dev/prod-parity decision:
  templates are always cached, so a page renders identically in test and production and there is no
  "works in dev, breaks packaged" cache-only class of bug.

The practical consequence for operations: **content and translation edits do not need a restart** —
console edits apply at once, out-of-band template and message edits apply on the next request, and other
out-of-band file edits (`site.yml`, `seo.yml`, `manifest.yml`, icons, `params.yml`, `widget-instances.yml`, plugin
config) take a site reload — and until that reload the pages keep both their old content and their old ETags
(Administration §12.2; on a node with more sites than those per-site caches hold — 512 sites, 256 for widget config,
512 site × plugin settings pairs — an evicted entry is re-read earlier, page and ETag together);
**config, security, plugin, and code changes do** (Administration §8, §11).

### 5.3 GC on small hosts

Stay on the JVM default collector unless you have a measured reason not to; at these heap sizes the
default is fine. If you must trim footprint on a tiny box, prefer lowering `-Xmx` (respecting §5.1)
over hand-picking a collector.

## 6. Plugin troubleshooting

The **Plugins** screen (`/__app/plugins`) is the operator surface for plugin state. Its model is
**restart-to-apply**: uploading, enabling, disabling, and uninstalling change only the
`external/plugins/` tree on disk; **nothing takes effect until you restart**. A malformed, disabled,
or blocked plugin is logged and skipped — the platform always boots ("degrade-don't-crash").

**The drift / restart-required banner.** Because changes apply on restart, the screen shows a
**restart-required** banner whenever the on-disk state differs from what is currently running (a
newly uploaded, re-enabled, or disabled/removed-but-still-running plugin). The banner clears after
you restart and the running set matches the disk. Use the console's **restart** action, or
`systemctl restart runway`.

**Status badges** (first match wins — full table in Administration §11):

| Badge | Meaning / action |
|---|---|
| **Loaded** | Running and the disk still wants it — steady state. |
| **Pending** | Drift — restart to apply. |
| **Blocked** | Enabled and well-formed but its requirements aren't met (unmet `requires:`, or a `conflicts:` rival). The **exact reason** is on the row. |
| **Disabled** | Operator-parked (`enabled: false`) — won't load. |
| **Malformed** | `plugin.yml` missing/unparsable/invalid, or id ≠ folder name. Reason on the row. |

**Reading and clearing a Blocked row.** The reason names the unmet requirement. Blocking **cascades
transitively** — a plugin that `requires:` a Blocked one is itself Blocked — and a dependency target
shows **"Required by: …"** so you can see what will break if you remove it. To clear it:
install/enable/upgrade the required target (matching the version constraint — `id`, `id >= v`, or
`id == v`), or remove the conflicting rival; then **restart**. **Confirm:** the badge reads
**Loaded**.

**Essential-floor fail-closed (`503` on tenant hosts).** A small, **code-defined** set of plugin ids
is **essential**: their failure to load puts the platform into **fail-closed degraded mode** instead
of booting open. Today that set is exactly **`protection`**. A plugin cannot promote *itself* — the
descriptor's `essential: true` is advisory and ignored for any id not on the kernel's list. When an
essential plugin is missing or fails to arm:

- **tenant hosts get a plain `503`** ("This site is temporarily unavailable.") — served *without*
  the missing security floor is refused;
- **the application realm stays reachable** (`/__app`, `/app/login`, `/app/logout`, `/app/forgot-password`,
  `/app/reset`, `/actuator`) on every host the console answers on (ADMINISTRATION §8.6), so you can log in and
  fix it.

**Recover:** read why — the boot log's `essential plugin 'protection' is installed but failed to load`
line (`journalctl -u runway | grep -i 'essential plugin'`) and the plugin's row on the still-reachable
**Plugins** screen both carry the reason and the fix. The usual cause is a protection folder that is not the
copy shipped with the running release (a hand-copied kernel jar, or a hand-set `enabled: false`); the fix is
that copy, which the installer keeps in the release directory (after a rollback it is the older release's,
the one that kernel needs):

```bash
sudo cp -R /opt/runway/seed/plugins/protection/. /opt/runway-data/external/plugins/protection/
sudo chown -R runway:runway /opt/runway-data/external/plugins/protection
sudo systemctl restart runway
```

Or re-run the running release's installer (`sudo ./runway-<version>.bin -y`, every upgrade refreshes it). After
replacing the kernel jar by hand (`seed/` is then the earlier release's), take the copy from the `.bin`:
`./runway-<version>.bin --extract DIR` and copy `DIR/runway-<version>/seed/plugins/protection/` over
`/opt/runway-data/external/plugins/protection/`. The console cannot **Disable** an essential plugin, and a disabled one fails closed too.
While degraded, the app realm's own edge rate-limit
and login lockout fall back to permissive kernel defaults, so keep the app realm behind your proxy /
bound to localhost until recovered (Administration §12). **Confirm:** tenant hosts stop returning
`503`.

> **The bundled plugin.** The essential **`protection`** plugin is bundled in the `.bin` and seeded into
> `external/plugins/protection/` on a fresh install and refreshed on every upgrade (it is lockstep with the
> kernel), so the `503` fail-closed path is armed out of the box — a protection folder that is present but does
> not load (refused, disabled, malformed) is what trips it. Removing the folder does not trip it; it runs the
> sites without abuse defense until the next installer run seeds it again. It is the only bundled plugin; any
> other plugin (for example `search`) is installed by the operator
> (Administration §11–§12).

## 7. Backup & restore procedures

Each site backs up as a **complete, self-contained `.zip`** — its data, presentation, users, roles,
permissions, and plugin config — so it restores on any platform version and renders identically. The
full description of what's in a backup, what's excluded (isolation guarantees), and restore safety
is in **Administration §13**; the site-manager end-user workflow is in **Site-Manager guide §12**.
This section is the operational how-to.

**Who takes them.** Backups are a **per-site (site-manager)** feature: the site's administrators (its
superadmin or the Administrator tier) take, download, and restore **their own** site's backups from the tenant
console **Backups** (`/__admin/backups`). A user only *granted* *Manage backups* may save backups on the server
and download **transfer copies**, but not download a full backup (it carries password hashes and plugin secrets)
or restore — both answer `403` (Administration §13).

**The operator-realm view** is the same thing for **any** site: `/__app/backups`, restricted to platform
superadmins because these are cross-tenant operations. It lists every site with its stored backups, and
can save a backup on the server, download a fresh one, restore a site from an upload or from one of its
stored backups, delete a stored backup, and **create a new site from an uploaded backup** — cloning or
migrating a site under a new id and hosts.

> **Transfer copy.** The operator view has a second download button, **transfer copy**
> (`/__app/backups/<siteId>/download-transfer`). A backup is complete on purpose — the site's sub-users
> and their password hashes, its per-plugin credentials, the hosts it answers on — and that completeness
> becomes a liability the moment the archive goes to somebody else (a contractor, a bug report, a
> published demo). A transfer copy carries the content and presentation only: tenant accounts and
> permission deltas are omitted, every value a plugin declares as a secret is blanked, the hostnames in
> `site.yml` are neutralised, traffic data is dropped, and plugin participant exports (search indexes,
> form submissions) are skipped. **The trade-off:** a transfer copy will *not* restore the site as it
> was — it is not a backup, and you still take the plain one for that.

**Where server-saved backups live.** Under the **deployment root** at
`backups/<siteId>/backup-YYYYMMDD-HHMMSS-SSS.zip` (i.e. `/opt/runway-data/backups/<siteId>/`).
`backups/` is one of the three version-independent data dirs (with `external/` and `logs/`) — it
**survives in-place upgrades** and must stay **out of** `external/`. "Download now" backups stream to
the browser and never touch the server.

**Take a backup (verify from the shell).** After a site manager clicks *Create backup*:

```bash
ls -lt /opt/runway-data/backups/<siteId>/          # newest backup-<stamp>.zip on top
unzip -l /opt/runway-data/backups/<siteId>/backup-<stamp>.zip | grep -E 'manifest.yml|site/site.yml'
```

A valid backup contains `manifest.yml` and `site/site.yml` (plus the rest of `site/…` and any
`plugin-data/…`). Treat every `.zip` as **sensitive** — it holds the site's user records and plugin
secrets; never web-serve it, and include `backups/` in your host-level rotation.

**Restore a site.** A site master restores from the console (an uploaded file, or a server-saved
backup). Restore is **validated before it swaps anything in** — a corrupt, empty, or non-Runway
archive is rejected with the **live site untouched** — then swapped atomically, with caches evicted
immediately so no stale entry serves the pre-restore site.

**Verify a restore worked:**

```bash
journalctl -u runway | grep -iE "restored site '<siteId>' from backup"   # success line
ls -d /opt/runway-data/external/sites/.restore-work 2>/dev/null || echo "clean (no leftover scratch)"
```

Then load the site — it should render the restored content. A missing scratch dir
(`sites/.restore-work/`) means the restore completed and cleaned up. If restore was **rejected**
(bad archive), the log says "not a valid Runway site backup …" and the site is unchanged — that is
the safe outcome, not a failure to recover from.

## 8. Incident recovery

### 8.1 Interrupted restore — automatic roll-forward on next start-up

A restore swaps atomically: `siteDir → old`, then `staging → siteDir`, then drop `old`. If the
process **crashes or loses power between those two moves**, the live site is momentarily absent —
its only copy sits in the restore scratch at `external/sites/.restore-work/<siteId>/old`.

**You don't fix this by hand.** On the **next start-up**, a recovery sweep runs: for any scratch dir
whose site folder is missing, it **rolls the interrupted rollback forward** — moves `old` back into
place — and then clears the leftover scratch. (`.restore-work` is a hidden name the platform's id
rules reject, so it can never be mistaken for a real tenant.)

**Recover:** simply start the service again — `systemctl restart runway`. **Confirm:**

```bash
journalctl -u runway | grep -iE "recovered site '<siteId>' from an interrupted restore"
ls -d /opt/runway-data/external/sites/.restore-work/<siteId> 2>/dev/null && echo "STILL PRESENT — see below" || echo "cleaned"
```

If a scratch dir is **still present** after a restart, the sweep found its recovery move *itself*
failed and **deliberately left `old` untouched** — it is the **only copy** of that site's content.
The log shows `could not recover site '<siteId>' automatically — its content is preserved at …`. Do
**not** delete it; move it back manually onto the same filesystem and restart:

```bash
sudo -u runway mv /opt/runway-data/external/sites/.restore-work/<siteId>/old \
                  /opt/runway-data/external/sites/<siteId>
sudo systemctl restart runway
```

### 8.2 Recovering a lost `external/application.yml`

`external/application.yml` holds the **application-superadmin pin** (`runway.security.app-master-id`)
and any `runway.security.secret-key`. Losing it has two consequences: the platform drops back to
**WIZARD mode** (no pin — see **§4.4**), and any `ENC(...)` plugin secrets encrypted under a lost
`secret-key` can no longer be decrypted.

- **You have a backup of `external/`** (host-level rotation — the recommended posture): restore
  `application.yml` from it, fix ownership/mode, restart:
  ```bash
  sudo install -o runway -g runway -m 600 /path/to/backup/application.yml \
       /opt/runway-data/external/application.yml
  sudo systemctl restart runway
  ```
  **Confirm:** the log comes up **ACTIVE** (no WIZARD) and `/app/login` works; the pin matches
  `external/app-users.yml`'s superadmin (a mismatch is refused at boot — see below).
- **No backup:** re-run the **wizard** (**§4.4**) — reach any URL, recreate the administrators; it
  writes a fresh `application.yml` with a new pin. The existing `external/app-users.yml` still holds
  your operators, but the **new pin must line up with the existing superadmin record**: the app
  realm has a **fail-closed load check** that refuses to boot if the on-disk store disagrees with the
  pin (a second master, a pin mismatch, a disabled/demoted master, or a stray superadmin role). If
  boot is refused for that reason, reconcile `app-users.yml` with the pin (Administration §9) and
  restart. Any secrets that were encrypted under a lost `secret-key` must be **re-entered** in each
  plugin's settings form under a new key.

Keep `external/` (especially `application.yml`, mode `600`) in your host backup rotation precisely so
this is a restore, not a rebuild.

### 8.3 The supervised-relaunch exit-code contract

Runway asks to be relaunched by **exiting with code 42** (the first-run wizard and plugin
install/enable/disable use this). The supervisor honours the contract:

| Exit code | Meaning | Who relaunches |
|---|---|---|
| **42** | *Relaunch me* (wizard / plugin apply) | systemd `RestartForceExitStatus=42`; or the launcher's detached supervisor loop |
| **143** | Clean `SIGTERM` shutdown (128 + 15) | nobody — treated as success, not a failure |
| anything else | A real crash | systemd `Restart=on-failure` (after `RestartSec=5`) |

`SuccessExitStatus=143 42` keeps both a clean shutdown **and** the relaunch from being logged as
failures. **The implication for incidents:** if the service is **relaunch-looping**, it is exiting
`42` repeatedly — something the wizard or a plugin apply keeps requesting on every boot. Look at the
log around each restart (`journalctl -u runway | grep -iE 'relaunch|WIZARD|plugin'`): a stuck wizard
means the pin never got written (**§4.4**); a stuck plugin apply means a plugin's state can't settle
(disable the offender and restart). Do **not** paper over a `42` loop by removing
`RestartForceExitStatus` — that just hides a real unfinished operation.

## 9. Upgrade & rollback

**Upgrade = re-run the installer with the newer `.bin`** — the same self-extracting installer
installs and upgrades, and it is idempotent (Administration §15 has the full walk-through):

```bash
sudo ./runway-<newer-version>.bin -y        # or interactively — not with --port / --tls-keystore
```

`--port` and `--tls-keystore` are first-install options; on an existing install the installer refuses them
rather than replace `external/application.yml` (it holds the setup wizard's superadmin pin — Administration §3).

**Why data survives.** The installer separates *code* from *state*. It unpacks the new release into
a **new versioned dir** `/opt/runway-<newer-version>/`, symlinks the **same** version-independent
data dir `/opt/runway-data/` (`external/`, `logs/`, `backups/`) into it, and flips the
**`/opt/runway` "current" symlink** to the new release, then restarts. Your `application.yml`, all
site folders, plugin folders, logs, and backups are never inside the versioned dir, so they are
never touched — with one deliberate exception: the bundled essential plugin's folder
(`external/plugins/protection/`) is **refreshed from the new release on every upgrade**, because it moves in
lockstep with the kernel (an old copy is refused, and a refused essential plugin is a `503` on every site). Its
settings live outside the folder; the previous jar and `plugin.yml` stay beside the new ones as `*.bak` (the
rollback pair, below), and a template/i18n file you edited is backed up with a warning. The installer prints
each replaced file. `--update-essential-plugins` is accepted and does nothing.

**Verify the upgrade:**

```bash
ls -l /opt/runway                  # -> runway-<newer-version>
systemctl status runway            # active (running)
journalctl -u runway -e            # startup banner shows the new version, ACTIVE mode
```

Sign in at `/app/login` and confirm sites and users are intact. After a **major** platform change,
watch the **Plugins** screen for any row that turned **Blocked** or **Malformed** (a plugin built
against an older SPI) and rebuild that plugin (**§6**).

**Confirm the protection plugin by its bytes, not by its version** — on the Plugins screen a hand-copied or
partially restored folder shows the same version as the release's own copy:

```bash
P=/opt/runway-data/external/plugins/protection; S=/opt/runway/seed/plugins/protection
sudo cmp "$S/runway-plugin-protection.jar" "$P/runway-plugin-protection.jar" && sudo cmp "$S/plugin.yml" "$P/plugin.yml" && echo "protection is this release's copy"
```

The closing line means the folder holds this release's plugin; `differ` means it does not (copy the release's
`seed/plugins/protection/` in, §6). Compare both files: a current jar under an older `plugin.yml` still accepts
what the older plugin accepted. `sha256sum -c` against `external/plugin-manifests/protection.sha256` does not tell:
it lists only `templates/` and `i18n/`. Details: Administration §15.

**Rollback is a symlink flip.** Because the previous release dir is left in place and the data dir is
shared, rolling back is pointing `current` back and restarting:

```bash
sudo ln -sfn /opt/runway-<previous-version> /opt/runway
sudo systemctl restart runway
```

**Confirm:** `ls -l /opt/runway` points at the previous version and it comes up `active`. Do **not**
delete a versioned release dir until you're sure you won't roll back to it.

**Restore the protection plugin before the flip.** The upgrade refreshed it in the shared
`external/plugins/protection/` (every upgrade does), so the flip leaves the older kernel facing the newer plugin,
which the older kernel can refuse on the plugin's declared SPI floor — and a refused essential plugin, being
fail-closed, leaves every site answering `503` (the console stays up). Restore the jar and descriptor **together** (the new jar under
the old descriptor can fail with a `NoClassDefFoundError` naming an SPI type). The installer kept both as `*.bak`
when it replaced them; skip the two `cp` lines if there is no `plugin.yml.bak` (the upgrade did not change the
plugin):

```bash
P=/opt/runway-data/external/plugins/protection
sudo cp -f "$P/runway-plugin-protection.jar.bak" "$P/runway-plugin-protection.jar"
sudo cp -f "$P/plugin.yml.bak" "$P/plugin.yml"
sudo ln -sfn /opt/runway-<previous-version> /opt/runway
sudo systemctl restart runway
```

**Confirm:** protection is *Loaded* on the Plugins screen, and the two `cmp` lines under *Verify the upgrade*
print the closing line: after the flip `/opt/runway/seed/` is the older release's own copy. Do not go by the
version number, which an old copy and a new one can share. Restore both files: the new `plugin.yml` declares an
SPI floor that gets the old jar Blocked (sites answer `503`). The older release also keeps its own copy in
`/opt/runway-<previous-version>/seed/plugins/protection/` — the same files as the `.bak` pair, and still right after two upgrades, when the pair holds the release in between: copy
it over the plugin folder instead (the commands in §6's essential-floor recovery, after the flip). Plugin releases installed
for the newer kernel may be Blocked by the older one on their SPI floor; re-install their previous versions.
Content written for the newer release can fail too: a template that reads a variable the older release does not
provide answers `500` on every page of that site after the flip (the console keeps working). The release's
upgrade notes name the new variables; edit the template on the site's **Fragments** screen. Details:
Administration §15.

**Rolling back to a release that does not know the newer one's settings.**
The older release reads `external/platform.yml`, ignores what it does not know, and drops it when it writes the
file:

- **Before the flip,** `sudo cp -p /opt/runway-data/external/platform.yml /opt/runway-data/external/platform.yml.<newer-version>`.
- **A setting the older release does not know** is back on its default under it. A key you removed from
  `application.yml` to manage it in the console goes back into `application.yml` while the older release runs.
- **While on the older release,** do not save **Settings → Platform settings**, delete the default site or
  install a demo site as the default: each rewrites `platform.yml` without the newer blocks.
- **To roll forward,** stop the service, copy `platform.yml.<newer-version>` back over `external/platform.yml`, run
  `sudo ./runway-<newer-version>.bin -y` (a bare flip would leave the older release's protection under the newer
  kernel), confirm protection with the `cmp` lines, and take out of `application.yml` the keys you put back.

> **Take a backup first.** [CONFIGURATION-AND-DATA-STABILITY.md](CONFIGURATION-AND-DATA-STABILITY.md) governs
> configuration and data formats from `1.0.0`. A rollback past a data-layout change needs this copy. Have a
> current copy of `external/` (and each site's backup) before upgrading (**§7**, **§8.2**; KNOWN-LIMITATIONS).
