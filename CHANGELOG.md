# Changelog

All notable changes are recorded here. Versions follow [Semantic Versioning](https://semver.org/).

## [1.1.0] — 2026-10-29

_A minor release: plugins can keep their data in PostgreSQL or MariaDB through the new database plugins, platform
mail can sign in with OAuth2 (Microsoft 365), the client address is read from the right of `X-Forwarded-For`, slow
request bodies are refused, and password-reset links go only to an address an administrator set. The plugin contract
gains one method (SPI `1.1.0`); every plugin that runs on 1.0.1 loads unchanged._

### Upgrading — read this first

**From `1.0.1` or `1.0.0`: a kernel upgrade, no flag. Behind a reverse proxy, check it first (step 1).** Coming from
an older release: read each newer release's *Upgrading* first, oldest first. Paths are the installer defaults
(`/opt/runway`, `/opt/runway-data`).

**1. Behind a reverse proxy: before you upgrade.** This release reads the client address from the right of
`X-Forwarded-For` and no longer reads the `Forwarded` header (see *Security* and *Changed*). Without a proxy, when
visitors reach Runway directly, skip this step.

- **List every proxy of yours in `runway.proxy.trusted-cidrs`:** the reverse proxy and anything in front of it, such
  as a CDN, a load balancer or a second proxy (for a CDN, its published address ranges). Runway now walks
  `X-Forwarded-For` from the right and stops at the first address that is not listed. An outer hop that is missing
  from the list becomes every visitor's address, so all visitors share one set of login lockouts and rate limits.
  Until now the leftmost entry was used, so a missing outer hop went unnoticed.
- **A proxy that sends only `Forwarded`** must be set to send `X-Forwarded-For`, `X-Forwarded-Proto` and
  `X-Forwarded-Host` instead. Otherwise every visitor appears as the proxy, and the scheme and host as the proxy's
  connection.
- **Recommended for every release:** the proxy sets `X-Forwarded-Proto` and `X-Forwarded-Host` and deletes
  `X-Forwarded-Port`, `X-Forwarded-Prefix` and `X-Forwarded-Ssl`. `docs/ADMINISTRATION.md` §7 has the HAProxy and
  nginx lines.

**2. Password-reset links.** A reset link now points at a site's own address and, on the application console, at a
host listed in `runway.console.hosts` or else at the default site's address (see *Security*). A site's address is
its base URL, or else `https://` and its **first** host name, with no port:

- **A site served over plain `http` or on a port other than 443** must have its **base URL** set (**Sites → Edit
  hosts**), or its reset links point at `https://` and port 443. The same goes for the default site when
  `runway.console.hosts` is not set, for the console's links.
- Where there is no address, the login page no longer offers **Forgot your password?**: on a site with no host name,
  or whose first host is `localhost` (as the setup wizard leaves the default site) and that has no base URL; on the
  application console, when `runway.console.hosts` is not set and the default site has no such address. To keep it,
  set `runway.console.hosts`, or give the default site its public host name first, or a base URL.
- Without `runway.console.hosts`, the default site's address decides where the platform accounts' reset links go,
  so its hosts and base URL can now be edited only by the application master or a wildcard administrator, even where
  `MANAGE_SITE_HOSTS` is delegated, and a restore made from a site's own console keeps the site's hosts and base URL
  instead of the backup's.

See `docs/ADMINISTRATION.md` §8.3.

**3. Back up.** Take each site's backup (**Backups**) and copy `/opt/runway-data/external/` somewhere **outside**
`external/plugins/`.

**4. Upgrade the kernel:**

```bash
chmod +x runway-1.1.0.bin
sudo ./runway-1.1.0.bin -y
```

The essential plugin moves with the kernel to `1.1.0` (its descriptor changes in version only, and its jar is
rebuilt), so the installer refreshes it; expect these lines, once:

```
refreshing bundled plugin 'protection' from this release (it moves with the kernel; files you edited are backed up)
plugin 'protection': plugin.yml replaced (the previous one kept as plugin.yml.bak, for a rollback)
plugin 'protection': runway-plugin-protection.jar replaced (the previous one kept as runway-plugin-protection.jar.bak, for a rollback)
keeping the bundled plugins in the release: /opt/runway-1.1.0/seed/ (the repair copy — Administration §12)
```

**5. Check.**
- **About Runway** shows `1.1.0`.
- On **Plugins**, every row is *Loaded* and none *Blocked*; protection shows `1.1.0`.
- The start log says `plugins: N registered, 0 blocked`.
- Behind a proxy, **About Runway → This request** shows your own public address as the client address, and the
  connection as a trusted proxy.

**What changes without any action.** None of these needs a setting changed, unless it affects you:
- **Slow request bodies are refused.** A request body that arrives at fewer than 4096 bytes per second is answered
  `408` (`runway.http.min-request-data-rate`; Settings → Server settings → Request limits; `0` turns the check off).
  Lower it only if your users upload large files over very slow links.
- **Sign-in lines in a site's audit log now carry the address in full** (`ip=…`). A site's setting to anonymize
  client addresses applies to its access log only.
- **Platform mail can sign in with OAuth2 (Microsoft 365).** This is optional; password accounts work as before.
- **Plugins.** Every plugin that runs on 1.0.1 loads unchanged: SPI `1.1.0` only adds a method. This release also
  carries:
  - the database plugins (`database`, `database-postgresql`, `database-mariadb`) and the `runway-demo-database`
    example, all new and optional; see `docs/ADMINISTRATION.md` §11.1;
  - demo `2.1.1`, whose feedback form now asks the site's CAPTCHA.

**6. Rollback**, if needed: `sudo bash runway-1.0.1.bin -y` puts 1.0.1 back, its protection files included.
- The database plugins need 1.1.0 and are *Blocked* on 1.0.1; their data stays in the database.
- 1.0.1 has no OAuth2 sign-in, so an OAuth2 mail account stops sending there. Anything 1.0.1 saves to the platform
  settings also drops the OAuth2 fields.

### Added

- **SPI 1.1.0: `PluginSettings.appSecret(key)`.** A plugin can now read its own `secret`-typed app-level setting
  (`settings:` in `plugin.yml`) in clear: a credential that belongs to the whole installation, such as a database
  password. Until now only per-site secrets could be read in clear; an app-level one reached the plugin only in its
  encrypted `ENC(...)` form. The value is the one the running application started with, so a change saved on the
  console applies after a restart, like every app-level setting. A plugin that uses it declares
  `requiresSpi: ">=1.1.0"`. See `docs/SPI-STABILITY.md`.
- **Database plugins, published with this release:** `database`, `database-postgresql` and `database-mariadb`
  `1.0.0`, and the example `runway-demo-database` `1.0.0`. Plugins can keep their own data in PostgreSQL 14+ or
  MariaDB 10.11+: one schema per site, per-plugin migrations, per-site backup and restore (also between the two
  products), and a **Database** screen in the application console. Runway's own data stays on files, and Runway
  starts whether or not the database is reachable. See `docs/ADMINISTRATION.md` §11.1.
- **Testkit: `FakePluginSettings.appSecret(key, clearValue)`**, so a plugin's unit tests can hand it an app-level
  secret the way the running application would.
- **About Runway → This request.** The request that loaded the page as Runway saw it: the connection it came from and
  whether that is a trusted proxy, the `X-Forwarded-For` list as received, the client address Runway used, and the
  scheme and host. A reverse-proxy setup (`runway.proxy.*`) can now be checked from the browser. See
  `docs/ADMINISTRATION.md` §7.
- **`runway.http.min-request-data-rate`** (default `4096` bytes per second; Settings → Server settings → Request
  limits). A request body that arrives more slowly is answered `408 Request Timeout` and its request thread freed. Until now a client sending a byte now and then could
  hold a request thread for as long as it kept sending. `server.jetty.connection-idle-timeout` (`30s`, Jetty's own
  default) is now stated in the shipped configuration.
- **Platform mail can sign in with an OAuth2 token (Microsoft 365).** Settings → Platform settings → Platform mail
  gains a sign-in method: *OAuth2 token*. Runway obtains an access token from the configured token endpoint with a
  client ID and client secret (the client-credentials grant), keeps it until shortly before it expires, and signs in
  to the mail server with SASL `XOAUTH2`. This is how Microsoft 365 accepts application mail once password sign-in
  to SMTP is retired. The client secret is stored encrypted, like the password; an OAuth2 account requires STARTTLS or
  implicit TLS and an `https` token endpoint. **Send test email** says whether the token or the mail server failed.
  Existing password accounts are unchanged. See `docs/ADMINISTRATION.md` §8.1.
- **A site's audit log records where a sign-in came from.** `login.success`, `login.failure` and `logout` now carry
  the client address (`ip=…`), as the application activity log already did; behind a trusted proxy, the visitor's
  address. Until now the field was empty. The address is kept in full: a site's setting to anonymize client
  addresses applies to its access log only.

### Security

- **The client address behind a trusted proxy is read from the right of `X-Forwarded-For`.** Runway took the
  leftmost entry, which the client can write itself: behind a proxy that appends to the header (HAProxy's
  `option forwardfor`, nginx's `$proxy_add_x_forwarded_for`), a client could choose the address that rate limits,
  login lockouts, the access log and plugins saw. Runway now skips the addresses in `runway.proxy.trusted-cidrs` from
  the right and takes the first one that is not a trusted proxy. An entry that is not an IP address there attributes
  the request to the proxy. **Every proxy in front of Runway, a CDN included, must now be listed in
  `trusted-cidrs`** (*Upgrading*, step 1).
- **A password-reset email no longer links to the host name the request named.** The link was built from the
  request's `Host` (or the `X-Forwarded-Host` of a trusted proxy), so anyone could have the platform mail a real user
  a genuine reset link pointing at a site of the sender's choosing, and take over the account if the link was
  opened. A site's link now points at the site's own address (its base URL, or its first host name), and the
  application console's at a host listed in `runway.console.hosts` (spelled and, if listed with one, numbered as
  listed) or else at the default site's address. Where there is no such address, or only `localhost`, "Forgot your
  password?" is not offered. See `docs/ADMINISTRATION.md` §8.3.
- **Only a platform administrator can change where the console's reset links go.** Without `runway.console.hosts`
  they go to the default site's address, which anyone holding the delegable `MANAGE_SITE_HOSTS` permission could
  edit, and a site manager could set through a restore of a backup file they wrote. The default site's hosts and
  base URL now need the application master or a wildcard administrator, and a restore made from a site's own
  console keeps the site's hosts and base URL.
- **A stored platform-mail secret is no longer sent to a server it was not saved for.** A blank password field kept
  the stored password even when the same save changed the mail host, so anyone allowed to edit Platform settings
  could point the account at a server of theirs and receive the password at the next send, without knowing it. The
  password is now kept only while the host, port and username stay the same, and the OAuth2 client secret only while
  the token endpoint, client ID, mail server and scope do (the token minted from it goes to the mail server);
  otherwise the password is cleared and must be entered again, and an OAuth2
  account is not saved without its secret. The activity log's `platform.settings-changed` line now names the
  settings each save changed (never their values). See `docs/ADMINISTRATION.md` §8.1.

### Changed

- **The standard `Forwarded` header (RFC 7239) is no longer read**, even from a trusted proxy; Runway reads the
  `X-Forwarded-*` headers only. A client could otherwise send its own `Forwarded` through a proxy that manages only
  `X-Forwarded-*` and choose its address, host and scheme. A proxy that sends only `Forwarded` must send
  `X-Forwarded-For`, `X-Forwarded-Proto` and `X-Forwarded-Host` instead.

### Fixed

- **`PluginSiteFiles.readText`, `stat` and `list` no longer report a file or folder they cannot reach as absent.**
  When a folder cannot be searched (its permissions, a stale network mount), these calls now throw `IOException`, as
  their contract said; only a path that is certainly not there answers empty. A plugin that treated "absent" as "start
  afresh" could otherwise act on a passing read failure. The testkit's `TempDirSiteFiles` behaves the same way.
- **`Sites.find` no longer answers "no such site" for a site whose folder it cannot read.** It throws instead, so a
  plugin cannot take a live site for a deleted one; site lists leave such a site out and log it, as before.

## [1.0.1] — 2026-10-21

_A patch release: every first-party plugin is now published with Runway — blog, carousel-manager, contact and
discoverability join demo, search, search-lucene and lucene-provider — the installed release carries its license
files, the install instructions work as written, and the 1.0.0 upgrade notes are corrected. The plugin contract is unchanged (SPI `1.0.0`); every plugin release that
`1.0.0` paired with loads unchanged. Built on the `1.0.1-SNAPSHOT` line after `1.0.0`._

### Upgrading — read this first

**From `1.0.0`: a kernel upgrade, no flag, no setting; plugin upgrades optional.** Coming from an older release: read each
newer release's *Upgrading* first, oldest first. Paths are the installer defaults (`/opt/runway`, `/opt/runway-data`).

**1. Back up.** Take each site's backup (**Backups**) and copy `/opt/runway-data/external/` somewhere **outside**
`external/plugins/`. Nothing in `platform.yml` changes in this release.

**2. Upgrade the kernel:**

```bash
chmod +x runway-1.0.1.bin
sudo ./runway-1.0.1.bin -y
```

The essential plugin moves with the kernel to `1.0.1` (its descriptor changes in comments and version only, and its
jar is rebuilt), so the installer refreshes it; expect these lines, once:

```
refreshing bundled plugin 'protection' from this release (it moves with the kernel; files you edited are backed up)
plugin 'protection': plugin.yml replaced (the previous one kept as plugin.yml.bak, for a rollback)
plugin 'protection': runway-plugin-protection.jar replaced (the previous one kept as runway-plugin-protection.jar.bak, for a rollback)
keeping the bundled plugins in the release: /opt/runway-1.0.1/seed/ (the repair copy — Administration §12)
```

**3. Check.** **About Runway** shows `1.0.1`; on **Plugins** every row is *Loaded*, none *Blocked*, and protection
shows `1.0.1`; the start log says `plugins: N registered, 0 blocked`. `/opt/runway-1.0.1/` now holds `LICENSE`,
`LICENSE-EXCEPTION` and `THIRD-PARTY-NOTICES`.

**4. Plugins (optional).** This release carries blog 2.1.0, carousel-manager 2.1.0, contact 2.1.0 and
discoverability 2.3.0, each built for Runway 1.0.0 with its license files, and search 2.3.1 (French strings for
visitors); their earlier releases keep working on 1.0.1. To upgrade one, read its CHANGELOG section, replace its folder with the new zip and restart.

**5. Rollback**, if needed: `sudo bash runway-1.0.0.bin -y` puts 1.0.0 back, its protection files included.

### Fixed
- **The installed release carries its license files.** The installer now copies `LICENSE`, `LICENSE-EXCEPTION`
  and `THIRD-PARTY-NOTICES` into the release directory (`/opt/runway-<version>/`). In `1.0.0` they were in the
  distribution zip and inside the jars (`META-INF/`), but not at the top of an installed release.
- **The install and upgrade instructions set the execute bit.** A downloaded `.bin` is not executable, so the
  documented `sudo ./runway-<version>.bin` failed; README, Administration §3 and §15 and
  Operations now run `chmod +x` first (`sudo bash runway-<version>.bin` works too).

### Added
- **blog, carousel-manager, contact and discoverability are published**, under the GNU AGPL v3.0 with the additional
  permission for plugins, and attached to this release beside demo, search, search-lucene and lucene-provider.
  `KNOWN-LIMITATIONS.md` no longer lists the missing sitemap/robots/llms.txt/structured-data plugin, and the docs list
  all eight plugins.
- **search 2.3.1 speaks French to visitors**: the search box, the results heading and count, "no results" and the
  rate-limit notice.

### Changed
- Code comments across the kernel, the SPI's version log (`RunwaySpi`) and the protection descriptor no longer refer
  to releases before `1.0.0`.

### Notes
- **An upgrade to 1.0.0 printed more protection-refresh lines than its notes listed:** besides the jar, the installer
  replaced protection's `plugin.yml` (keeping `plugin.yml.bak`) and updated `templates/admin-app.th.xml` and
  `templates/app-dos.th.xml`, which changed in comments only. Upgrading to 1.0.1 prints the four lines listed above.
- The documentation links the releases page directly (<https://g.quest.mx/quest/runway/releases>), and
  Administration §11 says where each plugin's zip comes from.

## [1.0.0] — 2026-10-21

The first public release of Runway: a multi-site web platform built as a small multi-tenant kernel, with every
domain feature delivered as a plugin.

- **Kernel**: sites with their own hosts, pages, templates, messages and static files; the operator console
  (application and per-site realms) with the setup wizard, the page editor, roles and permissions, per-site
  backups, platform mail and password recovery; optional in-app TLS termination; Prometheus metrics; an
  optional cluster mode. The essential `protection` plugin (rate limiting and login protection) is bundled.
- **Plugin contract**: SPI `1.0.0`, frozen for the `1.x` line — it changes only additively
  ([docs/SPI-STABILITY.md](docs/SPI-STABILITY.md)). Plugin authors build against the `runway-spi` and
  `runway-spi-testkit` jars attached to each release ([docs/PLUGIN-DEVELOPMENT.md](docs/PLUGIN-DEVELOPMENT.md)).
- **Public plugins** in `plugins/`, attached to this release: `runway-demo` 2.1.0 (the reference plugin, Apache-2.0),
  `search` 2.3.0, `search-lucene` 3.3.0 and `lucene-provider` 10.4.0.
- **Install**: `runway-1.0.0.bin` (self-extracting installer) or `runway-1.0.0-dist.zip`, verified against
  `SHA256SUMS` ([docs/ADMINISTRATION.md](docs/ADMINISTRATION.md) §3).
- **License**: GNU AGPL v3.0 with an additional permission for plugins (`LICENSE`, `LICENSE-EXCEPTION`);
  third-party components are listed in `THIRD-PARTY-NOTICES`.
