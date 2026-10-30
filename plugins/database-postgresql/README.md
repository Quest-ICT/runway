# Runway Database — PostgreSQL provider

Lets the [`database`](../database) plugin use **PostgreSQL 14 or later** (database encoding `UTF8`). It bundles the
pgjdbc driver and builds a configured, unpooled `DataSource`; every statement Runway runs is written in the database
plugin. Since 2.0.0 it also holds the server's settings: host, port, database, account, password and TLS,
on its own settings page (**Plugins → Database — PostgreSQL → Settings**, file `external/plugin-settings/database-postgresql.yml`).
With both providers installed, choose this one with the database plugin's `provider: postgresql` setting; with one
installed, it is used.

- **Plugin id:** `database-postgresql` (v`2.0.0`) — install folder `external/plugins/database-postgresql/`
- **Requires:** `database >= 2.0.0`, Runway SPI `1.2.0` or later (Runway 1.2.0)
- **Bundles:** pgjdbc `42.7.10` (BSD-2-Clause) in `lib/`; its license is in `THIRD-PARTY-NOTICES`

Setup, grants and settings: the kernel's ADMINISTRATION §11.1 — step by step, the settings file for a setup
without the console, upgrading from 1.x, and a table for when it does not connect. Details: [`docs/DATABASE-POSTGRESQL-PLUGIN.md`](docs/DATABASE-POSTGRESQL-PLUGIN.md),
[`CHANGELOG.md`](CHANGELOG.md).
