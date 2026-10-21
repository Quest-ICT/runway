# The `runway-demo` plugin — a walkthrough of the Runway plugin contract

> The **kitchen-sink reference plugin**. Every extension axis and every service of the SPI service tier
> is used here once, in the place a real plugin would use it, with a comment in the code saying
> **when you would use it**. Read this document top to bottom to learn the contract; copy the folder to start a
> plugin. The canonical rules live in the authoring guide (`../../docs/PLUGIN-AUTHORING-GUIDE.md`); this document
> shows them working.

- **id** `runway-demo` · **install folder** `external/plugins/runway-demo/` · **Java package** `mx.quest.demo`
- **version** `2.1.0` · **`requiresSpi`** `">=1.0.0"` · links **only** `mx.quest.runway.spi`
- **jar** `runway-plugin-demo.jar` (thin: 18 top-level classes, nothing bundled) · **Maven artifactId**
  `runway-plugin-runway-demo` · **release zip** `runway-plugin-runway-demo-<version>.zip`
- **Verified** live against the packaged kernel (§9) and by 82 unit tests with 20 mutation checks (§8).

---

## 1. What it does

| Feature | Who sees it | Built on |
|---|---|---|
| A generator `<meta>`, the plugin stylesheet and an inert analytics `<script>` in every page's `<head>` | visitors | `HeadContributor` + `PluginSettings` |
| A notice callout at `data-widget="demo-notice/<instance>"` | visitors | `ContentWidget` + `PluginSettings` + `Messages.site` |
| A "leave a message" form at `data-widget="demo-feedback/<instance>"`, posting without a CSRF token | visitors | `ContentWidget` + an **anonymous** `POST` route |
| `/demo/hello.txt`, `/demo/echo.json`, `/demo/notes.json` (permission-gated) | visitors / operators | `EndpointContributor` + `AccessCheck` + `Messages.site` |
| SEO advice in the page editor (long title, no description, no `<h1>`) | site managers | `PageQualityCheck` + `PluginSettings` |
| Per-site **notes** (operator notes, visitors' messages, a settings audit note), personal data | operators | `PluginSiteFiles` + `siteData.personal` |
| A **showcase** page at `/demo/showcase`, published from the console | visitors | `ContentSource` + `ContentChanges` + a `SOURCE` listener |
| A **demo page** at `/demo` installed in one click | site managers | `SitePages` + `AccessCheck` |
| The demo's **console screen** (`/__admin/runway-demo`) | operators | `@ConsoleScreen` + `RequestContext` + `Messages.console` + `ContentCatalogView` |
| *Recent changes* on that screen, with URLs before and after | operators | `SiteEventListener` (`CONSUMER`) + `Sites` |
| A dashboard card and an admin fragment under the settings form | operators | descriptor + templates + `Sites`, `SitePages.pathOf`, `PluginSettings.secret`, `PluginInfo` |
| A start/stop hook logging the built-against and running SPI | the boot log | `PluginLifecycle` + `PluginInfo` |

---

## 2. The files

```
demo/
├── plugin.yml                      the descriptor: floor, personal data, permissions, both settings schemas, menu, card
├── pom.xml                         runway-spi (+ runway-spi-testkit for tests) and platform libraries, all provided
├── CHANGELOG.md · README.md
├── scripts/verify-against-host.sh  build → throwaway Runway → boot ACTIVE → 58 live checks
├── scripts/install-into.sh         copy the assembled plugin into an external/ tree
├── i18n/messages[_es].properties   console AND public text (add-only)
├── static/                         css/demo.css · js/demo-analytics.js · notes-help.html  (/__plugin/runway-demo/static/…)
├── templates/                      decoupled Thymeleaf (.html markup + .th.xml logic)
│   ├── console.*                   the @ConsoleScreen content
│   ├── showcase.*                  the content source's body template
│   ├── dashboard.*                 the dashboard card
│   └── admin.*                     the fragment under the settings form (filename convention)
└── src/main/java/mx/quest/demo/
    ├── DemoPluginConfig            mainClass: every bean, wired explicitly, named runwayDemo…
    ├── DemoAppProps                app-level settings (@ConfigurationProperties via @Bean)
    ├── DemoAnalyticsHead           HeadContributor
    ├── DemoNoticeWidget            ContentWidget "demo-notice"
    ├── DemoFeedbackWidget          ContentWidget "demo-feedback" (the anonymous form)
    ├── DemoMetaLengthCheck         PageQualityCheck
    ├── DemoEndpoints               EndpointContributor (3 GETs + the anonymous POST)
    ├── DemoNotesStore · Note · NotesFile           PluginSiteFiles: notes.yml
    ├── DemoShowcaseStore · Showcase                PluginSiteFiles + ContentChanges: showcase.yml
    ├── DemoShowcaseSource          ContentSource + SiteEventListener (SOURCE)
    ├── DemoActivity                SiteEventListener (CONSUMER) + Sites
    ├── DemoLifecycle               PluginLifecycle + PluginInfo
    ├── DemoAdminModel              backs the card and the admin fragment
    ├── DemoConsoleController       @ConsoleScreen: RequestContext, AccessCheck, Messages.console, SitePages, ContentCatalogView
    └── Html                        escaping (every axis that returns HTML owns its escaping)
```

---

## 3. Wiring: how a plugin gets the kernel's services (`DemoPluginConfig`)

The kernel registers `mainClass` into its Spring context **before refresh**; the plugin's `@Bean` methods receive
the services as ordinary parameters. There is nothing else to learn — no lookup, no handle, no host class.

```java
@Bean
public DemoShowcaseStore runwayDemoShowcaseStore(PluginSiteFiles files, ContentChanges changes) {
    return new DemoShowcaseStore(files, changes);
}
```

- **Plugin-scoped services** — `PluginSettings`, `PluginSiteFiles`, `ContentChanges`, `PluginInfo` — are bound to
  *this* plugin by **the jar the injecting class was loaded from** (`DemoPluginConfig` is in the plugin's own jar).
  No method takes a plugin id, so a plugin can never read another's settings or files. Inject them in your own
  classes: injection from a `lib/` jar, from kernel code, or through a bare `getBean(...)` fails at startup.
- **Kernel singletons** — `Sites`, `RequestContext`, `AccessCheck`, `Messages`, `SitePages`, `ContentCatalogView`.
- **Bean names start with the plugin id in lowerCamelCase** (`runwayDemo…`); the kernel
  blocks a plugin with any other name. The demo's app-level settings bean is therefore a
  `@Bean @ConfigurationProperties("runway.plugin.runway-demo")` method, **not** `@EnableConfigurationProperties`
  (which names the bean `runway.plugin.runway-demo-mx.quest.demo.DemoAppProps`).
- **What a plugin may contribute to Spring:** SPI implementations, plain beans, and `@Controller`s mapped under
  `/__admin/<id>` or `/__app/<id>`. **Not:** servlet `Filter`s, `SecurityFilterChain`s, bean (factory)
  post-processors, unrestricted `@ControllerAdvice`. Return the **concrete** class from a `@Bean` method so Spring
  sees every interface it implements (`DemoShowcaseSource` is a content source *and* a listener).

---

## 4. The service tier, one service at a time

Each subsection: what the demo does, where, and **when you would use it**.

### 4.1 `PluginSettings` — my per-site settings

*Where:* `DemoAnalyticsHead`, `DemoNoticeWidget`, `DemoFeedbackWidget`, `DemoMetaLengthCheck`, `DemoEndpoints`,
`DemoAdminModel`.

```java
SettingsView site = settings.site(view.site().id());
if (!site.bool("notice.enabled", true)) { return RenderFragment.empty(); }
int maxChars = (int) site.integer("notice.max-chars", 280, 1, 2000);   // clamped
```

`site(siteId)` returns the descriptor's `siteSettings:` defaults overlaid by what the operator saved; the typed
accessors never throw (a bad value yields the fallback). `secret(siteId, key)` hands a `type: secret` value back in
clear — `DemoAdminModel.siteTokenConfigured` only asks whether `analytics.site-token` is set and never shows it.
**Use it** for every per-site setting read; a save applies at once on every node. App-level (platform) settings
are not here: they bind through `@ConfigurationProperties` under `runway.plugin.<id>.*` and apply on restart
(`DemoAppProps`).

### 4.2 `PluginInfo` and `PluginLifecycle` — who am I, and the start/stop hook

*Where:* `DemoLifecycle` (and `PluginInfo` injected into `DemoAdminModel` and the console controller).

`started(PluginInfo self)` runs once the platform is up, in plugin load order; `stopping()` at shutdown, in reverse
order, only if `started` returned normally. Both block the host's thread — keep them short. The demo logs
`runway-demo 2.1.0 started — install dir …; built against SPI 1.0.0, running on SPI 1.0.0`:
`RunwaySpi.VERSION` is a compile-time constant (what the plugin was **built against**, copied into the plugin's
classes), `RunwaySpi.runtimeVersion()` is the running kernel's. **Use it** to open an
index, start your own executor, warm a cache — and to close them again.

### 4.3 `PluginSiteFiles` and `siteData.personal` — my per-site data

*Where:* `DemoNotesStore` (`notes.yml`), `DemoShowcaseStore` (`showcase.yml`).

The plugin's folder of a site, `sites/<id>/plugins/runway-demo/`: inside the site folder, so **full backups carry it
and a deleted site takes it along** — no `BackupParticipant` needed. Paths are relative, confined (no `..`, no
symlink escape), each write is atomic; a **read-modify-write** (append a note) is the plugin's to serialize, which
the store does per site. A write for a site whose folder does not exist fails rather than creating it.

**A `GET` never writes.** Opening the console screen, the settings page or `/demo/notes.json` only reads. A site
with no notes yet shows a welcome note, but the store does not save it then: the site has no `notes.yml` until the
first write — a console note, a visitor's message or the settings audit note. That first write saves the welcome
note together with the new note, so the note the operator saw stays in the list (it gets the time of that write).
**Rule:** a page that only shows data must not change a site. Show defaults from memory; write on the first `POST`.
(2.0.0 wrote the welcome note on the first read, so each site got a `notes.yml` as soon as someone opened the
screen.)

`notes.yml` holds what people typed, so `plugin.yml` declares it:

```yaml
siteData:
  personal:
    - notes.yml
```

A **transfer copy** of the site (for someone outside the organisation) leaves it out and lists it in the
manifest; a full backup keeps it. **Rule:** any file holding visitor or user input is personal. The showcase is
published content and is not declared.

### 4.4 `Sites` — a site's view from its id, off-request

*Where:* `DemoAdminModel.siteName` (a console fragment only has `tenantSiteId`), `DemoActivity.onSiteLifecycle`
(a listener only has the event's site id).

**Use it** whenever code that knows only an id — a listener, a background task, a fragment — needs the site's
name, locales or base URL. Empty for an unknown id; an unsafe id throws.

### 4.5 `RequestContext` — which site is this console request about?

*Where:* `DemoConsoleController.boundSite()`.

A console screen has no `RouteRequest`; `RequestContext.site()` is how it learns the tenancy. It is **empty on the
application console** (`/__app/…`), so the screen answers `404` there. An endpoint does not need it: its
`RouteRequest` already carries the site.

### 4.6 `AccessCheck` — may the current user do this?

*Where:* `DemoEndpoints` (`/demo/notes.json` → `403` without `runway-demo|manage-notes`), every console handler
(`@PreAuthorize("@authz.may('runway-demo|manage-notes')")`), the starter-page install (`may("EDIT_PAGES")`, a
kernel permission), and the admin fragment (`${@authz.may('runway-demo|manage-notes')}` shows the screen link).

One bean, three spellings: inject `AccessCheck` in Java, `@authz` in `@PreAuthorize` and templates. Fail-closed:
no request, no principal, an undeclared key, or an anonymous route → `false`. **Use it** every time your code
decides; a menu entry's or card's `permission:` only hides UI (and must be the full key `<id>|<permission>`).

### 4.7 `Messages` — localized text for visitors and for operators

*Where:* `Messages.site` in `DemoEndpoints.hello`, both widgets, the showcase title, the starter page title;
`Messages.console` for the console screen's flash messages.

`site(siteId, locale, code, fallback, args…)` resolves the site's own bundle first, then every plugin's `i18n/`
(add-only), in the **visitor's** locale; `console(code, fallback, args…)` resolves the console bundles in the
**operator's** UI language. With arguments the text is a `MessageFormat` pattern (write `''` for an apostrophe).
Templates use `#{…}` for the same console bundle.

### 4.8 `SitePages` — install my starter page

*Where:* `DemoConsoleController.installPage` (and `pathOf` in `DemoAdminModel` and the screen).

The demo page (`/demo`, slug `runway-demo`, `noindex`) carries both widget anchors and pulls the site's chrome
through `${master}`. `install` never overwrites and **never publishes around the site's editorial level**:

| Outcome | When | The demo says |
|---|---|---|
| `PUBLISHED` | Advisory site; Enforced/Reviewed with the checks passing and the right to approve | "Demo page published at /demo." |
| `PENDING_REVIEW` | Reviewed site, the user lacks `APPROVE_PAGES` | "… waiting in the review queue." |
| `DRAFT_BLOCKED` | the quality checks blocked publishing | "… as a draft: the quality checks blocked publishing." + the findings |
| `ALREADY_EXISTS` | the slug exists (any state) | "A demo page already exists at /demo; nothing changed." |

The `switch` has a `default` branch (the enum may grow). The plugin checks the caller may edit pages **before**
calling — `SitePages` leaves that to the plugin. `pathOf` finds the page wherever an operator moved it.

### 4.9 `ContentCatalogView` — the site's published content surface

*Where:* the console screen's *Content surface* card.

`entries(siteId)` lists every published page and content item with the URLs **the host computed** (the same
computation as the page `<head>`, so a listing never disagrees with the pages). `pageAt(siteId, path, locale)`
answers which published page owns a path; `servablePath(siteId, path)` whether content may live there at all (not
`/__…`, `/login`, an endpoint route…). `refresh(siteId)` drops the cached listing — a manual reindex; ordinary
changes never need it. **Use it** for a sitemap, a search index, an "is this path taken?" check.

### 4.10 `ContentSource` + `ContentChanges` — my own routable content

*Where:* `DemoShowcaseSource` (the source), `DemoShowcaseStore.publish/unpublish` (the announcements).

The source serves `/demo/showcase` through the kernel's routing, the site's chrome, the sitemap and on-site search
(`CAP_INDEXABLE_TEXT`). `enumerate` is metadata-only (it runs on anonymous requests); `resolve` is a cheap path
comparison; `body` renders the escaped text into `templates/showcase.*`, which may rely only on `${master}`,
`${content.*}`, `${locale}` and `${urls[…]}`. Every publish moves `updatedAt`, which the page's `ETag` folds.

After every write, the store announces it — after the write, outside its own lock:

```java
files.writeText(siteId, FILE, yaml.writeValueAsString(...));
changes.changed(siteId, DemoShowcaseSource.SOURCE_KEY, DemoShowcaseSource.ID);
```

On return, routing, the listing, sitemaps and search have been evicted on every node and every listener on this
node has run. The kernel refuses a source key the plugin does not own, and a call from inside a
`SiteEventListener` callback (a test proves the second).

### 4.11 `SiteEventListener` — react to changes

*Where:* `DemoShowcaseSource` (phase `SOURCE`) and `DemoActivity` (phase `CONSUMER`, the default).

- **`SOURCE`** is for a content source dropping its own state. All `SOURCE` listeners run first, the kernel evicts
  its listing again, then the `CONSUMER`s — so a consumer that re-reads the listing never sees a source's stale
  state. The showcase source evicts its cached read on its own `CONTENT` changes (a change **replayed from another
  node** is the only way this node learns the file changed) and on wholesale lifecycle events (`DELETED`,
  `RESTORED`).
- **`CONSUMER`** reacts to everything else. `DemoActivity`:
  - `onSettingsChanged` — a save of **this plugin's own** settings; appends the audit note **only when
    `change.local()`**, so a cluster writes it once, not once per node;
  - `onCatalogChanged` — records each change (`page`, `content`, `site`, `settings`) with `previousUrls` (where
    the item was — a deleted page still names its old address) and `currentUrls` (where it is now); the console's
    *Recent changes* shows them;
  - `onSiteLifecycle` — logs the event with the site's name (`Sites`) and drops what it recorded on a wholesale
    event.

The kernel guarantees: every kernel cache is already evicted when a callback runs; no host lock is held (calling
any service is safe); a throwing callback is logged and counted, never propagated. Callbacks run on the writer's
thread: **evict, record or enqueue** — anything slow belongs on your own executor.

### 4.12 `@ConsoleScreen` — a screen inside the console

*Where:* `DemoConsoleController` + `templates/console.*`; the menu entry opens it.

```java
@Controller
@ConsoleScreen
@RequestMapping("/__admin/runway-demo")
public class DemoConsoleController {
    @GetMapping
    @PreAuthorize("@authz.may('runway-demo|manage-notes')")
    public String screen(...) {
        model.addAttribute(ConsoleScreen.CONTENT_TEMPLATE, "__plugin__/runway-demo/console");
        model.addAttribute(ConsoleScreen.PAGE_TITLE_KEY, "runwaydemo.console.title");
        return ConsoleScreen.LAYOUT_VIEW;
    }
}
```

The kernel fills the shell (topbar, menus, language switcher, footer); the plugin needs no `@ControllerAdvice`.
Every mapping lies under `/__admin/runway-demo`, so the console's sign-in, realm security and CSRF apply. The
content template may rely on `tenantSiteId`, `_csrf`, `${@<own bean>…}`, `${@authz.may(…)}`, `#{…}` — and on
what the controller itself put in the model (`rd`). The screen renders its own flash (`runwayDemoFlash`,
`runwayDemoError`); the shell's flash region is not promised to plugins.

**The `POST` rule.** The `GET` only reads. Everything that changes state — add a note, install the page, publish or
unpublish the showcase, refresh the listing — is a `<form method="post">` carrying the session's token
(`<input class="rd-csrf" type="hidden" name="_csrf">`, bound in `console.th.xml` to `${_csrf}`), followed by a
redirect (post/redirect/get). A link or an `<img>` on another web site can therefore never act through a signed-in
operator's browser. Spring MVC binds the form fields (`@RequestParam`).

### 4.13 The anonymous `POST` route — a public form without a token

*Where:* `DemoEndpoints` (`Route.anonymousPost("/demo/feedback", …)`) + `DemoFeedbackWidget`.

A form on a page every visitor shares (and caches) cannot carry a per-session CSRF token, and an anonymous visitor
has no session. Declaring the route `anonymous` exempts **exactly that method and path** from CSRF; the kernel
makes that safe by running the handler with **no principal** (`AccessCheck.may` is `false` even for a signed-in
administrator) and accepting only an `application/x-www-form-urlencoded` body (`415` otherwise). The handler keeps
its own guards — a honeypot field, a length cap, five messages per minute per `req.clientIp()` (the address after
the kernel's trusted-proxy gate; never a header) — reads the fields from `req.form()` only (personal text never in
a URL), and answers `303` back with only an outcome flag the widget renders. **Use it** only for a form any visitor
may submit with no account; never for an action on something a signed-in user owns.

---

## 5. The axes (pure functions of their inputs)

| Axis | Class | Notes |
|---|---|---|
| `HeadContributor` | `DemoAnalyticsHead` | `@Order(200)`; returns `""`, never `null`; `headInputs` names `plugins/runway-demo.yml` so a settings change moves the page `ETag`; emits plain `<script src>` (the kernel stamps the CSP nonce) |
| `ContentWidget` | `DemoNoticeWidget`, `DemoFeedbackWidget` | anchor `<div id="…" data-widget="<key>/<instance>">`; `RenderFragment.empty()` to stay silent; reads only its view (the query, the path) |
| `PageQualityCheck` | `DemoMetaLengthCheck` | an `ERROR` blocks publishing on Enforced/Reviewed sites — the demo's `h1.missing` is an `ERROR` on purpose |
| `EndpointContributor` | `DemoEndpoints` | exact paths, tenant realm; `RouteRequest` carries locale, query, form, lowercase headers, `clientIp`, `origin` |
| `ContentSource` | `DemoShowcaseSource` | §4.10 |

A throwing axis is contained by the kernel (empty head/widget output served `no-store`, a `500` for an endpoint)
and counted on the plugins screen.

---

## 6. What the demo deliberately does not do

- **No servlet filter.** **Servlet filters are outside the Runway 1.0 plugin contract:** a filter needs servlet types the SPI does not
  carry, runs on every request of every site, and no feature plugin needs one. `FilterBand` stays a kernel concept
  (it places the kernel's and the essential protection plugin's filters). A plugin that wants to shape a response
  uses an axis — a head contributor, a widget, an endpoint. The kernel blocks a plugin with
  a `Filter` bean.
- **No `BackupParticipant`.** The notes live in the site's folder, which backups carry. The axis remains for data a
  plugin keeps *outside* the site folder (none in 1.0).
- **No `@Primary`.** That is for the singleton kernel seams the essential `protection` plugin arms; every demo
  contribution is collected by type.
- **No `requires:` / `conflicts:`** (a hard dependency would stop the demo from always loading); the forms are in
  the descriptor's comments.
- **`essential` is not claimed** — only kernel security-floor ids on the allowlist may.
- **No per-instance widget config** (`widgetSettings:`): the `search` plugin is the example of that.

---

## 7. Templates and i18n

- **Decoupled Thymeleaf**: markup in `.html` with zero `th:*`, logic in the sibling `.th.xml`. A repeated `id` in a
  `.html` is a hard error (a test forbids it); iterated rows use classes and `//` selectors. Form actions are
  constants in the markup (`th:action`/`th:attr` run in a restricted context).
- **Fragments** (`dashboard`, `admin`) are bare (a root element, no `<html>`) and read the plugin's own bean
  `${@runwayDemoAdminModel…}` with `tenantSiteId`.
- **The body template** (`showcase`) pulls the site's chrome by the `${master}` variable, never a literal name.
- **i18n**: `i18n/messages[_es].properties` is appended to the console's and every site's message sources
  (add-only). The descriptor's labels, the console templates' `#{…}` and both `Messages` calls read it. A test
  keeps English and Spanish in step and fails on a key used but missing.

---

## 8. Tests

`mvn verify` runs 82 tests over `runway-spi-testkit` (no kernel, no Spring Boot):

| Class | Covers |
|---|---|
| `DemoSpiUnitTest` | head, both widgets, quality check, every endpoint route incl. the anonymous feedback POST (honeypot, blank, query ignored, rate limit, foreign return path), lowercase header names |
| `DemoNotesStoreTest` | the notes over `TempDirSiteFiles`: a read shows the welcome and writes nothing, the first append keeps it, order, caps, isolation, lenient reads, an unreadable file never overwritten, a missing site, unsafe ids |
| `DemoShowcaseTest` | publish/unpublish announce through `EventDispatch`, the source's refs/body/resolve, `updatedAt`, the TTL backstop, eviction on a replayed change and on wholesale events only, the kernel's refusal from inside a callback |
| `DemoActivityTest` | own vs other plugins' settings, `local`, URLs before/after, the bound, wholesale drops, failures contained |
| `DemoConsoleControllerTest` | the screen and each action through MockMvc: the shell model, opening the screen writes no file, 404 on the app console, every flash, `EDIT_PAGES`, every install outcome, a failed install, the path check, refresh |
| `DemoTemplatesTest` | every `POST` action has a form carrying the CSRF field; no link points at an action; no repeated id; every selector matches |
| `DemoPluginConfigTest` | wiring from SPI fakes alone, bean names, forbidden Spring types, the controller's gates and mappings, template bean references, descriptor (floor, version, personal data, menu, secrets), i18n, **the compiled classes link no `mx.quest.runway` class outside `.spi`**, the removed classes are gone |

Mutation-checked: 20 deliberate breaks (the `local` gate, each eviction, each announcement, the honeypot, the rate
limit boundary, the permission gates, body-only fields, a missing `@PreAuthorize`, the notes bound, the
personal-data declaration, the TTL boundary, escaping, the open-redirect guard, a form's CSRF field, an
unprefixed bean name, the clamp) — each makes a test fail.

---

## 9. Build, install, verify

```bash
export JAVA_HOME=<a JDK 25>
cd demo && mvn verify
#   target/runway-demo/                             the install-ready folder
#   target/runway-plugin-runway-demo-<version>.zip  the archive the management UI upload expects (byte-reproducible)
JAVA_HOME=/path/to/jdk-25 HOST_JAR=…/runway-app-1.0.0-exec.jar VERIFY_TMPDIR=… VERIFY_LOG_DIR=… scripts/verify-against-host.sh
```

The first build downloads its Maven dependencies, and needs the `runway-spi` jars installed in `~/.m2` as the
[authoring guide §2](../../docs/PLUGIN-AUTHORING-GUIDE.md#2-prerequisites) describes.

The verify script never runs `mvn install`. Its result for 2.1.0: **58 passed, 0 failed**.
From the host log:

```
plugins: 1 registered, 0 blocked
runway-demo 2.1.0 started — install dir …/plugins/runway-demo; built against SPI 1.0.0, running on SPI 1.0.0
runway-demo settings changed for site 'default': audit note appended (now 4 notes)
transfer export: blanked 1 secret value(s) in runway-demo.yml: [analytics.site-token]
```

Four notes: the welcome note (stored by the first write, a visitor's message), that message, the operator's note
and the audit note. Before the first write, the signed-in site user (`owner`) opened the screen, `notes.json` and the settings page, and
the site still had no `notes.yml`.

The log shows no host-linkage ("uses host internals"), bean-name or surface finding — each would block the plugin,
which the script's `is blocked` check catches. *Recent changes* after installing the page and publishing then
unpublishing the showcase (newest first):

```
content  runway-demo:showcase   before /demo/showcase   after —
content  runway-demo:showcase   before —                after /demo/showcase
page     pages:runway-demo      before —                after /demo
page     pages:runway-demo      before —                after /demo
```

(The install is two page writes — the draft, then its publication.)

---

## 10. Known limits

- A non-anonymous `EndpointContributor` `POST` is not limited to header tokens: it reads every form field whether
  the CSRF token travels as a form **field** or in the `X-CSRF-TOKEN` header. The demo keeps its operator actions in a
  `@ConsoleScreen` controller by choice.
- *Recent changes* is per node and in memory (it is a demonstration of the callback, not an audit log); it starts
  empty after a restart.
- Changes nobody announces (a file edited by hand) reach the showcase within its 60-second cache lifetime and the
  content listing within its own 30 seconds.
