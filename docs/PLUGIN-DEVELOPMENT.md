# Runway — Plugin Development Guide

> **Audience:** plugin authors (first-party and third-party).
> **What this is:** how to write, package, install, and test a Runway plugin — the descriptor,
> the contribution seams, settings, permissions, and the rules that keep plugins compatible.

> **Doc status.** The plugin contract is **SPI `1.0.0`, frozen** (Runway `1.0.0`): from here it changes only
> additively within `1.x` (SPI `1.1.0` added `PluginSettings.appSecret`) — the commitment and the full type list are in [SPI-STABILITY.md](SPI-STABILITY.md); the
> `plugin.yml` keys and their tolerance rules are covered by
> [CONFIGURATION-AND-DATA-STABILITY.md](CONFIGURATION-AND-DATA-STABILITY.md) §4. A plugin is built against
> `runway-spi` **only**; the kernel refuses one that links its internal classes (§3, *The 1.0 contract at a
> glance*). The reference plugin to read is `runway-demo` under `plugins/demo/`, which exercises every
> service; the protection module in §12 is the kernel's own **essential** plugin — documented here because it ships
> with Runway, and explicitly **not** an example to copy (it is the one plugin allowed to link kernel classes).

---

## 1. What a plugin is

A plugin is a **folder** installed under the platform's external plugins directory:

```
external/plugins/<id>/
├── plugin.yml            # the declarative descriptor (required)
├── <your-plugin>.jar     # the thin jar of plugin code (omit for a code-less provider — §9)
├── lib/                  # optional: bundled third-party jars
├── static/               # optional: assets served at /__plugin/<id>/static/**
├── templates/            # optional: Thymeleaf templates, referenced as __plugin__/<id>/<name>
└── i18n/                 # optional: message bundles, appended to the host sources at load — see §8
```

The folder **name is the plugin id**, and the id in `plugin.yml` **must match it** — a mismatch is
logged and the plugin is skipped. The id is validated as a safe directory name (no traversal, no
separator characters), so it can be used verbatim in URL and cache-key namespaces.

Four principles are baked into the loader; internalize them before writing any code:

- **Resources live in the folder, not inside the jar.** The descriptor, `static/`, `templates/`,
  `lib/`, and `i18n/` are all on disk so an operator can inspect and edit them without a rebuild.
  The jar carries only compiled classes.
- **Restart to install.** Installing, upgrading, disabling, or removing a plugin is a change to the
  folder followed by a **restart** — there is no hot reload. The Spring context, security chains,
  template resolvers, and MVC mappings are built **once** at boot; rebuilding them live is the
  fragile path Runway deliberately avoids (§10).
- **Degrade, don't crash.** A malformed, disabled, blocked, or throwing plugin is logged as one line
  and skipped — the platform always boots. Your plugin's failure never takes the server down (the one
  exception is a *security-floor* plugin, which fails **closed** on purpose — §10).
- **Trusted code with a narrow contract, not a sandbox.** A loaded plugin runs in the host JVM with a
  classloader whose parent is the application classloader, and its beans wire into the kernel context as
  first-class peers. What it may *use* of the host is the SPI alone — the kernel refuses at startup a plugin that
  links an internal class (§3) — but that is a compatibility contract, **not** a security boundary. Only install
  plugins you trust as you would the application itself.

---

## 2. The plugin descriptor (`plugin.yml`)

`plugin.yml` is the single declarative descriptor the kernel reads **without loading any plugin
code** — so it works for disabled, blocked, or broken plugins too. It is parsed with unknown keys
**ignored in both directions** (a newer file read by an older kernel, or vice-versa, never blows up),
so the descriptor evolves additively. Realm values (`application` / `tenant`) are accepted
case-insensitively.

### Fields

| Key | Type | Meaning |
|---|---|---|
| `id` | string | **Required.** Stable id = the install-dir name. A path-safe identifier (no traversal / separators). |
| `name` | string | Display name shown in the operator console. |
| `version` | string | **Plain `MAJOR.MINOR.PATCH`** (e.g. `1.0.0`). A pre-release/qualifier segment (`1.0-RC1`, `1.0.x`, `v1`) is a hard descriptor error. |
| `mainClass` | string | The `@Configuration` class to register, fully qualified. **Omit** it for a code-less library/asset provider (§9). |
| `enabled` | boolean | `false` parks the plugin (stays on disk, never loads). **Absent = enabled.** |
| `essential` | boolean | Advisory only. Honored (drives fail-closed mode) **only** if the id is on the kernel's security-floor allowlist; otherwise ignored with a warning (§10). |
| `requires` | list of strings | Hard dependency gates: `id`, `id >= v`, or `id == v` (§10). |
| `conflicts` | list of strings | Rival ids the declarer steps aside for (§10). |
| `requiresSpi` | string | The **minimum** kernel SPI level, written `">=X.Y.Z"`. A kernel below it **blocks** the plugin at startup rather than letting it crash mid-request (§10). **Required for a plugin with a `mainClass`** from SPI `1.0.0`, and at least `>=0.4.0` — a missing or older floor marks a plugin built for the pre-1.0 contract, which is blocked (library-only plugins are exempt). Only a minimum — a `<=` ceiling is refused. |
| `permissions` | list | Declared grantable permissions (§6). |
| `settings` | list | App-level settings schema (§5). |
| `siteSettings` | list | Per-tenant settings schema (§5). |
| `widgetSettings` | map | Per-instance content-widget schema, keyed by a widget's `key()` (§5); each value is a settings schema. |
| `settingGroups` | list | Optional titled sections of the settings forms, `{ id, label, help, helpAnchor }`, in display order (§5, *Laying out the form*). |
| `helpUrl` | string | Optional help page (a path such as `/__plugin/<id>/static/help.html`, or an http(s) URL) that `helpAnchor` links into (§5). |
| `menu` | list | Admin-menu contributions (§4). |
| `dashboardCards` | list | Dashboard-card contributions (§4). |
| `siteData` | map | `personal:` — the paths of your per-site subtree that hold **personal data**; a transfer copy of a site leaves them out (§3, *Personal data*). Understood from SPI `0.5.0`. |

### Descriptor rules (enforced at discovery, each a skip-and-log)

- `id` present and a safe directory name.
- `version` parses as plain `MAJOR.MINOR.PATCH`.
- every `requires` entry parses as exactly `id`, `id >= v`, or `id == v` (single-spaced).
- `requiresSpi`, if present, parses as exactly `>=X.Y.Z` — anything else (another operator, a `<=` ceiling, an
  unparsable version) makes the plugin **Malformed** rather than silently unprotected: a floor that fails open
  is worse than none, because it reads as protection that is not there.
- every `permissions` entry has a non-blank, safe `id` **and** a `realm`.
- every `menu` and `dashboardCards` entry has a `realm`.
- every `siteData.personal` entry is a relative path with `/` separators and no empty, `.` or `..` segment (a
  declaration that cannot be honoured would let personal data travel, so it makes the plugin **Malformed**).
- descriptor `id` equals the folder name.

Any violation logs a precise, operator-readable reason and the plugin is skipped — the platform still
boots.

> **Which floor to declare.** The lowest SPI version whose additions you use — the evolution log on
> `RunwaySpi.VERSION` lists them per version. `>=0.4.0`, `>=0.5.0` and `>=0.6.0` are all **1.x floors** (the service
> tier those versions introduced is the 1.0 contract), so a plugin built against them loads on every `1.x` kernel;
> declare `>=1.0.0` only if you use `HeadContext.origin()`, and `>=1.1.0` if you call `PluginSettings.appSecret`. The floor is an **SPI level** — the value of
> `RunwaySpi.VERSION` — not the version of the `runway-spi` artifact you compile against, which is the kernel's
> release version (kernel `1.0.0` carries SPI `1.0.0`; §3).

### Minimal descriptor

```yaml
id: hello
name: Hello Widget
version: 1.0.0
requiresSpi: ">=1.0.0"
mainClass: com.example.hello.HelloConfig
```

**Verify:** place the folder under `external/plugins/hello/`, restart, and confirm the row shows
**Loaded** on the operator console **Plugins** screen (`/__app/plugins`). A bad descriptor shows
**Malformed** with the reason (ADMINISTRATION §11).

---

## 3. Building against `runway-spi`

`runway-spi` is the **dependency-free** extension API — interfaces, immutable model records and the host services,
JDK-only, depending on nothing in the host. Compile against it and its test kit; **never** against the application:

```xml
<properties>
  <runway.version>1.0.0</runway.version>   <!-- a RELEASED kernel version, never a -SNAPSHOT (below) -->
</properties>

<dependency>
  <groupId>mx.quest</groupId>
  <artifactId>runway-spi</artifactId>
  <version>${runway.version}</version>
  <scope>provided</scope>       <!-- the kernel supplies it at runtime; never bundled -->
</dependency>
<dependency>
  <groupId>mx.quest</groupId>
  <artifactId>runway-spi-testkit</artifactId>
  <version>${runway.version}</version>
  <scope>test</scope>           <!-- in-memory fakes of every service, with the kernel's input rules -->
</dependency>
```

