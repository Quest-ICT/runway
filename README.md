# Runway

**Runway** is a multi-site web platform — a **kernel + plugins** server: a small,
multi-tenant content/website core that ships only the essential bytes, with every domain
feature delivered as a plugin or a first-class SPI implementation.

| | |
|---|---|
| Coordinates | `mx.quest:runway` |
| Version | `1.0.0` (plugin contract: SPI `1.0.0`) — the exact version is in the root `pom.xml` and each release in [CHANGELOG.md](CHANGELOG.md) |
| Java | 25+ |
| Framework | Spring Boot 4 on embedded Jetty |
| Storage | Filesystem / YAML (no database); plugins may use PostgreSQL or MariaDB through the database plugins |
| Status | **Stable.** Semantic Versioning from `1.0.0`. The kernel, both realms, the setup wizard (with a catch-all default site), the page editor, per-site backups and optional in-app TLS termination run today; open items are in [docs/KNOWN-LIMITATIONS.md](docs/KNOWN-LIMITATIONS.md). |

## Modules

```
runway/                      reactor (this pom)
├── runway-spi/              dependency-free extension API (interfaces + immutable model records)
├── runway-spi-testkit/      in-memory fakes of the SPI services, for plugins' unit tests (test scope)
├── runway-app/              the Spring Boot kernel (Jetty) — collects SPI impls + plugin contributions
├── runway-plugin-protection/ the essential protection plugin (rate-limit edge guard, login gate, CAPTCHA;
│                             shipped inside the installer and seeded on a fresh install)
├── runway-dist/             packaging only: the dist zip, the self-extracting .bin installer, checksums
└── plugins/                 every first-party plugin, each a standalone project (not reactor modules)
```

