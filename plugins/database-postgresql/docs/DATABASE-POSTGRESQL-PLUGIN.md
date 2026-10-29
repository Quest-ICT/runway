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
