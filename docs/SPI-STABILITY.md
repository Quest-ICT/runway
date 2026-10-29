# SPI stability commitment

This document states what Runway's extension API (`runway-spi`) guarantees to the code that compiles against
it — plugins and any first-party extension — and the rules by which it may evolve. It is the promise a plugin
author relies on when they build against a released `runway-spi` and expect their jar to keep loading on a
later Runway.

> **Status: in force from SPI `1.0.0` (Runway `1.0.0`).** The surface below — 56 types in three packages — is
> **frozen**: from here it changes only additively, within the `1.x` line, by the rules in this document. It includes
> the service tier (SPI `0.4.0`–`0.6.0`) through which a plugin reaches the host instead of importing host-internal
> classes. From `1.0.0` the kernel **enforces** the contract at load time: a plugin built for the pre-1.0 contract,
> one that links a kernel class, and one that breaks the Spring-context rules are each **blocked** with a reason on
> the plugins screen (see "How the kernel enforces it" below).

## What the SPI is

`runway-spi` is a **dependency-free** module: the extension interfaces (`mx.quest.runway.spi`), the immutable
boundary model they exchange (`mx.quest.runway.spi.model`) and the host services a plugin receives by injection
(`mx.quest.runway.spi.service`). It depends on **nothing** in the host — a plugin compiles against this thin, stable
artifact (and `runway-spi-testkit` for its tests), never against `runway-app`. `RunwaySpi.VERSION` is the contract
version an extension was built against — a compile-time constant, so the compiler copies it into the plugin;
`RunwaySpi.runtimeVersion()` reports the version of the kernel actually running.

**The 1.0 contract is the SPI alone.** Everything a plugin needs from the host — its settings, its per-site files,
the site's content and URLs, change events, the current request's site, permission checks, messages, starter pages,
console screens — is in the tables below. A plugin that needs something else has found a gap in the SPI; it is
added (additively) in a 1.x MINOR, never reached around.

## The surface (SPI `1.0.0`)

"Since" is the SPI version that introduced the type; every type is part of the `1.0.0` contract regardless.

### Extension axes — interfaces a plugin implements, collected by type

