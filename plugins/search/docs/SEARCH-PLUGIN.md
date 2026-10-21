# Runway Search plugin — design & reference

> On-site search as a **query surface + a swappable engine over one corpus**. `search` publishes its own SPI —
> `SearchCorpus` (what every engine indexes) and `SiteSearchEngine` (the engine extension point); `../search-lucene`
> implements the second and reads the first. Since 2.0.0 the plugin links **only the Runway SPI** (service tier,
> SPI `1.0.0`). See the shared `../docs/PLUGIN-AUTHORING-GUIDE.md` and
> [`docs/PLUGIN-DEVELOPMENT.md`](../../../docs/PLUGIN-DEVELOPMENT.md) at the root of the repository for the seams.

## 1. What it does

- **Query-aware search box** — a `ContentWidget` (`key = "search"`). A site manager drops
  `<div id="site-search" data-widget="search/box"></div>` on any page or the master chrome; the widget renders a
  GET form that submits `?q=` to the site's results page (the `resultsPath` setting, default `/search`). It is
  **query-aware** (kernel `WidgetView.query()`): when the page carrying it is opened with a non-blank `q`, it
  renders the ranked results **inline below the box** (referencing the plugin's own `/__plugin/search/static/search.css`,
  which a site overrides to match its theme). So the results page is a **normal, editable tenant page**, not a
  plugin-owned surface. Its words come from `Messages.site` (the site's bundle first, then this plugin's) for the
  request locale. **Where results are listed (2.1.0):** each placement has a `results` setting (Site → Content
  widgets, `widgetSettings` in `plugin.yml`): `auto` (the default) lists them unless the box sits in the **master
  chrome** (`WidgetView.isChrome()`), so a header or footer box does not repeat the results page's list; `always`
  and `never` override that. Before 2.1.0 every box on a page opened with `?q=` listed the results.
- **Result highlighting (2.1.0)** — the page a result opens highlights the words the search matched there and
  scrolls to the first visible one. How it travels:
  1. The engine reports the matched words **as the page writes them** (`SearchHit.terms`, §2): the built-in engine
     the whole words around each match, `search-lucene` the surface forms its analyzer matched (stemming and typo
     tolerance included: `maestría` finds `maestrías`).
  2. Each result link (inline results and the API's `link`) carries them in the fragment:
     `/mba#rw-hl=maestr%C3%ADas%20maestr%C3%ADa` (`HighlightMarker`). A fragment never reaches the server, so the
     target page is served and cached exactly as without it.
  3. `SearchHighlightHead` (a `HeadContributor`) puts one `<script src=".../highlight-loader.js" defer>` on every
     page of a site with search and `highlight` on. The loader (~500 bytes) does nothing unless the address carries
     the marker; then it loads `highlight.js`, which highlights every whole-word occurrence (ignoring case) inside
     `<main id="content">` (else `<main>`, else `<body>`) with the **CSS Custom Highlight API** (no DOM change), at
     most 200, scrolls to the first visible one, and removes the marker with `history.replaceState`. A
     `hashchange` re-runs it. It adds `highlight.css` before the site's own stylesheets, so a site restyles it with
     its own `::highlight(rw-search) { … }`.
  - **Why an external loader and not an inline script:** the kernel stamps the CSP nonce only on scripts its host
    allowlist would also run, so an inline script stays blocked under `enforce`. **Cost:** the loader is referenced
    by every page. A browser keeps it for an hour (`runway.http-cache.plugin-assets`) and then revalidates it
    against its ETag. Every plugin file URL ends in `?v=<version>` (`SearchConfig.VERSION`), so the next release's
    files are fetched at once. Turning `highlight` off removes the loader.
  - **Browsers:** the Highlight API is in Chrome/Edge 105+, Safari 17.2+ and Firefox 140+. Elsewhere nothing is
    highlighted and the marker is still removed.
  - **Limits:** a match split across inline elements (`<b>mae</b>stría`) is not found. Text outside the content
    slot is not highlighted, by design: with no `#content` or `<main>`, the script searches `<body>` but skips
    `<header>`, `<nav>`, `<footer>` and `<aside>`, so it never scrolls to a menu item. Java lowercases the Turkish
    capital `İ` to `i` plus a combining dot, which a browser never matches case-insensitively, so the script lets
    every `i` match `İ` (`src/test/js/highlight-pattern.js`).
- **JSON API** — `GET /search/api?q=…` (`EndpointContributor`, tenant realm, `noindex`) returns the results as
  JSON (`{query,count,results:[{url,title,snippet,score,link,terms}]}`) for a site's own scripts (e.g. an
  autocomplete box). `link` (2.1.0) is the URL with the highlight marker (the plain URL when `highlight` is off);
  `terms` the matched words as plain text.
  Rate-limited per client (`SearchRateLimiter`, §4). Every string is JSON-escaped and the angle brackets are
  unicode-escaped so a payload embedded in an HTML `<script>` can't break out. (The HTML results rendering lives
  in the widget; rendered during page render it carries no search-specific limiter — the kernel's edge
  protection and `resultLimit` bound a flood — while the JSON API keeps its dedicated limiter.)
