# Configuration and data stability commitment

This document states what Runway promises about everything **outside** the Java API that an installation,
a backup, a plugin package or a monitoring system depends on: the `runway.*` configuration keys, the files
Runway keeps on disk, the backup archive, the plugin descriptor (`plugin.yml`), and the HTTP paths, metric
names and logger names other systems are built against. It is the counterpart of
[SPI-STABILITY.md](SPI-STABILITY.md), which covers the binary extension API.

> **Status.** These rules bind from **`1.0.0`**: this document governs, and every change to a covered
> surface is called out in the CHANGELOG ([`../CHANGELOG.md`](../CHANGELOG.md)).

## Why this exists

An operator upgrading from `1.2` to `1.5` should not have to re-read every configuration key, re-test every
backup, or rebuild a Prometheus dashboard. A plugin author should know which `plugin.yml` keys an older kernel
will understand. That needs two things: a written promise per surface, and something **on disk** that says
which version of a format a file is in, so that a later Runway can tell an old file from a new one and an
older Runway can refuse a file it cannot read instead of quietly damaging it. Two markers carry that version:
`external/layout.yml` for the data folder, and `formatVersion` in a backup's `manifest.yml`.

## What "compatible" means — the common rules

Within one **major** line (`1.x`):

1. **Nothing covered is removed or changes meaning.** A configuration key keeps its meaning, its type and its
   default **unit**; a file keeps its location and the meaning of its fields; a metric keeps its name and tags.
2. **Additions are free.** New keys, new optional fields, new files, new metrics and new paths may appear in any
   minor release. Each has a default that reproduces the previous behaviour.
3. **Renames and removals take a deprecation cycle.** The old name keeps working for the rest of the major line,
   a startup `WARN` names the replacement, the configuration metadata marks it deprecated, and the changelog
   lists it under *Deprecated*. It is removed only in the next major.
4. **Defaults may be tuned** (a larger cap, a shorter cache lifetime) when the change does not alter what a
   correctly configured installation does; the changelog says so. A default that changes behaviour an operator
   relies on is treated like a rename.
5. **A breaking change is a new major** (`2.0`), called out in the changelog with upgrade steps.

## 1. The `runway.*` configuration namespace

**Covered:** every key below, whether set in `external/application.yml`, as an environment variable (Spring's
relaxed binding: `RUNWAY_STORAGE_EXTERNAL` sets `runway.storage.external`), or on the command line.

**Where the list comes from.** The kernel builds `META-INF/spring-configuration-metadata.json` from its 18
`@ConfigurationProperties` classes and from
`runway-app/src/main/resources/META-INF/additional-spring-configuration-metadata.json`, which describes the
keys read through `@Value` or straight from the environment (the demo installer, two size caps, the plugin
root and the node name). An IDE uses the same file for completion and type checks. A test
(`ConfigurationMetadataTest`) fails the build if a key named in the shipped `application.yml`, or one of the
`@Value`/environment keys, is missing from the metadata. Meanings are in [ADMINISTRATION.md](ADMINISTRATION.md)
§8 and the section of the manual that owns each feature; the table below is the inventory.

"Default" is the value the kernel uses when the key is absent. Where the shipped classpath `application.yml`
sets a different value, both are shown.

**`runway.app-activity.*`** — the application-realm activity log

| Key | Type | Default |
|---|---|---|
| `enabled` | boolean | `true` |
| `retention-days` | int | `90` |

**`runway.backups.*`**

| Key | Type | Default |
|---|---|---|
| `max-entry-bytes` | long (bytes) | `268435456` (256 MiB) |
| `offsite-dir` | string (path) | _(blank — off)_ |
| `retention.max-age-days` | int | `0` (no age limit) |
| `retention.max-count` | int | `30` |
| `schedule.cron` | string (6-field Spring cron) | `0 30 2 * * *` |
| `schedule.enabled` | boolean | `false` |

**`runway.bandwidth.*`**

| Key | Type | Default |
|---|---|---|
| `enabled` | boolean | `true` |
| `quota.default-monthly-bytes` | long (bytes) | `0` (no cap) |
| `quota.per-site` | map: site id → long (bytes) | empty |
| `retention-days` | int | `90` |
| `snapshot-seconds` | int | `60` |

**`runway.cluster.*`** — see *Not covered* below: clustering is opt-in and outside the 1.0 promise.

| Key | Type | Default |
|---|---|---|
| `enabled` | boolean | `false` |
| `cluster-name` | string | `runway` |
| `topology` | enum `embedded` \| `client-server` | `embedded` |
| `session-ttl` | duration | `30m` |
| `sessions-map-name` | string | `runway.sessions` |
| `events-topic-name` | string | `runway.events` |
| `embedded.discovery` | string `tcp-ip` \| `multicast` | `tcp-ip` |
| `embedded.members` | list of string | empty |
| `embedded.port` | int | `5701` |
| `embedded.port-auto-increment` | boolean | `true` |
| `client.addresses` | list of string | empty |
| `client.smart-routing` | boolean | `true` |

**`runway.compression.*`**

