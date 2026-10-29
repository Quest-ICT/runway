# Runway Database — PostgreSQL provider

Lets the [`database`](../database) plugin use **PostgreSQL 14 or later** (database encoding `UTF8`). It bundles the
pgjdbc driver and builds a configured, unpooled `DataSource`; every statement Runway runs is written in the database
plugin. Choose it with the database plugin's `provider: postgresql` setting.

- **Plugin id:** `database-postgresql` (v`1.0.0`) — install folder `external/plugins/database-postgresql/`
- **Requires:** `database >= 1.0.0`, Runway SPI `1.1.0` or later
- **Bundles:** pgjdbc `42.7.10` (BSD-2-Clause) in `lib/`; its license is in `THIRD-PARTY-NOTICES`

Setup, grants and settings: the kernel's ADMINISTRATION §11.1. Details: [`docs/DATABASE-POSTGRESQL-PLUGIN.md`](docs/DATABASE-POSTGRESQL-PLUGIN.md),
[`CHANGELOG.md`](CHANGELOG.md).