- **The corpus** — `SearchCorpus` (§2): the kernel's content listing (`ContentCatalogView.entries`: every
  **published** real page and every content source's item — blog entries, … — re-filtered fail-closed by the
  host, with host-computed URLs) **minus the site's `exclude` list and its results page** (the page at
  `resultsPath`, in all its languages; since 2.1.1, because it matched the subjects it describes and was listed
  among its own results). Every engine builds from it, so the exclusion rule lives in one place.
- **Built-in engine** — `SimpleSearchEngine`, a dependency-free full-text matcher. Since 2.2.0, **one document per
  corpus entry and language it exists in**: localized **title**, **description**, and the plain-text **body**
  (`SearchCorpus.indexableText` for that language — the page as rendered there, bundle strings included —
  reduced by `HtmlText`), linked at `CatalogEntry.pathFor` that language — the item's real address there.
  A query searches the documents of its own language (`SearchQuery.locale()`: the results page's
  language, or the API's `?lang=`) plus, for an item that does not exist in that language, the item's
  **primary-locale** document (the site's default language when the item exists in it, else its first language),
  so a page in one language only is still found from every other; a language the site does not speak searches the
  site default's documents. (Before 2.2.0 only the primary-locale document existed.) It holds a small
  per-site index (at most 512 sites, least recently used dropped first), **dropped the moment anything that may
  reshape the corpus changes** (§3), with the freshness TTL kept as the backstop for changes nobody announces (a
  file edited by hand). Always available; no external index.
  - **Configurable ranking** (per-site): `titleWeight` (8), `descriptionWeight` (4), `bodyWeight` (1 per
    occurrence, capped at 20 occurrences per term), plus flat bonuses for containing **all** terms
    (`allTermsBonus`) and the whole query as a **phrase** in the title/body (`phraseTitleBonus`/`phraseBodyBonus`).
    All data — a site tunes ranking with no code change.
  - **Highlighted snippets** built by `SearchSnippet` from the body (falling back to the description),
    honouring the frozen `SearchHit.snippet` contract (see §5).
- **Operator maintenance** — three **CSRF-checked `POST`s** reached from buttons on the plugin's settings page,
  each gated by the `search|settings` control point (`AccessCheck`), fail-closed to `404`, answered in the
  operator's language (`Messages.console`):
  - `POST /search/reindex` — rebuilds this site's index on **every** registered engine via the SPI's `reindex`
    hook (the built-in and any contributed engine alike), reporting the fresh document count of the engine that
    actually serves the site's queries. The built-in engine first refreshes the kernel's listing
    (`SearchCorpus.refresh`): the button exists for changes no event announced. The settings page's **Rebuild
    index** button submits it (the plain-text answer opens in a new tab).
  - `POST /search/install-page` — installs a first-class, master-wrapped tenant page at `/search` carrying the
    query-aware search-box widget anchor, through the kernel's `SitePages.install`, which **never overwrites** and
    follows the site's **editorial level**. The operator is told what happened: *installed at /search*
    (`PUBLISHED`), *waiting in the review queue* (`PENDING_REVIEW`: a Reviewed site and a user without
    `APPROVE_PAGES`), *created as a draft because the quality checks blocked publishing* with each blocking
    finding listed (`DRAFT_BLOCKED`: an Enforced site, or a Reviewed one with approval, whose checks fail), or
    *already exists at <path>* (`ALREADY_EXISTS`, wherever the page now lives). Once installed it is an ordinary
    site page — listed under Pages, restyleable, movable — so the operator owns the results page. It is created
    **`noindex`** — the canonical index opt-*out* for internal search results (thin/duplicate/infinite crawl
    space); since the page is the operator's own after install, the setting can be flipped in the page meta.
  - `POST /search/no-results/clear` — forgets the site's recorded no-result terms (below), then `303`s back to
    the settings page.
- **Searches with no results** (since 2.0.0; `NoResultsLog`) — the platform's own content-gap signal: a per-site list of what visitors searched for and did not find, shown on the settings page
  with a **Clear list** action. Both the widget (inline results) and the JSON API hand a query that found nothing
  to `NoResultsLog.record`.
  - **Why it is off by default.** A search term is free text a visitor typed; people search for names, email
    addresses, order or phone numbers. Recording is therefore an explicit per-site choice (`recordNoResults`,
    default `false`) rather than something an upgrade turns on silently. When it is on, the list holds **only a
    normalized term, a count, and a last-seen time** — no IP, no user, no session, no per-search timestamp — and
    a term that looks like an email address (an `@` followed by a dot) or a phone / ID number (seven or more
    digits in a run, ignoring spaces, dashes, dots, slashes, parentheses and `+`) is dropped unrecorded. The
    term never appears in an application log line (a failure logs the site and the exception class only).
  - **Declared personal data (2.0.0).** `plugin.yml` declares `siteData.personal: [no-results.tsv]`, so a
    site's **transfer copy** (the one meant for someone outside the organisation) leaves the file out and lists it
    under `omitted:` in the archive's `manifest.yml`; a **full backup** keeps it. A restored transfer copy simply
    starts with an empty list.
  - **Normalization.** Unicode NFC, control/format characters removed, whitespace runs collapsed to one space,
    trimmed, lower-cased (`Locale.ROOT`), cut at 100 characters without splitting a surrogate pair — so
    "Opening  Hours" and "opening hours" are one entry.
  - **Bounds.** At most **500** terms per site: admitting a new term to a full list evicts the least frequent one
    (the longest-unseen among equals), so a flood of one-off junk churns only the bottom and recurring gaps
    survive. A term not searched for `noResultsRetentionDays` (default 90) drops off listings and the next
    write. The same term on the same site within one second counts once — a page with a box in the master
    chrome *and* one in the content renders (and would record) the same empty result twice per request.
  - **Storage.** In memory per site, loaded lazily from `no-results.tsv` in the plugin's own per-site folder
    (`PluginSiteFiles`: `sites/<id>/plugins/search/no-results.tsv`); written atomically by
    a background flush every 30 s when something changed and on shutdown, never synchronously on the public search
    path. A crash loses at most the last 30 s. On a site lifecycle event that replaces the site wholesale
    (`RESTORED` / `DELETED`) the in-memory copy is dropped **unwritten**, so the restored file is what is read
    next and a deleted site's list is never written back (a late write for a missing site folder fails rather than
    creating it). A file over 512 KiB is ignored rather than read. A file that **cannot be read** (an I/O error) is
    never overwritten: the site's list stays unloaded — nothing is recorded for it — until a read succeeds.
  - **What it does not see.** Searches served from an engine that returns results are not recorded; a site whose
    search is disabled records nothing; a search box rendered from a cached page response (if a proxy caches
    `?q=` pages) never reaches the plugin.

## 2. The plugin's own SPI (`mx.quest.search.spi`)

```java
public interface SearchCorpus {                    // since 2.0.0 — one bean, `searchCorpus`, injected by type
    List<CatalogEntry> corpus(String siteId);      // the kernel's listing minus the site's `exclude` list
    String indexableText(String siteId, CatalogEntry entry, Locale locale);  // "" for an excluded entry
    void refresh(String siteId);                   // drop the kernel's cached listing (manual reindex)
}

public interface SiteSearchEngine {                // unchanged since 1.2.0
    String key();                                  // stable, namespaced, e.g. "search/simple"
    List<SearchHit> search(SearchQuery query);     // best-first, ≤ query.limit(); empty = no matches
    default int priority() { return 0; }           // higher wins
    default boolean available(String siteId) { return true; }   // false ⇒ fall back to the next engine
    default int reindex(String siteId) { return 0; }   // rebuild derived state NOW; returns the fresh doc count
}
record SearchQuery(String siteId, String text, Locale locale, int limit) {}
record SearchHit(String url, String title, String snippet, double score, List<String> terms) {
    SearchHit(String url, String title, String snippet, double score)  // the 2.0.0 form: no terms
}
```

**Why `SearchCorpus` exists.** Before 2.0.0, `search-lucene` read the kernel's
`ContentCatalog` and **this plugin's settings** (`SitePluginConfigService.config(site, "search")`) to apply the
`exclude` list itself — host linkage, and a cross-plugin settings read the service tier forbids
(`PluginSettings` only ever returns the *calling* plugin's settings). Now `search` reads its own settings and
publishes the filtered corpus; an engine never touches either.

- **Exclusion rule.** An entry is left out when its **own id** (`entry.ref().id()`: a page's slug, a content
  source's item id — never the composite `source:id`) equals, ignoring case, one of the `exclude` values; a leading
  `/` and blanks around a value are ignored. A YAML list or a comma/newline-separated string.
- **Freshness.** Nothing is cached on the plugin side: every call reads the kernel's listing (cached per site by the
  kernel, evicted by every change that can reshape it) and the current settings. An engine with an index of its own
  drops it from a `SiteEventListener.onCatalogChanged` callback for **every** cause — a page write, a content
  change, a site lifecycle event, and any plugin's settings save (this plugin's `exclude` list included).
- **Rules.** Every method takes a site id; an unsafe id throws `IllegalArgumentException`, an unknown site answers
  empty or `""`; thread-safe; never `null`.

Both interfaces are **binary API**: they evolve by adding `default` methods only — `reindex` (added in 1.2.0)
defaults to a no-op returning `0`, so an engine compiled against an earlier SPI keeps loading unchanged.
`SiteSearchEngine`, `SearchQuery` and `SearchHit` did not change in 2.0.0: an engine built against 1.x links and
behaves the same. **2.1.0 adds `SearchHit.terms`** — the words the engine matched, as the item writes them;
lowercase, distinct, at most 20 of at most 64 characters (the record normalizes them). The four-argument
constructor stays, so an engine built before 2.1.0 still links and its hits simply carry no words (no highlight on
the target page). An engine that fills `terms` calls the five-argument constructor and must therefore declare
`requires: search >= 2.1.0` (`search-lucene` 3.1.0 does).

The `SearchEndpoint` and the widget inject `List<SiteSearchEngine>` and, per request, select the
highest-`priority()` engine whose `available(siteId)` is true. So:

- **Installing `search-lucene`** (which registers an engine at priority 100 and is available once its index
  exists) **transparently upgrades** search — the widget and endpoint are untouched.
- If the Lucene index isn't built yet (`available()` false), the surface **falls back** to the built-in engine —
  a not-yet-built index never breaks search.

`search-lucene` compiles against this plugin's jar as a `provided` dependency and declares `requires: search`; at
runtime both plugin jars share one classloader, so the SPI types have one `Class` identity.

## 3. Freshness: the plugin's change listener

`SearchSiteEvents` is the plugin's one `SiteEventListener` (phase `CONSUMER`: it reads the listing, contributes
none). The kernel delivers it **after** every kernel cache the change affects has been evicted and after every
content source dropped its own state, and never while the host holds a write lock.

