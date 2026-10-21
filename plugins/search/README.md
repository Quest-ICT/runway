# Runway Search plugin

On-site search for [Runway](../README.md) sites: an embeddable **search box**, an installable, editable
**`/search`** results page (created `noindex` — the canonical opt-out for search results), a **`/search/api`**
JSON endpoint, and a built-in **full-text** engine over the site's published content — your pages AND any
content source's items (blog entries, product pages, …), titles, descriptions and bodies alike — backed by the
plugin's own SPI, so a companion plugin (`../search-lucene`) can upgrade the engine with no change to the search UX.
Since 2.0.0 it links **only the Runway SPI** (needs a kernel whose SPI is `1.0.0` or later).

- **Plugin id:** `search` — install folder `external/plugins/search/`
- **Java package:** `mx.quest.search` · **Published SPI:** `mx.quest.search.spi.SearchCorpus` (what every engine
  indexes) and `mx.quest.search.spi.SiteSearchEngine` (the engine extension point)

## What it adds

| Surface | Where |
|---|---|
| Search box widget | `<div id="x" data-widget="search/box"></div>` on any page/master |
| Results page | an installed, editable tenant page at `/search` carrying the query-aware box widget (created `noindex`; styled by `/__plugin/search/static/search.css`); installed through the kernel's `SitePages`, so it follows the site's editorial level (published, waiting for review, or a draft the quality checks blocked) |
| JSON API | `GET /search/api?q=…` → `{query,count,results:[{url,title,snippet,score,link,terms}]}` (rate-limited per client IP as the kernel's trusted-proxy gate resolved it, `noindex`) |
| Corpus | `SearchCorpus`: every published page and content item of the kernel's `ContentCatalogView`, minus the site's exclude list — what the built-in engine and `search-lucene` both index |
| Built-in engine | full-text match over each item's title + description + **body**, in the item's own language, linked at its real URL |
| Highlighted snippets | `<mark>`-wrapped, length-bounded, XSS-safe (escape-then-mark) |
| Highlight on the result page (2.1.0) | a result link carries the matched words (`#rw-hl=…`); the page it opens highlights them and scrolls to the first (CSS Custom Highlight API, restyle with `::highlight(rw-search)`); per-site switch `highlight` |
| Box only in the chrome (2.1.0) | a header/footer box no longer repeats the results on the results page; per-placement `results` setting (`auto`/`always`/`never`) on Site → Content widgets |
| Instant freshness | any page write, content-source change, site lifecycle event or plugin settings save drops that site's index at once (the plugin's `SiteEventListener`); the TTL is only the backstop |
| Operator actions | the **Rebuild index** and **Install search page** buttons and **Clear list** — CSRF-checked `POST /search/reindex`, `/search/install-page`, `/search/no-results/clear`, gated by the `search|settings` control point |
| Searches with no results | opt-in per site (**off by default** — terms can be personal data): term + count + last seen, bounded (500 terms, 90-day retention by default), never an IP or a user; listed on the settings page with a Clear action; **declared personal data**, so a site's transfer copy leaves it out |
| App settings | index freshness + per-client rate limit (`runway.plugin.search.*`) |
| Per-site settings | enable, max results, results page path, placeholder, exclude list, snippet length, highlight on the result page, ranking weights, no-result recording + its retention |
| Console | a tenant dashboard card + an admin status panel (searchable items, active engine, the two buttons) + the no-result searches card |

## Build

```bash
export JAVA_HOME=/path/to/jdk-25
mvn -q verify
#   → target/runway-plugin-search.jar
#     target/search/                        (cp -r into <external>/plugins/ and restart)
#     target/runway-plugin-search-2.3.0.zip (upload at /__app/plugins → install)
HOST_JAR=/path/to/runway/runway-app/target/runway-app-<version>-exec.jar \
  JAVA_HOME=/path/to/jdk-25 scripts/verify-against-host.sh          # boots the PACKAGED host and proves the contract live
```

The first build downloads its Maven dependencies, and needs the `runway-spi` jars installed in `~/.m2` as the
[authoring guide §2](../docs/PLUGIN-AUTHORING-GUIDE.md#2-prerequisites) describes.

The build is reproducible: two builds of the same source give a byte-identical zip.

See [`docs/SEARCH-PLUGIN.md`](docs/SEARCH-PLUGIN.md) for the design and the SPI contract.

## License

GNU Affero General Public License v3.0 with an additional permission for plugins (`LICENSE`, `LICENSE-EXCEPTION`),
copyright Quest ICT SAS de CV. The permission means that a plugin which uses only the published SPI, including
search's own SPI for engines, may carry any license.
