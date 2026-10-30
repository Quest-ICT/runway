# Writing a Runway plugin — the authoring guide

> **Audience:** a developer starting a **new Runway plugin from scratch**.
> **Scope:** the complete plugin contract of the Runway kernel at **SPI `1.1.0`** (Runway `1.1.0`; within `1.x` it
> changes only additively — `1.1.0` added `PluginSettings.appSecret`, `1.2.0` `PluginSettings.savedApp` — see the kernel's `docs/SPI-STABILITY.md`) — the descriptor, every extension
> axis, the **service tier** through which a plugin reaches the kernel, the `POST`/CSRF rules, the build, the
> install, how to verify, and the traps. Everything here is grounded in the kernel as built and verified against the
> packaged host jar; a gap is called a gap.
>
> **A plugin links only the Runway SPI** (`mx.quest.runway.spi.*`). The kernel **blocks** a plugin that links its
> internal classes, breaks the Spring-context rules (§5) or declares no `requiresSpi` floor of at least `0.4.0` (§4)
> — the plugin is skipped with the reason in the log and on the Plugins screen; the platform still boots. Every
> first-party plugin under `plugins/` is on the service tier; this guide describes only that contract.
>
> The **`demo`** plugin (`../demo/`, walkthrough in `../demo/docs/DEMO-PLUGIN.md`) is the worked reference: it uses
> every axis and every service once, where a real plugin would, with a comment saying when to use it. Copying
> `demo/` and deleting what you don't need is the fastest correct start. The kernel's own developer guide is
> [`docs/PLUGIN-DEVELOPMENT.md`](../../docs/PLUGIN-DEVELOPMENT.md) at the root of the repository.

---

## 1. What a Runway plugin *is*

- **A folder**, deployed under the host's external tree at `external/plugins/<id>/`, containing:
  - `plugin.yml` — the declarative descriptor (required, at the folder root);
  - one **thin** `*.jar` — only *your* classes (never the kernel, never Spring; see §3);
  - optional `lib/` (bundled third-party jars, scanned one level deep), `static/`, `templates/`, `i18n/`.
- **Trusted first-party code, not a sandbox.** Plugin jars load into one shared child classloader whose parent is the
  application classloader; your `mainClass` `@Configuration` is registered into the host Spring context *before*
  refresh, so your beans wire like the kernel's — and receive the kernel's **services** (§7) by ordinary injection.
- **Restart-to-install, degrade-don't-crash.** Install / upgrade / remove = change the folder and **restart** (no hot
  reload). A malformed, disabled or broken plugin is logged and skipped; a plugin that breaks the boot is
  **quarantined** (the kernel writes `external/plugin-quarantine/<id>.yml`, relaunches, and the next boot shows it
  Blocked); the platform always comes up.
- **Your package MUST live outside `mx.quest.runway`** (e.g. `com.acme.myplugin`): the host would scan it, and the
  kernel treats a plugin class under `mx.quest.runway` as host linkage.

---

## 2. Prerequisites

- **JDK 25**.
- **Maven**.
- **`mx.quest:runway-spi` and `mx.quest:runway-spi-testkit` in your `~/.m2`**, at the version of the kernel you build
  for (`ls ~/.m2/repository/mx/quest/runway-spi/`). That is all of Runway a plugin compiles against. Their Maven
  version is the **kernel's release version**, not the SPI level: `1.0.0` carries SPI `1.0.0` (the jar's
  `RunwaySpi.VERSION` says which), and a later kernel such as `1.0.1` may carry the same SPI.
