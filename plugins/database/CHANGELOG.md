# Changelog

All notable changes are recorded here. Versions follow [Semantic Versioning](https://semver.org/).

## [1.0.0] — 2026-10-29

First public release of `database`: plugins can keep their own data in PostgreSQL 14+ or MariaDB 10.11+, through a
provider plugin (`database-postgresql` or `database-mariadb`). One schema per site, per-plugin migrations with
recovery actions, per-site backup and restore (also between the two products), and a **Database** screen in the
application console; Runway's own data stays on files, and Runway starts whether or not the database is reachable.
Plugins use it through the consumer SPI `mx.quest.database.spi`. Requires SPI `1.1.0` (Runway `1.1.0` or later).
Licensed under the GNU AGPL v3.0 with an additional permission for plugins (`LICENSE`, `LICENSE-EXCEPTION`); bundles
HikariCP (Apache License 2.0).
