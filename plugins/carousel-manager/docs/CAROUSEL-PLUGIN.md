# Runway Carousel Manager — design & reference

> The reference for a **`ContentWidget`** plugin with **per-tenant content config** and bundled
> front-end assets. Grounded in the Runway kernel as built.

## 1. What it does

A `ContentWidget` (`key = "carousel"`) that renders an image carousel from the site's per-tenant configuration
wherever a page carries `<div id="…" data-widget="carousel/…"></div>`. It emits self-contained markup that pulls
in its own stylesheet and script (`/__plugin/carousel-manager/static/carousel.{css,js}`); the JS handles
auto-advance, prev/next, and pause-on-hover, and is idempotent so multiple carousels on a page don't double-bind.
With no slides configured the widget renders nothing.

## 2. Configuration (per-tenant, applied immediately)

| Key | Type | Default | Notes |
|---|---|---|---|
| `slides` | **text** | — | One slide per line: `imageUrl \| caption \| link`. `text` (not `list`) so a caption with a comma survives. Image required; caption/link optional. |
| `intervalSeconds` | int (0–60) | `5` | Auto-advance interval; `0` disables auto-play. |
| `showCaptions` | boolean | `true` | Overlay captions on slides. |
| `showControls` | boolean | `true` | Show prev/next controls (only when >1 slide). |

Read live through the SPI service **`PluginSettings.site(siteId)`**, which answers a `SettingsView`: the
descriptor defaults above overlaid by what the site saved. A save applies immediately (the host evicts the cached
view on every node). The code fallbacks equal the descriptor defaults, so the widget renders the same either way;
`intervalSeconds` is clamped to `0..60`; a hand-edited value that does not parse falls back to the default. The
`settings` control point gates the editor. Every emitted value (image URL, caption, link) is HTML-escaped by
`Html.escape`.

## 3. One slide set per site (documented)

The host supports per-placement widget config (a `widgetSettings:` schema in `plugin.yml`, edited per anchor at
`/__admin/widgets`, delivered in `WidgetView.config()`), but this plugin does not declare a schema yet — so
`config()` is empty for it and every `carousel/*` anchor on a site renders the **same** configured slides from the
flat per-tenant `siteSettings` (one carousel per site). Distinct carousels per placement need this plugin to
declare a `widgetSettings:` schema and read `config()`; the widget already threads `instanceId` through for that.

## 4. Inventory

| Piece | Type | File |
|---|---|---|
| `mainClass` `@Configuration` | `CarouselConfig` | wires the widget (`carouselManagerWidget`) + admin model (`carouselManagerAdminModel`), each given the injected `PluginSettings` |
| Widget | `CarouselWidget` (`ContentWidget` `carousel`) | parse slides → render markup |
| Slide model | `Slide` | parse `imageUrl \| caption \| link` |
| Console model | `CarouselAdminModel` + `templates/` | dashboard card + admin status, read as `${@carouselManagerAdminModel.slideCount(tenantSiteId)}` |
| Front-end | `static/carousel.css`, `static/carousel.js`, `static/carousel-help.html` | self-contained assets |
| i18n (EN/ES) | `i18n/` | settings + console labels |

## 5. What it links: the SPI only

The plugin's compiled classes reference nothing of the kernel outside `mx.quest.runway.spi`, which is the contract
the Runway kernel enforces (its loader refuses a non-essential plugin that links host classes). Concretely:

- **Settings:** `mx.quest.runway.spi.service.PluginSettings`, injected as a `@Bean` method parameter of
  `CarouselConfig`. The host binds it to `carousel-manager` by the jar `CarouselConfig` was loaded from — no id is
  passed, so the plugin can only read its own settings.
- **Bean names** start with the plugin id in lowerCamelCase (`carouselManager…`): plugins share the host's bean
  namespace, and the kernel refuses other names.
- **Descriptor:** `requiresSpi: ">=1.0.0"` — the plugin is built against the Runway 1.0.0 SPI. An older kernel
  blocks the plugin with a reason rather than failing to wire it.
- **Build:** `pom.xml` depends on `runway-spi` (and `runway-spi-testkit` for tests), never on `runway-app`, so a
  host import does not compile. `CarouselConfigTest` also scans the compiled classes' constant pools for any
  `mx/quest/runway/` name outside `spi/`, checks every bean name's prefix, and checks that every
  `${@bean…}` the templates use is one of the plugin's own beans.

## 6. Verify

`HOST_JAR=… scripts/verify-against-host.sh` builds the plugin offline (run `mvn verify` once first, so that its
dependencies are in the local Maven repository), boots the packaged host (wizard, then ACTIVE) with it installed,
and asserts:

- it loads — `plugins: 1 registered, 0 blocked` — with **zero** host-linkage findings and zero
  bean-name/surface findings, and no WARN/ERROR line names it;
- it serves its static assets;
- signed in as the site owner, a slide set saved on its settings page is counted on the status fragment and
  on the dashboard card, and renders (both slides, the comma caption intact, the saved interval) on the seeded
  About page, into which the script plants a `data-widget="carousel/hero"` anchor before the ACTIVE boot.

Without `HOST_JAR` it takes the jar matching the host pom's version from the `runway-app/target` of the kernel
this plugin sits in (`HOST_TARGET` names another build directory); `JAVA_HOME` must name a JDK 25 and `MVN` names
Maven (default: `mvn` on the PATH). It never runs `mvn install`. `VERIFY_TMPDIR` sets where the throwaway deployment goes, `VERIFY_LOG_DIR` keeps the host
logs. The widget's rendering rules (parsing, escaping, flags, clamping, defaults, per-site isolation) are covered
by `CarouselWidgetTest`. Never `spring-boot:run`.