- **Where those jars come from: the kernel release's assets.** No Maven repository serves them. Every kernel
  release on the releases page (<https://g.quest.mx/quest/runway/releases>)
  attaches, for its version `<ver>`, `runway-spi-<ver>.jar`, `runway-spi-testkit-<ver>.jar`, their `-sources.jar`
  and a standalone pom for each (no parent, versions resolved — the testkit's names `runway-spi` at the same
  version), all listed in that release's `SHA256SUMS`. Install them once per kernel version you build against:

  ```sh
  V=1.0.0
  # Download the six runway-spi* files and SHA256SUMS from the v$V release page.
  sha256sum -c --ignore-missing SHA256SUMS     # each of the six must print OK; fails if none was verified
  mvn install:install-file -Dfile=runway-spi-$V.jar -DpomFile=runway-spi-$V.pom \
      -Dsources=runway-spi-$V-sources.jar
  mvn install:install-file -Dfile=runway-spi-testkit-$V.jar -DpomFile=runway-spi-testkit-$V.pom \
      -Dsources=runway-spi-testkit-$V-sources.jar
  ```

  **Do not skip the checksum:** a download that returns an HTML page (a sign-in or error page) with HTTP 200 instead
  of the asset passes `curl -f`, and `install:install-file` would install it as a "jar". **Install with the pom**
  (`-DpomFile`), not `-DgroupId/-DartifactId`, so Maven knows the testkit needs `runway-spi`. A local build of the
  kernel's source puts the same artifacts in `~/.m2` too — but a release of your plugin is built
  against a **released** kernel version, never the kernel's `-SNAPSHOT` (§3, point 4). The kernel's
  `docs/PLUGIN-DEVELOPMENT.md` §3, *Getting the jars*, says the same.

---

## 3. The project (pom.xml)

A plugin is an ordinary Maven `jar` project:

1. **Depend on `runway-spi` (`provided`) and `runway-spi-testkit` (`test`) — never on `runway-app`, in any scope.**
   A host import then does not compile, which is the point. `provided` says what is true: the kernel supplies the
   SPI at runtime and the plugin never ships it (the kernel docs recommend the same). The plugins in this repo
   declare it with the default `compile` scope, which is harmless here: `runway-spi` is resolved parent-first at
   runtime, and their zips are built from an explicit file list that never includes a dependency jar.
2. **Platform libraries are `provided`.** The host supplies Spring Framework 7, Spring Boot 4, Spring MVC, Spring
   Security, Thymeleaf, Jackson 3 (incl. `jackson-dataformat-yaml`) and the Jakarta EE 11 APIs; the kernel's MAJOR
   line pins their MAJOR lines. Compile against the ones you use; never bundle them (a second copy is a second
   `Class` identity, and by-type collection of your beans silently breaks).
3. **No `spring-boot-maven-plugin` repackage** — `mvn package` produces a thin jar of only your classes. Inherit
   `spring-boot-starter-parent` to get the host's managed versions.
4. **Reproducible builds**: set `project.build.outputTimestamp` (bump it per release) so two builds are
   byte-identical. Build a release against a **released** kernel artifact, never a `-SNAPSHOT`: a snapshot changes
   under the same version, and so does your jar — compile-time constants such as `RunwaySpi.VERSION` are copied into
   your classes, and the jar embeds your `pom.xml` (`META-INF/maven/…`). The same holds for any pom edit, the kernel
   version included: a rebuild with it no longer matches the published archive, so it ships as a new version.

```xml
<parent>
  <groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId>
  <version>4.0.6</version><relativePath/>
</parent>
<groupId>com.acme</groupId><artifactId>runway-plugin-myplugin</artifactId><version>1.0.0</version>
<properties>
  <maven.compiler.release>25</maven.compiler.release>
  <project.build.outputTimestamp>2026-10-18T00:00:00Z</project.build.outputTimestamp>
  <runway.version>1.0.0</runway.version>   <!-- the kernel RELEASE you build against (SPI 1.0.0) -->
</properties>
<dependencies>
  <dependency><groupId>mx.quest</groupId><artifactId>runway-spi</artifactId><version>${runway.version}</version></dependency>
  <dependency><groupId>mx.quest</groupId><artifactId>runway-spi-testkit</artifactId><version>${runway.version}</version>
    <scope>test</scope></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter</artifactId>
    <scope>provided</scope></dependency>
  <!-- only what you use, all provided: spring-webmvc (a @ConsoleScreen controller), spring-security-core
       (@PreAuthorize), tools.jackson.dataformat:jackson-dataformat-yaml (your own files), … -->
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope></dependency>
</dependencies>
<build>
  <finalName>runway-plugin-myplugin</finalName>
  <!-- an antrun step that assembles target/<id>/ and target/<artifactId>-<version>.zip — copy demo/pom.xml.
       The artifactId is runway-plugin-<id> (here the id is myplugin): the release asset is
       runway-plugin-<id>-<version>.zip. -->
</build>
```

(A plugin release pins a **released** kernel in `runway.version` — one published on the kernel's releases page —
never a `-SNAPSHOT` (point 4). Moving the pin is a new plugin version; a plugin that needs nothing new from the kernel
keeps its pin until its next release. When you copy a first-party pom, `demo/pom.xml` included, set `runway.version`
to the release you build against.)

---

## 4. The descriptor (plugin.yml)

Parsed with unknown keys ignored (forward/backward compatible). The **install folder name MUST equal `id`**.

```yaml
id: myplugin                 # REQUIRED. Path-safe (/[A-Za-z0-9][A-Za-z0-9._-]*/). == install-dir name.
name: My Plugin              # REQUIRED. Shown in the management UI.
version: 1.0.0               # REQUIRED. STRICT plain MAJOR.MINOR.PATCH — no qualifiers.
mainClass: com.acme.myplugin.MyPluginConfig   # the @Configuration; omit for a code-less library/asset plugin.

# The LOWEST SPI level whose types you use (the newest type decides): ">=0.4.0" PluginSettings/PluginInfo/
# PluginLifecycle; ">=0.5.0" PluginSiteFiles, Sites, RequestContext, AccessCheck, Messages, SitePages, anonymous
# routes, siteData; ">=0.6.0" ContentCatalogView, ContentChanges, SiteEventListener, @ConsoleScreen; ">=1.0.0"
# HeadContext.origin(); ">=1.1.0" PluginSettings.appSecret; ">=1.2.0" PluginSettings.savedApp. A plugin loads on every 1.x kernel at or above the floor
# it declares; a kernel below it BLOCKS the plugin with a reason. A plugin with a mainClass MUST declare a floor >= 0.4.0: without one it is
# blocked as "built for the pre-1.0 plugin contract" (a code-less library plugin is exempt).
requiresSpi: ">=0.6.0"

enabled: true               # optional (default true) — the operator's disable switch.
essential: false            # honored ONLY for kernel security-floor ids on the allowlist ('protection').
requires: [ other >= 1.2.0 ] # optional. Forms: `id`, `id >= v`, `id == v` (single space). Never an essential id.
conflicts: [ legacy ]        # optional. The declaring plugin steps aside if a listed id is installed and enabled.

# Personal data in your per-site folder (sites/<id>/plugins/<pluginId>/): left OUT of a site's transfer copy and
# listed in its manifest; full backups keep it. A trailing "/" names a folder. Rule: any file holding visitor or
# user input is personal.
siteData:
  personal:
    - submissions.tsv

# Grantable control points, namespaced <id>|<declId>. defaultRoles: leave EMPTY — a control point starts ungranted;
# administrators hold it via the wildcard tier and a superadmin grants it explicitly. A reserved/master tier in
# defaultRoles is a hard startup failure. The reserved id `settings` gates the per-site settings form.
permissions:
  - { id: settings, realm: tenant, label: myplugin.perm.settings, defaultRoles: [] }
  - { id: manage,   realm: tenant, label: myplugin.perm.manage,   defaultRoles: [] }

# App-level settings (PLATFORM concerns): bound as runway.plugin.<id>.<key> from external/plugin-settings/<id>.yml,
# applied on RESTART, read through your own @ConfigurationProperties bean (§7.2).
settings:
  - { key: some.flag,  type: boolean, default: true,  label: myplugin.settings.flag }

# Per-site settings (tenant-owned BEHAVIOUR): sites/<siteId>/plugins/<id>.yml, applied IMMEDIATELY, read with
# PluginSettings (§7.2). `settings` gates the form.
siteSettings:
  - { key: enabled, type: boolean, default: true, label: myplugin.settings.enabled }
  - { key: style,   type: enum, default: info, range: "info,warning,danger", label: myplugin.settings.style }
  - { key: apiKey,  type: secret, label: myplugin.settings.apiKey }

# Per-placement widget settings, keyed by the ContentWidget key(): sites/<siteId>/widget-instances.yml; the values
# reach the widget as WidgetView.config(). Keep defaults in code.
widgetSettings:
  myhero:
    - { key: heading, type: string, label: myplugin.widget.heading }

# Console menu entry. `permission` is the FULL key; visibility is UX only. `icon`: a Font Awesome class.
menu:
  - { realm: tenant, region: menu, order: 60, label: myplugin.menu.link,
      path: "/__admin/myplugin", permission: "myplugin|manage", icon: "fa-solid fa-feather-pointed" }

# Dashboard card: `fragment` resolves to __plugin__/<id>/<fragment> = templates/<fragment>.html.
dashboardCards:
  - { realm: tenant, order: 20, fragment: dashboard, permission: "myplugin|manage" }
```

Also understood: `settingGroups` (titled sections of the settings forms) and `helpUrl` — see the kernel's
PLUGIN-DEVELOPMENT §5.

### 4.1 Setting types (and their traps)

| `type`    | Java value        | Notes / trap |
|-----------|-------------------|--------------|
| `string`  | `String`          | trimmed; blank ⇒ null. Also the fallback for an unknown/misspelled `type` (silent!). |
| `secret`  | `String`          | stored **encrypted** `ENC(...)`. Per-site: absent from `PluginSettings.site()`, read in clear only with `PluginSettings.secret(site, key)`. App-level: the property map carries the ciphertext. |
| `text`    | `String`          | **verbatim** — NOT trimmed, blank NOT nulled (whitespace significant). The only such type. |
| `int`     | `Long`            | `range: "min..max"` / `"min.."` / `"..max"` enforced by the form. Read with `SettingsView.integer(key, fallback, min, max)` to clamp a hand-edited value too. |
| `decimal` | `BigDecimal`      | **`range` is IGNORED** for decimal. Non-numeric ⇒ form error. |
| `boolean` | `Boolean`         | truthy iff value ∈ {`true`,`on`,`yes`,`1`} (case-insensitive). |
| `enum`    | `String`          | `range: "a,b,c"` or `"a|b|c"`; value must be a member. |
| `list`    | `List<String>`    | split on comma **or** newline; trimmed; empties dropped. |

---

## 5. The mainClass @Configuration

Register **every** bean **explicitly** with `@Bean` — no `@ComponentScan` of your own package. The kernel's services
(§7) arrive as `@Bean` method parameters:

```java
package com.acme.myplugin;   // OUTSIDE mx.quest.runway

@Configuration(proxyBeanMethods = false)
public class MyPluginConfig {

    @Bean
    @ConfigurationProperties("runway.plugin.myplugin")      // app-level settings (not @EnableConfigurationProperties)
    public MyAppProps myPluginAppProps() { return new MyAppProps(); }

    @Bean
    public MyWidget myPluginWidget(PluginSettings settings, Messages messages) {   // plugin-scoped + kernel singleton
        return new MyWidget(settings, messages);
    }

    @Bean
    public MyEndpoints myPluginEndpoints(AccessCheck access, PluginSiteFiles files) {
        return new MyEndpoints(access, files);
    }
}
```

The kernel checks these rules **before and after refresh**. Before refresh a violation **blocks** the plugin
(`plugin '<id>' is blocked: breaks the plugin rules for Runway's shared Spring context (SPI 1.0): bean name '…' does
not start with '<prefix>' …`); one that only shows after refresh (a bean the pre-check cannot see) **quarantines** it
(§1) with the same reason:

- **Bean names start with your id in lowerCamelCase** (`my-plugin` → `myPlugin…`). Plugins share one bean namespace
  with the kernel and each other. The host registers `mainClass` itself as `plugin:<id>`. Use the method name as
  the bean name. `@EnableConfigurationProperties(X.class)` names its bean `<prefix>-<class name>` and breaks the rule
  — declare the properties bean with `@Bean @ConfigurationProperties(...)` instead. Templates follow the names
  (`${@myPluginAdminModel…}`).
- **Allowed Spring surface:** SPI implementations and plain beans; `@Controller`s whose every mapping lies under
  `/__admin/<id>` or `/__app/<id>`; `@ControllerAdvice` restricted to your own packages, types or annotations.
  **Not allowed:** servlet `Filter`s / `FilterRegistrationBean`s (§6.6), `SecurityFilterChain`s,
  `BeanPostProcessor`s / `BeanFactoryPostProcessor`s, an unrestricted `@ControllerAdvice`.
- **No host linkage.** At startup the kernel reads the constant pool of every class in your jars and **blocks** the
  plugin, before wiring, for any reference to an `mx.quest.runway` class outside `mx.quest.runway.spi` or a class of
  yours under `mx.quest.runway` (`plugin '<id>' linkage scan: uses Runway's internal classes, which the plugin
  contract (SPI 1.0) does not allow (links N kernel classes: …)`), and for a reference to an **SPI type this kernel
  does not have** (`… needs SPI types this Runway does not have: …`) — then `plugin '<id>' is blocked: …`.
- **Collected contributions are plain `@Bean`s — never `@Primary`.** `@Primary` is only for the singleton kernel
  seams the essential `protection` plugin arms. Return the **concrete** class from a `@Bean` method, so Spring sees
  every interface it implements before creating it (a class that is both a `ContentSource` and a
  `SiteEventListener` is collected as both).

---

## 6. The extension axes

All live in `mx.quest.runway.spi`; their immutable model records in `mx.quest.runway.spi.model`. Each has a
**`String key()`** that must be **unique and namespaced** (`"myplugin/thing"`): a blank or colliding plugin key is
**refused** (logged as an ERROR naming both sides, the plugin marked degraded; its other contributions load). Each
has a `default Capabilities capabilities()` — lowercase-dotted flags the host may branch on. Implementations are
**functions of their immutable inputs plus the services they were given**: thread-safe, no request objects, return
plain values.

**Containment.** A `RuntimeException`/`LinkageError` from your code on a request path is caught, logged (the first
time with the stack, then at most once a minute) and counted on the plugins screen: a head or widget failure yields
empty output and the page is served `no-store`; a quality check yields one `WARNING` `check.failed`; an endpoint
answers `500` `no-store`; a listener or lifecycle callback is skipped. `BackupParticipant.export` is **not**
contained: its failure fails the backup.

### 6.1 `HeadContributor` — edge `<head>` injection
```java
String key();
String render(HeadContext ctx);                 // "" (never null) for nothing
default List<String> headInputs(String siteId)  // site-relative files you read: "plugins/<id>.yml"
```
Collected (`@Order`-honored), spliced before `</head>` on every rendered page — no tenant template edit. Order after
the kernel's own (SEO `@Order(100)`, icons `@Order(90)`) with e.g. `@Order(200)`. You own HTML escaping. Head markup
is spliced *after* the page `ETag` is computed: list the per-site files you read in `headInputs` so a change to them
moves the validator. Emit plain `<script src>` tags — the kernel stamps the CSP nonce. For an absolute URL (a
JSON-LD `@id`, an `og:url`) use **`HeadContext.origin()`** (SPI `1.0.0` — declare `requiresSpi: ">=1.0.0"`): the
trusted `scheme://host[:port]` the page is served under — the site's base URL, or, on a host-less site, the request's
origin as the trusted-proxy gate left it. Never read the `Host` header.

### 6.2 `ContentWidget` — a body-content render block
```java
String key();                                   // the <type> half of data-widget="<type>/<instance>"
RenderFragment render(WidgetView view);         // RenderFragment.of(html) / RenderFragment.empty()
```
Rendered wherever a page carries `<div id="x" data-widget="<key>/<instance>">` (the `id` **and** `data-widget` on the
**same** element). `WidgetView` gives `site()`, `page()`, `locale()`, `instanceId()`, `isChrome()`, `config()` (the
placement's `widgetSettings:` values; absent key = your default), `query()` (first value per name — read a
`?sent`-style flag) and `path()` (the public path being rendered — use it for a return path, never the slug). A
widget only *reads*; storing a submission is an endpoint's job.

### 6.3 `EndpointContributor` — HTTP routes (servlet-free)
```java
String key();
List<Route> routes();          // new Route("GET", "/my/path", Realm.TENANT, SurfacePosture.discoverable())
RouteResponse handle(RouteRequest r);
```
Dispatched on a site's public host. A route is an **exact** path (`*`, `{`, `}` reserved), realm `TENANT`, and must
avoid the kernel's surface (`/__app`, `/__admin`, `/__plugin`, `/__assets`, `/__preview`, `/__csp-report`,
`/__ui-lang`, `/__health`, `/actuator`, `/app/`, `/login`, `/logout`, `/error`, `/forgot-password`, `/reset`); a
route that breaks a rule is refused on its own. `RouteRequest` (frozen) carries:

