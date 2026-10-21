# The `discoverability` plugin — design, features & verification

> **SEO+ and GEO in one plugin**, sharing one schema.org JSON-LD core. It enriches the head the Runway kernel
> already fills (canonical + hreflang + the full Open Graph / Twitter social card) with the **structured-data**
> the lean core leaves out, and **owns the crawl surfaces** the core does not ship: `/sitemap.xml`, `/llms.txt`
> and `/robots.txt`. The second reference plugin (after `../demo`) — a real, cohesive feature.

- **id:** `discoverability` · **version:** `2.3.0` · **install folder:** `external/plugins/discoverability/` · **package:** `mx.quest.discover`
- **jar:** `runway-plugin-discoverability.jar` (thin) · **links:** the Runway SPI only (`requiresSpi: ">=1.0.0"`)
- **Verified:** 178 unit tests + 6 host-integration tests, a live run against the packaged kernel and (2.0.0) a
  before/after comparison with 1.6.0 (see §6)

---

## 1. The boundary: what the kernel does vs. what this adds

The lean kernel SEO core already emits, for free, on every public page (via `SeoHeadContributor` and the
head-injection edge):

- `<link rel="canonical">`, `hreflang` alternates + `x-default`, and the `robots` meta directive (from the
  effective `noindex`);
- the **full Open Graph + Twitter card** — `og:title`/`description`/`url`/`site_name`/`type`/`locale`(+alternates)
  and the mirrored `twitter:*`, **derived from the page** so a card renders even for an unconfigured site — plus
  `og:image`/verification meta from the site's stored SEO config.

**This plugin never re-emits any of those.** It adds the layer the core deliberately omits:

| Area | Surface | Seam |
|------|---------|------|
| **SEO+** | schema.org **JSON-LD** — one `@graph`: `WebSite` / `WebPage`\|`Article` / `Organization` / `BreadcrumbList`, a homepage `SoftwareApplication`, and per-page **typed** nodes | `HeadContributor` |
| **SEO+** | **`/sitemap.xml`** — published content + `hreflang` alternates | `EndpointContributor` |
| **SEO+/GEO** | **`/robots.txt`** — `Sitemap:` line + configurable AI-crawler policy | `EndpointContributor` |
| **GEO** | **`/llms.txt`** — compact page index for LLM / answer engines | `EndpointContributor` |
| **GEO** | `SearchAction` + `Speakable`, folded into the SAME `@graph` nodes by `@id` | (same `HeadContributor`) |

> **robots.txt ownership.** The kernel serves no `/robots.txt` of its own; this plugin owns the route (an
> `EndpointContributor` route has exactly one owner, and a kernel handler reading plugin config would hard-code a
> plugin id into core). An install **without** this plugin therefore serves no `/robots.txt`, which crawlers
> treat as allow-all.

---

## 2. What it links, and why (2.0.0)

Since 2.0.0 the plugin compiles against `runway-spi` alone and links **no kernel-internal class** (the SPI
service tier). Everything it needs from the host arrives by ordinary Spring injection of an SPI type, which the
kernel implements:

| Need | SPI |
|------|-----|
| this plugin's per-site settings | `PluginSettings.site(site)` — bound to this plugin by the jar its code came from; the descriptor's `default:`s applied by the host |
| the site's published content, with its URLs | `ContentCatalogView.entries(site)` → `CatalogEntry` (metadata + one `LocalizedUrl` per available locale + the `x-default` path) |
| a site by id, off-request | `Sites.find` |
| "something changed, tell IndexNow" | `SiteEventListener.onCatalogChanged` — with the item's URLs **before and after** the change, and whether it happened on this node |
| an absolute base without a configured one | `RouteRequest.origin()` — the request's origin as the kernel's trusted-proxy gate left it (until 2.0.0 the plugin built it from the raw `Host` header, with a guessed `https`) |
| the same, for the page's JSON-LD (2.1.0) | `HeadContext.origin()` — the same trusted origin, handed to every head contributor (2.0.0's head had no origin, so a host-less site's graph was root-relative) |

