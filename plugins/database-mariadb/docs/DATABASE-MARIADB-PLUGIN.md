# The MariaDB provider

`MariaDbProvider` builds a `MariaDbDataSource` whose URL is `jdbc:mariadb://host:port/database` followed only by
properties it pins; the account goes in through the data source's setters, never the URL.

| Property | Value | Why |
|---|---|---|
| `allowLocalInfile` | `false` | the server cannot ask the client for a local file |
| `allowMultiQueries` | `false` | one statement per call |
| `permitRedirect` | `false` | the server cannot send the client to another host |
| `allowPublicKeyRetrieval` | `false` | no key exchange a man in the middle could abuse |
| `connectionTimeZone` | `UTC` | matches the session time zone the database plugin pins |
| `preserveInstants` | not set (off) | a `LocalDateTime` is read and written as it is, whatever the JVM's zone: the database plugin keeps instants as UTC `LocalDateTime` values, and with `preserveInstants` on every value read would be moved into the JVM's zone |
| `tcpKeepAlive` | `true` | |
| `connectTimeout`, `socketTimeout` | from the settings | the socket timeout is longer than any statement timeout |
| TLS `DISABLE` / `REQUIRE` / `VERIFY_FULL` | `sslMode=disable` / `trust` / `verify-full` | `verify-full` checks the chain and the host name, against `serverSslCert` (the CA file) when given, else the JVM trust store |

**Times in plugin code.** The session is UTC, so bind and read an instant as a UTC `LocalDateTime`
(`LocalDateTime.ofInstant(at, ZoneOffset.UTC)`, `rs.getObject(i, LocalDateTime.class)`) or as a `Timestamp` with a
UTC `Calendar` (`setTimestamp(i, ts, utc)`, `getTimestamp(i, utc)`). A bare `setTimestamp`/`getTimestamp`, or an
`OffsetDateTime`, goes through the JVM's own zone and is off by its offset when the JVM is not on UTC.

Values go into the URL as they are: Connector/J decodes nothing, so an encoded value would name another file or
database. A value containing `&`, `?` or `=` (which its parser splits on) is refused. MySQL's `autoDeserialize` has no equivalent in Connector/J 3, so there is nothing to pin.

**Licence.** Connector/J is LGPL-2.1. It is shipped unmodified as its own jar in `lib/`, so it can be replaced with
another build of the same version; the release publishes its sources jar (`mariadb-java-client-3.5.8-sources.jar`)
next to this plugin's zip. The build copies it into `target/release-assets/`, whose files the release scripts attach
beside the zip, and the zip carries the LGPL-2.1 text in `THIRD-PARTY-NOTICES`.

The provider is one bean, `databaseMariadbProvider`, collected by type. Tests: `MariaDbProviderTest` (the URL for
each TLS policy and that it never carries the account, a real connection when `-Ddb.it.mariadb=host:port` and
`-Ddb.it.password-file=…` are set, the bean name, and the linkage scan). End to end:
`../database/scripts/verify-against-host.sh` with `PROVIDER=mariadb`.

## Its settings (since 2.0.0)

The server's settings are this plugin's own app-level settings (`runway.plugin.database-mariadb.*`, the console's **Plugins →
Database — MariaDB → Settings**): `host`, `port`, `database`, `username`, `password` (a `secret`, bound to the
others), `tls`, `caCertificate`, and `collation`. The provider hands them to the database plugin raw; the database
plugin checks every one before use, as before:

- **running** (`runningSettings()`): bound at startup into a `ProviderSettingsProps` bean
  (`databaseMariadbSettings`), the password read in clear with `PluginSettings.appSecret("password")`;
- **saved** (`savedSettings()`): read with `PluginSettings.savedApp(key)` (SPI 1.2.0) from the settings file as it is
  now, for the database plugin's connection test before a restart applies them.
