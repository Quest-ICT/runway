# The database plugin — how it works and why

> **Audience:** whoever maintains the `database` plugin or a provider, and plugin authors who want to know what
> happens behind `Scope.transaction`. Operators want the kernel's ADMINISTRATION §11.1; plugin authors start with the
> authoring guide's §7.14.

## 1. What it is for, and what it is not

Runway keeps its own data on files: a site is a folder, a backup is a zip of it, and nothing needs a database to
run. Some plugins are better served by one — many small records, queries, concurrent writers. This plugin gives them
PostgreSQL or MariaDB **without** making Runway depend on it:

- one database server per installation, chosen in settings;
- one schema per site, so a site's data is separate, travels in its backups and disappears with it;
- each plugin's objects carry its prefix, so plugins sharing a schema cannot collide;
- Runway starts, serves and administers sites whether or not the database is there.

Not covered: Runway's own data (it stays on files); a database per plugin; read replicas or several servers;
a plugin choosing its own server. Plugins are trusted code in the same JVM, as everywhere in Runway: the
separation between sites is enforced by this plugin's API, not by database permissions, because one account
reaches every schema.

## 2. The parts

| Part | Where | Job |
|---|---|---|
| Settings | `DatabaseProps`, `ProviderChoice` → `DatabaseConfig` | This plugin's own settings (provider choice, namespace, limits) bound from `runway.plugin.database.*`; `ProviderChoice` picks the provider (`auto`: the one installed) and takes the server's settings from it (`DatabaseProvider.runningSettings()`, since 2.0.0); `DatabaseConfig` checks every value before any use. 1.x server keys still in `database.yml` are bound only to report that they moved. |
| Runtime | `DatabaseRuntime` | Connects after startup on the plugin's executor, with back-off; checks the server; owns the pools. |
| Engines | `PostgresEngine`, `MariaDbEngine` | **All** the SQL Runway runs, per product. |
| Registry | `Registry` | Runway's own `rw_` tables in `<ns>_app`. |
| Site schemas | `SiteSchemas` | Which schema holds a site's data; allocation, retirement, removal, the sweep. |
| Locks | `Locks` | Per-site and install-wide locks across nodes. |
| Migrations | `Migrator` | Brings a plugin's tables in a schema to the shipped version. |
| Scopes | `ScopeImpl`, `PluginDatabaseImpl`, `DatabasesImpl` | What consumers call. |
| Backups | `DatabaseBackup`, `Archive`, `Portable` | Export, staged restore, parked data. |
| Console | `DatabaseConsoleController`, `DatabaseScreens`, `ConnectionTester` | The health screen, the dashboard line, and the connection tests (panel `templates/admin-app.html`, below this plugin's settings form): the running connection, and each installed provider's **saved** settings (`DatabaseProvider.savedSettings()`, read by the provider through the kernel's `PluginSettings.savedApp`). |

**Providers bring the driver, not the SQL.** A provider builds an unpooled `DataSource` from its own driver
classes with the checked settings and pinned properties; nothing goes through `DriverManager`. Every statement —
schema creation, locks, ownership checks, migrations, backup, restore — is in this plugin's engines, so the checks
that keep sites and plugins apart are written and reviewed in one place and a provider cannot weaken them. The
price: a new product needs a new engine here, not only a provider.

## 3. Connections

Three ways to a connection, for three jobs:

- **The main pool** (`poolMax`) lends connections to consumers: a 2-second borrow timeout so an outage fails fast,
  the consumer statement timeout, and a per-site in-flight cap of half the pool so one busy site cannot starve the
  others. A borrow that times out because every connection is in use fails that call only ("too many database calls
  in flight"); only a failure to reach the server (SQLState class `08`, a connection-class exception) marks an
  outage, which is re-checked after 2 seconds and then by the minute's heartbeat. The heartbeat opens a connection
  of its own, so a setup pool that is only busy (a long restore, a migration) is never taken for an outage.
- **The setup pool** (two connections, 30-minute timeout) runs schema creation, registry writes, migrations, backup
  and restore — never on a consumer's connection, because MariaDB's DDL commits whatever transaction it is in.
- **Lock connections** are opened unpooled for each database lock and closed after it, so a lock is never held on a
  pooled connection and lock holders cannot exhaust a pool.

Every new connection is pinned: UTC time zone; on MariaDB a strict `sql_mode`, `InnoDB` by default,
`explicit_defaults_for_timestamp = 1` (a server can still be configured with it off, and then a table's first
`TIMESTAMP NOT NULL` column silently gains `DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP`, so a `created_at` would change on every
update), and a statement and lock-wait limit. Every borrow **binds** the connection in the same round trip that checks the registry still maps
the site to that schema (PostgreSQL: `set_config('search_path', …)` selected from `rw_sites`; MariaDB: the check, then
an explicit `USE`, never `setCatalog`, which the driver may skip). A connection whose bind or reset fails is thrown
away. On return PostgreSQL runs `DISCARD TEMP` (never `DISCARD ALL`, which would drop the driver's prepared
statements).

The server is checked before anything is created: PostgreSQL 14+ with a `UTF8` database; MariaDB 10.11+ and not MySQL (a
session cannot set `explicit_defaults_for_timestamp` before 10.10, so an older server is refused by its version rather
than failing every connection).
A failed check counts as unreachable, with the reason on the health screen.

## 4. Names and the life of a site's schema

Every name starts with the **namespace** (`rw`): `<ns>_app` and `<ns>_s_<base>_<gen>`, where `<base>` is the site id
lower-cased with everything else mapped to `_`, cut to fit 63/64 bytes, and `<gen>` comes from a sequence. The
mapping from site ids loses information (`Acme`/`acme`, `a.b`/`a_b`), so the generation — never reused — is what makes
names unique. Every name is checked against `^<ns>_(app|s_[a-z0-9_]+)$` and the length limit before any DDL, and
quoted. The namespace has no underscore, so one installation's names are never the start of another's.

**The incarnation token.** Allocation records the mapping with a random token, then writes the same token, and the
site id it was issued for, to the site's own folder (`schema.yml`, declared personal so transfer copies leave it out);
every resolution compares the two. What the folder says decides:

- **the same token**: the mapping is the site's;
- **a token issued for another site id** (copied in with another site's full backup), or the **`fresh: new site`**
  marker: the data the registry has for this id is not this site's; that mapping is retired and a new generation
  allocated. A new site gets the marker when it is created while the database is unreachable; with the database
  reachable, creating a site retires at once any data an earlier site with the same id left behind;
- **another token of this site, or the `adopt: live` marker**: a restore put back a folder from another moment of the
  same site (a full backup carries the token of the generation live when it was taken). The registry is the
  authority: the live mapping's token is written back and its data stays;
- **no token file** while the registry has live data for the id: either a restore whose first step has not run yet,
  or a site deleted and created again while the plugin was off. Neither binding (it could be an earlier site's data)
  nor a fresh start (it could be a restored site's live data) is safe, so the site's data is "being prepared" until
  the restore writes the token back, or the sweep finds the folder still without one on two passes in a row and
  retires the data;
- **a folder that cannot be read** (permissions, a stale network mount): nothing is decided or changed. The call fails
  as "database unavailable", the sweep skips the site, and a backup of it fails rather than record "no data".

The registry is written first and the token after, so a failure between the two leaves a folder that does not name
the new mapping: allocation retires it on the next use (and a late call for a deleted site leaves only that, which the
sweep drops); a restore's switch is adopted on the next use.

**Registry tables** (`<ns>_app`, identifier columns binary so ids never match by case): `rw_sites` (the live mapping),
`rw_schemas` (every generation and its state: `creating`, `live`, `staging`, `retired`, `dropping`), `rw_plugins`
(prefixes), `rw_nodes` (heartbeats; a node not seen for a day is removed at the next heartbeat), `rw_issues` (migration problems, visible to every node), `rw_install`.

**Locks.** Allocation, restore and removal take the per-site lock: an in-JVM lock, then `pg_advisory_lock` /
`GET_LOCK` on a lock connection, both with a bounded wait; a request that cannot get it fails fast with
"site data is being prepared" while holding no pooled connection.

**Removal.** The site-deleted event only enqueues, and only on the node that deleted the site (the database is
shared). The job re-reads the mapping under the lock, retires it and drops exactly that schema. The **reconcile
sweep** (after connecting and every 15 minutes) retires mappings whose site is gone (their data is dropped after
the grace period, never at once: a site that reads as gone may only be unreadable for a moment), whose folder names
another site or is marked new, or whose folder has had no token on two passes in a row; it re-adopts those whose folder
names another generation of the same site; it drops retired generations once they have been retired for 10
minutes, staging generations no restore is building, and creations a crash interrupted. Every retire or drop is
decided again under the site lock from fresh reads, so a restore or an allocation that finishes while the sweep waits
for the lock is never undone, and a schema a site maps to is never dropped. A site folder that cannot be read, or a
site the kernel cannot read, is skipped and the sweep goes on with the others. A schema in the namespace that the registry never recorded is reported, never dropped.

## 5. Migrations

Scripts come only from the jar the consumer's `DatabaseConsumer` bean was loaded from (its `CodeSource`, whose folder
must be named after the plugin id): every plugin shares one class loader, so "the first resource with that name"
could be another plugin's. Each folder has an `index`; the plugin never lists a folder through the class loader.

The checksum is the SHA-256 of the script with the BOM stripped, line endings normalized and trailing blanks trimmed;
comments stay (MariaDB executes `/*! … */`). Statements are split on a line holding only `-- runway:next`; a statement
that would move the connection is refused before anything runs, and after every statement the binding is checked
again.

- **PostgreSQL**: all of a plugin's pending scripts in one transaction under `pg_advisory_xact_lock`; the ownership
  check compares the schema's objects before and after; a failure or a foreign object rolls everything back. Every
  script of the rolled-back batch gets a `failed` row (the failing one with its statement), so Retry runs them all
  again and Mark applied covers them all. A shipped script below the highest applied one that has no row at all is
  refused as a failure, never skipped.
- **MariaDB**: per statement under `GET_LOCK`. The script's row is written `failed` first and `last_ok_statement`
  advances after each statement; the ownership check runs after **each** statement and drops exactly what that
  statement created outside the prefix (or a table not on InnoDB). Retry resumes after the last good statement.

The ownership check covers, on PostgreSQL, relations (so every index, including those constraints create),
functions, procedures and types other than row and array types, minus extension members; on MariaDB tables, views,
sequences, triggers, routines, events and foreign-key names. Index and CHECK names on MariaDB and trigger names on
PostgreSQL are scoped to their table and exempt.

**The lazy check is the authority.** The first call per (schema, plugin) in a JVM runs it, single-flight, waiting at
most 10 seconds (then "being prepared", while the migration continues). The startup sweep only warms it up.
**Failure is per (schema, plugin)**: a failed script, a changed checksum or a schema newer than the shipped scripts
stops that plugin in that schema with a `ScopeNotReadyException`, recorded in `rw_issues` for the health screen,
until an operator acts; the outcome is remembered for 30 seconds so a failing plugin does not hammer the server.

**Prefixes** are recorded in `rw_plugins` at a plugin's first migration. A prefix equal to, starting or started by a
recorded one is refused (`a-b`/`a_b`, `Blog`/`blog`), and `rw_` is reserved.

## 6. Scopes

`transaction` and `read` borrow one connection per outer call. A call on the same scope from inside the work joins
(same connection, no commit of its own, a throw rolls back the outer call); `transaction` inside `read` throws; a call
on another scope throws, so a consumer never holds two connections. There is no Spring transaction manager over a
per-site binding; the helper gives the same one-connection guarantee explicitly, which is what makes `lastValue`
after an insert reliable. `read` sets read-only and `REPEATABLE READ`; MariaDB also starts `START TRANSACTION WITH
CONSISTENT SNAPSHOT, READ ONLY` explicitly, because its driver ignores `setReadOnly` on a single host.

## 7. Backups

**Export** (only in full backups — the kernel gives transfer copies no participants): a site without a token writes
`state.json {"schema":"none"}`. Otherwise one read-only snapshot reads the tables (streamed with a fetch size), the
applied versions and, last, the sequences (as the next value to issue and the increment), into
`tables/<t>/part-NNNNN.jsonl.gz` (at most 4 MiB compressed, under the kernel's per-file write cap so the same parts
can be parked in the site folder), one `index.json` per table with row counts and SHA-256s, and `manifest.json`
**last** — a backup cut short has no manifest and never restores. Unreachable database with a token present: the
backup fails. The parts are read into a private spool folder under the plugin's work folder and handed to the kernel
only after the read transaction has ended: a download streams to the browser, and a slow browser must not hold a
setup-pool connection and an open snapshot. The spool holds at most the backup budget and is deleted afterwards.

**The format is typed** (`Portable`): `BIGINT, INTEGER, SMALLINT, DECIMAL(p,s), DOUBLE, BOOLEAN, VARCHAR(n), TEXT,
DATE, TIME, TIMESTAMP, TIMESTAMPTZ, BYTES, JSON`. Each column is read with the getter its type names and restored by
the **target** column's type; types of the same family restore into each other, which is what lets a PostgreSQL backup
load into MariaDB.

**Restore** treats the archive as untrusted (a site administrator can upload one). The kernel has already put the
archive's site folder in place when the plugin's turn comes, token included (or none, for a backup taken before the
site used the database). So the first step, under the site lock, makes the folder name the **live** generation again:
whatever happens next — success, failure, an archive without database data — the site keeps its data until a restore
actually replaces it. With the database unreachable, a folder left without a token gets the `adopt: live` marker,
which the next resolution honours. An archive whose state is `none` (the site had no database data) retires the live
data, as before (with the database unreachable, the folder is marked `fresh: new site` instead). A restore does not
make other nodes drop their cached mapping: every borrow re-checks it against the registry, and a restore that
replaces the data changes the registry. If the switch to the restored data reports an error after it committed, the
restore counts as done; a staging generation is dropped only while no site maps to it.

1. The whole archive is checked first — names, counts, checksums, line lengths, the budget, and text with NUL on
   PostgreSQL.
2. Per plugin: installed at or above the backup's version → restored; installed but older → the restore is refused;
   absent → its data is parked in the site folder and loaded, exactly like a restore, when the plugin first migrates
   at the data's version or later (an older plugin is refused as `NEWER_THAN_INSTALLED`, shown on the Database
   screen, and the data waits). The parked manifest is removed only once the data is in, so a load that fails part
   way runs again on the next use. Parked data that cannot be read, or that is older than the plugin's tables already
   in the schema, refuses the plugin on that site (`PLUGIN_REFUSED`, a "PARKED" problem on the screen); it is never
   taken for "no data" and the tables are never started empty over it.
3. Under the site lock a **new generation** is created; each plugin is migrated to the backup's version, its tables
   emptied (seed rows), every archived table and column matched **by lookup** against the new generation's catalog
   (no archive string ever becomes an identifier), loaded with user triggers and foreign keys out of the way
   (PostgreSQL: `DISABLE TRIGGER USER` and dropped foreign keys re-added afterwards, which re-checks them; MariaDB:
   triggers captured and recreated, `FOREIGN_KEY_CHECKS = 0`), given its sequences, and migrated forward.
4. One transaction switches `rw_sites` to the new generation with a fresh token, then the token is written to the
   site folder; the old generation is retired and dropped by the sweep.
5. On any failure the new generation is dropped, the old one stays live, and the restore throws; the failure is kept
   for the health screen and the site's dashboard, because the kernel reports the site files as restored either way.

## 8. Security notes

- Host, port and database are checked against strict grammars (one host, no lists, options or credentials); the
  URL is built from them and nothing else; the account goes in as properties. MariaDB pins `allowLocalInfile=false`,
  `allowMultiQueries=false`, `permitRedirect=false`, `allowPublicKeyRetrieval=false`.
- TLS defaults to `verify-full`. The CA certificate is written to an owner-only file in the plugin's folder.
- The password is bound (`secretBoundTo`, on the provider's page) to everything that decides where it goes. The
  connection test takes no typed values, only what is saved: a form with a username and a password box is one a
  browser fills with the operator's own console login, which a typed test (1.0.0's) would then send to whatever host
  was typed.
- No log line carries the password or a credentialed URL (checked by the tests and the verify script).
- The health screen needs `CONFIGURE_PLATFORM`; its recovery actions are logged with the operator's name.

## 9. Known gaps

- A transfer copy carries no database data; a kernel hook telling participants a backup's purpose would let plugins
  mark which tables travel.
- The backup budget exists because the kernel holds a participant's restored entries in memory.
- A widget cannot read the database and stay cache-correct; it renders a shell and loads rows from a `no-store`
  endpoint.
- `<ns>_app` has no export of its own.
- MariaDB keeps a lent connection's temporary tables; consumers drop them in `finally`.