| Callback | What the plugin does |
|---|---|
| `onCatalogChanged` (every cause: `PAGE`, `CONTENT`, `SITE`, `SETTINGS`) | drops the built-in engine's index for the site; the next query rebuilds from the new state |
| `onSiteLifecycle` with `wholesale()` (`RESTORED`, `DELETED`) | `NoResultsLog` forgets the site's in-memory list unwritten |

Both run on every node, for local and replayed changes alike — they only drop local state. A query that was
building the index while a change arrived (another thread) answers its own caller but is **not cached**, so a
build that may have read the old state never outlives the change.

## 4. Settings

### App-level (`settings`, superadmin, applied on restart — platform concerns only)

Bound from `external/plugin-settings/search.yml` (prefix `runway.plugin.search`) into `SearchProperties`, through
the `@ConfigurationProperties` on the `searchProperties` `@Bean` (not `@EnableConfigurationProperties`, whose
generated bean name would break the plugin bean-name rule).

| Key | Type | Default | Effect |
|---|---|---|---|
| `refresh-minutes` | int (1–1440) | `60` | Freshness TTL of a built-in per-site index before the next query rebuilds it. Changes the kernel announces are reflected immediately (§3); the TTL re-captures what nobody announces. |
| `rate-limit.per-ip-per-minute` | int (0 = off) | `30` | Per-client query ceiling on the JSON API, per minute. |
| `rate-limit.per-ip-per-hour` | int (0 = off) | `600` | Per-client query ceiling on the JSON API, per hour. |