- `site()` (a `SiteView`), `locale()` (negotiated: `?lang` → the site's language cookie → `Accept-Language` → site
  default; always a site locale), `method()`, `path()`;
- `query()` — the URL query string only; `form()` — the fields of an `application/x-www-form-urlencoded` body;
  `param(name)` — query first, then form (`Optional`);
- `headers()` — **lowercase** names (lookups ignore case); forwarded headers never reach you;
- `clientIp()` — the client address **after** the kernel's trusted-proxy gate (use it for rate limits; never read an
  `X-Forwarded-For`); `origin()` — `scheme://host[:port]` after the same gate (absolute URLs when the site has no base
  URL); `body()`.

Answer with `RouteResponse.text(...)`, `RouteResponse.notFound()` or `new RouteResponse(status, headers, bytes)`.
There is no principal in the request; the dispatch runs inside the security chain, so check a permission with
`AccessCheck` (§8).

**`POST` routes and CSRF** (the full rules are §8.1):

- A **`GET`** route must not change anything. Anything that changes state is a `POST`.
- An ordinary (non-anonymous) `POST` route is **CSRF-checked** by the tenant chain: the caller must send the
  session's token. From your console fragment that is a `<form method="post">` with a hidden field bound to
  `${_csrf}` (§8.1). `form()` carries **every** body field, whether the token travels as that form field or in
  the `X-CSRF-TOKEN` header (the `_csrf` field itself is in `form()` too; ignore it), and never a query-string value
  of the same name. Both a route like this and a `@ConsoleScreen` controller (§7.11) can take an operator's form fields.
