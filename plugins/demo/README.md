# runway-demo — the Runway kitchen-sink reference plugin

One plugin that **exercises the whole Runway plugin contract**: every extension axis (head contributor, content
widgets, endpoints including an anonymous form `POST`, a page-quality check, a content source) and **every service
and callback of the SPI service tier** (SPI `1.0.0`: `PluginSettings`, `PluginInfo`/`PluginLifecycle`,
`PluginSiteFiles`, `Sites`, `RequestContext`, `AccessCheck`, `Messages`, `SitePages`, `ContentCatalogView`,
`ContentChanges`, `SiteEventListener`, `@ConsoleScreen`), plus every setting type, personal-data declaration,
permissions, a menu entry, a dashboard card, an admin fragment, static assets, templates and i18n. It links **only
the Runway SPI**. It is the worked reference for the canonical
[authoring guide](../docs/PLUGIN-AUTHORING-GUIDE.md) and the template to copy when starting a new plugin.

- **The walkthrough — every service, what the demo does with it, when you would use it:**
  [`docs/DEMO-PLUGIN.md`](docs/DEMO-PLUGIN.md)
- **How to build any Runway plugin:** [`../docs/PLUGIN-AUTHORING-GUIDE.md`](../docs/PLUGIN-AUTHORING-GUIDE.md)
- **Release history:** [`CHANGELOG.md`](CHANGELOG.md)

## Requirements

A Runway kernel carrying **SPI `1.0.0`** or later: every `1.x` kernel (`requiresSpi: ">=1.0.0"` counts as a 1.x
floor; a kernel whose SPI is below it blocks the plugin at startup with a reason). The build needs
`mx.quest:runway-spi` and `runway-spi-testkit` in `~/.m2`.

## Build

```bash
export JAVA_HOME=<a JDK 25>
mvn verify
```

The first build downloads its Maven dependencies, and needs the `runway-spi` jars installed in `~/.m2` as the
[authoring guide §2](../docs/PLUGIN-AUTHORING-GUIDE.md#2-prerequisites) describes.

Runs 82 unit tests (over the SPI testkit's fakes, plus a scan proving the compiled classes link nothing of the host
but the SPI) and produces the thin `target/runway-plugin-demo.jar` and **both** installable forms:
`target/runway-demo/` (the install-ready folder) and `target/runway-plugin-runway-demo-<version>.zip` (the archive
the management UI upload expects). Two builds produce byte-identical artifacts. The zip is named after the plugin id,
`runway-plugin-<id>-<version>.zip`, like every plugin's; the jar inside it is `runway-plugin-demo.jar`.

## Install (either path, then restart the host)

```bash
cp -r target/runway-demo <runway-external>/plugins/     # 1. filesystem (or scripts/install-into.sh <external>)
# 2. app console → /__app/plugins → install → upload runway-plugin-runway-demo-<version>.zip
```

The plugin loads only once the host is in **ACTIVE** mode (after first-run setup); it is skipped in WIZARD mode.

## Verify (one command)

```bash
HOST_JAR=<runway checkout>/runway-app/target/runway-app-<version>-exec.jar \
JAVA_HOME=/path/to/jdk-25 VERIFY_TMPDIR=<scratch dir> VERIFY_LOG_DIR=<evidence dir> scripts/verify-against-host.sh
```

It builds the plugin (offline, never `mvn install`), drops it into a throwaway Runway deployment, runs the
first-run wizard to reach ACTIVE mode and checks 58 things live — from "zero host-linkage and bean-name warnings"
through a `GET` writing nothing, the anonymous feedback `POST`, the console screen's CSRF-checked actions, the showcase page appearing and
disappearing, to a transfer copy leaving `notes.yml` out. `HOST_JAR` defaults to the host checkout's jar matching
its pom version.

## Try it

- Tenant console → **Runway demo** (needs `runway-demo|manage-notes`; administrators hold it): the demo screen.
  Add a note, install the demo page, publish a showcase, check a path, watch *Recent changes*.
- `/demo` — the demo page (after installing it): the notice widget and the public feedback form.
- `/demo/showcase` — the showcase page, while one is published.
- `GET /demo/hello.txt` — a per-site greeting in the visitor's language.
- `GET /demo/echo.json?q=1` — what the platform tells an endpoint about your request.
- `GET /demo/notes.json` — the notes as JSON, gated by `manage-notes` (`403` without it).
- Any public page's `<head>` — a generator meta and the plugin's stylesheet.
- The plugin's per-site settings page — every setting type, and the admin fragment below the form.

## Starting a new plugin from it

Copy this folder, rename the descriptor `id`, the Maven `artifactId`, the Java package (`mx.quest.demo`) and the
bean-name prefix (`runwayDemo…` → your id in lowerCamelCase), and delete what you do not need. Keep the tests that
check the contract (`DemoPluginConfigTest`, `DemoTemplatesTest`) — they are the cheapest way to stay inside it.

_Java package `mx.quest.demo` · plugin id `runway-demo` · version 2.1.0 · `requiresSpi: ">=1.0.0"`._

## License

Apache License 2.0 (`LICENSE`, `NOTICE`), copyright Quest ICT SAS de CV. You are free to copy this plugin as the
start of your own plugin and to release yours under any terms.
