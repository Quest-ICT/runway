# Runway Carousel Manager plugin

An image/slide **carousel** content widget for [Runway](../README.md): configure slides per site, then drop the
carousel onto any page with a `data-widget` anchor. Self-contained CSS + JS, no external libraries.

- **Plugin id:** `carousel-manager` — install folder `external/plugins/carousel-manager/`
- **Java package:** `mx.quest.carousel` · **SPI:** `ContentWidget` (`key = "carousel"`) and the service
  `PluginSettings` — it links **only the SPI**, no kernel class
- **Version 2.1.0** · built against Runway 1.0.0; needs a kernel whose SPI is **1.0.0 or later**
  (`requiresSpi: ">=1.0.0"`; an older kernel blocks it with a reason). What changed: [`CHANGELOG.md`](CHANGELOG.md).

## Use it

1. On the carousel settings page, add slides (one per line): `imageUrl | caption | link`.
2. Put an anchor on a page: `<div id="hero" data-widget="carousel/hero"></div>`.

Settings: auto-advance interval (0 = off), show captions, show controls. See
[`docs/CAROUSEL-PLUGIN.md`](docs/CAROUSEL-PLUGIN.md).

> Note: this plugin does not yet declare a `widgetSettings:` schema, so the host's per-placement widget config
> (`/__admin/widgets`) does not apply to it and every `carousel/*` anchor on a site shows the same configured
> slides (one carousel per site).

## Build

```bash
export JAVA_HOME=/path/to/jdk-25
mvn -q verify   # jar + target/carousel-manager/ + the .zip
HOST_JAR=/path/to/runway-app-<ver>-exec.jar scripts/verify-against-host.sh   # boot a packaged host with it
```

The first build downloads its Maven dependencies, and needs the `runway-spi` jars installed in `~/.m2` as the
[authoring guide §2](../docs/PLUGIN-AUTHORING-GUIDE.md#2-prerequisites) describes.

## License

GNU Affero General Public License v3.0 with an additional permission for plugins (`LICENSE`, `LICENSE-EXCEPTION`),
copyright Quest ICT SAS de CV. The permission means that a plugin which uses only the published SPI may carry any
license.
