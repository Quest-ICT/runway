# Runway — Administration Manual

> **Audience:** **application administrators** — the operators who install and run the Runway
> platform itself (not the people who manage an individual site).
> **What this is:** everything needed to install, configure, run, and maintain a Runway server,
> including managing plugins, sites, and application users.

> **Doc status.** Every section covering a built feature is written, including **§14 Monitoring & metrics**.
> Each change ships with its documentation. This document is self-contained.

---

## 1. Overview & audience

Runway is a **multi-site web platform**: one running server hosts many independent **sites**
(tenancies), each with its own content, presentation, users, and configuration, resolved by the
request's `Host` header. This manual is for the **application administrator** — the operator who
installs, configures, runs, and maintains that server.

**What you control.** As an application administrator you run the *platform*: its configuration,
the **plugins** it loads, the **sites** it hosts (create, suspend, resume, delete), the
**application-level user accounts** (fellow operators), and the platform-wide settings. You do all
of this from the **operator console** at `/__app`, after signing in at `/app/login`. The console answers on
the default site's host names and on host names that serve no site (the server's IP address, `localhost`), but
not on a host name that serves another site, or only on the names you list in `runway.console.hosts` (**§8.6**).

The console's top bar is organized into **dropdown groups**: **Dashboard** (top-level), **Sites**
(Manage sites, Backups, Templates, Site master users), **Access** (App users, Permissions),
**Activity** (Bandwidth, Logs), the top-level **Plugins**, and **Settings** (Platform settings, About
Runway). Items you cannot open are hidden — each entry is shown exactly when its screen would let you in
(the same check the screen makes: a grantable permission such as **Logs** → `VIEW_AUDIT`, **Bandwidth** →
`VIEW_BANDWIDTH`, **Templates** → `MANAGE_STYLES`, **Manage sites** → `VIEW_TENANCIES` or `MANAGE_SITE_HOSTS`,
or the superadmin/wildcard-admin gate of **Backups**, **Permissions** and **Platform settings**) — and a group left with nothing in it is hidden too, so the menu
never offers a link that answers "forbidden". Hiding is only presentation: every screen still checks on its
own. Section references below name the
destination screen; the parenthetical path is the direct URL. A **language switcher** (the globe/language
icon next to your username) sets the console's display language — English or Spanish by default (see
`runway.operator.ui-languages` in **§8**); the choice is remembered per operator and does not affect any
site's own language.

**What you do NOT do.** You never operate *inside* a site as if you were a site user — you cannot
edit a site's pages, its content, or its per-site data. That is the site manager's job, done in a
separate realm and a separate console. This is a deliberate, foundational boundary:

- **Two user realms that never cross.** The **application realm** (operators; login `/app/login`,
  console `/__app`) and the **tenant realm** (site users; login `/login`, console `/__admin`) have
  separate login URLs and separate user stores. Authenticating in one never grants the other — even
  in the same browser, an application session and a site session coexist without crossing. A
  compromised or buggy action in one site is contained to that one site; the operator and the other
  sites are never exposed.
- **The application superadmin runs the platform but cannot enter a site;** every **site
  superadmin** fully manages its site but can never reach the application realm. You provision a
  site's first superadmin when you create the site, and you can reset its password from the console,
  but you cannot use it to act inside the site.

The companion document for operating an individual site is the *Site Manager & Designer Guide*
([SITE-MANAGER-GUIDE.md](SITE-MANAGER-GUIDE.md)); its §2 also describes keyboard and screen-reader use, which
is the same in the platform console (skip link, Esc then Tab out of a code editor, Down arrow or Shift+F10 for
a listing table's column menu). Day-to-day operational troubleshooting lives in
[OPERATIONS.md](OPERATIONS.md).

## 2. System requirements

| Requirement | Detail |
|---|---|
| **OS** | Linux with **systemd** (the installer and unit target it; tested on RHEL-family — Rocky/Fedora/Alma — and Debian-family). macOS/Windows are not covered by the installer. |
| **Java** | A **JDK or JRE 25+** on the host. A JRE is sufficient — Runway does **not** compile at runtime. The installer auto-detects a 25+ runtime (Zulu, Temurin, generic OpenJDK) or you point it with `--java-home`. |
| **Database** | **None.** The kernel is filesystem/YAML-backed on purpose; there is no SQL server to install or configure, and the first-run wizard never asks for one. Plugins that need one use PostgreSQL or MariaDB through the **database plugins** (§11.1). |
| **CPU / RAM** | Modest. The service runs in one JVM; the shipped heap is `-Xms256m -Xmx1g` (`JAVA_OPTS` in the unit), which suits a small VM. Note the sizing interlock: each Argon2id password verification costs 19–32 MiB, and the hash pool is bounded (`runway.security.max-concurrent-hashes`, default 8) precisely so a login flood cannot exhaust heap — keep `-Xmx` comfortably above `max-concurrent-hashes × memory-cost`. |
| **Disk** | The code footprint is small (a single fat jar). Plan capacity for **editable state**: `external/` (config + all site folders), `logs/`, and `backups/` (site backup archives can be large — see **§13**). |
| **Network / ports** | One HTTP listener. By default Runway serves **plain HTTP on port 8080** and expects a reverse proxy (HAProxy/nginx/Caddy) in front to terminate TLS (**§7**). Change the port with `--server.port=` (`RUNWAY_OPTS`). Binding a privileged port (80/443) directly needs `CAP_NET_BIND_SERVICE` — see the unit (**§6**). |

Clustering is **opt-in and off by default** (`runway.cluster.enabled`, set in the external
`application.yml`). Left off — the default, and what the sizing figures below assume — no Hazelcast member
starts, no session repository is wired, and the container's own `HttpSession` is used: one instance, scaled
vertically on a bigger host. Turned on, several instances run behind a load balancer — HTTP sessions live in
a Hazelcast `IMap` shared by every node (so a session survives losing the node that created it, with no
sticky-session requirement), and a Hazelcast topic relays cache-invalidation events (site, page, content and
per-site plugin-config changes) so every node evicts in step. Each node is a Hazelcast member itself by
default (`runway.cluster.topology: EMBEDDED`, `tcp-ip` discovery with an explicit member list), or a client
of a separately-run grid (`CLIENT_SERVER`).

**The clustering keys.** All of them live in the external `application.yml` (or the environment) of **every**
node, with the same values on each, and are read once at startup: a change needs a restart of every node. None is
on a console screen, because one node's console cannot change the others. While `enabled` is `false` none of the
other keys is read, so a mistake in them shows only when you switch clustering on. Nor are they read in WIZARD mode:
clustering starts only once first-run setup is done.

| Key | Default | What it does |
|---|---|---|
| `runway.cluster.enabled` | `false` | The master switch. Off, the node runs alone with the servlet container's own sessions. |
| `runway.cluster.cluster-name` | `runway` | The Hazelcast cluster name. Members and clients join only peers with the same name, so two estates on one network stay apart. With `CLIENT_SERVER` it must equal the name of the grid you connect to. |
| `runway.cluster.topology` | `embedded` | `embedded`: every node is a Hazelcast member and reads only the `embedded.*` keys. `client-server` (or `CLIENT_SERVER`): every node is a client of a grid you run separately and reads only the `client.*` keys. Any other value stops startup. |
| `runway.cluster.session-ttl` | `30m` | How long an idle signed-in session lives while clustering is on. **Keep it equal to `server.servlet.session.timeout`** (§8): with clustering on, this value decides the timeout, and with it off that one does, so equal values mean switching clustering never changes when people are signed out. Write the unit (`30m`, `2h`): a bare number is read as **milliseconds** here, unlike `server.servlet.session.timeout`, where it means seconds. On an embedded member the grid also drops idle session entries itself after this time, but never sooner than 60 seconds. |
| `runway.cluster.sessions-map-name` | `runway.sessions` | The name of the distributed map that holds the sessions. Change it only when two separate Runway estates share one external grid (`CLIENT_SERVER`): with the same name they would share sessions. |
| `runway.cluster.events-topic-name` | `runway.events` | The name of the distributed topic that carries "this changed, drop your cached copy" messages between nodes (site, page, content and per-site plugin-setting changes). Same rule as the map name: different estates on one grid need different names. |
| `runway.cluster.embedded.discovery` | `tcp-ip` | How embedded members find each other: `tcp-ip` (the `members` list below; use it in production) or `multicast` (members find each other on the local network; convenient for a test cluster, and `members` is then ignored). Written exactly so, in lower case; any other value stops startup with a message naming it. |
| `runway.cluster.embedded.members` | empty | With `tcp-ip`: the members' addresses as `host:port`, for example `["10.0.0.1:5701", "10.0.0.2:5701"]`. |
| `runway.cluster.embedded.port` | `5701` | The port this node's member listens on for the other members. Open it between the nodes in the host firewall, and nowhere else: it is the cluster's internal traffic, sessions included. |
| `runway.cluster.embedded.port-auto-increment` | `true` | When the port is taken, try the next ones, so several members can run on one host. Turn it off to make a taken port fail instead of moving to a port your firewall does not open. |
| `runway.cluster.client.addresses` | empty | With `client-server`: the grid members to connect to, e.g. `["hz1.internal:5701"]`. **Required**: an empty list stops startup with *"runway.cluster.topology=CLIENT_SERVER requires runway.cluster.client.addresses to be non-empty"*. |
| `runway.cluster.client.smart-routing` | `true` | The client talks to every grid member and sends each operation to the member that owns the data (the fastest). Turn it off only when a firewall lets the client reach just one member: everything then goes through that one. |

With `client-server`, Runway does not configure the grid's session map for you: declare the map named in
`sessions-map-name` on the grid with `max-idle-seconds` equal to the session TTL. Without it an idle session is
removed only when someone presents its id again, so abandoned sessions stay in the grid's memory.

Two limits remain, and they shape the topology you can actually run: there is **no read-only edge/replica
mode** yet (every node is a full read-write instance), and the filesystem stores serialize writes **per site
within one JVM only** — two nodes writing the same site's files are last-writer-wins at file granularity (a
distributed lock is the follow-up). Keep authoring traffic on one node until that lands.

### Capacity, sizing & resilience

Runway is **CPU-bound**, not memory-bound, for dynamic traffic — so **size the CPU for your peak request rate and
let memory follow the §15 rule.** Reference numbers from a load test on a **2 vCPU** box (Jetty/JDK 25, behind
HAProxy; SSR-heavy mix including a full-JSON-LD page):

- **~750 req/s sustained** for server-rendered pages, at **~99 % CPU** — the **SSR render is the cost**; the box
  saturated CPU well before anything else. **Median latency ~27 ms** when not overloaded.
- **Static / cached content goes far higher** (a CSS/JS asset is cheap) — the dynamic render is what consumes a
  core, so read the 750 figure as a *dynamic-page* ceiling, not a total.
- **Memory was comfortable throughout: zero full GCs**, heap peaked ~0.7 GB of a 2 GB `-Xmx`, and the `MemoryMax`
  cgroup cap was never approached. Add cores for throughput; give memory the §15 headroom and it is not the limit.
- **Rule of thumb:** throughput scales with cores for SSR traffic — roughly **~350–400 dynamic req/s per core** on
  this class of hardware. Extrapolate cautiously (these are 2-core measurements; real pages and CPU differ).

**Concurrency ceiling — the Jetty thread pool.** In-flight request concurrency is bounded by the embedded Jetty
thread pool (**default max 200**, min 8). On ≤ 2 cores CPU saturates long before 200 threads matter, so the default
is fine; on a **larger multi-core host** the pool becomes the binding ceiling. Raise it on **Settings → Server
settings → Capacity** ("Most request threads", "Fewest request threads"), or with the standard Spring Boot property
in the external `application.yml` (or as a `RUNWAY_OPTS`/JVM arg). Either way it applies after a restart. The
minimum may not be above the maximum (the console refuses it, because the server would not start). The same threads
serve the public sites **and** the consoles, so a maximum set too low leaves the console waiting behind public
traffic under load:

```yaml
server:
  jetty:
    threads:
      max: 400   # default 200 — raise on a box with many cores serving high SSR concurrency
      min: 8
```

**Resilience past capacity.** Beyond its capacity Runway **degrades gracefully — it sheds excess with `503`s,
never crashes, and recovers instantly** when load drops (in the test, CPU fell 99 %→8 % and busy threads 200→3 the
moment the spike ended). Combined with the DoS floor (**§12**), an overload or a flood is shed, not fatal.

## 3. Installation

Runway ships as a **single-file, self-extracting installer** — `runway-<version>.bin` — a plain
shell script with a compressed bundle appended after a marker. It needs only stock tools
(`bash`, `tar`, `gzip`, `awk`, `tail`); there is no `makeself` or other dependency. The **same
file installs and upgrades** — re-run it to upgrade in place (**§15**).

**Download & verify.** Get the installer (and the plain `runway-<version>-dist.zip` bundle) from the
releases page, <https://g.quest.mx/quest/runway/releases>. Every release also ships **stable-named
aliases** and an auto-computed **`SHA256SUMS`** manifest, so these links always fetch the newest build
without looking up a version number:

```
https://g.quest.mx/quest/runway/releases/download/latest/runway-latest.bin
https://g.quest.mx/quest/runway/releases/download/latest/runway-latest-dist.zip
https://g.quest.mx/quest/runway/releases/download/latest/SHA256SUMS
```

The browser page `…/releases/latest` always opens the newest release.

> **`latest` serves the newest release.** `…/releases/latest` and every `…/download/latest/…` link serve the
> newest release from the moment it is cut. The release title and its versioned file (`runway-<version>.bin`, beside the
> alias) say which version you got; the CHANGELOG says what each one is. **For production, download an explicit version** from the
> releases page rather than the `latest` link, so a re-run of your install script cannot pick up a newer build
> you have not read the upgrade notes for (§15).

Verify before running — `SHA256SUMS` lists both
the versioned and the alias names (the aliases are byte-identical copies of that release's artifacts), and the
plugin author's `runway-spi*` files, which an operator does not download (`--ignore-missing` skips them):

```bash
curl -fL -O \
  https://g.quest.mx/quest/runway/releases/download/latest/runway-latest.bin
curl -fL -O \
  https://g.quest.mx/quest/runway/releases/download/latest/SHA256SUMS
sha256sum -c SHA256SUMS --ignore-missing        # must print: runway-latest.bin: OK
chmod +x runway-latest.bin                      # a download has no execute bit
sudo ./runway-latest.bin
```

> **The `sha256sum -c` step is not optional, and it is the only thing standing between you and a silent
> failure.** Where the releases page requires signing in, a Gitea server given no valid token does not return an
> error — it returns **HTTP 200 with
> `Content-Type: text/html`**, the sign-in page, under the asset's own URL. `curl -f` does **not** catch that
> (`-f` only fails on status ≥ 400), so you end up with a ~12 KB HTML file named `runway-latest.bin`, and any
> check that looks at the exit code or the status code reports success.
>
> The checksum comparison is what fails loudly there, so never skip it — and if you script the download, assert
> **`Content-Type` and size**, not the status code. The same reasoning applies to any automated freshness or
> availability probe against these URLs.

Before you point an audit, prober or load test at the new install, exempt its address — §12.4, *Exempting
your own high-volume clients*.

**Install (interactive).** Copy the `.bin` to the target host and run it as root:

```bash
chmod +x runway-<version>.bin      # a download has no execute bit
sudo ./runway-<version>.bin
```

On a terminal it walks you through the deployment choices it can't infer — **install location**,
**service user**, the **listen port**, whether to **terminate TLS in Runway itself** (and if so the
PKCS12 keystore + password, and an optional HTTP→HTTPS redirect), and whether to **open the host
firewall** — with any flags you passed pre-filled as the defaults. Anything you supply as a flag is not
re-asked. Watch for the closing lines — it prints the install path, the `systemctl status` /
`journalctl` commands, and the reminder to finish setup in the browser. TLS choices are written to the
external `application.yml` and the first-run web wizard **preserves** them (it merges only its
superadmin pin — see §4).

**Install (unattended).** Accept all defaults with `-y`, or drive every choice by flag:

```bash
sudo ./runway-<version>.bin -y                              # plain HTTP behind a reverse proxy (the default)
sudo ./runway-<version>.bin --port 8080 --open-firewall     # set + open the listen port
# terminate TLS locally with an existing PKCS12 keystore (see §7.1 to make one from PEM):
sudo ./runway-<version>.bin -y --tls-keystore /opt/runway-data/external/tls/runway.p12 \
    --tls-password 's3cr3t' --tls-redirect --open-firewall
```

**Installer options** (pass any straight through the `.bin`):

| Flag | Effect |
|---|---|
| `--prefix DIR` | Install root (default `/opt`). |
| `--user NAME` | Service user to create/use (default `runway`). |
| `--port N` | The app's listen port. Written to `application.yml` when non-default (443 with local TLS, else 8080), and opened in the firewall if requested. A port below 1024 also switches on `CAP_NET_BIND_SERVICE` in the unit; later upgrades keep it (§6). **First install only** (see below). |
| `--tls-keystore PATH` | Terminate TLS in Runway itself with this **PKCS12** (`.p12`) keystore — implies local TLS; the file is copied to `external/tls/` and `runway.tls.*` is written (§7.1). **First install only** (see below). |
| `--tls-password PASS` | Keystore password (else prompted; unattended installs must pass it). It is written **in plain text** as `runway.tls.keystore-password` in `external/application.yml`, which is why that file is mode `600` and owned by the service user. Typed on the command line it also lands in your shell history; leave it off on a terminal and answer the prompt instead. |
| `--tls-redirect` | Also add an HTTP→HTTPS 301 redirect + ACME webroot on port 80. Port 80 is privileged, so it also switches on `CAP_NET_BIND_SERVICE` in the unit (kept on upgrade, as with `--port`; see §6), and `--open-firewall` then opens 80 as well. |
| `--no-tls` | Skip the TLS prompt — stay plain HTTP behind a proxy. |
| `--secret-key KEY` | The master key for encrypting stored secrets (SMTP / CAPTCHA / plugin passwords). Persisted as a **root-only systemd drop-in** (`/etc/systemd/system/runway.service.d/30-secret-key.conf`, env var `RUNWAY_SECURITY_SECRET_KEY`). **Leave it off to auto-generate one on first install** (so secret storage never silently fails — even with `-y`). An existing key is **kept** on upgrade (never rotated). Pass an explicit value only to reuse a known key (e.g. migrating a deployment). **Back it up** — losing or changing it makes existing `ENC(...)` values undecryptable. |
| `--java-home DIR` | The JDK/JRE 25+ to run under (else auto-detected). The installer takes the first of these whose `bin/java` reports version 25 or later: this flag, then `$JAVA_HOME`, then `/usr/lib/jvm/` folders matching `*zulu*25*`, `*temurin*25*`, `*-25-*`, `java-25-*`, `jdk-25*`, `jre-25*`. A `--java-home` older than 25 is passed over without an error, so check the `java home` line it prints. With none found it warns and writes `/usr/lib/jvm/java-25-openjdk` into the unit; fix the unit's `JAVA_HOME` then. |
| `--open-firewall` | Best-effort: open the listen port (and 80 with `--tls-redirect`) in **firewalld** or an already-active **ufw** (**§16**). |
| `--firewall-ports LIST` | Comma-separated TCP ports to open (implies `--open-firewall`). |
| `--no-firewall` | Never touch the firewall — **wins over** `--open-firewall` and skips the prompt. |
| `-y`, `--yes`, `--non-interactive` | Non-interactive: accept all defaults. Without it the installer asks only when its input is a terminal, so a run from a pipe, a script or cron is already non-interactive. |
| `--update-essential-plugins` | Accepted and ignored (it logs that it has nothing to do). **Every upgrade** refreshes the bundled essential plugin (`protection`) from the new release; see §15 *Essential plugins on upgrade*. |
| `--heap SIZE` | Set the JVM heap `-Xmx` (e.g. `2g`, `1536m`) via an **upgrade-surviving drop-in** (`runway.service.d/20-heap.conf`) instead of the installer-rewritten main unit. `SIZE` is a whole number with an optional `k`, `m` or `g` (either case); a value with any other character (`2GB`, `1.5g`) is refused before the install starts. The drop-in sets `JAVA_OPTS=-Xms256m -Xmx<SIZE>` and is rewritten whole each time you pass `--heap`. See **Memory tuning** in **§15**. |
| `--memory-max SIZE` | Set a systemd `MemoryMax` cgroup backstop (e.g. `3g`, `1945M`) via drop-in `10-memory.conf`. Keep it ≥ heap + headroom. See **§15**. |
| `--dry-run` | Print every privileged action instead of doing it (change nothing). |
| `-h`, `--help` | Show help. |
| `--extract DIR` | (On the `.bin` itself) unpack the bundle into `DIR` without installing. |

**Where the `.bin` unpacks.** Before it installs, the `.bin` unpacks itself into a new folder under `$TMPDIR` (or
`/tmp` when `TMPDIR` is unset) and deletes that folder when it ends. On a host whose `/tmp` is small, point it
elsewhere: `sudo env TMPDIR=/var/tmp ./runway-<version>.bin`. Or unpack it yourself with `--extract DIR` and run
`DIR/runway-<version>/scripts/install.sh` with the same options.

**`--port` and `--tls-keystore` are for the first install only.** They write `external/application.yml` whole,
and on an existing install that file also holds the setup wizard's superadmin pin and anything you added to it;
replacing it would put the platform back into wizard mode with `/setup` open. So when the file already exists the
installer **refuses** them, before changing anything, and names the keys to edit instead — `server.port`,
`runway.tls.*` (§7.1) — then restart. An upgrade needs neither (**§15**); `--port 8080` (the default, never
written) and the firewall flags are still accepted. On an existing install the interactive run does not ask
about TLS or the port either.

**What it lays down.** The installer is **idempotent** and separates *code* from *state* so an
upgrade never clobbers your configuration or data (see **§5** for the layout, **§15** for
upgrades). With the defaults it:

1. Creates the **service user** `runway` (a system account, `nologin` shell).
2. Creates the version-independent **data dir** `/opt/runway-data/` with `external/`, `logs/`,
   and `backups/`.
3. Unpacks the release to a **versioned dir** `/opt/runway-<version>/` — `bin/` (the jar and the
   launcher), `scripts/` (the installer), and `seed/plugins/` (the bundled protection plugin as it ships, the copy
   you repair from — **§12**) — and symlinks `external/`, `logs/`, `backups/` from there into the data dir.
4. Flips the **`/opt/runway` "current" symlink** to the new release (this path is `RUNWAY_HOME`).
5. Sets ownership to the service user and, on SELinux-enforcing hosts, **relabels contexts**
   (`restorecon`) — this is required, not optional (**§16**).
6. Installs/updates the **systemd unit** `/etc/systemd/system/runway.service` with the detected
   `JAVA_HOME`, enables it, and **starts the service**.

**Verify the install.** After it finishes:

```bash
systemctl status runway            # should be "active (running)"
journalctl -u runway -e            # startup log — look for the listen port and "WIZARD" mode
ls -l /opt/runway                  # -> runway-<version>  (the current symlink)
ls /opt/runway-data                # external  logs  backups
```

Then open the server in a browser and complete the first-run setup (**§4**) — a fresh install
boots in **WIZARD** mode and every request funnels to the setup page.

## 4. First-run setup

A brand-new install has no accounts and no sites, so Runway boots in **WIZARD mode** and serves a
single open setup page — every other path is funneled to it. The mode is **derived**, not a knob:
the platform is in WIZARD mode precisely while the application-superadmin **pin**
(`runway.security.app-master-id`) is unset in `external/application.yml`. Once the wizard writes
it, the next launch comes up in **ACTIVE mode** and the whole wizard layer is gone. Config can
never contradict reality because there is no separate `mode` setting to drift.

Writing the pin is a **merge, not a rewrite**: any settings already in `external/application.yml` —
self-managed TLS (§7.1), proxy CIDRs, a custom `server.port`, whatever the installer or you put there —
are **preserved** through first-run setup (the wizard only sets the pin). If that file exists but is not
valid YAML, setup stops with a clear message rather than overwriting it, so a hand-edit is never lost.

**Security note.** Because there are no accounts yet, the setup page is **unauthenticated**. Reach
it only over localhost or from behind your reverse proxy until setup completes — do not expose
`/setup` to the internet.

**Run the wizard.** Open the server (e.g. `http://<host>:8080/setup`, or just any URL — it
redirects to setup). You provision **two administrators, one per realm** in a single step:

- **Application superadmin** — runs the *platform* (sites, plugins, application users). You give a
  username, an optional display name and recovery email, and a password (min 8 chars). There is
  exactly one, and it is **pinned** — it can never be deleted, disabled, demoted, or renamed from
  within the realm (**§9**).
- **Default site administrator** — the **superadmin of the first site** the wizard seeds. This is a
  separate **tenant-realm** identity (username, password and an optional recovery email); the two realms
  never cross, so this is a distinct account from the application superadmin. Give it the recovery email
  here, or later on **Site masters** (**§10**), so it can use *Forgot your password?*.

Submit **"Create administrators & restart"**. The wizard:

1. Seeds a first site from the **starter template** and marks it the **default (catch-all)** site
   (**§10**) — so the deployment answers any hostname out of the box. The starter is a real, styled
   **two-page site** (a home page and an about page) sharing one master template, not a blank placeholder:
   it is meant to be edited, not replaced. There is no other choice to make here.
2. Provisions that site's superadmin (the tenant admin you entered). If this step fails, it rolls
   back to a clean slate so you can simply retry.
3. Creates the pinned application superadmin, writes `external/application.yml` with the pin
   (mode `600` — it holds a secret), and triggers a supervised relaunch.

**Confirm it worked.** The success page says setup is complete and the server is restarting. Under
systemd the JVM exits with the relaunch code and systemd restarts it into ACTIVE mode (**§6**).
Within a few seconds:

```bash
journalctl -u runway -f            # you should see a fresh startup, now WITHOUT WIZARD mode
ls -l /opt/runway-data/external/application.yml   # exists, mode -rw------- (600)
```

Then sign in to the **operator console** at `/app/login` with the application-superadmin
credentials — you land on the console home at `/__app`. The site administrator signs in
separately at the site's `/login`. Use the server's address, or a host name of the first site (it is the
default site): once you give other sites their own host names, the console does not answer on those (**§8.6**).

## 5. Deployment layout

Runway keeps all editable state in an **external folder** (no database). The kernel resolves
these paths from `runway.storage.*`; only `external` is normally set, the rest default relative
to it:

| Key | Default | Holds |
|---|---|---|
| `runway.storage.external` | `./external` | The operator-editable root: `application.yml`, shared templates/static/i18n, app-level stores, `sites/` |
| `runway.storage.sites` | `<external>/sites` | One self-contained folder per site (its data, presentation, users, config) |
| `runway.storage.backups` | `./backups` | Per-site backup archives |
| `runway.plugins.root` | `<external>/plugins` | Installed plugins, one folder each (**§11**). Normally left unset; when set, both the loader and the console's install/uninstall/enable/disable use it |

`runway.storage.stats` and `runway.storage.logs` are not settings (bandwidth is metered per site, in
`sites/<id>/stats/`; the launcher's `runway.out` lives in the deployment's `logs/`, outside Runway's control).
A configuration that sets either starts normally and logs one `WARN` naming the key — delete the line.

**The layout marker.** On its first start with a data folder, Runway writes `external/layout.yml`
(`version: 1`). It records which version of this folder's layout the data is in, and it is checked before
anything else touches the folder: a release older than the layout refuses to start rather than write into it
(see **§15**, *Rolling back*). Do not edit or delete it.

**Every file, and who owns it.** The full `external/` and `sites/<id>/` inventory — each file, the class
that reads and writes it, and what an upgrade may change about it — is in
[CONFIGURATION-AND-DATA-STABILITY.md](CONFIGURATION-AND-DATA-STABILITY.md) §2.

## 6. Running the service

The installer registers Runway as a **systemd service**, which is the normal way to run it. The
service is driven by the launcher script `bin/runway.sh`, which you can also run by hand.

**Under systemd (recommended):**

```bash
sudo systemctl start runway        # start
sudo systemctl stop runway         # stop
sudo systemctl restart runway      # restart
sudo systemctl status runway       # is it running?
sudo journalctl -u runway -f       # follow the log
```

The unit uses `Type=simple` and runs the launcher in **foreground** mode
(`ExecStart=/opt/runway/bin/runway.sh -f`) so systemd gets the real JVM PID and `SIGTERM` reaches
Java directly. Two exit codes matter and are handled by the unit:

- **42** is Runway's *supervised-relaunch* request (used by the first-run wizard and by plugin
  install/enable/disable). `RestartForceExitStatus=42` makes systemd restart even though 42 is a
  clean exit, and `SuccessExitStatus=143 42` keeps both a normal `SIGTERM` shutdown (143) and the
  relaunch (42) from being logged as failures. This is why installing a plugin (**§11**) simply
  needs a restart action, not a manual `systemctl` dance.

The unit also sandboxes the process (`NoNewPrivileges`, `ProtectHome`, `ProtectSystem=strict`,
`PrivateTmp`) and grants write access only to the data dir: the installer writes
`ReadWritePaths=/opt/runway-data` (the template in `scripts/runway.service` names the three data folders under
`/opt/runway`; the installer replaces that line). Anything Runway must write — a log file you configure (Operations
§3), an off-host backup folder (§13) — therefore has to be under `/opt/runway-data`, or added to `ReadWritePaths` in a
drop-in.

**The installer rewrites the main unit on every run.** Put lasting changes in drop-ins under
`/etc/systemd/system/runway.service.d/`. The installer leaves your own files there alone; it writes only its own
`10-memory.conf` and `20-heap.conf` (when you pass `--memory-max` or `--heap`) and `30-secret-key.conf` (on the first
install). **Binding a privileged port** (80/443 without a proxy) needs `CAP_NET_BIND_SERVICE`. The installer switches
it on in the main unit when you install with `--port` below 1024 or `--tls-redirect`, and an upgrade keeps it when the
unit it replaces had it switched on. It cannot read your port from `application.yml`, though: if you move to a
privileged port later by editing that file, give the service the capability yourself, in a drop-in:

```bash
sudo mkdir -p /etc/systemd/system/runway.service.d
printf '[Service]\nAmbientCapabilities=CAP_NET_BIND_SERVICE\nCapabilityBoundingSet=CAP_NET_BIND_SERVICE\n' \
  | sudo tee /etc/systemd/system/runway.service.d/40-net-bind.conf
sudo systemctl daemon-reload && sudo systemctl restart runway
```

**The launcher directly** (e.g. on a host without systemd, or for a quick manual run). It resolves
`RUNWAY_HOME`, the executable jar, and `JAVA_HOME`, then:

```bash
bin/runway.sh            # detached: a supervisor backgrounds the JVM, writes runway.pid,
                         #   logs to logs/runway.out, and relaunches on exit 42
bin/runway.sh -f         # foreground: exec the JVM (what systemd uses); also --foreground
bin/runway.sh stop       # stop the supervisor (which SIGTERMs the JVM); also --stop
bin/runway.sh -h         # the launcher's help; also --help
```

Detached mode refuses to start a second instance while one is running. It keeps two PID files in `RUNWAY_HOME`:
`runway.pid` (the supervisor) and `runway.jvm.pid` (the JVM it is running now); `stop` uses both and removes them.
Any other argument is passed to Spring as it is, and everything after `--` is passed even if it looks like a launcher
option: `bin/runway.sh -f --server.port=9090`.

Environment overrides (honored by both the launcher and the unit's `Environment=`): `RUNWAY_HOME`,
`RUNWAY_JAR`, `RUNWAY_OPTS` (extra Spring args, e.g. `--server.port=`), `JAVA_HOME`, `JAVA_OPTS` (heap/GC —
default `-Xms256m -Xmx1g`). The launcher always adds `--enable-native-access=ALL-UNNAMED` after your `JAVA_OPTS`:
the Brotli compression library and Hazelcast (clustering) use native code, which Java 25 restricts without it. Do not
remove it from a hand-written start command.

**Verify:** `systemctl status runway` shows `active (running)`; `journalctl -u runway -e` shows the
banner with the bound port; hitting the site in a browser returns a page (the default site, if no
host is bound).

## 7. TLS & reverse-proxy setup

**Trusting forwarded headers (implemented).** Runway honors `X-Forwarded-*` headers (client IP,
host, scheme) **only** from proxy peers you list in `runway.proxy.trusted-cidrs`; from any other peer the
headers are ignored and the raw connection is used, so a directly-reachable instance cannot spoof them. The
container's own forwarded-header handling is off (`server.forward-headers-strategy: none`) — Runway's gated
filter is the sole handler. Defaults: forwarding is on (`runway.proxy.forwarded-headers: true`) and the
shipped config trusts loopback only (`127.0.0.1/32`, `::1/128`) for a same-host proxy. **Behind an off-host
proxy, add its exact IP** (prefer a single address over a broad range); an **empty** `trusted-cidrs` trusts
nothing (every request attributed to its direct peer, logged at startup). A catch-all (`0.0.0.0/0`, `::/0`,
or ranges that add up to one, such as `0.0.0.0/1` with `128.0.0.0/1`) trusts every client to fake its address:
Server settings refuses it, and one set in `application.yml` is warned about at startup. Never put a tenant-editable value here — it is an isolation/abuse boundary.

**Which address is the client.** Each proxy *appends* the address it received the request from to
`X-Forwarded-For`, so everything to the left of what your own proxies wrote may have come from the client itself.
Runway therefore reads the list **from the right**: it skips the addresses in `trusted-cidrs` and takes the first
one that is not a proxy you trust. That is the address your nearest trusted proxy saw, and it is what rate limits,
login lockouts, the access log and every plugin see. An entry that is not an IP address where one is needed
(`unknown`, a host name) attributes the request to the proxy instead; Runway never looks names up. So list
**every** proxy of yours in `trusted-cidrs`, a CDN in front of the reverse proxy included, or the client will be
taken to be the outermost one.

**Only the `X-Forwarded-*` headers are read.** The standard `Forwarded` header (RFC 7239) is ignored, even from a
trusted proxy: a client could otherwise send its own `Forwarded` through a proxy that only manages
`X-Forwarded-*` and choose its address, host and scheme. A proxy that sends only `Forwarded` must be set to send
`X-Forwarded-For`, `X-Forwarded-Proto` and `X-Forwarded-Host` instead. From a trusted proxy Runway also applies
`X-Forwarded-Port` and `X-Forwarded-Prefix` (and `X-Forwarded-Ssl`), so whatever of these the proxy does not write
itself comes from the client. The proxy should **set** (replace) `X-Forwarded-Proto` and `X-Forwarded-Host`, and
**delete** the others it does not set:

```
# HAProxy
http-request set-header X-Forwarded-Proto https if { ssl_fc }
http-request set-header X-Forwarded-Proto http if !{ ssl_fc }
http-request set-header X-Forwarded-Host %[req.hdr(host)]
http-request del-header X-Forwarded-Port
http-request del-header X-Forwarded-Prefix
http-request del-header X-Forwarded-Ssl
option forwardfor
```

nginx: `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;`, `proxy_set_header X-Forwarded-Proto
$scheme;`, `proxy_set_header X-Forwarded-Host $host;`, and `proxy_set_header X-Forwarded-Port "";`,
`proxy_set_header X-Forwarded-Prefix "";`, `proxy_set_header X-Forwarded-Ssl "";` (an empty value removes the
header).

**Check it.** **Settings → About Runway → This request** shows the request that loaded the page as Runway saw it:
the connection it came from and whether that is a trusted proxy, the `X-Forwarded-For` list as received (marked
*ignored* when the peer is not trusted), the client address Runway used, and the scheme and host. Behind a correctly
trusted proxy the client address is your own public address, not the proxy's. To check that a client cannot choose
its address, open the page once with a forged header (a browser extension that adds `X-Forwarded-For: 192.0.2.1`, or
`curl -H 'X-Forwarded-For: 192.0.2.1'` with a signed-in session cookie). Behind a proxy that appends to the header
(`option forwardfor`, `$proxy_add_x_forwarded_for`) the list shows `192.0.2.1` on its left; behind one that replaces
it, `192.0.2.1` does not appear at all. Either way the client address stays yours.

**The host name behind a proxy.** Runway decides which site a request is for, and whether the operator console
answers (**§8.6**), from the host name it sees: the request's own `Host`, or the forwarded host from a trusted proxy.
So the proxy must pass the visitor's host name on (`proxy_set_header Host $host;` in nginx, the default in HAProxy
and Caddy). To give the console a host name of its own, point that name at Runway like any other and list it in
`runway.console.hosts`.

### 7.1 Self-managed TLS (optional)

By default Runway serves **plain HTTP behind a TLS-terminating reverse proxy** — that stays the
recommended topology. When no proxy is wanted, Runway can **terminate TLS itself**: put the certificate +
key in a **PKCS12 keystore** and enable it in the external `application.yml`:

```yaml
runway:
  tls:
    enabled: true
    keystore: /opt/runway-data/external/tls/runway.p12
    keystore-password: "…"        # a secret — this file is chmod 600
    key-alias: runway             # the keystore entry holding the server key
```

Set TLS **only** through `runway.tls.*`. Spring Boot's own `server.ssl.*` keys are filled from them by the shipped
`application.yml`; setting `server.ssl.*` yourself would bypass Runway's startup checks (keystore readable, password
set, alias present, each failing with a message that says what to fix), and the startup log line would still say
that TLS does not terminate here.