| Type | Since | Lifecycle method | Purpose |
|---|---|---|---|
| `HeadContributor` | 0.1 | `render(HeadContext) → String`, `headInputs(path)` | edge `<head>` markup injected on every page |
| `ContentWidget` | 0.1 | `render(WidgetView) → RenderFragment` | body content at a `data-widget` anchor |
| `PageQualityCheck` | 0.1 | `inspect(PageView) → List<Finding>` | content/SEO checks — advisory by default; an `ERROR` gates publishing on a site set to Enforced/Reviewed |
| `EndpointContributor` | 0.1 | `routes()`, `handle(RouteRequest) → RouteResponse` | servlet-free HTTP routes on the tenant realm |
| `ContentSource` | 0.3.0 | `enumerate` / `resolve` / `body` / `indexableText` | routable plugin content (virtual pages) fed to routing, the catalog, sitemap/llms.txt and search |
| `BackupParticipant` | 0.1 | `export` / `importInto` | per-site data kept **outside** the site folder (no first-party plugin has any; the plugin's own subtree already travels in the backup) |
| `CaptchaService` | 0.2.1 | `widget` / `verify` | the configured CAPTCHA (implemented by the essential plugin, consumed by any plugin with a public form) |
| `SiteLoginProtectionControl` | 0.2.1 | per-site login-protection toggles | implemented by the essential plugin |

`FilterBand` (enum, 0.1) remains in the SPI as an **identity** only: it names the request-pipeline band of a filter
contribution, a host-coupled seam that is not part of the 1.0 contract (see "What is NOT part of this contract").

### Service tier — interfaces the kernel implements, received by ordinary Spring injection

| Type | Since | Scope | What it gives a plugin |
|---|---|---|---|
| `service.PluginSettings` (+ `model.SettingsView`) | 0.4.0 | plugin-scoped | its per-site settings, defaults applied, and its declared secrets decrypted (app-level settings bind through `@ConfigurationProperties("runway.plugin.<id>")`; an app-level `secret` setting in clear through `appSecret(key)`, since 1.1.0) |
| `model.PluginInfo` | 0.4.0 | plugin-scoped | its own id, version and install facts |
| `service.PluginLifecycle` | 0.4.0 | callback | `started(PluginInfo)` / `stopping()`, in load order and reverse |
| `service.PluginSiteFiles` (+ `model.FileEntry`) | 0.5.0 | plugin-scoped | its own per-site subtree `sites/<id>/plugins/<pluginId>/`: atomic text writes, capped reads, listing, deletes; confined against crafted paths **and** symbolic links |
| `service.Sites` | 0.5.0 | kernel singleton | a site's `SiteView` by id, off-request |
| `service.RequestContext` | 0.5.0 | kernel singleton | the tenancy the current request is bound to; empty on the application realm and off a request |
| `service.AccessCheck` | 0.5.0 | kernel singleton (bean `authz`) | `may(permissionKey)` for the current principal, fail-closed |
| `service.Messages` | 0.5.0 | kernel singleton | public text by an explicit site id; console text in the operator's UI language |
| `service.SitePages` (+ `model.StarterPage`, `model.InstallResult`) | 0.5.0 | kernel singleton | install a starter page through the site's editorial level |
| `service.ContentCatalogView` (+ `model.CatalogEntry`, `model.LocalizedUrl`) | 0.6.0 | kernel singleton | the site's published content surface with the URLs the host computed (the same addresses the `<head>`, the sitemap and llms.txt use) |
| `service.ContentChanges` | 0.6.0 | plugin-scoped | announce that its own content changed (evicts routing, listings, sitemap and search on every node; notifies listeners) |
| `service.SiteEventListener` (+ `model.SiteLifecycleEvent`, `model.SettingsChange`, `model.CatalogChange`) | 0.6.0 | callback | site lifecycle, its **own** settings saves, every change that may reshape the content surface — phased, after the kernel's caches, outside every host lock, with the item's URLs before and after |
| `service.ConsoleScreen` | 0.6.0 | annotation | a plugin `@Controller` that renders inside the console shell, with no host class |

A plugin-scoped service is bound to the plugin **whose own jar the injecting class came from**, never to an id the
plugin passes, so a plugin can only reach its own data. Rules every service keeps: no method returns `null`; an unsafe
site id (or slug, or path) throws `IllegalArgumentException` before any I/O; every service is thread-safe; off a request
thread the request-bound services answer empty, `false` or the fallback and never throw. A plugin consumes these
interfaces; it does not implement them — an alternative host or a `runway-spi-testkit` fake does, which is why the
additive rules below apply to them unchanged.

### Boundary model — immutable records the host builds (and plugin tests construct)

`Alternate`, `Capabilities`, `CaptchaWidget`, `ContentBody`, `ContentRef`, `Finding`, `HeadContext`, `IconHints`,
`PageMeta`, `PageView`, `Provenance`, `RenderFragment`, `Route`, `RouteRequest`, `RouteResponse`, `SeoTags`, `SiteRef`,
`SiteView`, `SurfacePosture`, `WidgetView` (0.1–0.3); `SettingsView`, `PluginInfo` (0.4.0); `FileEntry`,
`StarterPage`, `InstallResult` (0.5.0); `CatalogEntry`, `LocalizedUrl`, `SiteLifecycleEvent`, `SettingsChange`,
`CatalogChange` (0.6.0). Two interfaces: `BackupReader`, `BackupWriter`. Enums: `Realm`, `Severity`, and the nested
`InstallResult.Outcome`, `CatalogChange.Cause`, `SiteLifecycleEvent.Kind`, `SiteEventListener.Phase`.

### What `1.0.0` itself changed

`1.0.0` freezes the surface; it adds one component and fixes one behaviour:

- **`HeadContext.origin()`** — the trusted origin (`scheme://host[:port]`) a page is served under: the site's base
  URL, else, for a host-less site such as the platform's catch-all default, the request's origin as the trusted-proxy
  gate left it. A head contributor builds absolute URLs (JSON-LD `@id`s) with it; before, a host-less site's
  structured data was relative. The 0.6.0 constructor is kept (`origin` is `""` there).
- **`CatalogEntry.xDefaultPath` / `pathFor`** never name an address the item is not served at: when the site does
  not serve an item's primary locale, x-default is the item's first **available** locale (it used to be the site
  default, an unprefixed path the item may not exist at — which llms.txt then linked).

### What `1.1.0` added

- **`PluginSettings.appSecret(key)`**: the calling plugin's own `secret`-typed **app-level** setting (`settings:` in
  `plugin.yml`), in clear. App-level settings bind through `@ConfigurationProperties`, where a secret arrives only in
  its stored `ENC(...)` form, and a plugin has no way to decrypt that. The answer is the value the running application
  was started with, so a change saved on the console applies after the restart, as every app-level setting does.
  It is a `default` method answering empty, so an implementation written against `1.0.0` still compiles. A plugin
  that calls it declares `requiresSpi: ">=1.1.0"`.