What this buys: a kernel refactor cannot break the plugin (the kernel refuses plugins that link its
internals); the URL scheme lives in one place, the kernel, so the sitemap, `llms.txt` and the page
`<head>` cannot disagree; and IndexNow can report deletions (2.0.0; 1.x could not). The plugin never applies the
URL scheme itself — it only prefixes the origin to the root-relative paths the kernel computed.

**2.1.0: the JSON-LD of a host-less site is absolute.** A site with neither a base URL nor a host name (the
platform's catch-all default) gets a root-relative canonical from the kernel — the kernel prefixes only a base URL it
knows. 2.0.0 anchored the `@graph` on that canonical, so its `@id`s were root-relative (`/#website`), and with no
base it left out the GEO `SearchAction` and the breadcrumb trail. From SPI 1.0.0 the kernel hands every head
contributor `HeadContext.origin()` — the site's base URL, or the request's origin as the trusted-proxy gate left it
— and the graph is built on it: absolute `@id`s and URLs, a `SearchAction`, a breadcrumb trail, an absolute
`org.logo`. A site with a base URL or a host name is unaffected (byte for byte). The kernel's own `<link
rel="canonical">`, `hreflang` and Open Graph tags keep their rule (root-relative on such a site); they are not this
plugin's. Because `origin()` needs SPI 1.0.0, the descriptor declares `requiresSpi: ">=1.0.0"`: a kernel below it
blocks 2.1.0 at startup with a reason.

**Bean names carry the plugin id.** Plugins share the kernel's bean namespace, so every bean is named
`discoverability…`. Two were renamed: the console model is `discoverabilityAdminModel` (was `discoverAdminModel`;
the dashboard card and admin fragment follow), and the app-level settings are a named `@Bean`
`discoverabilityProperties` with `@ConfigurationProperties` on the method — `@EnableConfigurationProperties` would
have registered them under a generated name outside the prefix.

**Personal data:** none. The plugin stores nothing under `sites/<id>/plugins/discoverability/`; its settings file
(`plugins/discoverability.yml`) is configuration, so the descriptor declares no `siteData.personal`.

---

## 3. Why one plugin (SEO+ and GEO together)

The two areas share one spine: **schema.org JSON-LD**, which serves *both* rich search results (SEO) and AI
answer extraction (GEO). As of 1.2.0 there is **literally one `HeadContributor`** (`StructuredDataHead`)
emitting **one `@graph`** — the GEO `SearchAction` and `Speakable` are folded onto the same `WebSite`/`WebPage`
nodes (rather than a second `<script>` block that repeats the `@id`s), so every `@id` is defined exactly once
and a consumer that parses each `<script>` independently still sees complete entities. GEO stays gated by a
single `geo.enabled` toggle. One plugin ⇒ one config surface, one permission, one JSON-LD engine.

---

## 4. Feature → seam coverage

| Seam | Class | What it does |
|------|-------|--------------|
| `mainClass` `@Configuration` + explicit `@Bean` wiring | `DiscoverabilityConfig` | registers all beans + the `SchemaType` registry |
| **HeadContributor** (`@Order 150`) | `StructuredDataHead` | the one JSON-LD `@graph` (SEO nodes + GEO signals + SoftwareApplication + typed nodes); skips preview/`noindex`/disabled |
| **EndpointContributor** `/sitemap.xml` | `SitemapEndpoint` | urlset over `ContentCatalogView` entries (the kernel's URLs); `sitemap.enabled`-gated |
| **EndpointContributor** `/robots.txt` | `RobotsTxtEndpoint` | an **explicit** named AI/LLM-crawler group (always emitted — the posture is stated, not implied) + a general allow group + `Sitemap:` line |
| **EndpointContributor** `/llms.txt` | `LlmsTxtEndpoint` | Markdown page index over `ContentCatalogView`; `geo.enabled`-gated |
| **EndpointContributor** `/indexnow-key.txt` | `IndexNowKeyEndpoint` | the site's IndexNow key; 404 unless both switches are on and a key is set |
| **SiteEventListener** (`CONSUMER`) + `@Scheduled` flush | `IndexNowSubmitter` | queues each local change's previous ∪ current URLs; one batched POST per site per period |
| **Content-type registry** (`SchemaType`) | `FaqPageSchema`, `ProductSchema`, `ArticleSchema`, `LocalBusinessSchema`, `ServiceSchema`, `PersonSchema`, `CollectionSchema` | one impl per type, dispatched by declared type token — the host stays type-blind |
| **Advisory** (`PageQualityCheck`) | `FaqAdvisoryCheck` | edit-time: detects an on-page `<details>` FAQ not in `content.schema` and advises adding it (localized by finding code) |
| Per-page typing | `PageSchemaConfig` + `SchemaContext` + `Schemas` | parse `content.schema`, resolve by path (exact > longest `*`-prefix) |
| JSON-LD core (shared) | `Ld` | node/`@id`/graph builder; HTML-safe `<script>` serialization |
| App-level settings | `DiscoverabilityProperties` (`@ConfigurationProperties` on the `discoverabilityProperties` bean) | `robots.ai-*`, the IndexNow egress switch and limits |
| Per-tenant config | `SiteConfig` over `PluginSettings.site(...).values()` | live typed reads, 1.x's lenient rules |
| Dashboard card + admin fragment | `DiscoverAdminModel` (bean `discoverabilityAdminModel`) + `templates/` | live status |
| Control-point permission (`settings`) | `plugin.yml` | `discoverability\|settings` ("Manage discoverability (SEO & GEO)"): gates the per-tenant config editor and the dashboard card; `defaultRoles: []`, so only the site's superadmin and administrators hold it until it is granted on the site's Permissions screen |
| i18n (EN/ES), static help page | `i18n/`, `static/` | console + public-facing strings |

---

## 5. Settings

### App-level (platform; `external/plugin-settings/discoverability.yml`; restart to apply)

| Key | Type | Default | Meaning |
|-----|------|---------|---------|
| `robots.ai-default` | enum | `allow` | the default AI/LLM-crawler policy a site inherits (`robots.ai-policy: inherit`) — `allow` (GEO-friendly) or `disallow` |
| `robots.ai-agents-extra` | list | *(empty)* | extra AI/LLM crawler user-agent names **appended** to the built-in list. The built-in list is a compile-time constant that **goes stale silently** — an agent it does not name is simply not covered by a site's opt-out, and nothing reports the gap. This closes a newly announced agent without waiting for a plugin release. Additive only; a retired name costs one inert `User-agent:` line. Prefix a name with `training=`, `retrieval=` or `user=` to put it in that class (it then follows the site's per-class override); without a prefix it follows the site's overall `robots.ai-policy`. A misspelt class is logged and the name is kept, unclassified. One name per line or comma-separated entry; whitespace, `:` and `=` are stripped from the name, and a name already in the list (built-in included, any case) is ignored, so a built-in agent cannot be reclassified. The 22 built-in agents and their classes are listed in the help page (`#ai-agents`) |
| `indexnow.enabled` | boolean | `false` | the operator's egress decision: lets sites turn IndexNow on (this server then makes outbound requests). Off, a site enabling it gets a WARN and its form says the platform has it off |
| `indexnow.endpoint` / `.batch-seconds` / `.max-urls-per-submission` / `.max-queued-per-site` / `.timeout-seconds` | string / int ×4 | `https://api.indexnow.org/indexnow` / `60` / `1000` / `5000` / `10` | where and how submissions go. Ranges `5..3600` / `1..10000` / `1..100000` / `1..120`: the console form refuses a value outside them; a value set by hand (this file, `application.yml`, the environment) is not range-checked — the three limits count anything below 1 as 1, and `batch-seconds` is held to `5..3600` (2.2.0; before, it went to the scheduler as written, so `0` or a negative value made the start fail). What each does: help page `#indexnow-limits` |

### Per-tenant (live, no restart). **SEO+ on by default; GEO opt-in.**

| Key | Type | Default | Meaning |
|-----|------|---------|---------|
| `jsonld.enabled` | boolean | `true` | emit structured data |
| `jsonld.type` | enum | `WebPage` | the page `@type` of every page whose `content.schema` entry declares no page type (`WebPage` or `Article`); a page type there (`Article`, `FAQPage`, `ContactPage`, …) replaces it on the pages it matches (2.2.0). As the default, `Article` carries the title as `headline` but no author, dates or image (those need an `Article`, `BlogPosting` or `NewsArticle` block in `content.schema`, which then sets the page's type). Case-sensitive: in a hand-edited file anything but exactly `Article` means `WebPage` |
| `jsonld.breadcrumbs` | boolean | `true` | include a `BreadcrumbList` on every page but `/` and a page whose canonical points to another site (a non-default-language home such as `/es` gets one): `Home` (a fixed English label, linking to the site root) → the `content.schema` `breadcrumb` parent, if any → the page (its title, else its path) |
| `content.schema` | text | — | **per-page typing** — YAML `path-pattern → { type, fields }` (see below) |
| `org.name` / `org.logo` / `org.sameas` | string / string / list | — | Organization identity; a root-relative `org.logo` resolves to absolute against the base |
| `software.*` | (group) | off | homepage-only `SoftwareApplication` (also on each non-default-language home, `/es`) — `software.enabled`, `.name`, `.description`, `.category`, `.os` (default `Web`), `.price`, `.currency` (default `USD`), `.license`, `.url`, `.screenshot`. Free text, not validated; a blank `.os`/`.currency` means its default. `.license`, `.url` and `.screenshot` are emitted as written — unlike `org.logo`, a root-relative value stays relative, so they need full URLs |
| `sitemap.enabled` | boolean | `true` | serve `/sitemap.xml` (also gates the robots `Sitemap:` line) |
| `robots.ai-policy` | enum | `inherit` | this site's AI-crawler policy — `inherit` (app default) / `allow` / `disallow` |
| `robots.ai-training` / `robots.ai-retrieval` / `robots.ai-user` | enum | `inherit` | per-class overrides of the policy above (`inherit` = whatever `robots.ai-policy` says). The classes have opposite consequences: refusing training costs nothing visible, refusing retrieval removes the site from AI answers, refusing user fetches breaks a visit a person asked for |
| `robots.extra-disallow` | list | — | extra `Disallow:` paths in the general group (and in every allowed AI group); a missing leading `/` is added, CR/LF stripped, otherwise as written — each is a robots.txt path prefix |
| `robots.custom` | text | — | full-body override — replaces the generated `robots.txt` verbatim |
| `geo.enabled` | boolean | `false` | **GEO master switch** (SearchAction, Speakable, `/llms.txt`) |
| `geo.summary` | text | — | the one-line summary atop `llms.txt` |
| `geo.speakable` | list | `h1,main` | Speakable CSS selectors (add `article` etc. if your master has them); comma- or line-separated; blank means `h1,main`. Needs `jsonld.enabled` too |
| `search.url` | string | — | site-search URL → a machine-readable `SearchAction` (GEO and structured data both on). A path or full URL (a value starting `http` is used as is); `{query}` or `{search_term_string}` marks where the words go, else `?q=` is appended (`&q=` when the URL already has a `?`) |
| `indexnow.enabled` | boolean | `false` | notify Bing, Yandex, Seznam, Naver and Yep (**not Google**) of changed URLs — inert unless the app-level switch is also on, and silently inert (no warning) for a site with neither a base URL nor a host name |
| `indexnow.key` | string | — | 8–128 letters, digits or dashes (the protocol's rule, not enforced here); served at `/indexnow-key.txt`. Nothing is sent without one |

**How the form is laid out (1.6.0).** The per-site form is in six sections — structured data, sitemap &
robots.txt, AI crawlers, GEO, product on the homepage, IndexNow — each field with a help line (EN + ES) and
the longer explanations on the help page (`static/discoverability-help.html`, one anchored section per form
section). `content.schema`, `robots.custom` and the three per-class AI overrides sit under *Advanced*;
fields that do nothing while their switch is off (`jsonld.enabled`, `geo.enabled`, `software.enabled`,
`indexnow.enabled`) are hidden until it is on. 2.0.0 keeps the layout unchanged (the before/after comparison in §6 diffs the rendered
form). `SettingsDescriptorTest` (plain YAML, default build) fails the build on a dangling group, switch,
inheritance source, help anchor or message key; `HostDescriptorTest` (the `host-it` profile) also parses the
descriptor with the kernel's own parser. `search.url` stays under GEO rather than structured data because the
code only emits the `SearchAction` while GEO is on.

**`content.schema` format** — a small YAML document keyed by URL-path pattern; an exact path wins over a
trailing-`*` prefix, and among prefixes the longest wins. The complete reference — every type and every key it
reads, how a page is matched (its own canonical path only: `/faq` does not match `/es/faq`, because an entry's text
is in one language; the translated page needs its own key, and the page editor says so when the default language's
entry does not reach it), and which types are **page types** (they replace `jsonld.type` on the page:
`Article`/`BlogPosting`/`NewsArticle`, `FAQPage`, `CollectionPage`, `WebPage`, `AboutPage`, `ContactPage`,
`ItemPage`, `SearchResultsPage`) versus types that add an entity (`Product`, `Service`/`ProfessionalService`,
`LocalBusiness`/`CafeOrCoffeeShop`, `Person`, `ItemList`) — is in the operator help page
`static/discoverability-help.html#content-schema`, which the settings form links to. `PageTypes` computes the
answer once for the `<head>`, for the page editor's line (`PageTypeCheck`) and for the FAQ advice
(`FaqAdvisoryCheck`); the admin fragment lists every mistake (`DiscoverAdminModel.schemaProblems`), including a
block that applies but publishes nothing of its own (`SchemaType.gap`: an `FAQPage` with no complete question, a
list with no item `url`). There is no `QAPage` or `ProfilePage`: Google requires a `mainEntity` on both, which a
type with no keys cannot give.

```yaml
# A pricing page that carries TWO types at once — the entry is a LIST of type-blocks:
/pricing:
  - type: FAQPage
    faq:
      - { q: "Why ranges?", a: "Scope drives cost…" }
  - type: Service
    name: "Advisory Sprint"
    offers:
      - { name: "Sprint", price: "18000", currency: USD }
# A collection/index page: the page is a CollectionPage whose main entity is the list:
/coffees:
  type: CollectionPage
  items:
    - { name: "Ethiopia Guji", url: "/coffee-ethiopia-guji" }
    - { name: "Colombia Huila", url: "/coffee-colombia-huila" }
# A product detail page — `breadcrumb:` inserts its parent collection as a middle level (Home → Coffees → …):
/coffee-ethiopia-guji:
  type: Product
  price: "22.00"
  currency: USD
  image: ["/static/img/guji.jpg"]
  breadcrumb: { name: "Coffees", url: "/coffees" }
/cafes:
  type: CafeOrCoffeeShop
  locations:
    - { name: "Roastery", street: "14 Lamplight Lane", locality: Portland, region: OR }
/team:
  type: Person
  people: [{ name: "Dana Whitlock" }]
```

Supported `type`s (case-sensitive; any other token is skipped and reported): the page types `Article`/`BlogPosting`/
`NewsArticle`, `FAQPage`, `CollectionPage`, `WebPage`, `AboutPage`, `ContactPage`, `ItemPage`,
`SearchResultsPage`, and the added entities `Product`, `Service`/`ProfessionalService`,
`LocalBusiness`/`CafeOrCoffeeShop`, `Person`, `ItemList`. An entry is **one type-block** (a map) or
a **list of type-blocks** when one page carries several types. Any detail entry may add `breadcrumb: {name, url}`
to place its parent collection between Home and the page. Values are **tenant-owned**; the plugin owns the typing
mechanism. Each typed node references the page's `WebPage`/`Organization` by `@id`, so it joins the one `@graph`;
the `WebPage` in turn links its `BreadcrumbList` by `@id` (`breadcrumb`).

> **Defaults: the descriptor's, applied by the kernel — and the code's, which must match.** Since 2.0.0 the
> settings come from `PluginSettings`, which overlays the stored values on the descriptor's declared `default:`s
> (1.x read only the stored values and applied its code fallbacks). Every code fallback (`SiteConfig.bool(cfg,
> key, default)`) equals its declared default, so nothing a tenant sees changed: `DescriptorDefaultsTest`
> renders every surface with and without the declared defaults and requires identical bytes, and
> `HostDescriptorTest` checks the unit tests' settings fake against the kernel's own merge.

---

## 6. Verification

**Unit (178 tests, `mvn verify`; SPI-only, on `runway-spi-testkit`'s fakes):** `LdTest`; `StructuredDataHeadTest`
(WebSite/WebPage/Organization/Article/Breadcrumb; the GEO fold — one block, `@id`s once; SoftwareApplication
homepage-only; the typed nodes; several type-blocks on one path; breadcrumb links; relative-logo resolution; the
skip cases; 2.1.0: a host-less graph absolute at `origin()` with its SearchAction and trail, the base URL winning over
the origin byte for byte, no origin rendering as 2.0.0 did); `RobotsTxtEndpointTest` (the explicit AI-crawler group on both postures, mirroring, extra-disallow,
custom override, the Sitemap line from the base or the trusted origin); `SitemapEndpointTest` (the exact
document, noindex and canonical rules incl. root-relative canonicals, the index/shards, conditional GET, the
origin fallback); `LlmsTxtEndpointTest` (the exact document, the link fix for an item missing from the default locale, labels; 2.1.0: the summary, the site
name and the base URL advance `Last-Modified`, the request origin and the summary do not move the sitemap's); `IndexNowSubmitterTest`
(payload, both switches, bounds, and the SPI change events: deletions submit their old URLs, renames the union,
peers' replays nothing); `CrawlConditionalTest` (2.1.0: document facts, length-prefixed, one validator per
document); `FaqAdvisoryCheckTest`; `SettingsDescriptorTest`;
`DescriptorDefaultsTest`; `DiscoverabilityConfigTest` (wiring over SPI services only, bean names, template bean
references, the app-settings binding, descriptor floor/version, and a bytecode scan proving no class links
`mx.quest.runway.*` outside `.spi`).

**Host integration (6 tests, `mvn -P host-it verify`):** the only build that sees `runway-app` (test scope).
`HostDescriptorTest` (the kernel parses the descriptor; the kernel's settings merge equals the unit tests' fake);
`HostCatalogParityTest` (over the real kernel catalog: the sitemap equals what 1.6.0's algorithm built from the
same catalog with the kernel's URL builders, llms.txt differs only by the default-locale link fix, head and sitemap alternates
agree, and the test fixture's URL mirror equals the kernel's entries).

**Live** (`scripts/verify-against-host.sh` against the packaged kernel; honours `HOST_JAR`, `VERIFY_TMPDIR`,
`VERIFY_LOG_DIR`): 49 checks — loaded with zero host-linkage and bean-name/surface findings, IndexNow collected as an SPI listener; JSON-LD (2.1.0: absolute, with its
SearchAction and a trail), sitemap with hreflang for a bilingual site, robots.txt and llms.txt at the trusted origin
of a host-less site; llms.txt's `Last-Modified` advancing on a summary-only save through the console; the 1.6.0 settings layout, the admin fragment and dashboard card through the renamed
bean; IndexNow inert by default (one WARN, key 404, nothing sent); IndexNow on (a fake endpoint): an
**unpublished** page's old URLs are submitted, a republish once per batch.

**Before/after** (`scripts/compare-with-previous.sh`, `PREVIOUS_DIR=<1.6.0 install folder>`): one deployment, the
same files, 1.6.0 then 2.0.0; a bilingual fixture (FAQ-typed About, a Spanish-only page, noindex, canonicalised,
root-relative self canonical, draft; GEO, Organization, SoftwareApplication), each surface fetched with a
configured base URL, a host-derived one and none. Identical — sitemap, robots.txt, llms.txt, JSON-LD of four
pages, status codes, the settings form, the admin and dashboard values — except the three documented fixes
(CHANGELOG 2.0.0).

---

## 7. Honest gaps & deliberate non-uses

- **No `<lastmod>` for an item without `updatedAt`** — pages always carry one; a content source may not.
- **Closed in 2.1.0** (all three were listed here at 2.0.0): a host-less site's structured data is absolute
  (`HeadContext.origin()`, above); an item whose primary locale the site does not serve is linked at its first
  available locale's real address (the SPI 1.0.0 kernel's `CatalogEntry.pathFor`, which 2.1.0 requires); and
  `llms.txt`'s validator folds the site name, `geo.summary`, the default locale and the base URL, so editing any of
  them advances `Last-Modified`.
- **The sitemap's validator does not fold the base URL.** Changing a site's base URL rewrites every `<loc>` but not
  the sitemap's `Last-Modified` until content changes (llms.txt folds it since 2.1.0). Rare (a domain move) and
  self-correcting at the next content change; a follow-up.
- **Typing is a path map, not a per-page field** — `content.schema` keys by URL path; the cleaner long-term
  seam (a `schema-type` on the page's own metadata) needs a small additive kernel change.
- **Per-instance / per-page config** beyond `content.schema` is not offered — configuration is per-site.

---

**Added after an SEO and GEO audit of the plugin (2026-08-19):**

- ~~**The AI-crawler classes are not separately controllable.**~~ **Fixed in 1.5.0.** Each agent now carries an
  `AiClass` — `TRAINING`, `RETRIEVAL`, `USER` — and a site can override the policy per class with
  `robots.ai-training` / `robots.ai-retrieval` / `robots.ai-user` (each `inherit|allow|disallow`, `inherit`
  meaning "whatever `robots.ai-policy` says"). So a publisher can decline to be *training* material while
  staying eligible for *citation*, which was the whole point.

  **What is still a judgement call:** the classification itself. The operators' own triads are not in doubt
  (OpenAI's `GPTBot`/`OAI-SearchBot`/`ChatGPT-User`, Anthropic's `ClaudeBot`/`Claude-SearchBot`/`Claude-User`,
  Perplexity's pair), nor are the training-only opt-out agents that exist precisely to be separable from their
  search siblings (`Google-Extended`, `Applebot-Extended`). The rest is a best-effort reading of each
  operator's own description, and an agent whose role is genuinely ambiguous is classified `TRAINING` —
  the class a site opting out is most likely to mean, so a misplacement fails toward honouring the site's
  stated intent rather than around it.

  A site that sets no per-class override gets a **byte-identical** `robots.txt` to before, single group and
  all: splitting a site that expressed no such preference into three identical groups would churn every
  crawler's copy to say the same thing.

  An operator-added agent (`robots.ai-agents-extra`) may declare its class as `training=SomeNewBot`; without a
  prefix it follows the site's overall policy, exactly as it did before. A misspelt class is **logged** and the
  agent is still listed — folding the bad prefix into the name would emit a token matching nothing, and the
  operator would believe they had covered an agent they had not.
- **IndexNow — built in 1.5.0, off at both ends, and honest about what it buys.** Participating engines
  (Bing, Yandex, Seznam, Naver, Yep) are told when a site's content changes, instead of waiting to be
  re-crawled. **Google does not participate**, so this changes nothing about Google's view of a site — worth
  saying plainly, because the opposite is widely implied and a tenant turning it on should know what they are
  buying. It is a *freshness* signal, never a ranking one.

  **Two switches, both off.** This is the first thing in the plugin that makes an **outbound** request on a
  tenant's behalf, so it needs the platform operator's permission (they own the network, the egress firewall
  and the incident) *and* the tenant's (they own the URLs being disclosed). An upgrade must never start making
  network calls a deployment was not making before. A tenant who enables it while the platform has not gets a
  WARN naming both the site and the setting to flip — a feature that is silently inert is worse than one that
  is off, because from inside nobody can tell the difference.

  **The key file lives at a fixed `/indexnow-key.txt`, not `/<key>.txt`.** The usual pattern names the file
  after the key, which cannot work here: the host matches routes by exact path and builds its table at
  startup, while the key is per-site configuration that changes at runtime. The spec's own `keyLocation` field
  exists for exactly this, so every submission points at the stable path. It is served `no-store` and outside
  the sitemap — a cached copy would keep answering with an old key after a rotation, failing verification in a
  way that looks like the engine's fault.

  **Deletions and moves reach the engines (2.0.0).** The submitter is an SPI `SiteEventListener`; each change
  carries the item's URLs before and after it, and the union is queued — so an unpublished or deleted page's old
  address is submitted, which 1.x could not do (it looked the URLs up after the kernel had dropped the item).
  Only the node where the change happened submits; a cluster's replays on its other nodes are ignored.

  **The flush runs on the kernel's scheduler.** It is a Spring `@Scheduled` method, and the kernel enables
  scheduling unconditionally. The plugin does not start a scheduler of its own.

  **Batched, bounded, and never on a request thread.** A page save touching five localized URLs must not
  become five outbound requests; a bulk edit or a restore must not become thousands. Queued URLs per site and
  URLs per submission are both capped, and overflow is dropped with a WARN naming the tenant. A failed
  submission is logged and dropped rather than retried: a stale freshness ping is worthless, an unbounded
  retry queue during an outage is a liability, and the pages are still crawled the ordinary way.

- **`llms.txt` is not known to do anything, and the docs now say so.** It is a community proposal, not a
  standard; no major engine documents consuming it; and Google states plainly that no AI-specific file or markup
  is needed to appear in its AI features. It is emitted because it is cheap and harmless, **not** because
  visibility depends on it. Any claim to the contrary in marketing copy is unsupported.
- ~~**No sitemap index.**~~ **Fixed in 1.5.0.** `/sitemap.xml` stays a single plain `<urlset>` while the site
  fits in one shard (10,000 URLs — which is every realistic tenant, and splitting a 40-URL site across an index
  would cost a crawler two fetches to learn nothing). Past that it becomes a `<sitemapindex>` naming
  `/sitemap-1.xml` … `/sitemap-N.xml`, each shard a conformant `<urlset>`.

  **Why shards of 10,000 rather than the spec's 50,000:** the spec cap is not the binding constraint. This is a
  public, anonymous route, and a 50,000-URL document with `hreflang` alternates is tens of megabytes assembled
  in the heap, per concurrent request. A smaller shard stays comfortably inside both the spec and a sane memory
  envelope, and costs a crawler only round trips it makes anyway.

  **The ceiling is real and visible:** 20 shards, so 200,000 URLs — four times the single-file limit it
  replaces. The host matches routes by exact path (no patterns, deliberately — a pattern on a public tenant
  route is a path-safety surface nobody needs), so each shard is a declared route. Past the ceiling it still
  WARNs loudly naming the tenant, rather than truncating in silence.

  **Shard membership is derived from the sorted URL list, not from source-registration order.** The kernel's
  listing is grouped by source in an order it does not promise, which says nothing across restarts or across the nodes of a cluster —
  and if boundaries followed it, two nodes behind one load balancer would serve *different content for the
  same shard number*, so a crawler alternating between them would watch URLs appear and vanish with nothing in
  the response to explain it.


## 8. Handover

Self-contained: the shared authoring guide ([`../../docs/PLUGIN-AUTHORING-GUIDE.md`](../../docs/PLUGIN-AUTHORING-GUIDE.md);
the kernel's [`docs/PLUGIN-DEVELOPMENT.md`](../../../docs/PLUGIN-DEVELOPMENT.md) is the reference for the SPI
services), a one-command verifier (`scripts/verify-against-host.sh`) and the before/after comparison
(`scripts/compare-with-previous.sh`). A build needs `runway-spi` and `runway-spi-testkit` in `~/.m2`
(`runway-app` too for the `host-it` profile), installed from the kernel at the root of this repository.
