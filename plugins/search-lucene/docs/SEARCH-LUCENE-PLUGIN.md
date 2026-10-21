# Runway Search — Lucene engine — design & reference

> The companion engine for the `../search` plugin, and the workspace's reference for **two** hard-to-get-right
> plugin patterns at once: implementing another plugin's **published SPI** (`requires:`), and **consuming a
> shared library-provider plugin** (`../lucene-provider`) instead of bundling the library privately. Grounded in
> the Runway kernel as built. Since 3.0.0 it links **only the Runway SPI and the search plugin's SPI**.

## 1. What it does

Implements `search`'s `SiteSearchEngine` SPI with Apache Lucene. Where the built-in engine substring-matches,
this one builds a full-text index over each searchable item's **title**, **description**, and **body text**
(extracted from the item's indexable material via `HtmlText`), analyzes queries with a **per-locale stemming
analyzer**, and ranks with a **tiered constant-score** model layered on Lucene's BM25. It registers at
`priority()` 100, so the shared search box and the results page transparently use it wherever it is installed —
and fall back to the built-in engine if this plugin is removed.

What it indexes is the search plugin's **`SearchCorpus`** (search 2.0.0): every published page and content-source
item of the kernel's listing **minus the site's search exclusions**. The built-in engine builds from the same
corpus, so which items are searchable never depends on which engine serves the query, and the `exclude` rule lives
in exactly one place — this plugin never reads the search plugin's settings.

## 2. The two `requires:` gates and the SPI floor

`plugin.yml` declares `requires: [ "search >= 2.2.0", "lucene-provider == 10.4.0" ]` and `requiresSpi: ">=1.0.0"`:

- **`search >= 2.2.0`** publishes the `SiteSearchEngine` interface this plugin implements and the `SearchCorpus` it
  indexes (both in `mx.quest.search.spi`). This plugin compiles against the search plugin as a `provided`
  dependency from `~/.m2` (installed from `../search`; never bundled). Both plugin jars load into ONE shared classloader, so the SPI types have a single `Class` identity and
  Spring collects this engine into `search`'s `List<SiteSearchEngine>`. `>=` (not exact) because the search SPI is
  API-stable — it grows by `default` methods and kept constructors only; **2.2.0** is the floor (since 3.2.0)
  because this plugin keeps one index per language, which a visitor reaches only when the search box on a
  localized page submits to the results page in that language — search 2.2.0 does that, while 2.1.x always
  submitted to the default-language results page. (3.1.x needed 2.1.0, the first with the `SearchHit`
  constructor that carries the matched words; 2.0.0 was the first to publish `SearchCorpus`.) Only the `spi` package is a contract: a build-time test fails if this plugin links any
  other search class.
- **`lucene-provider == 10.4.0`** is the standalone code-less provider (`../lucene-provider`) that places the
  single deployment-wide copy of Lucene on the shared classloader. **`==` (exact)** because this plugin is
  **compiled against the 10.4.0 binaries** — a different Lucene could be binary-incompatible.
- **`requiresSpi: ">=1.0.0"`** — the frozen 1.x contract, which carries the services it uses: `PluginSettings`
  (0.4.0), `Sites` (0.5.0), `SiteEventListener` and `CatalogEntry` (0.6.0).

If a gate is unsatisfied (missing/disabled/wrong version, or an older kernel), the host **does not load** this
plugin (skipped with a reason, shown "Blocked" in the console) — so the SPI types AND every Lucene class are
guaranteed present when this plugin's beans wire.

## 2a. What it uses, and nothing else

| Need | Published service |
|------|-------------------|
| what to index, its indexable text, the manual refresh | `SearchCorpus.corpus` / `indexableText` / `refresh` (search) |
| a site's default locale (the analyzer) | `Sites.find` (kernel SPI) |
| the per-site ranking knobs | `PluginSettings.site` (kernel SPI; descriptor defaults applied under the stored values) |
| drop an index when content changes | `SiteEventListener.onCatalogChanged` (kernel SPI; one `CONSUMER` listener) |
| app-level storage settings | its own `@ConfigurationProperties` bean under `runway.plugin.search-lucene` |

Beans: `searchLuceneStorageProps`, `searchLuceneEngine`, `searchLuceneSiteEvents`, `searchLuceneAdminModel` — every
name starts with the plugin id in lowerCamelCase (the kernel's rule for the bean namespace plugins share).
`SearchLuceneConfigTest` scans the compiled classes' constant pools: nothing of `mx.quest.runway` outside `.spi`,
nothing of `mx.quest.search` outside `.spi`, no Caffeine.

## 3. Graduated from private bundling to the provider

search-lucene **used to** bundle Lucene privately in its own `lib/` (it was the only consumer). Lucene has
**graduated** to the standalone `lucene-provider` (whose version IS the Lucene version):

- `pom.xml` now marks `lucene-core` + `lucene-analysis-common` + `lucene-highlighter` (and their transitives
  `lucene-memory` + `lucene-queries`) as **`provided`** — compile-only, supplied by the provider at runtime.
- **Nothing** is copied into this plugin's own `lib/`. The staged install folder is the thin jar + templates +
  i18n only; a `clean package` verifies no Lucene jar appears in `target/search-lucene/`.
- The search jar is compile-only too (never bundled — a second `Class` identity would break by-type SPI
  collection).

One Lucene version deployment-wide; no silent classpath clash. On a Lucene upgrade, bump `../lucene-provider` and
this plugin's `${lucene.version}` + `requires:` pin together, then re-cut.

## 4. Ranking model

`buildQuery` composes a **tiered, additive constant-score** query — the tiers are `SHOULD` clauses, so a hit
scores the **sum** of the tiers it satisfies, giving a deterministic ordering:

| Tier | What it matches | Score |
|------|-----------------|-------|
| any-terms | each term across title (× `titleBoost`), description (× `descriptionBoost`), body; **+ fuzzy** clauses when `fuzzy` is on | BM25 (× boosts) — the base layer that separates hits *within* a tier |
| near phrase (body) | the query as a body `PhraseQuery` with slop 1 (lightly reordered / 1 gap) | constant `tierNearPhrase` |
| exact phrase (body) | the query as an exact (slop 0) body phrase | constant `tierPhraseBody` |
| exact phrase (title) | the query as an exact title phrase | constant `tierPhraseTitle` |
| all-terms-anywhere | every term appears in some field (`MUST` across fields) | constant `tierAllTerms` |

Defaults reproduce the historical ranking constants (`tierPhraseTitle 4000 > tierPhraseBody 2000 > tierNearPhrase
800 > tierAllTerms 300`; `titleBoost 8`, `descriptionBoost 4`; `fuzzy` on, `fuzzyBoost 0.4`). The phrase tiers
are skipped for a single-term query (a lone term IS its own phrase — no double-count).

- **Fuzzy** (`FuzzyQuery`): typo tolerance with a length-aware edit budget (`maxEdits`: 0 for ≤2 chars, 1 for
  ≤5, 2 beyond), fixed 1-char prefix, transpositions on. Fuzzy operates on the **analyzed (stemmed)** term.
- **Per-locale analyzers** (`LocaleAnalyzers`): a Lucene language `Analyzer` (with that language's stopwords +
  stemmer) chosen by the **site's default locale** where one exists (en, es, fr, de, it, pt, ru, …), else
  `StandardAnalyzer`. The **same** analyzer is used at index and query time — stemming is analysis-time, so the
  two must never drift (or a stemmed query term would never match a stemmed indexed term).
- **Highlighting** (`UnifiedHighlighter` + `DefaultPassageFormatter`): a query-focused body passage with hits
  wrapped in `<mark>…</mark>`. Private control-char markers are used internally; the passage is **HTML-escaped
  first, THEN** the markers become real `<mark>` tags — so page content can't inject markup while the highlight
  tags survive. Falls back to the description / a leading body slice when there is no highlightable passage.
  This is the `SearchHit` snippet contract (escape everything, wrap only hits in `<mark>`), and the search surface
  renders the snippet as raw HTML on that basis.
- **Matched words** (since 3.1.0, `MatchedForms`): each returned hit also carries `SearchHit.terms`, the words the
  query matched **as the page writes them**, which search 2.1.0 hands to the page the result opens for
  highlighting. The stored title, description and body go through the same analyzer; a token whose term is a query
  term (or, with `fuzzy` on, within the fuzzy edit distance, same rule as the clauses) is kept as the text its
  offsets cover. So `maestría` (term `maestr`) reports `maestrías` from the page. At most 20 words; the first
  64 KiB of each text are read, so a long page costs a fixed amount per hit. Stop words match nothing and are never
  reported.

## 5. Index lifecycle & storage

- **Source:** `SearchCorpus.corpus(siteId)` — every published item minus the site's exclusions — and
  `SearchCorpus.indexableText(siteId, entry, locale)` for its body material (stripped to text) — the page
  as rendered in that language, so strings from the site's bundles are in it.
- **One index per language (since 3.2.0).** Each item is indexed once per language it exists in: title,
  description, body in that language, and the hit's URL `CatalogEntry.pathFor(locale)` — its real address there.
  A site has one index per language it speaks, analyzed with **that language's analyzer**: the language's own
  documents plus, for an item that does not exist in it, the item's **primary-locale** document (the site default
  when the item has it, else its own first language), so a page published in one language only is still found
  from every other. A query searches its own language's index (`SearchQuery.locale()` — the results page's
  language, or the API's `?lang=`) with that analyzer; a language the site does not speak searches the site
  default's. A fallback document is analyzed with the index's analyzer, not its own language's, so it matches on
  exact and lightly stemmed forms rather than every inflection. Before 3.2.0 a site had one index of
  primary-locale documents with the default language's analyzer, so every language searched default-language
  text. The console's count is of items, not per-language documents.
- **Store (app-level `settings:`, `LuceneStorageProps`, restart-only — a PLATFORM concern, not per-site, because
  a tenant-editable path is an isolation/abuse risk):**
  - `heap` (default) — an in-memory `ByteBuffersDirectory` per language; rebuilt on first use after boot.
  - `disk` — a memory-mapped `MMapDirectory` per language under `indexRoot/<siteId>/<language>` (since 3.2.0;
    the site id and the language are validated and the resolved path is asserted to stay under the normalized
    index root — traversal-safe). The index files a 3.1.x build left directly under `indexRoot/<siteId>` are
    deleted on the first rebuild — only files Lucene names (`segments*`, `write.lock`, codec files `_*.*`), once
    per site per process, so a misdirected `indexRoot` loses nothing else.
- **Freshness:** each index is **cached per site** and dropped by the plugin's `SiteEventListener`
  (`LuceneSiteEvents`, phase `CONSUMER`) on **every** catalog change of the site — a page write, a content source's
  change, a site lifecycle event, any plugin's settings save (the search plugin's `exclude` list and a source's
  own settings both reshape the corpus). The kernel delivers it after its own caches are evicted and after every
  content source has dropped its state, so the next query rebuilds from the new content. A build that a change
  overtook (it may have read the old corpus) is kept as already stale: it answers the query that built it, and the
  next query rebuilds. A 30 s TTL remains as the backstop for changes no event announces, and the SPI's
  `reindex(siteId)` hook (the operator's rebuild-index button) refreshes the kernel's listing through
  `SearchCorpus.refresh`, then drops and rebuilds eagerly, returning the fresh document count. The build is
  rebuild-from-scratch (`deleteAll` then re-add), so a disk index is never left stale.
- **Safety:** `search(...)` **never throws** — a Lucene/store/corpus failure logs and returns no results, so the
  query surface degrades to "no results", never a 500. Every surface is scoped to the queried `siteId`.
- **No personal data.** The index is derived from published content and lives in heap or under `indexRoot`, not in
  the site folder; the plugin declares no `siteData.personal`.

## 6. Console & settings

- **App-level** `settings:` — `storage` (enum heap/disk) + `indexRoot` (string). Superadmin-only; applied on
  restart.
- **Per-tenant** `siteSettings:` — the 8 ranking knobs (`fuzzy`, `fuzzyBoost`, `titleBoost`, `descriptionBoost`,
  `tierPhraseTitle`, `tierPhraseBody`, `tierNearPhrase`, `tierAllTerms`), read per query (a save applies to the
  next query) through `PluginSettings`. The editor is gated by the `settings` control point. `decimal` ignores `range`
  (authoring guide §4.1), so `RankingConfig` defaults + clamps every value defensively.
- A dashboard card + admin fragment (bean `searchLuceneAdminModel`) show the active engine key, the site's indexed
  item count, the storage mode, and the effective title boost.

## 7. Verify

- **Offline unit tests** (`mvn verify`, 72 tests, on the `runway-spi-testkit` fakes and search's own
  `SiteSearchCorpus`): SPI contract (never-throws, empty index, unsafe/unknown site, limit, tie order), the search
  plugin's exclusions reaching the index through its corpus, the primary-locale title/body/URL, one index per
  language (`LucenePerLanguageTest`: each language's own text, analyzer and address, the primary-locale fallback,
  an unknown language, the item count, the per-language disk folders, only Lucene-named 3.1.x files deleted, a
  search from inside the site's own build answering nothing), `reindex`
  (listing refresh + immediate rebuild inside the TTL), the listener dropping the index for every cause (page,
  content, settings of another plugin, lifecycle, a replayed change) and only for its site, the mid-build change,
  tiering (exact phrase > scattered; title phrase > body phrase; the documented tier sum), fuzzy on/off applied on
  the next query, per-locale stemming (English `running`↔`run`; Spanish `producto`↔`productos`; unknown locale →
  StandardAnalyzer), heap and disk answering identically, `<mark>` highlighting (incl. page-markup escaping), each
  tuning knob, defensive config parsing, and the contract test (wiring from published services only, bean names,
  template bean references, descriptor floors and version, code fallbacks = descriptor defaults, i18n, the
  constant-pool scan).
- **Live** (`scripts/verify-against-host.sh`; honours `HOST_JAR`, `VERIFY_TMPDIR`, `VERIFY_LOG_DIR`; packages
  everything offline and never `mvn install`s): boots the packaged host twice on the same fixture site — first
  search 2.0.0 + search-lucene 3.0.0 (built from git at `BASELINE_REF`, a commit carrying those two versions; when
  `BASELINE_REF` is unset, the default, or `SKIP_BASELINE=1`, the script prints a note and runs only the checks on the
  current pair), then the current search + this release with the index on disk — and asserts that twelve queries
  answer **byte-identically** (order, URLs, titles, snippets, scores) once the `link` and `terms` fields 3.0.0 never had are set aside; those it checks on
  their own: each hit carries its marked link and at least every word its snippet marks. (Until 3.1.0 the baseline
  was 2.2.1, which a Runway 1.0 kernel blocks for linking kernel internals; 3.0.0 had matched it byte for byte.) On
  the current pair it asserts `3 registered, 0 blocked` with zero host-linkage and bean-name/surface warnings, the
  console card and admin fragment, a save of search's `exclude` list and of this plugin's knobs reaching the next
  query without a reindex, and the rebuild-index POST reporting this engine's count.

## 8. Log messages you can ignore

### `Java vector incubator module is not readable`

Once per start of the host, the first time Lucene is used (usually the first search, on a request thread), the log
shows:

```
WARN … o.a.l.i.v.VectorizationProvider : Java vector incubator module is not readable. For optimal vector performance, pass '--add-modules jdk.incubator.vector' to enable Vector API.
```

Lucene prints it (the library the `lucene-provider` plugin supplies). Nothing is wrong. **Leave it off**, for three
reasons.

- The flag makes Lucene's vector maths faster. Lucene needs that mainly for k-nearest-neighbour *vector* search.
  Runway's search is keyword search (terms, phrases, typo-tolerant matches) and uses no vectors.
- A site's index is small (its published pages and items), so there is no time to win.
- `jdk.incubator.vector` is an *incubator* module, a JDK API that is not final. With it, the JVM prints
  `WARNING: Using incubator modules: jdk.incubator.vector` at every start, and a later JDK may change or drop the
  module.

If you want it anyway, add `--add-modules jdk.incubator.vector` to `JAVA_OPTS` in a systemd **drop-in** under
`/etc/systemd/system/runway.service.d/`. Never edit the main unit, `runway.service`: the installer rewrites it at
every upgrade. `Environment=` sets the whole variable, so the line must also carry your heap flags:

```ini
# /etc/systemd/system/runway.service.d/20-heap.conf
[Service]
Environment="JAVA_OPTS=-Xms256m -Xmx1g --add-modules jdk.incubator.vector"
```

Then run `sudo systemctl daemon-reload && sudo systemctl restart runway`. The installer's `--heap` rewrites
`20-heap.conf` whole and drops the flag, so add it again after each `--heap`. Runway's administration manual
(§15, *Memory tuning*) explains the drop-ins.
