# Runway plugins

This folder holds **Runway plugin projects**. Each plugin is a **standalone project** (its own build) that
compiles against the Runway SPI only (`mx.quest:runway-spi`, plus `runway-spi-testkit` for its tests) from the local Maven repository and deploys as a
folder under a Runway install's `external/plugins/<id>/`. The contract is **SPI `1.1.0`** (Runway `1.1.0`; every `1.0.0`
plugin still loads): the kernel blocks a plugin that links its internal classes. The SPI jars are not yet published to a Maven repository — see the authoring
guide, §2.

Each Runway release on the releases page (<https://g.quest.mx/quest/runway/releases>) carries the zips of these
plugins built for it, beside the kernel's own files and listed in the same `SHA256SUMS`.

## Layout

```
plugins/
├── docs/PLUGIN-AUTHORING-GUIDE.md   # THE canonical guide: build any Runway plugin from scratch
├── demo/                            # the reference "kitchen-sink" plugin — exercises EVERY seam
├── blog/                            # a blog: entries as virtual pages via ContentSource, in the site's own chrome
├── carousel-manager/                # an image/slide carousel content widget, slides configured per site
├── contact/                         # a contact form widget: emails each submission and stores it for the console
├── discoverability/                 # SEO+ & GEO: sitemap.xml, robots.txt, llms.txt and schema.org JSON-LD
├── search/                          # on-site search: box widget + /search + /search/api + a published SiteSearchEngine SPI
├── search-lucene/                   # a Lucene engine for `search` (requires: search + lucene-provider; per-locale analyzers, heap or disk)
├── lucene-provider/                 # CODE-LESS library provider: bundles Apache Lucene under lib/ (no mainClass)
├── database/                        # plugins' own data in PostgreSQL or MariaDB: schema per site, migrations, backups
├── database-postgresql/             # the PostgreSQL provider for `database` (bundles pgjdbc)
├── database-mariadb/                # the MariaDB provider for `database` (bundles MariaDB Connector/J)
└── runway-demo-database/            # the worked example of a database consumer: a per-site guestbook
```

The essential **protection** plugin is not here: it is `runway-plugin-protection` at the root of the repository,
and ships inside the Runway installer.

Each plugin carries a `README.md` and a `plugin.yml` (and, when it has code, its unit tests), and versions independently (its
version is in its `plugin.yml`). Each also carries a `docs/<NAME>-PLUGIN.md`, and most a
`scripts/verify-against-host.sh` that checks it against the packaged host. Three do not have their own verify script:
**`lucene-provider`** is a code-less library bundle with no code of its own to verify, and the two database
providers (**`database-postgresql`**, **`database-mariadb`**) are verified by `database/scripts/verify-against-host.sh`,
which installs both.

## Start here

- **Building a new plugin?** Read [`docs/PLUGIN-AUTHORING-GUIDE.md`](docs/PLUGIN-AUTHORING-GUIDE.md) — it covers
  the project setup, the `plugin.yml` descriptor, every SPI, settings, permissions, resources, build/install,
  and the pitfalls. Then **copy the closest existing plugin** as your starting template and strip what you don't
  need.
- **Reference plugins by pattern:**
  - Every seam at once → [`demo/`](demo/).
  - Publishing your **own** SPI for other plugins → [`search/`](search/) (`SiteSearchEngine`).
  - Implementing another plugin's SPI (`requires:`) + **consuming a library-provider** (Lucene) → [`search-lucene/`](search-lucene/).
  - A **code-less library-provider** plugin (bundle a shared library under `lib/`, no `mainClass`) →
    [`lucene-provider/`](lucene-provider/) (Apache Lucene) — consumed by a `requires:` plugin (`search-lucene`).
  - Routable content of your own (virtual pages in the site's chrome, sitemap and search included) →
    [`blog/`](blog/) (`ContentSource`).
  - A public form that posts, mails and stores tenant data → [`contact/`](contact/).
  - Server routes and `<head>` markup for a whole site (`EndpointContributor`, `HeadContributor`) →
    [`discoverability/`](discoverability/).
  - A self-contained content widget with its own static assets → [`carousel-manager/`](carousel-manager/).
  - Keeping your data in a database (PostgreSQL or MariaDB) → [`runway-demo-database/`](runway-demo-database/), on
    [`database/`](database/) (authoring guide §7.14).

## Conventions

- Each plugin project is self-contained, with no external dependencies beyond the host artifacts in `~/.m2`. The
  authoring guide lives at the top level (`docs/PLUGIN-AUTHORING-GUIDE.md`) and nowhere else is current:
  `demo/docs/PLUGIN-AUTHORING-GUIDE.md` is only a pointer to it.
- Plugin **id** (and its install-folder name) is short and path-safe (e.g. `runway-demo`); the Maven artifactId
  is **`runway-plugin-<id>`** (so `runway-plugin-runway-demo`), because the release zip is named after it,
  `runway-plugin-<id>-<version>.zip`; the Java package lives **outside** `mx.quest.runway`.
- Build with JDK 25 (point `JAVA_HOME` at it) and Maven.