- A **public form any visitor may submit without an account** (contact, feedback, newsletter) declares an
  **anonymous** route: `Route.anonymousPost("/my/submit", SurfacePosture.safeDefault())` (§8.1).

### 6.4 `PageQualityCheck` — content/SEO checks
```java
String key();
List<Finding> inspect(PageView page);   // Finding.info/warning/error(code, message); empty list = clean
```
Never mutates. **Severity has consequences:** on a site set to *Enforced* or *Reviewed* any `ERROR` refuses
publishing; keep `ERROR` for something clearly broken the author can fix, `WARNING` for advice. The `PageView` may be
a candidate not yet live — judge only the view. `Finding.code()` is looked up as the console message
`quality.<code>` (ship it in your `i18n/` to localize).

### 6.5 `BackupParticipant` — only for data outside the site folder
```java
String key();
void export(BackupWriter out, SiteRef site) throws IOException;
void importInto(BackupReader in, SiteRef site) throws IOException;
```
**You almost certainly do not need it.** Data you keep with `PluginSiteFiles` (§7.3) lives in the site folder,
which full backups already carry. The axis stays for data a plugin keeps elsewhere (none of the first-party plugins
in 1.0). If you use it: `importInto` must be idempotent and tolerate a missing entry; read and write whole byte arrays
(never let a serializer auto-close the archive stream); a failing `export` fails the whole backup.

### 6.6 Servlet filters — not a plugin seam

**A plugin cannot contribute a servlet filter.** Servlet filters are outside the Runway 1.0 plugin contract: a
filter needs servlet types the SPI does not carry, runs on every request of every site ahead of the kernel's own
handling, and no feature plugin needs one. The kernel's `FilterContribution` and its `FilterBand` placement stay
kernel concepts (they place the kernel's filters and the essential `protection` plugin's floor). A plugin `Filter`,
`FilterRegistrationBean` or `FilterContribution` bean blocks the plugin (§5). To shape a
response, use an axis: `<head>` markup (§6.1), a widget (§6.2), an endpoint (§6.3). (A separate
`runway-spi-servlet` module could add a filter seam in a later 1.x if a real consumer appears.)

### 6.7 `ContentSource` — routable content ("virtual pages")
```java
String key();                                                    // stable; namespaces ids
List<ContentRef> enumerate(SiteView site);                       // published-only, METADATA-ONLY, cheap
ContentBody body(SiteView site, String id, Locale locale);       // lazy — only for a 200 render
default Optional<ContentRef> resolve(SiteView, String path, Locale); // override with an O(1) probe
default String indexableText(SiteView, String id, Locale);       // HTML/plain text for search (CAP_INDEXABLE_TEXT)
default Capabilities capabilities();
```
Your plugin serves **whole pages at site URLs** (blog entries, product pages) as peers of kernel pages: the
locale-dispatch ladder, the tenant's master chrome around your body template, `<head>` treatment, `ETag`/`304`,
**sitemap.xml + llms.txt**, by-id links (`${urls['<key>:<id>']}`) and — with `CAP_INDEXABLE_TEXT` — on-site search.
The host re-filters your output fail-closed (unpublished items, unsafe paths and reserved URLs are dropped).

- **`enumerate()` never reads bodies** and returns published content only — it runs on anonymous requests.
- **Announce every change with `ContentChanges.changed(siteId, key, id)`** (§7.9) after each mutation (`id` `null`
  = the whole corpus). It evicts routing, the listing, sitemaps and search on every node.
- **`updatedAt` is load-bearing for HTTP caching**: the render `ETag` folds the ref's metadata, never the body —
  bump `updatedAt` whenever what `body()` returns changes.
- **If you cache, drop it from a `SOURCE`-phase `SiteEventListener`** (§7.10): on your own `CONTENT` changes (a
  replay from another node is how this node learns), on wholesale lifecycle events (`DELETED`, `RESTORED`), on your
  own settings changes if they reshape URLs. The kernel runs sources before consumers and re-evicts in between.
- **`resolve()` must be cheap** — keep an in-memory `path|lang → id` index (printable `|`, never a control char).
- **Validate a custom prefix with `ContentCatalogView.servablePath`, and before publishing at a path ask
  `pageAt`** (§7.8): a real page always wins a contested URL.
- **The body template** (`ContentRef.bodyTemplate`, `__plugin__/<id>/<name>`) may rely on exactly: `${master}` (the
  master to wrap in — pull the chrome with `~{${master} :: head}`, `#site-header`, `#site-footer`, never a literal),
  `${content.*}` (`id`, `sourceKey`, `title`, `description`, `bodyHtml`, `attributes`, `updatedAt`), `${locale}`,
  `${urls[…]}`. The file must be byte-identical for every tenant.
- **Read only your own data**: your per-site folder (`PluginSiteFiles`) and your per-site settings.

---

## 7. The service tier — how a plugin reaches the kernel

Package `mx.quest.runway.spi.service`: narrow interfaces the **kernel implements** and your plugin **receives by
ordinary Spring injection**. They are the only way to reach what the host's own classes hold; a plugin never injects
those classes themselves (`SitePluginConfigService`, `SecretCipher`, `StorageLayout`/`SiteFileStore`, `SiteResolver`,
`AuthzExpressions`, `SiteAwareMessageSource`, `PageWriteService`, `ContentCatalog`, the host's Spring events,
`OperatorShellModel`, `RunwayPlugin`/`PluginContext`) — the linkage scan blocks a jar that links them (§5).
Rules for all: no method returns `null`; an unsafe site id, slug or path throws `IllegalArgumentException` before any
I/O (a well-formed unknown id is not an error); every service is thread-safe; enums may grow in a MINOR release (give
a `switch` a `default`).

### 7.1 How you get one, and the binding

| Kind | Services | Bound to |
|---|---|---|
| **plugin-scoped** | `PluginSettings`, `PluginSiteFiles`, `ContentChanges`, `PluginInfo` | **your plugin, by the jar the injecting class was loaded from** — no method takes a plugin id, so you can only reach your own settings, files and sources |
| **kernel singletons** | `Sites`, `RequestContext`, `AccessCheck`, `Messages`, `SitePages`, `ContentCatalogView` | — |
| **callbacks you implement** | `PluginLifecycle`, `SiteEventListener` | collected by type; matched to your plugin by jar |