These are deliberately **not** per-tenant — a tenant must not set a global timer or an abuse limit.

### Per-tenant (`siteSettings`, applied immediately)

| Key | Type | Default | Effect |
|---|---|---|---|
| `enabled` | boolean | `true` | Off ⇒ the box (and its inline results) render nothing and the JSON API 404s (opt out without uninstalling). |
| `resultLimit` | int (1–100) | `20` | Max hits shown inline / returned by the API. |
| `resultsPath` | string | `/search` | Where the box submits `?q=` — the results page carrying this widget, by any of its addresses; set it if you move/rename that page. Since 2.2.0 a box on a localized page submits to that page's address in the box's language (`/es/buscar` from a Spanish page), else to this value as written. |
| `placeholder` | string | — | The search box's greyed prompt (falls back to a localized default). |
| `exclude` | list | — | Ids kept out of every engine's corpus: page slugs and content-source item ids. |
| `snippetLength` | int (40–400) | `160` | Characters of context per result snippet. |
| `highlight` | boolean | `true` | Result links carry the matched words and the site's pages highlight them (§1, 2.1.0). Off: plain links, no loader on the pages. |
| `titleWeight` / `descriptionWeight` / `bodyWeight` | int | `8` / `4` / `1` | Ranking weight per field. |
| `allTermsBonus` / `phraseTitleBonus` / `phraseBodyBonus` | int | `1000` / `400` / `300` | Flat ranking bonuses. |
| `recordNoResults` | boolean | `false` | Keep the "Searches with no results" list (§1). Off by default because terms can be personal data. |
| `noResultsRetentionDays` | int (1–365) | `90` | A recorded term nobody searched for in this many days drops off the list. |

