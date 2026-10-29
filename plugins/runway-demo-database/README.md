# Runway demo — database

The worked example of a plugin that keeps its data in the database: a **per-site guestbook** on PostgreSQL or
MariaDB, through the [`database`](../database) plugin. It uses each part of the database SPI once, the way a real
plugin would.

- **Plugin id:** `runway-demo-database` (v`1.0.0`) — install folder `external/plugins/runway-demo-database/`
- **Requires:** `database >= 1.0.0` (without it the plugin shows as **Blocked**), Runway SPI `1.1.0` or later
- **Links:** only the Runway SPI and the database SPI (`mx.quest.database.spi`)

## Try it

1. Install `database`, a provider and this plugin; set the database up (ADMINISTRATION §11.1); restart.
2. Put the widget on a page: `<div id="guestbook" data-widget="guestbook/main"></div>`.
3. Sign it as a visitor; manage the entries under **Guestbook** in the site console (permission
   `runway-demo-database|manage`).

Per-site settings: `enabled` and `maxEntries` (10–10000, default 500: the oldest entries beyond it are deleted).

Read [`docs/DEMO-DATABASE-PLUGIN.md`](docs/DEMO-DATABASE-PLUGIN.md) for the walkthrough and
[`../docs/PLUGIN-AUTHORING-GUIDE.md`](../docs/PLUGIN-AUTHORING-GUIDE.md) §7.14 for the rules it follows.

## Build and verify

```bash
mvn -o install     # tests; the store's real-server tests run with -Ddb.it.postgresql / -Ddb.it.mariadb / -Ddb.it.password-file
PROVIDER=mariadb DB_HOST=127.0.0.1 DB_PORT=53306 DB_PASSWORD_FILE=/path/pw scripts/verify-against-host.sh
```

See [`CHANGELOG.md`](CHANGELOG.md).

## License

Apache License 2.0 (`LICENSE`, `NOTICE`), copyright Quest ICT SAS de CV. You are free to copy this example as the
start of your own plugin and to release yours under any terms. One exception: `GuestbookStoreRealServerTest` builds
a real database layer from the `database` plugin's internal classes (`mx.quest.database.internal`) and the two
providers, which are AGPL-3.0 and outside the plugin permission. A copy released under other terms should delete
that test and its two test-scope provider dependencies, and test against a running Runway instead
(`scripts/verify-against-host.sh` does).