Inject a plugin-scoped service into a class from your **own top-level jar** (a `@Bean` parameter, a constructor, a
field, or an `ObjectProvider<…>`). Injection **fails at startup** from a class in your `lib/` folder (a library is
shared by every plugin that bundles it), from kernel code, and through a bare `getBean(PluginSettings.class)`.

### 7.2 `PluginSettings` — your per-site settings (0.4.0)
```java
SettingsView site = settings.site(siteId);           // defaults overlaid by saved values; secrets absent
boolean on = site.bool("enabled", true);
long n = site.integer("interval", 5, 1, 60);          // clamped
Optional<String> key = settings.secret(siteId, "apiKey");   // a type: secret value in clear (ENC decrypted)
```
Precedence: saved value › descriptor `default` › your code fallback (which matters only for undeclared keys — keep
them equal). Accessors never throw: a value that does not parse yields the fallback. A save applies **immediately**
on every node. Nested structures: parse `values()` yourself. **Three settings surfaces:**

- **App-level (`settings:`)** — platform concerns, `runway.plugin.<id>.*`, applied on **restart**, read through your
  own `@ConfigurationProperties` bean (§5). A `secret` there binds as ciphertext: read it in clear with
  `settings.appSecret(key)` (SPI `1.1.0`; declare `requiresSpi: ">=1.1.0"`). It answers only for a key your own
  `settings:` declares `type: secret`, with the value the running application started with. To check a saved change
  before the restart applies it (a connection test, say), read `settings.savedApp(key)` (SPI `1.2.0`): any key your
  `settings:` declares, as saved now, a secret in clear and a blank value as its declared default.
- **Per-site (`siteSettings:`)** — `PluginSettings`; react to a save with `SiteEventListener.onSettingsChanged`.
- **Per-placement (`widgetSettings:`)** — nothing to read: they arrive as `WidgetView.config()`.

### 7.3 `PluginSiteFiles` — your per-site data, and personal data (0.5.0)
```java
Optional<String> text = files.readText(siteId, "notes.yml");
files.writeText(siteId, "notes.yml", yaml);           // atomic; creates parent folders
files.delete(siteId, "x.tsv"); files.deleteTree(siteId, "entries/old"); files.list(siteId, ""); files.stat(...);
```
Your folder `sites/<id>/plugins/<yourId>/`: inside the site, so full backups carry it and a deleted site takes it
along. Paths are relative with `/`; `..`, empty segments, backslashes and symlink escapes are refused. One write is
capped (`runway.plugins.site-files.max-write-bytes`, 16 MiB); `readText` refuses a bigger file. Each write is atomic
but a **read-modify-write is yours to serialize** (a lock per site). A write for a site whose folder does not exist
fails. YAML/JSON go through your own Jackson (a platform library) — read leniently (unknown keys ignored) so a later
version can add fields. Writing a file announces nothing: if it shapes routable content, call `ContentChanges`.
**Declare personal data** in `siteData.personal` (§4): any file holding visitor or user input.

### 7.4 `Sites` and `RequestContext` (0.5.0)

- `Sites.find(siteId)` → `Optional<SiteView>` (suspended sites included), **off-request too**: the way a listener, a
  background task or a console fragment (which only has `tenantSiteId`) gets a site's name, locales or base URL.
- `RequestContext.site()` → the site the **current request** is bound to (a public page or the tenant console);
  **empty on the application console** and off a request. A console screen uses it to learn which site it manages;
  an endpoint does not need it (`RouteRequest.site()`).

### 7.5 `AccessCheck` (0.5.0)

`access.may("myplugin|manage")` (or a kernel permission: `may("EDIT_PAGES")`) for the current principal, in its realm
and tenancy; fail-closed. The same bean is `@authz` in `@PreAuthorize("@authz.may('…')")` and in templates
(`${@authz.may('…')}`). See §8.

### 7.6 `Messages` (0.5.0)

- `messages.site(siteId, locale, code, fallback, args…)` — **visitor-facing** text: the site's own bundle first,
  then every plugin's `i18n/` (add-only), in the visitor's locale. Works off-request.
- `messages.console(code, fallback, args…)` — **console** text in the **operator's** UI language (the fallback off a
  request).

With arguments the text is a `MessageFormat` pattern (write `''` for an apostrophe). A `null` fallback yields the code.

### 7.7 `SitePages` — install a starter page (0.5.0)
```java
InstallResult r = pages.install(siteId, new StarterPage(slug, "/contact", Map.of("en", "Contact"), html, logic, false));
Optional<String> where = pages.pathOf(siteId, slug);    // any state; follows an operator's move
```
Idempotent, **never overwrites**, and follows the site's editorial level: `PUBLISHED`, `PENDING_REVIEW` (Reviewed
site, no `APPROVE_PAGES`), `DRAFT_BLOCKED` (the quality checks blocked it; `blocking()` has the findings),
`ALREADY_EXISTS`. Tell the operator which, in words, with a `default` branch. **Check the caller's permission first**
(`AccessCheck`) — `SitePages` does not. An unsafe or reserved path throws `IllegalArgumentException`; a path another
page serves, an unknown site or a write failure throws `IllegalStateException` (no stray draft is left). The page's
logic pulls the chrome through `${master}`.

### 7.8 `ContentCatalogView` — the site's published content surface (0.6.0)
```java
List<CatalogEntry> entries = catalog.entries(siteId);   // pages ("pages" source; id = slug) + every source's items
String path = entry.pathFor(locale);                    // the URL to link in that locale (never builds one yourself)
Optional<String> owner = catalog.pageAt(siteId, "/blog", locale);   // the published page that owns a path
boolean ok = catalog.servablePath(siteId, "/blog");      // may content live there at all?
catalog.refresh(siteId);                                 // a manual reindex only
```
What a sitemap, an llms.txt or a search index reads. Each `CatalogEntry` carries its `ContentRef`, one
`LocalizedUrl(locale, path, canonical)` per available locale (`absolute(origin)`, `alternate(origin)`) and an
`xDefaultPath` — computed by the same code as the page `<head>`, so they cannot disagree. Cached per site, evicted
by every change that can reshape it (30-second backstop). Metadata only — safe on anonymous paths.

### 7.9 `ContentChanges` — announce that your content changed (0.6.0)

`changes.changed(siteId, yourSourceKey, id)` after every mutation of content your `ContentSource` serves (`id`
`null` = the whole corpus). Call it **after** the write, outside your own locks (listeners run on your thread before
it returns). Refused (`IllegalArgumentException`) for a source key your plugin does not contribute, and
(`IllegalStateException`) from inside a `SiteEventListener` callback. It does not refresh HTTP validators — bump
`updatedAt`.

### 7.10 `SiteEventListener` — react to changes (0.6.0)
```java
public class MyIndex implements SiteEventListener {
    @Override public Phase phase() { return Phase.CONSUMER; }               // SOURCE if you are a content source
    @Override public void onSiteLifecycle(SiteLifecycleEvent e) { if (e.wholesale()) drop(e.siteId()); }
    @Override public void onSettingsChanged(SettingsChange c) { … }        // YOUR plugin's settings only
    @Override public void onCatalogChanged(CatalogChange c) { … }          // PAGE, CONTENT, SITE, SETTINGS
}
```
- **Order:** all `SOURCE` listeners, then the kernel evicts its listing again, then all `CONSUMER`s; per listener and
  event: lifecycle, settings, catalog.