Read live through the kernel's `PluginSettings.site(siteId)` (bound to this plugin by its jar): the stored value,
else the descriptor default, else the code fallback — and every code fallback in `SearchSiteConfig` equals its
descriptor default (`SearchConfigTest` holds them equal). The `settings` control point gates the editor **and**
the operator maintenance actions.

## 5. Boundaries & safety

- **Every state change is a CSRF-checked `POST`.** Reindex, install-page and clear change state (an index is
  rebuilt, a page is created, data is deleted), so each is an ordinary, non-anonymous `POST` route. The plugin
  endpoint is servlet-free, but it is dispatched *inside* the tenant security chain, whose CSRF filter checks the
  `_csrf` field the admin fragment's forms render from the console page (`${_csrf}`). A `GET` on those paths
  matches no route (the kernel answers `404`), so another web site cannot trigger them through a signed-in
  operator's browser with a link or an `<img>` — which 1.x's `GET /search/reindex` and `GET /search/install-page`
  allowed. `scripts/verify-against-host.sh` proves it live: `GET` 404, no token 403, forged token 403, an
  anonymous caller with a valid token 404 (the permission check), the console's token accepted. The only `GET`s are
  the JSON API (a read) and the box's own form (a `GET` to the results page — an ordinary tenant page).
- **No-result terms are visitor text.** The admin card binds them with `th:text` only; they never reach a log
  line; recording is opt-in per site; the file is declared personal data (§1).
