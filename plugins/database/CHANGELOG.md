# Changelog

All notable changes are recorded here. Versions follow [Semantic Versioning](https://semver.org/).

## [2.0.0] — 2026-10-30

The server's settings move to the provider plugins, and the connection tests move to this plugin's settings page.
**A breaking change for the settings**: read the *Upgrade note* first.

### Upgrade note

- Install `database`, and the provider you use, **2.0.0 together**, on Runway **1.2.0** or later (SPI 1.2.0). A 1.x
  provider with database 2.0.0 gives *No provider: the database-postgresql plugin is older than 2.0.0*.
- Move the server's keys — `host`, `port`, `database`, `username`, `password`, `tls`, `caCertificate`, and on
  MariaDB `collation` — from `external/plugin-settings/database.yml` (under `runway.plugin.database`) to the
  provider's file, `external/plugin-settings/database-postgresql.yml` under `runway.plugin.database-postgresql` (or
  `database-mariadb`). An encrypted password (`ENC(...)`) moves as it is. Keep `provider`, `namespace` and the limits
  in `database.yml`. Or retype the settings on the provider's settings page and delete the old keys. Move them
  **before** saving this plugin's settings form: a save keeps only the settings the form has, so it removes the old
  keys, encrypted password included.
- Until they are moved, the status is *Not configured: the server settings moved to the database-postgresql plugin
  …*, naming the keys to move; Runway never connects with the old keys.
- Then test them (below) and restart.
- On Runway 1.1.x, keep `database` 1.0.0 and its provider 1.0.0.

### Changed

- **The server's settings are the provider's.** Host, port, database, account, password, TLS and CA certificate are
  set on the provider plugin's own settings page; MariaDB's collation is on MariaDB's page only. This plugin keeps
  the provider choice, the namespace and the limits (pool size, statement time limit, backup budget).
- **The `provider` setting defaults to `auto`**: with one provider plugin installed, it is used; a choice is needed
  only when both are installed.
- **The connection tests are below this plugin's settings form** (**Plugins → Database → Settings**), no longer on
  the **Database** screen, which now links to them. They test the running connection and **each installed
  provider's saved settings**, the ones the next restart would apply, and take no typed values. 1.0.0's test of
  typed values is removed: a form with a username and a password box is one a browser fills in with the operator's
  own saved console login, which a test would then send to whatever server the form named.
- The password is bound to host, port, database, username, TLS and CA certificate (no longer to the provider, which
  now has a password of its own).

### Added

- Provider SPI: `ProviderSettings` (a provider's server settings, raw), `ProviderSettingsProps` (to bind them), and
  `DatabaseProvider.runningSettings()` / `savedSettings()` (`default` methods; empty means a provider older than
  2.0.0).

### Fixed

- On PostgreSQL, asking for TLS from a server that has TLS off was reported as a refused username or password; it is
  now reported as a TLS failure.

## [1.0.0] — 2026-10-29

First public release of `database`: plugins can keep their own data in PostgreSQL 14+ or MariaDB 10.11+, through a
provider plugin (`database-postgresql` or `database-mariadb`). One schema per site, per-plugin migrations with
recovery actions, per-site backup and restore (also between the two products), and a **Database** screen in the
application console; Runway's own data stays on files, and Runway starts whether or not the database is reachable.
Plugins use it through the consumer SPI `mx.quest.database.spi`. Requires SPI `1.1.0` (Runway `1.1.0` or later).
Licensed under the GNU AGPL v3.0 with an additional permission for plugins (`LICENSE`, `LICENSE-EXCEPTION`); bundles
HikariCP (Apache License 2.0).