## The compatibility rules (in force from `1.0.0`)

The SPI is **binary API**. A plugin compiled against a released `runway-spi` keeps loading on any later version of
the same MAJOR line. That is upheld by evolving **additively only**:

1. **Interfaces evolve by adding `default` methods.** Never add an abstract method (it breaks every existing
   implementation), never remove, rename, reorder or re-type an existing method.
2. **Records evolve by adding components with an older-arity constructor kept.** A record gains a field only
   alongside a retained constructor (or static factory) of the previous arity, so code built against the old shape
   still constructs it. Never reorder, re-type, or remove an existing component. (`HeadContext` keeps four
   constructors for exactly this reason.) **Record deconstruction patterns are not source-stable across MINOR
   versions** (a pattern names every component, so a new component breaks it at compile time); use the accessors.
3. **Enums may gain constants in a MINOR release.** A `switch` over an SPI enum needs a `default` branch.
4. **Optional behaviour is an opt-in capability, not a fat base.** Where only some implementations participate in a
   concern, it is a separate interface or a `Capabilities` flag the host branches on — never a new abstract method.
   The host is **capability-driven**: it branches on declared data, never on a concrete type or `instanceof` over a
   known catalog.
5. **`key()` values are the forward-compatible contract with stored data.** A stable string `key()` — not the class
   name — is persisted with tenant data, so an implementation class is free to move or rename. A duplicate key never
   silently shadows: between two kernel contributions it fails startup; when a plugin is involved, the plugin's
   contribution is refused — the later plugin's in load order between two plugins — logged with both sides and shown
   on the plugins screen, while the plugin's other contributions load.
6. **`RunwaySpi.VERSION` bumps on each additive evolution** (MINOR for a new type, member or component; PATCH for a
   documented behaviour fix). A pure Javadoc clarification that does not change the contract's meaning is not a bump.
   The version is plain `MAJOR.MINOR.PATCH`; the evolution log on `RunwaySpi.VERSION` is what an author reads to pick
   a floor.
7. **A MAJOR line is a hard boundary.** A breaking change lands only in a MAJOR (`2.0.0`), called out in the
   changelog. The kernel's MAJOR line also pins the MAJOR lines of the **platform libraries** it provides at runtime
   (Spring Framework, Spring Boot, Spring Security, Thymeleaf, Jackson, the Jakarta EE APIs), which a plugin compiles
   against `provided`.
