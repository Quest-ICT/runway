# Runway Blog plugin — design & reference

> The plugins' reference for **routable content that is not a kernel page**: a plugin `ContentSource` whose
> items the kernel routes, renders in the tenant's own master chrome, lists in sitemap.xml / llms.txt and hands to
> search — using **only the Runway SPI** (SPI 1.0.0). Grounded in the Runway kernel as built.

## 1. What it does

- **Entries as virtual pages.** Each entry is served by the kernel at `<prefix>/<slug>` (default prefix `/blog`),
  or at a per-entry **custom absolute path** (`/announcing-v2`) for the default language, with **per-language
  slugs** (`/es/blog/hola-mundo`). The kernel does the routing (real pages always win first), the locale ladder
  (`?lang=`, cookie, `Accept-Language`, canonical redirects), the `<head>` (title, description, `hreflang`
  alternates, canonical), the `ETag`/`304`, and wraps the body in the **site's own master** (`_master`).
- **Body templates** shipped by the plugin: `entry-classic` (title, a meta line with the date and the byline, the
  body, a back link) and `entry-minimal` (title and body). The operator picks one per site.
- **Discoverability and search, through the kernel catalog.** Published entries are part of the site's content
  catalog, so sitemap.xml (with `hreflang` and `<lastmod>`), llms.txt and the URL map (`${urls['blog:<id>']}`, usable
  in any page) list them, and a search engine that indexes the catalog finds an entry by the words of its body
  (`content.indexable-text`: the body rendered to HTML, the same transform the page uses). The blog has no
  blog-specific code in any of those plugins.
- **Bylines and dates.** Each entry carries its own `author` and `publishedOn`; both cross the SPI as that entry's
  own values (`ContentRef.author`, `ContentRef.publishedAt`, midnight UTC; a non-ISO date yields no date), so
  structured data can attribute and date every post truthfully. Both are optional and render independently.
- **Console** (`/__admin/blog`, tenant console, gated by `blog|manage`): list, create, edit (per-language title,
  description, slug, Markdown body; date, author, custom path, published), publish/unpublish, delete. The editor
  warns when a **published page owns an entry's URL** in some language (the page wins, so the entry is unreachable
  there). Per-site settings (`enabled`, `prefix`, `template`) are edited on the kernel's generic settings form,
  gated by `blog|settings`.
- **Markdown** is rendered with [commonmark-java], bundled in this plugin's `lib/` and private to it. Entry
  Markdown is operator-authored, trusted content: raw HTML passes through, exactly as a page author may write
  markup.

## 2. Storage (the site's folder)

```
sites/<siteId>/plugins/blog/
└── entries/<entryId>/
    ├── entry.yml          # metadata (no id: the folder name is the id)
    ├── body-en.md         # one Markdown body per language
    └── body-es.md
sites/<siteId>/plugins/blog.yml   # the per-site settings (the kernel's settings store)
```

- Read and written through **`PluginSiteFiles`**, which confines every path to `plugins/blog/` (symbolic links
  included) and writes atomically (temporary file + rename), so a public reader never sees a torn `entry.yml`.
- `entry.yml` goes through the plugin's own **lenient** Jackson 3 `YAMLMapper`, so **an entry written by an earlier
  release or by hand loads unchanged** (a missing `published:` defaults, an unknown key is ignored) and is **written
  back byte for byte** (`BlogStoreTest` proves both with the fixture entries under `src/test/resources/fixtures`,
  written by blog 1.3.1 and including an entry with no `author`).
- **`updatedAt`** is the newest modification time of the entry's files (`FileEntry.lastModified`), so a console
  edit of only a body moves the `ETag` and `<lastmod>` with no explicit bump. It is read when the source lists the
  entries, and that list is cached until a change is announced (§3) — so a file edited **on disk** does not move
  them until then.
- **Metadata/body split**: listing reads only `entry.yml` files; a body is read only to render or index it — the
  `ContentSource` rule that keeps `/sitemap.xml` from reading the whole corpus.
- Ids (`[a-z0-9-]`, ≤ 100) and language codes (`[a-z]{2,8}`) are validated before any path is formed. One
  unreadable `entry.yml` is skipped with a WARN; it never blanks the blog.
- **Backups**: the entries live in the site's folder, so full backups carry them with no `BackupParticipant`.
  They are **published content, not personal data**, so nothing is declared under `siteData.personal` and a
  transfer copy carries them too.

## 3. Freshness: who drops what, and when

| Change | Announced by | What the kernel does | What the blog does |
|---|---|---|---|
| An entry is created, edited, (un)published or deleted | the console: `ContentChanges.changed(site, "blog", id)` after the write | evicts routing, the URL map and the catalog; relays to cluster peers; delivers a `CONTENT` `CatalogChange` | `BlogContentSource.onCatalogChanged` (SOURCE phase) drops the site's URL index and enumerated refs |
| The blog's settings are saved | the kernel's settings form | evicts the settings cache; delivers `SettingsChange` | `onSettingsChanged` drops the index (a prefix change re-keys every URL) |
| A site lifecycle event (incl. a backup **restore**) | the kernel | evicts its caches | `onSiteLifecycle` drops the index (the files were replaced wholesale) |

