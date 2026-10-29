# Runway Database

Lets Runway plugins keep their own data in **PostgreSQL** or **MariaDB**. Runway's own data — sites, pages, users,
settings — stays on files; this plugin is for plugins.

- **One schema per site** (a database per site on MariaDB), created the first time a plugin uses the database for that
  site, plus one installation-wide schema. Generations are never reused, so a site deleted and created again never
  sees the old data.
- **Per-plugin migrations** read only from the plugin's own jar, with **ownership checks**: every object a plugin
  creates carries its prefix. PostgreSQL runs a plugin's scripts in one transaction; MariaDB statement by statement,
  resuming after a failure.
- **Backups**: a site's full backup carries its database data in a portable format (PostgreSQL ↔ MariaDB); a restore
  builds a new generation and switches only when everything loaded.
- **A health screen** in the application console (**Settings → Database**) with connection tests and recovery
  actions; one line on each site's dashboard.
- **Runway never fails to start because of the database**: the plugin connects after startup, on its own thread.

The product comes from a provider plugin — [`database-postgresql`](../database-postgresql) or
[`database-mariadb`](../database-mariadb) — which brings the driver; every statement Runway runs is written here.

- **Plugin id:** `database` (v`1.0.0`) — install folder `external/plugins/database/`
- **Requires:** Runway with SPI `1.1.0` or later (`requiresSpi: ">=1.1.0"`) and one provider plugin
- **Bundles:** HikariCP `7.0.2` (Apache-2.0) in `lib/`; its license is in `THIRD-PARTY-NOTICES`
- **Publishes:** `mx.quest.database.spi` — the consumer interface (`DatabaseConsumer`, `Databases`, `PluginDatabase`,
  `Scope`, `Dialect`) and the provider interface (`DatabaseProvider`, `ConnectionSettings`)

## For operators

Installation, the server's grants, every setting, TLS, clusters, backups and recovery are in the kernel's
**ADMINISTRATION §11.1**.

## For plugin authors

Read the authoring guide's **§7.14 "Using the database"** ([`../docs/PLUGIN-AUTHORING-GUIDE.md`](../docs/PLUGIN-AUTHORING-GUIDE.md)),
then the worked example [`runway-demo-database`](../runway-demo-database). How the plugin works inside, and why, is in
[`docs/DATABASE-PLUGIN.md`](docs/DATABASE-PLUGIN.md).

## Build and verify

```bash
mvn -o install                         # unit tests; the real-server tests skip without servers
# against throwaway servers (podman run … postgres:17 / mariadb:11.4), both products:
mvn -o test -Ddb.it.postgresql=127.0.0.1:55432 -Ddb.it.mariadb=127.0.0.1:53306 -Ddb.it.password-file=/path/pw
# inside a real Runway, with both providers installed:
PROVIDER=postgresql DB_HOST=127.0.0.1 DB_PORT=55432 DB_PASSWORD_FILE=/path/pw scripts/verify-against-host.sh
```

The real-server tests expect an account `runway` with a database `runway`; on MariaDB the account needs
`ALL PRIVILEGES` on `` `it%`.* `` for the tests' namespaces (and on `` `hv%`.* `` for the verify script's).

See [`CHANGELOG.md`](CHANGELOG.md).