Spring Framework, Spring Boot, Spring Security, Thymeleaf and Jackson are **platform libraries**: compile against
them `provided` (the kernel's MAJOR line pins their MAJOR lines — SPI-STABILITY rule 7). `runway-app` is not a
dependency in any scope of your default build: a class that imports from it cannot load. (The first-party plugins
keep a few host-integration tests in a separate `host-it` Maven profile, the only place `runway-app` appears,
test-scoped.)

### Licensing your plugin

Runway is licensed under the GNU AGPL version 3 with an **additional permission for plugins** (the kernel's
`LICENSE-EXCEPTION`). A plugin that interacts with Runway only through the published SPI may carry **any license
you choose**, proprietary included. The published SPI is `runway-spi`, plus any `spi` package a Runway plugin
publishes for other plugins, such as `mx.quest.search.spi`. Compiling your tests against `runway-spi-testkit` does
not change that. What the permission does not cover: a plugin that copies or modifies kernel code, or links
`runway-app` classes, which the loader refuses anyway. The reference plugin `runway-demo` is licensed under
Apache-2.0, so you can start a plugin from a copy of it under any terms.

### Getting the jars — the kernel release's assets

No public Maven repository serves `runway-spi` or `runway-spi-testkit`. **Every kernel release carries them as
release assets** on the releases page (<https://g.quest.mx/quest/runway/releases>), listed in that release's `SHA256SUMS`:

| Asset (for kernel release `<ver>`) | What it is |
|---|---|
| `runway-spi-<ver>.jar` | the contract you compile against (`provided`) |
| `runway-spi-<ver>-sources.jar` | its sources — the Javadoc is the contract, so attach it in your IDE |
| `runway-spi-<ver>.pom` | its pom: no dependencies |
| `runway-spi-testkit-<ver>.jar` | the in-memory fakes for your unit tests (`test`) |
| `runway-spi-testkit-<ver>-sources.jar` | their sources |
| `runway-spi-testkit-<ver>.pom` | its pom: depends on `runway-spi` at the same `<ver>` |

Install them into your local Maven repository once per kernel version you build against:

```sh
V=1.0.0
# Download the six runway-spi* files and SHA256SUMS from the v$V release page. If the release host requires
# sign-in, download with a token or from a logged-in browser.
sha256sum -c --ignore-missing SHA256SUMS     # each of the six must print OK; fails if none was verified
mvn install:install-file -Dfile=runway-spi-$V.jar -DpomFile=runway-spi-$V.pom \
    -Dsources=runway-spi-$V-sources.jar
mvn install:install-file -Dfile=runway-spi-testkit-$V.jar -DpomFile=runway-spi-testkit-$V.pom \
    -Dsources=runway-spi-testkit-$V-sources.jar
```

Then depend on `mx.quest:runway-spi:<ver>` (`provided`) and `mx.quest:runway-spi-testkit:<ver>` (`test`) as above.

- **Verify, do not skip it.** A release host that requires sign-in may answer an asset URL requested without valid
  credentials with **HTTP 200 and its HTML sign-in page**, which `curl -f` accepts; `install:install-file` would then install a "jar" that is a web page.
  The checksum is what catches it (the same trap as the installer — ADMINISTRATION §3).
- **Install with the pom, not `-DgroupId/-DartifactId`.** The poms are standalone consumer poms (no parent, versions
  resolved), generated for the release; installing them is what tells Maven that the testkit needs `runway-spi`.
- **The artifact version is the kernel's release version; the SPI level is `RunwaySpi.VERSION`.** They are different
  numbers on purpose: `1.0.0` carries SPI `1.0.0`, and later kernel releases (`1.0.1`, …) may carry the
  same SPI level or a higher `1.x`. `requiresSpi:` (§2) names the **SPI level** — the lowest one whose additions you
  use — and your plugin then loads on every `1.x` kernel at or above it, whichever kernel release you compiled
  against.
- **Pin a RELEASED kernel version, never a `-SNAPSHOT`.** Your jar embeds your pom (which names `runway.version`),
  and `javac` inlines compile-time constants such as `RunwaySpi.VERSION` into your classes, so the bytes of your
  plugin zip depend on which SPI jar the build saw. A released kernel version is immutable: rebuilding your tag a
  year later gives the same zip, byte for byte. A snapshot moves with every kernel build — the same tag rebuilt
  after it moved is a **different** zip (measured on `runway-demo` 2.0.0, rebuilt against a moved
  kernel snapshot), and on a machine without the kernel's source it does not resolve at all. So a plugin release
  is built against a published kernel release, and moving the pin to a newer kernel is a new plugin version —
  never republish an existing version from a tree whose pin has moved.

### The 1.0 contract at a glance

| You need | Use | Never |
|---|---|---|
| your per-site settings and secrets (app-level ones: `@ConfigurationProperties("runway.plugin.<id>")`; an app-level secret in clear: `PluginSettings.appSecret`, SPI 1.1.0) | `PluginSettings` (+ `SettingsView`) | `SitePluginConfigService`, `SecretCipher`, `@Value("${runway.plugin…}")` on a per-site key |
| your per-site files | `PluginSiteFiles`; declare personal data in `siteData.personal` | `SiteFileStore`, `StorageLayout`, a path you build |
| the site, the request's site, a permission check, a message | `Sites`, `RequestContext`, `AccessCheck` (`@authz`), `Messages` | `SiteResolver`, `AuthzExpressions`, `SiteAwareMessageSource` |
| a starter page | `SitePages.install` → `InstallResult` | `PageWriteService`, writing templates |
| the site's content and its URLs | `ContentCatalogView` (`CatalogEntry.pathFor`, `LocalizedUrl`) | `ContentCatalog`, `ContentUrls`, `LocaleUrls`, building a URL yourself |
| to say your content changed | `ContentChanges.changed` | publishing `ContentChangedEvent` |
| to react to a change | `SiteEventListener` (phase, `previousUrls`/`currentUrls`, `local`) | `@EventListener` on a kernel event |
| a start/stop hook | `PluginLifecycle` | `RunwayPlugin`/`PluginContext` (removed at `1.0.0`) |
| a console screen | `@ConsoleScreen` on a `@Controller` under `/__admin/<id>` or `/__app/<id>` | your own `@ControllerAdvice` calling `OperatorShellModel` |
| an HTTP route on the site | `EndpointContributor` (`Route`, `Route.anonymousPost` for a public form) | a `@Controller` outside the console prefixes, a servlet `Filter` |
| an absolute URL in head markup | `HeadContext.origin()` (1.0.0) | the `Host` header |

**How the kernel enforces it** — each is a **Blocked** plugin with the reason in its row on the **Plugins** screen,
never a boot failure: a `mainClass` without `requiresSpi` (or below `>=0.4.0`); a reference in your own jars to a
`mx.quest.runway` class outside `mx.quest.runway.spi` (read from the class files' constant pool) or a class of yours
under `mx.quest.runway`; a bean name or Spring surface that breaks the rules in *Bean names start with your id*
below. The full table is in SPI-STABILITY.md, *How the kernel enforces it*.

**State-changing actions are `POST`s with the console's CSRF token.** A `GET` — a link, an `<img>`, a prefetch —
must never change anything: a cross-site page can make a signed-in operator's browser send one. Every action of your
console screens (install a page, rebuild an index, send a test mail) is a `<form method="post">` carrying
`_csrf` (the console model provides it; `RouteRequest` endpoints accept it as the `_csrf` field or the
`X-CSRF-TOKEN` header) and is gated by `@authz.may(…)`. The one exception is a public visitor form, which is an
**anonymous route** (below) — never an operator action.

### The SPI surface

Most of the dependency-free interfaces (package `mx.quest.runway.spi`) carry a stable `key()`, one
lifecycle method taking an immutable host-built **view** and returning a plain value, and a
`default Capabilities capabilities()`. The last two rows are shaped differently — they are *platform-armed*
seams with a single implementation, so they have no `key()`:

| Interface | Method | Purpose |
|---|---|---|
| `HeadContributor` | `render(HeadContext) → String` | `<head>` markup, injected at the response edge (§4). `headInputs(String siteId)` names the site files whose change must move a page's `ETag` — see below. |
| `ContentWidget` | `render(WidgetView) → RenderFragment` | body content at a `data-widget` anchor; return `RenderFragment.empty()` to soft-degrade. |
| `PageQualityCheck` | `inspect(PageView) → List<Finding>` | content/SEO checks; never mutates. Advisory by default; an `ERROR` blocks publishing on a site set to *Enforced*/*Reviewed* (see below). |
| `EndpointContributor` | `routes() → List<Route>` + `handle(RouteRequest) → RouteResponse` | server routes (`robots.txt`, `sitemap.xml`, …). |
| `BackupParticipant` | `export(BackupWriter, SiteRef)` / `importInto(BackupReader, SiteRef)` | a site's plugin-generated data in the site backup; import is idempotent. |
| `ContentSource` | `enumerate(SiteView) → List<ContentRef>` + `body(SiteView, id, Locale) → ContentBody` | routable content that is not a kernel page (blog entries, product pages). `enumerate` is metadata-only — it runs on anonymous public requests (the sitemap), so reading bodies there turns `/sitemap.xml` into a full-corpus read; `resolve(...)` answers a routing miss after real pages lose, `indexableText(...)` feeds search. |
| `CaptchaService` | `widgetForSite(String) → CaptchaWidget` + `verify(siteId, token, clientIp) → boolean` | a challenge for a site form and its server-side check. The kernel default is permissive (no challenge, verification accepts), so a form works with no provider installed; the `protection` plugin supplies the real one as `@Primary`. |
| `SiteLoginProtectionControl` | `captchaEnabled(String)` / `disableCaptcha(String)` | app-realm rescue for a site whose own misconfigured login CAPTCHA has locked its manager out. Kernel default is a no-op; `protection` arms it as `@Primary`. |

The model package (`mx.quest.runway.spi.model`) holds the immutable boundary types the host
materializes and hands you: `HeadContext`, `WidgetView`, `PageView`, `RouteRequest`,
`RouteResponse`, `Route`, `RenderFragment`, `Finding`, `Severity`, `SurfacePosture`, `SiteView`,
`PageMeta`, `Alternate`, `Provenance`, `SeoTags`, `IconHints`, `ContentRef`, `ContentBody`,
`CaptchaWidget`, `Realm`, `Capabilities`, `BackupReader`/`BackupWriter`, `SiteRef`, (from `0.4.0`)
`SettingsView` and `PluginInfo`, (from `0.5.0`) `FileEntry`, `StarterPage` and `InstallResult`, and (from `0.6.0`)
`CatalogEntry`, `LocalizedUrl`, `SiteLifecycleEvent`, `SettingsChange` and `CatalogChange`. `HeadContext` gained
`origin()` in `1.0.0`: the trusted `scheme://host[:port]` to make a URL absolute with — the site's base URL, or, on a
host-less site, the request's origin as the trusted-proxy gate left it. (`Permission` left the SPI in `0.4.0`: no plugin constructed one — plugins
declare permissions in `plugin.yml` and check them by key string.)
**An implementation is a pure function of its inputs** — it reads no request, no store, no
other tenant, and returns plain values, so it is trivially unit-testable with no Spring and no I/O.
Implementations must be **stateless and thread-safe**: one instance serves many concurrent requests.

**`headInputs` and the `ETag`.** Head markup is spliced in at the response edge, *after* a page's strong
validator has been computed and possibly answered `304` — so any file a contributor reads that the host cannot
see is invisible to that validator. A site manager edits it, sees the change on a fresh fetch, and every client
and CDN holding the old validator keeps the previous head indefinitely. Return the **site-relative** paths of
**your own** files that you read (`"myplugin/feed.yml"`, a folder you list — a folder folds its listing, a file
its content), and read them from disk when you render (or keep a cache that follows the file), because the host
folds their bytes on every request; they are read on **every** conditional request for every page of the site,
so list only what genuinely shapes the markup. Anything already derived from the `HeadContext` is folded by the
host and must not be listed, which is why the default is empty and every contributor written before the method
existed keeps working.

**Your settings move the `ETag` without you doing anything.** The kernel folds, into every public page's ETag,
every running plugin's **app-level** settings (whatever binds under `runway.plugin.<id>.*` — the settings form's
`external/plugin-settings/<id>.yml`, `application.yml`, environment variables — as the running application bound
them, so a value saved on the form moves the ETag at the restart that applies it) and, if your `plugin.yml` declares
`siteSettings:`, your **per-site** settings for that site (as `PluginSettings` returns them, so a save or a site reload moves the
ETag together with what you render). This covers widgets and `ContentSource` pages as well as head contributors. Do not
declare `plugins/<id>.yml` (or the kernel's `seo.yml`, `manifest.yml`, `icons`): a declared kernel-loaded file
is ignored in favour of its loaded state — folding its bytes would move the ETag on a hand edit that your
`PluginSettings` reads only after the reload, putting the old page under the new ETag.

**What the ETag cannot see: your widget's own data.** A `ContentWidget` that renders from data you keep elsewhere (an
index, a feed, a store of your own) is in the page ETag only if that data lives in your per-site settings or in files a
head contributor of yours declares (`headInputs`). The one case the kernel handles for you is the **query**: a request
with a query string on a page whose page or master template anchors any widget is rendered fresh every time — no
`ETag`, never a `304`, `Cache-Control: private, no-cache` — because `WidgetView.query()` is how a widget answers from
data that moves on its own (search results). So `?q=` results are always current; the same page without a query is
cached and revalidated like any other.

**Scripts and the CSP nonce.** On public pages the kernel sends `script-src 'nonce-<x>' 'strict-dynamic' …`
and, at the response edge, adds `nonce="<x>"` to every executable `<script>` in the finished page — including the
ones your `ContentWidget` or `HeadContributor` emitted. So **emit plain `<script src="…">` tags and do nothing
about nonces** (no SPI carries one, by design). Rules that keep your scripts working under `enforce`: never load a
script with `document.write` (a parser-inserted script without a nonce is blocked; `document.createElement` from
a nonced script is trusted transitively), no inline `on*=` handlers or `javascript:` URLs, and mark data blocks
with a non-JavaScript `type` (`application/ld+json`, `application/json`) — those are left unstamped. A tag you
emit that already has a `nonce` attribute is left exactly as written. Third-party hosts your script *fetches* or
*frames* are not covered by `'strict-dynamic'` (connect-src/frame-src): the operator adds them with
`runway.csp.extra-hosts` (ADMINISTRATION §8.4) — say so in your plugin's README. Widening the policy from code
(`CspHostContributor`) is a kernel seam the essential plugin uses for its CAPTCHA provider; it is not part of the
1.0 contract.

`FilterBand` (an enum) also lives in `runway-spi` — a filter contribution names its **band**, never a
raw order (§4).

### The service tier (SPI 0.4.0 and later)

Axis implementations above are pure functions. Some plugin code needs the host instead — its own per-site
settings, its own identity, a start hook. For that the SPI has a **service tier** (package
`mx.quest.runway.spi.service`): narrow interfaces the **kernel implements** and your plugin **receives by
ordinary Spring injection**. They stand in for the host classes a plugin may not link (`SitePluginConfigService`,
`SecretCipher`, `SiteFileStore`, `SiteResolver`, `AuthzExpressions`, `SiteAwareMessageSource`,
`PageWriteService`, `ContentCatalog`, the host's change events, `OperatorShellModel`, `RunwayPlugin`/`PluginContext`).
SPI `0.4.0` shipped the first four, SPI `0.5.0` the site-facing ones, SPI `0.6.0` the content catalog, change events
and console screens. Declare the floor your code needs: `requiresSpi: ">=0.6.0"`
if you use any `0.6.0` type, `">=0.5.0"` for a `0.5.0` service.

| Service | What it gives you |
|---|---|
| `PluginSettings` | `site(siteId)` → a `SettingsView` of **your** per-site settings: the `siteSettings:` defaults overlaid by what the operator saved (`sites/<id>/plugins/<yourId>.yml`), unknown stored keys preserved, secret keys absent. `secret(siteId, key)` → a `type: secret` value in clear (an `ENC(...)` value decrypted; a hand-written plaintext returned as is; empty when unset, undeclared as secret, or undecryptable). `appSecret(key)` (SPI 1.1.0) → the same for a secret declared in your app-level `settings:`, as the running application holds it. |
| `SettingsView` | lenient typed reads: `string(key, fallback)` (blank → fallback), `bool`, `integer` (and a clamped form), `decimal`, `list` (a YAML list or a comma/newline string). A value that does not parse yields the fallback, never an exception. Nested values are frozen; parse structures from `values()` yourself. |
| `PluginInfo` | your `id`, `version` and install folder. Injectable; also handed to `PluginLifecycle.started`. |
| `PluginLifecycle` | optional `started(PluginInfo)` after the platform is up (in plugin load order) and `stopping()` at shutdown (reverse order, only if started). Collected by type — no `id()` to keep in sync. |
| `PluginSiteFiles` *(0.5.0)* | **your** per-site folder `sites/<id>/plugins/<yourId>/`: `readText`, `writeText` (atomic, creates folders), `delete` (a file), `deleteTree` (a folder; links removed, never followed), `list` (direct children by name, ≤ 10,000), `stat` → `FileEntry(name, directory, size, lastModified)`. Plugin-scoped like `PluginSettings`. |
| `Sites` *(0.5.0)* | `find(siteId)` → the site's `SiteView` (suspended sites included), off-request too. |
| `RequestContext` *(0.5.0)* | `site()` → the site this request is bound to (a public page or the tenant console); **empty** on the application realm and off a request. |
| `AccessCheck` *(0.5.0)* | `may("<yourId>|<permission>")` or `may("EDIT_PAGES")` for the current principal, fail-closed. The same bean as `@authz` in `@PreAuthorize` and templates. |
| `Messages` *(0.5.0)* | `site(siteId, locale, code, fallback, args…)` for visitor-facing text (the site's bundle, then yours); `console(code, fallback, args…)` for console text in the operator's language (the fallback off a request). |
| `SitePages` *(0.5.0)* | `install(siteId, StarterPage)` → `InstallResult` (see *Starter pages*); `pathOf(siteId, slug)` → where that page lives now, in any state. |
| `ContentCatalogView` *(0.6.0)* | `entries(siteId)` → every published page and content item as a `CatalogEntry` with host-computed URLs; `indexableText`, `pageAt` (the published page owning a path), `servablePath` (may content live at a path at all), `refresh` (a manual reindex). See *Reading the content surface*. |
| `ContentChanges` *(0.6.0)* | `changed(siteId, sourceKey, id)` — announce that **your** content changed. Plugin-scoped. See *Announcing your changes*. |
| `SiteEventListener` *(0.6.0)* | a callback you implement: site lifecycle, **your** settings saves, and every change to the content surface — with the item's URLs before and after. See *Reacting to changes*. |
| `@ConsoleScreen` *(0.6.0)* | an annotation on your `@Controller`: its screens render inside the console shell. See *Console screens*. |

```java
@Configuration
public class MyPluginConfig {
    @Bean
    public CarouselWidget mypluginCarouselWidget(PluginSettings settings) {   // bound to THIS plugin
        return new CarouselWidget(settings);
    }
}

final class CarouselWidget implements ContentWidget {
    private final PluginSettings settings;
    CarouselWidget(PluginSettings settings) { this.settings = settings; }
    public String key() { return "carousel"; }
    public RenderFragment render(WidgetView view) {
        SettingsView config = settings.site(view.site().id());
        long interval = config.integer("interval-ms", 5000, 1000, 60000);
        ...
    }
}
```

**How the binding works, and its limits.** A plugin-scoped service (`PluginSettings`, `PluginSiteFiles`,
`ContentChanges`, `PluginInfo`) is bound
to the plugin **whose own jar the injecting class was loaded from** — never to an id you pass, so no plugin can
read another's settings. Inject it into a class from your top-level jar (a `@Bean` method or constructor
parameter, a field, or an `ObjectProvider<PluginSettings>`). Three forms **fail at startup** with a message
naming the alternative: injection into a class from your `lib/` folder (library code is shared by every plugin
that bundles it, so it cannot be bound to one — inject in your own class and pass the service on), a bare
`getBean(PluginSettings.class)` (there is no injection point to bind by), and kernel code. Once injected, the
instance keeps answering for your plugin on any thread.

**Precedence:** stored value › your `siteSettings:` `default` › the fallback you pass in code. Keep your code
fallbacks equal to your descriptor defaults; the code fallback only matters for keys the schema does not
declare. Saves apply immediately on every node of a cluster: the next `site()` call returns the new values.

**Your contributions are contained.** The host calls into your code under one policy: a
`RuntimeException` or `LinkageError` from `HeadContributor.render`/`headInputs`, `ContentWidget.render`,
`PageQualityCheck.inspect`, `EndpointContributor.handle`, a `ContentSource` method, `PluginLifecycle`, a
`SiteEventListener` callback or `BackupParticipant.importInto` is caught, logged (the first time per key with the stack, then at most once a
minute with a count), counted (`runway.plugin.contribution.failures{plugin,axis,key}`) and shown on the plugins
screen. What replaces your output: an empty head fragment or widget (the page is then served
`Cache-Control: no-store` without an `ETag`, so a transient fault is never cached), one
`WARNING` finding `check.failed` for a check (it never blocks a publish), a `500` with `no-store` for an
endpoint. A widget or `headInputs` returning `null` counts as a failure. `BackupParticipant.export` is **not**
contained: its failure fails the backup, and the message names your participant. Any other `Error` propagates.

**Key collisions refuse the plugin side.** Two contributions with one `key()` (or one route) do not stop
the platform when a plugin is involved: against a kernel contribution, yours is refused; between two plugins,
the one later in load order is refused. The refusal is an ERROR naming both sides, and your plugin shows as
having a refused contribution on the plugins screen — its other contributions load. A kernel-only collision
still fails startup.

**Bean names start with your id.** Plugins share the host's bean namespace, so every bean name you define must
start with your id in lowerCamelCase — `myplugin…`, `runwayDemo…`, `searchLucene…` (templates
follow: `${@runwayDemoAdminModel…}`). The host registers your `mainClass` itself as `plugin:<id>`. And
your Spring surface is limited to SPI implementations and plain beans, `@Controller`s whose every mapping is
under `/__admin/<id>` or `/__app/<id>`, and `@ControllerAdvice` restricted to your own packages, types or
annotations — **no** `SecurityFilterChain`, servlet `Filter`/`FilterRegistrationBean`/`FilterContribution`,
`BeanPostProcessor`/`BeanFactoryPostProcessor`, or unrestricted `@ControllerAdvice`. Both rules are checked
before and after refresh, and a violation **blocks** the plugin (SPI `1.0.0`): found before wiring, it is refused
with the rule it broke; found after wiring (a component-scanned bean the first check cannot see), the plugin is
quarantined — the boot ends with the relaunch code and the next boot blocks it with that reason.

**Linking host classes blocks the plugin.** At startup the kernel reads the constant pool of every class on your
plugin's class path — the jars in your folder **and in its `lib/`** (`lib/` is on the same class
path, so a helper moved there is checked too) — without loading them. A reference to an `mx.quest.runway` class
outside `mx.quest.runway.spi`, or a class
of yours that lives under `mx.quest.runway`, blocks the plugin before wiring ("uses Runway's internal classes, which
the plugin contract (SPI 1.0) does not allow (links N kernel classes: …)"), and the plugins screen badges it "Uses
host internals". A reference to an **SPI type this kernel does not have** blocks it too ("needs SPI types this
Runway does not have: …") — the second guard behind `requiresSpi`. A jar the kernel cannot open, or a `.class` entry
it cannot parse, blocks as well ("contains class files Runway could not read, so what they link cannot be verified:
`lib/x.jar!a/B.class: …`") — a check that cannot read a class must not wave it through; the rest of the jar is still
scanned and reported. The only entries exempt are multi-release ones for a Java version newer than the running one
(`META-INF/versions/<n>/` with `n` above it), which the class loader never serves. A normally built jar never trips
this; a damaged download or a hand-edited jar does. Check before you ship: `jdeps -verbose:class`
on your jar must show no `mx.quest.runway.` reference outside `.spi.` (each first-party plugin carries a class-scan
unit test for it).

**Your per-site files (`PluginSiteFiles`).** Keep everything your plugin generates for a site in your subtree —
it travels in the site's backup, disappears with the site, and nobody else's code can reach it. Paths are relative,
use `/`, and never contain `..`, an empty segment or a backslash (`IllegalArgumentException` before any I/O). The
kernel also checks the real path: a symbolic link inside your folder, or standing in for it, can never carry a
read or write out of it — such a call throws `IllegalArgumentException`, and a link at the leaf reads as absent.
One write is capped by `runway.plugins.site-files.max-write-bytes` (16 MiB by default; over it,
`IllegalArgumentException` and nothing is written), and `readText` refuses a bigger file with an `IOException`
rather than loading it. Each write is atomic, so readers never see half a file — but a **read-modify-write** (append
a line) is yours to serialise, for example with a lock per site. A write for a site whose folder does not exist
fails (`IOException`) rather than creating it, so a late write for a deleted site cannot leave data behind for a new
site with the same id. Your settings file (`plugins/<yourId>.yml`) is beside the folder, not in it: write settings
through the settings form, not through files.

**Personal data: declare it.** Any file that holds what a visitor or user typed — names, emails, messages, search
terms — must be declared in `plugin.yml`:

```yaml
siteData:
  personal:              # paths under sites/<id>/plugins/<yourId>/; end a folder with /
    - submissions.tsv
    - uploads/
```

A **transfer copy** of a site (the console's "Download a transfer copy", meant for someone outside the
organisation) leaves every declared path out and lists it in the archive's `manifest.yml` (`omitted:`); a full
backup keeps everything. If your plugin is not loaded when a transfer copy is made, its **whole** folder is left out,
because nobody can then tell what in it is personal. Restoring a transfer copy simply has the files absent, so read
an absent file as empty. Review rule for every plugin: *any file holding visitor or user input is declared personal.*
Published content (a blog entry) is not personal data.

**Starter pages (`SitePages`).** To offer "install my page" (a contact page, a search results page), build a
`StarterPage(slug, path, titles, html, logic, noindex)` and call `install`. The kernel never overwrites and never
publishes around the site's editorial level — first check `AccessCheck` for whatever permission your install action
needs, then tell the operator what happened:

| `InstallResult.Outcome` | When | Say |
|---|---|---|
| `PUBLISHED` | Advisory site; Enforced, or Reviewed by someone holding `APPROVE_PAGES`, with the checks passing | the page is live at `path` |
| `PENDING_REVIEW` | Reviewed site, the user lacks `APPROVE_PAGES` | it is waiting in the review queue |
| `DRAFT_BLOCKED` | the quality checks blocked publishing (`blocking` has the findings) | it exists as a draft; fix it in the page editor |
| `ALREADY_EXISTS` | a page with the slug exists (any state); `path` is where it is now | nothing changed |

`switch` over the outcome with a `default` branch: the enum may grow in a MINOR release. An unsafe slug, or a path
that is unsafe or reserved (`/__…`, `/login`, `/reset`, …) throws `IllegalArgumentException`; an unknown site, a path
another page already serves, or a write failure throws `IllegalStateException` (and leaves no stray draft).

**What a `RouteRequest` carries (0.5.0).** `query` is the URL query string only; `form` holds the fields of an
`application/x-www-form-urlencoded` body — every field of it, whether the page sent the CSRF token as the `_csrf`
form field or in the `X-CSRF-TOKEN` header (the token field itself is in `form` too; ignore it), and never a
query-string value, even under the same name; `body()` is the raw bytes. A form body over
`runway.endpoints.max-form-bytes` (256 KiB — it is read before authentication, so it is kept small) or any other
body over `runway.endpoints.max-body-bytes` (10 MiB) is answered `413` before your handler runs, a malformed one
`400`. **A caller who is not signed in is held to `max-form-bytes` for every body**, whatever the
method and content type: your route decides access itself, inside `handle`, so its body is read before anyone is
authenticated and is bounded like a form. `max-body-bytes` applies to a signed-in caller. Send large payloads (an
upload, a document) as a non-form body from a signed-in page, not as a form field.
`param(name)` reads the query, then the form. `locale` is negotiated like
a page's (`?lang` → the site's language cookie → `Accept-Language` → site default) and is always one of the site's
locales. Header names are lowercase (lookups ignore case). `clientIp` is the client address **after** the kernel's
trusted-proxy gate — use it for rate limits and abuse decisions, never an `X-Forwarded-For` header (which never
reaches you: the gate applies it for a trusted proxy and hides it from everyone else). `origin` is
`scheme://host[:port]` after the same gate — use it to build absolute URLs when the site has no configured base URL.

**Bodies, by method and type (SPI 1.0.0).** A form body (`application/x-www-form-urlencoded`) reaches `form()` and
`body()` whatever the method — `POST`, `PUT`, `PATCH` or `DELETE` — and with the CSRF token in the `_csrf` field or
the header. A `multipart/form-data` body reaches `body()` **raw** and is not parsed for you: `form()` is empty, so
parse the parts yourself, and a CSRF-checked multipart route takes the token in the **`X-CSRF-TOKEN` header** (a
`_csrf` part is never read). Any other body (JSON) is in `body()`. Every body counts against
`runway.endpoints.max-body-bytes` (`413` over it) — or, for a caller who is not signed in and for every anonymous
route, against `runway.endpoints.max-form-bytes`; an anonymous route accepts form bodies only (`415`
otherwise).

**Anonymous form routes (`Route.anonymous`, 0.5.0).** Every `POST` on a site's host normally needs the CSRF token
of the visitor's session. A public form on a cacheable page — a contact form — cannot carry one. Declare such a
route with `Route.anonymousPost("/contact/send", posture)` and the kernel exempts **exactly that method and path**
from CSRF, on a site's host only. In exchange it takes away everything a forged cross-site submission could use:
your handler runs with **no principal** (`AccessCheck.may` is `false` even for a signed-in administrator), it is
handed only `application/x-www-form-urlencoded` bodies (anything else is answered `415`), and an anonymous route
must be `POST` (registration refuses anything else). Use it only for a form any visitor may submit with no account,
keep your own abuse guards (a CAPTCHA via `CaptchaService` with `req.clientIp()`, a honeypot field, a rate limit),
and never use it for an action that changes something a signed-in user owns — that is what the CSRF check is for.

**Reading the content surface (`ContentCatalogView`, 0.6.0).** `entries(siteId)` is what a sitemap, an llms.txt or
a search index lists: every **published** real page (`sourceKey` `"pages"`, the id is the slug) and every content
source's items, already re-filtered by the host (unpublished items, unsafe paths, and URLs that collide with the
kernel's surface or an endpoint route are dropped; a failing source contributes nothing). Each `CatalogEntry` carries
its `ContentRef`, one `LocalizedUrl(locale, path, canonical)` per locale it is available in, and its `xDefaultPath`.
Never build a URL yourself: `path` is the real root-relative address (localized slug under the locale prefix);
`canonical` is the item's canonical override as given, else `path` (an entry whose canonical differs from its path
"points elsewhere", and a sitemap lists the real `path`); `absolute(origin)` and `alternate(origin)` make the absolute
forms; `pathFor(locale)` is the URL to link from content in `locale` — the primary locale's real address when the item
does not exist in that locale (or, when the site does not serve the primary, the item's first available locale —
SPI `1.0.0`), never an unprefixed path that 404s. These are the computations the page `<head>` uses,
so your listing and the page cannot disagree. Every method takes a site id (unsafe → `IllegalArgumentException`;
unknown → empty). The listing is cached per site, evicted by every change that can reshape it, with a 30-second
backstop; call `refresh(siteId)` only for a manual reindex. A content source that validates its own prefix asks
`servablePath(siteId, "/blog")`, and before publishing an item at a path asks `pageAt(siteId, path, locale)`: a
published real page there wins, so the item would be unreachable.

`indexableText(siteId, entry, locale)` is the material a search index folds in beyond the title and description:
HTML or plain text, read from the source on every call (the host does not cache it). For a kernel page it is the
page **as rendered in `locale`**: the inner HTML of the rendered page's content slot (the element
with `id="content"`), with code (`<script>`, `<style>`, `<template>`), `<noscript>` fallbacks, forms (`<form>`
with everything inside it) and stray `<button>`, `<input>`, `<select>` and `<textarea>` elements removed; without a slot, the `<body>`'s inner HTML. So
the strings a page takes from the site's message bundles — decoupled `th:text="#{key}"` in the `.th.xml` — are in
it, in that locale's language, which is what visitors read there. The render is the editor preview's pipeline
(content widgets in their no-query form) without its cache eviction, it binds the page's own site for its
duration, and it works with or without a request. The chrome (header, navigation, footer) is outside the slot and
never returned: every page carries the same words there. Only a published page is rendered; if a render fails, the
page file's own slot text is returned instead (its literal text stays searchable) and the failure is logged once
per page and locale. The rendered text is **cached** per page and locale under a fingerprint of the page's and its master's
file times and the site's bundle fingerprint, so an engine that rebuilds its index often (search-lucene's 30 s
backstop) re-renders only what changed; page, content, site and plugin-settings events drop a site's entries, and
an entry is re-rendered after 10 minutes whatever its fingerprint says (a content widget's own data has none).
A Messages-screen save announces a change to the whole pages corpus, so search indexes rebuild from the
new strings.

**Announcing your changes (`ContentChanges`, 0.6.0).** After every mutation of your content — an item created,
edited, deleted, published, unpublished, or the whole corpus replaced (`id` = `null`) — call
`changes.changed(siteId, yourSourceKey, id)`. On return, routing, the listing, sitemaps and search indexes have been
evicted on every node, and every `SiteEventListener` on this node has run. The call is refused
(`IllegalArgumentException`) for a source key your plugin does not contribute, and (`IllegalStateException`) from
inside a `SiteEventListener` callback — announce from the code that made the change, never from a reaction to one.
It does not refresh HTTP validators: bump the item's `updatedAt` when its body changes. (The host's own
`ContentChangedEvent` is kernel-internal; a plugin that links it is blocked.)

**Reacting to changes (`SiteEventListener`, 0.6.0).** Implement it on any bean from your own jar and override what
you need:

| Callback | Delivered for |
|---|---|
| `onSiteLifecycle(SiteLifecycleEvent)` | every site lifecycle event: `CREATED` (`fromBackup` when imported from a backup — do not seed defaults then), `UPDATED`, `SUSPENDED`, `RESUMED`, `DELETED`, `RESTORED` (`wholesale()` is true for the last two: drop everything you derived from the site's files) |
| `onSettingsChanged(SettingsChange)` | a save of **your own** plugin's per-site settings; `PluginSettings.site()` already returns the new values |
| `onCatalogChanged(CatalogChange)` | anything that may reshape the content surface — cause `PAGE` (a page write), `CONTENT` (a `ContentChanges` call; `pluginId` names the source's plugin), `SITE` (any lifecycle event) or `SETTINGS` (any plugin's settings save; `pluginId` names whose) |

What the host guarantees, so you do not have to:

- **After the kernel.** Every kernel cache the change affects is already evicted: what you read from a callback is
  the new state.
- **Phases.** Declare `phase()` = `SOURCE` if you are a content source dropping your own index; leave the default
  `CONSUMER` otherwise. All `SOURCE` listeners run, the host evicts the listing once more, then all `CONSUMER`
  listeners — so a consumer that re-reads `entries()` never sees a source's stale state. For one event, one listener
  gets lifecycle, then settings, then catalog. Within a phase the order is plugin load order, then bean
  registration order — deterministic, not a contract.
- **No host lock is held.** A change made under a write lock (a page save, a restore) is delivered after the lock is
  released and before the write call returns — "when `save` returns, every listener has run". You may call any
  service from a callback, including one that writes; the events of that write reach you after the current callback
  returns.
- **URLs before and after.** A `PAGE` or `CONTENT` change with an id carries `previousUrls` (where the item was, as
  the host last listed it — so a **deleted** or moved item still names its old address; empty for a new item or a
  site not listed since startup) and `currentUrls` (where it is now, from a fresh listing; empty when gone). A
  consumer that notifies the outside world submits the union. `SOURCE` listeners always receive `currentUrls` empty.
- **`local`.** `false` when this node replays a change made on another node of a cluster. Act on external side
  effects (an IndexNow submission, an e-mail) only when `local` is true, or N nodes do it N times; evict caches
  either way.
- **Isolation.** A callback that throws is logged and counted on the plugins screen (axis `listener`); the save and
  every other listener are unaffected.

Callbacks run on the writer's thread (a console request; the cluster topic thread for a replay): **evict or enqueue,
never do heavy work** — rebuild an index or call the network from your own executor. Changes nobody announces (a
file edited by hand) and changes made before the platform finished starting are not delivered; keep a time-to-live
as the backstop. The host's own Spring events are kernel-internal: this interface is the only way a plugin observes
changes.

**Console screens (`@ConsoleScreen`, 0.6.0).** A screen of your own inside the operator console is a `@Controller`
annotated `@ConsoleScreen` whose handlers set the content template and title key and return the shell layout:

```java
@Controller
@ConsoleScreen
public class MyPluginAdminController {
    @GetMapping("/__admin/myplugin")
    @PreAuthorize("@authz.may('myplugin|manage')")
    public String list(Model model) {
        model.addAttribute(ConsoleScreen.CONTENT_TEMPLATE, "__plugin__/myplugin/admin-list");
        model.addAttribute(ConsoleScreen.PAGE_TITLE_KEY, "myplugin.admin.title");
        return ConsoleScreen.LAYOUT_VIEW;   // "operator/op-layout"
    }
}
```

The kernel fills the shell (topbar, menus, language switcher, footer) for every request your annotated controller
handles — exactly the model a kernel screen gets; you need no `@ControllerAdvice` and no host class (a package-scoped
advice calling `OperatorShellModel` links a host class and is blocked). Keep every mapping
under `/__admin/<yourId>` (tenant console) or `/__app/<yourId>` (application console) so the console's realm security
and CSRF apply, and gate each handler with `@authz.may(…)`. Your content template — and your admin fragments and
dashboard cards — may rely on exactly this: the variables `tenantSiteId` (the bound site on the tenant console,
`null` on the application console) and `_csrf`; the expressions `${@<your own bean>…}` and `${@authz.may('…')}`; and
`#{…}` messages from your bundle. Nothing else in the shell's model is promised.

**Test with the testkit.** `mx.quest:runway-spi-testkit` (test scope, same version as `runway-spi`) has
in-memory fakes that apply the kernel's input rules — `FakePluginSettings` for SPI `0.4.0`; `TempDirSiteFiles`
(your per-site files over a temporary folder, with the kernel's confinement rules) and `FakeSiteServices` (sites,
request context, access, messages, starter pages) for SPI `0.5.0`; `FakeContentCatalog` (a programmable content
surface with the kernel's path rules) and `EventDispatch` (delivers change events to your listeners in the kernel's
phase order and isolation, and doubles as your `ContentChanges`, refusing what the kernel refuses) for SPI `0.6.0`.

**A plugin that breaks the boot is quarantined.** If your plugin makes the context fail to start (a constructor
that throws, a bean that cannot be wired) or a component-scanned bean breaks the Spring-context rules, the kernel
writes `external/plugin-quarantine/<id>.yml`, exits with the relaunch code 42, and the next boot comes up with your
plugin **Blocked** ("quarantined: …"). Installing a different build, or **Retry** on the plugins screen, clears
it.

### Binary-compatibility rules — what "released API" means for you

`RunwaySpi.VERSION` is the SPI contract version your plugin compiles against — **`1.0.0`, the frozen contract**
(`0.4.0` began the service tier, `0.5.0` added the site-facing services, `0.6.0` the content and event tier, and
`1.0.0` froze it, adding `HeadContext.origin()`; the full log is on `RunwaySpi.VERSION`). It is a compile-time
constant, so in your code it is the version you **built against**; `RunwaySpi.runtimeVersion()` tells you the
version of the kernel you are running on. A plugin is refused when the kernel's SPI MAJOR is above its own
(`max(floor MAJOR, 1)` — a `0.4.0`–`0.6.0` floor counts as 1.x), so a plugin built for 1.x never wires into a 2.x
kernel; and on a 1.x kernel every plugin with code must declare a floor of at least `0.4.0` (§2). The SPI evolves
**only additively**, so a plugin built against an earlier version keeps loading on a later one:

- new methods on an interface arrive as `default` methods — your existing implementation keeps working;
- model records keep their **old-arity constructors** when a component is added, so a record you
  construct or receive never breaks;
- capability flags are free-form strings (not a closed enum), so the catalog grows without a binary
  change — a host that doesn't recognize a flag simply doesn't branch on it, and an implementation
  built against an older SPI never needs to know a flag a newer host added;
- an SPI enum may gain constants in a MINOR, so a `switch` over one needs a `default` branch, and a record
  deconstruction pattern may stop compiling when a component is added — use the accessors.

Additivity protects the **backward** direction only. A plugin compiled against a *newer* SPI still throws
`NoSuchMethodError` on an older kernel — at the first request that touches the affected path, not at startup —
so declare the level you need with `requiresSpi:` (§2) and let the kernel refuse it with a reason instead.

The same rules apply to any SPI **you** publish for other plugins (§7) — search's `SearchCorpus`, which
search-lucene reads, is the first-party example; pin with `requires: <spi-owner> == v` if you compiled against a
specific binary, or `>=` for an additive one. The full commitment, what is (and isn't) covered by it, and the
purity/isolation guarantees are set out in **[SPI-STABILITY.md](SPI-STABILITY.md)**.

---

## 4. Contribution seams

The kernel knows only **generic collection points** — it contains zero plugin-specific code. You
contribute either a **bean** (collected by type) or a **descriptor declaration** (parsed from
`plugin.yml`). Each SPI registry indexes contributions by the stable `key()`. A duplicate key among **kernel**
contributions fails startup; a plugin contribution that collides is **refused** (the later plugin's, or yours
against the kernel's) and reported — never a silent shadow (see "Key collisions" in §3).

### Bean-collected seams (implement the SPI, expose a `@Bean`)

- **`HeadContributor`** → `HeadContributorRegistry` collects every one (core + plugin), orders them
  by `@Order`/`Ordered`, and a kernel edge filter splices their concatenated output before `</head>`
  — **independently of the site's templates**. This is how SEO/GEO/analytics markup lands on every
  page without any code inside a tenant's template. A blank return contributes nothing.
- **`ContentWidget`** → rendered where the page markup carries a `data-widget="<type>/<instance>"`
  anchor. Your `key()` is the `<type>`. Return `RenderFragment.empty()` when there's nothing to show.
  **Per-instance configuration:** declare a `widgetSettings:` schema in your descriptor keyed by your
  widget's `key()` (see §5), and the host renders a **Content widgets** editor per placement and hands
  each placement's resolved values to your widget as `WidgetView.config()`. Read config with the
  `<type>/<instance>` context in mind — the *same* widget type placed twice (`carousel/home`,
  `carousel/promos`) gets two independent config maps. An absent key means "use your default", so keep
  your own defaults in code; the schema's `default` is only for pre-filling the editor. A widget that
  declares no `widgetSettings:` receives an empty config map.
- **`PageQualityCheck`** → `inspect(PageView)` returns `Finding`s; the host surfaces them to the author in
  the editor's Page quality panel (and on the review screen). Never mutates the page. **Severity is a decision
  with consequences:** a site manager can set their site to *Enforced* or *Reviewed* (SITE-MANAGER-GUIDE §10),
  and there **any `ERROR` from any check — yours included — refuses publishing**, saving a published page and
  approving a review. `WARNING` and `INFO` never block. So return `ERROR` only for something clearly broken that
  the author can fix (the kernel's own errors are just two: a missing title and an `<img>` without `alt`), and
  use `WARNING` for advice. On enforced sites your check runs on content that is not yet live (an edit being
  saved, a pending revision being approved): the `PageView` is that candidate, rendered, under the page's real
  slug — so judge only the view, never re-read the page from elsewhere. It runs once per locale the page is
  offered in, and may run several times per request; keep it fast and stateless. The kernel's own structural
  checks (`core.title`, `core.description`, `core.headings`, `core.images`, `core.links`, `core.content`,
  `core.duplicates`) are ordinary `PageQualityCheck` beans — read them as the pattern. Your `Finding.code()`
  is looked up as the console message `quality.<code>`; ship that key in your plugin's `i18n/` to localize it,
  otherwise your `message()` is shown as is.
- **`EndpointContributor`** → `EndpointContributorRegistry` indexes your declared `routes()` by
  uppercased `method + path` and dispatches matches to `handle(RouteRequest)`. A route's path is an **exact**
  path — `*`, `{` and `}` are reserved — and its realm must be `TENANT`; it may not **shadow a reserved kernel
  prefix** (`/__app`, `/__admin`, `/__plugin`, `/__assets`, `/__preview`, `/__csp-report`, `/__ui-lang`,
  `/__health`, `/actuator`, `/app/`, `/login`, `/logout`, `/error`) or collide with another route. A plugin route
  that breaks one of these is **refused on its own** (logged, shown on the plugins screen) while your other
  routes load. Each `Route` carries a `SurfacePosture` (fail-safe default: `noindex`, not in sitemap, no-store).
  A `HEAD` request is answered by your `GET` route unless you declare a `HEAD` route yourself: your handler
  sees a `GET` (`request.method()` is `"GET"`), and the kernel sends the status and headers, with the body's
  length, but not the body. So a `GET` handler runs for every `HEAD` too: keep it free of side effects (no writes,
  no counters a `HEAD` should not move), as HTTP expects of `GET` anyway.
- **`BackupParticipant`** → your `export`/`importInto` move a site's plugin-generated data in and out
  of the site's backup, scoped to your `key()` namespace. `importInto` **must be idempotent** and
  tolerate a missing entry.

### Filter contributions — the essential plugin only

**Not part of the 1.0 contract.** A servlet filter or a security chain can reach every request of the platform, and
both are servlet- and Spring-Security-shaped, so a non-essential plugin may not contribute one: a
`FilterContribution`, servlet `Filter`, `FilterRegistrationBean` or `SecurityFilterChain` bean blocks the plugin
(§3). What a feature plugin does with a filter is done instead with an `EndpointContributor` (a route), a
`HeadContributor` (head markup) or a `ContentWidget`. If a real need appears, a separate `runway-spi-servlet` module
can be added in a 1.x. What follows is how the kernel places the **essential** plugin's filters.

A servlet-filter contribution is host-coupled (it references `jakarta.servlet.Filter`), so it is a
`runway-app` type, not a released-SPI one. The essential plugin implements **`FilterContribution`**:

```java
public interface FilterContribution {
    Filter filter();          // stateless, thread-safe
    FilterBand band();        // EDGE_INGRESS | EDGE_EGRESS | TENANT | APPLICATION
    default int offset() { return 0; }   // small relative offset within the band
    default String name() { return "plugin-" + getClass().getSimpleName(); }
}
```

You declare a **`FilterBand` + a small offset** — never a raw order. The kernel maps that through
`PluginFilterPlacement.resolve(...)`, **clamped to the band's window**, so a plugin filter can never
escape its band to preempt the protection floor or run before security. The bands (outermost →
innermost) are `EDGE_INGRESS` (raw request, pre-security), `EDGE_EGRESS` (response rewriting),
`TENANT` (after the site is resolved, before security), `APPLICATION` (feature filters, inner of
security; the default). **`SECURITY` is not contributable** — it belongs to the kernel's realm
security chains, and (as above) a non-essential plugin may not declare a `SecurityFilterChain` bean either.

**Verify:** the boot log prints one line per plugin filter —
`registered plugin filter '<name>' (<BAND>+<offset>) at order <n>` — so you can confirm the resolved
placement.

### Descriptor-declared seams

- **`menu:`** — a realm-tagged, region-targeted, ordered link with an optional `permission` that
  hides the entry from users who fail it (UX only — the controller still enforces).
- **`dashboardCards:`** — a realm-tagged, ordered fragment placed in the console's Work area, with an
  optional gating `permission`.

  Both are collected by the console (`MenuRegistry` / `DashboardCardRegistry`), filtered by realm +
  permission, and rendered — menu entries in the topbar, cards in the dashboard's Work area.

### Static, templates, i18n

Per-plugin static assets serve from `/__plugin/<id>/static/**`; plugin templates resolve under the
reserved `__plugin__/<id>/<name>` prefix; i18n bundles are covered in §8.

---

## 5. Settings (app-level & per-tenant)

Declare a **typed settings schema** in the descriptor; the kernel renders, validates, and persists the
form **generically** — your plugin code never sees the form, only bound configuration.

There are three schemas:

- **`settings:`** — **app-level** (platform concerns: global timers, IP lists, filesystem paths, abuse
  thresholds). Edited by the operator; values live in **`external/plugin-settings/<id>.yml`** and
  apply on the **next restart**.
- **`siteSettings:`** — **per-tenant** (site-owned behaviour: enable switches, exclusions, tuning,
  transport credentials the site owns). Edited per site; values live in
  **`sites/<id>/plugins/<plugin>.yml`** (inside the tenant folder, so they travel in the site backup)
  and apply **immediately** (the config cache is evicted on every node and your `SiteEventListener` gets
  `onSettingsChanged`, so your plugin can react).
- **`widgetSettings:`** — **per content-widget placement** (a map keyed by your `ContentWidget.key()`;
  each value is a settings schema of the same shape). The site manager edits it on the **Content
  widgets** screen, one form per `data-widget` placement, so the *same* widget type placed twice is
  tuned separately. Values live in **`sites/<id>/widget-instances.yml`** (inside the tenant folder, so
  they travel in the site backup) and reach your widget as `WidgetView.config()` immediately on save.
  Keep your own defaults in code — an absent key means "use the default"; the schema `default` only
  pre-fills the editor.

**Which level?** Tenant-owned behaviour is `siteSettings:`; a knob that differs *per placement* of a
widget is `widgetSettings:`; platform concerns are `settings:`. A tenant-editable path or rate limit
would be an isolation/abuse risk, so those stay app-level.

### Schema entry

Each entry is `{ key, type, default, label, range }`, plus the optional presentation keys described under
*Laying out the form* below:

| Field | Meaning |
|---|---|
| `key` | The dotted setting key — the tail of the config-properties prefix. |
| `type` | One of `string`, `secret`, `text`, `int`, `decimal`, `boolean`, `enum`, `list`. Unknown behaves as `string`. |
| `default` | The default value (YAML key `default`); absent = none. |
| `label` | An i18n label key for the form. |
| `range` | Optional constraint hint: `min..max` for `int`, or a `,`/`|`-separated allowed set for `enum`. |
| `secretBoundTo` | On a `secret` only: the keys of the settings this secret is valid **together with** — usually where it is sent. See *Secrets bound to where they go* below. |

### Secrets bound to where they go

The form never shows a stored secret, and a `secret` field left blank on save **keeps** the stored value, so
nobody retypes a password to change an unrelated setting. That is only safe while the secret keeps going to the
same place. Whoever may edit your settings form — on a site, any user granted your `<id>|settings` permission,
not only the site's administrators — could otherwise point your SMTP host at a server they run, leave the
password blank, and let your plugin hand them the stored password on its next login.

Declare what the secret belongs with:

```yaml
siteSettings:
  - { key: smtpHost, type: string }
  - { key: smtpPort, type: int, default: 587 }
  - { key: smtpUsername, type: string }
  - key: smtpPassword
    type: secret
    secretBoundTo: [smtpHost, smtpPort, smtpUsername]
```

On every save of that form (app-level `settings:` and per-site `siteSettings:` alike), when **any** bound
setting's value changes and the secret field is blank, the kernel **removes** the stored secret in the same
write, and the console shows *"The stored value of smtpPassword was removed … Enter it again"*. Typing the
secret again with the new host keeps the new value. Values are compared as they take effect (the stored value,
else the default; booleans as booleans; surrounding blanks ignored), so re-saving an unchanged form never drops
anything. Your plugin must therefore cope with a missing secret after such a save — the same as before it was
first entered.

Bind every setting that decides **where** or **as whom** the secret is used: host, port, username, an API
base URL, a webhook target. Do not bind cosmetic settings, or editors will be asked for the password for no
reason. `secretBoundTo` is read leniently (a list, one key, or a comma-separated string; anything malformed
reads as empty, and a key your schema does not declare never matches), so a typo never blocks your plugin — but
it leaves the secret unbound. The kernel logs a `WARN` when it validates your descriptor, naming
the plugin, the secret and each bound key that is not **another setting of the same schema** (`settings`,
`siteSettings` or one `widgetSettings` entry — a key of a different schema, or the secret itself, counts as
unknown). Read your startup log once after adding a binding, and test it: save the form with a changed host and a
blank secret and assert the secret is gone.

### Laying out the form

A schema with nothing but the keys above renders as one flat list in declaration order. Past a handful
of fields that stops being usable — a tenant faced with thirty unexplained inputs cannot tell which matter.
These optional keys fix that. They are **presentation only**: none changes what is stored or how a value
validates, so adding, moving or removing them never touches a site's configuration.

| Key | On | Effect |
|---|---|---|
| `settingGroups` | descriptor | The form's sections, `{ id, label, help, helpAnchor }`, in display order. `label`/`help` are i18n keys: a heading and a one-line introduction. A section with no fields in the schema being rendered is not shown, so one list serves `settings:` and `siteSettings:` alike. Each section renders as its own card, its title in the card header; ungrouped fields come first in an untitled card. A form with three or more titled sections opens with a "Jump to a section" index of them. |
| `helpUrl` | descriptor | Your help page, which `helpAnchor` links into. A same-origin path or an http(s) URL; anything else (a `javascript:` URL, a `//host` URL) produces no link at all. |
| `group` | entry | The section id this field renders under. Ungrouped fields — and fields naming an undeclared group — form an untitled section at the top. |
| `help` | entry | An i18n key for **one short line shown under the field, always visible**. Deliberately not a hover tooltip: touch screens cannot open one and screen readers often skip it. |
| `helpAnchor` | entry | A fragment of `helpUrl`; renders a "Learn more" link for what does not fit in one line. |
| `advanced` | entry | `true` puts the field in a collapsed "Advanced" disclosure at the end of its section. The disclosure opens by itself when one of its fields holds a non-default value, so a setting that is in effect is never out of sight. |
| `showWhen` | entry | The key of a **boolean** in the same schema; the field is hidden while that switch is off — or while any switch *that* switch depends on is off (a chain is followed). Hidden is not cleared: the input is still submitted, so its value survives switching off and on. A chain that loops back on itself is ignored, so a cycle can never hide fields behind a checkbox nobody can reach. When every advanced field of a section depends on the same switch, the whole "Advanced" disclosure follows it. |
| `inheritsFrom` | entry | For an `enum` with an `inherit` choice: where `inherit` takes its value — a key in the same schema, or `app:<key>` for one of your app-level settings. The form then reads "inherit (currently: allow)". A chain (`inherit` → a sibling on `inherit` → `app:`) is followed. `app:` reads only a key your `settings:` schema declares and never a `secret` one — the value is shown on *site* pages. |
| `requiresPlatform` | entry | The key of one of your app-level **booleans** that must be on for this per-site field to do anything; while it is off the field says so. |

Two behaviours come with them on the plugin settings forms (`settings:` / `siteSettings:`; a `widgetSettings`
form gets only the `help` line for now). An `enum` with a `range` renders as a **select**; a stored value outside
the range is kept as the selected first choice, so narrowing a range never silently changes a site's value on its
next save. And under each field the form states, in the console's
language, only what a person needs: "One per line" for a list, the allowed range of a number, the default.

**The kernel tolerates mistakes in these keys** — an unknown group, a `showWhen` naming a missing or
non-boolean key (or looping), an `inheritsFrom` that resolves to nothing — by rendering the plain field; a
message key you forgot shows as the raw key text (`myplugin.help.foo`). That keeps a typo from breaking a page,
and it also means nothing at runtime will tell you about one.
Test your descriptor: a unit test that checks every group, switch, inheritance source, help anchor and message
key (in each language you ship) catches what the kernel will not report.

```yaml
helpUrl: /__plugin/myplugin/static/help.html
settingGroups:
  - { id: crawl, label: myplugin.grp.crawl, help: myplugin.grp.crawl.help, helpAnchor: crawling }
siteSettings:
  - { key: crawl.enabled, type: boolean, default: false, group: crawl, help: myplugin.help.crawlEnabled,
      requiresPlatform: crawl.allowed }
  - { key: crawl.depth,   type: int, default: 3, range: "1..10", group: crawl, help: myplugin.help.depth,
      showWhen: crawl.enabled }
  - { key: crawl.agent,   type: enum, default: inherit, range: "inherit,polite,fast", group: crawl,
      help: myplugin.help.agent, inheritsFrom: "app:crawl.default-agent", advanced: true }
```

Example (app-level):

```yaml
settings:
  - { key: rate-limit.capacity, type: int, default: 120, range: "1..100000", label: myplugin.rl.capacity }
  - { key: mode,                type: enum, default: strict, range: "strict,lenient", label: myplugin.mode }
```

### Binding the values

App-level values are loaded as a property source under the **`runway.plugin.<id>`** prefix (so the
schema key is the prefix tail), sitting **above** the config files but **below** system properties /
env / CLI — a saved value masks a stray same-prefix key yet never overrides an operator's
command-line override. Bind them with a normal `@ConfigurationProperties`:

```java
@ConfigurationProperties("runway.plugin.myplugin")
public class MyPluginProperties { /* getters/setters mirroring the keys */ }
```

**Ship configuration metadata for your keys.** Put `spring-boot-configuration-processor` on the
`maven-compiler-plugin`'s `annotationProcessorPaths` (with `annotationProcessorPathsUseDepMgmt` set to `true`)
so the build writes `META-INF/spring-configuration-metadata.json` into your jar: an operator's IDE then completes
and type-checks `runway.plugin.<id>.*`, with your field Javadoc as the description. A plain `<dependency>` is not
enough — **JDK 23+ no longer runs annotation processors it merely finds on the classpath**, so the metadata
silently never appears. `runway-plugin-protection/pom.xml` is the reference, and its
`ProtectionConfigurationMetadataTest` shows how to assert the file was generated (read it from your module's
own classes folder — the kernel's metadata is on the test classpath too).

Per-tenant values are read through **`PluginSettings`** (SPI `0.4.0`, §3): `settings.site(siteId)` gives the
declared defaults overlaid by the saved values. (A plugin that injects the host's `SitePluginConfigService` to read
the raw stored map is blocked — it links a kernel class.)

### Secrets

A `secret`-typed value is stored **encrypted** as `ENC(...)` and the file is `chmod 600` whenever it
holds one. Read a per-site secret with `PluginSettings.secret(siteId, key)` — it decrypts for you, and never
appears in `site(siteId)`. Read an **app-level** secret (declared `type: secret` under `settings:`) with
`PluginSettings.appSecret(key)` (SPI 1.1.0, so declare `requiresSpi: ">=1.1.0"`): your `@ConfigurationProperties`
bind it only in its `ENC(...)` form, which you cannot decrypt. It answers the value the running application started
with, so a saved change applies after the restart, like every app-level setting. Encryption requires `runway.security.secret-key` to be set — until it is, a secret can't be
saved and the form says so. A **blank secret on resubmit keeps** the stored value (you never retype
it), and secrets are never echoed back into the form.

Blank-policy differs by level: app-level blank falls back to the declared **default**; per-tenant
blank **removes the key** so the plugin default applies (a default-true boolean turned off is written
explicitly; unknown/hand-edited keys in a per-tenant file are **preserved** across schema upgrades).

A plugin may also ship an **admin fragment** — a `templates/admin.html` in its folder that the console
renders below the per-tenant settings form as a status/actions panel (index stats, action buttons),
fed by the plugin's own beans and served from the plugin's namespace (`__plugin__/<id>/admin`). The
console detects it (`PluginAdminService.hasAdminFragment`) and includes it automatically — a plugin
never builds a separate admin page for per-tenant surfaces.

---

## 6. Permissions

A plugin contributes grantable **permissions** — **never roles**. Each is declared as data:

```yaml
permissions:
  - { id: settings, realm: application, label: myplugin.perm.settings, defaultRoles: [] }
```

| Field | Meaning |
|---|---|
| `id` | The permission key, unique within your plugin; a safe identifier. |
| `realm` | **Required** — `application` or `tenant`. A permission is meaningless outside its realm. |
| `label` | An i18n label key for the admin UI. |
| `defaultRoles` | Leave **empty** — the control point starts ungranted (admins hold it via the wildcard tier; a superadmin grants it explicitly to the blank seed `OPERATOR`/`SYSTEMOPERATOR` role or a custom role). |

**How it wires up.** The kernel namespaces your permission by plugin id (`<pluginId>|<declId>`, so two
plugins declaring `settings` never collide) and merges it into the `PermissionCatalog` as an ordinary
grantable permission. Because the single access check (`mayAccess`) keys off the catalog **by string**,
your permission lights up authorization and the bounded-grant admin surface with **no host change**.

**Enforcement is fail-closed.** `mayAccess` denies when the realm mismatches, a tenant permission has
no resolved tenancy, or the permission is **undeclared**. The precedence: the pinned master passes;
a realm wildcard-admin passes; else the concrete permission must be in the principal's effective set;
else deny. The check re-reads the authoritative stores per call — never a cached session claim.

**`defaultRoles` guardrails.** Leave it **empty**: a control point starts **ungranted**, and the seed
`OPERATOR`/`SYSTEMOPERATOR` role starts **blank** by design — administrators hold every control
point via the wildcard tier, and a superadmin grants a control point explicitly to the seed role or a
custom role. If you do name a role it must be the seed role (`SYSTEMOPERATOR` app / `OPERATOR` tenant);
a **reserved master/wildcard-chain role is a hard error** (a plugin may not default a permission to an
admin tier), and an unknown or cross-realm role is ignored with a warning.

**Reserved id.** The permission id **`settings`** gates a plugin's per-tenant configuration editor.

**Verify:** after installing, the permission appears (by its label) in the realm's permissions matrix
so a superadmin can grant it to roles or users.

---

## 7. Plugin-to-plugin SPIs

A plugin may publish **its own SPI** — a thin interface (plus immutable model records) that other
plugins implement. The pattern mirrors the core SPI:

- The **owner** plugin exposes the interface and collects implementations as beans, selecting/ordering
  them by Spring `@Order`.
- An **implementing** plugin declares `requires:` on the owner so it never loads without it, and — if
  it compiled against a specific binary — pins the exact version with `id == v`.
- Keep a published SPI **binary-compatible**: evolve interfaces only by adding `default` methods, and
  keep **old-arity constructors** on your SPI records, so an implementer compiled against v1 keeps
  loading on v2 (§3).

Because the plugin classloader is **shared** and **parent-first** (§10), the owner's SPI type resolves
to the same `Class` for owner and implementers, so a `List<TheSpi>` injection collects
implementations across plugins by type.

---

## 8. Static assets, templates & i18n

Everything a plugin needs to render is served **from the plugin's own namespace** — plugin
code/assets belong to the plugin and are **never copied into a tenant** (the self-containment law).

### Static assets — `/__plugin/<id>/static/**`

Each loaded plugin's `static/` directory is served at `/__plugin/<id>/static/**`. The id is a loaded
plugin's (already path-safe) id, and Spring's resource resolver confines the `**` inside that plugin's
`static/` dir, so a request can never traverse into another plugin or the external tree. Reference an
asset with a root-relative URL, e.g. `/__plugin/myplugin/static/js/widget.js`. `/__plugin` is a
reserved prefix an `EndpointContributor` can never claim.

**Caching.** A file is sent with `Cache-Control: public, max-age=3600` (the operator's
`runway.http-cache.plugin-assets`) and a strong ETag of its **content**, with no `Last-Modified`: after the hour a
browser asks again and gets a `304` while the file is unchanged. The ETag follows the bytes, not the file date,
because plugin zips are built reproducibly and every release stamps the same date. **Version your URLs:** a
file keeps its name across your releases, so a browser may use the previous release's copy for up to the hour
after an upgrade. Append your version, `/__plugin/myplugin/static/js/widget.js?v=1.4.0` (the query is ignored
when serving), and change it with each release; the browser then fetches the new file at once.

**Verify:** with the plugin loaded, `curl -I https://<host>/__plugin/myplugin/static/js/widget.js`
returns `200` with `Cache-Control: max-age=3600, public` and an `ETag`; repeating it with
`-H 'If-None-Match: <that ETag>'` returns `304`.

### Templates — `__plugin__/<id>/<name>`

A plugin template named `__plugin__/<id>/<name>` resolves to
`<installDir>/templates/<name>.html` (with its paired decoupled-logic `<name>.th.xml`). Templates use
the **decoupled logic** model (no Thymeleaf attributes in the HTML) and run with the cache **on**. The
`__plugin__/<id>/` prefix makes each cached template name **plugin-unique** (Thymeleaf caches by name),
and the id must name a **currently-loaded** plugin while the name is confined under that plugin's
`templates/` dir. This resolver is ordered after the site and operator resolvers.

**A content source's body template renders as the site.** It runs inside the tenant's public render, with the
tenant's isolation: it (and everything it pulls) can reach only the *rendering* site's `__site__/<siteId>/…`
templates — another site's name fails as a missing template — and **Spring beans are not available** there
(`${@yourBean…}` fails the render). Hand the body everything it needs through `ContentSource.body(…)`. The
`${@…}` expressions of §8 belong to console templates (admin fragments, dashboard cards, console screens),
which render in the signed-in console, not on a public page.

> When you reference such a name from another template, pass it through a **model variable**
> (`~{${frag}}`), never as a `__…__` literal in the expression — a literal `__name__` trips
> Thymeleaf's expression-preprocessing pass.

**Console screens are held to the console's accessibility rules.** A screen or panel your plugin renders
inside the operator console sits next to kernel screens that a structural guard keeps accessible,
and an operator using a screen reader or only a keyboard meets yours the same way. Give every input, select
and textarea a `<label for>` (or an `aria-label` from your i18n bundle when the form has no visible label);
give icon-only buttons an `aria-label`, not only a `title`; give table headers `scope="col"`; and never let a
`th:each` repeat an `id` — drop the template id at render time with `th:id="${null}"` or make it unique per row
(and point labels at it with `th:for`). The bundled protection plugin checks its own screens with
`ConsoleA11yAudit` in its tests; copying that class into your plugin's tests is the quickest way to do the same.

### i18n

Your plugin may carry an `i18n/` directory in its folder (`i18n/messages[_<locale>].properties`), and descriptor
`label` keys resolve against it. **The runtime seam is wired in both realms.** Each loaded plugin's bundle is
**appended after** the host's own bundles in every message source — the per-site `SiteAwareMessageSource` (for
your SPI code: a widget, a head contributor, an endpoint) and the operator `OperatorMessageSource` (for your
descriptor `label` keys in the console forms/menus). It is **add-only**: a plugin can add keys but can **never
override** a host key (the host bundle is consulted first). So ship your `label`/UI strings in your own
`i18n/` bundle for the locales you support; an unresolved key falls back to the key text
(`useCodeAsDefaultMessage`), so a missing translation degrades gracefully rather than 500-ing.

---

## 9. Library-provider plugins

The shared classloader means **one version of any library, deployment-wide** — two plugins bundling
the same jar (or worse, different versions) collide silently. The convention:

- A bundled third-party library is **private** to your plugin (in its `lib/`) **until a second
  consumer exists**. Never split preemptively — a provider with one consumer is pure overhead.
- The **moment two plugins need the same library**, move it into a **code-less provider plugin**: a
  folder with a `plugin.yml` that has **no `mainClass`** (legal and first-class — the descriptor's
  `isLibraryOnly()` recognizes it) and the jars in `lib/`. Its **version tracks the principal library**
  (a `lucene-provider` at `10.4.0` *is* Lucene 10.4.0).
- Consumers compile against the same artifacts as `provided` and declare `requires:` with `>=` (API-
  stable) or `==` (compiled-against binaries) on the provider (§10).

A code-less provider registers no `@Configuration` (it contributes no beans) but its jars join the
shared classloader, so consumers resolve the library at runtime.

---

## 10. Lifecycle: install, gate, resolve

### Install = drop the folder + restart

There is no hot reload. Install/upgrade/disable/uninstall change the `external/plugins` tree and take
effect on the **next restart** (ADMINISTRATION §11). This is deliberate: the DI context, security
chains, template resolvers, and MVC mappings are assembled once at boot, and rebuilding them live is
the fragile path.

### The `requires:` / `conflicts:` gate

Before any code loads, the resolver computes a **loadable** set and a **blocked** map of
operator-readable reasons — the *same* resolver feeds both the loader and the console's "Blocked"
preview, so the badge can never disagree with what actually loaded. Three passes:

1. **The SPI floor first.** A plugin declaring `requiresSpi: ">=X.Y.Z"` is blocked when the kernel's
   `RunwaySpi.VERSION` is below it, with a reason naming both levels and **which side to move** ("needs Runway
   SPI … or later; this Runway provides … — upgrade Runway, or install an earlier version of this plugin"). It
   runs first because the alternative is not misbehaviour but a `NoSuchMethodError` on some later request,
   possibly a public page's. It cascades like any other block, and it is a **floor only** — the reverse
   direction is already safe by the additive-evolution rule (§3), so a ceiling could only refuse a kernel that
   would have worked.
2. **Conflicts**, against the full enabled set. `conflicts: [x]` blocks the **declarer** when
   `x` is present. **Mutual** declarations block **both** (the operator disables one); a one-sided
   declaration steps only the declarer aside.
3. **Requirements to a fixpoint.** A `requires:` entry is a **hard gate** in one of three forms:
   - `id` — any installed version satisfies;
   - `id >= v` — the installed version must be `>=` the bound (API-stable dependencies);
   - `id == v` — the installed version must **equal** the bound (compiled-against binaries).

   A plugin whose requirement target is missing/disabled, version-mismatched, or **itself blocked** is
   blocked — so blocking **cascades transitively**. A `requires:` **cycle** (unsatisfiable, since a
   provider must register before its consumer) blocks the whole cycle. Version comparison is
   segment-wise numeric on plain `MAJOR.MINOR.PATCH`.

Anything malformed in these declarations is a **loud** parse error (`>`, `~>`, a double space, a
pre-release version), never a silent fallback.

### Register before refresh

The loadable set is topologically ordered (a provider's `@Configuration` registers before its
consumers'), a **single shared** `URLClassLoader` is built over every plugin's `*.jar` and `lib/*.jar`
with the **application classloader as parent (parent-first)**, and each plugin's `mainClass` is
registered into the host context **before `refresh()`** — so plugin beans, controllers, filters,
security chains, and SPI implementations wire in the **same** refresh pass as the kernel's. Each
registration is **isolated**: one bad plugin is logged and skipped while the platform still boots.
(There is no second wiring pass — that is exactly why install is restart-only.)

Plugins do **not** load in **WIZARD mode** (before first-run setup): with no application master pinned
there are no tenants or app users to protect, so plugin discovery is skipped entirely.

### Optional lifecycle hook

A plugin that only contributes beans/SPI implementations needs **nothing** more — they're collected by
type like the kernel's own. To run logic once the context is fully up (warm a cache, open an index) or
on shutdown, implement the SPI's **`PluginLifecycle`** on any bean from your own jar (SPI `0.4.0`):

```java
public interface PluginLifecycle {
    default void started(PluginInfo self) {}   // after refresh, plugins in load order; isolated
    default void stopping() {}                 // shutdown, reverse order, only if started
}
```

The host matches each hook to your plugin by the jar its class came from, so there is no id to keep in sync.
Both calls are contained (§3, *Your contributions are contained*): a throw is logged and counted, and the next hook runs.

### Periodic work (`@Scheduled`)

Scheduling is **always on**: a `@Scheduled` method on any of your beans runs, with no `@EnableScheduling` of your
own (do not add one, and do not declare your own `TaskScheduler` bean — it would replace the platform's for
everyone).

Every scheduled task on the platform shares **one pool** (`runway-scheduler-*` threads, 4 by default — the operator
can resize it with `spring.task.scheduling.pool.size`). So keep a task short, bound its I/O with timeouts, and never
block waiting for something that may not come: a stuck task holds a thread every other plugin and the nightly
backup also need. A failure escaping the method is **contained** (SPI `1.0.0`): the run is skipped and the schedule
continues, the first failure is logged with its stack and later ones at most once a minute with a count, and each is
counted against your plugin on the plugins screen (axis `scheduled`, key `<Class>.<method>`) — so a task that keeps
failing never floods the log, but it shows. On a cluster each node runs its own copy of the task: act on external side
effects from one node only, or make them idempotent.

### Degrade vs. fail-closed

Ordinary plugin failures **degrade** (logged, skipped). A **security-floor** plugin does the opposite:
if an id on the kernel's essential allowlist is installed but fails to load — **or** loads but doesn't
arm its floor — the kernel enters **fail-closed degraded mode**, serving tenant hosts a `503` while
keeping the **application realm reachable** so an operator can fix it. Today that allowlist is exactly
**`protection`** (§12). A plugin cannot promote itself: `essential: true` is ignored (with a warning)
for any id not on the list.

---

## 11. Packaging & testing

### Thin jar, shared classloader

- Package a **thin jar** — compiled plugin classes only. Depend on `runway-spi` (`provided`) and
  `runway-spi-testkit` (`test`) at a **released** kernel version, installed from that release's assets (§3,
  *Getting the jars*), and Spring/Boot as `provided`; the host supplies them at runtime through the
  parent classloader. Bundling them would double-register or collide. **Never** depend on `runway-app`: a class
  that links it is blocked at startup (§3).
- Keep your packages **outside** `mx.quest.runway` so the kernel's component scan never picks them up —
  they must be wired only by the loader, never double-scanned.
- Third-party jars your plugin genuinely needs go in `lib/` (subject to the one-version rule — §9).

### Test against the **packaged jar**, never devtools

Spring DevTools' hot-restart classloader starts **zero** plugins — plugin classes won't resolve under
it. The loader **warns** when it detects a devtools restart classloader
(`running under the Spring DevTools restart classloader … always test plugins against the packaged
jar`). Always verify plugin wiring against the real packaged build:

1. Build the reactor and the plugin jar.
2. Drop the folder into `external/plugins/<id>/` of a packaged install.
3. Restart, then confirm the boot log: `plugins: N registered, M blocked`, and the row shows
   **Loaded** on `/__app/plugins`.

Because SPI implementations are **pure functions of immutable inputs**, unit-test them directly with
in-memory views — no Spring, no I/O (the SPI's own contract test constructs each view in-memory). For the
service tier, `runway-spi-testkit` gives you the fakes (`FakePluginSettings`, `TempDirSiteFiles`,
`FakeSiteServices`, `FakeContentCatalog`, `EventDispatch`), which apply the kernel's input rules. Save
the packaged-jar run for wiring, ordering, filter placement, and settings/permission integration.

---

## 12. A worked example — the essential protection plugin

`runway-plugin-protection` (module `/runway-plugin-protection`) is a real, tested plugin — and the **essential**
one: part of the kernel distribution, built in the Runway reactor, versioned on the kernel's line (`1.0.0` with
Runway `1.0.0`), and the only plugin allowed to link kernel classes, because it implements the security floor itself.
It is documented here because it ships with Runway; **do not copy its dependencies** (`runway-app` `provided`) or its
`@Primary` overrides of kernel seams — a plugin that does either is blocked. For a feature plugin, read `runway-demo`
under `plugins/demo/` (guide: `plugins/docs/PLUGIN-AUTHORING-GUIDE.md`). **Status:** it is bundled in the `.bin`/dist installer, staged under
`seed/plugins/protection/` (descriptor, thin jar, `templates/`, `i18n/`) and copied into
`external/plugins/protection/` on a fresh install, and **refreshed on every upgrade** — it is lockstep with the
kernel, so an old copy beside a new kernel would be refused and fail closed; the installer keeps the previous jar and
descriptor as `*.bak` and backs up any template/i18n file the operator edited (ADMINISTRATION §12/§15). A feature
plugin is never touched by an upgrade. Read it as a plugin that is actually running, not as a template — the
template for a feature plugin is `runway-demo`.

### Its descriptor (`plugin.yml`)

Abridged — the shipped descriptor carries more `settings:` keys and its two console `menu:` entries:

```yaml
id: protection
name: Essential Protection
version: 1.0.0                 # the kernel's version line
essential: true
requiresSpi: ">=1.0.0"         # the SPI major's floor, met by every 1.x kernel: the mandatory floor never blocks it
mainClass: mx.quest.protection.ProtectionConfig

settings:
  - { key: rate-limit.capacity,          type: int,     default: 120, label: protection.settings.rl.capacity }
  - { key: rate-limit.refill-per-second, type: decimal, default: 2,   label: protection.settings.rl.refill }
  - { key: rate-limit.auto-block.enabled,       type: boolean, default: true, label: protection.settings.ab.enabled }
  - { key: rate-limit.auto-block.block-seconds, type: int,     default: 900,  range: "1..604800", label: protection.settings.ab.duration }

permissions:
  - { id: settings, realm: application, label: protection.perm.settings, defaultRoles: [] }
```

The login-protection knobs (honeypot, lockout, CAPTCHA) are deliberately **not** in this schema: the generic
form applies on the next restart, and a lockout setting you cannot change without one is the wrong shape for
the moment you need it. They render instead as the plugin's own immediate-apply panel on the same settings
page, so login config still has exactly one home.

Note the four features it exercises: `essential: true` (honored **only** because `protection` is on
the kernel allowlist — §10), an app-level `settings:` schema (§5), and a realm-tagged `permissions:`
entry using the reserved `settings` id to gate its config surface (§6).

### Its `mainClass` (`ProtectionConfig`) — arming the floor with `@Primary`

```java
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProtectionProperties.class)
public class ProtectionConfig {

    @Bean @Primary
    public EdgeGuard protectionEdgeGuard(ProtectionProperties properties) { ... }

    @Bean @Primary
    public LoginAttemptGate protectionLoginGate(ProtectionProperties properties) { ... }
}
```

The kernel ships **permissive defaults** for two seams — `EdgeGuard` (the outermost reject point) and
`LoginAttemptGate` (the login-attempt hook) — that pass everything, so the platform runs without abuse
defense until a policy plugin overrides them. Because the loader registers this `@Configuration`
**before refresh** (§10), its `@Primary` beans **win by-type injection** over the kernel defaults and
arm the floor. The kernel verifies the arming post-refresh: if the essential plugin loaded but the
resolved `EdgeGuard`/`LoginAttemptGate` is still the permissive default, it **fails closed** (§10).

### Its SPI implementations

- **`RateLimitEdgeGuard implements EdgeGuard`** — an allowlist (trusted CIDRs) passes; a blocked address is
  rejected **before** the token bucket, so it never consumes a token; otherwise a per-IP token bucket admits
  the request or throttles it `429` with a short `Retry-After`. O(1) per request. A block's status depends on
  **who decided it**: an **auto-escalated** block (the rate limiter escalating) answers `429` with the block's
  real remaining seconds in `Retry-After`, because it genuinely is "too many requests" and there is an honest
  time to name — `403` with no retry time told a crawler it was forbidden, plausibly permanently, for what is
  in fact a fifteen-minute time-out, and a crawler that concludes it is banned stops coming back. An
  **operator ban** (`blocklist-manual`, `blocklist-cidr`) keeps `403`: it really is "forbidden", and there is
  no retry time to offer. An indefinite auto-block falls back to `403` for the same reason — better to say
  nothing than to invent a number.
- **`SlidingWindowLoginGate implements LoginAttemptGate`** — per-IP, counts failed logins in a sliding
  window; once `maxAttempts` fall inside it the IP is locked for `lockoutSeconds`; a success clears the
  IP. Thread-safe, with an injectable clock for deterministic tests.

`ProtectionProperties` binds the app-level schema from `runway.plugin.protection.*` — the exact prefix
the settings loader exposes (§5).

### Its packaging (`pom.xml`)

The module depends on `runway-spi` (compile — it genuinely uses `FilterBand`/model types) and on
`runway-app` + `spring-boot-starter` as **`provided`** (the kernel supplies `EdgeGuard`,
`LoginAttemptGate`, `CidrSet` at runtime), producing a thin `runway-plugin-protection.jar`. Its package
`mx.quest.protection` sits **outside** `mx.quest.runway`, so the kernel's component scan never
double-registers it — only the loader does. It deploys as `external/plugins/protection/` with the jar
and `plugin.yml` side by side.

### Its tests

`RateLimitEdgeGuardTest`, `SlidingWindowLoginGateTest`, `TokenBucketRateLimiterTest`, and
`ProtectionConfigTest` exercise the policy directly (injected clocks, in-memory state) — the pattern to
copy: **unit-test the SPI implementations pure**, then verify wiring/`@Primary` arming against the
packaged jar (§11).

---

## See also

- [ADMINISTRATION.md](ADMINISTRATION.md) §11 (installing & managing plugins), §12 (protection & abuse defense)
- [SITE-MANAGER-GUIDE.md](SITE-MANAGER-GUIDE.md) — per-site plugin settings from the site console
- [SPI-STABILITY.md](SPI-STABILITY.md) — the SPI commitment, versioning & binary compatibility
- [KNOWN-LIMITATIONS.md](KNOWN-LIMITATIONS.md) — what is not yet built