The source listens in the **`SOURCE` phase**: the kernel delivers every SOURCE listener first, evicts the catalog
once more, and only then lets consumers (sitemap, search) re-read — so none of them rebuilds from the blog's stale
index. A change replayed from a cluster peer arrives with `local = false` and drops this node's index the same way.
A file edited by hand **on disk** announces nothing, and the blog's index and listing have **no** time-to-live:

- a **new** entry folder, an `entry.yml` change (title, slug, path, published) or a deleted entry takes effect only
  after the next console save of any entry, a save of the blog's settings, or a site lifecycle event — a **site
  reload** (`POST /__reload/site/<id>`, Runway Administration §12.2) is the deliberate one;
- an edited **`body-<lang>.md`** is served at once (the body is read on every full render), but **under the old
  `ETag`** and with the old `<lastmod>`, and search keeps the old text: a browser or CDN holding the page
  revalidates to `304` and keeps the old body, until one of the events above. **After editing entry files on
  disk, reload the site.** (Measured against a packaged kernel: body changed on disk → `200` with the new body and the
  same `ETag`, conditional request → `304`; after the reload → a new `ETag`.)

**Result, for site managers:** an entry saved in the console serves at once, and is in sitemap.xml and search on
the next request; an unpublished entry is a 404 at once and leaves both (the verify script checks each of these).

## 4. The body-template contract

The templates read `${master}` and `${content.*}`. The SPI documents that render model on `ContentSource` /
`ContentRef.bodyTemplate` as a **contract**: a body template renders with
exactly

- `master` — the site master to wrap (`th:replace="~{${master} :: …}"`);
- `content` — `id`, `sourceKey`, `title`, `description` (in the rendered locale), `bodyHtml`, `attributes` (from
  `body()`), `updatedAt`;
- `locale`; and `urls` (link targets: `${urls['index']}`, `${urls['blog:<id>']}`).