**Convert a PEM certificate to the PKCS12 keystore.** ACME clients (certbot, acme.sh) and most CAs issue
**PEM** files — a certificate (usually with its intermediate chain) plus a private key — while Runway wants a
**PKCS12** keystore. Convert them with `openssl`. For the typical Let's Encrypt layout under
`/etc/letsencrypt/live/<domain>/`, where `fullchain.pem` is the leaf certificate **plus** the intermediate
chain and `privkey.pem` is the private key:

```bash
openssl pkcs12 -export \
  -inkey privkey.pem \
  -in fullchain.pem \
  -name runway \
  -out runway.p12 \
  -passout pass:'CHANGE-ME'
```

- **`-name runway`** is the keystore **alias** — it MUST equal `runway.tls.key-alias` (default `runway`), or
  startup fails to find the key.
- **`-passout pass:'CHANGE-ME'`** sets the keystore **password** — it MUST equal
  `runway.tls.keystore-password` in the external `application.yml`.
- **`-in fullchain.pem`** must be the leaf certificate **followed by** its intermediate chain (that is exactly
  Let's Encrypt's `fullchain.pem`). If your cert and chain are in **separate** files, use
  `-in cert.pem -certfile chain.pem` instead.
- Add **`-passin pass:'<key-password>'`** if the private key is itself password-protected.
- The output is already PKCS12 — `runway.tls.keystore-type: PKCS12` is the default, so no extra flag is needed.
  `keystore-type` takes any keystore type the Java runtime knows (for example `JKS` for an older Java keystore); a
  type it does not know, like a file that is not of the stated type, stops startup with a message naming the
  keystore and the type.

Then lock it down (it holds the private key) and place it where `runway.tls.keystore` points:

```bash
install -o runway -g runway -m 600 runway.p12 /opt/runway-data/external/tls/runway.p12
```

**Verify** it before enabling TLS — both the alias and the password must match your config:

```bash
# lists the entries; must show one `runway` alias (prompts for the keystore password):
keytool -list -keystore runway.p12 -storetype PKCS12
# or inspect the certificate's subject / issuer / validity dates directly:
openssl pkcs12 -in runway.p12 -nodes -passin pass:'CHANGE-ME' | openssl x509 -noout -subject -issuer -dates
```

> **On renewal, just re-run the `openssl pkcs12 -export` above** (same `-out`, `-name` and password). With
> `watch-keystore` on (the default), Runway reloads the new certificate **live** — no restart. Wire that
> one-liner into your ACME client's `--deploy-hook` / renew hook.

Startup **fails fast** — with a precise message — if TLS is enabled with a missing/unreadable keystore or a
blank password, and one log line always states where TLS terminates (`TLS terminates HERE: HTTPS on port …`
vs `TLS does NOT terminate here: plain HTTP …`), so the topology is never ambiguous.

**Hot certificate reload (no restart).** With `watch-keystore: true` (the default when TLS is on), Runway
watches the keystore file and **reloads the certificate live when it changes** — so a renewal that rewrites
the keystore is picked up without a restart (only new TLS connections use the new cert; existing ones drain
naturally). Point your ACME client's deploy/renew hook at rewriting the PKCS12 keystore and you are done; a
half-written or invalid keystore is logged and the **previous** certificate keeps serving (degrade, don't
crash). Set `watch-keystore: false` to opt out and reload by restart instead.

**HTTP→HTTPS redirect + Let's Encrypt.** With TLS on, an optional **plain-HTTP listener** (default port 80)
301-redirects every request to the HTTPS listener — **except** `/.well-known/acme-challenge/`, which is
served as plain files from `acme-challenges-dir`, so **ACME HTTP-01** issuance/renewal keeps working with
the redirect on (point `certbot`/`acme.sh` `--webroot` at that directory, then convert the issued PEMs into
the PKCS12 keystore as shown above — the hot reload picks up the new certificate, no restart needed):

```yaml
runway:
  tls:
    redirect-http:
      enabled: true
      port: 80                    # binding <1024 needs CAP_NET_BIND_SERVICE — see §6 / §16
      acme-challenges-dir: /opt/runway-data/external/acme-challenges
```

The redirect answers at the **connector level** — nothing on the plain port ever reaches the application —
with hardened handling (ACME tokens are shape-checked and confined to the webroot; symlinks and over-sized
files are refused; malformed `Host`/path values are rejected `400`). `status` is the redirect's HTTP status:
`301` (the default, permanent), `308` (permanent, and the client keeps the request method), `302` or `307`
(temporary). Any other value stops startup with a message naming it.

> **The webroot's permissions are yours to guard:** everything in `acme-challenges-dir` is served to
> anyone on the plain port. Only the ACME client should be able to write there — never the application or
> an untrusted user (e.g. `chown` it to the user running certbot and keep it `700`/`750`).

**Privileged ports & firewall.** Binding 443 and/or 80 directly needs `CAP_NET_BIND_SERVICE`. An install with
`--tls-keystore` on 443 or with `--tls-redirect` already has it, and upgrades keep it. If you switched to these ports
afterwards by editing `application.yml`, give it with the drop-in shown in **§6**. Open both ports in the host firewall (e.g. re-run the installer with
`--firewall-ports 80,443 --open-firewall`, or your firewall tooling directly).

> **Verify:** `curl -sI https://<host>/ | head -1` answers over TLS; `curl -sI http://<host>/x` returns
> `301` with a `Location: https://…`; and with a file `t` in the webroot,
> `curl -s http://<host>/.well-known/acme-challenge/t` returns its content instead of a redirect.

Set `runway.tls.sni-host-check: false` only for a **re-encrypting proxy** that connects by the backend's IP
while forwarding the real Host (Jetty's SNI/Host consistency check would otherwise reject those requests);
leave it on everywhere else.

## 8. Configuration reference

Runway is configured through **`application.yml`**, following the house convention: a reference
copy of every default ships inside the jar (on the classpath), and your **external override** at
`external/application.yml` (i.e. `/opt/runway-data/external/application.yml`) is loaded **above** it
and wins key-by-key. Only set in the external file the keys you actually change. The first-run
wizard creates this file and writes the application-superadmin pin into it; add your own keys
alongside. Because it holds a secret (the pin, and any `runway.security.secret-key`), keep it mode
`600`, owned by the service user (**§16**).

Changes take effect on the **next restart** (`systemctl restart runway`), except where noted.
Runtime-editable resources — messages/i18n, templates, static assets — live under `external/` and
reload without a restart.

**Most settings are set in the console, not here.** Every setting that can be changed safely
while the server runs has a page in the application console: **Settings → Server settings**
(`/__app/config/server`), and the **Settings** button on Backups, Bandwidth, Activity → Logs and Plugins
(§8.5). Those pages save to `settings:` in `external/platform.yml`. What stays in `external/application.yml`
is what the server needs before it can start or what is only safe to change on the host: the port, TLS, the
secret key, the storage paths, clustering, the audit switch `runway.app-activity.enabled`, and the console's host
names `runway.console.hosts` (a wrong value would lock you out of the console, so the file is where you fix it,
**§8.6**). [SETTINGS-REFERENCE.md](SETTINGS-REFERENCE.md) lists every host-only key with its reason.

**One home per setting; the host wins.** The console's values are loaded *below* `external/application.yml`,
environment variables and command-line arguments. A key you set there by hand wins, and the console shows
that field disabled with the note *"Set on the server in external/application.yml, which wins over this
page."*, followed by what removing the line would do: the console holds the same value, or the value is the
default (removing it changes nothing); the console holds a different value (that one takes effect); or the console
holds none and the default takes effect. A list counts in either form: a comma string
(`trusted-cidrs: "10.0.0.5/32,::1/128"`) or a YAML list (`trusted-cidrs: [10.0.0.5/32, ::1/128]`).

**Moving a key from `application.yml` to the console: one restart, with "Use the file's value".** While a key's
line is in the file, its field is locked, so the console cannot be given a value first. Moving it the plain way —
delete the line, restart, set the value on the page, restart again — costs **two restarts, and between them the
server runs on the default**. For a key whose default differs from your value (your proxy's address in trusted
proxy addresses, a monitoring token, the CSP mode) that is a window with the wrong value. A
locked row whose removal would change something offers **Use the file's value** (**Use the server's value** when an
environment variable or a command-line argument sets it). It copies the value the server runs with now into the
console's store. Then:

1. On the locked row, click **Use the file's value** and confirm. The field stays locked (the file still wins), and
   the row now says *"The console holds the same value, so removing it there changes nothing."*
2. Delete the key's line from `external/application.yml`.
3. Restart once (`sudo systemctl restart runway`). Nothing changes: the console's copy is what the server reads now.