| Key | Type | Default |
|---|---|---|
| `enabled` | boolean | `true` |
| `max-buffer-bytes` | long (bytes) | `4194304` (4 MiB) |
| `mime-types` | list of string (replaces the default list) | `text/html`, `text/xml`, `text/plain`, `text/css`, `text/javascript`, `application/javascript`, `application/json`, `application/xml`, `image/svg+xml` |
| `gzip.enabled` | boolean | `true` |
| `gzip.min-size` | int (bytes) | `1024` |
| `brotli.enabled` | boolean | `true` |
| `brotli.min-size` | int (bytes) | `1024` |
| `brotli.quality` | int `0..11` | `5` |

**`runway.console.*`** — where the operator console answers (host only, read at startup; ADMINISTRATION §8.6)

| Key | Type | Default |
|---|---|---|
| `hosts` | list of host names (case and port ignored) | empty: the default site's host names and host names that serve no site |

**`runway.csp.*`**

| Key | Type | Default |
|---|---|---|
| `mode` | enum `report` \| `enforce` \| `off` | `report` |
| `default-src` | string | `'self'` |
| `script-src` | string | `'self'` |
| `style-src` | string | `'self' 'unsafe-inline'` |
| `img-src` | string | `'self' data:` |
| `font-src` | string | `'self'` |
| `connect-src` | string | `'self'` |
| `base-uri` | string | `'self'` |
| `form-action` | string | `'self'` |
| `frame-ancestors` | string | `'self'` |
| `report-uri` | string | `/__csp-report` |
| `upgrade-insecure-requests` | boolean | `false` |
| `strict-dynamic` | boolean | `true` — `'strict-dynamic'` on the nonced kernel surfaces (console, login, recovery, wizard) |
| `public-strict-dynamic` | boolean | `true` — public tenant pages get a nonce derived from the page's ETag plus `'strict-dynamic'`, stamped only on the scripts the `script-src` + `extra-hosts` + plugin-host allowlist would have run (never an inline script unless `script-src` has `'unsafe-inline'`); takes effect only while `strict-dynamic` is on and `mode` is not `off` |
| `extra-hosts` | list of string | empty |

The secret that keys public-page nonces is a file, not a key: `external/csp-nonce.key` (§2).

**`runway.demo.*`** — unattended demo install (read via `@Value`)

