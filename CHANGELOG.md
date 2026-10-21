# Changelog

All notable changes are recorded here. Versions follow [Semantic Versioning](https://semver.org/).

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
