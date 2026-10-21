# Runway Documentation

This is Runway's **delivered documentation** — the self-contained set that ships with the
product. Together with the source code, it is everything you need to install, operate,
manage, use, and extend Runway through its plugin system. Each document explains what it
needs directly.

## Find your document

| Document | For | Covers |
|---|---|---|
| [`../README.md`](../README.md) | Anyone | What Runway is, and **quick install + first access** |
| [PLUGIN-DEVELOPMENT.md](PLUGIN-DEVELOPMENT.md) | Plugin authors | Writing a Runway plugin: descriptor, contribution seams, settings, permissions, packaging |
| [SPI-STABILITY.md](SPI-STABILITY.md) | Plugin authors, maintainers | What the extension API (`runway-spi`) promises the code compiled against it, and the rules by which it may evolve from `1.0.0` |
| [ADMINISTRATION.md](ADMINISTRATION.md) | **Application administrators** | Install, configure, run; install/manage plugins; create and manage sites and application users; protection, backups, monitoring, upgrades |
| [SITE-MANAGER-GUIDE.md](SITE-MANAGER-GUIDE.md) | **Site managers & limited users (e.g. designers)** | Configure a site and its plugins; create users; roles & permissions; create/edit pages; the page-authoring contract for designers |
| [OPERATIONS.md](OPERATIONS.md) | Operators | Day-2 operations: health checks, troubleshooting, tuning, backup/restore, incident recovery |
| [GLOSSARY.md](GLOSSARY.md) | Everyone | The shared vocabulary used across these documents |
| [SETTINGS-REFERENCE.md](SETTINGS-REFERENCE.md) | Operators | Every setting: what it does, its values and default, and where it is set — which console screen, or the server only and why (generated) |
| [CONFIGURATION-AND-DATA-STABILITY.md](CONFIGURATION-AND-DATA-STABILITY.md) | Operators, plugin authors, monitoring | What stays compatible from `1.0.0` outside the Java API: every `runway.*` key, the data folder file by file, the backup format, `plugin.yml`, public paths, metric and logger names — and what is **not** covered |
| [KNOWN-LIMITATIONS.md](KNOWN-LIMITATIONS.md) | Everyone | What the code does **not** do yet (or does partially), and what is decided to come after `1.0.0` — read before evaluating a release |

The repository root also carries [`../CHANGELOG.md`](../CHANGELOG.md), and
[`../monitoring/README.md`](../monitoring/README.md) describes the bundled Prometheus and Grafana
assets: exposing `/actuator/prometheus` with a scrape token, the alert rules, the dashboard, and
the metrics they use.

## Document conventions

- **Self-contained.** Each document explains Runway directly. Cross-links point only within
  this delivered set.
- **Status.** Runway is built kernel-first, and each change ships with its documentation. Every section
  covering a built feature is written; the delivered set no longer carries `_Pending_` placeholders.
  [KNOWN-LIMITATIONS.md](KNOWN-LIMITATIONS.md) is the honest, current list of what Runway does not yet do.