| Key | Type | Default |
|---|---|---|
| `first-run.id` | string | _(blank — nothing installed)_ |
| `first-run.host` | string | _(blank — the demo's own host)_ |
| `first-run.master-username` | string | `demo` |
| `first-run.master-password` | string (secret) | _(blank)_ |

**`runway.endpoints.*`** (read via `@Value`)

| Key | Type | Default |
|---|---|---|
| `max-body-bytes` | long (bytes) | `10485760` (10 MiB) |
| `max-form-bytes` | long (bytes) | `262144` (256 KiB) |

`max-form-bytes` is the platform's **pre-authentication body budget**: the most body a caller who is **not signed
in** can make the server read, on any path. It bounds the form (or multipart) body of a plugin route, which the
kernel reads before authentication and the CSRF check (so the check and the route both see it); it also bounds
**any** body a caller who is not signed in sends — to the sign-in, setup and password-recovery forms of both realms,
to any console path, to the CSP report endpoint, to a `PUT`/`PATCH`/`DELETE` form, and to a plugin route of any
method and type. Above it the request is answered `413`; a declared `Content-Length` above it is refused without
reading. The effective value is the smaller of the two keys. `max-body-bytes` bounds the body a **signed-in** caller
sends to a plugin route (JSON, uploads, raw text), read after security; a signed-in console form keeps the
container's limit.
Raise `max-form-bytes` only for a plugin whose anonymous forms or uploads really carry more than 256 KiB. (A
multipart upload to a kernel path is the exception: it is parsed to temporary files before security under
`spring.servlet.multipart.max-request-size`.)

**`runway.health.*`** — the per-site `/__health` probe

| Key | Type | Default |
|---|---|---|
| `enabled` | boolean | `true` |
| `token` | string (secret) | _(blank — detail off)_ |
| `content-check` | boolean | `true` |
| `content-check-ttl-seconds` | int | `10` |

**`runway.http-cache.*`**

| Key | Type | Default |
|---|---|---|
| `public-pages` | string (`Cache-Control` value) | `public, max-age=300, stale-while-revalidate=60` |
| `assets` | string | `public, max-age=86400` |
| `immutable-assets` | string | `public, max-age=31536000, immutable` |
| `immutable-extensions` | set of string | `woff2, woff, ttf, otf, eot` |

There is no `no-store` key: the editor preview and error responses are always `no-store`. A file that sets it
starts normally; the key is ignored.

**`runway.i18n.*`**

| Key | Type | Default |
|---|---|---|
| `cache-seconds` | int | `5` |

**`runway.logs.*`** — per-site log capture

| Key | Type | Default |
|---|---|---|
| `enabled` | boolean | `true` |
| `access-log-user-agent` | boolean | `false` |
| `max-retention-days` | int | `90` |
| `queue-capacity` | int | `8192` |

**`runway.metrics.*`**

| Key | Type | Default |
|---|---|---|
| `scrape-token` | string (secret) | _(unset — off; a blank console field keeps a stored token)_ |

**`runway.node.*`** (read from the environment)

| Key | Type | Default |
|---|---|---|
| `name` | string | _(blank — the OS host name)_ |

**`runway.operator.*`**

| Key | Type | Default |
|---|---|---|
| `ui-languages` | list of BCP-47 codes | `[en, es]` |
| `default-language` | string | `en` |

**`runway.plugins.*`**

| Key | Type | Default |
|---|---|---|
| `root` | path (read from the environment before startup) | _(blank — `<external>/plugins`)_; the loader and the console's install/uninstall/enable/disable resolve it by one shared rule, so both always use the same folder |
| `max-entry-bytes` | long (bytes, `@Value`) | `134217728` (128 MiB) |
| `site-files.max-write-bytes` | long (bytes, `@Value`) | `16777216` (16 MiB): the largest single write (and whole-file read) a plugin makes in its per-site subtree through `PluginSiteFiles` |

**`runway.protect.*`** — the kernel's edge-rejection logging

| Key | Type | Default |
|---|---|---|
| `log-rejections` | boolean | `true` |
| `log-rejection-window-seconds` | int | `60` |

**`runway.proxy.*`**

| Key | Type | Default |
|---|---|---|
| `forwarded-headers` | boolean | `true` |
| `trusted-cidrs` | list of CIDR | empty (trust nothing); the shipped `application.yml` sets `127.0.0.1/32`, `::1/128` |

**`runway.http.*`**

| Key | Type | Default |
|---|---|---|
| `min-request-data-rate` | long, bytes per second | `4096` (`0` = off) |

**`runway.reload.*`** — `/__reload/**`

| Key | Type | Default |
|---|---|---|
| `enabled` | boolean | `true` |
| `token` | string (secret) | _(blank — the endpoint is absent)_ |

**`runway.security.*`**

| Key | Type | Default |
|---|---|---|
| `app-master-id` | string | _(blank — WIZARD mode)_; written by the first-run wizard |
| `password-strength` | enum `standard` \| `high` | `standard` |
| `max-concurrent-hashes` | int | `8` |
| `acquire-timeout-millis` | long (ms) | `2000` |
| `secret-key` | string (secret) | _(blank)_; set by the installer as `RUNWAY_SECURITY_SECRET_KEY` |

**`runway.storage.*`**

| Key | Type | Default |
|---|---|---|
| `external` | path | `external` (relative to the working directory, which the launcher sets to the release dir) |
| `sites` | path | `<external>/sites` |
| `backups` | path | `backups` (relative to the working directory) |

`runway.storage.stats` (never read) and `runway.storage.logs` (display-only; the kernel writes nothing there) are
not keys, so they never enter the promise. A configuration that sets one starts normally and logs one startup
`WARN` naming the key; the metadata marks both as removed (deprecation level `error`).

**`runway.tls.*`**

| Key | Type | Default |
|---|---|---|
| `enabled` | boolean | `false` |
| `keystore` | string (path) | _(blank)_ |
| `keystore-password` | string (secret) | _(blank)_ |
| `keystore-type` | string | `PKCS12` |
| `key-alias` | string | `runway` |
| `watch-keystore` | boolean | `true` |
| `sni-host-check` | boolean | `true` |
| `redirect-http.enabled` | boolean | `false` |
| `redirect-http.port` | int | `80` |
| `redirect-http.status` | int `301` \| `302` \| `307` \| `308` (any other value stops startup while the redirect is on) | `301` |
| `redirect-http.acme-challenges-dir` | string (path) | `./external/acme-challenges` |

**Plugin keys: `runway.plugin.<id>.*`.** Each plugin owns the prefix `runway.plugin.<its id>.`; its app-level
settings are stored as `external/plugin-settings/<id>.yml` and bound by the plugin's own properties class. The
kernel promises the **prefix and the file**; the keys under it are the plugin's contract with its operators and
follow that plugin's own versioning. The bundled essential plugin documents its keys in
[ADMINISTRATION.md](ADMINISTRATION.md) §12 (`runway.plugin.protection.*`: `rate-limit.*`, `allowlist` and
`remember-device.*` on its settings form; `login.*` including `login.captcha.*`, which only seed a fresh
install's Login protection panel), and its jar carries its own
`META-INF/spring-configuration-metadata.json` for them (`ProtectionConfigurationMetadataTest` fails its build if
the file is not generated).

**Framework keys** (`server.*`, `spring.*`, `management.*`) belong to Spring Boot and are not covered here.
The ones Runway pins on purpose are listed, with the reason for each, in [ADMINISTRATION.md](ADMINISTRATION.md)
§8; a change to a pinned value is a changelog entry.

## 2. The data folder: `external/` and `sites/<id>/`

**Covered:** the location and meaning of every file below. Its *contents* are covered as far as its owner's
format goes: YAML stores keep their field names and meanings, properties files keep their keys, and templates
are the site's own content.

### The layout marker — `external/layout.yml`

```yaml
# Runway data-layout marker. Written by Runway on first start; do not edit or delete it.
# ...
version: 1
```

Owner: `mx.quest.runway.storage.DataLayout`, checked by `DataLayoutInitializer` at the very start of every
boot — before the plugin loader reads `external/`, before any store, seeder or plugin bean exists.

| What is on disk | What Runway does |
|---|---|
| no `layout.yml` | Writes `version: 1`. Every folder that predates the marker is layout 1 by definition. On a first, WIZARD-mode start against a folder that does not exist yet, nothing is written (nothing to protect); the first start that has a folder writes it. |
| `version` ≤ what the build supports | Starts normally; the file is not rewritten. |
| `version` > what the build supports | **Refuses to start** ("APPLICATION FAILED TO START", naming the file and both versions). An older Runway must never write into a layout it does not understand. |
| unreadable, not a mapping, no `version`, not a whole number, or below 1 | **Refuses to start** rather than guess. |

A failure to *write* an absent marker (for example a read-only folder) is logged as a warning and retried on
the next start; it never stops a start that worked before the marker existed.

**What a layout bump means.** The layout version changes only when a release changes the folder in a way an
older release would misread or damage: moving or renaming a file, changing what a field means, or adding data
an older release would silently drop when it rewrites a store. That release migrates the folder forward on its
first start, then writes the new version. The consequence for operators is the downgrade rule: **after a
layout bump, the previous release refuses to start against the migrated folder.** To roll back past a layout
bump, restore the copy of `external/` taken before the upgrade. Adding an optional field, file or folder is
*not* a layout bump.

### How a YAML store tolerates change

Every kernel store reads through `mx.quest.runway.storage.Yaml`, whose mapper has
`FAIL_ON_UNKNOWN_PROPERTIES` and `FAIL_ON_NULL_FOR_PRIMITIVES` disabled; each record's compact constructor
supplies the default for an absent field. So:

- a **newer** file read by an **older** build loads (unknown keys are ignored); an **older** file read by a
  newer build loads (absent fields take defaults that reproduce the old behaviour);
- **but an older build does not preserve keys it does not know when it rewrites a record.** That is exactly why
  a field whose loss would matter needs a layout bump, and why the layout marker refuses a downgrade instead of
  relying on tolerance.

### `external/` — platform state (never in a tenant backup)

| Path | Owner | Holds |
|---|---|---|
| `application.yml` | operator; the first-run wizard (`WizardService`) merges the superadmin pin into it | The operator's configuration override (loaded by `spring.config.import`). Keep it mode `600`. |
| `layout.yml` | `DataLayout` | The layout marker above. |
| `platform.yml` | `PlatformSettingsStore` | Platform settings edited on **Settings → Platform settings**: the default site, platform mail (SMTP), node name, compression and password-strength overrides; and under `settings:` the kernel settings edited on **Settings → Server settings** and the policy cards (full property name → value; `ENC(...)` for a token), loaded at startup below `external/application.yml` (`KernelSettingsInitializer`), declared in the jar's `kernel-settings.yml`. Also `siteQuotas:` (each site's own bandwidth cap in bytes, set on **Sites**, read live), `siteStorageCeilings:` (each site's own storage limit for its files in bytes, set on **Sites**, read live), `embedHosts:` (the hosts approved on **Settings → Embed hosts**: `id`, `label`, `source`, `directives`, read live) and `logLevels:` (logger name → level, set on **Activity → Logs → Log levels**; applied at once on save and again at every start; a `logging.level.*` set in `application.yml` wins over it). |
| `app-users.yml` | `AppUserStore` | Application-realm accounts (Argon2id hashes). |
| `site-users.yml` | `TenantMasterStore` | Tenant superadmins, each assigned to one or more sites (application-managed, so kept outside every site folder). |
| `app-roles.yml` | `RoleStore` | Application-realm custom roles. Validated at startup; an invalid file stops the start. |
| `app-grants.yml` | `GrantStore` | Application-realm permission grants. |
| `recovery-tokens.yml` | `RecoveryTokenStore` | Hashed, expiring password-recovery tokens (both realms). |
| `blocklist.yml` | `StorageLayout.blocklistFile()`; written by the protection plugin's `IpBlocklist` | Manual IP/CIDR blocks. |
| `activity/` | `AppActivityLog` | Application-realm activity day-files (`activity-<yyyy-mm-dd>.log`), one line per operator action in the audit journal format below. Pruned after `runway.app-activity.retention-days`. |
| `csp-nonce.key` | `PublicPageNonce` | The secret that keys public-page CSP nonces (below). |
| `plugins/<id>/` | operator / console install (`PluginAdminService`); read by `PluginLoader` | One folder per installed plugin: `plugin.yml`, jars, `static/`, `i18n/`. When `runway.plugins.root` is set, these folders live there instead — for the loader and the console alike. |
| `plugin-settings/<id>.yml` | `PluginSettingsService` | A plugin's app-level settings (`runway.plugin.<id>.*`), loaded as a property source above `application.yml` and below environment variables and command-line arguments. Only a file named for an installed plugin is loaded, and only its keys under `runway.plugin.<id>.`; any other key in the file is ignored, with a warning naming it. A save from the console rewrites the file with the keys the plugin declares, so an undeclared key is dropped. Another file in the folder (the protection plugin's `protection-app.yml`) is that plugin's own data, never configuration. |
| `plugin-quarantine/<id>.yml` | `PluginQuarantine` | The marker of a plugin that broke the platform's startup: its version, a checksum of its jars, the reason and the time. While it matches the installed plugin, the plugin stays Blocked; **Retry** on the Plugins screen removes it, and a different version or different jars make it stale, and it is deleted (ADMINISTRATION.md §11). |
| `plugin-manifests/<id>.sha256` | the installer (`scripts/install.sh`) | What the installer shipped for a bundled plugin, so an upgrade can tell an operator's edit from a stale copy. |
| `seed/site-templates/<id>/` | `SeedTemplateStore` | The site-template catalog new sites are born from (`template.yml` plus the seed files). |
| `seed/site/` | `SeedTemplateStore` (legacy) | The pre-catalog seed folder; migrated into `seed/site-templates/default` on first run. |
| `i18n/operator-messages[_xx].properties` | `OperatorMessageSource` | Optional operator re-translation of the console, above the bundled messages. |
| `tls/` | the installer | A copied PKCS12 keystore when Runway terminates TLS itself. |
| `acme-challenges/` | operator / ACME client (`runway.tls.redirect-http.acme-challenges-dir`) | Files served at `/.well-known/acme-challenge/`. |
| `sites/` | `StorageLayout.sites()` (`runway.storage.sites`) | One folder per site (below). `sites/.restore-work/` is restore scratch space, never a site. |

### The audit journal line format — `activity/*.log` and `sites/<id>/logs/audit-*.log`

Both journals (the application activity log and each site's audit log) share one format, owned by
`mx.quest.runway.log.AuditJournal`. It is **covered**: a monitoring or SIEM pipeline may parse it.

- **Files.** One UTF-8 file per **server-local** calendar day: `activity-<yyyy-MM-dd>.log` and
  `audit-<yyyy-MM-dd>.log`. Lines are only ever appended.
- **Line.** Four tab-separated fields, ending in `\n`:

  ```
  <time> TAB <actor> TAB <action> TAB <detail>
  2026-10-17T08:10:03.123-06:00	admin	appuser.deleted	alice (id 6f1c…)
  ```

  `actor` is the operator's username (or a system marker); `action` is a stable dotted key
  (`appuser.created`, `master.suspended`, `user.password-reset`, …); `detail` is free text (the target's name, an IP
  for a login). CR, LF, tab and NUL inside a field are written as a space, so a crafted value can never forge a
  line or a field.
- **Time.** ISO-8601 local date-time with milliseconds **and its UTC offset** (`Z` at UTC), in the server's time
  zone: `yyyy-MM-dd'T'HH:mm:ss.SSSXXX`. The offset makes every entry's instant unambiguous across a DST change or
  a server time-zone move.
- **Offset-less lines.** Runway writes only the form above. The reader also accepts lines that carry
  `yyyy-MM-dd HH:mm:ss` with no offset and places them in the server's **current** zone (the console shows such
  times without an offset). A reader parsing these files should accept both, as Runway does.
- **Tolerance.** A line with fewer than four fields reads its missing trailing fields as empty; a line whose time
  matches neither form is skipped, never an error.

The per-site **access** log (`access-*.log`) is not part of this promise: it keeps its offset-less timestamp and
its field layout may change (see *Not covered*).

### The public-page CSP nonce secret — `external/csp-nonce.key`

Public tenant pages carry a CSP nonce derived from the page version:
`base64url(HMAC-SHA256(secret, "runway-csp-nonce/v1|" + ETag))`, so every `200` and `304` of one content version
carries the same nonce and shared caches keep working. The secret is this file.

- **Created** on the first page that needs it (only while `runway.csp.public-strict-dynamic` is in effect): 32
  random bytes, written owner-only (`0600`) and published atomically, so two nodes sharing the folder never
  overwrite each other's key. A file shorter than 32 bytes is refused.
- **Read once** per process and held in memory.
- **Rotation:** delete the file and restart. Every nonce changes, and because a fingerprint of the secret (never
  the secret) is folded into each page ETag, **every page ETag changes once** too, so no browser or CDN can pair
  an old body (old nonce) with a new header. Caches simply revalidate.
- **Clustering:** give every node the same file, as with other shared secrets; otherwise each node has its own
  ETags (still correct, fewer cross-node `304`s).
- **If it cannot be created or read**, the feature turns itself off for that process (logged once at `ERROR`):
  public pages keep the host-allowlist policy and their ETags say so. Never a `500`.
- It is platform state under `external/`, so it never travels in a site backup.

The nonce values and the ETag values themselves remain opaque (see *Not covered*); the file's name, location,
permissions and the rotation procedure are covered.

### `sites/<id>/` — one self-contained site (travels whole in its backup)

| Path | Owner | Holds |
|---|---|---|
| `site.yml` | `SiteStore` | The site record: id, name, hosts, locales, base URL, status, and the optional `editorial:` level (`advisory` \| `enforced` \| `reviewed`; absent or unrecognised reads as `advisory`). |
| `users.yml` | `SiteUserStore` | The site's own accounts (not the tenant superadmin). |
| `roles.yml` | `RoleStore` | The site's custom roles. An invalid file quarantines that site's console, not the platform. |
| `permissions.yml` | `GrantStore` | The site's permission grants. |
| `templates/` | `PageStore`, `PageWriteService` | Pages (`<slug>.html` + decoupled `<slug>.th.xml` + `<slug>.meta.yml`) and `_master.html`. |
| `templates/.pending/<slug>/` | `PendingRevisionStore` | Pending editorial work for one page: `review.yml` (state `submitted` \| `returned`, who/when, the approver's note) and, for a change to a published page, `revision.meta.yml` + `revision.html` + optional `revision.th.xml`. Never routed or listed; carried by backups and transfer copies. |
| `i18n/messages[_xx].properties` | `SiteMessagesStore` | The site's messages; the default locale is the unsuffixed file. |
| `static/` | site manager (served by `SiteStaticController`) | The site's public assets at `/static/**`. |
| `static/doc/` | `DocumentService` | Uploaded documents. |
| `doc-state.yml` | `DocumentService` | The documents a site manager has disabled (withheld from the public without deleting them). |
| `icons/` | `SiteIconService` | `favicon.svg`, `favicon.ico`, `apple-touch-icon.png`, `icon-192.png`, `icon-512.png`. |
| `manifest.yml` | `SiteManifestService` | The web app manifest's fields (served as `/manifest.webmanifest`). |
| `seo.yml` | `SeoConfigService` | Site-wide SEO settings. |
| `csp.yml` | `SiteCspService` | `hosts:` the ids of the operator's approved embed hosts this site switched on (**Site config → Embedded content**). Only ids still approved count. |
| `contact.yml` | `SiteContactService` | The site's contact details. |
| `params.yml` | `SiteParamsService` | Template parameters: a flat key → value map exposed to every render as `${params.key}`. |
| `log.yml` | `SiteLogConfigService` | The site's own log-retention preference. |
| `logs/` | `SiteLogService` | `access-<date>.log` and `audit-<date>.log` day-files; the audit files use the audit journal format (§2, above). |
| `stats/bandwidth.csv` | `BandwidthService` | Hourly served-bytes buckets. |
| `plugins/<plugin>.yml` | `SitePluginConfigService` | A plugin's per-site settings. |
| `plugins/<plugin>/` | the plugin | Plugin-generated per-site data (for example a content source's items). |
| `widget-instances.yml` | `WidgetInstanceConfigService` | Per-instance settings of placed content widgets. |

### Outside `external/`

| Path | Key | Holds |
|---|---|---|
| `backups/<siteId>/backup-<yyyyMMdd-HHmmss-SSS>.zip` | `runway.storage.backups` | Stored backups (below). |
| `logs/runway.out` | (the launcher) | The service's stdout/stderr when run detached. |

## 3. The backup archive

**Covered:** the archive's structure and `manifest.yml`, and the restore promise.

```
manifest.yml                     what the archive is
site/<path inside sites/<id>/>   the whole site folder (symlinks are never followed)
plugin-data/<key>/<entry>        each BackupParticipant's out-of-folder data
```

`manifest.yml` (`mx.quest.runway.backup.BackupManifest`):

| Field | Meaning |
|---|---|
| `formatVersion` | The archive **format** version. Currently `1`. **Absent = `1`**. |
| `siteId` | The site the archive was taken from (informational; a restore targets the chosen site). |
| `spiVersion` | The SPI version of the writer — **diagnostic only**; restore never branches on it. |
| `createdAt` | ISO-8601 instant. |
| `participants` | The `BackupParticipant` keys whose data is under `plugin-data/`. |
| `purpose` | `RESTORE` (complete) or `TRANSFER` (accounts, secrets and hostnames removed). Absent = `RESTORE`. |
| `omitted` | What a `TRANSFER` copy left out as plugin personal data: each declared `siteData.personal` path (`plugins/<pluginId>/<path>`; a folder ends in `/`) and `plugins/<id>/` for each plugin that was not loaded. Empty for a complete backup. **Absent = empty** (an additive key, so `formatVersion` stays `1`). |

**Reading rules.** Readers do not depend on entry order (Runway writes `manifest.yml` first, and checks its
`formatVersion` the moment it reads it). Unknown manifest keys are ignored. Unknown top-level entries are
drained and ignored. A participant named in the manifest but not installed is tolerated: its data stays
unrestored. An archive without `manifest.yml` or without `site/site.yml` is not a Runway backup.

**The format gate.** Restore — on the site console, on the app console, and when creating a new site from a
backup — accepts any `formatVersion` up to the one the build supports and **refuses a higher one**, before
anything is swapped in: the operator sees "This backup was made by a newer version of Runway (backup format N;
this version restores up to format M)" and the live site is untouched. A value below 1 is not a valid backup.

**The cross-version promise.** An archive written by any `1.x` restores on the same or any later `1.x`.
`formatVersion` rises only for a change an older reader would misread (a new mandatory entry, a moved tree);
adding a manifest key or an optional entry is not a format change. Restoring an archive from a *later* `1.x`
on an earlier one works whenever the format number allows it, but is not part of the promise: the later
release may have written site files the earlier one does not use.

## 4. The plugin descriptor (`plugin.yml`)

**Covered:** every key below. The kernel reads the descriptor without loading plugin code
(`mx.quest.runway.plugin.PluginDescriptor`), with `FAIL_ON_UNKNOWN_PROPERTIES` off, so a descriptor written for
a newer kernel loads on an older one and the other way round. Within `1.x` a key keeps its meaning; new keys
are optional. A plugin that needs a newer kernel says so with `requiresSpi`, and an older kernel then blocks
it with a clear reason instead of loading it to fail later.

| Key | Type | Meaning |
|---|---|---|
| `id` | string | Stable id = install-folder name (must be a safe id). **Required.** |
| `name` | string | Display name. |
| `version` | `MAJOR.MINOR.PATCH` | The plugin's version. **Required**, strictly parsed. |
| `mainClass` | string | The `@Configuration` class; absent = a code-less library provider. |
| `enabled` | boolean | `false` excludes the plugin. Absent = enabled. |
| `essential` | boolean | Honoured only for plugins on the kernel's security-floor allowlist. |
| `requires` | list | Hard dependencies: `id`, `id >= v` or `id == v`. |
| `conflicts` | list of id | Rival plugins this one steps aside for. |
| `requiresSpi` | `>=X.Y.Z` | Minimum kernel SPI level. Malformed = the plugin is blocked, never silently ignored. |
| `permissions` | list | Declared permissions: `id`, `realm` (`tenant` \| `application`), `label`, `defaultRoles`. |
| `settings` | list of setting | App-level settings schema. |
| `siteSettings` | list of setting | Per-site settings schema. |
| `widgetSettings` | map: widget key → list of setting | Per-instance settings of a content widget. |
| `settingGroups` | list | Titled form sections: `id`, `label`, `help`, `helpAnchor`. |
| `helpUrl` | string | The plugin's help page (path or absolute URL). |
| `menu` | list | Console menu entries: `realm`, `region`, `order`, `label`, `path`, `permission`, `icon`. |
| `dashboardCards` | list | Dashboard cards: `realm`, `order`, `fragment`, `permission`. |
| `siteData` | map | `personal`: paths under `sites/<id>/plugins/<pluginId>/` holding personal data (a trailing `/` names a folder). A transfer copy leaves them out. A malformed path (absolute, a backslash, an empty, `.` or `..` segment) blocks the plugin. Absent = nothing declared. |

A **setting** (`PluginSettingDef`) has `key`, `type` (`string` \| `text` \| `secret` \| `int` \| `decimal` \|
`boolean` \| `enum` \| `list`), `default`, `label` and `range`, plus the presentation keys `help`, `group`,
`advanced`, `showWhen`, `helpAnchor`, `inheritsFrom` and `requiresPlatform`, and — on a `secret` only —
`secretBoundTo`: a list of keys in the same schema the secret belongs with (where it is sent). When a save
changes one of them and the secret field is left blank, the stored secret is **removed** rather than kept, and
the form says it must be entered again. Absent = empty = a blank secret is always kept.

**Tolerance rules.** Structural keys (`id`, `version`, `requires`, `requiresSpi`, `permissions`, `menu`, `siteData`) are
validated, and a violation blocks the plugin with an operator-readable reason on the **Plugins** screen; the
platform still starts. Presentation keys (`settingGroups`, `help`, `group`, `advanced`, `showWhen`,
`helpAnchor`, `inheritsFrom`, `requiresPlatform`, `helpUrl`) **degrade** instead: a group id nobody declared, an empty
list item, a `showWhen` naming a missing or non-boolean key, or an `inheritsFrom` that cannot be resolved
renders the form without that refinement, never a failure. An
older kernel ignores them entirely, so a plugin can adopt them without raising `requiresSpi`. `secretBoundTo`
is read just as leniently — a list, one key, or a comma-separated string; a malformed value (a map, a number)
reads as empty, and a key the schema does not declare never matches — so it never blocks a plugin; at worst a
binding is missing and the old "blank keeps the secret" applies.

## 5. HTTP paths, metrics and logger names

### Public paths a client may depend on

| Path | Realm / host | Contract |
|---|---|---|
| `GET /actuator/health` (+ `/liveness`, `/readiness`) | any | Public `UP`/`DOWN`; details only to an authenticated operator. |
| `GET /actuator/prometheus` | any | Prometheus scrape; app superadmin/admin session or `runway.metrics.scrape-token` bearer. |
| `GET /__health` | a site's host | Per-site probe: `{"status":"up\|degraded\|down","time":…}`; detail with `runway.health.token`. |
| `POST /__reload/site/<id>`, `POST /__reload/sites` | loopback only | Re-read a site (or all) without a restart; `runway.reload.token` bearer. |
| `POST /__csp-report` | any | CSP violation reports (the default `runway.csp.report-uri`). |
| `/login`, `/logout`, `/forgot-password`, `/reset` | a site's host | Site sign-in and password recovery. |
| `/app/login`, `/app/logout`, `/app/forgot-password`, `/app/reset` | the platform host | Application-realm sign-in and recovery. |
| `/static/**`, `/static/doc/**` | a site's host | The site's assets and documents. |
| `/favicon.svg`, `/favicon.ico`, `/apple-touch-icon.png`, `/icon-192.png`, `/icon-512.png`, `/manifest.webmanifest` | a site's host | Site icons and web app manifest. |
| `/__plugin/<id>/static/**` | any | A plugin's static files (the path shape; the files are the plugin's). |

**Reserved namespace.** Tenant content can never claim a path under `/__`, `/actuator`, `/app/`, or the exact
routes `/error`, `/login`, `/logout`, `/forgot-password`, `/reset` (`mx.quest.runway.web.ReservedPaths`). New
kernel paths are added only inside that reserved space, so a 1.x upgrade never shadows a site's page.

### Metric names and tags

Consumed by the bundled `monitoring/` dashboard and alert rules. Names (Micrometer form → Prometheus form) and
tag keys are covered; tag *values* listed are covered too.

| Meter | Prometheus | Tags |
|---|---|---|
| `runway.sites` | `runway_sites` | — |
| `runway.sites.suspended` | `runway_sites_suspended` | — |
| `runway.sites.maintenance` | `runway_sites_maintenance` | — |
| `runway.plugins.loaded` | `runway_plugins_loaded` | — |
| `runway.logs.dropped` | `runway_logs_dropped` | — |
| `runway.storage.free.bytes` | `runway_storage_free_bytes` | — |
| `runway.storage.total.bytes` | `runway_storage_total_bytes` | — |
| `runway.http.server.requests` (timer) | `runway_http_server_requests_seconds_*` | `site`; `outcome` = `informational` \| `success` \| `redirection` \| `client_error` \| `server_error` |
| `runway.edge.rejections` (counter) | `runway_edge_rejections_total` | `status` (HTTP code); `reason` |

The JVM, Jetty and route-level (`http_server_requests_seconds_*`) metrics are Spring Boot / Micrometer's and
are not covered here.

### Logger names

| Logger | Carries |
|---|---|
| `runway.access` | One line per served public request, platform-wide (route it to its own file or log stack). |
| `runway.protect` | Edge rejections (rate limits, blocks), de-duplicated per client and reason. |

The **names** are covered, so a logging configuration that routes them keeps working. The wording of a line is
not (see below).

## 6. Explicitly NOT covered

- **Operator console URLs and screens** under `/__app/**` and `/__admin/**`, the first-run `/setup`, the
  console's vendor assets under `/__assets/**`, and `/__ui-lang`. They are a user interface, not an API, and
  may move in any release.
- **The markup of internal templates** (console pages, login, error pages, the bundled seed templates as
  shipped). A site's own templates are its content and are covered as files; what Runway ships as a starting
  point is not.
- **Log line wording** of every logger and log file, including `runway.access`, `runway.protect` and the
  per-site access log, except the audit journal format listed in §2. Within that format, the set of `action`
  keys may grow; an existing key keeps its meaning.
- **ETag values and CSP nonces**: opaque; their derivation may change in any release (a change only makes
  caches revalidate once).
- **Clustering**: the `runway.cluster.*` keys, the Hazelcast map and topic names (`runway.sessions`,
  `runway.events`), and the wire format between nodes. Clustering is opt-in and outside the 1.0 scope; it
  joins this promise when it leaves opt-in.
- **Framework keys and metrics** (`server.*`, `spring.*`, `management.*`, JVM/Jetty meters): Spring Boot's.
- **The Java classes** named here as owners. They identify who reads and writes a file; they are not API.
  The binary API is `runway-spi`, covered by [SPI-STABILITY.md](SPI-STABILITY.md).

## For maintainers: changing a covered surface

- **A new configuration key**: give it a default that keeps today's behaviour; it appears in the metadata
  automatically (properties class) or must be added to `additional-spring-configuration-metadata.json`
  (`@Value` / environment), or `ConfigurationMetadataTest` fails.
- **A renamed key**: keep binding the old one, log a startup `WARN` naming the new one, mark the old one
  deprecated in the metadata, list it under *Deprecated* in the changelog.
- **A new optional field in a store**: default it in the record's compact constructor so older files read
  unchanged; check that an older build dropping it on rewrite does no harm — if it would, that is a layout bump.
- **A layout bump**: raise `DataLayout.SUPPORTED_VERSION`, write the forward migration that runs before
  anything else touches the folder, rewrite the marker after it, document the downgrade consequence in the
  upgrade notes.
- **A backup format bump**: raise `BackupManifest.SUPPORTED_FORMAT_VERSION`, keep reading every older format,
  and say in the changelog which release first writes the new one.
