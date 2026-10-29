# Runway Database — MariaDB provider

Lets the [`database`](../database) plugin use **MariaDB 10.11 or later** (MySQL is not supported). It bundles MariaDB
Connector/J and builds a configured, unpooled `DataSource`; every statement Runway runs is written in the database
plugin. Choose it with the database plugin's `provider: mariadb` setting.

- **Plugin id:** `database-mariadb` (v`1.0.0`) — install folder `external/plugins/database-mariadb/`
- **Requires:** `database >= 1.0.0`, Runway SPI `1.1.0` or later
- **Bundles:** MariaDB Connector/J `3.5.8` (LGPL-2.1), unmodified, in `lib/`. Its sources are published next to this
  plugin's zip in each release (`mariadb-java-client-3.5.8-sources.jar`); the license and how to replace the jar are
  in `THIRD-PARTY-NOTICES`.

Setup, grants and settings: the kernel's ADMINISTRATION §11.1. Details: [`docs/DATABASE-MARIADB-PLUGIN.md`](docs/DATABASE-MARIADB-PLUGIN.md),
[`CHANGELOG.md`](CHANGELOG.md).