8. **The values of the SPI's string constants are frozen, not just their names.** A `static final String`
   initialised with a literal is a compile-time constant: the compiler copies the *value* into the plugin's bytecode,
   so a plugin built against `1.0.0` keeps using the `1.0.0` value whatever a later kernel declares. Each one names
   something the kernel must keep providing under that exact string for the whole `1.x` line:
   `ConsoleScreen.LAYOUT_VIEW` (`"operator/op-layout"` — the console layout template),
   `ConsoleScreen.CONTENT_TEMPLATE` (`"contentTemplate"`) and `ConsoleScreen.PAGE_TITLE_KEY` (`"pageTitleKey"`)
   (model attributes that layout reads), `AccessCheck.BEAN_NAME` (`"authz"` — the bean name in security
   expressions), `ContentCatalogView.PAGES_SOURCE` (`"pages"` — the kernel's own content-source key), and
   `ContentSource.CAP_INDEXABLE_TEXT` / `CAP_ARBITRARY_PATHS` (`"content.indexable-text"`,
   `"content.arbitrary-paths"` — capability flags the host branches on). Renaming the template, the attribute, the
   bean or the key in a `1.x` would break every plugin that uses it, silently and at run time. (`RunwaySpi.VERSION`
   is inlined too, on purpose — it records what the plugin was built against.) A new constant follows the same rule
   from the release that adds it.

## How the kernel enforces it (from `1.0.0`)

Every rule here **blocks** the plugin — it is not wired, the reason is logged and printed in its row on the
**Plugins** screen, plugins that `requires:` it are blocked in cascade, and the platform starts with everything else.
None of them is a boot failure.

| Rule | Refused | Reason shown |
|---|---|---|
| **Mandatory floor** | a plugin with a `mainClass` that declares no `requiresSpi`, or a floor below `0.4.0` (library-only plugins are exempt) | "was built for the pre-1.0 plugin contract …" |
| **Floor** | a floor above the running SPI | "needs Runway SPI X or later …" |
| **MAJOR ceiling** | `kernel MAJOR > max(floor MAJOR, 1)` — a floor of `0.4.0`–`0.6.0` counts as 1.x (the service tier those versions introduced *is* the 1.0 contract); a 1.x floor is refused by a 2.x kernel | "was built for Runway SPI 1.x …" |
| **Host linkage** | a class in the plugin's own jars that references a kernel class outside `mx.quest.runway.spi` (read from the constant pool, without loading the class), or a plugin class living under `mx.quest.runway` | "uses Runway's internal classes …", naming them |
| **Missing SPI types** | an SPI type the running kernel does not have (a missing or wrong floor) | "needs SPI types this Runway does not have …" |
| **Spring-context rules** (before wiring) | a `@Bean` name not starting with the plugin id in lowerCamelCase; a `SecurityFilterChain`, servlet `Filter`, `FilterRegistrationBean`, `BeanPostProcessor` or `BeanFactoryPostProcessor`; a `@Controller` mapping outside `/__admin/<id>` and `/__app/<id>`; an unrestricted `@ControllerAdvice` | "breaks the plugin rules for Runway's shared Spring context …" |
| **Spring-context rules** (after wiring) | the same, found on a component-scanned bean the first check cannot see | the plugin is **quarantined**: the boot ends with the relaunch code (`42`) and the next boot blocks it with that reason |
| **Wiring failure** | a plugin bean that breaks the context refresh | quarantined the same way |
| **Essential dependency** | a `requires:` on the essential plugin | "requires the essential plugin …" |

A plugin's calls that fail at run time are contained instead (next section). The kernel also refuses individual
**contributions** without blocking the plugin: a colliding key, a route that is not exact or not on the tenant realm.

## The purity & isolation guarantees

Every model type is **immutable and host-built**, with collection/array components defensively copied and
null-normalized in the compact constructor — so a view handed to an implementation cannot be mutated by it or by
whoever built it. An extension-axis implementation is therefore a **pure function of its inputs**: it never sees
drafts, other versions, other tenants or other requests, and returns plain values. That keeps it trivially
unit-testable (no Spring, no I/O — `runway-spi-testkit` fakes the services), keeps tenant isolation outside the
extension, and lets the host evolve storage without touching an implementation.

- **`PageQualityCheck.inspect` must return non-null.** An empty list means "nothing to report".
- **`RouteRequest` / `RouteResponse` carry a `byte[] body`**, so their record-generated `equals`/`hashCode` compare it
  by reference; do not rely on record equality for such instances. `body()` returns a fresh copy.
- **A `ContentSource` reads only its own per-tenant subtree and settings**, returns only published content with safe
  paths, and keeps `enumerate()` metadata-only (it runs on anonymous requests — the sitemap). The kernel re-checks the
  first two fail-closed.

## Failure containment (the host's side of the contract)

Every call the host makes into plugin code on a request, dispatch or scheduler path is contained: a
`RuntimeException` or `LinkageError` from `HeadContributor.render`/`headInputs`, `ContentWidget.render`,
`PageQualityCheck.inspect`, `EndpointContributor.handle`, a `ContentSource` method, `PluginLifecycle`,
`SiteEventListener`, `BackupParticipant.importInto` or a plugin's **`@Scheduled` method** (from `1.0.0`) is caught and
replaced — an empty head fragment or widget (that page is then served `Cache-Control: no-store` without validators),
one `WARNING` finding `check.failed` (which never blocks a publish), an endpoint `500` with `no-store`, a skipped
callback or scheduled run (the next run happens on schedule). A `null` where the axis forbids it counts as a failure.
Any other `Error` propagates. `BackupParticipant.export` is deliberately not contained: its failure fails the backup.
The first failure per plugin, axis and key is logged with its stack, then at most once a minute with a count; each is
counted in `runway.plugin.contribution.failures{plugin,axis,key}` and shown on the plugins screen. There is no circuit
breaker: a throwing contributor costs a stack unwind, not a timeout.

## What is NOT part of this contract

- **Kernel classes** (`mx.quest.runway.*` outside `.spi`): storage, sites, pages, the catalog, the host's Spring
  events, the console model, the security seams. A plugin that links one is blocked.
- **Servlet filters and Spring Security chains** (`FilterContribution`, `SecurityFilterChain`): servlet- and
  Spring-shaped, and able to reach every request; a non-essential plugin may not contribute them. A separate
  `runway-spi-servlet` module may be added in a 1.x if a real consumer appears.
- **The kernel's events** (`PageChangedEvent`, `ContentChangedEvent`, `SiteChangedEvent`,
  `SitePluginConfigChangedEvent`): internal and cluster-relayed. Plugins observe changes through `SiteEventListener`
  and announce them through `ContentChanges`.
