# Runway Database — MariaDB provider

Lets the [`database`](../database) plugin use **MariaDB 10.11 or later** (MySQL is not supported). It bundles MariaDB
Connector/J and builds a configured, unpooled `DataSource`; every statement Runway runs is written in the database
plugin. Since 2.0.0 it also holds the server's settings, collation included: host, port, database, account, password and TLS,
on its own settings page (**Plugins → Database — MariaDB → Settings**, file `external/plugin-settings/database-mariadb.yml`).
With both providers installed, choose this one with the database plugin's `provider: mariadb` setting; with one
installed, it is used.

- **Plugin id:** `database-mariadb` (v`2.0.0`) — install folder `external/plugins/database-mariadb/`
- **Requires:** `database >= 2.0.0`, Runway SPI `1.2.0` or later (Runway 1.2.0)
- **Bundles:** MariaDB Connector/J `3.5.8` (LGPL-2.1), unmodified, in `lib/`. Its sources are published next to this
  plugin's zip in each release (`mariadb-java-client-3.5.8-sources.jar`); the license and how to replace the jar are
  in `THIRD-PARTY-NOTICES`.

Setup, grants and settings: the kernel's ADMINISTRATION §11.1 — step by step, the settings file for a setup
without the console, upgrading from 1.x, and a table for when it does not connect. Details: [`docs/DATABASE-MARIADB-PLUGIN.md`](docs/DATABASE-MARIADB-PLUGIN.md),
[`CHANGELOG.md`](CHANGELOG.md).
