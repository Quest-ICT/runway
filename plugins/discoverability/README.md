# discoverability — SEO+ & GEO for Runway

One Runway plugin that makes a site legible to **search engines** and **AI answer engines**, sharing a single
schema.org **JSON-LD** core. It adds the layer the platform's lean SEO deliberately leaves out — it **never
duplicates** a tag the kernel already emits (canonical, `hreflang`, `robots`, Open Graph, Twitter cards are
free).

- **SEO+** — JSON-LD structured data (`WebSite` / `WebPage`|`Article` / `Organization` / `BreadcrumbList`) in
  every indexable page's `<head>`; a per-site **`/sitemap.xml`** with `hreflang` alternates.

**Since 1.1.0** the sitemap and `llms.txt` enumerate the host's whole **content catalog** — kernel pages AND
every content source's items (blog entries, any plugin content) — fail-closed re-filtered by the kernel, with the
URLs the kernel computes for the page head too, so head and sitemap provably agree (since 2.0.0 through the
SPI's `ContentCatalogView`). Every item now carries a `<lastmod>` (from its `updatedAt`; pages gain this for the first time).
Content whose URL collides with the reserved surface (e.g. a page at `/login`) is no longer listed.
- **GEO** (opt-in) — **`/llms.txt`** (a compact, machine-friendly page index for LLM/answer engines); a
  `SearchAction` + `Speakable` **answer schema** that enriches the SEO JSON-LD by `@id`.

Everything is per-site: SEO+ on by default, GEO opt-in — no restart to change (per-tenant config applies live).

- **What it does & how it maps to seams:** [`docs/DISCOVERABILITY-PLUGIN.md`](docs/DISCOVERABILITY-PLUGIN.md)
- **How to build any Runway plugin:** [`../docs/PLUGIN-AUTHORING-GUIDE.md`](../docs/PLUGIN-AUTHORING-GUIDE.md)

## Links only the Runway SPI (2.0.0+)

2.0.0 compiles against `runway-spi` alone: settings through `PluginSettings`, the content listing and its URLs
through `ContentCatalogView`, sites through `Sites`, and IndexNow as a `SiteEventListener` that receives each
change with the item's URLs before and after it — so **unpublished, deleted and moved pages are now submitted**,
and only by the node where the change happened. Everything it serves for an unchanged site is byte-for-byte what
1.6.0 served, except three fixes (an item missing from the default locale is linked at its real URL in
`llms.txt`; a root-relative self canonical no longer drops a page from the sitemap; a host-less site's fallback
base is the request's trusted origin). The console bean is now `discoverabilityAdminModel`.

**Upgrade:** no data or setting changes. Details: [`CHANGELOG.md`](CHANGELOG.md),
[`docs/DISCOVERABILITY-PLUGIN.md`](docs/DISCOVERABILITY-PLUGIN.md) §2.

## Absolute JSON-LD on a host-less site; llms.txt revalidates honestly (2.1.0, needs SPI 1.0.0)

A site with neither a base URL nor a host name (the platform's catch-all default) now gets absolute `@id`s and
URLs in its JSON-LD — built on `HeadContext.origin()`, the trusted origin the page is served under — and with them
the GEO `SearchAction` and the breadcrumb trail it never had. `llms.txt`'s `Last-Modified` now advances when only
the site name, `geo.summary` or the base URL changes. **Needs Runway 1.0.0 or later** (SPI `1.0.0`; an older
kernel blocks it with a reason). Details: [`CHANGELOG.md`](CHANGELOG.md).

## A settings page in sections, every field explained (1.6.0+)

The per-site settings page is laid out in six sections — structured data, sitemap & robots.txt, AI crawlers,
GEO, product on the homepage, IndexNow — with a help line under every field (English and Spanish), a
**Learn more** link into the matching section of the help page, rarely needed fields under **Advanced**, and
fields hidden while the switch they depend on is off. A site that turns IndexNow on while the platform has it
off is told so on the form, and the operator gets one WARN per site in the log. No setting changed name, type,
default or meaning. Details:
[`docs/DISCOVERABILITY-PLUGIN.md`](docs/DISCOVERABILITY-PLUGIN.md) §4.

## IndexNow, AI crawlers by class, and a sitemap index (1.5.0+)

**IndexNow (opt-in twice).** Tells Bing, Yandex, Seznam, Naver and Yep when a site's content changes.
**Google does not participate** — this is a freshness signal for those engines, not a ranking one anywhere.

It is the only feature here that makes an outbound request, so it takes two switches, both off by default: the
platform operator permits it (`runway.plugin.discoverability.indexnow.enabled`) and the tenant enables it plus
sets a key. The key is served at `/indexnow-key.txt` and named in every submission via the spec's
`keyLocation`. Submissions are batched, capped, and dropped rather than retried on failure.


**Decline training while staying citable.** The three AI-agent classes — *training*, *live retrieval*,
*user-triggered fetch* — carry opposite consequences, and each now has its own per-site setting
(`robots.ai-training`, `robots.ai-retrieval`, `robots.ai-user`; `inherit` by default, meaning "whatever
`robots.ai-policy` says"). Declining training costs a site nothing it can see; declining retrieval removes it
from generated answers; declining user-triggered fetch breaks a visit somebody initiated. One switch could not
express that.

A site that sets none of them gets a **byte-identical** `robots.txt` to before.

**`/sitemap.xml` becomes an index only when it has to.** Under 10,000 URLs it stays exactly the single
`<urlset>` it always was. Past that it is a `<sitemapindex>` over `/sitemap-1.xml` … `/sitemap-N.xml`, up to 20
shards (200,000 URLs). Shard membership comes from the **sorted** URL list, not source-registration order, so
two nodes behind a load balancer cannot serve different content for the same shard number.

## Per-site settings reach the page ETag (1.4.2+)

`StructuredDataHead` declares its per-site settings file (`plugins/discoverability.yml`) through
`HeadContributor#headInputs`, so the kernel folds it into every page's `ETag`.

Before that, it did not. Head markup is spliced in at the **response edge**, after the page validator has been
computed and possibly answered `304` with no body — so changing the organisation name, the GEO block or the
search URL changed the delivered page and **not** its validator. Every client and CDN holding the old one kept
the previous markup indefinitely, until some unrelated file happened to move; the operator, checking with a
fresh fetch, saw the new value and reasonably concluded it was live.

**If you add a head contributor that reads a file, declare it.** An undeclared input is invisible to the
validator and fails silently, in the direction that looks correct.

## Reproducible structured data (1.4.1+)

The emitted JSON-LD is **byte-reproducible**: the same inputs always render the same bytes, across restarts
and across machines.

That was not true before 1.4.1. `SearchAction.target` was built with `Map.of(...)`, and `java.util.Map.of`
randomizes iteration order **per JVM instance**, so the key order flipped on roughly every other restart —
stable within one run, different after the next. Semantically harmless (the graphs are equal key-sorted), but
it made rendered pages non-reproducible and quietly broke the promise of a page's strong `ETag`, which folds
metadata and templates rather than the body and therefore did *not* change when the bytes did.

**If you extend the schema types: never build a serialized node with a multi-entry `Map.of(...)`.** Use
`Ld.node(...)` or a `LinkedHashMap`. A test enforces this at the source level, deliberately — the behavioural
test alone catches a reintroduction only about half the time, because the defect is probabilistic.

## Build

```bash
export JAVA_HOME=/path/to/jdk-25
mvn verify               # SPI-only: runway-spi + runway-spi-testkit
mvn -P host-it verify    # + the host-integration tests (runway-app, test scope)
```

Produces the thin `target/runway-plugin-discoverability.jar` (running the plugin's unit tests — 178;
the `host-it` profile adds 6 that run the plugin against the real kernel classes) and **both** installable forms:
`target/discoverability/` (folder) and `target/runway-plugin-discoverability-<version>.zip` (the UI-upload archive).

The first build downloads its Maven dependencies, and needs the `runway-spi` jars installed in `~/.m2` as the
[authoring guide §2](../docs/PLUGIN-AUTHORING-GUIDE.md#2-prerequisites) describes (the `host-it` profile needs
`runway-app` there too).

## Install (either path, then restart the host)

> **Upgrading? Do not park the old version inside `external/plugins/`.** The loader scans that directory and
> reads every `plugin.yml`; a parked copy still declares the same `id:`, so the scan sees a duplicate id and
> **fails fast at startup** — the platform refuses to boot because someone kept a rollback copy. Renaming the
> folder does not help; the id inside the descriptor is what collides. Park it outside the tree, or keep the
> release zip.


```bash
cp -r target/discoverability <runway-external>/plugins/     # 1. filesystem
# 2. app console → /__app/plugins → install → upload runway-plugin-discoverability-<version>.zip
```

Loads only in **ACTIVE** mode (after first-run setup). Configure per site on the plugin's tenant settings page.

## Verify (one command)

```bash
scripts/verify-against-host.sh          # build → throwaway Runway → boot ACTIVE → prove the live contract
scripts/verify-against-host.sh --keep   # ... and leave it running (prints the URL)
HOST_JAR=/path/runway-app-<ver>-exec.jar VERIFY_TMPDIR=/dir VERIFY_LOG_DIR=/dir scripts/verify-against-host.sh
PREVIOUS_DIR=/path/to/<previous>/target/discoverability scripts/compare-with-previous.sh   # before/after diff
```

The verifier checks the load (zero host-linkage and bean-name warnings), JSON-LD on `/`, `/sitemap.xml`,
`/robots.txt`, `/llms.txt`, the settings form, the console card and fragment, and IndexNow both off (inert) and on
(an unpublished page's old URLs reach a local fake endpoint). It never runs `mvn install`. Both scripts need
`JAVA_HOME` set to a JDK 25 and use `mvn` from the `PATH` (or `MVN`). Without `HOST_JAR` they use the host exec
jar under `runway-app/target/` of the kernel at the root of this repository (`HOST_TARGET` names another),
matching the host pom's version.

## Try it (after installing + enabling GEO on a site)

- View any public page source → `<script type="application/ld+json">` with the entity graph.
- `GET /sitemap.xml` — the whole published content surface + `hreflang` + `<lastmod>`.
- `GET /llms.txt` — the AI-facing index (when GEO is enabled).

_Java package `mx.quest.discover` · plugin id `discoverability` · version 2.3.0 · built against `runway-spi`
1.0.0 (SPI 1.0.0) · declared SPI floor 1.0.0._

## License

GNU Affero General Public License v3.0 with an additional permission for plugins (`LICENSE`, `LICENSE-EXCEPTION`),
copyright Quest ICT SAS de CV. The permission means that a plugin which uses only the published SPI may carry any
license.