- **The `snippet` safety contract (frozen).** A `SearchHit.snippet` is **pre-escaped, highlight-bearing HTML**:
  the engine HTML-escapes every character from the page and leaves `<mark>…</mark>` — and nothing else — as raw
  markup around the matched terms. The widget therefore renders the snippet as **raw HTML** (so the
  highlight shows as emphasis, not literal `&lt;mark&gt;`), which is XSS-safe precisely because escaping happened
  at the engine — a crafted page title or body cannot forge a tag. `SearchSnippet` does escape-then-mark; both the
  built-in engine and `search-lucene` honour the contract, so the one raw-render serves both.
- **Output safety.** The query, page titles, and every non-snippet value are escaped by `Html.escape`; the JSON
  API escapes strings and unicode-escapes angle brackets. The JSON API is `noindex`; the results page is a normal
  tenant page **installed with `noindex` on**, and the operator owns its page meta and can flip it.
- **Abuse guard.** `SearchRateLimiter` caps the **JSON API** per client with two fixed windows (a minute and an
  hour, each starting at the client's first query in it); over the ceiling ⇒ `429` with
  `{"error":"rate_limited"}`. A blank query is not counted. **The key is `RouteRequest.clientIp()`** — the client
  address after the kernel's **trusted-proxy gate**: a forwarded header counts only when the
  immediate peer is a configured trusted proxy. 1.x keyed on the first `X-Forwarded-For` hop it read itself, so a
  visitor could send a made-up header per request and never be limited. A request with no client IP shares one
  `shared` bucket rather than escaping. The table holds at most 50,000 clients: when full, clients whose windows
  have expired are dropped first, and if all are active the table restarts (fails open — this is a courtesy limit;
  the kernel edge guard is the platform's DoS floor beneath it, and also bounds the inline results the widget
  renders during page render).
- **No host internals.** The compiled classes reference nothing under `mx.quest.runway` but `mx.quest.runway.spi`
  (`SearchConfigTest` scans the bytecode; the kernel's load-time scanner reports nothing), and nothing of Caffeine
  either: it is a kernel implementation detail, not one of the platform libraries a plugin may rely on.
- **Isolation.** Every surface is scoped to the bound tenancy (`req.site().id()` / `view.site().id()`); the index
  is keyed per site, so one tenant never sees another's content; the plugin's files are confined by the kernel to
  `sites/<id>/plugins/search/`.

## 6. Inventory

| Piece | Type | File |
|---|---|---|
| `mainClass` `@Configuration` | `SearchConfig` | wires every bean explicitly over SPI services; every bean name starts with `search` |
| **Published SPI** | `spi/SearchCorpus`, `spi/SiteSearchEngine` + `SearchQuery` + `SearchHit` | the corpus and the extension point |
| Corpus | `SiteSearchCorpus` | `ContentCatalogView` minus the `exclude` list (`PluginSettings`) |
| Built-in engine | `SimpleSearchEngine` | full-text over title/description/body, bounded per-site index cache |
| Change listener | `SearchSiteEvents` (`SiteEventListener`, `CONSUMER`) | drops the index on every catalog change; forgets the no-result list on a wholesale lifecycle event |
| Body extraction | `HtmlText` | HTML → plain text for indexing (strips script/style/comments/tags) |
| Snippet builder | `SearchSnippet` | escape-then-mark; honours the frozen snippet contract |
| Indexed document | `SearchDocument` | one per item: display + pre-lowercased match fields |
| Request endpoint | `SearchEndpoint` (`EndpointContributor`) | `GET /search/api`; `POST /search/reindex`, `/search/install-page`, `/search/no-results/clear` (no `/search` page) |
| No-result terms | `NoResultsLog` | opt-in per-site content-gap list over `PluginSiteFiles` (`no-results.tsv`, declared personal) |
| Rate limiter | `SearchRateLimiter` + `SearchClientId` | per-client two-window courtesy cap (JSON API), keyed on the gated client IP |
| Query-aware search box | `SearchBoxWidget` (`ContentWidget` `search`) | embeddable GET form + inline results when `?q=` (per-placement `results`) |
| Result highlighting | `MatchedWords`, `HighlightMarker`, `SearchHighlightHead` (`HeadContributor`), `static/highlight-loader.js`, `static/highlight.js`, `static/highlight.css` | the matched words, the `#rw-hl=` link marker, the page script |
| App settings | `SearchProperties` | `runway.plugin.search.*` (refresh + rate-limit) |
| Per-site settings | `SearchSiteConfig` | typed accessors over `SettingsView` |
| Console model | `SearchAdminModel` + `templates/` | dashboard card + admin status (searchable items = the corpus size) + the forms |
| i18n (EN/ES), static (help + CSS) | `i18n/`, `static/` | console + public strings |

## 7. Verify

`scripts/verify-against-host.sh` builds the plugin offline (never `mvn install`), drops it into a throwaway
deployment (`VERIFY_TMPDIR`), boots the **packaged** host (`HOST_JAR`, else the host checkout's jar matching its pom
version) in ACTIVE mode via the first-run wizard, with three sites (Advisory, Enforced, Reviewed) and loopback made
an **untrusted** peer, and proves the 2.1.1 contract: zero host-linkage and bean-name/surface warnings in the boot
log; the app settings bind; the JSON API serves and there is no `/search` page until one is installed; the three
operator actions are CSRF-checked POSTs (GET 404, no token 403, forged 403, anonymous-with-token 404) offered as POST
forms by the console; reindex reports a count; the starter page installs on all three editorial levels, renders
results inline and is `noindex`, and a second install changes nothing; the `exclude` list applies on the save; the
no-result list is off by default, records when on (never an email-like term), is flushed to the plugin's file,
carried by a full backup and **omitted (and listed) by a transfer copy**, and cleared only by the CSRF-checked POST;
made-up `X-Forwarded-For` headers do not evade the API's per-client ceiling while another address keeps its own
budget; result links (page and API) carry `#rw-hl=<words>`, every page carries the loader script stamped with the
nonce its CSP allows, and the loader, `highlight.js` and `highlight.css` are served. `VERIFY_LOG_DIR` keeps the host logs, the install answers and both backup manifests. Never
`spring-boot:run`.

Unit tests run on `runway-spi-testkit` (`FakePluginSettings`, `TempDirSiteFiles`, `FakeSiteServices`,
`FakeContentCatalog`, `EventDispatch`): `SearchConfigTest` (wiring over SPI services only, bean names, template
beans, descriptor floor/version/personal data, code fallbacks = descriptor defaults, bundles, the bytecode scan),
`SearchAdminTemplateTest` (every operator action is a POST form carrying the token; no link), `SiteSearchCorpusTest`,
`SimpleSearchEngineTest` (matching, ranking, exclusion, the primary-locale URL, every change cause dropping the
index, the raced build not kept, the bounded cache), `SearchEndpointTest` (routes, JSON API, rate-limit key, gated
reindex and install with every outcome), `SearchBoxWidgetTest`, `SearchAdminModelTest`, `NoResultsLogTest`,
`NoResultsRecordingTest`, `SearchRateLimiterTest`, `SearchSnippetTest`, `HtmlTextTest`, `HighlightTest` (matched words, the link marker, the
loader, the JSON fields, and the page script agreeing with the Java side) — 155 in all. The page script itself was
checked in Chrome under an enforcing CSP: highlights on arrival and on `hashchange`, the marker removed
with the query kept, no console error.