Nothing else is promised. The shipped templates use only these;
**`BlogEntryTemplateContractTest` holds them to it**: statically (every `${…}` starts from one of the four
variables, every `content.` key is a listed one, no bean is reached, every `#{…}` key is in the plugin's bundle) and
by rendering them with Thymeleaf over a model built exactly to the contract, with no kernel class. The attributes
the blog puts in `body()` are its own: `publishedOn` and `author`, each present only when set.

## 5. The console screen

`BlogAdminController` carries **`@ConsoleScreen`**: the kernel's own advice fills the operator shell's model
(topbar, menus incl. this plugin's entry, language switcher, footer). Handlers return `ConsoleScreen.LAYOUT_VIEW` with
`CONTENT_TEMPLATE` set to `__plugin__/blog/admin-list|admin-edit` and `PAGE_TITLE_KEY`.

- **Tenancy** is always the bound one: `RequestContext.site()`; empty (the application console) → `400`.
- **Every handler** is `@PreAuthorize("@authz.may('blog|manage')")` (`AccessCheck`, bean `authz`).
- **Every state change is a `POST`** (create, update, publish toggle, delete) checked by the console chain's CSRF
  filter; each form carries `${_csrf}`. The three `GET`s only render. A test asserts this shape.
- **Validation before writing** — what the kernel's fail-closed re-filter would otherwise drop silently: a slug
  must match the slug grammar; a custom path must be absolute, free of `|` and `:`, and
  **`ContentCatalogView.servablePath`** (safe, not reserved, not an endpoint route such as `/sitemap.xml`).
- **The reverse advisory** asks `ContentCatalogView.pageAt(site, path, locale)` for each language's real URL.
- Console words come from `Messages.console` (the operator's UI language) and the plugin's bundle. The templates
  read only the controller's model plus `_csrf`, and only this plugin's message keys (the pretitle uses
  `blog.admin.pretitle`, not a kernel key) — `BlogAdminTemplatesRenderTest` checks both.

## 6. Settings

| Key | Type | Default | Notes |
|---|---|---|---|
| `enabled` | boolean | `true` | Off: nothing serves or lists; entries are kept. |
| `prefix` | string | `/blog` | Normalized (leading `/`, no trailing `/`); `/` puts entries at the top level. A prefix under which the kernel would not serve an entry (`/app`, `/__x`, `/actuator`, `|`, `..` — checked with `servablePath` on a child path) falls back to `/blog`. |
| `template` | enum | `entry-classic` | `entry-classic` or `entry-minimal`; anything else reads as classic. |

Read through **`PluginSettings.site(siteId)`** (stored value > descriptor default > code fallback; the code
fallbacks equal the descriptor defaults, which a test asserts).

## 7. Inventory

| Piece | Type | Role |
|---|---|---|
| `BlogConfig` | `mainClass` `@Configuration` | wires the three beans from SPI services; bean names `blogStore`, `blogContentSource`, `blogAdminController` |
| `BlogStore` | over `PluginSiteFiles` | `entries/<id>/entry.yml` + `body-<lang>.md` |
| `BlogEntry` | record | one entry's metadata (the `entry.yml` document) |
| `BlogSettings` | record | the effective per-site settings (`PluginSettings` + `servablePath`) |
| `BlogContentSource` | `ContentSource` (`blog`) + `SiteEventListener` (SOURCE) | enumerate / resolve (O(1) index) / body / indexableText; index invalidation |
| `BlogAdminController` | `@Controller` `@ConsoleScreen` | the authoring screens |
| `templates/` | decoupled Thymeleaf | `entry-classic`, `entry-minimal`, `admin-list`, `admin-edit` |
| `i18n/` | EN / ES | public labels + console |
| `lib/commonmark-*.jar` | bundled | Markdown |

## 8. What it links (SPI 1.0.0)

`pom.xml` depends on `runway-spi` only (`runway-spi-testkit` and Thymeleaf for tests; Spring, Spring Security and
Jackson 3 YAML as **provided** platform libraries); **`runway-app` is not a dependency in any scope**, and
`BlogConfigTest` scans the compiled classes' constant pools for any `mx.quest.runway` reference outside
`mx.quest.runway.spi`.

The SPI services it uses: `PluginSiteFiles` (with the plugin's own `YAMLMapper`), `PluginSettings`,
`ContentCatalogView` (`servablePath`, `pageAt`), `ContentChanges`, `SiteEventListener` (SOURCE phase),
`RequestContext` (`SiteView`), `Messages.console` and `@ConsoleScreen`.

`requiresSpi: ">=1.0.0"`: a kernel with an older SPI blocks the plugin before wiring, with a reason.

## 9. Verify

`scripts/verify-against-host.sh` (honours `JAVA_HOME` (required), `MVN`, `HOST_TARGET`, `HOST_JAR`, `VERIFY_TMPDIR`,
`VERIFY_LOG_DIR`, `COMPANIONS`; never runs
`mvn install`, never needs `--keep`) boots the **packaged** host in ACTIVE mode with the wizard's default site made
bilingual (EN default, ES), the **1.3.1 fixture entries** copied into it, and — built offline from copies of the
sibling folders, skipped if they cannot be built — the `discoverability` and `search` plugins. It checks:

- the plugin loads (registered, 0 blocked) with **zero** host-linkage and bean-name/surface findings (the kernel
  logs "linkage scan: …" and blocks a plugin that links host internals; a surface problem names the bean) and no
  WARN or ERROR about it, and the application console's plugins screen shows it Loaded with no health note (this check
  has no positive control since every companion became SPI-only; one needs a deliberately host-linked fixture jar);
- the 1.3.1 entries serve unchanged: default URL, localized slug in Spanish, custom path, the byline and date, the
  entry without an author (date, no byline), the draft a 404, an `ETag` that revalidates `304`, and a page's
  `${urls['blog:hello-world']}`;
- sitemap.xml and llms.txt list the published entries and not the draft; search finds an entry by a body word;
- the console renders inside the shell, is gated for anonymous visitors, and every mutation is a CSRF-checked POST
  (no token → `403`, nothing written; a `GET` on a mutation path changes nothing);
- create → serves at once, in sitemap.xml and search on the next request; edit the body → search follows;
  unpublish → `404` and gone from both; a reserved custom path is refused at save; a prefix change through the
  settings form re-keys the URLs at once (and a reserved prefix falls back to `/blog`); delete removes the folder;
- a full backup and a transfer copy both carry the entries.

Unit tests (67) run on the testkit's fakes with no kernel class: the store (`BlogStoreTest`, incl. the 1.3.1
fixture read in place and written back byte for byte, confinement through a planted symbolic link), the source and
its listener (`BlogContentSourceTest`, driven by `EventDispatch` in the kernel's order), the controller
(`BlogAdminControllerTest`: parsing, validation, announcements, advisory, tenancy, the screen's POST-only shape),
the console templates (`BlogAdminTemplatesRenderTest`), the body-template contract
(`BlogEntryTemplateContractTest`), and the plugin's contract with the host (`BlogConfigTest`: wiring, bean names,
descriptor, EN/ES parity, the constant-pool scan).

## 10. Not covered (honest gaps)

- **No listing page.** There is no `/blog` index of entries; link entries from a site page with
  `${urls['blog:<id>']}`. A listing would be a widget or a virtual page — a feature for a later release.
- **No per-entry `noindex` or canonical override**, no categories or tags, no RSS/Atom feed.
- **`author` is free text** (a display name), not an account.
- **Concurrent edits of one entry**: the last save wins (each file write is atomic; there is no merge).
- **A file edited on disk** is not picked up until something announces a change (any console save, a settings
  save, a site reload); an edited body is served at once but under the old `ETag` until then (§3). Reload the site
  after editing entry files by hand.

[commonmark-java]: https://github.com/commonmark/commonmark-java