- **The URL scheme** (locale prefixes, localized slugs): plugins receive computed URLs, never the rule.

### The essential-plugin exception

A plugin on the kernel's security-floor allowlist — today only **`protection`** — is **part of the kernel
distribution**: built in the Runway reactor, released in the same tag, versioned on the kernel's line (`1.0.0`),
and declaring `requiresSpi: ">=1.0.0"`. It is exempt from the linkage scan and the Spring-context rules, because it
implements the security floor itself (`EdgeGuard`, `LoginAttemptGate`, `LoginFormGuard`, `TrustedDeviceIssuer` and
their collaborators). It still uses the service tier wherever one exists (`@ConsoleScreen`, `RequestContext`). No
plugin may `requires:` it — consumers use `CaptchaService`. If it is installed but does not load — for any reason,
including the floor rules — the platform **fails closed** (tenant pages answer `503`) rather than run without its
security floor. It is not an example to copy.

### The descriptor promise

`plugin.yml` is parsed by the host without loading plugin code, with unknown keys ignored in both directions, so a
descriptor written for a newer kernel loads on an older one and the other way round. Within `1.x` a key keeps its
meaning and new keys are optional — except that, from `1.0.0`, a plugin with a `mainClass` **must** declare
`requiresSpi` (see the mandatory floor above). The keys, their tolerance rules, the configuration namespace, the
on-disk data layout, the backup format, metric names and public paths are covered by the companion commitment
[CONFIGURATION-AND-DATA-STABILITY.md](CONFIGURATION-AND-DATA-STABILITY.md).

## For extension authors

- Depend on `runway-spi` (`provided`) and `runway-spi-testkit` (`test`) — never on `runway-app`. Declare
  `requiresSpi` with the lowest version whose additions you use (the evolution log on `RunwaySpi.VERSION`).
- **Where the jars come from.** No Maven repository serves them: every kernel release on
  the releases page (<https://g.quest.mx/quest/runway/releases>) attaches `runway-spi-<ver>.jar`, `runway-spi-testkit-<ver>.jar`,
  their sources jars and their (standalone) poms, all listed in the release's `SHA256SUMS`. Verify them, install
  each with `mvn install:install-file -Dfile=<jar> -DpomFile=<pom> -Dsources=<sources jar>`, then depend on
  `mx.quest:runway-spi:<ver>`. The exact commands are in [PLUGIN-DEVELOPMENT.md](PLUGIN-DEVELOPMENT.md) §3,
  *Getting the jars*.
- **Artifact version ≠ SPI level.** The Maven version of `runway-spi` is the **kernel release** it came from
  (`1.0.0`); the contract level it carries is `RunwaySpi.VERSION` (`1.0.0`). Several kernel releases carry one
  SPI level, and a later `1.x` artifact may still be SPI `1.0.0`. This commitment, `requiresSpi:` and the loader all
  speak in SPI levels; only your pom names the artifact version.
- **Build against a released kernel version, never a `-SNAPSHOT`.** Your plugin's bytes depend on the SPI jar it
  compiled against (your jar embeds your pom, and `javac` inlines `RunwaySpi.VERSION`), so only an immutable,
  published kernel version lets a plugin release be rebuilt byte for byte. A plugin version is never republished
  from a tree whose kernel pin has moved: a new pin is a new plugin release.
- Read the contract in [PLUGIN-DEVELOPMENT.md](PLUGIN-DEVELOPMENT.md); the plugin-authoring guide
  ([`plugins/docs/PLUGIN-AUTHORING-GUIDE.md`](../plugins/docs/PLUGIN-AUTHORING-GUIDE.md)) is the code-grounded companion.
- Build against a version and expect additive-only change within the MAJOR line. A MAJOR bump (`1.x → 2.0`) is the
  only place a breaking SPI change may land, and the loader will then refuse a plugin built for `1.x` with a reason
  rather than let it fail at run time.