The plugins under `plugins/` are **standalone** projects, each with its own build; one with code is built
against `runway-spi` as a `provided` dependency (with `runway-spi-testkit` for its tests), plus another plugin's
published SPI when it implements or uses one (search-lucene uses search's; the database providers and the guestbook
example use database's). A plugin that links `runway-app` classes is refused at
load time — the 1.0 plugin contract is the SPI ([SPI-STABILITY.md](docs/SPI-STABILITY.md)).

## Quick start

Two audiences: **operators** install the packaged release and finish setup in the browser;
**developers** build the reactor and run the jar. Both end at the same first-run wizard.

### Operator — install the release

The Linux release is a single self-extracting `.bin` (a shell stub with the deployment
bundle appended — no `makeself`, only stock `bash`/`tar`/`gzip`). Run it as root; it is
**idempotent**, so re-running the same or a newer `.bin` upgrades in place:

```sh
chmod +x runway-<version>.bin            # a download has no execute bit
sudo ./runway-<version>.bin              # interactive: prompts on a TTY
sudo ./runway-<version>.bin -y           # unattended, all defaults
sudo ./runway-<version>.bin -y --no-firewall   # ...and never touch the host firewall
```

Get it from the releases page (<https://g.quest.mx/quest/runway/releases>) and check it against the
release's `SHA256SUMS` before running it ([Administration §3](docs/ADMINISTRATION.md)). The stable
`…/releases/latest` page and `runway-latest.bin` link serve the newest published release — install an explicit
version in production.

The installer lays the code under `/opt/runway-<version>`, points `/opt/runway` at it,
keeps editable state (`external/`, `logs/`, `backups/`) in the version-independent
`/opt/runway-data/` so upgrades never clobber it, installs a systemd unit, relabels
SELinux contexts (required on enforcing RHEL-family hosts), and starts the service.
There is **no database to configure** — Runway is filesystem/YAML-backed (plugins that need a database use
the optional database plugins); first-run configuration happens in the app's own wizard (below).

Confirm it is up:

```sh
systemctl status runway          # active (running)
journalctl -u runway -f          # startup log; also /opt/runway-data/logs/
```

### Developer — build and run from source

**Prerequisites:** a JDK 25+ (with `javac`) and Maven.

```sh
# build the reactor (spi + spi-testkit + app + protection plugin + dist) and run the tests
JAVA_HOME=/path/to/jdk-25 mvn -e install

# run the packaged app (the -exec jar is the runnable one; the plain jar is the
# thin library other modules compile against)
java -jar runway-app/target/runway-app-<version>-exec.jar
```

The app listens on **:8080** by default and expects its `external/`, `logs/` (and
`backups/`) folders in the working directory — run it from the deployment root, or use
the launcher `scripts/runway.sh` which `cd`s there for you.

To produce the deployment artifacts — the dist zip (`runway-<version>-dist.zip`), the `.bin`
installer (`runway-<version>.bin`), stable-named `runway-latest.*` aliases of both, the plugin author's
`runway-spi` / `runway-spi-testkit` jars with their sources jars and poms, and a `SHA256SUMS` over all of
them, landing in `runway-dist/target/`:

```sh
JAVA_HOME=/path/to/jdk-25 mvn -P dist package
```

### First access — the setup wizard

On first boot Runway comes up in **wizard mode**: it has no accounts yet, so **every URL
redirects to `/setup`**. Open the site in a browser (e.g. `http://<host>:8080/`) and you
land on the setup form. Because the surface is open before setup completes, reach it only
over localhost or behind your reverse proxy until it is done.

The wizard collects **two administrators, one per realm** — the two realms never cross
(authenticating in one never grants the other), which is why setup creates both up front:

- **Application superadmin** — runs the *platform*: creates and manages sites, plugins, and
  application users. It cannot operate inside a site. This account is pinned and can never be
  deleted or demoted, so there is always a way back in.
- **Default site administrator** — manages the seeded default site (a separate identity in
  the *site* realm). Unbound hosts resolve to this default site.

Submit the form; Runway writes the external `application.yml` (the superadmin pin, `chmod 600`)
and **restarts into normal mode**, after which the whole wizard layer is gone. The restart is a supervised
relaunch (the JVM exits with code 42): under systemd or the launcher `scripts/runway.sh` (detached mode) it comes
back by itself; a bare `java -jar` simply exits, so start it again. Then sign in:

- **Operator console** — [`/app/login`](http://localhost:8080/app/login) (application superadmin)
- **Site management** — [`/login`](http://localhost:8080/login) (default site administrator)

To confirm setup took: once it is back up, requesting `/` no longer redirects to `/setup`,
`/opt/runway-data/external/application.yml` exists with a `runway.security.app-master-id`
line, and both login pages authenticate their respective account.

## Versioning & release cycle

Runway follows [Semantic Versioning](https://semver.org/) with a `SNAPSHOT → beta → rc →
release` lifecycle. `1.0.0` is the first stable release, with the plugin contract frozen at SPI `1.0.0`.
From `1.0.0` a breaking change costs a MAJOR. What stays compatible within a MAJOR line is in [SPI-STABILITY.md](docs/SPI-STABILITY.md) and [CONFIGURATION-AND-DATA-STABILITY.md](docs/CONFIGURATION-AND-DATA-STABILITY.md).

## Documentation

The documentation delivered with Runway lives in **[`docs/`](docs/README.md)** — start at the
[documentation index](docs/README.md). It covers:

- **[Plugin Development](docs/PLUGIN-DEVELOPMENT.md)** — for plugin authors
- **[Administration](docs/ADMINISTRATION.md)** — for the operators who install and run the platform
- **[Site Manager & Designer Guide](docs/SITE-MANAGER-GUIDE.md)** — for the people who manage a site (and designers who edit pages)
- **[Operations](docs/OPERATIONS.md)** and a **[Glossary](docs/GLOSSARY.md)**
- **[SPI stability](docs/SPI-STABILITY.md)** and **[Configuration & data stability](docs/CONFIGURATION-AND-DATA-STABILITY.md)** — what stays compatible from `1.0.0`
- **[Settings reference](docs/SETTINGS-REFERENCE.md)** — every setting, its values and default, and where it is set
- **[Known limitations](docs/KNOWN-LIMITATIONS.md)** — what Runway does not do yet

## License

Copyright (C) 2026 Quest ICT SAS de CV.

Runway is licensed under the [GNU Affero General Public License, version 3](LICENSE), with an
[additional permission for plugins](LICENSE-EXCEPTION): a plugin that interacts with Runway only through the
published SPI (`runway-spi`) may carry any license, including a proprietary one. Runway itself stays under
the AGPL: if you run a modified Runway for others over a network, you must offer them its source.
