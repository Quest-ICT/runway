# The PostgreSQL provider

`PostgresProvider` builds a `PGSimpleDataSource` from the database plugin's checked `ConnectionSettings`:

| Property | Value |
|---|---|
| server, port, database | the checked host (an IPv6 literal in brackets), the port or 5432, the database |
| user, password | the account, as properties — never in the URL |
| `ApplicationName` | `runway` (visible in `pg_stat_activity`) |
| `connectTimeout`, `loginTimeout` | the connect timeout |
| `socketTimeout` | longer than any statement timeout the database plugin sets |
| `tcpKeepAlive` | on |
| TLS `DISABLE` | `sslmode=disable` |
| TLS `REQUIRE` | `sslmode=require` with `NonValidatingFactory`: encrypted, the server not checked, no client certificate |
| TLS `VERIFY_FULL` | `sslmode=verify-full`; with a CA certificate `LibPQFactory` and `sslrootcert` = the CA file, otherwise `DefaultJavaSSLFactory` (the JVM trust store). The host name is always checked. |

Nothing else is taken from settings: no socket factory, logger file or password callback. `channelBinding` stays at
the driver's default, `prefer`, so a server that offers SCRAM channel binding over TLS gets it.

The provider is one bean, `databasePostgresqlProvider`, collected by type. Tests: `PostgresProviderTest` (the
properties for each TLS policy, a real connection when `-Ddb.it.postgresql=host:port` and
`-Ddb.it.password-file=…` are set, the bean name, and the linkage scan). End to end:
`../database/scripts/verify-against-host.sh` with `PROVIDER=postgresql`.

## Its settings (since 2.0.0)

The server's settings are this plugin's own app-level settings (`runway.plugin.database-postgresql.*`, the console's **Plugins →
Database — PostgreSQL → Settings**): `host`, `port`, `database`, `username`, `password` (a `secret`, bound to the
others), `tls`, `caCertificate`. The provider hands them to the database plugin raw; the database
plugin checks every one before use, as before:

- **running** (`runningSettings()`): bound at startup into a `ProviderSettingsProps` bean
  (`databasePostgresqlSettings`), the password read in clear with `PluginSettings.appSecret("password")`;
- **saved** (`savedSettings()`): read with `PluginSettings.savedApp(key)` (SPI 1.2.0) from the settings file as it is
  now, for the database plugin's connection test before a restart applies them.

A `collation` written into this plugin's file by hand is never handed over: it is a MariaDB setting.
