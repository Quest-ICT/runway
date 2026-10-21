# Changelog

All notable changes are recorded here. Versions follow [Semantic Versioning](https://semver.org/).

## [1.0.0] — 2026-10-21

The first public release of Runway: a multi-site web platform built as a small multi-tenant kernel, with every
domain feature delivered as a plugin.

- **Kernel**: sites with their own hosts, pages, templates, messages and static files; the operator console
  (application and per-site realms) with the setup wizard, the page editor, roles and permissions, per-site
  backups, platform mail and password recovery; optional in-app TLS termination; Prometheus metrics; an
  optional cluster mode. The essential `protection` plugin (rate limiting and login protection) is bundled.
- **Plugin contract**: SPI `1.0.0`, frozen for the `1.x` line — it changes only additively
  ([docs/SPI-STABILITY.md](docs/SPI-STABILITY.md)). Plugin authors build against the `runway-spi` and
  `runway-spi-testkit` jars attached to each release ([docs/PLUGIN-DEVELOPMENT.md](docs/PLUGIN-DEVELOPMENT.md)).
- **Public plugins** in `plugins/`, attached to this release: `runway-demo` 2.1.0 (the reference plugin, Apache-2.0),
  `search` 2.3.0, `search-lucene` 3.3.0 and `lucene-provider` 10.4.0.
- **Install**: `runway-1.0.0.bin` (self-extracting installer) or `runway-1.0.0-dist.zip`, verified against
  `SHA256SUMS` ([docs/ADMINISTRATION.md](docs/ADMINISTRATION.md) §3).
- **License**: GNU AGPL v3.0 with an additional permission for plugins (`LICENSE`, `LICENSE-EXCEPTION`);
  third-party components are listed in `THIRD-PARTY-NOTICES`.