- **URLs before and after:** a `PAGE`/`CONTENT` change with an id carries `previousUrls` (where the item was — a
  deleted item still names its old address) and `currentUrls` (where it is now; always empty for `SOURCE`
  listeners). A notifier submits the union.
- **`local`:** `false` for a change replayed from another node. Do external side effects (an e-mail, an IndexNow
  POST, a file append) only when `local`; evict caches either way.
- **Guarantees:** every kernel cache is already evicted; no host lock is held (calling any service is safe); a throw
  is logged and counted, never propagated. **Duty:** evict, record or enqueue on the writer's thread — slow work goes
  to your own executor. Changes nobody announces and changes before startup completes are not delivered.

### 7.11 `@ConsoleScreen` — your own screen in the console (0.6.0)
```java
@Controller
@ConsoleScreen
@RequestMapping("/__admin/myplugin")
public class MyAdminController {
    @GetMapping
    @PreAuthorize("@authz.may('myplugin|manage')")
    public String list(Model model) {
        model.addAttribute(ConsoleScreen.CONTENT_TEMPLATE, "__plugin__/myplugin/admin-list");
        model.addAttribute(ConsoleScreen.PAGE_TITLE_KEY, "myplugin.admin.title");
        return ConsoleScreen.LAYOUT_VIEW;   // "operator/op-layout"
    }
}
```
The kernel fills the shell (topbar, menus, language switcher, footer) — no `@ControllerAdvice`, no host class. Every
mapping under `/__admin/<id>` (tenant console) or `/__app/<id>` (application console), each handler gated with
`@authz.may(…)`, every state change a CSRF-checked `POST` (§8.1). Use `RequestContext.site()` for the managed site.
The content template's root is `#content`; it may rely on `tenantSiteId`, `_csrf`, `${@<own bean>…}`,
`${@authz.may('…')}`, `#{…}`, and what your controller put in the model — nothing else of the shell's model is
promised (render your own flash attribute rather than relying on the shell's).

### 7.12 `PluginInfo` and `PluginLifecycle` (0.4.0)

`PluginInfo(id, version, installDir)` is your identity — injectable anywhere, and handed to
`PluginLifecycle.started(self)`. `started` runs once the platform is up, in plugin load order; `stopping()` at
shutdown, reverse order, only if `started` returned. Both block the host's thread: keep them short. Use them to start
and stop your own executor, open an index, warm a cache. `RunwaySpi.VERSION` is the SPI you **built against** (a
compile-time constant); `RunwaySpi.runtimeVersion()` the kernel's you **run on**.

### 7.13 Testing with `runway-spi-testkit`

In-memory fakes with the kernel's input rules, so plugin tests need no kernel and no Spring Boot:

| Fake | For |
|---|---|
| `FakePluginSettings` | `.defaults(…)`, `.site(id, values)`, `.secret(id, key, clear)`, `.appSecret(key, clear)` (testkit `1.1.0`), `.savedApp(key, value)` (testkit `1.2.0`) |
| `TempDirSiteFiles(sitesRoot, pluginId)` | `PluginSiteFiles` over a temp folder, with the kernel's three confinement layers, the caps and the missing-site rule |
| `FakeSiteServices` | `Sites`, `RequestContext` (`.current(id)`), `AccessCheck` (`.grant(key)`), `Messages` (`.message(…)`), `SitePages` (`.page(…)`, `.nextInstall(outcome)`, `.installs()`) |
| `FakeContentCatalog` | `ContentCatalogView`: `.entry(…)`, `.page(…)`, `.route(…)`, `.refreshed()` |
| `EventDispatch(pluginId, ownSourceKeys)` | delivers lifecycle/settings/page/catalog events to your listeners in the kernel's phase order with its isolation, and **is** your `ContentChanges` (refusing what the kernel refuses; `.announced()`) |

Wire your `mainClass` in a plain `AnnotationConfigApplicationContext` with these beans registered to prove the wiring
uses services only; drive a `@ConsoleScreen` controller with a standalone `MockMvc`. Add a test that scans your
compiled classes for `mx/quest/runway/` outside `spi/` (see `demo`'s `DemoPluginConfigTest`).

### 7.14 Using the database (the `database` plugin)

A plugin can keep its data in PostgreSQL or MariaDB through the **`database`** plugin (`../database/`; operators set
it up per the kernel's ADMINISTRATION §11.1). Runway's own data stays on files; the database is for plugins. The
worked example is **`runway-demo-database`** (`../runway-demo-database/`): a per-site guestbook that uses everything
below once.

**Wiring.** Declare the dependency and the floors, compile the database SPI as `provided` (the shared plugin class
loader supplies it at runtime; never bundle it), and register one `DatabaseConsumer` bean:

```yaml
requires: [ "database >= 1.0.0" ]     # the lowest database version whose SPI types you use
requiresSpi: ">=1.1.0"
```

```java
@Bean public MyConsumer myPluginConsumer() { return new MyConsumer(); }      // implements DatabaseConsumer
@Bean public MyStore myPluginStore(Databases databases, MyConsumer myPluginConsumer) {
    return new MyStore(databases, myPluginConsumer);                         // calls databases.of(consumer) lazily
}
```

**Compiling against it.** The database SPI (`mx.quest.database.spi`) is in the database plugin's own jar: Maven
coordinates `mx.quest:runway-plugin-database:<version>`, scope `provided` (the guestbook's `pom.xml` shows it). No
public Maven repository serves it, so install it in your local repository once, like `runway-spi` (§2): from source,
`mvn install` in the `database/` plugin folder; or from a release, unzip the plugin and run
`mvn install:install-file -Dfile=database/runway-plugin-database.jar` (the jar carries its own pom, which names the
coordinates).

`DatabaseConsumer` names your plugin id and the scopes you use (`SITE`, `APP`). The database plugin checks the claim
against the jar the bean's class was loaded from, and refuses a second bean for the same id: pass **that bean** to
`Databases.of(…)`. Ask for the handle on first use — never in a constructor, a `@Bean` method or
`PluginLifecycle.started()`: Runway starts without the database and connects afterwards.

**Scopes.** Everything goes through a `Scope`, one per site plus one Runway-wide:

| Call | Use it for |
|---|---|
| `db.currentSite()` | the site of the request you are serving (throws in the application console or a background thread) |
| `db.site(siteRef)` | a site Runway named to you (a backup, a site event) |
| `db.siteInBackground(id)` | background or application-console code that got the id **from Runway** |
| `db.app()` | data that belongs to no site — it is in **no** site backup and does not move with a change of database |

**Never take a site id from request input** (a field, a query parameter, a path segment): a site's administrator
could name another site. Isolation between sites is enforced by this API, because one database account reaches every
schema.

`scope.transaction(c -> …)` runs your work on one connection in one transaction (commit on return, roll back on a
throw); `scope.read(c -> …)` runs it read-only on a consistent snapshot (a write fails on both products). A call on
the **same** scope from inside the work joins it — same connection, no commit of its own — so `lastValue` after an
insert is reliable without `@Transactional`; `transaction()` inside `read()` throws, and so does a call on **another**
scope (finish one before starting the next). The connection is lent: do not close it, change its auto-commit, its
schema or session settings. On MariaDB, drop temporary tables in `finally`; PostgreSQL clears them for you.

**Failures** are unchecked: `DatabaseUnavailableException` (not configured, down, or too many calls in flight for
the site), `ScopeNotReadyException` with a `reason()` (`PREPARING`: retry shortly; `MIGRATION_FAILED`,
`CHECKSUM_MISMATCH`, `NEWER_THAN_INSTALLED`, `PLUGIN_REFUSED`: an operator acts on the **Database** screen), and
`DatabaseException` for a failed statement. `db.available()` tells you up front; degrade quietly (the guestbook says
"This example needs the database").

**Migrations.** Ship your scripts **in your jar** (`src/main/resources/`), for both products, per scope:

```
db/<your-plugin-id>/postgresql/site/index           V1__init.sql
db/<your-plugin-id>/postgresql/site/V1__init.sql
db/<your-plugin-id>/mariadb/site/index
db/<your-plugin-id>/mariadb/site/V1__init.sql
db/<your-plugin-id>/<product>/app/…                 only if you declared the APP scope
```

- The `index` lists the scripts in order, `V1__…` first, versions rising by one. The database plugin reads them only
  from your jar and runs them the first time a schema is used (and warms them up at startup).
- Statements are separated by a line holding only `-- runway:next`. A statement that moves the connection (`USE`,
  `SET search_path`, `SET SESSION`) is refused.
- **Every object you create starts with your prefix**: your id lower-cased, other characters as `_`, then `__`
  (`runway-demo-database` → `runway_demo_database__`; `db.prefix()` says it). A prefix is at most 24 characters:
  past that, a longer id keeps its first 15 characters, then `_`, 6 hex digits of a hash and `__`
  (`acme-visitor-guestbook-pro` → `acme_visitor_gu_` + hash + `__`), so for an id longer than 22 characters read
  the real prefix from `db.prefix()` before you write your scripts. That covers tables, indexes, sequences,
  views, functions, procedures, types and constraints that create an index on PostgreSQL; tables, views, sequences,
  triggers, routines, events and foreign keys on MariaDB. A script that creates or drops anything else fails, and
  what it created is removed. MariaDB tables must be `ENGINE=InnoDB`.
- **Never change a released script**: its checksum is recorded, and a changed one stops your plugin in every schema
  until an operator accepts it. Add `V<n+1>` instead.
- PostgreSQL runs all your pending scripts in one transaction, so `CREATE INDEX CONCURRENTLY`, `VACUUM` and using an
  enum value added in the same script are not supported. MariaDB commits each DDL statement: keep one statement per
  chunk, so a failed script resumes cleanly from the failing statement.
- Both trees must produce the **same tables, columns and nullability** at every version: a site's backup moves
  between the products.

**Conventions** (the guestbook follows them): lower-case unquoted identifiers; `BIGINT` ids from a sequence
(`db.dialect().nextValue("<prefix>x_seq")` in the insert, `dialect.lastValue(c, …)` after it), never
`AUTO_INCREMENT` or identity columns; entity columns `id, version, created_at, updated_at, updater`, with `updater`
set by your code (every write uses one account); instants as `TIMESTAMPTZ` on PostgreSQL and `TIMESTAMP(6)` on
MariaDB (both sessions are pinned to UTC; in Java read and bind them as `Timestamp` with a UTC `Calendar`, or on
MariaDB as a UTC `LocalDateTime`: a bare `getTimestamp`/`setTimestamp` goes through the JVM's zone); paging with `dialect.limitOffset(…)`. Backups carry these types:
`BIGINT, INTEGER, SMALLINT, DECIMAL(p,s), DOUBLE, BOOLEAN, VARCHAR(n), TEXT, DATE, TIME, TIMESTAMP, TIMESTAMPTZ,
BYTES (bytea / BLOB), JSON`; a table with another type cannot be backed up.

**What travels.** A site's **full backup** carries its schema; a **transfer copy** carries none (the copy starts
empty). So keep in the database only data a transfer may leave behind — visitors' entries, caches, derived data — and
keep content that must travel with the site in its folder (`PluginSiteFiles`, §7.3).

**Public writes.** A form any visitor can submit (an anonymous `POST`, §8.1) must bound what it writes: a honeypot,
length caps, the site's CAPTCHA through `CaptchaService` when the site has one (render `widgetForSite(siteId).html()`
in the form, `verify` it in the route), a per-address limit on `RouteRequest.clientIp()`, and a **row cap per site**
applied in the same transaction as the insert (the guestbook deletes the oldest entries beyond `maxEntries`).
Refuse control characters other than line breaks and tabs before you write: PostgreSQL rejects U+0000 in text, so a
NUL byte would otherwise reach the visitor as a database error. Compute a page offset in `long`, or bound the page
number: `page * size` in `int` overflows into a negative offset on a crafted `?page=`.

**Caching.** A `ContentWidget`'s output must depend only on what the page is rendered from; the kernel caches pages
and answers `304` while their ETag holds. Do not read the database inside a widget: render a static shell and load
the rows from your own endpoint with `Cache-Control: no-store` (the guestbook's `/guestbook/entries.json`).

**Testing.** Unit-test your guards with a stub `Databases`; test your SQL against real servers on both products (the
guestbook's `GuestbookStoreRealServerTest` wires the database plugin's classes to throwaway PostgreSQL and MariaDB
containers), and run the whole plugin in a real Runway (`runway-demo-database/scripts/verify-against-host.sh`).

---

## 8. Control points (permissions) and the `POST` rules

Declared in `plugin.yml` `permissions:`, merged into the catalog as the **full key `<id>|<declId>`**. Enforce in code
with `AccessCheck` — there is no `hasControl(...)`:

```java
if (!access.may("myplugin|manage")) { return RouteResponse.text(403, "Forbidden\n"); }   // endpoint
@PreAuthorize("@authz.may('myplugin|manage')")                                          // console handler
<attr sel="#link" th:if="${@authz.may('myplugin|manage')}"/>                            // template (UX only)
```

- **Menu/card `permission:` fields must be the FULL key**; a bare decl id resolves to nothing and **hides the entry
  from everyone**. Menu/card gating is **UX only** — the handler still checks.
- `defaultRoles` stays **empty**; a reserved/wildcard tier there is a hard startup failure.
- If you declare the reserved `settings` permission (tenant realm), the kernel requires it to open your per-site
  settings form.

### 8.1 The `POST` rules

1. **A `GET` never changes anything.** A link or an `<img>` on another web site can make a signed-in user's browser
   send any `GET`; the CSRF defence does not cover `GET`.
2. **Every state change is a `POST` the kernel CSRF-checks.** Operator actions go in a `@ConsoleScreen` controller
   (§7.11) — Spring MVC binds the fields — or, for an action on your admin fragment, a non-anonymous endpoint
   route, which reads the fields with `form()` (§6.3). Either way
   the console page submits a form carrying the session's token:

   ```html
   <!-- .html (markup only) -->
   <form id="mp-run-form" method="post" action="/__admin/myplugin/run">
       <input class="mp-csrf" type="hidden" name="_csrf" value="">
       <button type="submit" id="mp-run">Run</button>
   </form>
   ```
   ```xml
   <!-- .th.xml (logic) -->
   <attr sel=".mp-csrf"
         th:attr="name=${_csrf != null ? _csrf.parameterName : '_csrf'},value=${_csrf != null ? _csrf.token : ''}"/>
   ```
   Keep the `action` a constant in the markup (`th:action`/`th:attr` run in a restricted context that forbids
   `${@bean}`). Answer with a redirect (post/redirect/get) so a reload never repeats the action.
3. **A public form that any visitor may submit without an account** — and that sits on a page every visitor shares,
   so it cannot carry a per-session token — declares an **anonymous** route:
   `Route.anonymousPost("/myplugin/submit", SurfacePosture.safeDefault())`. The kernel exempts exactly that method
   and path from CSRF and takes away what a forged submission could use: the handler runs with **no principal**
   (`AccessCheck.may` is `false`), and only an `application/x-www-form-urlencoded` body is accepted (`415`
   otherwise). Your duties: read fields from `form()` only (personal text never in a URL); keep abuse guards — a
   honeypot field, a length cap, a rate limit keyed by `req.clientIp()`, a CAPTCHA through `CaptchaService` when the
   site has one; answer `303` back with an outcome flag, never a submitted value; declare what you store as personal
   data. **Never** use it for an action on something a signed-in user owns.

---

## 9. Resources — static, templates, admin fragment, i18n

- **Static assets** — `static/`, served at `/__plugin/<id>/static/**`. Reference by that URL; never copied into
  tenants.
- **Templates** — `templates/`, Thymeleaf **decoupled logic**: `<name>.html` with **zero `th:*`** and a sibling
  `<name>.th.xml` with every binding; resolved as `__plugin__/<id>/<name>`. A **repeated `id`** in a `.html` is a hard
  error (a 500); iterated rows use classes and `//` selectors. Fragments (dashboard card, admin fragment) are
  **bare** — a root element, no `<html>`/`<body>`. A fragment may rely on `tenantSiteId` (null on the application
  console), `_csrf`, `${@<own bean>…}`, `${@authz.may('…')}` and `#{…}` — nothing else in the console model.
- **Admin fragment** — a filename convention: ship `templates/admin.html` and the host renders it **below** your
  per-site settings form.
- **i18n** — `i18n/messages[_xx].properties`, appended (add-only) to the console's **and** every site's message
  source. Console labels (menu, settings, permissions) and `#{…}` resolve automatically; public text through
  `Messages.site` (§7.6). Keys live with the plugin; keep every bundle's keys in step.

---

## 10. Capabilities

`Capabilities.of("thing.flag")` from each axis's `capabilities()`. Only host-recognised flags do anything (today
`ContentSource.CAP_INDEXABLE_TEXT`; `CAP_ARBITRARY_PATHS` is reserved); the vocabulary is open. The lifecycle hook is
§7.12.

---

## 11. Build → install → verify

```bash
mvn -o verify                        # JAVA_HOME on a JDK 25 (§2)
#   target/<id>/                       the install-ready FOLDER
#   target/<artifactId>-<version>.zip  the UI-uploadable ARCHIVE (antrun step; build twice → identical)
```

- **Filesystem install:** `cp -r target/<id> <runway-external>/plugins/`, then **restart** the host. To upgrade,
  delete the old `<runway-external>/plugins/<id>/` first: `cp -r` over it keeps files the new version no longer
  has, and the kernel loads **every** `*.jar` in the folder (`plugin.yml` names no jar), so an old jar left beside
  the new one loads too. The console upload replaces the whole folder.
- **Management-UI install:** app console → `/__app/plugins` → install → upload the `.zip` (`plugin.yml` at the root
  or under one wrapping directory); applied on restart.

**Verify against the PACKAGED host jar, never `spring-boot:run`** (DevTools' restart classloader loads ZERO plugins),
in **ACTIVE** mode (plugins are skipped in WIZARD mode). Each plugin ships `scripts/verify-against-host.sh`
(`demo`'s is the model): build offline (never `mvn install`), install into a throwaway deployment
(`VERIFY_TMPDIR`), boot the kernel named by `HOST_JAR`, run the first-run wizard,
reboot ACTIVE, then check — and keep the logs (`VERIFY_LOG_DIR`):

1. **Loads cleanly:** `plugins: N registered, 0 blocked`; **no** `plugin '<id>' linkage scan:` line, no
   `plugin '<id>' is blocked:` line (linkage, `requiresSpi` floor, or `breaks the plugin rules for Runway's shared
   Spring context`), no `QUARANTINED`, no collision (`contribution refused`) or degraded warning.
2. **Serves:** your endpoints, `/__plugin/<id>/static/**`, head injection, widgets on a page, the console screen and
   card as a signed-in user, permission refusals (`403`), `POST`s refused without the CSRF token, an anonymous route
   accepting a form with none, a transfer copy omitting your personal data.

---

## 12. Pitfalls (paid for already)

1. **WIZARD mode = ZERO plugins.** Before first-run setup completes, discovery is skipped entirely.
2. **Never depend on or bundle `runway-app`; never repackage.** Only `runway-spi` (+ the testkit for tests); platform
   libraries `provided`; jar thin. A plugin linking host classes is blocked.
3. **Plugin-scoped services bind by jar.** Inject `PluginSettings`/`PluginSiteFiles`/`ContentChanges`/`PluginInfo`
   in your own top-level classes — not from `lib/`, not with `getBean`.
4. **Bean names start with your id** (lowerCamelCase); no `@EnableConfigurationProperties`; no `@Primary` on collected
   contributions; a colliding or blank `key()` is refused.
5. **No servlet filters, security chains or post-processors** — not part of the plugin contract.
6. **Two config surfaces differ:** app-level = property source, restart-only, your `@ConfigurationProperties`;
   per-site = `PluginSettings`, immediate, `onSettingsChanged`.
7. **Setting-type traps:** `text` isn't trimmed; `decimal` ignores `range`; an unknown `type` is silently `string`.
8. **`POST` rules:** a `GET` changes nothing; state changes are CSRF-checked `POST`s from forms carrying `${_csrf}`
   (§8.1); a non-anonymous endpoint `POST` reads every form field with `form()` (§6.3);
   `Route.anonymousPost` only for public visitor forms, with your own abuse guards.
9. **Announce content changes** with `ContentChanges` after every write — never from a listener callback — and bump
   `updatedAt` when a body changes.
10. **Listeners:** side effects only when `local`; evict on wholesale events; never heavy work on the writer's thread.
11. **Thymeleaf:** decoupled `.th.xml`; no repeated `id`; fragments bare; `id` + `data-widget` on one element; never
    a literal `__plugin__/…` inside `~{…}` (pass it through a variable, e.g. `${master}`).
12. **Menu/card `permission` = full namespaced key**, or the entry is hidden from everyone; handlers still check.
13. **Personal data is declared** (`siteData.personal`), or a transfer copy hands it to an outsider.
14. **Install/upgrade/remove is RESTART-ONLY**; declare `requiresSpi` so an older kernel blocks you with a reason
    instead of failing at the first request.

---

*Grounded in the Runway kernel at SPI `1.0.0` (Runway `1.0.0`: the log lines quoted in §5 and §11 are its
`HostLinkageScanner`, `PluginContextInitializer` and `PluginResolution` texts) and verified against the packaged host
jar. When the kernel
adds or changes a seam, update this guide in the same change.*