The value goes through the same checks as a save, and nothing is stored when one fails: a value the console
refuses (a catch-all trusted proxy, a value containing `${`, a number outside the console's range) stays on the host
until you correct it there. A token needs `runway.security.secret-key`: it is stored encrypted, like a token typed
on the page, and never shown. A token the host sets to `""` removes the console's stored token, since no token is
what runs. A blank value the console cannot hold is refused, because a blank field on the console means the default
(an empty `runway.compression.mime-types`, say, which compresses nothing): keep that one on the host. The copy is
recorded in the activity log, with the key and never the value. Values copied this way are listed with the
other changes saved since the start, but they change nothing until the line is removed and the server restarts.

Two keys do not follow this rule. **Password strength** (`runway.security.password-strength`): the value chosen
on Settings → Platform settings wins over `application.yml`, environment variables and command-line arguments,
and that field is never locked (§8.1). **The protection plugin's settings**: what its settings screen saves in
`external/plugin-settings/protection.yml` wins over `external/application.yml`, and the form shows no lock; an
environment variable or a command-line argument still wins over both.

**Keys.** The tables below list the most-used keys; anything not listed is left at its default. The
complete `runway.*` inventory, with types and defaults, is in
[CONFIGURATION-AND-DATA-STABILITY.md](CONFIGURATION-AND-DATA-STABILITY.md) §1, and your IDE completes every key
from the configuration metadata shipped in the jar.

**`runway.*` — the platform's own keys:**

| Key | Default | Meaning |
|---|---|---|
| `runway.storage.external` | `./external` | Root of the operator-editable folder (see **§5**). |
| `runway.storage.sites` | `<external>/sites` | Where per-site folders live. |
| `runway.storage.backups` | `./backups` (deployment root) | Where per-site backup archives are written (**§13**). |
| `runway.plugins.root` | `<external>/plugins` | Where plugins are installed and discovered (read from the environment before startup). The console's install/uninstall/enable/disable work in the same folder. |
| `runway.operator.ui-languages` | `[en, es]` | Languages the operator console's topbar switcher offers (BCP-47 codes, in display order). English and Spanish ship; any other language needs your own translation in `external/i18n/` (*Translating the console*, below). |
| `runway.operator.default-language` | `en` | Fallback console language when the browser requests none offered. |
| `runway.console.hosts` | _(empty)_ | The host names the operator console answers on (case and port ignored); every other host gets a `404` for the console's paths. Empty: the default site's host names and host names that serve no site. Host only, applies after a restart. **§8.6**. |
| `runway.compression.enabled` | `true` | Master switch for the single Brotli+gzip response-compression filter. Set `false` when a fronting proxy already compresses. Jetty's own gzip (`server.compression`) stays off either way. |
| `runway.compression.max-buffer-bytes` | `4194304` (4 MiB) | Largest response buffered to compress; anything larger streams through uncompressed. The buffer is held in memory **per response being sent**, so the worst case is this size times the busy request threads. `0` or a negative value (possible only in `application.yml`; the console takes 64 KiB to 256 MiB) sends every response uncompressed. |
| `runway.compression.mime-types` | `text/html`, `text/xml`, `text/plain`, `text/css`, `text/javascript`, `application/javascript`, `application/json`, `application/xml`, `image/svg+xml` | The content types that are compressed, matched on the type without its `charset`. **A list you set replaces this one**, so repeat the defaults you want to keep. An empty list in `application.yml` compresses nothing; a blank field on the console restores this default. Leave out formats that are already compressed (images, fonts, zip). |
| `runway.compression.gzip.enabled` / `.min-size` | `true` / `1024` | Offer gzip; skip bodies below the minimum. |
| `runway.compression.brotli.enabled` / `.quality` / `.min-size` | `true` / `5` / `1024` | Offer Brotli (needs the brotli4j native lib; falls back to gzip if absent). Quality `0..11`: lower is faster and compresses less, higher is smaller and costs more CPU on every response; 4 to 6 suit compressing as pages are sent. In `application.yml`, a value above 11 or below -1 stops startup (the library refuses it; -1 asks for the library's own default). |
| `runway.security.app-master-id` | _(blank)_ | The pinned application-superadmin principal id. **Written by the wizard** — its presence flips WIZARD → ACTIVE. Do not edit by hand. |
| `runway.security.password-strength` | `STANDARD` | Argon2id memory-cost profile for **new** passwords: `STANDARD` (19 MiB) or `HIGH` (32 MiB). The Settings screen can override this (**§8.1**). |
| `runway.security.max-concurrent-hashes` | `8` | Most password hashes computed at once before requests fail "busy" — bounds heap under a login flood. Read once at startup (Settings → Server settings → Capacity, or here); at least `1` — `0` or a negative value stops startup. |
| `runway.security.acquire-timeout-millis` | `2000` | How long a request waits for a hashing slot before failing "busy". |
| `runway.security.secret-key` | _(installer-set)_ | Master key for AES-256-GCM encryption of every stored secret (platform SMTP password or OAuth2 client secret, per-site CAPTCHA secrets, `secret`-typed plugin settings). The installer generates one on first install and stores it in a root-only systemd drop-in as `RUNWAY_SECURITY_SECRET_KEY` (see `--secret-key`, §3); Spring's relaxed binding maps that env var to this property. **While blank the secret cipher is unavailable and no secret can be saved** — the app console shows a persistent warning on every page until it is set. Holds a real secret — never in the classpath default. |
| `runway.proxy.forwarded-headers` | `true` | Whether to honor `X-Forwarded-*` at all (still only from trusted peers; the standard `Forwarded` header is never read) — see **§7**. |
| `runway.proxy.trusted-cidrs` | `127.0.0.1/32`, `::1/128` | Proxy peers whose forwarded headers are trusted. The **bound default is empty** (trust nothing); the shipped file seeds loopback. **§7**. |
| `runway.http.min-request-data-rate` | `4096` | The slowest a request body may arrive, in **bytes per second**, before Runway answers `408 Request Timeout` and frees the request thread. It applies to every body — login and other forms, multipart uploads, raw bodies alike. Without it a client sending a byte now and then kept a thread for as long as it liked (the idle timeout only limits one stalled read). Only the time spent waiting for the client counts, from the first second of waiting on, so a server slow to start reading is never blamed, and a body that has arrived complete is never refused, however late its last packet came. Sending part of a body fast earns at most 30 seconds of waiting, so a large body cannot buy a long drip; a client pausing between packets is cut when its next packet arrives, within one idle timeout. 4096 (4 KiB/s) is below any real connection; `0` turns the check off. Set on **Settings → Server settings → Request limits**; applies after a restart. |
| `runway.i18n.cache-seconds` | `5` | How often a message lookup **outside** a public page (an error page, a plugin's background text) and the operator console's own bundle re-check their files on disk (`0` = on every lookup). Public pages re-check on every request, so a bundle edited on disk shows on the next page view, under a new ETag. |
| `runway.csp.mode` | `report` | Content-Security-Policy delivery: `report` (report-only — violations are logged, nothing is blocked), `enforce`, or `off`. Upper or lower case; blank means `report`. Any other value (`enforced`, say) stops startup with a message naming it. See **§8.4**. |
| `runway.csp.strict-dynamic` | `true` | `'strict-dynamic'` on the nonced pages. `false` rolls the whole estate (console **and** public pages) back to the host allowlist. **§8.4**. |
| `runway.csp.public-strict-dynamic` | `true` | Public tenant pages get a nonce derived from the page version + `'strict-dynamic'`, stamped at the edge on the scripts the allowlist policy would have run (same-origin or allowlisted `src`; never inline). `false` keeps public pages on the host allowlist. Needs `strict-dynamic: true`. **§8.4**. |
| `runway.csp.extra-hosts` | `[]` | Extra hosts an operator allowlists for the fetch directives and `frame-src` (a CDN, an analytics host), on **every site's** pages (the console calls it *Extra allowed hosts (all sites)*). A host only one site needs belongs on **Settings → Embed hosts**. **§8.4**. |
| `runway.http-cache.public-pages` | `public, max-age=300, stale-while-revalidate=60` | The `Cache-Control` header of a public page: browsers and shared caches may keep it 5 minutes, then serve it one more minute while they check for a newer version. A page also carries an ETag that changes with its content, so a cache that asks again gets the new page at once. (A page whose content widget reads the request's query, such as a search results page asked with a query, is sent `private, no-cache` and no ETag instead.) `no-cache` makes every visit ask. A page's own **Cache-Control** field in the page editor wins over this value. Blank means this default. |
| `runway.http-cache.assets` | `public, max-age=86400` | The header of a site's files under `/static/` (one day), and always of its documents under `/static/doc/`, which can be replaced in place. Blank means this default. |
| `runway.http-cache.plugin-assets` | `public, max-age=3600` | The header of the files plugins serve under `/__plugin/<id>/static/` (their styles and scripts): browsers keep them an hour, then ask again and get a `304` while the file is unchanged (each file carries an ETag of its content). An hour rather than a day because a plugin file keeps its name across upgrades. Accepts `public`, `private`, `max-age`, `s-maxage`, `stale-while-revalidate`, `stale-if-error`, `must-revalidate`, `proxy-revalidate`, `no-transform`, `immutable`, and `no-cache` or `no-store` alone; another value logs a warning and uses the default. Blank means this default. |
| `runway.http-cache.immutable-extensions` | `woff2, woff, ttf, otf, eot` | Files under `/static/` with these extensions (any case, with or without the dot) get the immutable header below instead. Add `css` or `js` only if the site's file names change with their content (`app.3f9c2.js`): a browser never asks again for an immutable file, so an edited `site.css` under the same name would stay old for a year. An empty list makes no file immutable. |
| `runway.http-cache.immutable-assets` | `public, max-age=31536000, immutable` | The header for those files: kept a year and never re-checked, not even on reload. Blank means this default. |

**`server.*` / `spring.*` — framework keys Runway pins (change with care):**

| Key | Shipped value | Why |
|---|---|---|
| `server.port` | `8080` (Boot default; unset) | The HTTP listen port. Override with `RUNWAY_OPTS=--server.port=N`. |
| `server.forward-headers-strategy` | `none` | The container's own forwarded-header handling is **off** — Runway's gated filter is the sole handler, so a direct client cannot spoof `X-Forwarded-*` (**§7**). Do not turn this on. |
| `server.error.whitelabel.enabled` | `false` | Runway serves its own error pages; the Spring whitelabel page is disabled. |
| `spring.config.import` | `optional:file:external/application.yml` | Loads your external override above the classpath defaults. |
| `spring.web.resources.add-mappings` | `false` | The page controller owns the catch-all `/**`; Boot's default static `/**` mapping is off so it can never shadow a page. |
| `spring.servlet.multipart.max-file-size` / `max-request-size` | `256MB` | The largest upload the platform accepts is a site backup zip (restore-from-upload); the 1 MB Boot default would reject any real backup. |
| `spring.security.filter.order` | `100` | Runs Spring Security's chain **after** the multi-tenancy filters, so a chain can read the bound site — required for tenant isolation. Do not lower it. |
| `spring.task.scheduling.pool.size` | `4` | Threads in the **one** scheduler pool every periodic task runs on: automatic backups (when enabled, §13) and plugins' scheduled work. Scheduling is always on. Four, so a long nightly backup cannot hold up short plugin jobs; threads start only as tasks are scheduled, so an idle pool costs nothing. Raise it only if a thread dump shows every `runway-scheduler-*` thread busy. |
| `spring.task.scheduling.thread-name-prefix` | `runway-scheduler-` | Names those threads, so they are recognisable in a thread dump (`jcmd <pid> Thread.print`). |
| `spring.thymeleaf.servlet.produce-partial-output-while-processing` | `false` | A page is rendered whole before its first byte is sent. Streamed instead (Boot's default), an error halfway through a render would leave the visitor a blank or cut-off page instead of the error page. Do not turn it on. |
| `server.servlet.session.timeout` | `30m` | How long a signed-in session (the operator console and every site console) may sit idle before it signs out. A duration such as `30m`, `2h` or `1d`; a bare number means seconds. Set on **Settings → Server settings → Sessions**; applies after a restart. With clustering on, `runway.cluster.session-ttl` decides instead — keep the two equal (§2). |
| `server.jetty.threads.max` / `.min` | `200` / `8` | The request thread pool (§2, *Capacity*). Set on **Settings → Server settings → Capacity**; applies after a restart; the minimum may not exceed the maximum. |
| `server.jetty.connection-idle-timeout` | `30s` | How long a connection may make no progress before Jetty closes it — a request with nothing more to read, or a response the client has stopped reading. Jetty's own default, stated so it has one home. It is what ends a client that reads a large response too slowly; a request body that keeps trickling in is ended by `runway.http.min-request-data-rate` instead. |
| `server.ssl.*` | from `runway.tls.*` | Filled from `runway.tls.*` (§7.1). Never set these yourself: they would bypass Runway's TLS startup checks. |
| `management.endpoints.web.exposure.include` | `health, prometheus` | The only actuator endpoints served. Do not add others: everything under `/actuator/` is open to the superadmin and System admins without the console's CSRF protection, and an endpoint such as `heapdump` would hand out what the server holds in memory, secrets included. |
| `management.endpoint.health.probes.enabled` | `true` | Adds `/actuator/health/liveness` and `/actuator/health/readiness` for a supervisor or load balancer. |
| `management.endpoint.health.show-details` | `when-authorized` | The public health answer is only `UP`/`DOWN`; a signed-in operator also sees each check (e.g. the data folder's path). |
| `logging.level.<logger>` | Boot's (`INFO`) | Raise one logger for diagnosis, e.g. `logging.level.mx.quest.runway.head: DEBUG`. Runway's own loggers are `runway.access` (one line per public request), `runway.protect` (edge rejections) and the `mx.quest.runway.*` packages. Set here, it applies after a restart (or set it for one run with `RUNWAY_OPTS=--logging.level.mx.quest.runway=DEBUG`). Easier: **Activity → Logs → Log levels** (`/__app/logs/levels`) changes a level at once and keeps it across restarts (in `platform.yml`); a level set here wins and shows locked there. **Default** on that screen puts a logger back to the level set here, or else its parent's; `ROOT` goes back to `logging.level.root`, or else `INFO`. Turn it back down afterwards: `DEBUG` on a busy server writes a lot. |
| `logging.file.name` / `logging.file.path` | _(unset)_ | Unset, everything goes to the console stream (journald under systemd). Setting either also writes a log file, rotated by Spring Boot; see Operations §3. |

Plugin settings are **not** placed in `application.yml`: app-level plugin settings live in their own
file per plugin under `external/plugin-settings/<id>.yml` and are edited from the console (**§11**).

**Translating the console.** The console's text ships in the jar as `i18n/operator-messages.properties` (English,
also the fallback for any language without its own file) and `operator-messages_es.properties` (Spanish). To
correct a wording or add a language, put a file of the same name in `external/i18n/`: for example
`external/i18n/operator-messages_fr.properties` for French. It needs only the keys you change; any key it lacks
comes from the shipped files. Copy the keys from the shipped file (`unzip -p <runway-app-…-exec.jar>
'BOOT-INF/classes/i18n/operator-messages.properties'`) and write the file in **UTF-8**. A new language also needs
its code added to `runway.operator.ui-languages` (Settings → Server settings → Console languages; applies after a
restart) and a name for the switcher, the key `op.lang.<code>` (`op.lang.fr=Français`); without it the switcher
shows the key itself. Edits to these files are picked up while the server runs, within
`runway.i18n.cache-seconds` (5 seconds by default). Mind one trap: a key in the unsuffixed
`external/i18n/operator-messages.properties` wins over the **shipped Spanish** text too, because your folder is read
before the shipped files; put a Spanish correction in `operator-messages_es.properties`.

The console's **Settings** menu is a dropdown with two entries: **Platform settings** (§8.1, the
editable settings below) and **About Runway** (§8.2, a read-only view of the running platform).

### 8.1 Platform settings

A small set of platform-wide settings is edited from the operator console at **Settings → Platform
settings** (`/__app/settings`, restricted to the application superadmin or a wildcard admin) and
stored in `external/platform.yml` — application-realm state kept **outside every tenant folder**, so
it never travels in a per-site backup:

- **Default site** — the catch-all that answers requests whose `Host` isn't bound to any other site
  (see **§10**). Changing it takes effect immediately.
- **Password strength** — the Argon2id memory-cost profile (`STANDARD` = 19 MiB, `HIGH` = 32 MiB) used
  for **new** passwords. It overrides the `runway.security.password-strength` value from
  `application.yml` and applies on the next restart; existing passwords are unaffected (Argon2id hashes
  are self-describing, so verification never breaks when the profile changes).
- **Node name** and **Response compression** — a display name for this node (shown on About) and the
  master + per-encoding (Brotli / gzip) compression switches. Both apply immediately. The name shown is this field
  when it is set, else `runway.node.name` from the server's configuration, else the server's host name. So in a
  cluster, leave the field blank and give each node its own `runway.node.name` (an environment variable such as
  `RUNWAY_NODE_NAME=web-1` suits that); a value saved in the field wins over it. The name is only displayed; nothing
  else uses it.
- **Platform mail (SMTP)** — the application's own mail account: host, port, From address, and optional
  authentication (a username and password, or an OAuth2 token for Microsoft 365) with STARTTLS or implicit TLS. With STARTTLS chosen, a relay that does
  not offer it fails the send rather than receiving the password in the clear, and with either TLS mode the
  relay's certificate must name its host. A relay that does not accept the connection within 10 seconds, or
  stalls for 30 seconds while sending, fails the send. It is used to send
  **password-recovery** links and any future operator notifications, for **both** the application and
  site consoles — and is **never** used for mail a site sends itself (a site keeps its own SMTP
  relay). The password is stored **encrypted** (leave the field blank on a later save to keep the stored
  one, as long as the server stays the same: see below), and a **Send test email** button verifies the account. See **§8.3**. The fields in detail:
  - Mail counts as configured — the *Forgot your password?* links appear and the test button works — only when
    **both** the host and the From address are set.
  - **Port** — blank means `587`; otherwise a number from 1 to 65535.
  - **STARTTLS** (a plain connection upgraded to TLS, usually port `587`) or **SSL** (TLS from the first byte,
    usually port `465`). If both are ticked, SSL is used and STARTTLS is ignored. With neither, the connection is
    not encrypted, and neither is the password when authentication is on.
  - **Authentication** — off by default; while it is off the username and password are not sent.
  - **Sign-in method** — *Username and password*, or *OAuth2 token (Microsoft 365)*: see *Microsoft 365 and other
    OAuth2 mail servers* below. The fields of the method not chosen are hidden but kept, so switching back does not
    lose them.
  - **A stored secret follows its server.** A blank password field keeps the stored password only while the host,
    port and username stay the same; when one of them changes, the password is cleared, the page says so, and it
    must be entered again. The OAuth2 client secret is kept only while the token endpoint, the client ID, the mail
    server (host and port) and the scope stay the same, because the secret goes to the endpoint and the token minted
    from it goes to the mail server; an OAuth2 account where one of them changes is not saved until the secret is
    entered again. This way someone who may edit this screen, but does not know the secret, cannot have Runway send
    it, or a token made from it, to a server of their choosing. The activity log's `platform.settings-changed` line names the settings each save
    changed (never their values) and any secret it cleared.
  - **Otherwise the password cannot be removed from this form:** with the same server, a blank field keeps the
    stored one. To remove it, delete the `smtp:` block (or its `password:` line) from `external/platform.yml` and
    restart (see *Editing platform.yml by hand* below). The same goes for the OAuth2 client secret
    (`oauthClientSecret:`).

**Microsoft 365 and other OAuth2 mail servers.** Microsoft is retiring password sign-in to SMTP (SMTP AUTH with
"Basic authentication") in Exchange Online: Microsoft has announced that it is turned off by default from the end
of December 2026 (Message Center MC786329), and it already fails in a tenant whose security defaults or
Conditional Access block legacy sign-in. Runway can sign in the way Microsoft
requires instead: it obtains an **OAuth2 access token** with an application's own credentials (the
*client-credentials* grant) and presents it to the mail server with SASL `XOAUTH2`. In the Microsoft 365 admin
tools, once:

1. In **Microsoft Entra ID → App registrations**, register an application. Note its **Application (client) ID** and
   the **Directory (tenant) ID**, and under **Certificates & secrets** create a **client secret** (note its *value*,
   and when it expires).
2. Under **API permissions**, add the application permission **Office 365 Exchange Online → SMTP.SendAsApp** and
   **grant admin consent**.
3. In Exchange Online PowerShell, register the application's service principal and give it the mailbox Runway sends
   as: `New-ServicePrincipal -AppId <client id> -ObjectId <object id of the enterprise application>`, then
   `Add-MailboxPermission -Identity <mailbox> -User <service principal id> -AccessRights FullAccess`.
4. Make sure **Authenticated SMTP** is enabled for that mailbox (Microsoft 365 admin center → the user → Mail →
   Manage email apps). OAuth2 still goes through SMTP AUTH.

Then on this page: host `smtp.office365.com`, port `587`, **STARTTLS** on, **Authentication** on, sign-in method
**OAuth2 token**, and:

| Field | Microsoft 365 value |
|---|---|
| Username | the mailbox to send as (blank uses the From address) |
| Token endpoint | `https://login.microsoftonline.com/<tenant id>/oauth2/v2.0/token` |
| Client ID | the application (client) ID |
| Client secret | the secret's value (stored encrypted, like the password) |
| Scope | `https://outlook.office365.com/.default` |

Save, then **Send test email**. A failure says which step failed: *"no OAuth2 token"* means the token endpoint
refused or could not be reached, and the message carries Microsoft's reason (an `AADSTS…` code: a wrong secret, a
wrong tenant, missing consent); any other failure came from the mail server after the token was issued (for
Microsoft, a missing `SMTP.SendAsApp` permission or mailbox permission, or Authenticated SMTP turned off).

How it behaves:

- **TLS is required.** An OAuth2 account must use STARTTLS or implicit TLS; the page refuses to save one without it,
  because a token, like a password, signs in whoever reads it.
- **The token endpoint must be `https`** (`http` only to this machine's loopback address, for a local identity
  provider). Runway does not follow a redirect from it, so the client secret is only ever sent to the address you
  entered.
- **Tokens are reused** until five minutes before they expire (Microsoft's last about an hour), so a burst of
  recovery mails costs one token request. A mail server that rejects a token makes Runway fetch a new one on the
  next send.
- **Only `XOAUTH2` is offered** once OAuth2 is chosen: a server that does not support it fails the sign-in rather
  than receiving the token as a password.
- **Remember the secret's expiry.** Microsoft client secrets expire (24 months at most); when it does, recovery mail
  stops with an `AADSTS7000222` error. Create a new secret and enter it here before then.
- **Other providers.** Any mail server that accepts `XOAUTH2` tokens issued by a client-credentials grant works the
  same way; enter that provider's token endpoint and scope. (Google Workspace issues such tokens to service accounts
  through a signed JWT instead, which Runway does not do; use an app password or an SMTP relay there.)
- **Instead of OAuth2**, Microsoft 365 also accepts mail without any sign-in through an **inbound connector** that
  trusts the server's fixed public IP address (port 25, Authentication off in Runway). That is set up entirely in
  Exchange Online.

Leaving a field blank restores its default (no catch-all site; the `application.yml` password
strength; the node name from `runway.node.name` or the host name; no platform mail). The three compression
switches are the exception: a tick box has no "default" state, so **the first save of this page stores all three**,
and from then on they, not the shipped defaults, decide. A switch set in the server's `external/application.yml`
still wins over the saved one, and the page then shows it locked with where it is set. To hand a switch back to
the configuration, set it in `application.yml`, or delete it from `platform.yml` by hand.

**What `external/platform.yml` holds.** The fields of this page are top-level keys:

```yaml
defaultSiteId: acme          # the catch-all site's id
passwordStrength: HIGH       # STANDARD | HIGH
nodeName: web-1
compressionEnabled: true
brotliEnabled: true
gzipEnabled: true
smtp:
  host: smtp.example.com
  port: 587
  auth: true
  username: runway@example.com
  password: ENC(...)         # encrypted with runway.security.secret-key
  from: runway@example.com
  starttls: true
  ssl: false
  authMethod: password       # password | oauth2
  oauthTokenUrl: https://login.microsoftonline.com/<tenant id>/oauth2/v2.0/token   # oauth2 only
  oauthClientId: 00000000-0000-0000-0000-000000000000
  oauthClientSecret: ENC(...)
  oauthScope: https://outlook.office365.com/.default
```

The same file also keeps `settings:` (Server settings and the policy cards, §8.5), `siteQuotas:` (each site's
bandwidth cap, §12), `siteStorageCeilings:` (each site's storage limit, §10), `embedHosts:` (§8.4) and `logLevels:`
(the levels set on Activity → Logs → Log levels).

**Editing platform.yml by hand.** The running server reads this file once and keeps it in memory, so a hand edit
applies only **after a restart** — and a save of any console page that writes this file before that restart writes
the old values back over your edit. Stop the service, edit, start it. An unknown `passwordStrength` is logged and
ignored (the `application.yml` profile is used).

**Clear template cache.** Below the settings form, **Clear template cache** empties the cache of parsed
page and chrome templates on **the node that answers you**. You normally never need it: Runway keeps the
cache on for speed, evicts exactly the right entries whenever a page or fragment is changed
**through the console** or a site is deleted or restored, and re-reads a template whose file changed
**outside** the console (edited on the server, copied in by a deployment script) on its next use — a
cached parse counts only while its files are unchanged on disk (Operations §5.2). It stays as a
backstop, e.g. for a file system whose timestamps cannot be trusted. (For **one** site, the site reload
of §12.2 does the same for that site's templates only, and leaves every other site's cache warm.) It is harmless — each page re-reads its template on its next request, which costs a few
milliseconds once — and is recorded in the activity log (`platform.template-cache-cleared`). In a
cluster it clears only the node that served the click: open the console on each node and run it there.
Restricted to the application superadmin or a wildcard admin.

### 8.2 About Runway

**Settings → About Runway** (`/__app/about`) is a **read-only** view of the running platform — useful
when diagnosing an install or filing a report. Unlike Platform settings it changes nothing and exposes
no secret, so it is visible to **any** signed-in operator. It has four parts:

- **Version & build** — the running version and, when the build carries it, the build timestamp.
- **Effective configuration** — a curated table of the non-secret settings in force: whether TLS
  terminates here or upstream, the HTTP port, reverse-proxy trust, the password-hashing profile,
  whether secret encryption is configured, the data and backups directories, the backup
  schedule/retention/off-host mirror, and the message-reload interval. These come from
  `external/application.yml`; **a restart is needed to change them**. No secret value is ever shown —
  secret-adjacent state (e.g. the master secret key) appears only as *configured / not configured*.
- **Runtime environment** — the node serving the request: active profiles, Java/JVM/OS, processors,
  heap, data-disk free/total, uptime, start time, PID, host name, time zone and locale — plus the
  versions of the bundled components (Spring Boot, Jetty, Thymeleaf, Jackson, and more). In a
  multi-node deployment these describe **whichever node answered you**.
- **This request** — the request that loaded the page as Runway saw it: the connection it came from and whether
  that is a trusted proxy, the `X-Forwarded-For` list as received, the client address Runway used, and the scheme
  and host. It checks a reverse-proxy setup from the browser (§7).

### 8.3 Password recovery

Both sign-in pages — the application login (`/app/login`) and each site's login (`/login`) — offer a
**"Forgot your password?"** link that lets a user reset their own password by email, without an operator.
The link appears **only when platform mail is configured** (§8.1) **and the emailed link has an address to point
at** (below); until then, recovery is done manually (reset an application user from **§9**, a tenant-superadmin
from **§10**, or a site sub-user from the site console).

How it works:

1. The user clicks **Forgot your password?** and enters the **email address** of their account. Every
   account type can recover — application users, tenant-superadmins, and site sub-users — **provided it
   has a recovery email set** (captured when the account is created, and editable afterwards by an
   administrator or by the user on their own **Account** page — `/__app/account` or `/__admin/account`, where
   changing it asks for the current password; make sure operators and site managers set one). Five wrong
   current passwords in a row on the Account page, for the email or the password, sign that session out, even
   when they are sent all at once; each wrong one is journaled as `account.password-check-failed` with the
   client's IP (`ip=203.0.113.9`) and counts toward the network's sign-in lockout.
2. The platform emails a **single-use link that expires in one hour**. For security the page always
   shows the same *"if an account with that email exists…"* confirmation, whether or not any account
   matched (no account enumeration).
3. Opening the link lets the user choose a new password. Reset links are stored only as **hashes**
   (`external/recovery-tokens.yml`, an ephemeral file that is **not** part of any backup), are used
   once, and requesting a new link invalidates the previous one.

**Isolation.** A link is scoped to its realm and site: an application link can never reset a tenant
account, and a site's link works **only on that site's own host**. A tenant-superadmin who manages
several sites has one password; recovering it from any of their sites resets that single account.

**Where the emailed link points.** Never at the host name the request came in on: anyone can send a request that
names any host (a made-up `Host`, or `X-Forwarded-Host` through a proxy that passes it on), and a reset mail with a
link to that host would hand the sender the token. The address comes from what Runway was told:

- **A site's sign-in:** the site's own address: its **base URL**, or else `https://` and its **first** host name,
  with no port. A site with no host name offers no recovery, and nor does one whose first host is `localhost` (or
  `127.0.0.1`, `::1`) and that has no base URL: that is what the setup wizard gives the default site, and
  `https://localhost` reaches nobody who reads the mail. **A site served over plain `http` or on a port other than
  443 needs its base URL set** (§10, *The site's base URL*), or its links point at `https://` and port 443.
- **The application console:** with `runway.console.hosts` set (§8.6), the listed name the request came in on (as
  listed: case and a trailing dot in the request do not count), the request's scheme, and the port listed with the
  name if any, else the request's. The request's port is whatever the client wrote in its `Host` header (through a
  proxy too: `X-Forwarded-Host` carries it), so list the name with its port (`ops.example.com:443`) to fix it.
  Without `runway.console.hosts`, the **default site**'s address as above, on which the console also answers;
  without one, the console offers no recovery: set `runway.console.hosts`, or give the default site a public host
  name first, or a base URL.

**Who decides the console's link.** Without `runway.console.hosts`, whoever sets the default site's hosts or base URL
decides where the platform accounts' reset links go. So only a platform administrator (the application master or a
wildcard administrator) can edit the default site's hosts, even where `MANAGE_SITE_HOSTS` is delegated, and a
restore made from the site's own console keeps the site's hosts and base URL (§10). Setting `runway.console.hosts`
takes the default site out of it altogether.

The address a link points at is logged at INFO when one is requested (`a link, if mailed, points at …`), and a
request that finds no address is logged at WARN with the reason.

Beyond that there is nothing to configure: recovery is on once mail works. Verify it with **Send test email**
(§8.1), then exercise a full round-trip from a login page and check that the link in the mail points at the
address you expect.

### 8.4 Content-Security-Policy and the public-page nonce secret

Every HTML response carries a Content-Security-Policy header (`runway.csp.*`, **§8**). Out of the box it
is **report-only**: violations are logged by the in-kernel collector (`/__csp-report`, WARN level) and
nothing is blocked. Review the log, then set `runway.csp.mode: enforce` in the external
`application.yml` and restart.

**The directives.** The policy is built once at startup from `runway.csp.*` (Settings → Server settings →
Content-Security-Policy, or `application.yml`; every field applies after a restart, and SETTINGS-REFERENCE.md lists
each default). Each directive key — `default-src`, `script-src`, `style-src`, `img-src`, `font-src`, `connect-src`,
`base-uri`, `form-action`, `frame-ancestors` — is the source list written after the directive's name, **exactly as
you type it** and not checked, so quote keywords the CSP way (`'self'`, `'none'`). Runway then adds to it:

- `extra-hosts` (*Extra allowed hosts (all sites)* on Server settings: every site gets them) and the hosts plugins
  declare (reCAPTCHA's, for instance) — `extra-hosts` to `default-src`,
  `script-src`, `style-src`, `img-src`, `font-src`, `connect-src` and an added `frame-src`; plugin hosts to
  `script-src`, `connect-src` and `frame-src` only;
- the response's nonce (and `'strict-dynamic'` where it applies) to `script-src`, which is therefore always sent;
- on a site's public pages, the embed hosts that site switched on (below), each to its own directive.

A **blank value in `external/application.yml`** (`runway.csp.default-src: ""`) leaves that directive out. For
`style-src`, `img-src`, `font-src` and `connect-src` the browser then uses `default-src`; with `default-src` blank
too, those are unrestricted. `base-uri`, `form-action` and `frame-ancestors` do **not** fall back to
`default-src`: blank removes that protection (`frame-ancestors` is what stops other sites from framing yours).
On the Server settings screen a blank field restores the default instead (a form field cannot say "nothing"); to
omit a directive, set its key to `""` in `application.yml`, and the console then shows the field locked.
`object-src 'none'` is always sent and has no key.

- `runway.csp.report-uri` — where browsers send violation reports: Runway's own collector at `/__csp-report` by
  default, which writes them to the log at `WARN`. Any URL is used as given, so a report collector of your own
  works too. A blank value in `application.yml` (`runway.csp.report-uri: ""`) sends no reporting directive at all
  (no `report-uri`, `report-to` or `Reporting-Endpoints`), so `report` mode then only shows violations in each
  visitor's browser console. On the Server settings screen a blank field restores `/__csp-report`.
- `runway.csp.upgrade-insecure-requests` — `true` adds `upgrade-insecure-requests`: the browser fetches every
  `http://` image, script or style of a page over `https://` instead. Turn it on only once every host your pages
  load from serves `https`, or those resources stop loading.

**What `script-src` looks like.** The console and login/recovery pages get a fresh nonce per request
plus `'strict-dynamic'`. **Public site pages** get `'nonce-<x>' 'strict-dynamic'`
too, where the nonce is **derived from the page version** (its ETag): every visitor and every cache
sees the same value until the page changes, so shared caching (`public, max-age=…`) and `304`
revalidation keep working exactly as before. The response edge adds that nonce to **the scripts the
allowlist policy would have run** — a `<script src>` on the site's own origin (relative URLs included) or
on a host the policy names (`runway.csp.script-src`, `runway.csp.extra-hosts`, and plugin hosts such as
reCAPTCHA's) — wherever it comes from: the site's templates, plugin widgets, head contributors. With the
shipped `script-src 'self'`, an **inline** `<script>…</script>` is never stamped, and a script on any other
host is never stamped either, so both stay blocked in `enforce` mode exactly as they were before this
release; a `nonce` attribute already written in the markup is overridden, so a copied nonce cannot unlock a
script either. One exception: if you wrote `'unsafe-inline'` into `runway.csp.script-src` yourself (and no
`'nonce-…'`, `'sha…-…'` or `'strict-dynamic'` token next to it), the old policy ran inline scripts, so
inline scripts are stamped too and keep running. The stamp never runs a script the old policy blocked: a
script on a host outside the policy stays unstamped either way. Add any of those three tokens and the
exception ends: inline scripts are no longer stamped. `'self'` and the host allowlist
stay in the header as the fallback for browsers that predate `'strict-dynamic'`. *Why the stamp follows the
allowlist:* under `'strict-dynamic'` the browser runs a script only if it carries the nonce, so stamping
everything would have turned scripts the allowlist blocked — markup injected into a page included — into
scripts that run. *Why `'strict-dynamic'` at all:* a script the allowlist trusts may now load further
scripts itself (`document.createElement('script')`) on any host — reCAPTCHA's loader fetching its own
chunks is the case it exists for — while a modern browser no longer lets the allowlist alone authorize a
parser-inserted script. So add a host to `extra-hosts` only if you also trust what its scripts load.

Responses with no page version — error pages, plugin endpoints, the maintenance page — keep the plain
`'self'` + host-allowlist policy. A page larger than the 8 MiB rewrite buffer is sent unrewritten with
the allowlist policy and `Cache-Control: no-store`. So is a page whose rendered markup **already contains
its own nonce** (in any spelling): markup copied the public value in — for example into an
`<iframe srcdoc>`, whose document inherits the page's policy — and the log shows a `WARN` *"Public page …
already contains its own CSP nonce"*. Find the content that carries it (a widget's settings, a site
parameter, a plugin's data: the inputs that do not change the page version) and remove it. *Why:* the
stamp can override a `nonce` on a `<script>` tag but not one inside an attribute value, so that response
simply gets no nonce to match.

**The secret: `external/csp-nonce.key`.** 32 random bytes, created on the first page request that needs
it, mode `600`, owned by the service user. It is not a credential (it cannot sign anyone in), but keep it
private: without it the nonce of a page version cannot be computed in advance from the ETag alone.

- **Rotate** it by deleting the file and restarting the service: every nonce changes, and — because
  the page version folds a fingerprint of the secret — every page's ETag changes with it, so no browser
  or CDN can pair an old page body with a new header. Expect one full re-render per page afterwards.
- **Several nodes** (clustering, §2): give every node the same file (copy it, `600`, same owner) so a
  `304` from one node matches a page cached from another. With different files each node simply has
  its own ETags — still correct, just fewer `304`s.
- **If the file is unusable** (unreadable, truncated), public pages fall back to the allowlist policy and
  an ERROR is logged once at the first page request; nothing breaks. Fix or delete the file and restart.

**Switches.** `runway.csp.public-strict-dynamic: false` puts public pages back on the host allowlist
(the console keeps its nonces); `runway.csp.strict-dynamic: false` rolls back everything. Either
change moves every public page's ETag once (the page bodies change).

**What changes in `enforce` mode, compared with the allowlist policy.**

| Script | Before (allowlist) | Now (`public-strict-dynamic: true`) |
|---|---|---|
| `<script src>` on the site's origin or an allowlisted host | runs | runs (stamped) |
| inline `<script>…</script>` in a template, or injected into the page | blocked | blocked (never stamped) — unless `script-src` has `'unsafe-inline'`: then it ran before and runs now (stamped) |
| `<script src>` on a host outside the policy | blocked | blocked (never stamped) |
| a script added with `document.write` | runs if its host is allowlisted | **blocked** |
| an inline-SVG `<script href>` (no `src`), or a `<script src>` with text inside | its file ran if allowlisted | **blocked** (never stamped) — unless `script-src` has `'unsafe-inline'`: then it is stamped, as long as the URL it names is allowed |
| a script an allowed script inserts with `document.createElement` | runs if its host is allowlisted | **runs, any host** (`'strict-dynamic'`) |
| `<link rel="modulepreload">` / `prefetch` / `preload as="script"` on a host outside the policy | not fetched | **removed from the page** (never fetched) |

**What a site's designer should know.** Scripts loaded from the site itself or from an allowlisted host
keep working: they are stamped automatically. With the shipped `script-src 'self'`, inline `<script>`
blocks do not run under `enforce` — move the code into a `.js` file the page loads
with `<script src>`. (If the operator put `'unsafe-inline'` into `script-src`, they run.) A script added by
`document.write` is blocked (use `<script src>` or `document.createElement('script')` instead); inline
`onclick=` handlers and `javascript:` links are blocked too. Write script URLs plainly: a URL the edge
cannot read unambiguously (a backslash, a character reference in the host, three or more leading slashes
such as `///cdn.example/x.js`, a cross-origin `<base href>` before a relative URL) is not stamped, so it is
blocked. A stamped script is written `<script src="…"></script>` with nothing between the tags: a
`<script src>` that also carries text is not stamped (inside an inline `<svg>` the browser ignores `src`
and runs that text), and neither is a script without `src` — `href`/`xlink:href` do not count, because an
HTML `<script href>` runs its text. **Inline-SVG scripts** (`<svg><script href="…">`) are therefore
blocked under `enforce`; load the code from the page with a normal `<script src>` instead, and keep scripts
out of an SVG's `<title>` or `<style>` too (the edge reads those as text and leaves the script unstamped, so it is
blocked). **Preload hints:** `<link rel="modulepreload" href="/js/app.js">` next to the module's own
`<script type="module" src="/js/app.js">` keeps working — the preload is fetched once and reused, the stamped
script runs, its `import`s run, and the browser reports nothing (checked in Chrome 155). A classic
`<link rel="preload" as="script">` is the exception: the browser refuses the preload itself (a CSP message in
the console, and a violation report where the browser sends one), then loads and runs the stamped
`<script src>` normally — drop such hints rather than read the message as an attack. **A hint to a host outside
the policy is removed from the page:** the edge deletes every `<link>` whose `rel` holds `modulepreload` or
`prefetch`, or `preload` with `as="script"` (or a worker/worklet/`xslt` `as`), when its URL fails the same rule
as a stamped script — the site's own origin, or a host the policy names. *Why:* under `'strict-dynamic'` the
browser fetches such a hint from any host (it ignores the host list for requests that are not parser-inserted;
Chrome 155 does it for every `prefetch` too), so injected markup could make every visitor's browser report the
visit to a host of the attacker's choosing, which the allowlist policy blocked. Allowed hints are left exactly
as written; stylesheet, font and image hints (`as="style"`, `as="font"`, …) have their own directives and are
untouched. To preload a module from a CDN, add the CDN to `extra-hosts` (it then also runs that CDN's scripts). A
foreign hint the edge cannot remove — inside an SVG `<title>`/`<style>` or another element it reads as text, or
inside an `<iframe srcdoc>` — makes that response fall back to the allowlist policy, without a nonce and with
`Cache-Control: no-store` (nothing is logged above `DEBUG`: `logging.level.mx.quest.runway.head=DEBUG` shows
*"hides a foreign script preload"*); remove the hint or write it in the page's normal markup. The same fallback
applies to a page whose markup the edge cannot read the way a browser will — malformed or deliberately tangled
markup where the reading depends on context the edge does not track (a raw-text element or `<![CDATA[` after an
inline `<svg>`/`<math>`/`<select>`/`<template>` whose two possible readings disagree, a `<noscript>` whose content
reads differently with scripting on and off). Normal pages, inline SVG icons included, are never affected. *Why:* a
crafted bogus comment (`<?x <!-- y>`) could otherwise make the edge read the rest of the page as a comment the browser never
saw, so a foreign hint after it would survive under the nonce; the edge therefore follows the
browser's tokenizer, and where it cannot be certain it serves the allowlist policy rather than guess.
`preconnect`/`dns-prefetch` hints are not governed by CSP at all and are left alone (Known limitations).
Template escaping remains the first defence
against injected markup; the stamp only guarantees that injected markup gains nothing it did not have under
the allowlist policy.

**Verify** (enforce mode shown; report-only uses `Content-Security-Policy-Report-Only`):

```bash
curl -si https://www.example.com/ | grep -i -E '^(etag|content-security-policy):'
# script-src 'nonce-AbC…' 'strict-dynamic' 'self' …   — the same value on every request until the page changes
curl -s https://www.example.com/ | grep -o '<script[^>]*>'   # same-origin/allowlisted src carry nonce="AbC…";
                                                             # inline scripts, other hosts and JSON-LD do not
curl -si -H 'If-None-Match: "<etag from above>"' https://www.example.com/ | grep -i -E '^(HTTP|content-security)'
# 304, with the same nonce
ls -l /opt/runway-data/external/csp-nonce.key                 # -rw------- runway runway
```

**Hosts a site may add: Settings → Embed hosts.** The policy is shared by every site, so `extra-hosts` widens it
for all of them. When only one site needs a third party — a video player, a map, an image host — approve the host
on **Settings → Embed hosts** (`/__app/embed-hosts`, platform admins) instead: give it an id (`youtube`), a name the
site manager will see ("YouTube videos"), its source (`https://www.youtube-nocookie.com`, `https://host:port`, or
`https://*.example.com` for its subdomains; https only, no path, no keywords such as `'unsafe-inline'`), and tick the
directives it may join: `frame-src` (embedded pages and players), `img-src`, `media-src`, `connect-src`,
`style-src`, `font-src`. A site then switches it on under **Site config → Embedded content**, and only that site's
**public** pages get it; its console, sign-in and recovery pages never do, nor does any other site. The list
shows how many sites use each host. Removing a host takes it away from every site on its next page, and a site's
own file (`sites/<id>/csp.yml`) only ever counts hosts still on your list, so a restored or imported backup cannot
widen anything. Changes apply at once, with no restart.

*Why scripts are not offered:* on a public page a `<script src>` in the markup runs only when the edge has stamped
it with the page's nonce, and that follows the platform-wide `script-src` allowlist, so a script host added for one
site would not run. A script host for those tags therefore stays a platform decision (`runway.csp.extra-hosts`, or a
plugin that declares its hosts). *Why approval at all:* the policy is a security boundary between sites that share
the server; letting a site manager type any source would let one compromised account load content from anywhere
into that site's pages.

**What the policy does not stop.** Two limits, stated plainly so nobody relies on more than there is:

- **A site can already run any script on its own public pages.** Under `'strict-dynamic'` a script the site serves
  from its own origin (a `.js` file in its static files, or one its templates name) is stamped, and it may insert
  further scripts from any host (`document.createElement('script')`, the table above). So whoever can put
  JavaScript on a site — its users with *Configure site* (the **Static files** screen), and whoever edits its
  templates — decides what runs on its public pages. The allowlist governs the `<script src>` tags in the markup,
  not what those scripts load. (Where those scripts may send data is still limited by `connect-src` and `img-src`,
  which the hosts the site switched on under Embedded content can widen.)
- **CSP cannot separate a site's public pages from its own console.** The public pages and `/__admin` share one
  origin (the same host name). A script on a public page can call `/__admin` with the session of a site user who
  is signed in in the same browser, read the form token from a console page and submit the form as that user.
  Nothing in the console's own headers prevents it, because the script runs in the public page. Treat a site's
  scripts as able to act as that site's signed-in console users.

That is why the **operator console is kept off site host names** (**§8.6**): it does not answer on
a host name that serves a site other than the default one, so another site's scripts never share an origin with an
operator's session. The default site's own scripts still do, when you sign in on its host name: for the strongest
separation, list a host name of its own in `runway.console.hosts` (one that serves no site) and sign in only there.

### 8.5 Server settings and the policy cards

**Settings → Server settings** (`/__app/config/server`) holds the server-wide settings, in sections:
Content-Security-Policy, Request limits, Site storage (the default storage limit per site, §10), HTTP caching,
Response compression (tuning), Monitoring (the
`/__health` probe, the metrics token, the site reload endpoint), Site logs, Console languages, Sessions,
Reverse proxy, Capacity and Protection logging. Each field has a help line with its values and default;
rarely changed fields sit under **Advanced**. The **Settings** button on **Backups** (automatic backups,
retention), **Bandwidth** (metering, history, the monthly cap), **Activity → Logs** (activity log retention)
and **Plugins** (size limits) opens that screen's own card. Restricted to the application superadmin or a
wildcard admin.

- **Applies after a restart.** These values are read when the server starts; the page lists the ones saved
  since, and the save says so.
- **Only changes are stored.** Saving a value equal to its default removes it from `platform.yml`, so a later
  release that changes a default is not pinned to the old one.
- **A value that would stop the server is refused**: an invalid schedule (cron), duration, size, address range
  or language code, fewer request threads than the minimum, and every out-of-range number.
- **Tokens** (health detail, metrics, reload) are stored encrypted and need `runway.security.secret-key`.
  A blank token field keeps the stored token: **blank-and-save does not remove a token.** To revoke a token,
  type a new one and save; the old one stops working after the restart. To remove it and go back to "no token",
  stop the service, delete its line under `settings:` in `external/platform.yml`, and start it (*Editing
  platform.yml by hand*, §8.1, says why the service must be stopped first); or set the key to `""` in
  `external/application.yml`, which wins over the console's value and shows the field locked. Unticking
  **Site reload endpoint** makes `/__reload` answer 404 even while a reload token is stored.
- **No `${` in a value.** A value containing `${` is refused, on every field: the server would read it as a
  reference to one of its own settings (the secret key, for one) and could send that out in a page header. A
  `${` that is already in `platform.yml` (written by hand, or by an older release) is kept as plain text.
- **Trusted proxy addresses** must be your proxy's own address. A catch-all is refused: `0.0.0.0/0`, `::/0`, or
  ranges that together cover every IPv4 or every IPv6 address (two `/1` halves, four `/2` quarters, the
  IPv4-mapped `::ffff:0.0.0.0/96`). It would let every client fake its address. One set in `application.yml` is not refused, but the server log
  warns about it at startup.
- **Locked fields** are the ones the host sets (§8, *One home per setting*). Each says what removing the host's
  line would do and, when that would change something, offers **Use the file's value**: it copies the value the
  server runs with into the console, so you can delete the line and restart once with nothing changing (§8,
  *Moving a key from `application.yml` to the console*). Same permission as saving this page; it asks first.
- **Lowering a limit that sites choose within** (today the sites' log retention, *Longest a site may keep its
  logs*) keeps every site's own choice: the save says how many sites are above the new limit, they are held to it
  after the restart, and each sees a note on its own Logs screen. Nothing is deleted by the save itself.
- **A plugin's app-level default that sites can override**:
  the plugin's settings page says how many sites set their own value; changing the default leaves them as they
  are.

Each key and its values are listed in [CONFIGURATION-AND-DATA-STABILITY.md](CONFIGURATION-AND-DATA-STABILITY.md)
§1; the console page states the same on each field.

### 8.6 Where the operator console answers (`runway.console.hosts`)

The operator console does not answer on every host name. This covers all of its paths: `/__app`
and everything under it, the sign-in page `/app/login`, `/app/logout`, and password recovery (`/app/forgot-password`,
`/app/reset`).

- **By default** (`runway.console.hosts` not set) it answers on the **default site's** host names and on host names
  that **serve no site**: the server's IP address, `localhost`, any name no site lists. On a host name that serves
  **another** site, those paths answer `404` with that site's own "not found" page. (The response is not
  byte-for-byte an unknown page's: some headers differ, so a careful prober can tell a console path exists. It
  cannot reach it.) With no default site, only host names that serve no site work.
- **With `runway.console.hosts` set**, it answers only on the names in the list. Every other name gets the `404`,
  the default site's names and the server's address included, unless you list them.

```yaml
# external/application.yml
runway:
  console:
    hosts: [ops.example.com, 10.0.0.5]
```

Names are compared without case and without the port. There is no console field for this: a wrong value would lock
every operator out of the console, and the file on the server is how you get back in. Change it, then restart. In a
cluster each node reads its own file, so give every node the same list (each node's own address may be added).

**Behind a proxy.** The host name compared is the one the request names. With `runway.proxy.forwarded-headers` on, that
is `X-Forwarded-Host` when the trusted proxy sends one, so the proxy must **set** that header from the request's own
`Host` (or remove any the client sent), never pass a client's value through: otherwise a page on a tenant site could
send a request that names the console's host. HAProxy passes a client's header through unless told otherwise, so add
to the frontend (or each backend):

```
http-request set-header X-Forwarded-Host %[req.hdr(host)]
```

nginx: `proxy_set_header X-Forwarded-Host $host;`. A page on another site still cannot carry the operator's session
(browsers keep that cookie on the console's host name), but nothing should let it name that host.
Under the default rule the console also follows the **default site** setting: choosing another default site
changes which names work at once on the node where you saved it (platform settings are not relayed between nodes, so
restart the others).

*Why:* a site's managers decide what scripts run on its pages (**§8.4**, *What the policy does not stop*). If you
signed in to the console through that site's host name, those scripts would share the origin with your session and
could act as you: open console pages, read their form tokens and submit forms, with your rights over the whole
platform. Nothing in
the console's own pages can prevent that; a different host name does. Best: give the console a name of its own
(`ops.example.com`, pointed at Runway like any site's name and served by no site) and list it, with the server's
address for emergencies, in `runway.console.hosts`.

**What does not change.** A site's own console (`/__admin`, `/login`, `/forgot-password`, `/reset`) still answers
on that site's host names. Public pages are unchanged. `/actuator/**` (health, Prometheus) still answers on any host
name, so monitoring by address keeps working. The first-run wizard (`/setup`) is not affected: the rule starts once
setup is done.

**The log.** The first time a host name is refused, the server logs one `INFO` line: *"Operator console refused on
host 'shop.example.com' (it serves the site 'shop', not the default site) …"*, followed by what to do. That host is
not logged again until the next restart, however many requests it gets.

**Verify** (on the server, against the app port):

```bash
curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: shop.example.com' http://127.0.0.1:8080/app/login   # 404
curl -s -o /dev/null -w '%{http_code}\n' -H 'Host: ops.example.com'  http://127.0.0.1:8080/app/login   # 200
```

## 9. Managing application users

Application users are the **platform operators** — the people who run the server. They live in the
application realm and are managed from the operator console at **App users** (`/__app/users`). They
are stored in `external/app-users.yml`, one of the platform's trusted-computing-base files: owned by
the service user, mode-restricted, and never under a web-served or tenant-writable path.

**The roles** (application realm — they never apply to a site):

| Role | What it can do |
|---|---|
| **System superadmin** | The pinned platform superadmin — full control. There is exactly one, created by the wizard, and it is immutable (below). |
| **System admin** | A platform administrator (can perform the platform operations — manage sites, plugins, users, settings). |
| **System operator** | A platform operator. Out of the box it can sign in and open the screens that need no permission (Site master users, App users, Plugins, About); everything else is **granted** to it on **Permissions** (below). Mutating platform operations stay with the superadmin or a wildcard admin. |

**The immutable application superadmin — always a way back in.** The single superadmin is matched by
a **stable pinned id** (`runway.security.app-master-id`), not by its renameable username. It **cannot
be deleted, disabled, demoted, un-pinned, renamed, or moved to another realm** — the store rejects
any such change, and the console blocks deleting it. Only its **password and profile** (display name,
recovery email) can change. This guarantees there is always exactly one un-lockable-out platform
owner; you can never accidentally remove the last one. The rule is enforced two ways: on every
mutation, and by a **fail-closed load check** at start-up that refuses to boot the application realm
if the on-disk store disagrees with the pin (a second master, a pin mismatch, a disabled/demoted
master, or a stray superadmin role on a non-master record).

**Create an application user.** On **App users**, use the create form: username, password, optional
display name and email, and a **role** — you may create a **System admin** or a **System operator**,
but **never a second superadmin** (the store forbids it). Optionally pick **Additional roles**: custom roles
(**§9.1**) the user holds on top of the tier. If one of them cannot be given, nothing is created and the page says
which. Confirm the new row appears in the list.
The username must not already belong to another application user or to the superadmin, compared ignoring
case and surrounding spaces; a taken name is refused with *"The username … is already in use"*.

**Login names are unique per sign-in form — everywhere.** The same rule holds in the tenant realm: a site's
users and the **site superadmins assigned to that site** share the site's `/login`, so a site user cannot be
created with a superadmin's name, a superadmin cannot be created with (or assigned to a site where a user
holds) its name, and two site superadmins never share one (Site masters, §10, says *"already used by
another tenant superadmin or by a user of a site this superadmin would sign in to"*). Comparison ignores
case and surrounding spaces. **Why:** delegated user managers (`MANAGE_USERS`) could otherwise create a
site user named like the site's superadmin, sign in as it and lock it out. An install upgraded with such a
clash already on disk keeps working: sign-in **prefers the superadmin** and logs a `WARN` from
`SiteUserDetailsService` naming the site and both accounts — delete the site user to clear it.

**Edit a user.** Use *Edit* on the user's row to change the **display name**, the **recovery email**
(the address password recovery mails, §8.3) and the **access tier** (System admin ↔ System operator).
The username — the login name — is not editable; to rename, create a new user and delete the old one.
A tier change applies to the user **at once**: an open console session gains or loses the tier's rights
on its next request, no sign-out needed. Two limits, both enforced by the server: the superadmin's tier
never changes (only its display name and email do), and **nobody changes their own tier** (a
self-demotion could not be undone by the same person). After creation, custom roles are managed on the
**Permissions** screen (**§9.1**), not here. Each edit is recorded in the activity log (`appuser.updated`).

**Suspend and resume a user.** *Suspend* blocks an account without deleting it — use it for someone on
leave, a contractor between engagements, or an account you suspect is compromised while you investigate.
A suspended user:

- **cannot sign in** (the login page shows the usual generic "invalid" message, so it reveals nothing
  about the account);
- **is signed out** of an open console session on their **next request** — you do not have to wait for
  the session to expire;
- **cannot use password recovery** (no reset link is sent to a suspended account).

Their password, email, tier and custom roles are kept, so *Resume* restores exactly the access they
had. The list marks suspended users with a **suspended** badge. The superadmin can never be suspended,
and nobody can suspend their own account. Both actions are recorded in the activity log
(`appuser.suspended`, `appuser.resumed`). Deleting a user also ends any session they still have open.

**Reset a password.** Use *Reset password* on the user's row (this also works for the superadmin —
resetting its password is allowed). The new hash uses the current Argon2id profile (**§8.1**);
existing users are unaffected.

**A new password ends the account's other sessions, on every path.** Whether an operator resets it
here or on **Site master users**, a site manager resets it on the site's Users screen, the user changes it
on their own **Account** page, or they set it through a recovery link (**§8.3**), every *other* open
session of that account is signed out on its **next request**. *Why:* changing a password is how someone
throws out whoever else knows the old one; a session that outlived it would defeat the point. The session
that changed its **own** password (on **Account**, or an operator resetting their own row here) stays
signed in. Like suspension, the sign-out is per realm: a sign-in to the other realm in the same browser
is untouched. A tenant superadmin signed in to several of its sites is signed out of each of them, since
each is a separate session. *How it works:* every request already re-checks the signed-in account against
the store (`AccountStateFilter`); a session whose password hash no longer matches the stored one is signed
out. Nothing new is stored and no session list is kept.

**Delete a user.** Use *Delete* on the row. Deleting the pinned superadmin is refused ("The
application superadmin cannot be deleted."). Confirm the row is gone from the list. The activity log
records the deleted user's **username**, with the internal id in brackets (`appuser.deleted`,
`alice (id 6f1c…)`), so a later user re-created under the same name stays distinguishable.

> Rich per-permission authorization and custom roles exist under the hood; the app-user screen
> exposes the three predefined role tiers above. The fine-grained **Permissions** screen
> (`/__app/permissions`) manages the role/permission matrix for the application realm (**§9.1**).

**What you can delegate to a System operator.** The superadmin and System admins hold everything. A
System operator holds only what you grant to it (or to a custom role it has) on **Permissions**:

| Permission | Opens |
|---|---|
| **View application dashboard** (`VIEW_APP_DASHBOARD`) | The dashboard's content at `/__app` — the site, user and plugin counts and the plugins' dashboard cards. Without it, signing in lands on a short page that points to the menu instead (never a "forbidden" page, since `/__app` is where sign-in goes). |
| **View tenancies** (`VIEW_TENANCIES`) | **Sites → Manage sites**, read-only: the sites, their hosts, superadmins and status — no create, edit, suspend, resume, delete, demo install or CAPTCHA rescue (those stay with the superadmin and System admins). |
| **Manage site hosts** (`MANAGE_SITE_HOSTS`) | Editing a site's hosts (**§10**); it also opens the Sites list, where that editor lives. |
| **Manage site templates** (`MANAGE_STYLES`) | **Sites → Templates** (**§10.1**). |
| **View bandwidth** (`VIEW_BANDWIDTH`) | **Activity → Bandwidth**. |
| **View audit log** (`VIEW_AUDIT`) | **Activity → Logs** — the activity log (**§14**). |

*Why the dashboard is a permission:* the counts tell how big the platform is (sites, accounts, plugins),
which a help-desk operator granted only *View audit log* has no need to see.

### 9.1 Permissions (roles and grants)

**Access → Permissions** (`/__app/permissions`) decides what each operator below System admin may do. It is open
to the application superadmin and System admins. Every change applies on the affected user's next request and is
recorded in the activity log (the `permissions.*` keys, §14). A refused change writes nothing and says why.

**What the screen shows.** *Users*: each operator with the roles they hold and their direct grants. *Roles*: each
role an operator can be given — System admin (every permission), System operator and your custom roles — with the
permissions it **really** grants once inheritance and the changes below are counted, and what it inherits.

**Custom roles** (*Manage roles*):

- **Create role** — a name of 1 to 64 letters, digits, `_` or `-`, not already used by a custom role or a
  built-in role (such as `SYSTEMADMIN` or `SYSTEMOPERATOR`). Optionally pick roles it inherits from. A
  new role grants nothing until you give it permissions (*Role permission deltas*, below) or it inherits some.
- **Role hierarchy** — *Add edge* makes a role inherit another: it then grants everything the other grants,
  including what that one inherits in turn. Only custom roles take part. A role cannot inherit itself, and an edge
  that would close a loop is refused. *Remove edge* takes one inheritance away.
- **Delete role** — refused while another role inherits it (remove that edge first). Deleting a role also takes it
  away from every user who holds it and drops the permissions granted to it here, at once; a role created later under
  the same name starts with no holders and no grants.

**Assign** — three pairs of actions:

- **User roles** — give a user a role, or take it away. A user's access tier (System admin or System operator,
  set on App users) cannot be revoked here.
- **User direct grants** — give one permission to one user, without a role.
- **Role permission deltas** — give a permission to a role, or take one away; every holder of the role, and
  every role inheriting it, follows. This works on System operator and on custom roles; System admin already holds
  everything.

Which permissions exist is listed in §9 above (*What you can delegate*); plugins add their own (for example
**Configure protection**). The superadmin's own authorization can never be changed here.

**Where it is stored.** Two files in `external/`, both written by this screen:

- `app-roles.yml` — the custom roles: `roles:`, a list of `{name, realm: APPLICATION, permissions, inherits}`.
  The screen sets `name` and `inherits`; it never adds to a role's `permissions` there. A file that is invalid (two
  roles with one name, an inherited role that does not exist, a loop) stops the server from starting, because it is
  part of the platform's security.
- `app-grants.yml` — everything else: `roles` (user → role names) and `permissions` (user → direct grants), both
  keyed by each user's internal id (not the username), and `roleGrants` (role → permissions given to it) and
  `roleRevokes` (role → permissions taken from it). **Giving a role a permission on this screen adds it to
  `roleGrants`** (or, for one the role held already and had taken away, removes it from `roleRevokes`); it never
  writes `app-roles.yml`. A role grants: (the `permissions` listed for it in `app-roles.yml`, plus what plugins
  give it by default, plus its `roleGrants`) minus its `roleRevokes`; a revoke always wins.

*Why a taken-away permission is stored rather than just removed:* a plugin can give a role a permission by default.
Recording the revoke keeps it taken away when a later plugin release changes its defaults, while a role you never
touched follows the plugin's new default.

## 10. Managing sites (tenancies)

Manage tenancies from the operator console at **Sites → Manage sites** (`/__app/sites`). Day-to-day
site operation is the site manager's job — see [SITE-MANAGER-GUIDE.md](SITE-MANAGER-GUIDE.md); this
section is the platform operator's view.

**Every site always has a superadmin.** Creating a site is a single action that provisions the site
**and** its first **site-superadmin** (you supply that account's username and password on the create
form) — a tenant-realm identity that fully manages the site but can never reach the application realm.
The application superadmin, conversely, never operates *inside* a tenant. This invariant holds for the
wizard-seeded first site too, and the Sites list shows each site's superadmin(s) so you can see it at a
glance. (To hand a site a *different* or additional superadmin later, use the **Site master users**
screen — **Sites → Site master users**.)

**Creating a site.** The create form on **Sites → Manage sites** asks for:

- **Site id** — letters, digits, `.`, `_` and `-`, starting with a letter or digit (`acme`, `blog-2`). It becomes
  the site's folder name under `sites/` and never changes.
- **Name** — the display name; the site manager can change it later.
- **Hosts** — the hostnames the site answers on, comma-separated (the rules are under *Editing a site's hosts*,
  below). Empty is allowed: the site is then reachable only as the catch-all default. **The first host is the one
  its links are built on** unless you set a base URL (below).
- **Default language** and **other languages** — language tags such as `en` and `es, pt-BR`. The default is added
  to the list for you here (unlike *Edit*, below, which refuses a list without it).
- **Born from template** — the site template it is seeded from (§10.1).
- **The superadmin** — username, password and an optional recovery email for the site's first superadmin. A
  username already used by another site superadmin is refused before the site is created; if the superadmin cannot
  be created, the site is removed again, so a site never exists without one.

The base URL is not on this form; set it afterwards in the row's **Edit hosts** dialog if the first host is not
the right origin.

**Site master users** (`/__app/masters`) lists every site superadmin with the sites it manages. From it
the application superadmin or a wildcard admin can create one, **edit** its display name and recovery
email, reset its password, assign or unassign a site, **suspend or resume** it, and delete it. Give every
site superadmin a **recovery email**: without one it cannot use *Forgot your password?* (**§8.3**), and an
operator has to reset the password for it. The create form asks for it; *Edit* adds or changes it later
(recorded as `master.edited`). *Suspend* works like an application user's (**§9**): the
superadmin cannot sign in to **any** of its sites, every open session of it ends on its next request, and
its password and site assignments are kept, so *Resume* restores exactly what it had. Use it when a site's
management must be locked out without losing the account — an investigation, an unpaid contract. Unlike
deletion, suspending a site's **only** superadmin is allowed: it is undone with one click, and the site's
other users keep working. Both actions are recorded in the activity log (`master.suspended`,
`master.resumed`, with the superadmin's username).

*Assign* and *Unassign* take one site id, typed into the field; Assign refuses an id no site has. The superadmins
and their sites are kept in `external/site-users.yml` (`sites:` under each). A `sites:` entry of `"*"` assigns that
superadmin to **every** site, including sites created later. The console never writes `"*"`; it exists only if
someone put it in the file by hand, and such a superadmin then signs in to every site's `/login` and is listed as a
superadmin on every row of the Sites list. A hand edit of this file applies after a restart.

**Lifecycle.** A site is **active**, **suspended** (public requests short-circuit; the operator can
resume it), or deleted. Deleting a site removes its entire folder and is irreversible — take a backup
first (Site-Manager guide §12).

**The site's file, `sites/<id>/site.yml`.** Everything on this screen and on the site manager's **Site config** is
kept here. Prefer the console, which checks every value; a hand edit is not checked when it is read, and it applies
only at a site reload or a restart (**§12.2**). The keys, as the file writes them:

| Key | Values | Set on |
|---|---|---|
| `id` | the folder name; never change it | create |
| `name` | the display name | Edit (here) or Site config |
| `hosts` | a YAML list of hostnames, the primary first (rules under *Editing a site's hosts*) | Edit hosts |
| `defaultLocale`, `locales` | language tags with a hyphen, such as `pt-BR`; `pt_BR` in a hand edit reads the same. The default is put first in `locales` if it is not there already | Edit (here) or Site config |
| `baseUrl` | an origin such as `https://www.example.com`, or `null` for the first host | Edit hosts |
| `status` | `ACTIVE` or `SUSPENDED`, in capitals | Suspend / Resume |
| `maintenance`, `maintenanceMessage` | `true`/`false`, and the plain-text message | Settings → Maintenance (the site manager) |
| `editorial` | `advisory`, `enforced` or `reviewed` | Site config (the site manager) |

**Editing a site's name and languages.** Use the row's **Edit** action to change the site's **display
name**, its **default language** and its **available languages** (a comma-separated list of language
tags such as `en, es, pt-BR`; the default must be one of them — here, unlike on the create form, it is never
added for you). This is the
same change a site manager can make on their own **Site config** screen, with the same rules, so the two
can never disagree; you use it when the site manager cannot (a site with no active manager, or a site you
are setting up). Changing the default language re-homes the site's message bundles so the base file keeps
the default language's text, and existing pages keep their URL paths — tell the site manager to review
page paths after a default-language change (SITE-MANAGER-GUIDE §5). The site's **editorial level** (advisory, enforced or reviewed) is
not on this form: it is the site manager's choice on **Site config**, and an operator edit leaves it as it is. The change is live on the next
request and is recorded in the activity log (`site.updated`). Restricted, like the other lifecycle
actions, to the application superadmin or a wildcard admin. The site **id** never changes (it is the
site's folder name and stable identity).

**A site's storage limit.** Every site shares the data disk, so one site that uploads without limit can fill it for
all of them. The **Edit** dialog's **Storage limit** caps what a site's files may take: everything under its
`static/` folder, which is what the site manager uploads on **Static files** and **Documents**. There are two
levels, and `0` means no limit at either:

| Level | Where you set it | Applies |
|---|---|---|
| The platform default | **Settings → Server settings → Site storage**, field "Storage per site (bytes)"; key `runway.static-files.default-max-bytes` (default `0`) | after a restart |
| One site's own limit | **Sites → Edit**, field "Storage limit": a size such as `5GB` or `500MB`, `0` for no limit, empty for the platform default | at once |

Both default to no limit, so an upgrade changes nothing until you set one. The row on Sites shows each site's limit
and where it comes from ("this site" or "platform default"); the site's **Static files** screen shows the site
manager the usage against it, read-only. **The limit only refuses new uploads**: an upload that would go over it is
refused with a message saying so. It never deletes, hides or stops serving a file. If you lower a site's limit below
what it already stores, the site keeps every file, its Static files screen warns that it is over, and the site
manager can still delete (and replace a file with one no larger) until it fits. A site's own limit is stored in
`external/platform.yml` (`siteStorageCeilings:`), never in the site's folder, so it does not travel in a site backup,
and it is removed when the site is deleted. **A restore is never refused by the limit**: it brings the site back
whole, and only the next upload is checked. What the limit does not count (pages, messages, logs, plugin files) is in
[KNOWN-LIMITATIONS.md](KNOWN-LIMITATIONS.md). Each change is recorded in the activity log
(`site.storage-ceiling-changed`).

**Who sees this screen.** The superadmin and System admins, with every action on it. A System operator
needs **View tenancies** (`VIEW_TENANCIES`) to see the list — read-only, with no create, edit, lifecycle,
demo or CAPTCHA action — or **Manage site hosts** (`MANAGE_SITE_HOSTS`), which also shows the list
because host editing happens on it (**§9**).

**Editing a site's hosts.** The Sites list shows each site's routing **hosts** (the hostnames that
resolve to it). Use the row's **Edit hosts** action to change them — enter a comma-separated list;
entries are lowercased, trimmed and de-duplicated, each is validated as a hostname, and a host already
bound to **another** site is rejected (two sites can never claim the same host). The same rule applies
wherever a site gets hosts: creating a site, installing a demo, and creating a site from a backup refuse a
malformed host or one another site answers on, before anything is created. Restoring a backup brings back the
hosts and base URL the site had when the backup was taken when it is made from the application console (a host
given to another site since then is left off, the log says which: `restored site '…' without host(s) […]`, and you
can add it back with **Edit hosts** once it is free). After such a restore, and after creating a site from a backup,
the Backups screen shows the site's hosts and base URL **as they now are**, with the same editor as **Edit hosts**:
check them before anything else, because a backup taken on another installation, or before the site moved, brings
back how it was reached then. A restore made from the site's own console keeps the hosts and
base URL the site has: how a site is reached is yours to set, never the site manager's, and a backup file is
theirs to write. A host is a plain DNS name: no scheme, path,
port (`example.com:8080`), wildcard (`*.example.com`) or underscore, and an international name is written in its
`xn--` form. The port is never part of the match: a site answers on its hosts on whatever port Runway listens on.
The **first** host is the primary one: the site's links are built on it unless a base URL is set. Leaving the list empty
makes the site reachable only as the catch-all **default** (below). The change takes effect on the next
request — no restart. Unlike the create/suspend/delete lifecycle actions (which are master-only), host
editing is gated by the **grantable** `MANAGE_SITE_HOSTS` permission, so you can **delegate** it to an
operator role or user on the **Permissions** screen (**§9.1**) without granting the full tenancy
lifecycle. The application superadmin always has it. The **default site** is the exception: its hosts and base
URL can be edited only by a platform administrator (the application master or a wildcard administrator), because
without `runway.console.hosts` the console's password-reset links go to its address (§8.3).

**The site's base URL.** The same dialog has a **Base URL** field (`baseUrl` in the site's `site.yml`): the origin
every absolute link of the site is built on — canonical and alternate-language links, the sitemap, structured data
and social cards. Leave it **empty** and it is `https://` plus the site's first host, which is right
for most sites. Set it when that is wrong: the site is served over plain `http`, on a non-default port, or its
public name is another of its hosts (`https://www.example.com` while `example.com` is listed first). Only a scheme
(`https` or `http`), a host and an optional port are accepted — no path, query or fragment, because a site is served
at the root of its host — and it is stored lowercased without a trailing `/`. It need not be one of the site's
hosts (a CDN name, for example), but then make sure that name reaches the site, or every canonical link points
nowhere. When set, the Sites row shows it ("Links: …"). It takes effect on the next request; the site manager
cannot change it.

**The default site (catch-all).** One site can be marked the **default** on the **Settings** screen
(**§8.1**); it answers every request whose `Host` is not bound to another site — so a brand-new
deployment reached by an unconfigured hostname still serves a page instead of a 404. The first-run
wizard marks its seeded site as the default. Notes: an explicitly-bound host always wins over the
default; a **suspended** default stops catching unbound hosts (they 404) until it resumes; and deleting
the default clears the setting automatically. The default site is otherwise an ordinary, fully-isolated
tenant — only host *resolution* falls back to it, never authorization.

Each site's data lives under `sites/<id>/` in the external folder (see **§13** for backups, **§5** for
the layout).

### 10.1 Site templates (the catalog new sites are born from)

Every newly created site is **seeded** with a starter presentation so it renders immediately: a
self-contained master layout and two published pages — a home page and an about page. The **Templates** screen (**Sites → Templates**,
`/__app/site-templates`) manages a **catalog of named site templates** a site can be born from, and the
**Manage sites** create form has a **"Born from template"** picker to choose one. Each template is a set
of eight files:

| File | What it is |
|------|------------|
| `_master.html` | Master layout — the shared page shell (header, footer, styling) every page renders into |
| `_master.th.xml` | Master layout logic (decoupled Thymeleaf bindings for the master) |
| `index.html` | Home page markup — the starter landing page |
| `index.th.xml` | Home page logic (decoupled Thymeleaf bindings for the home page) |
| `index.meta.yml` | Home page metadata — its title, description and publish state |
| `about.html` | About page markup — the second starter page |
| `about.th.xml` | About page logic (decoupled Thymeleaf bindings for the about page) |
| `about.meta.yml` | About page metadata — its title, description and publish state |

**The catalog.** A built-in **default** template always exists (it ships in the jar and cannot be deleted).
From the catalog you can:

- **New template** — create one as a copy of an existing template (start from `default` or any other), giving
  it an id, name and description. The id follows the site-id rule — letters, digits, `.`, `_` and `-`, starting
  with a letter or digit — and may not be `default`, `new` or `import` (the last two are words in the screen's own
  addresses). The same applies to an imported template's id.
- **Rename** — a template's page has a **Name and description** card (not for `default`, whose name is the
  localized "Platform default"). The id never changes: it names the folder and is what a site records as born
  from, so renaming affects only what the catalog and the create form show.
- **Edit files** — open a template's files; each shows as **Customized** (the template overrides it) or
  **Inherits default** (it falls back to the platform default). **Save** writes an override; **Reset** (per
  file or **Reset all**) drops back to the default. A template only needs to store the files it changes.
- **Export / Import** — download a template as a `.zip`, or import a `.zip` (exported from another install) as a
  new template. Handy for moving a look between deployments.
- **Delete** — remove a template (not `default`). Sites already created from it are unaffected.

Templates live under `external/seed/site-templates/<id>/`: the files the template overrides, plus `template.yml`,
which holds its `name` and `description` (what *Rename* changes). (A single-seed override from an earlier build at
`external/seed/site/` is migrated once into the `default` template automatically.) The two `.meta.yml` files take the
same keys as any page's metadata — title, description and path per language, publish state, master, the search-engine
switches, a `Cache-Control` override and template parameters; the page editor's **Metadata** panel, described in the
*Site Manager & Designer Guide* §6, writes the same file.

**This is forward-only, by design.** Editing or deleting a template changes only sites created *afterward*.
Existing sites already own their own copy of these files inside `sites/<id>/templates/` and are **never**
restyled by the platform — the multi-tenant self-containment rule (a site's backup is complete and renders
identically anywhere). To change a live site's look, edit *that site's* templates (Site-Manager guide), not
the template it was born from.

**Access.** The screen is gated by the grantable **Manage site templates** (`MANAGE_STYLES`) permission, so
the application superadmin always reaches it and you can grant it to another operator from the **Permissions**
screen without handing over the rest of the platform. Template files are copied verbatim into every new site,
so treat this screen as trusted-operator surface (the same trust level as editing `application.yml`).

**Verify it.** Create a template, edit its `_master.html`, then create a throwaway site with **Born from
template** set to it and confirm `sites/<id>/templates/_master.html` matches your edit; then delete the site
and the template.

### 10.2 Demo sites (none bundled)

Runway carries no demo site. The installer mechanism is kept so you can add your own **example tenant** —
useful for evaluating the platform, demonstrating it, or as a starting point to copy from. A demo is **not** a
special mode: it installs as an ordinary, fully-isolated tenant (through the same create → seed →
provision-superadmin path as any site), so you can browse, edit, back up and delete it exactly like a site you
built yourself.

> **No demo sites ship with Runway.** The platform can install a bundled demo tree if one is present, but the
> distribution carries none, so the "Install demo site" card is empty on a stock install and nothing is offered
> during first-run setup. A demo is content, not platform: bundling one would put its payload in every download,
> and every content fix to it would require a Runway release.
>
> To add your own demo, drop a tree at `seed/demo/<id>/` (a `demo.yml` descriptor plus a verbatim mirror
> of a tenant folder) on the classpath and it appears in the card below. `demo.yml` holds only what creating
> the site needs: `id` (the site id and folder name), `name`, `hosts` (a list; the installer lets you change
> them), `defaultLocale` and `locales` (language tags, default first; English when left out), `default`
> (`true` also makes it the platform's catch-all default site), `master` (`username` and `displayName` of
> the site's superadmin; the password is asked at install) and `requiresPlugins` (informational: the plugins
> whose widgets light up on it).

A demo may be localized (a header language switcher lets a visitor switch) and may place plugin widgets, such
as the `search` plugin's search box — install those plugins and the widgets work; leave them out and the widgets
quietly disappear (the pages still render). Each demo is self-contained (its templates, i18n, static assets and per-site
plugin config all live in its own folder), so its backup is complete and restores anywhere.

**Install one from the console (recommended).** On **Sites → Manage sites** (`/__app/sites`) an
**"Install demo site"** card lists each bundled demo not yet installed. For each you choose:

- **Host** — the hostname the demo is created on (default is the first of the demo's own `hosts`; override it
  to a real hostname you control). You must point **DNS or a hosts-file entry** at that host, or make the demo
  the default (below) so it answers unmatched hosts.
- **Superadmin password** — the demo's site-superadmin is provisioned with the username from the descriptor's
  `master.username` (default `demo`) and the password you enter here; the app hashes it with Argon2, so no
  pre-computed hash is needed. (Two demos must not share a master username, because master identities are
  global.)
- **Make default** — tick to also make this demo the platform catch-all (§8.1).

Installing is **idempotent**: a demo already present is skipped (the card only lists uninstalled ones).

**Install one unattended (config-gated first-run).** For an eval/demo box that should come up already
carrying a demo, set — in the external `application.yml` — the `runway.demo.first-run` block:

```yaml
runway:
  demo:
    first-run:
      id: <demo-id>             # which bundled demo; leave BLANK in production (the default — does nothing)
      master-username: demo     # the demo's site-superadmin login
      master-password: "…"      # its password (this file holds a secret → keep it chmod 600)
      host: runway.example.com  # optional; omit to use the demo's default host
```

With `id` set but `master-password` blank, nothing is installed: the log says so at startup with a `WARN`
(*"…is set but no master-password is configured — not installing"*), because a demo site with no usable superadmin
password could not be signed in to.

On the **next startup**, if that demo isn't already installed, it is installed automatically once. It is
**idempotent** — a restart or upgrade never reinstalls, and it never removes or overwrites an existing site —
so it is safe to leave in place. Whether the demo becomes the platform default is governed by the demo's own
descriptor, not by this block. **Leave `id` blank on a production install** so no demo is ever created — and
note that on a stock distribution there is no demo to name, so this block does nothing until you add one.

Demos are deliberately **not** part of the born-from-template catalog (§10.1). A seed template is a small,
fixed, reviewable file set (§10.1) copied into every new tenant; a demo is an arbitrary whole site. The two
solve different problems and the file-set allowlist that makes seeding safe is exactly what a demo cannot fit.

**Verify it.** Install a demo on a test host, point a hosts entry at that host (or set it default), then browse
it, switch its language if it is localized, and try its plugin widgets, such as the search box (with the `search`
plugin installed). Delete it afterward to confirm it removes cleanly like any other site.

## 11. Installing & managing plugins

**Where the plugins come from.** Each Runway release on the releases page
(<https://g.quest.mx/quest/runway/releases>) also carries the public plugins built for it —
`runway-plugin-<id>-<version>.zip` for `blog`, `carousel-manager`, `contact`, `discoverability`, `runway-demo`, `search`,
`search-lucene`, `lucene-provider` and, from 1.1.0, `database`, `database-postgresql`, `database-mariadb` and
`runway-demo-database` (**§11.1**) — listed in
the same `SHA256SUMS`. Install one by uploading its zip on **Plugins** (below), then restart.

> ### ⚠️ Never park an old plugin version inside `external/plugins/`
>
> The instinct when upgrading a plugin is to keep the previous version beside the new one so a rollback is
> easy — `external/plugins/search.pre-2.0.0/`, say. **Do not.** The loader scans that directory and
> reads each `plugin.yml`; the parked copy still declares the same `id:`, so the scan sees a **duplicate plugin
> id** and **fails fast at startup**.
>
> The result is that the platform refuses to boot *because someone was being careful* — and it happens during a
> rollback-safety step, which is the worst possible moment to discover it. The folder name is irrelevant: the
> id inside the descriptor is what collides.
>
> Park the old version **outside** the scan directory (`/opt/runway-backups/`, anywhere off the tree), or keep
> the release zip and re-extract it if you need to roll back.

Optional features arrive as **plugins**, each a folder under `external/plugins/<id>/`. Manage them
from the operator console at **Plugins** (`/__app/plugins`) — the console's headline screen. The
model is deliberately simple and safe: **install/enable/disable/uninstall change only the
`external/plugins` tree on disk (or the folder `runway.plugins.root` names, when set — the loader and the
console always use the same one); nothing takes effect until you restart.** The DI context, security
chains, template resolvers, and MVC mappings are built once at boot; rebuilding them live is the
fragile path, so Runway does not hot-reload. A malformed, disabled, or blocked plugin is logged and
skipped, and a plugin that breaks the startup itself is quarantined and the platform relaunched without it
(below) — so a bad plugin costs you that plugin, not the platform ("degrade-don't-crash"). The exception is
the essential `protection` plugin, which fails closed instead (end of this section).

**Authority.** Every plugin **mutation** (upload, enable, disable, uninstall, restart) is a platform
operation reserved to the application **superadmin or a wildcard admin**; the read-only listing is
open to any signed-in operator.

**Install / upgrade.** On **Plugins**, use *Upload* to install a plugin package; upgrading is the
same action with a newer package. Uploading larger than 1 MB is fine — the platform accepts up to
256 MB (**§8**). Then click the **restart** action (or `systemctl restart runway`) to apply. Confirm:
the row's badge reads **Loaded** after the restart. Each file inside the package may unpack to at most
`runway.plugins.max-entry-bytes` (128 MiB by default; **Plugins → Settings**, applies after a restart): a guard
against a small archive that expands to fill the disk. A larger file stops the upload with *"Install failed: a zip
entry exceeds the per-entry limit of N bytes"* and nothing is installed. Raise it only for a plugin that really
ships a file that big. In `application.yml`, `0` or a negative value refuses every package that holds a non-empty
file.

A package whose `plugin.yml` says `id: protection` is
**refused** (*"… essential security-floor plugin; it is delivered with each Runway release by the installer"*):
the essential plugin is exempt from the plugin checks and cannot be disabled or removed from the console, and that
exemption goes by id — so the only copy Runway treats as essential is the one its installer lays down. To repair it,
copy `seed/plugins/protection/` from the Runway home over `external/plugins/protection/` or re-run the release's
installer (**§12**).

**A plugin's files per site.** A plugin can keep its own files in each site's folder (`sites/<site>/plugins/<plugin>/`).
Each file it writes or reads whole there may be at most `runway.plugins.site-files.max-write-bytes` (16 MiB by
default; **Plugins → Settings**, applies after a restart; `0` or a negative value means the default). Past it, that
one operation of the plugin fails with an error naming the key; what a visitor or site manager sees depends on the
plugin, and a failure the plugin does not handle shows up on its row as **Failures** (below) and in the log.

**Enable / disable / uninstall.** Use the row actions. *Disable* parks a plugin (it stays on disk but
won't load); *Enable* un-parks it; *Uninstall* removes its folder entirely. Each shows an
"— restart to apply" message and needs a restart to take effect.

**The drift banner and status badges.** Because changes apply on restart, the screen shows a
**restart-required** banner whenever the on-disk state differs from what is currently running. Each
row carries a badge (first match wins):

| Badge | Meaning |
|---|---|
| **Loaded** | Running this boot and the disk still wants it — steady state, no restart pending. |
| **Pending** | Drift — newly uploaded, re-enabled, or disabled/removed-but-still-running. Restart to apply. |
| **Blocked** | Well-formed and enabled, but Runway will not load it. The reason is printed in the row; blocking cascades to anything that requires it. The causes: an unmet `requires:` (target missing, disabled, wrong version, or itself blocked) or a `conflicts:` rival; an unmet **`requiresSpi:` floor** (the plugin needs a newer kernel SPI level than this Runway provides); a plugin **built for the pre-1.0 plugin contract** (no `requiresSpi:`, or one below `0.4.0`); a plugin that **uses Runway's internal classes** (it also carries the *Uses host internals* badge); a plugin that **breaks the rules for Runway's Spring context** (a servlet filter, a security chain, a bean named outside its own prefix); and a **quarantined** plugin, which broke a previous startup (*Retry* clears it — see *Plugin health and quarantine* below). |
| **Disabled** | The operator parked it (`enabled: false`) — it will not load. |
| **Malformed** | The folder is present but its `plugin.yml` is missing/unparsable/invalid, or its id ≠ folder name. The reason is shown. |

To clear a **Blocked** row, satisfy its requirement (install/enable/upgrade the target, remove the
conflicting plugin, or — for an SPI floor — upgrade Runway or install an earlier build of the plugin; for a
pre-1.0 or host-linked plugin, install the plugin's release made for Runway 1.0) and restart. A blocked plugin is
never a failed startup: the platform runs without it. The one exception is the essential **protection** plugin: if
it is installed but does not load, for any reason, tenant pages answer `503` until it is fixed (§12).

**The 1.0 plugin contract.** In Runway 1.0 the plugin contract is frozen (SPI `1.0.0`): a plugin is built
against `runway-spi` only and declares the SPI level it needs. A plugin built for the pre-1.0 contract is blocked
with the reason *"was built for the pre-1.0 plugin
contract"*; install the release made for 1.0. Code-less library plugins (`lucene-provider`) are
not affected.

**Plugin health and quarantine.** Each row also shows how the plugin is behaving in the
running platform, so a plugin that misbehaves is visible on this screen instead of only in the log:

- **Failures: N** — calls into the plugin that threw since the last restart (a widget, a `<head>` contributor,
  a quality check, an endpoint, a content source, a lifecycle hook, a backup import, a scheduled task). Each was **contained**:
  the page got an empty fragment instead (served `no-store`, so the gap is never cached), a quality check
  became a warning that never blocks publishing, an endpoint answered `500`. *Details* shows the last error and
  when it happened. The log has the full stack the first time per plugin and contribution, then at most one
  line a minute with a count; the metric `runway.plugin.contribution.failures{plugin,axis,key}` counts them all
  (§14). A few failures after a transient fault are harmless; a count that keeps rising is a plugin to update
  or report.
- **Refused contribution** — something the plugin offered was refused at startup: a key or route that collides
  with the kernel's or another plugin's (the later plugin loses; both are named in the log), or a route that is
  not an exact tenant-realm path. The plugin's other contributions load.
- **Uses host internals** — the plugin was built against Runway's internal classes instead of the published
  plugin contract (the SPI). Such a plugin is **Blocked** (the reason is in the row) and the badge
  stays on it so the cause is visible at a glance; install its release made for Runway 1.0. The essential
  `protection` plugin (it ships with the kernel) and a code-less plugin (`lucene-provider`) never carry it.

**Quarantine — a plugin that breaks the startup.** Some plugin faults happen while the platform is being
assembled, before any request — two beans with one name, a constructor that throws. Without a guard such a
plugin would stop the whole platform at every boot. Instead, when the failing bean belongs to a non-essential
plugin, Runway:

1. writes `external/plugin-quarantine/<id>.yml` (the plugin's version, a checksum of its jars, the reason and
   the time) and logs an `ERROR` saying the plugin is **QUARANTINED**;
2. exits with code **42**, the relaunch code. The systemd unit relaunches on it (`RestartForceExitStatus=42`),
   as does the launcher's detached mode; under a supervisor that does not, start it again by hand;
3. comes back up **without** the plugin: its row reads **Blocked — "quarantined: it broke the platform's
   startup at … (reason)"**.

The platform is therefore down only for the length of one failed start plus the relaunch. To try the plugin
again, use **Retry** on its row (platform authority; journaled as `plugin.retried`) and restart: if it breaks
the startup again it is quarantined again and the platform comes back without it. Installing a different build
clears the quarantine by itself (the marker no longer matches the jars). A startup failure that cannot be pinned
on one plugin is an ordinary failed start (exit code 1, no marker) — a kernel fault is never hidden behind a
plugin — and the essential plugin is never quarantined: it fails closed instead (below).

**The SPI floor (`requiresSpi:`).** A plugin built against a newer SPI than the kernel it is dropped into does
not fail cleanly on its own: it loads, registers, and then throws `NoSuchMethodError` the first time the
affected path runs — possibly on a public page request long after startup, naming a method you have never
heard of. A descriptor may therefore declare a minimum SPI level (`requiresSpi: ">=1.0.0"`), and a kernel that
understands the key refuses that one plugin at startup with a reason naming both levels and which side to
move, while every other plugin boots normally. Only a minimum is accepted: a value that is not `>=X.Y.Z`
(a `<=` ceiling included) makes the plugin **Malformed** rather than quietly unprotected, because a floor that
fails open reads as protection that is not there. From SPI `1.0.0` a plugin with code **must** declare a floor of
at least `0.4.0`; a missing one marks a plugin built for the pre-1.0 contract (above).

**Application-level plugin settings.** A plugin can declare typed settings the platform renders as a
form; edit them from the plugin's *Settings* link (`/__app/plugins/{id}/settings`). Values are stored
per plugin in `external/plugin-settings/<id>.yml` — **not** in `application.yml` — and apply on
restart. Each public page's ETag folds every running plugin's app-level settings as the restarted application
bound them, so a setting that changes what a plugin renders (an analytics key that adds a script) reaches every
browser and CDN after the restart instead of being revalidated away as `304`. `secret`-typed values are stored encrypted (`ENC(...)`), which requires
`runway.security.secret-key` to be set (**§8**); until it is, secret settings can't be saved and the
form tells you so. (Per-*site* plugin settings are a separate, tenant-realm concern, edited in the
site console.)

**What the settings file may hold.** A save writes every setting the plugin declares and nothing else, so a key
you added to the file by hand that the plugin does not declare is **gone after the next save** of that form. When
the server starts it reads only files named for an installed plugin (`<plugin id>.yml`), and from each only the keys
under `runway.plugin.<id>.`; any other key is ignored with a `WARN` naming it. (These files rank above
`application.yml`, so without that limit a file meant for one plugin could set any server property.) A file of
another name in the folder — the protection plugin keeps its login panel in `protection-app.yml` — is a plugin's own
data, not configuration. The shape of a file is the one shown for protection in §12.4.

A blank secret field keeps the stored secret, **except** when the plugin binds that secret to
other settings (`secretBoundTo`, e.g. an SMTP password to host, port and username) and one of them changed in
the same save: then the stored secret is removed, the form asks for it again, and the log line and activity
entry name the cleared key (never its value). This stops anyone who may edit a form but not read the secret
from redirecting it to a server they control; it applies to per-site forms the same way.

A plugin may lay its forms out in sections with a help line under each field, collapse rarely needed
fields under **Advanced**, and hide fields until the switch they depend on is on (their values are kept).
Two things on the *site* forms depend on your app-level values, and both show what is **running**, not
what was saved since the last restart: a choice set to *inherit* says what it currently resolves to, and
a per-site switch that needs a platform switch you have off says so at the field.

**Essential (security-floor) plugins.** A small, code-defined set of plugin ids are treated as
**essential** — their failure to load puts the platform into **fail-closed degraded mode** rather
than booting open. Today that set is exactly **`protection`** (the abuse-defense plugin — **§12**). A
plugin cannot promote *itself* into this set: the descriptor's `essential: true` is advisory and
ignored (with a warning) for any id not on the kernel's list. If an essential plugin is missing or
fails to arm, the kernel serves **tenant hosts a `503`** while keeping the **application realm
reachable** so you can fix it. Ordinary (non-essential) plugin failures never do this — they are just
skipped.

Authoring plugins is covered in [PLUGIN-DEVELOPMENT.md](PLUGIN-DEVELOPMENT.md).

> **Bundled plugins.** The installer bundles the **essential `protection` plugin**, seeds it into
> `external/plugins/protection/` on a fresh install, and refreshes it on every upgrade — it moves in lockstep
> with the kernel (your settings, which live outside the folder, are never touched; the previous jar and
> descriptor are kept as `*.bak`, and template/i18n files you edited are backed up); see **§12** and **§15**. That is the only plugin shipped with the platform; every
> other plugin (search and search-lucene, for example) is installed
> separately, so `external/plugins/` otherwise starts empty.

### 11.1 The database plugins (PostgreSQL or MariaDB)

Runway keeps its own data — sites, pages, users, settings — on files, and that does not change. The **database
plugins** let *other plugins* keep their data in a database server: one per installation, PostgreSQL or MariaDB,
chosen in settings. Nothing uses them unless a plugin that needs a database is installed (the first one is the
`runway-demo-database` guestbook example).

| Plugin | What it is |
|---|---|
| `database` | The generic plugin: the connection pool, the provider choice and the limits, one schema per site, migrations, backups, the **Database** screen and the connection tests. Version 2.0.0 requires Runway 1.2.0 or later (1.0.0 ran on 1.1.0). |
| `database-postgresql` | The PostgreSQL provider: bundles the pgjdbc driver and holds the server's settings (host, account, password, TLS). PostgreSQL **14 or later**, database encoding `UTF8`. |
| `database-mariadb` | The MariaDB provider: bundles MariaDB Connector/J (LGPL-2.1; its unmodified sources are published next to the zip) and holds the server's settings, collation included. MariaDB **10.11 or later**; MySQL is not supported. |

Install `database` and the provider for your server, then restart. With one provider installed, it is the one used;
with both, the database plugin's `provider` setting picks one. A plugin that needs the database declares it, so without `database` it shows as **Blocked** with the
reason, and Runway runs normally.

**Setting it up, step by step.** The whole path for one server; each step is explained further down.

1. **Prepare the server**: an account and a UTF-8 database for Runway (*Preparing the server*, below). On PostgreSQL
   the account only owns that database; on MariaDB it also needs the grant on the namespace's databases.
2. **Install the plugins.** From the release (the plugin zips are published with each Runway release, §11), download `runway-plugin-database-<version>.zip`, the provider's
   zip (`runway-plugin-database-postgresql-…` or `runway-plugin-database-mariadb-…`) and `SHA256SUMS`, check them
   (`sha256sum -c --ignore-missing SHA256SUMS`), and upload each zip on **Plugins**. Add the plugins that will use
   the database (for a first try, `runway-demo-database`, the guestbook). Do not restart yet.
3. **Enter the settings**: the server, the account, the password and TLS on the provider's page (**Plugins →
   Database — PostgreSQL → Settings**, or **Database — MariaDB**), or in its settings file (*Setting it up without the
   console*, below). The database plugin's own page (**Plugins → Database → Settings**) needs a change only when both
   providers are installed, or for the namespace and the limits. Settings take effect on the next restart.
4. **Test them**: on **Plugins → Database → Settings**, below the form, **Connection tests** → **Test the saved
   PostgreSQL settings** (or MariaDB) connects once with what is saved and changes nothing. The answer is one of those
   in *When it does not connect*, below.
5. **Restart once.** Runway starts without waiting for the database, then connects on its own thread.
6. **Check**: **Settings → Database** says *Connected* and shows the server's version; the server log has one line
   `database: connected to … (postgresql), namespace 'rw'` (or `mariadb`). If it says anything else, see *When it
   does not connect*, below.
7. **Try it** (with the guestbook): place `<div id="guestbook" data-widget="guestbook/main"></div>` on a page of a
   site, sign the guestbook as a visitor, and the site's schema appears on the server (`rw_s_<site>_<n>`); the entries
   are managed under **Guestbook** in that site's console (the site superadmin has it; anyone else needs the
   guestbook's *manage* permission, granted on **Permissions**).

**Setting it up without the console.** The server's settings live in the provider's file,
`external/plugin-settings/database-postgresql.yml` (or `database-mariadb.yml`), nested under
`runway.plugin.<provider plugin id>` like every plugin's (§11). `host`, `database` and `username` are required; the
other keys keep their defaults when absent:

```yaml
# external/plugin-settings/database-postgresql.yml   (owner: the service account; mode 600 — it holds the password)
runway:
  plugin:
    database-postgresql:       # database-mariadb for MariaDB
      host: "127.0.0.1"
      port: 5432               # blank or absent: 5432 / 3306
      database: "runway"
      username: "runway"
      password: "…"
      tls: disable             # same host only; otherwise verify-full (the default) or require
```

The database plugin's own file, `external/plugin-settings/database.yml` (under `runway.plugin.database`), is needed
only to choose the provider when both are installed (`provider: postgresql` or `mariadb`; the default `auto` uses the
one installed), or to change the namespace or a limit.

The password may be written **in clear**: it is used as written and stays in clear in the file. Runway does not
encrypt a hand-written value, and nothing outside the console can produce the encrypted `ENC(...)` form; it is made
only when the password is typed on the settings form, with the platform secret key (§8). So either keep the file
readable by the service account only (`chown runway: …; chmod 600 …`), or type the password once on the form, which
replaces it with `ENC(...)`. Saving the form with the password field left blank keeps whatever is stored, clear or
encrypted, as long as the settings it is bound to do not change (below).

**Upgrading from database 1.x.** Until 2.0.0 the server's settings were in the database plugin's own file. After the
upgrade, a `database.yml` that still holds them gives the status *Not configured: the server settings moved to the
database-postgresql plugin in database 2.0.0: move host, …* and Runway does not connect with them. Move every server
key (`host`, `port`, `database`, `username`, `password`, `tls`, `caCertificate`, and on MariaDB `collation`) into the
provider's file, under `runway.plugin.database-postgresql` (or `database-mariadb`), as above; keep `provider`,
`namespace` and the limits in `database.yml`. An encrypted password (`ENC(...)`) can be moved as it is: it is
encrypted with the platform secret key, not with anything tied to the file. **Move the keys before you save the
Database plugin's settings form**: a save writes only the settings the form has, so it removes the old server keys
from `database.yml`, encrypted password included (if that happens, type the settings on the provider's page). Or retype the settings on the provider's
page and delete the old keys. Install the 2.0.0 versions of all three plugins together; a 1.x provider with
database 2.0.0 gives *No provider: the database-postgresql plugin is older than 2.0.0*. Then test (step 4 above)
and restart.

**What Runway creates.** Every name starts with the **namespace** (setting `namespace`, default `rw`):

- `<ns>_app` — Runway's own registry tables (`rw_…`) and data that belongs to no site;
- `<ns>_s_<site>_<n>` — one per site, created the first time a plugin uses the database for that site. `<n>` is a
  generation number that is never reused: a site that is deleted and created again under the same id gets a new
  schema, never the old data.

On PostgreSQL these are **schemas** inside the database you name in the settings. On MariaDB, where a schema *is* a
database, they are **databases** next to the one you name, which only serves as the place to connect to and stays
empty. If several Runway installations share a server, give each its own namespace.

**Preparing the server.** The account needs to create and drop what it owns, and nothing more:

```sql
-- PostgreSQL (as a superuser)
CREATE ROLE runway LOGIN PASSWORD '…';
CREATE DATABASE runway OWNER runway ENCODING 'UTF8' TEMPLATE template0;
```

`TEMPLATE template0` lets the database be UTF-8 even on a cluster created with another encoding, whose `template1`
would refuse it; on a UTF-8 cluster it changes nothing. If PostgreSQL then answers that the encoding does not match
the locale, the cluster's locale is not UTF-8 either: add `LOCALE 'C.UTF-8'` (PostgreSQL 14 and later).

Owning the database is all the account needs: Runway creates its schemas inside it. The server must accept the
account over TCP with a password: on PostgreSQL that is a `pg_hba.conf` line for the database, the account and the
address Runway connects from, for example `host runway runway 127.0.0.1/32 scram-sha-256` for a server on the same
host (reload PostgreSQL after editing it), and, for a server on another host, `listen_addresses` covering an
interface Runway can reach. Give the host as `127.0.0.1` rather than `localhost` to avoid depending on which of
`127.0.0.1` and `::1` the name resolves to.

```sql
-- MariaDB (as root). The pattern is the namespace, then an escaped underscore: `rw\_%` for the default `rw`.
CREATE USER 'runway'@'10.0.0.%' IDENTIFIED BY '…';
CREATE DATABASE runway CHARACTER SET utf8mb4;
GRANT ALL PRIVILEGES ON `runway`.* TO 'runway'@'10.0.0.%';
GRANT ALL PRIVILEGES ON `rw\_%`.* TO 'runway'@'10.0.0.%';
```

A MariaDB account without the pattern grant connects but cannot create the namespace's databases: the **Database**
screen stays at *Connecting* with an "Access denied" error until the grant exists.

**Settings** (Runway-wide, applied on **restart**). The server's, on the provider's page (**Plugins → Database —
PostgreSQL → Settings**, or **Database — MariaDB**):

| Setting | Meaning |
|---|---|
| `host`, `port` | One server: a host name or an IP address (no lists, no options). A blank port means 5432 / 3306. |
| `database` | The database to connect to (see above). |
| `username`, `password` | The account. The password is stored encrypted. It is **bound** to host, port, database, username, TLS and the CA certificate: change any of them without typing the password again and the stored password is removed, so it can never be sent to a server someone else chose. |
| `tls` | `verify-full` (default: encrypted, the certificate and the host name checked against the Java trust store or `caCertificate`), `require` (encrypted, the server **not** checked), `disable`. Use `disable` only for a database on the same host or a private network you trust. |
| `caCertificate` | Advanced. The PEM certificate of the CA that signed the server's certificate, when the Java trust store does not know it. |
| `collation` | Advanced, MariaDB's page only: for plugins' tables. Blank means `utf8mb4_uca1400_ai_ci`. Runway's own identifier columns are always binary. |

The rest, on the database plugin's page (**Plugins → Database → Settings**):

| Setting | Meaning |
|---|---|
| `provider` | `auto` (default: the one provider plugin installed), `postgresql` or `mariadb`. Needed only when both providers are installed. |
| `namespace` | Advanced. Default `rw`; lower-case letters and digits, up to 12. Changing it later starts from empty schemas: the old ones stay on the server. |
| `poolMax` | Connections plugins may hold at once (default 10). One site may use at most half, so a busy site cannot starve the others. |
| `statementTimeoutSeconds` | A plugin's statement that runs longer is stopped (default 10). Migrations, backups and restores have their own 30-minute limit. |
| `backupBudgetMiB` | The compressed size a site's database data may reach in its backup (default 128). |

**Connection tests**, below the database plugin's settings form (same permission as the form, and the same as the
Database screen: `CONFIGURE_PLATFORM`): *Running now* shows the status; **Test the running connection** borrows from
the live pool; **Test the saved PostgreSQL settings** (one button per installed provider) opens one throwaway
connection with the settings saved on that provider's page — which the next restart would apply — and changes
nothing. The tests take no typed values: a form with a username and a password box is one a browser fills in with
your own saved console login, which a test would then send to whatever server the form named. Answers are fixed
categories (below); the driver's own message goes only to the server log, never with a password.

**Runway never fails to start because of the database.** Nothing connects while Runway starts; the plugin connects
right after, on its own thread, retrying with back-off. Until it succeeds, plugins that need it say so (for example
the guestbook shows "This example needs the database …"), and everything else works.

**The Database screen** (**Settings → Database**, needs `CONFIGURE_PLATFORM`, which the master and wildcard
administrators hold) shows:

- the status: *Connected*, *Connecting* (with the last error), *Not configured* (with the setting at fault), *No
  provider*, or *Server not supported*; the provider, server version, driver, pool use and the number of sites with
  data;
- a link to the settings page and its **Connection tests** (above);
- **Plugin tables needing attention**, with recovery actions (below);
- the plugins using the database, the nodes seen in the last five minutes, the largest site backup against the
  budget (a warning from 75%), and the last failed restore.

Each site's dashboard shows one line: whether the site keeps data in the database, its size, and the last database
restore.

**Plugin tables needing attention.** Each plugin brings its tables up to date by itself the first time it uses a
schema (its *migrations*). If that cannot happen, only *that plugin* in *that schema* stops — other plugins and other
sites carry on — and the screen lists the case:

| Problem | Meaning | Action |
|---|---|---|
| Script failed | A migration script stopped at a statement (the detail names it). On PostgreSQL nothing of it was kept, nor of the plugin's other scripts that ran with it (the detail lists them); on MariaDB the statements before it were. | Fix the cause (often a permission, or data the script did not expect), then **Retry**. On MariaDB it continues from the failed statement; on PostgreSQL it runs the whole script again, and the scripts rolled back with it, so do not run part of it by hand first. **Mark applied** only if you completed the script by hand, on PostgreSQL every script the detail lists. If the detail says a script *was never applied* although a later one was, apply it by hand and **Mark applied** (**Retry** cannot run it), or restore the schema. |
| Script changed | A script already applied is no longer the one the installed plugin ships. Released scripts must not change: report it to the plugin's author. | **Accept shipped checksum** once you know the change is harmless. |
| Newer than installed | The schema was migrated by a newer version of the plugin (a downgrade, or nodes on different versions), or data a restore kept for the plugin (when it was not installed) is from a newer version. | Install that version or later; kept data then loads on the plugin's first use. |
| Kept data cannot be loaded | A restore kept data for a plugin that was not installed then (in `deferred/<plugin>/` in the site's folder), and it cannot be read now, or the plugin's tables are already past its version. The plugin is refused on that site rather than started empty. | Fix the cause the detail names (often the folder's permissions) and the next use loads it. To go on without that data, remove `deferred/<plugin>/` from the site's folder, or restore the backup again. |

Every recovery action is written to the server log with the operator's name.

**When it does not connect.** The **Database** screen's status, and the server log's `database:` lines, say why;
**Test the saved … settings** checks what is saved before you restart. While the status is *Connecting*,
Runway retries on its own, waiting twice as long each time up to a minute (every 5 minutes for *Server not
supported*); a fixed server is picked up without a restart. A changed setting needs a restart.

| Status / test answer | Usual cause | What to do |
|---|---|---|
| *Not configured: …* (log: `database: not configured: …`; test: *The settings cannot be used: …*) | A setting is missing or invalid; the message names the plugin and the setting (for example *database-postgresql settings: no host is set*), or says *no server is set* on the provider's page, or *both provider plugins are installed* (choose one in `provider`), or *the server settings moved* (*Upgrading from database 1.x*, above). | Set it and restart. |
| *No provider: …* | No provider plugin is installed, `provider` names a product whose plugin is not, two plugins offer the same product, or the provider is older than 2.0.0 (*the database-postgresql plugin is older than 2.0.0*). | Install `database-postgresql` or `database-mariadb` (2.0.0 or later), or change `provider`; restart. |
| *Connecting:* … / *The server could not be reached.* | Wrong host or port, the server is down, it listens on another interface, or a firewall is in between. The connection attempt gives up after 5 seconds. | From the Runway host, check the address and port (`pg_isready -h … -p …`, `nc -vz … 3306`); `listen_addresses` / `bind-address` on the server. |
| *The server refused the username or password.* | Wrong password; on PostgreSQL, no `pg_hba.conf` line matches the database, account and Runway's address; on MariaDB, no account matches the address Runway connects from. | Fix the password (retype it; §11 on bound secrets), the `pg_hba.conf` line (then reload), or the account's host part. |
| *The TLS handshake failed* | `verify-full` against a server whose certificate the Java trust store does not know (a private CA, or a self-signed certificate), or whose name does not match `host`; or TLS asked for from a server that has none (on PostgreSQL the status shows *The server does not support SSL*; database 1.0.0 reported this one as a refused password). | Paste the CA into `caCertificate`, use the name on the certificate as `host`, or, for a server on the same host or a trusted private network only, `tls: disable`. |
| *The database does not exist on the server, or the account has no access to it.* | The `database` setting names a database that is not there, or (MariaDB) the account has no rights on it. On PostgreSQL an account without the `CONNECT` right answers *The test failed; see the server log*. | Create it as shown above, or fix the name. |
| *Connecting:* … *Access denied* (MariaDB) | The account connects but lacks the grant on the namespace's databases. | Add the namespace pattern grant shown above. |
| *Server not supported: …* | PostgreSQL older than 14, MariaDB older than 10.11, MySQL, or a PostgreSQL database whose encoding is not UTF-8 (*the database's encoding is … UTF8 is needed*). | Upgrade the server or use another one; recreate the database as UTF-8 as shown above. |

The test answers are fixed categories. The status and *Last error* on the screen, and the log's `database:` line,
carry the driver's own message (never a password): the exception, its SQL state and the text, which is where the
detail is.

**Clusters.** Every node needs the same `external/plugin-settings/database.yml` and provider file
(`database-postgresql.yml` or `database-mariadb.yml`) and the same platform secret key
(`runway.security.secret-key`), because the password is stored encrypted with it. The database is shared, so a site
deleted on one node has its schema dropped once, by that node. To change the server or the provider, stop **every**
node first.

**Backups and restore.**

- A site's **full backup** carries its database data (every plugin's tables in that site's schema), in a portable
  format: a backup taken on PostgreSQL restores on MariaDB and the other way round. If the database is unreachable
  when a backup runs, the backup **fails** and names the site — a backup is complete or it is not a backup.
- A **transfer copy** of a site carries **no** database data, and the copy gets its own, empty schema. Plugins are
  asked to keep in the database only data that a transfer may leave behind (visitors' entries, caches); content
  that must travel stays in files.
- A restore builds the data in a **new** schema next to the live one and switches the site to it only when
  everything is loaded; the old schema is dropped later by a cleanup that runs every 15 minutes and waits until it
  has been retired for at least 10. If anything fails, the new schema is
  dropped, **the previous data stays live**, and the failure shows on the **Database** screen and the site's dashboard
  ("Last database restore"). The site's files are restored either way.
- A restore is refused when an installed plugin is **older** than the backup's data (upgrade it first). Data of a
  plugin that is **not installed** is kept in the site's folder and loaded when that plugin is installed, at the
  backup's version or later; an older one is refused with "newer than the installed plugin" and the data waits.
- The backup budget (`backupBudgetMiB`) caps a site's database data in one backup; above it, the backup fails with a
  message, and you back that data up with the database's own tools.
- `<ns>_app` (Runway-wide data) is in **no** site backup. Back it up with the database's own tools (`pg_dump -n
  rw_app`, `mariadb-dump rw_app`). The **Database** screen names the plugins that keep data there.

**Moving to another server or provider.** Take a full backup of each site, change the settings, restart, then
restore each site. Runway-wide data (`<ns>_app`) does not move this way: copy it with the database's tools if a
plugin keeps data there.

**Removing.** Uninstalling the database plugins leaves the schemas on the server. Drop them there (every name starts
with the namespace) once you no longer need the data.

## 12. Protection & abuse defense

**Posture.** The kernel ships the protection *floor* (the request-pipeline seams: the outermost reject point
and the login-attempt hook), and the installer now **bundles the essential protection plugin** and seeds it
into `external/plugins/protection/` on a fresh install — so the *policy* (per-IP rate limiting + login
brute-force lockout) is present out of the box. It becomes active once the platform is in normal (post-wizard)
mode with a site configured; a fresh, pre-wizard install runs the floor open until then. The trusted-proxy
gate (§7) and the security-header baseline are active from first boot; volumetric/network DDoS always stays
upstream at the proxy/edge regardless.

The protection plugin is an **allowlisted-essential** plugin: once its folder is present, it is **fail-closed**
— if it is present but fails to load, the kernel serves tenant hosts a `503` (the app realm stays reachable)
rather than booting open. **Every upgrade refreshes it** from the new release (§15): it is lockstep with the
kernel — the one plugin allowed to use kernel internals; its descriptor's SPI floor is the major line's (`>=1.0.0`,
met by every 1.x kernel, raised only when it uses a newer SPI member) — so an older copy beside a newer kernel can
fail on a missing internal class, or be refused (Blocked) if its floor is ever above the kernel's — and a refused
or failing essential plugin is the fail-closed `503` on every site — or load and run code written for the previous
kernel. Your settings (`external/plugin-settings/protection.yml` and each site's `plugins/protection.yml`) live
outside the folder and are never touched. There is no supported opt-out: setting `enabled: false` in its
`plugin.yml` counts as a broken floor (`503`) and the next upgrade puts the shipped descriptor back, and removing the
folder runs the sites without abuse defense until the next installer run seeds it again.

**Where each part is set.**

| What | Where | Applies |
|---|---|---|
| Rate limit (burst capacity, refill per second), auto-block (on/off, threshold, window, duration), exemptions (allowlist), remember-this-device | **Plugins → Essential Protection → Settings** (`/__app/plugins/protection/settings`), each field with its help line; stored in `external/plugin-settings/protection.yml` | after a restart |
| Operator login: honeypot, lockout (attempts, window, lockout time), CAPTCHA (provider, keys, score) | the **Login protection** panel below those settings; stored in `external/plugin-settings/protection-app.yml` | at once |
| Manual blocks (an IP or CIDR, for a time or indefinitely) and the live auto-blocks | **Activity → DoS activity** (`/__app/dos`) | at once |
| A site's own login: honeypot and CAPTCHA | that site's console, **Settings → Essential Protection** (`/__admin/protection`); the operator can force a site's CAPTCHA off from **Sites** | at once |

The lockout thresholds on the Login protection panel count failed logins **per IP across every login**, the
operator's and every site's. The rate limit and auto-block run before the site is known, so they are one
policy for the whole server. A refill of 0 is refused (it would never let an address recover). The
`runway.plugin.protection.login.*` properties only seed a fresh install; once the panel has been saved, the
panel's values apply and the log says so at startup if those properties are still set. Who can open these:
the master, a wildcard admin, or an operator granted **Configure protection** (`protection|settings`).

**Rate limit and auto-block, in detail.** Each field's range and default is in
[SETTINGS-REFERENCE.md](SETTINGS-REFERENCE.md) (essential protection plugin); this is how they work together. Every
client address gets a bucket that holds `rate-limit.capacity` requests (default 120) and refills at
`rate-limit.refill-per-second` (default 2; decimals are allowed). Each request takes one. A request that finds the
bucket empty is answered `429` with `Retry-After: 1`. So by default an address can send 120 requests at once and then
keep up 2 a second for as long as it likes. While `rate-limit.auto-block.enabled` is on (the default), every such
`429` is also counted: an address that collects `auto-block.breach-threshold` of them (default 60) within
`auto-block.window-seconds` (default 60) is blocked for `auto-block.block-seconds` (default 900), and gets `429` with
the remaining time in `Retry-After` until the block ends. Turned off, an address over the limit only ever gets `429`s
and is never blocked automatically. Auto-blocks live in memory: they end on their own, and a restart clears them.

These keys (all under `runway.plugin.protection.`) are read at startup, so a change applies after a restart; the form
marks a saved value as pending until then. The form refuses a value outside its range. A value written into
`protection.yml` by hand is not checked; the plugin corrects only these:

| Key, set by hand | 0 or a negative value |
|---|---|
| `rate-limit.capacity` | works as 1. |
| `rate-limit.refill-per-second` | works as 0: an address that has spent its burst never gets requests back. This is why the form refuses it. |
| `rate-limit.auto-block.breach-threshold` | works as 1 (the first `429` blocks). |
| `rate-limit.auto-block.window-seconds`, `rate-limit.auto-block.block-seconds` | works as 1 second. So every auto-block has an end. |
| `remember-device.days` | works as 1; a value above 365 works as 365. |

Other values above the form's range are used as written.

**There is no `blocklist` key.** `runway.plugin.protection.blocklist` is read by nothing: an address listed there is
not blocked. The settings page lists such a key under "The file has keys this plugin no longer declares", and the next
save of the form removes it. Block an address on **DoS activity** instead.

**Blocking an address by hand (Activity → DoS activity).** The **Block an address** form takes an IP or a CIDR
(required), a reason (optional; shown in the list, "manual" when blank) and a duration in seconds (default 900; `0`
blocks it until you unblock it). It refuses your own address (as the server sees it) and any address on loopback or on the
allowlist. It does not refuse a CIDR that contains your address, so check a range before you block it: blocking one
that holds your own address locks you out of the console too. A loopback or allowlisted address is never blocked,
even inside a blocked range. A manual block applies at once and answers `403` with no
`Retry-After`: there is no honest time to offer. **Unblock** on its row lifts it. Manual blocks are kept in
`external/blocklist.yml` and survive a restart:

```yaml
entries:
  - ip: "203.0.113.7"          # an IP or a CIDR
    reason: "scraper"
    untilEpochSeconds: 0       # 0 = until unblocked; otherwise the Unix time (seconds) the block ends
```

The file is read only at startup. Every manual block or unblock on the screen, and a manual block running out,
rewrites it from what is in memory. So a hand edit needs a restart, and one made while the service runs is lost at
the next such change. Entries
whose end time has passed are dropped when the file is read. The screen also lists the busiest rate-limited addresses
and the blocks that ended since the last restart (the newest 100, in memory only).

**The Login protection panel, field by field.** It governs the operator sign-in. The panel's first save creates
`external/plugin-settings/protection-app.yml`; the file keys are below.

| Field | File key | Values | What it does |
|---|---|---|---|
| Enable the hidden honeypot field | `honeypotEnabled` | on (default) / off | Adds a hidden field a person never sees or fills. A sign-in that fills it is refused with the ordinary "Invalid username or password", so a bot cannot tell why, and it counts as a failed attempt. It also guards the **Forgot your password?** form (without counting toward the lockout there). |
| Max attempts | `maxAttempts` | whole number; default 5 | Failed sign-ins from one address, within the window, that lock it. A wrong password, the honeypot and a failed CAPTCHA all count. |
| Window (seconds) | `windowSeconds` | default 300 | How far back failures are counted. |
| Lockout (seconds) | `lockoutSeconds` | default 900 | How long the address is then refused, even with the right password; the sign-in page comes back saying there were too many failed attempts from that network and to wait a few minutes. A successful sign-in clears the address's count. |
| Require a CAPTCHA challenge | `captcha.enabled` | on / off (default) | Asks for a Google reCAPTCHA on the operator sign-in and on its **Forgot your password?** form. It is asked only when this is on **and** both keys are set; with a key blank it does nothing. |
| Provider | `captcha.provider` | `recaptcha-v2` (default; the "I'm not a robot" box) / `recaptcha-v3` (invisible, scored) | Must match the type you registered the keys for. Any other value in the file (blank or a typo) is treated as `recaptcha-v2`. |
| Site key (public) | `captcha.siteKey` | from the reCAPTCHA admin console | Put into the sign-in page. Register every host name you open the console on with the key, or the challenge cannot be solved there (§12.3). |
| Secret key | `captcha.secretKey` | from the reCAPTCHA admin console; stored as `ENC(...)` | Used only on the server, to check the answer with Google. A blank field keeps the stored secret, so the panel cannot empty it: turn the CAPTCHA off instead. Saving a secret needs `runway.security.secret-key` (§8); without it the save is refused with a message and nothing changes. If the stored secret cannot be decrypted any more (that key was changed), the CAPTCHA counts as not configured and is not asked; the log warns. |
| Score threshold (reCAPTCHA v3) | `captcha.scoreThreshold` | 0.0 to 1.0; default 0.5 | v3 only: a response scoring below it is refused. Higher is stricter. The field takes 0 to 1, and left empty it saves 0.5. The server does not check the range: a value above 1 written by hand refuses every v3 sign-in, and one below 0 refuses none for its score. |

If Max attempts, Window or Lockout is left empty on the panel, or is 0 or below in the file (set by hand), its
default applies (5, 300 or 900). If Google cannot be reached, or
answers with an error status, the challenge is let through and a warning is logged: a provider outage must not lock
everyone out, and the honeypot and lockout still apply. The panel applies at once. Editing `protection-app.yml` by
hand needs a restart (it is read at startup), and the next save on the panel replaces it; the break-glass in §12.3
relies on exactly that.

**The `runway.plugin.protection.login.*` properties seed a fresh install only.** Until the panel has been saved once,
there is no `protection-app.yml`, and the operator sign-in uses these properties, read at startup:
`login.honeypot-enabled` (`true`), `login.max-attempts` (`5`), `login.window-seconds` (`300`),
`login.lockout-seconds` (`900`), `login.captcha.enabled` (`false`), `login.captcha.provider` (`recaptcha-v2`),
`login.captcha.site-key` and `login.captcha.secret-key` (empty; the secret plain or `ENC(...)`), and
`login.captcha.score-threshold` (`0.5`). The defaults are the panel's, and the panel's first view shows what they
resolved to. Use them only to preconfigure a new install, in `external/application.yml`. Two traps: once the panel has
been saved, they are ignored (the log warns at startup when they differ from their defaults); and they are not fields
of the settings form, so if you put them in `protection.yml`, the next save of that form removes them. If
`protection-app.yml` exists but cannot be read, they apply again, with a warning in the log.

**Remember this device.** While `remember-device.enabled` is on (the default) and the CAPTCHA is asked, the sign-in
page offers "Remember this device for N days". Ticked on a sign-in that succeeds, it sets a cookie that lets that
device skip the sign-in CAPTCHA, on that console or that site only, for `remember-device.days` (default 30). It never
skips the password, the lockout, or the CAPTCHA on **Forgot your password?**. It applies to the operator sign-in and
to every site's. The cookie is sealed with `runway.security.secret-key`; without that key the box is not offered, and
changing the key ends every remembered device. Turned off (after a restart), the CAPTCHA is asked on every sign-in
and existing cookies are ignored.

**Per-site files.** A site's own settings are in `sites/<id>/plugins/protection.yml`, with the same `honeypotEnabled`
and `captcha.*` keys as above, and travel in the site's backup. The file is read at every sign-in, so a hand edit
applies at the next one. Keys the plugin does not know are ignored and dropped at the site's next save.

**If tenant pages answer `503` and the log says `essential plugin 'protection' is installed but failed to load`,**
the line (and the plugin's row on **Plugins**, which stays reachable in the application console) says why and what
to do. Almost always the plugin folder is not the copy that came with the running release — for example a hand copy.
The fix is the bundled copy, and the installer keeps it in the release directory, so it is already on the host:

```bash
sudo cp -R /opt/runway/seed/plugins/protection/. /opt/runway-data/external/plugins/protection/
sudo chown -R runway:runway /opt/runway-data/external/plugins/protection
sudo systemctl restart runway
```

`/opt/runway` is the release the service runs, so its `seed/` is always the matching copy — after a rollback too,
when it is the older release's plugin (the same files as the `.bak` pair, §15). Re-running the running release's
installer does the same and keeps the previous files as `*.bak` — `sudo ./runway-<version>.bin -y`. One case needs
the `.bin` instead, because `/opt/runway/seed/` is not the running release's: a kernel jar replaced by hand (`seed/` is still the
earlier release's). Extract the running release (`./runway-<version>.bin --extract DIR`) and copy
`DIR/runway-<version>/seed/plugins/protection/` over `/opt/runway-data/external/plugins/protection/` the same way. A hand copy does not update the
installer's record of what it laid down (`external/plugin-manifests/protection.sha256`), so the next upgrade may
back up a template or i18n file you never edited, with a warning; the backup is safe to delete. **Confirm** the
copy with the two `cmp` lines in §15 *Verify the upgrade*, not with the version on **Plugins**: a hand-copied or
partially restored folder shows the same version as the release's own copy.

**Request bodies before sign-in (kernel, always on).** A visitor who is not signed in can make the server read at
most **`runway.endpoints.max-form-bytes`** (256 KiB by default) of request body, on every path: the sign-in forms
of both realms, `/setup`, the password-recovery forms, the CSP report endpoint, any console path, and every plugin
route. A larger body is answered **`413`**, and a declared length over the cap is refused without reading it.
Signed-in users keep the larger limits their work needs: up to 16 MiB for a console form (the Messages editor can
post a whole site's message grid) and `runway.endpoints.max-body-bytes` (10 MiB) for a plugin route. "Signed in"
means signed in now: the account is re-checked first, so the open session of a user you suspend or delete (or whose
password was changed) is held to the cap from its next request on. *Why:* without the cap
any visitor could make the server parse a 16 MiB form before the `403` (the CSRF check reads the form
first), and a plugin route read 10 MiB before its handler decided access. The kernel's own anonymous forms are under
a kilobyte, so the cap never touches them; raise it only for a plugin whose public forms carry more. The log line
`… refused before sign-in: the body … over the N-byte cap` names each refusal. **One exception:** a
`multipart/form-data` upload to a kernel path is parsed before security — streamed to temporary files, not held in
memory — under `spring.servlet.multipart.max-request-size` (256 MB, sized for a backup restore upload); it is bounded,
but not by this cap.

**Bandwidth quota (kernel, active now).** Independent of that plugin, you can cap a site's **outbound**
traffic over a rolling 30 days. There are two levels, and `0` means no cap at either:

| Level | Where you set it | Applies |
|---|---|---|
| The platform default | **Bandwidth → Settings**, field "Monthly cap per site (bytes)"; key `runway.bandwidth.quota.default-monthly-bytes` | after a restart |
| One site's own cap | **Sites → Edit** on that site's row, field "Monthly bandwidth cap": a size such as `50GB` or `500MB`, `0` for no cap, empty for the platform default | at once |

The row on Sites shows each site's cap and where it comes from ("this site", "platform default" or "set on the
server"), and the site's own **Bandwidth** screen shows it to the site manager, read-only, with a warning while the
site is over it. A site's cap is stored in `external/platform.yml` (`siteQuotas:`), never in the site's folder, so it
does not travel in a site backup, and it is removed when the site is deleted. Units are base-1024 (`1GB` = 1024³
bytes), as on the Bandwidth screen.

You can also set either level in the host's `application.yml`; a value there wins, and the console shows that field
locked with where it is set:

```yaml
runway:
  bandwidth:
    quota:
      default-monthly-bytes: 0          # platform default; 0 = unlimited
      per-site:                         # per-site caps (bytes; 0 = unlimited); use [brackets] for ids with dots
        acme: 10737418240               # 10 GiB / 30 days for site "acme"
```

When a site's last-30-day served bytes exceed its cap, its **public** traffic returns `503` with a `Retry-After`,
while `/__admin`, `/login` and the application realm stay reachable, so neither the site manager nor you are locked
out. It is **operator-set only** (a site cannot raise its own cap) and evaluated in O(1) per request: a cached
decision recomputed on the metering cadence (once a minute by default) and right away when you change a site's cap on
Sites, so raising a cap reopens a blocked site immediately. This throttles application-layer egress abuse; it is
**not** a volumetric/network defense — that stays upstream at the proxy/edge.

### 12.1 Per-site health probe (`runway.health.*`)

Each tenant host serves `/__health`, a cheap per-site liveness probe (see `OPERATIONS.md` §2). Settings:

| Key | Default | What it does |
|---|---|---|
| `runway.health.enabled` | `true` | Serve the probe at all. |
| `runway.health.token` | *(blank)* | Bearer token unlocking the **detail** body (cause, site id, checks). Unset = detail disabled. **A secret** — set it on Settings → Server settings (stored encrypted) or in the external `application.yml` (already `600`). A blank console field keeps a stored token; §8.5 says how to remove one. A wrong/absent token silently returns the public body, never a `401`. |
| `runway.health.content-check` | `true` | Also verify the site can render (home page routable + `_master.html` present), so the probe cannot report green for a site whose every request 500s. |
| `runway.health.content-check-ttl-seconds` | `10` | How long a content result is reused before re-checking. Evicted immediately on a site change. `0` or a negative value (possible only in `application.yml`) means `10`. |

The **path is fixed and not configurable** — a configurable path could not be reserved against tenant content, and
a kernel path a tenant can occupy is a defect, not a convention.

### 12.2 Site reload without a restart (`runway.reload.*`)

Per-site state is read through snapshot caches invalidated when the *platform* writes a site. Editing
`sites/<id>/site.yml`, a per-site plugin config, or dropping in icon files **out of band** therefore changes
nothing until the caches are refreshed — silently. `POST /__reload/site/<id>` refreshes them without a restart.

What a reload re-reads: the site record and its host routing, per-site plugin settings, SEO, icons and the web
manifest, template parameters (`params.yml`), widget-instance config (`widget-instances.yml`), messages, users and
permissions, documents — and the site's **parsed templates** (its pages, `_master` and fragments). Template files
(`.html`, `.th.xml`, `.meta.yml` — pages, masters and fragments alike) and message bundles
(`i18n/messages*.properties`) edited on disk need no reload — they show on the next request by themselves
(Operations §5.2); the reload evicts them anyway, so "reload this site" after editing its files never leaves a
doubt.

**The rule of thumb: content is live, configuration applies at the reload.** Templates (pages, masters, fragments)
and messages edited on disk are served on the next request. Configuration — `site.yml`, `seo.yml`, `manifest.yml`,
icons, `params.yml`, `widget-instances.yml` and `plugins/<plugin>.yml` — applies when you save it in the console or
reload the site, and **not before**: until then the site keeps serving the configuration it has loaded, *and its
pages keep their ETags*. Each public page's ETag folds every one of these inputs — its own files, every template of
the site that is not a page (a fragment edit moves every page's ETag), the messages, the loaded configuration above,
and what its links to other pages and content are built from — so it moves at the moment a change takes effect,
together with the page: a browser or CDN that revalidates gets the new page then, and never a new ETag on the old
page (which it would otherwise keep, revalidating it to `304`). Content a **plugin** serves follows that plugin's own
rules. *Why not make configuration live
too:* a `site.yml` edit changes host routing, locales and caches that only a reload rebuilds consistently; applying
half of it on the next request would be a site in two states at once. Only that site's templates are evicted; other
sites keep their warm cache. `POST /__reload/sites` does the same for every site. In a cluster the reload is relayed to every node.

*Two things the ETag does not do.* A page with a content widget requested **with a query string** (the search
plugin's `?q=` results) carries no ETag at all and is sent `Cache-Control: private,
no-cache`: the widget may answer from data the kernel cannot see (a search index), so it is rendered fresh every
time. And public pages send no `Last-Modified`: a date could not move with configuration, so `If-Modified-Since`
alone never answers `304`.

*"Not before" holds while the site's configuration stays cached.* The per-site configuration caches are bounded —
512 sites for SEO, manifest, icons and template parameters, 256 for widget-instance config, and 512 (site, plugin)
pairs for per-site plugin settings (reached at 74 sites when seven running plugins declare `siteSettings:`).
Past that, an entry evicted for room is re-read from disk on the next request, so on a node with more sites than that
a hand edit can apply before the reload, at a moment you do not choose. It is still consistent — the ETag folds the
value as loaded, so page and ETag move together — but if you rely on "edit now, reload later", edit in the console or
keep such nodes below those sizes.

| Key | Default | What it does |
|---|---|---|
| `runway.reload.enabled` | `true` | Master switch. Still inert until a token is set. |
| `runway.reload.token` | *(blank)* | Bearer token. **Blank ⇒ the endpoint answers `404`** — absent, not merely locked. **A secret**: set it in the external `application.yml` (already `600`). |

Three gates, in order: **inert unless configured** (`404`), **loopback only** (`404` — no firewall rule, no ACL,
no exposure decision), then a **constant-time token compare** (`403` on a wrong token — unlike the read-only
health probe, this endpoint mutates, so it fails loudly rather than appearing to succeed).

Chosen as a token endpoint rather than a CLI verb or a signal deliberately: it needs nothing from the host's
privilege model, so it works for an operator whose steady state is scoped file access with no console
credentials. Arming it costs one restart; after that, reloads are restart-free.

> **⚠ If a reverse proxy fronts this instance, set `runway.proxy.trusted-cidrs`.** The loopback gate reads the
> client address *as resolved by the trusted-proxy filter*. When `trusted-cidrs` is empty that filter leaves the
> raw TCP peer address in place — which is correct for a directly-exposed instance, but a proxy configured to
> originate upstream connections from loopback (HAProxy's `source 127.0.0.1`) makes **every** proxied request
> arrive from `127.0.0.1`. The gate would then admit internet traffic, leaving only the bearer token in front of
> the endpoint. Set `trusted-cidrs` to the proxy's address so the real client is resolved — or block `/__reload`
> at the proxy, or leave `runway.reload.token` blank to keep the endpoint absent. The app logs a warning at
> start-up whenever the endpoint is armed and `trusted-cidrs` is empty.

### 12.3 Recovering from a CAPTCHA lock-out

CAPTCHA is the one login defense a *misconfiguration* can lock you out with: an invalid site key, a key whose
registered domains don't include the host you browse, or a reCAPTCHA **v3** score threshold set too high renders a
challenge that can never be solved, so the login it guards can no longer succeed. There are two recovery paths, by
which realm is affected — and neither loses the configured keys.

> **Prefer reCAPTCHA v2 (the checkbox) over reCAPTCHA v3.** v3 has no checkbox — it silently assigns each request
> a 0–1 *score* and Runway rejects anything below the configured threshold (default 0.5). A key whose registered
> domains don't include the host you actually browse (e.g. a reverse-proxy hostname), or a fresh key with no
> traffic reputation, scores low and gets rejected even for a real person — a self-inflicted lock-out that looks
> like a bug. reCAPTCHA **v2** is binary pass/fail and far more predictable, especially on low-traffic or
> development hosts. (**Google reCAPTCHA is the only CAPTCHA provider**; Runway auto-allowlists reCAPTCHA's domains in its CSP, so a blocked-widget CSP error is
> not a factor. The score gate remains a v3 property.)

- **A site locked itself out (a site manager's broken CAPTCHA).** You (the operator) rescue it from the
  application **Sites** screen (`/__app/sites`): any site with login CAPTCHA switched on shows a **Disable
  CAPTCHA** action. Clicking it turns *that site's* login CAPTCHA off so its manager can sign in again and fix
  the keys. It is **non-destructive** — the site's provider and keys are preserved (the secret stays encrypted),
  so the manager can correct and re-enable them — **master/wildcard-gated**, recorded in the activity log, and
  applies immediately (no restart). It does not touch the honeypot, the app-scope lockout, or the IP blocklist
  (those are already operator-managed at `/__app/dos`).

- **You locked yourself out of the application console (a broken *app-login* CAPTCHA).** There is no higher
  console to rescue the application realm from, so recovery is a **break-glass on the server** (you have
  filesystem access to the deployment): edit the app-scope protection file
  `external/plugin-settings/protection-app.yml` and set the CAPTCHA off —

  ```yaml
  captcha:
    enabled: false
  ```

  then restart the service (`systemctl restart runway`). The app login stops enforcing CAPTCHA on the next boot;
  sign in, correct the keys on the app protection panel, and re-enable it. Your provider and keys in that file are
  untouched. (This is why it is worth verifying a new CAPTCHA site/secret pair against its provider **before**
  turning it on for the application login.)

**Validated under load.** In a defense test, a single-IP flood offering **~11,700 req/s (2.45 M requests over
3.5 min)** was **~100 % shed** — only ~120 requests reached the app, which stayed **near-idle (~8 % CPU)**, kept
serving legitimate traffic, and emitted **no `5xx`** (excess was rejected/`429`-ed, not errored); both the edge and
the app recovered immediately. The layered floor did it: an upstream proxy's per-IP connection/request rate-limit
in front of the plugin's per-IP token-bucket (default **capacity 120, refill 2/s** — strict and effective at these
rates). **Note for the proxy layer:** if you rate-limit per source IP at the proxy (recommended), make the
stick-table **dual-stack** (e.g. HAProxy `type ipv6`, which also stores IPv4 as `::ffff:`-mapped keys) — an
IPv4-only table silently lets an IPv6 flood past the proxy tier onto the app's token-bucket alone.

### 12.4 Exempting your own high-volume clients

| Key | Default | What it does |
|---|---|---|
| `runway.plugin.protection.allowlist` | *(empty)* | CIDRs (or bare IPs) the abuse floor never throttles and never auto-blocks. **Loopback is not exempt from the rate limit unless you list it** (it is only never *blocked*): behind a same-host reverse proxy whose forwarded address is not trusted, every request arrives from `127.0.0.1`, and an automatic exemption would switch the limit off for everyone. List `127.0.0.1/32` only for a same-host probe that must never see a `429`, knowing that it then exempts anything arriving from loopback. |

Set it on **Plugins → Essential Protection → Settings**, section **Exemptions** (one entry per line, or separated by
commas), or in the file below; either way it applies **after a restart**. An entry that is not a valid IP or CIDR is
skipped without a warning, so check each one. An address on this list also cannot be blocked by hand: the
**DoS activity** screen refuses it (§12).

**Set this before you point any audit, prober, load test or first-party crawler at your own site.** The
floor cannot tell a legitimate high-volume client from an abusive one, and the auto-escalation fires with no
human in the loop: 60 breaches in 60 s earns a 900-second block. An SEO audit run against your own site can
lock the auditing IP out for fifteen minutes and report failures and warnings that are
**every one a `429` rather than a real defect** — even from a clean deployment, where it reads like a
catastrophic regression.

```yaml
# external/plugin-settings/protection.yml
runway:
  plugin:
    protection:
      allowlist:
        - "203.0.113.0/24"     # the office
        - "198.51.100.7"       # the audit runner
```

**A health check from a proxy on the same host.** Behind a same-host reverse proxy, the proxy's own health check
(`GET /actuator/health` or a site's `/__health`) usually reaches Runway from loopback with no `X-Forwarded-For`, so it
shares one rate-limit bucket with everything else run on that host — a local crawl or audit can then earn the check
`429`s and push the proxy towards marking the server down. Exempt the check alone, not loopback: have the proxy send
it with a fixed `X-Forwarded-For` from a documentation range, and list that address here. No visitor can pose as
it: on real traffic the proxy adds the visitor's own address to the right of anything the visitor sent (or replaces
the header), and Runway reads the header from the right. With HAProxy 2.2 or later:

```
backend runway
    option httpchk
    http-check send meth GET uri /actuator/health ver HTTP/1.1 hdr Host www.example.com hdr X-Forwarded-For 192.0.2.53
```

and `192.0.2.53` in `allowlist`. Loopback must be in `runway.proxy.trusted-cidrs` (it is, for a same-host proxy);
leave the probe's address out of it, which is for real proxies only. Checking
`/actuator/health` from outside the proxy (a monitoring service) needs no header: list that service's own address.

> **If you are blocked, stop — do not keep probing.** Once an address is auto-blocked, requests no longer
> reach the rate limiter at all; they hit the blocklist. So the `rate-limit` counter freezes while
> `blocklist-auto` keeps climbing, and continued probing simply confirms the block rather than measuring it.
> Wait out `block-seconds`, or restart the service (auto-blocks are in-memory and clear on restart; manual
> operator blocks are persisted and do not).

### 12.5 Edge-rejection visibility

| Key | Default | What it does |
|---|---|---|
| `runway.protect.log-rejections` | `true` | Write a line to the `runway.protect` logger for each edge `403`/`429`. Before this existed a rejection appeared in **no** log at all — it short-circuits outside the access log. Turn it off only if you collect rejections elsewhere; the metric keeps counting either way. |
| `runway.protect.log-rejection-window-seconds` | `60` | Suppress repeat lines for the same *(client IP, reason)* pair for this long. A flood guard, not a preference: logging every rejection lets a flood the limiter absorbs in O(1) become an unbounded stream of disk writes. Suppressed rejections are counted and reported on the next line for that pair. |
| `runway.logs.access-log-user-agent` | `false` | Append the `User-Agent` to each public access-log line. Off by default because that log is one line per request, and an upgrade should not silently change the size or shape of an operator's log stream. Edge-rejection lines always carry the agent regardless — those are rare and undiagnosable without it. |

The two `runway.protect.*` keys are on **Settings → Server settings**, group **Protection logging** (or in
`external/application.yml`, which wins), and apply after a restart. The form takes a window of 1 to 86400 seconds; a
value of 0 or below set by hand works as 1 second. The rejection lines are written at `WARN`, so the logger's level is
a second switch over the same lines: setting the `runway.protect` logger to `ERROR` (or `OFF`) silences them too,
either on **Activity → Logs → Log levels** (at once, and kept across restarts) or with
`logging.level.runway.protect: ERROR` in `external/application.yml` (after a restart). Prefer
`runway.protect.log-rejections: false` for that, so there is one place to look; leave the level alone otherwise, or
the lines vanish while the setting still says they are written.

Both agent values are sanitised and truncated before they reach a log line: a `User-Agent` is untrusted client
input, and a raw newline in one fabricates what reads as a separate, trustworthy log entry.

`runway_edge_rejections_total` is registered **at zero for every outcome the active guard can produce** when
the process starts, so it is in the scrape from the first second and a dashboard reads `0` rather than
"No data".

See `OPERATIONS.md` §3.1 for how to read the output — including which status each rejection reason answers
with (auto-blocks are `429` + `Retry-After`, operator bans are `403`) — and `monitoring/README.md` for
`runway_edge_rejections_total`.

## 13. Backups & restore

Each site is backed up as a **complete, self-contained `.zip`** — its data, presentation
(templates and static assets), configuration (users, roles, permissions, per-plugin settings),
and any plugin-generated per-tenant data. Because a backup is complete, it restores on the same or
any later platform version and renders identically; it is also a site's **portable copy**.

**Who takes them.** In this release backups are a **per-tenant (site-manager) feature**, on the
tenant console (**Backups**, `/__admin/backups`), for the site's own data only:

| Who | Save on the server / list / delete | Download a transfer copy | Download a **full** backup (fresh or saved) | Restore |
|---|---|---|---|---|
| Site superadmin, or the *Administrator* (`ADMIN`) tier | yes | yes | yes | yes |
| A user **granted** *Manage backups* (`MANAGE_BACKUPS`) | yes | yes | **no — `403`** | no — `403` |
| Anyone else | no | no | no | no |

A full backup is complete by design, so it carries every account's Argon2id password hash and the site's
`ENC(...)` plugin secrets; a delegated holder could otherwise take those off the server. The screen hides the full-download buttons from a delegated
holder and explains why; the routes answer `403` regardless. A backup the holder saves on the server stays
there for an administrator — or the platform operator, on `/__app/backups` — to download or restore. See the
*Site Manager & Designer Guide* §12 for the end-user workflow.

**Who did it is recorded.** Each backup act on the site console that took effect — saving, downloading (full,
transfer, or a saved backup), deleting and restoring — is a line in that site's own audit journal (`backup.saved`,
`backup.downloaded`, `backup.deleted`, `site.restored`; Site Manager & Designer Guide §3). The same acts on
`/__app/backups` are in the platform's activity log, under the same names, and not in the site's journal. **A
restore keeps the site's logs and traffic history** (on either console): it replaces the content only, so the
journal still holds what happened before it; logs or stats inside an archive are ignored, as no backup writes them.
A site created from a backup starts its own logs.

**Pending editorial work travels with the site.** On a site whose manager has turned on review
(*Site Manager & Designer Guide* §10), changes waiting for approval are kept beside their pages under
`sites/<id>/templates/.pending/<slug>/`. They are site content, never served, listed or indexed: a backup
carries them, a restore brings them back with their pages, and a site created from a backup keeps its
editorial level (the `editorial:` key of `site.yml`). A **transfer copy leaves the whole `.pending` folder out**
(below): each review record names site accounts and carries the reviewer's note, and the drafts are
unpublished work — not something to hand to a third party.

**Operator backups.** The platform operator has an application-realm counterpart at
**`/__app/backups`** (superadmin-gated): back up, download and restore **any** site, and — the
inverse of restore — **create a new site from an uploaded backup** (give it a new id, name and
hosts; the content comes from the archive). Because a backup carries a site's users and hashes,
this screen is superadmin-only, and the download of another site's backup is a privileged action.

**Creating a site from a backup, in detail.** The **new id** follows the site-id rule (§10) and must be free. A
blank **name** keeps the name in the archive. **Hosts** are separated by commas or spaces and checked like any
site's: a malformed host, or one another site answers on, is refused before anything is created, and the page says
*"Those hosts cannot be used:"* followed by the reason. Empty hosts are allowed (the site is
then reachable only as the catch-all default). Everything else comes from the archive: the languages, the base URL
(set it again in **Edit hosts** if it named the old site's host), the editorial level, the content, and the site's
own users with their password hashes and permissions. The new site starts **active**. Its **superadmins** do not
come with it: they live in `external/site-users.yml`, outside the site folder, so assign one on **Site master
users** (§10) — until then only the site's own users (and a superadmin assigned `"*"`, §10) can manage the new site.

**Backup vs transfer copy — two different files.** A backup must be complete or it is not a backup, which
means it carries the site's user accounts (with their password hashes), its per-plugin credentials, and the
hostnames it answers on. That is exactly what you want for recovery and exactly what you must not hand to
anyone else.

Both consoles therefore offer a second action, **transfer copy** (`/__admin/backups/download-transfer` and
`/__app/backups/<siteId>/download-transfer`). It produces the same archive format, restorable the same way,
with:

- `users.yml` and `permissions.yml` **omitted** — accounts, hashes and grants;
- traffic history (`stats/bandwidth.csv`) and logs — absent, as from **every** backup: they are transient
  per-site data that no backup carries (and a restore keeps the target site's own);
- every per-site plugin value that is **declared** secret, stored `ENC(...)`, or **named** like a credential
  (`secret`, `password`, `passwd`, `token`, `credential`, `apiKey`, `privateKey`) — **blanked** at any depth,
  including inside nested maps and lists, and kept as an empty key so the recipient sees what to fill in;
- `site.yml` hostnames and base URL replaced with a reserved `<siteId>.example.com` placeholder;
- **participant exports skipped** — a plugin's out-of-folder state, which is either regenerable (a search
  index) or personal data that must not travel;
- **plugin personal data omitted** — every file a plugin declares as personal in its
  `plugin.yml` (`siteData.personal`: the search terms visitors typed, …), and the
  **whole** `plugins/<id>/` folder of any plugin that is not installed and loaded when the copy is made. The second
  rule is the fail-safe one: without a loaded plugin's declaration nobody can tell what in its folder is personal,
  so none of it travels;
- **pending editorial work omitted** — the whole `templates/.pending/` folder: the
  unpublished revisions waiting for review and their `review.yml` records, which name the accounts that
  submitted and returned them (often email addresses) and the reviewer's free-text note. The recipient gets the
  site as published. The archive's `manifest.yml` lists every omission under `omitted:`, and the site's
  **Backups** screen shows, under the transfer button, what a copy made now would leave out. Restoring a transfer
  copy works as before: the omitted files are simply absent, and every plugin treats an absent file as empty.

A **full backup keeps everything**, personal data included — it is for recovering this site, and a backup that
silently dropped submissions would not restore the site you had. Treat backup files accordingly. Note also that
the *settings* files (`plugins/<id>.yml`) keep the secret-blanking rule above, so a non-secret setting such as a
recipient address still travels; only the declared data files are left out.

**Four independent nets, because any one of them misses.** A declared `siteSettings` schema is the most
precise, but it only helps for plugins that use the generic settings form — the bundled **protection** plugin
declares no schema and persists its own nested record, so declaration alone covered none of it. `ENC(...)` is
self-identifying and needs no schema. The **name** net covers a plugin that stores a secret in plaintext and
declares nothing, which neither of the others can see. And all three run **at every depth**, because a
top-level-only walk would copy the protection plugin's encrypted CAPTCHA secret through
verbatim.

A bare `key` is deliberately **not** a secret-name: a CAPTCHA *site* key is public — it ships in the page
markup — and blanking a working setting is its own kind of harm. It is still origin-bound and useless to the
recipient, so a transfer copy is *safe to send*, not *ready to run*: the recipient supplies their own keys.

Every blanked path is written to the log at `INFO` when the archive is produced, so what the sanitiser did —
and by implication what it did not — is visible to whoever is about to hand the file over.

The archive's `manifest.yml` records `purpose: RESTORE` or `TRANSFER`. **An archive with no `purpose` reads as
`RESTORE`** — every archive written before the field existed is complete, and the opposite default would mean
treating a file full of secrets as though it had been cleaned.

**Backups from a newer Runway.** The manifest also records the archive's `formatVersion` (currently `1`; an
archive without one is format 1). Restore, on either console and
when creating a site from a backup, refuses an archive whose format is **newer** than this release reads, with
the message *"This backup was made by a newer version of Runway … Upgrade Runway, then restore it."* Nothing is
changed. This protects a site from a restore that would silently drop data the older release does not
understand. The other direction always works: an archive from an older release restores on a newer one (the
promise and its limits are in [CONFIGURATION-AND-DATA-STABILITY.md](CONFIGURATION-AND-DATA-STABILITY.md) §3).

**Where they live.** Server-saved backups are stored at the deployment root under
`backups/<siteId>/` as timestamped `backup-YYYYMMDD-HHMMSS-SSS.zip` files. `backups/` is one of
the three **version-independent data directories** — alongside `external/` (config and site
data) and `logs/` — that survive in-place upgrades; keeping the archives out of `external/`
prevents them from bloating an `external/`-level sync. Include `backups/` in your host-level
backup rotation, and treat each `.zip` as sensitive (it contains the site's user records and
plugin secrets — never web-served). "Download now" backups are streamed to the operator's
browser and never touch the server.

**Retention (auto-prune).** So a site's `backups/<id>/` folder does not grow without bound, a
retention policy prunes older stored backups after each new one is written. It is configured
app-wide (never per tenant) in `application.yml`:

```yaml
runway:
  backups:
    retention:
      max-count: 30      # keep at most the newest N per site; 0 = unlimited
      max-age-days: 0    # also drop backups older than N days; 0 = no age limit
    max-entry-bytes: 268435456   # 256 MiB — reject any single restore entry larger than this (zip-bomb guard)
    schedule:
      enabled: false         # opt-in: back up EVERY site automatically
      cron: "0 30 2 * * *"   # 6-field Spring cron (sec min hour day month weekday) — 02:30 daily
    offsite-dir: ""          # blank = off; else an absolute dir every backup is mirrored into
```

Both limits apply independently (a backup is pruned if it is beyond the newest `max-count`
**or** older than `max-age-days`). The default keeps the newest **30** per site with no age
limit. Pruning only ever removes a site's *own* stored backups, never the live site data, and
the just-written backup is never pruned — so an explicit "Save on server" is always retained.
This governs only the server-stored archives; downloaded `.zip` files are yours to keep.

The same policy is on **Backups → Settings** in the console (except `offsite-dir`, which only the server sets);
either way it applies after a restart.

**Automatic backups (opt-in).** Set `schedule.enabled: true` and a `cron` and Runway backs up
**every** site on that schedule — each site isolated, so one site's failure never stops the
rest, and each run is pruned by the retention policy above. Left off (the default) no backup is
ever scheduled; a manual console backup is always available regardless. The `cron` has six fields — second,
minute, hour, day of month, month, day of week (`0 30 2 * * *` is 02:30 every day, `0 0 3 * * SUN` 03:00 on
Sundays) — and Spring's shorthands `@daily`, `@weekly`, `@monthly`, `@yearly`, `@hourly` and `@midnight` work too.
The times are in the **server's time zone** (the Java default, shown on About Runway). The console refuses an
invalid expression; one set in `application.yml` stops startup while `schedule.enabled` is `true`, and is not read
at all while it is `false`. The backup runs on the
platform's shared scheduler pool (`spring.task.scheduling.*`, §8), which is always on because plugins
schedule work of their own.

**Off-host mirror.** Set `offsite-dir` to an absolute path — ideally a **mounted remote share**
(NFS/SMB/rclone-mount) or a second disk — and every newly-created backup (manual *or* scheduled)
is copied there as `<offsite-dir>/<siteId>/<name>.zip`, so a copy survives loss of the deployment
host. It is **best-effort**: if the target is unwritable (share unmounted) the local backup still
succeeds and a warning is logged. Off-host copies are pruned by the same retention policy. (Runway
does not push to object storage such as S3/GCS directly — mount it, or run a sidecar sync from the
off-host directory.) Under the installed systemd unit the service may write only inside `/opt/runway-data` (§6):
for a folder elsewhere, add it to `ReadWritePaths` in a drop-in, or every copy fails with that warning.

**What's included / excluded.** Included: everything under `sites/<id>/` plus each installed
plugin's per-tenant data. **Excluded by design:** the platform's own configuration, other
tenants' data (isolation — a backup can only ever contain the one site), and anything outside
the site folder reached via a symlink (symlinks are not followed when archiving, so a backup
cannot smuggle in host files).

**Restore safety.** Restore validates the archive is a genuine, complete site backup **before**
it swaps anything in, then swaps atomically. A corrupt, empty, or non-Runway file is rejected
with the live site untouched; an interrupted restore (e.g. a crash mid-swap) is **rolled
forward automatically on the next start-up** — the platform never leaves a site half-restored
or missing. Every entry is read under a **per-entry decompression cap** (`max-entry-bytes`
above, default 256 MiB), so a crafted "zip-bomb" archive — a tiny entry that inflates to
gigabytes — is refused instead of exhausting the server's memory or staging disk. Raise the cap
only if a site legitimately stores single files larger than that; a value of `0` (or negative)
falls back to the 256 MiB default rather than rejecting every entry. A site's **storage limit** (§10) does not apply
to a restore: a backup whose files are over the site's limit restores whole, and the site's Static files screen then
says it is over, so only new uploads wait. A site created from a backup has the platform default limit until you set
its own (the limit lives in `platform.yml`, not in the backup).

## 14. Monitoring & metrics

A **`/actuator/health`** endpoint **is** available — unauthenticated in both normal and wizard
mode — so you can wire a liveness/readiness probe today (`curl http://<host>:8080/actuator/health`
→ `{"status":"UP"}`). It carries a custom **`storage`** health indicator that checks the external data
directory is present and writable (a lost/read-only mount flips health to `DOWN`); per-indicator details
show only to an authenticated operator, so the public probe reveals only `UP`/`DOWN`. Runway also exposes a
**Prometheus scrape endpoint at `/actuator/prometheus`**, carrying the JVM/HTTP metrics plus Runway gauges
(`runway_sites`, `runway_sites_suspended`, `runway_sites_maintenance`, `runway_plugins_loaded`,
`runway_logs_dropped`) and a **per-site request timer** `runway_http_server_requests_seconds_*` (tagged
`site` + `outcome`, for per-tenant volume/latency/error rate). Route-level timing
(`http_server_requests_seconds_*`) comes from Actuator. Like the rest of `/actuator/**` it is
**operator-only** — a monitoring system scrapes it with an app-superadmin/admin session **or** an optional
**bearer scrape token** (`runway.metrics.scrape-token`, unset = off) that grants that endpoint only. A blank
field on Server settings keeps a stored token; §8.5 says how to remove one.

**Dashboards & alerts.** The release ships a **`monitoring/`** directory — a Grafana dashboard
(`runway-grafana-dashboard.json`), Prometheus alert rules (`runway-prometheus-alerts.yml`, covering
target-down, per-site 5xx/latency, dropped log lines, suspended sites and high heap), and a `README.md` with
the scrape config. Import the dashboard, reference the rules from `prometheus.yml`, and tune the thresholds.
See Operations §2 for the step-by-step.

Beyond the endpoints above, observe the service with `systemctl status runway`, `journalctl -u runway`,
and the launcher log at `/opt/runway-data/logs/runway.out`.

### The activity log (operator audit journal)

The app console's **Activity → Logs** screen (`/__app/logs`) is the platform's audit journal: who did what, and
when. It is the application-realm counterpart of each site's own **Logs** screen, which keeps that site's public
traffic and its managers' actions (see the Site manager guide, §3).

**What is recorded.** One entry per action, with a stable dotted *action key*:

| Area | Action keys |
|---|---|
| Sign-in and recovery | `login.success`, `login.failure` (with the client IP), `logout`, `recovery.requested` (a reset link was issued to that account), `recovery.completed` (the account's password was reset through the link), `account.password-changed`, `account.profile-changed` (own name or recovery email), `account.password-check-failed` (a wrong current password on the Account page) |
| App users and site superadmins | `appuser.created` / `updated` / `suspended` / `resumed` / `password-reset` / `deleted`, `master.created` / `password-reset` / `assigned` / `unassigned` / `suspended` / `resumed` / `deleted` |
| Roles and permissions (`/__app/permissions`) | `permissions.role-assigned` / `role-revoked` (a user's roles), `permissions.permission-granted` / `permission-revoked` (a user's direct grants), `permissions.role-permission-granted` / `role-permission-revoked` (a role's grants), `permissions.role-created` / `role-deleted`, `permissions.role-inherit-added` / `role-inherit-removed` |
| Sites | `site.created`, `site.suspended`, `site.resumed`, `site.deleted`, `site.hosts-updated`, `site.captcha-disabled`, `demo.installed` |
| Backups | `backup.saved`, `backup.deleted`, `site.restored`, `site.created-from-backup` |
| Platform | `platform.settings-changed`, `platform.test-email`, `platform.restart`, `site-template.created` / `imported` / `deleted` |
| Plugins | `plugin.installed`, `plugin.enabled`, `plugin.disabled`, `plugin.uninstalled`, `plugin.settings-changed` |

Only changes that **took effect** are recorded: a refused permission change, or a recovery request for an
address that matches no account, writes nothing (the typed address is anonymous input and is never written).
**No password, reset token, link or setting value is ever recorded** — a settings change names the plugin, not
what was set. The *detail* column carries the target (a site id, a username, a principal id, `role=… permission=…`).

**Who can read it.** The screen and its CSV download require the **View audit log** (`VIEW_AUDIT`) application
permission. The application superadmin and wildcard administrators (`SYSTEMADMIN`) hold it; an ordinary
**operator does not**, until you grant it on **Access → Permissions** — to that user directly, or to a role they
hold. *Why:* the journal names every account and site and when each was touched, which is more than an
operator needs to do their job; it is granted deliberately instead of falling to anyone who can sign in.
Without the permission the **Logs** entry is not
in the menu, and when nothing else is left in **Activity** the whole dropdown is hidden.

**Using the screen.** Entries are listed **newest first**, 100 per page, across the **whole retention window**
(Older / Newer page through it). Filter by a **date range** (the day files to read), an **actor** and an
**action** — both match any part of the text, case-insensitively, so action `permissions.` shows every
permissions change and `login` every sign-in. Each action is shown by its label in your console language with the
raw key beneath it (the key is what the filter matches; an action Runway has no label for — a plugin's own, say —
shows as the key). **Download CSV** exports the filtered entries (at most 10 000 rows):
`time` is ISO-8601 with its UTC offset, then `actor`, `action` (key), `label`, `detail`. A cell that a
spreadsheet would evaluate as a formula (starting `=`, `+`, `-`, `@`) is prefixed with `'`, so a crafted
username can never run as a formula when an auditor opens the file. A response header
`X-Runway-Audit-Incomplete: true` marks an export that holds fewer rows than match.

**Bounds.** A search reads at most 250 000 lines and 32 MiB of journal, and at most the newest 4 MiB of any one
day; when a search stops at that budget the screen says so and asks for a narrower date range. *Why:* a
sign-in flood can grow one day's file far beyond normal, and opening the journal must never pull an
unbounded amount into memory.

**Format and storage.** Day files `external/activity/activity-YYYY-MM-DD.log` (platform state, **not** in any
tenant backup), one tab-separated line per entry: `time  actor  action  detail`, with CR/LF/TAB stripped from
every field so a crafted value cannot forge a line. The time is ISO-8601 local time **with its UTC offset** and
milliseconds (`2026-10-17T08:10:03.123-06:00`). *Why the offset:* without it, the hour repeated when daylight
saving ends — or a server moved to another time zone — makes two different moments read the same. For
retention beyond the window below, or alerting, ship the files to your log stack — the format above is stable.

**How long it is kept, and switching it off.**

- `runway.app-activity.retention-days` (default `90`) — day files older than this are deleted at startup and on
  the first recorded action of each day. Set it on **Activity → Logs → Settings** (1 to 3650) or in
  `application.yml`; it applies after a restart. `0` or a negative value (possible only in `application.yml`) means
  1 day, not "keep forever".
- `runway.app-activity.enabled` (default `true`) — `false` stops recording, and with it the pruning; the day files
  already written stay on disk and on the screen. It is set **only on the server** (`external/application.yml` or
  the environment), never in the console, so that the people the log records cannot switch it off. Applies after a
  restart.

### Site logs (`runway.logs.*`)

Each site keeps its own access log (its public traffic) and audit log (its managers' actions) under
`sites/<id>/logs/`; they feed the site's **Logs** and **Insights** screens. Set these on **Settings → Server settings
→ Site logs** or in `application.yml`; all apply after a restart. SETTINGS-REFERENCE.md lists their ranges.

- `runway.logs.enabled` (default `true`) — `false` stops writing both per-site logs and their daily clean-up; files
  already written stay. Each site's **Insights** screen shows no data while it is off. The platform-wide
  `runway.access` log lines (Operations §3) are not affected.
- `runway.logs.max-retention-days` (default `90`) — the longest a site may keep its logs. Each site chooses its own
  retention on its Logs screen (30 days unless it changes it), up to this; a lower platform value holds a site that
  chose more to the new value from the next restart (§8.5). `0` or a negative value (only possible in
  `application.yml`) means 1 day.
- `runway.logs.queue-capacity` (default `8192`, at least `16`) — log lines wait in a queue between the request and
  the disk, so a slow disk never slows a page. When the queue is full, new lines are **dropped**, not waited for:
  the count is the `runway_logs_dropped` metric (and the Logs screen), and a `WARN` *"site log queue saturated"* is
  logged at most once a minute. An eighth of the queue is held for audit lines (sign-ins, changes), so a flood of
  public requests drops access lines first. Raise it if that count grows; each queued line takes a little memory.

### Bandwidth metering (`runway.bandwidth.*`)

Runway counts the bytes each site serves, per hour, for the **Bandwidth** screens (the platform's and each site's)
and for the monthly cap (§12). Set these on **Bandwidth → Settings** or in `application.yml`; all apply after a
restart.

- `runway.bandwidth.enabled` (default `true`) — `false` stops counting: nothing new is recorded, and the screens keep
  showing the history recorded before. With counting off **no bandwidth cap is enforced** (a cap is measured by
  counting; nothing new would ever lift it), so turn counting back on when you want caps to apply.
- `runway.bandwidth.retention-days` (default `90`) — how many days of hourly counts are kept, in memory and in each
  site's `sites/<id>/stats/bandwidth.csv`; older hours are dropped (from the file at every write, from memory once a
  day). Keep it at **30 or more**: the cap and the "last 30 days" figures add up the last 30 days, so a shorter
  history makes them count less than was served. `0` or a negative value (possible only in `application.yml`) keeps
  at most the current hour on disk, so the cap and the history stop meaning anything.
- `runway.bandwidth.snapshot-seconds` (default `60`) — how often the counts are written to disk and the cap is
  re-checked, so a site that goes over its cap is blocked within this time (a cap changed on Sites is re-checked at
  once). Values below 1 behave as 1. A crash loses at most this much counting.

## 15. Upgrades

**Upgrade = re-run the installer with the newer `.bin`.** The same self-extracting installer
installs and upgrades, and it is idempotent:

```bash
chmod +x runway-<newer-version>.bin
sudo ./runway-<newer-version>.bin -y      # or interactively
```

Do not repeat `--port` or `--tls-keystore` from the first install: on an existing install the installer refuses
them, because they would replace `external/application.yml` (**§3**). The port and TLS stay as that file says.
**If Runway binds port 80 or 443 itself**, the upgrade rewrites the main unit but keeps `CAP_NET_BIND_SERVICE` when
the unit it replaces granted it, and a drop-in of yours (§6) is never touched, so the service can still bind its ports.

**Why your config and data survive.** The installer separates *code* from *state* (**§3**, **§5**).
An upgrade unpacks the new release into a **new versioned dir** `/opt/runway-<newer-version>/`,
symlinks the **same** version-independent data dir (`/opt/runway-data/` — `external/`, `logs/`,
`backups/`) into it, and flips the `/opt/runway` "current" symlink to the new release. Your
`application.yml`, all site folders, plugin folders, logs, and backups are never touched — they were
never inside the versioned dir. The service is restarted on the new code at the end.

**Browsers pick up the new console at once — nobody needs to clear a cache.** The console's own scripts,
stylesheets and images are served from addresses that change with every release (`…/page-editor.js?v=<build
time>`), so a browser cannot keep using the previous release's copy; it caches each one for a day only under
that release's address. An asset requested
without that version — for example from a third-party plugin's own page — is revalidated on every use, so it is
never stale either, only slightly slower. A reverse-proxy cache in front of Runway that honours `Cache-Control`
needs no purge of `/__assets/` after an upgrade.

**Essential plugins on upgrade — always refreshed.** The bundled essential plugin
(`protection`) lives in the operator-owned `external/plugins/protection/`, and every upgrade refreshes it from the
new release: the jar and the descriptor (the previous ones kept as `<name>.jar.bak` and `plugin.yml.bak`, which a
rollback restores — see *Rollback* below), bundled `lib/`, and the plugin's `templates/` and `i18n/`. Your settings
live outside the folder and are never touched. No flag is needed; `--update-essential-plugins` is accepted
and does nothing.

*Why always.* The essential plugin moves in lockstep with the kernel: it is the one plugin allowed to use kernel
internals; its descriptor's SPI floor is the major line's (`>=1.0.0`, met by every 1.x kernel, raised only when it
uses a newer SPI member). An older copy beside a newer kernel can crash on a missing internal class, or be refused
(Blocked) if its floor is ever above the kernel's — and because it is the essential, fail-closed plugin, either way
every tenant page answers `503` — or it loads and runs the previous release's code. What makes "always" safe is what the refresh keeps, below.

*What you see.* The installer logs `refreshing bundled plugin 'protection' from this release`, then one line per
replaced file — `plugin 'protection': plugin.yml replaced (the previous one kept as plugin.yml.bak, for a rollback)`
— and a warning for each template/i18n file you had edited. Re-running the same installer changes nothing and says
so (`plugin 'protection' was already this release's — nothing changed`): a `.bak` is written only when the file
actually changes, so a re-run never overwrites the previous release's copy. A `plugin.yml` you edited by hand (for
example `enabled: false`) is replaced too; your copy is the `plugin.yml.bak`. An essential plugin cannot be
switched off that way in any case — a disabled essential plugin is a broken floor and fails closed (`503`).

**Why `templates/` and `i18n/` are refreshed too.**
Holding those two folders back would sound careful and be wrong in a way nobody could see: an essential plugin's
**screens** ship in exactly those files, so a release that added one could never reach an upgraded install. The
controller would pass the data and the old template would simply not read it: no error, no missing file, just a
feature that was not there. The refresh never destroys your work, though, and it can tell your edits from the release's:

- The installer records what it laid down — a SHA-256 per `templates/`/`i18n/` file — in
  `external/plugin-manifests/<id>.sha256`, outside the plugin folder so it survives the folder being replaced.
- A file that still matches that record was **not edited since it was installed**; it is merely the previous
  release's copy. It is refreshed with one log line and **no backup**.
- A file that differs from the record **was edited by you**. It is copied aside as
  `<name>.operator-backup-<timestamp>`, the path is printed as a warning, and then the new version is written, so
  your customization is recoverable by re-applying it on top of the new file.
- An install with no record yet gives the installer no way to know who
  changed a differing file. It assumes you did — backing up and warning, saying exactly that — because guessing
  "unedited" would silently destroy a real edit. It then writes the record, so the **next** upgrade is exact.
- Byte-identical files are skipped without a word.

**Verify the upgrade.** After it finishes:

```bash
ls -l /opt/runway                  # now -> runway-<newer-version>
systemctl status runway            # active (running)
journalctl -u runway -e            # startup banner shows the new version, ACTIVE mode
```

Sign in at `/app/login` and confirm your sites and users are intact.

**Confirm the protection plugin by its bytes, not by its version.** On the **Plugins** screen a hand-copied or
partially restored folder shows the same version as the release's own copy. Compare the folder with the copy the running release ships:

```bash
P=/opt/runway-data/external/plugins/protection; S=/opt/runway/seed/plugins/protection
sudo cmp "$S/runway-plugin-protection.jar" "$P/runway-plugin-protection.jar" && sudo cmp "$S/plugin.yml" "$P/plugin.yml" && echo "protection is this release's copy"
```

The closing line means the folder holds this release's plugin. A `cmp` line that says `differ` means it does not:
copy the release's own plugin in (§12). Compare both files: the descriptor carries the setting ranges and sections,
so a current jar under an older `plugin.yml` still accepts what the older plugin accepted. The same two lines
confirm a rollback after the flip, because `/opt/runway/seed/` is then the older release's own copy. A quick look that needs no seed: `unzip -p
"$P/runway-plugin-protection.jar" META-INF/MANIFEST.MF | grep Implementation-Version` prints the build the jar
came from (the jar only). `sha256sum -c` against
`external/plugin-manifests/protection.sha256` proves nothing here: that record lists only `templates/` and `i18n/`.

**Rollback.** Because the previous release dir is left in place, a rollback is a **symlink flip**
back to it plus a restart:

```bash
sudo ln -sfn /opt/runway-<previous-version> /opt/runway
sudo systemctl restart runway
```

(Do not delete a versioned release dir until you're confident you won't roll back to it.)

**Put the previous protection plugin back first.** The upgrade refreshed the plugin in the version-independent
`external/plugins/protection/` (every upgrade does), so the symlink flip does not take it back: the older kernel
meets the newer plugin. If the older kernel refuses it, every site answers `503`, because protection is the
essential, fail-closed plugin (the console stays up; the log line and the plugin's row say what to do).
Restoring only one of the two files is worse: the new jar under the old descriptor can fail with a
`NoClassDefFoundError` naming an SPI type. If there is no `plugin.yml.bak`, the upgrade did not change the plugin
and there is nothing to restore. The installer keeps the previous jar **and** descriptor beside the new ones
(`*.bak`, written only when the file changed, so re-running the installer does not overwrite them); restore both,
then flip:

```bash
P=/opt/runway-data/external/plugins/protection
sudo cp -f "$P/runway-plugin-protection.jar.bak" "$P/runway-plugin-protection.jar"
sudo cp -f "$P/plugin.yml.bak" "$P/plugin.yml"
sudo ln -sfn /opt/runway-<previous-version> /opt/runway
sudo systemctl restart runway
```

The descriptor matters as much as the jar: the newer `plugin.yml` can declare a `requiresSpi:` floor the older
kernel does not meet, so beside the old jar it would get protection refused (Blocked) by the older kernel, and with the essential plugin
blocked the sites answer `503`. **Confirm** after the flip with the two `cmp` lines under *Verify the upgrade*,
not with the version on the Plugins screen.

**Rolling back by re-running the older release's installer** (instead of flipping the symlink) puts protection back
too: the installer refreshes protection on every run, so it puts its own copy back and keeps
the newer one as `*.bak` — the same rollback, done for you.

**The older release carries its own copy:** the installer keeps each
release's bundled plugin in `/opt/runway-<version>/seed/plugins/protection/`, so after the flip
`/opt/runway/seed/plugins/protection/` is exactly the plugin that release shipped. Copying it over the plugin
folder (the commands in §12) is the same restore as the `.bak` pair — and the one that still works when the pair
no longer holds that release's files, because you upgraded twice since (each upgrade keeps only the release just
before it). The screens' `templates/` and `i18n/` can stay as they are — the older plugin renders them; if you
had edited one, your copy is the `*.operator-backup-<timestamp>` file next to it. A plugin you installed for the newer kernel may be Blocked by the
older one on its `requiresSpi:` floor; re-install its previous version (**§11**).

**Rolling back to a release that does not know the newer one's settings.** The flip takes back the code, not
what you set on the newer release's new screens. The older release reads `external/platform.yml`, ignores what it
does not know, and drops it when it writes the file. Before the flip, copy the file aside:
`sudo cp -p /opt/runway-data/external/platform.yml /opt/runway-data/external/platform.yml.<newer-version>`
(`-p` keeps it mode `600`: it can hold encrypted tokens). A setting the older release does not know is back on
its default under it; a key you removed from `application.yml` to manage it in the console (the trusted proxy
addresses, a health, metrics or reload token, …) goes back into `application.yml` for as long as the older
release runs. While on the older release, do not save **Settings → Platform settings**, delete the site that is
the default site, or install a demo site that becomes the default: each rewrites `platform.yml` without the newer
blocks. A template that reads a variable the older release does not provide fails with a `500` on every page of
that site after the flip (the console keeps working; the log names the expression and the template); a release's
upgrade notes name its new variables — edit the binding on the site's **Fragments** screen rather than restoring
a site backup, which would also undo every page, user and setting changed since. To roll forward again: stop the
service, restore the copy over `external/platform.yml`, run the newer release's installer
(`sudo ./runway-<newer-version>.bin -y`: it flips the link, puts that release's protection back and starts the
service — a bare flip would leave the older release's protection under the newer kernel), confirm protection with
the two `cmp` lines under *Verify the upgrade*, and take out of `application.yml` the keys you put back.

**Plugins across upgrades.** Installed plugins live in `external/plugins/` and carry through an
upgrade untouched. A plugin compiled against a specific platform/SPI version may need a matching
build after a major platform change; watch the **Plugins** screen (**§11**) after an upgrade for any
row that has become **Blocked** or **Malformed**, and update that plugin if so.
Until you do, its features are off, while the rest of the platform runs. The protection plugin needs nothing
from you: the installer refreshes it with the kernel (*Essential plugins on upgrade* above); if you replaced
the kernel jar by hand instead of running the installer, copy the bundled protection folder in as §12 describes.

> **Configuration and data across upgrades.** [CONFIGURATION-AND-DATA-STABILITY.md](CONFIGURATION-AND-DATA-STABILITY.md)
> is the promise for your configuration, data folder and backups; it binds from `1.0.0`. The data folder and backups carry the
> version markers it is built on. Whatever the release, read the CHANGELOG's upgrade notes ("Upgrading — read this
> first", "Upgrade note", "Upgrade effect") and **take a backup first** (**§13**).

**Rolling back and the layout marker.** `external/layout.yml` records the version of the data folder's
layout (§5). A release that changes the layout migrates the folder on its first start and raises that number;
the previous release then **refuses to start** against the migrated folder ("APPLICATION FAILED TO START …
declares layout version N, but this Runway build supports up to version M") instead of writing into a layout
it does not understand. Rolling back past such a release therefore means restoring the copy of `external/`
you took before the upgrade. Every release so far uses layout `1`, so today the layout never forces that
restore — but an essential-plugin refresh can need the smaller one described under *Rollback* above.

### Memory tuning (heap + cgroup cap)

Two independent knobs govern Runway's memory: the **JVM heap** (`-Xmx`) and an optional **systemd cgroup ceiling**
(`MemoryMax`). The golden rule for both: **put durable tuning in a systemd _drop-in_, never in the shipped unit.**
The installer **rewrites the main unit** (`/etc/systemd/system/runway.service`) on every upgrade, so an `-Xmx`
hand-edited there is silently reverted on the next `install.sh` run — but the installer never touches your
`runway.service.d/*.conf` drop-ins.

**Let the installer write the drop-ins** — pass `--heap` and/or `--memory-max` (on install or any later upgrade):

```bash
sudo ./runway-<version>.bin -y --heap 2g --memory-max 3g
```

- `--heap 2g` → `runway.service.d/20-heap.conf` with `Environment="JAVA_OPTS=-Xms256m -Xmx2g"` (overrides the main
  unit's `JAVA_OPTS`). `-Xms` is always `256m`. You may add other JVM flags to that line, but the next `--heap`
  rewrites the file whole and drops them, so add them again after each `--heap` (or stop using `--heap` and keep
  the whole line yourself).
- `--memory-max 3g` → `runway.service.d/10-memory.conf` with `MemoryAccounting=yes` + `MemoryMax=3g`.

Both survive every future upgrade. Passing neither leaves any existing tuning as-is. To change values later, re-run
with the flags (or edit the drop-ins and `systemctl daemon-reload && systemctl restart runway`).

**Sizing rule.** `MemoryMax` bounds the **whole JVM process** — heap **plus** metaspace, thread stacks, code cache,
direct/NIO buffers and GC overhead. So always **`MemoryMax` ≥ `-Xmx` + headroom**, where headroom ≈
`max(0.5 GiB, ~25 % of -Xmx)`. Make `-Xmx` the primary limiter and `MemoryMax` a backstop above it — never set
`MemoryMax` ≤ `-Xmx`, or the cgroup will OOM-kill a JVM that is still inside its own heap budget. (The installer
warns if you set `--heap` without a matching `--memory-max`.)

**Dedicated host (the common case) — all RAM is Runway's.** Give Runway most of RAM, leaving room for the OS + page
cache + JVM non-heap. For a box with `R` GiB: `MemoryMax ≈ 0.85·R`, `-Xmx ≈ MemoryMax − headroom`. E.g. **8 GiB →**
`--heap 5g --memory-max 6800m`; **16 GiB →** `--heap 11g --memory-max 13500m`. (You may instead omit `--memory-max`
and just size `-Xmx` generously — the heap self-limits; the cgroup cap is cheap insurance against a runaway.)

**Shared host — Runway co-tenant with other services.** Keep Runway's `MemoryMax` well below total RAM so it can't
starve the neighbours, and add **`MemoryLow` floors on the _other_ units** (not Runway) so the kernel reclaims from
Runway first under pressure — e.g. `sudo systemctl set-property php-fpm MemoryLow=512M`.

### A Lucene log line to leave alone (`jdk.incubator.vector`)

With the search plugins installed (search-lucene, lucene-provider), the log carries this warning once per start, at
the first search after the server is up (logger `o.a.l.i.v.VectorizationProvider`, on a request thread):

```
Java vector incubator module is not readable. For optimal vector performance, pass '--add-modules jdk.incubator.vector' to enable Vector API.
```

**Leave it off.** The flag only speeds up Lucene's vector arithmetic, which serves mainly
k-nearest-neighbour vector search; Runway's search is keyword search over a few dozen to a few thousand pages, so
it gains nothing measurable. The module is an *incubator* module: the JVM then prints its own
`WARNING: Using incubator modules: jdk.incubator.vector` at every start, and the feature depends on a JDK API that
is not final. If you want it anyway, add `--add-modules jdk.incubator.vector` to `JAVA_OPTS` in a drop-in (the
`20-heap.conf` line above, or one of your own), never in the main unit, which the installer rewrites.

## 16. Security hardening checklist

The installer applies most of this for you; use the checklist to verify and to catch drift after
manual edits.

**Sensitive files (the trusted computing base).** These hold secrets or the platform's identity and
must be owned by the service user and unreadable by others. Keep them **outside** any web-served or
tenant-writable path (they already are, under `external/`):

- `external/application.yml` — holds the superadmin pin and `secret-key`. **Mode `600`** (the wizard
  sets this). Verify: `ls -l /opt/runway-data/external/application.yml` → `-rw------- runway runway`.
- `external/app-users.yml` — the application operators, including the pinned superadmin.
- `external/platform.yml` — platform settings (**§8.1**).
- `external/plugin-settings/*.yml` — app-level plugin settings (may contain `ENC(...)` secrets).
- `external/csp-nonce.key` — the public-page CSP nonce secret (**§8.4**); created `600` by the service.
- `backups/**` — each site backup zip contains that site's user records and plugin secrets; treat as
  sensitive and never web-serve it (**§13**).

Quick check that the whole data tree is owned by the service user:

```bash
sudo chown -R runway:runway /opt/runway-data
find /opt/runway-data/external -maxdepth 1 -name '*.yml' -exec ls -l {} +
```

**SELinux (RHEL-family — required).** The `.bin` unpacks into a *user-temp* dir, so a plain copy can
carry the `user_tmp_t` context onto the install; on an **enforcing** host systemd then refuses to
exec the launcher with `status=203/EXEC` — a silent, baffling failure even though the file is `0755`.
The installer runs `restorecon -RF` on the release and data dirs to fix this (a no-op where SELinux
is absent). If you ever lay files by hand, re-run it:

```bash
sudo restorecon -RF /opt/runway-<version> /opt/runway-data
sestatus                                   # confirm enforcing/permissive
```

**Host firewall (opt-in, best-effort).** By default the installer does **not** touch the firewall —
behind a reverse proxy the app port is internal and the firewall is your call. When Runway faces
clients directly, open its port with `--open-firewall` (or `--firewall-ports LIST`). The installer
drives **firewalld** and an **already-active ufw** only; it **never enables ufw** (that could drop
your SSH session) and **never edits raw nftables/iptables** — it prints the manual command instead.
`--no-firewall` guarantees it won't touch the firewall at all. Verify the port is open:

```bash
sudo firewall-cmd --list-ports        # firewalld
sudo ufw status                        # ufw
```

**Network posture.**

- Prefer running **plain HTTP behind a reverse proxy** that terminates TLS; set
  `runway.proxy.trusted-cidrs` to the **exact** proxy IP (not a broad range) so forwarded headers
  are trusted only from it (**§7**). Never put a tenant-editable value there — it is an
  isolation/abuse boundary.
- Keep the first-run **`/setup`** page off the public internet until setup completes (**§4**).
- Give the **operator console** a host name of its own and set `runway.console.hosts` to it (plus the server's
  address for emergencies), so the console answers nowhere else (**§8.6**). Without it, the console already refuses
  other sites' host names but still answers on the default site's: if people you do not fully trust manage that
  site's files or templates, do not sign in there, because their scripts would run with your session (**§8.4**).
- Site users signed in to a site's console (`/__admin`) are exposed to that site's own scripts in the same browser
  (**§8.4**); give *Configure site* and template editing only to people trusted with that site's console.
- Confirm the bundled **`protection`** plugin is present and enabled (seeded into `external/plugins/protection/`
  on a fresh install) — it provides IP rate-limiting/blocklisting and login brute-force lockout, and if it is
  removed the fail-closed floor takes public sites to `503` (**§12**). Volumetric/network DDoS stays upstream at
  the proxy/edge regardless.

**Passwords.** Set `runway.security.password-strength: HIGH` (or via the Settings screen) for
higher-value deployments (**§8.1**); it applies to new/changed passwords only.

Operational troubleshooting lives in [OPERATIONS.md](OPERATIONS.md).
