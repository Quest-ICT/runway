# Changelog

All notable changes are recorded here. Versions follow [Semantic Versioning](https://semver.org/).

## [2.0.0] — 2026-10-30

### Upgrade note

- Install it together with `database` 2.0.0, on Runway 1.2.0 or later, and move the PostgreSQL server's
  settings into this plugin as the `database` 2.0.0 *Upgrade note* says. On Runway 1.1.x, keep 1.0.0 of both.

### Changed

- **This plugin holds the PostgreSQL server's settings**: host, port, database, username, password, TLS and CA
  certificate, on its own settings page (**Plugins → Database — PostgreSQL → Settings**; file
  `external/plugin-settings/database-postgresql.yml`, under `runway.plugin.database-postgresql`). Until 2.0.0 they were the `database` plugin's;
  move them as its 2.0.0 *Upgrade note* says.
- Requires `database >= 2.0.0` and Runway SPI 1.2.0 (Runway 1.2.0), whose `PluginSettings.savedApp` hands the saved
  settings to the database plugin's connection test.

## [1.0.0] — 2026-10-29

First public release of `database-postgresql`: the PostgreSQL 14+ provider for the `database` plugin, with the
PostgreSQL JDBC driver (pgjdbc) 42.7.10 bundled. Requires SPI `1.1.0` (Runway `1.1.0` or later) and `database`
`1.0.0`. Licensed under the GNU AGPL v3.0 with an additional permission for plugins (`LICENSE`,
`LICENSE-EXCEPTION`); pgjdbc is under the BSD 2-Clause License.
