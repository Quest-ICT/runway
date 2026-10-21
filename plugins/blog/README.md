# blog — a blog for Runway sites

Blog entries as **first-class virtual pages**, built on the host's `ContentSource` seam: each entry has its own URL under a configurable prefix (default `/blog`) — or a per-entry
**custom absolute path** — renders inside the **tenant's own master chrome** through this plugin's shipped
entry templates, follows the site's full language dispatch (per-language slugs, `?lang=` switcher, canonical
redirects), appears in **sitemap.xml / llms.txt** with `hreflang` + `<lastmod>`, and is linkable from any
tenant page as `${urls['blog:<id>']}`. All of that is host-provided; this plugin only stores entries and
answers the source contract.

**The plugin links only the Runway SPI** (service tier, SPI `1.0.0`) — no kernel-internal class. The full design
and reference is [`docs/BLOG-PLUGIN.md`](docs/BLOG-PLUGIN.md); the release notes are in
[`CHANGELOG.md`](CHANGELOG.md); `scripts/verify-against-host.sh` checks it end to end against a packaged kernel.

- **Storage** — entries live in the *tenant's* folder: `sites/<siteId>/plugins/blog/entries/<id>/entry.yml`
  (metadata) + `body-<lang>.md` (one Markdown body per language), through the kernel's `PluginSiteFiles` (confined
  to the plugin's subtree). Tenant backups carry the whole blog
  automatically; the metadata/body file split keeps enumeration body-free (the source contract).
- **Authoring** — the tenant console gains a **Blog** screen (`/__admin/blog`, gated by the grantable
  `blog|manage` control point): list, create, edit (per-language title/description/slug/body), publish/
  unpublish, delete — each a CSRF-checked `POST`. Every mutation is announced with `ContentChanges`, so routing,
  sitemap and search follow on the next request; the editor warns when a published **page owns an entry's
  address** (the page wins — the platform's precedence rule), and refuses a custom path the kernel would not serve.
- **Settings** (per tenant, applied immediately): `enabled`, `prefix`, `template`
  (`entry-classic` — title, date line, back link — or `entry-minimal` — title + body only).
- **Search** — entries declare the kernel's `content.indexable-text` capability and serve their
  body through it (rendered Markdown→HTML, the same transform the page render uses, so the engines' HTML
  stripper yields clean prose — raw Markdown syntax would leak into index terms and snippets). A search engine
  that indexes the content catalog (the `search` and `search-lucene` plugins) finds entries by body text,
  title and description — first-class search citizens, no blog-specific code in any engine.
- **Markdown** — rendered with [commonmark-java] (bundled in this plugin's `lib/`, private to this plugin).
  Entry Markdown is operator-authored trusted content: raw HTML passes through, exactly as a page author may
  write markup.

## Bylines and publication dates

Each entry carries its own **author** and **publication date**, and both travel across the SPI as that
entry's own values — so the discoverability plugin emits a truthful per-post `author` and `datePublished` in
`Article`/`BlogPosting` structured data, and the entry template renders a byline.

Without them, authorship and dates could only come from the per-site `content.schema` block, which matches by
path prefix — one `/blog/*` entry covering *every* post. The only honest options would be to emit nothing, or to
assert a single author and date identically across all posts: false of nearly every page carrying it, and
exactly the kind of untrue markup structured-data policy prohibits.

Notes:

- Both fields are **optional**. An unattributed entry renders no byline and asserts no author — absence is a
  normal state, never a placeholder.
- The byline element appears when the entry has an author **or** a date, and each part renders independently,
  so an entry with one but not the other is handled correctly.
- `author` is a free-text display name, matching the kernel's page-level author. A real identity model is a
  larger design; blocking bylines on it would leave content unattributable meanwhile.

## Publication dates

Each entry's `publishedOn` date is carried across the SPI as that entry's own `publishedAt`, so the
discoverability plugin can emit a **truthful per-post `datePublished`** in `Article`/`BlogPosting`
structured data.

Notes:

- `publishedOn` is free-form display text. A value that is not an ISO `yyyy-MM-dd` date yields **no**
  `datePublished` rather than a guess — asserting a wrong date is worse than asserting none, because a
  consumer is told to trust it.
- Dates are interpreted at **midnight UTC**, matching how the kernel's page editor parses its own date
  field, so a blog entry and a page dated the same day produce the same instant.

## Requirements

**Needs Runway 1.0.0 or later** (SPI `1.0.0`). The descriptor declares `requiresSpi: ">=1.0.0"`, so an older
kernel blocks this plugin at startup with a reason and still boots its other plugins. Upgrade the kernel first, then
the plugin.

## Build

```bash
mvn package
```

`target/blog/` (the install-ready folder) and `target/runway-plugin-blog-<version>.zip` (the UI-upload
archive), where `<version>` is the `version:` in `plugin.yml`.

The first build downloads its Maven dependencies, and needs the `runway-spi` jars installed in `~/.m2` as the
[authoring guide §2](../docs/PLUGIN-AUTHORING-GUIDE.md#2-prerequisites) describes.

## Install (either path, then restart the host)

```bash
# 1. cp -r target/blog <external>/plugins/
# 2. app console → /__app/plugins → install → upload runway-plugin-blog-<version>.zip
```

## Verify

```bash
mvn verify                                      # 67 unit tests, incl. the no-host-linkage bytecode scan
JAVA_HOME=… HOST_JAR=…/runway-app-<ver>-exec.jar VERIFY_TMPDIR=… scripts/verify-against-host.sh   # 56 end-to-end checks
```

## Try it

1. Console → **Blog** → **New entry**: id `hello-world`, tick a language, type a title and a Markdown body,
   tick **Published**, Save.
2. Visit `https://<site>/blog/hello-world` — the entry wears the site's chrome.
3. `GET /sitemap.xml` — the entry is listed with `hreflang` alternates and `<lastmod>`.
4. Give a **page** the same path — the page serves, the blog editor and the Pages screen both warn.

_Java package `mx.quest.blog` · plugin id `blog` · built against `runway-spi` 1.0.0 · declared SPI floor
`>=1.0.0`._

[commonmark-java]: https://github.com/commonmark/commonmark-java

## License

GNU Affero General Public License v3.0 with an additional permission for plugins (`LICENSE`, `LICENSE-EXCEPTION`),
copyright Quest ICT SAS de CV. The permission means that a plugin which uses only the published SPI may carry any
license.
